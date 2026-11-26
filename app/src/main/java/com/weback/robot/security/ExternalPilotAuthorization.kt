package com.weback.robot.security

import android.content.Context
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotEnvironment
import com.weback.robot.domain.pilot.PilotExternalApproval
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.PilotRequestBudgets
import com.weback.robot.domain.pilot.PilotTargetDevice
import com.weback.robot.domain.pilot.RuntimeAppIdentity
import com.weback.robot.protocol.pilot.StrictJsonKeyUniquenessValidator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

/**
 * Result of verifying the installed APK artifact SHA-256 against an externally approved record.
 * On real Android devices `applicationInfo.sourceDir` is hashed directly (`supported = true`).
 * In host JVM / Robolectric tests without a packaged APK file, `supported = false` and exact
 * runtime identity (`applicationId`, `versionName`, `versionCode`, `pilotBuildEnabled`) is still enforced.
 */
data class InstalledApkVerification(
    val supported: Boolean,
    val actualApkSha256: String?,
    val detail: String
)

fun interface InstalledApkVerifier {
    fun verifyInstalledApk(): InstalledApkVerification
}

class AndroidInstalledApkVerifier(private val context: Context) : InstalledApkVerifier {
    override fun verifyInstalledApk(): InstalledApkVerification {
        val sourceDir = runCatching { context.applicationInfo?.sourceDir }.getOrNull()
        if (sourceDir.isNullOrBlank()) {
            return InstalledApkVerification(
                supported = false,
                actualApkSha256 = null,
                detail = "UNSUPPORTED_NO_SOURCE_APK_PATH"
            )
        }
        val apkFile = File(sourceDir)
        if (!apkFile.isFile || !apkFile.canRead()) {
            return InstalledApkVerification(
                supported = false,
                actualApkSha256 = null,
                detail = "UNSUPPORTED_SOURCE_APK_NOT_READABLE"
            )
        }
        val digest = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            apkFile.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return InstalledApkVerification(
            supported = false,
            actualApkSha256 = null,
            detail = "UNSUPPORTED_APK_HASH_FAILED"
        )
        return InstalledApkVerification(
            supported = true,
            actualApkSha256 = digest,
            detail = "VERIFIED_INSTALLED_APK_SHA256"
        )
    }
}

/**
 * Read-only summary of an externally provisioned authorization record shown during review.
 * Never exposes passwords, raw account identifiers, or raw target `thing_name`.
 */
data class ProvisionedAuthorizationPreview(
    val authorizationId: String,
    val authorizedOperator: String,
    val approvedByProductOwner: String,
    val approvedByLeadMaintainer: String,
    val approvalDate: String,
    val externalRecordRef: String,
    val candidateCommitSha: String,
    val verifiedPilotApkSha256: String,
    val installedApkVerification: InstalledApkVerification,
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val accountAliasRedacted: String,
    val regionCallingCode: String,
    val targetRedactedTag: String,
    val targetSubTypeFamily: String,
    val approvedAuthOrigin: String,
    val approvedAuthPath: String,
    val pinnedApiOrigin: String?,
    val pinnedWssOrigin: String?,
    val endpointProvenanceRef: String?,
    val maxObservationWindowMs: Long,
    val httpCallTimeoutMs: Long,
    val recordSha256Prefix: String,
    val recordSha256Suffix: String
)

sealed interface ProvisionedAuthorizationLoadResult {
    data class Missing(val safeMessage: String) : ProvisionedAuthorizationLoadResult
    data class Invalid(val safeMessage: String) : ProvisionedAuthorizationLoadResult
    data class Loaded(val preview: ProvisionedAuthorizationPreview) : ProvisionedAuthorizationLoadResult
}

sealed interface ProvisionedActivationPreparation {
    data class Ready(
        val context: PilotAuthorizationContext,
        val recordSha256: String
    ) : ProvisionedActivationPreparation

    data class Rejected(
        val reason: PilotAbortReason,
        val safeMessage: String
    ) : ProvisionedActivationPreparation
}

interface ProvisionedPilotAuthorizationStore {
    fun loadRaw(): Pair<ByteArray, String?>?
    fun isConsumed(authorizationId: String, recordSha256: String): Boolean
    fun markConsumedAndClearPending(authorizationId: String, recordSha256: String)
}

/**
 * App-private file-backed external authorization store (`noBackupFilesDir/pilot_external_auth/`).
 * Stores ONLY non-credential consumed `authorizationId|recordSha256` lines in the consumed ledger
 * so process restarts and completed pilot runs require a fresh externally approved record.
 */
class FileBackedProvisionedPilotAuthorizationStore(
    private val directory: File
) : ProvisionedPilotAuthorizationStore {
    private val lock = Any()
    private val recordFile = File(directory, RECORD_FILE_NAME)
    private val digestFile = File(directory, DIGEST_FILE_NAME)
    private val consumedFile = File(directory, CONSUMED_LEDGER_FILE_NAME)

    override fun loadRaw(): Pair<ByteArray, String?>? = synchronized(lock) {
        if (!recordFile.isFile || !recordFile.canRead()) {
            return null
        }
        val bytes = runCatching { recordFile.readBytes() }.getOrNull() ?: return null
        val companion = if (digestFile.isFile && digestFile.canRead()) {
            runCatching { digestFile.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        bytes to companion
    }

    override fun isConsumed(authorizationId: String, recordSha256: String): Boolean = synchronized(lock) {
        if (!consumedFile.isFile || !consumedFile.canRead()) return false
        val normAuth = authorizationId.trim()
        val normSha = recordSha256.trim().lowercase()
        val lines = runCatching { consumedFile.readLines(Charsets.UTF_8) }.getOrNull() ?: return true
        lines.any { line ->
            val parts = line.split('|', limit = 2)
            parts.size == 2 && (parts[0] == normAuth || parts[1].lowercase() == normSha)
        }
    }

    override fun markConsumedAndClearPending(authorizationId: String, recordSha256: String) {
        synchronized(lock) {
            directory.mkdirs()
            val normAuth = authorizationId.trim()
            val normSha = recordSha256.trim().lowercase()
            if (!isConsumed(normAuth, normSha)) {
                consumedFile.appendText("$normAuth|$normSha\n", Charsets.UTF_8)
            }
            if (recordFile.exists()) recordFile.delete()
            if (digestFile.exists()) digestFile.delete()
        }
    }

    companion object {
        const val DIRECTORY_NAME = "pilot_external_auth"
        const val RECORD_FILE_NAME = "authorized_pilot_record.json"
        const val DIGEST_FILE_NAME = "authorized_pilot_record.sha256"
        const val CONSUMED_LEDGER_FILE_NAME = "consumed_records.txt"
    }
}

class InMemoryProvisionedPilotAuthorizationStore : ProvisionedPilotAuthorizationStore {
    private val lock = Any()
    private var pendingBytes: ByteArray? = null
    private var pendingDigest: String? = null
    private val consumedIds = mutableSetOf<String>()
    private val consumedDigests = mutableSetOf<String>()

    fun provision(rawJson: String, companionSha256: String? = null) {
        synchronized(lock) {
            pendingBytes = rawJson.toByteArray(Charsets.UTF_8)
            pendingDigest = companionSha256
        }
    }

    fun clearPending() {
        synchronized(lock) {
            pendingBytes = null
            pendingDigest = null
        }
    }

    override fun loadRaw(): Pair<ByteArray, String?>? = synchronized(lock) {
        pendingBytes?.let { it.copyOf() to pendingDigest }
    }

    override fun isConsumed(authorizationId: String, recordSha256: String): Boolean = synchronized(lock) {
        authorizationId.trim() in consumedIds || recordSha256.trim().lowercase() in consumedDigests
    }

    override fun markConsumedAndClearPending(authorizationId: String, recordSha256: String) {
        synchronized(lock) {
            consumedIds.add(authorizationId.trim())
            consumedDigests.add(recordSha256.trim().lowercase())
            pendingBytes = null
            pendingDigest = null
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object ExternalPilotAuthorizationLoader {

    private val strictJson = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    private val hex40Regex = Regex("^[0-9a-fA-F]{40}$")
    private val hex64Regex = Regex("^[0-9a-fA-F]{64}$")
    private val forbiddenSecretKeys = setOf(
        "pwd",
        "password",
        "passphrase",
        "secret",
        "jwt_token",
        "token",
        "access_token",
        "refresh_token",
        "private_key"
    )

    @Serializable
    private data class ExternalPilotAuthorizationJson(
        @SerialName("authorization_id") val authorizationId: String,
        @SerialName("authorized_operator") val authorizedOperator: String,
        @SerialName("governance_approval") val governanceApproval: GovernanceApprovalJson,
        @SerialName("artifact_identity") val artifactIdentity: ArtifactIdentityJson,
        @SerialName("target_account_scope") val targetAccountScope: TargetAccountScopeJson,
        @SerialName("target_robot_scope") val targetRobotScope: TargetRobotScopeJson,
        @SerialName("endpoint_trust_scope") val endpointTrustScope: EndpointTrustScopeJson,
        @SerialName("permitted_operation_set") val permittedOperationSet: PermittedOperationSetJson,
        @SerialName("execution_boundaries") val executionBoundaries: ExecutionBoundariesJson,
        @SerialName("operating_requirements") val operatingRequirements: OperatingRequirementsJson
    )

    @Serializable
    private data class GovernanceApprovalJson(
        @SerialName("product_owner_approved") val productOwnerApproved: Boolean,
        @SerialName("approved_by_product_owner") val approvedByProductOwner: String,
        @SerialName("lead_maintainer_approved") val leadMaintainerApproved: Boolean,
        @SerialName("approved_by_lead_maintainer") val approvedByLeadMaintainer: String,
        @SerialName("approval_date") val approvalDate: String,
        @SerialName("external_record_ref") val externalRecordRef: String
    )

    @Serializable
    private data class ArtifactIdentityJson(
        @SerialName("candidate_commit_sha") val candidateCommitSha: String,
        @SerialName("verified_pilot_apk_sha256") val verifiedPilotApkSha256: String,
        @SerialName("application_id") val applicationId: String,
        @SerialName("version_name") val versionName: String,
        @SerialName("version_code") val versionCode: Int
    )

    @Serializable
    private data class TargetAccountScopeJson(
        @SerialName("scope_type") val scopeType: String,
        @SerialName("authorized_account_alias") val authorizedAccountAlias: String,
        @SerialName("authorized_account_identifier") val authorizedAccountIdentifier: String,
        @SerialName("authorized_region_calling_code") val authorizedRegionCallingCode: String
    )

    @Serializable
    private data class TargetRobotScopeJson(
        @SerialName("expected_sub_type_family") val expectedSubTypeFamily: String,
        @SerialName("expected_target_thing_name") val expectedTargetThingName: String,
        @SerialName("redacted_tag") val redactedTag: String
    )

    @Serializable
    private data class EndpointTrustScopeJson(
        @SerialName("approved_auth_origin") val approvedAuthOrigin: String,
        @SerialName("approved_auth_path") val approvedAuthPath: String,
        @SerialName("pinned_api_origin") val pinnedApiOrigin: String? = null,
        @SerialName("pinned_wss_origin") val pinnedWssOrigin: String? = null,
        @SerialName("endpoint_provenance_ref") val endpointProvenanceRef: String? = null,
        @SerialName("require_provenance_verification") val requireProvenanceVerification: Boolean = true
    )

    @Serializable
    private data class PermittedOperationSetJson(
        @SerialName("permitted_operations") val permittedOperations: List<String>,
        @SerialName("prohibited_operations") val prohibitedOperations: List<String>
    )

    @Serializable
    private data class ExecutionBoundariesJson(
        @SerialName("read_only_mode") val readOnlyMode: Boolean,
        @SerialName("physical_robot_commands_permitted") val physicalRobotCommandsPermitted: Boolean,
        @SerialName("live_map_streaming_permitted") val liveMapStreamingPermitted: Boolean,
        @SerialName("max_login_attempts") val maxLoginAttempts: Int,
        @SerialName("max_password_retries") val maxPasswordRetries: Int,
        @SerialName("max_captcha_automation") val maxCaptchaAutomation: Int,
        @SerialName("max_inventory_requests") val maxInventoryRequests: Int,
        @SerialName("max_initial_ws_connections") val maxInitialWsConnections: Int,
        @SerialName("max_ws_reconnect_attempts") val maxWsReconnectAttempts: Int,
        @SerialName("max_outbound_ws_messages") val maxOutboundWsMessages: Int,
        @SerialName("max_physical_robot_commands") val maxPhysicalRobotCommands: Int,
        @SerialName("http_call_timeout_ms") val httpCallTimeoutMs: Long,
        @SerialName("max_observation_window_ms") val maxObservationWindowMs: Long
    )

    @Serializable
    private data class OperatingRequirementsJson(
        @SerialName("immediate_disconnect_button_required") val immediateDisconnectButtonRequired: Boolean,
        @SerialName("flag_secure_enforced") val flagSecureEnforced: Boolean,
        @SerialName("zero_credential_persistence") val zeroCredentialPersistence: Boolean,
        @SerialName("offline_device_checklist_passed") val offlineDeviceChecklistPassed: Boolean,
        @SerialName("physical_device_monotonic_expiry_verified") val physicalDeviceMonotonicExpiryVerified: Boolean
    )

    fun loadForReview(
        store: ProvisionedPilotAuthorizationStore,
        runtimeAppIdentity: RuntimeAppIdentity,
        expectedRuntimeIdentity: ExpectedCandidateIdentity?,
        installedApkVerifier: InstalledApkVerifier,
        allowedAuthOrigins: Set<String>
    ): ProvisionedAuthorizationLoadResult {
        if (!runtimeAppIdentity.pilotBuildEnabled) {
            return ProvisionedAuthorizationLoadResult.Invalid("Live pilot authorization is disabled on offline build")
        }
        val rawPair = store.loadRaw()
            ?: return ProvisionedAuthorizationLoadResult.Missing(
                "No externally provisioned authorization record found. Provision authorized_pilot_record.json out-of-band before review."
            )
        val parsed = parseAndValidate(
            rawBytes = rawPair.first,
            companionSha256Raw = rawPair.second,
            store = store,
            runtimeAppIdentity = runtimeAppIdentity,
            expectedRuntimeIdentity = expectedRuntimeIdentity,
            installedApkVerifier = installedApkVerifier,
            allowedAuthOrigins = allowedAuthOrigins
        )
        return when (parsed) {
            is InternalParseResult.Valid -> ProvisionedAuthorizationLoadResult.Loaded(parsed.preview)
            is InternalParseResult.Error -> ProvisionedAuthorizationLoadResult.Invalid(parsed.safeMessage)
        }
    }

    fun prepareActivation(
        store: ProvisionedPilotAuthorizationStore,
        expectedRecordSha256: String,
        runtimeAppIdentity: RuntimeAppIdentity,
        expectedRuntimeIdentity: ExpectedCandidateIdentity?,
        installedApkVerifier: InstalledApkVerifier,
        allowedAuthOrigins: Set<String>,
        nowElapsedMs: Long
    ): ProvisionedActivationPreparation {
        if (!runtimeAppIdentity.pilotBuildEnabled) {
            return ProvisionedActivationPreparation.Rejected(
                PilotAbortReason.AuthorizationExpiredOrInvalid,
                "Live pilot authorization is disabled on offline build"
            )
        }
        val normalizedExpectedSha = expectedRecordSha256.trim().lowercase()
        if (!hex64Regex.matches(normalizedExpectedSha)) {
            return ProvisionedActivationPreparation.Rejected(
                PilotAbortReason.AuthorizationExpiredOrInvalid,
                "Expected approval record SHA-256 must be a valid 64-hex digest"
            )
        }
        val rawPair = store.loadRaw()
            ?: return ProvisionedActivationPreparation.Rejected(
                PilotAbortReason.AuthorizationExpiredOrInvalid,
                "No externally provisioned authorization record found"
            )
        return when (
            val parsed = parseAndValidate(
                rawBytes = rawPair.first,
                companionSha256Raw = rawPair.second,
                store = store,
                runtimeAppIdentity = runtimeAppIdentity,
                expectedRuntimeIdentity = expectedRuntimeIdentity,
                installedApkVerifier = installedApkVerifier,
                allowedAuthOrigins = allowedAuthOrigins
            )
        ) {
            is InternalParseResult.Error -> ProvisionedActivationPreparation.Rejected(
                PilotAbortReason.AuthorizationExpiredOrInvalid,
                parsed.safeMessage
            )
            is InternalParseResult.Valid -> {
                if (!constantTimeHexEquals(parsed.recordSha256, normalizedExpectedSha)) {
                    ProvisionedActivationPreparation.Rejected(
                        PilotAbortReason.AuthorizationExpiredOrInvalid,
                        "Provisioned authorization record SHA-256 mismatches confirmed external approval digest"
                    )
                } else {
                    val rec = parsed.record
                    val windowMs = rec.executionBoundaries.maxObservationWindowMs
                    val ctx = PilotAuthorizationContext(
                        authorizationId = rec.authorizationId.trim(),
                        candidateIdentity = ExpectedCandidateIdentity(
                            candidateCommitSha = rec.artifactIdentity.candidateCommitSha.trim().lowercase(),
                            applicationId = rec.artifactIdentity.applicationId.trim(),
                            versionName = rec.artifactIdentity.versionName.trim(),
                            apkSha256 = rec.artifactIdentity.verifiedPilotApkSha256.trim().lowercase()
                        ),
                        accountScope = PilotAccountScope(
                            scopeType = PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
                            regionCallingCode = rec.targetAccountScope.authorizedRegionCallingCode.trim(),
                            authorizedAccountAlias = rec.targetAccountScope.authorizedAccountAlias.trim(),
                            authorizedAccountIdentifier = PilotAccountScope.normalizeAccount(
                                rec.targetAccountScope.authorizedAccountIdentifier
                            )
                        ),
                        targetDevice = PilotTargetDevice(
                            expectedSubTypeFamily = rec.targetRobotScope.expectedSubTypeFamily.trim(),
                            expectedTargetThingName = rec.targetRobotScope.expectedTargetThingName.trim(),
                            redactedTag = rec.targetRobotScope.redactedTag.trim()
                        ),
                        permittedOperations = setOf(
                            PilotOperation.LOGIN,
                            PilotOperation.USER_THING_LIST_GET
                        ),
                        endpointConfig = PilotEndpointConfig(
                            approvedAuthOrigin = rec.endpointTrustScope.approvedAuthOrigin.trim(),
                            approvedAuthPath = rec.endpointTrustScope.approvedAuthPath.trim(),
                            pinnedApiOrigin = rec.endpointTrustScope.pinnedApiOrigin?.trim()?.takeIf { it.isNotEmpty() },
                            pinnedWssOrigin = rec.endpointTrustScope.pinnedWssOrigin?.trim()?.takeIf { it.isNotEmpty() },
                            requireProvenanceVerification = true,
                            endpointProvenanceRef = rec.endpointTrustScope.endpointProvenanceRef?.trim()?.takeIf { it.isNotEmpty() }
                        ),
                        readOnlyMode = true,
                        physicalRobotCommandsPermitted = false,
                        budgets = PilotRequestBudgets.strictAuthAndInventoryOnly(
                            observationWindowMs = windowMs,
                            httpCallTimeoutMs = rec.executionBoundaries.httpCallTimeoutMs
                        ),
                        authorizedAtElapsedMs = nowElapsedMs,
                        observationDeadlineElapsedMs = nowElapsedMs + windowMs,
                        externalApproval = PilotExternalApproval(
                            approvedByProductOwner = rec.governanceApproval.approvedByProductOwner.trim(),
                            approvedByLeadMaintainer = rec.governanceApproval.approvedByLeadMaintainer.trim(),
                            externalRecordRef = rec.governanceApproval.externalRecordRef.trim()
                        ),
                        uiConsentCheckedOnly = false,
                        environment = PilotEnvironment.LIVE_PILOT
                    )
                    ProvisionedActivationPreparation.Ready(
                        context = ctx,
                        recordSha256 = parsed.recordSha256
                    )
                }
            }
        }
    }

    private sealed interface InternalParseResult {
        data class Valid(
            val record: ExternalPilotAuthorizationJson,
            val preview: ProvisionedAuthorizationPreview,
            val recordSha256: String
        ) : InternalParseResult

        data class Error(val safeMessage: String) : InternalParseResult
    }

    private fun parseAndValidate(
        rawBytes: ByteArray,
        companionSha256Raw: String?,
        store: ProvisionedPilotAuthorizationStore,
        runtimeAppIdentity: RuntimeAppIdentity,
        expectedRuntimeIdentity: ExpectedCandidateIdentity?,
        installedApkVerifier: InstalledApkVerifier,
        allowedAuthOrigins: Set<String>
    ): InternalParseResult {
        if (rawBytes.isEmpty() || rawBytes.size > 65_536) {
            return InternalParseResult.Error("Provisioned authorization record is empty or exceeds 64 KiB limit")
        }
        val actualSha256 = sha256Hex(rawBytes)
        if (companionSha256Raw != null) {
            val normalizedCompanion = extractCompanionHex(companionSha256Raw)
                ?: return InternalParseResult.Error("Companion SHA-256 digest file format is invalid")
            if (!constantTimeHexEquals(actualSha256, normalizedCompanion)) {
                return InternalParseResult.Error("Provisioned authorization record digest mismatch (record modified)")
            }
        }

        val jsonText = rawBytes.toString(Charsets.UTF_8)
        if (!StrictJsonKeyUniquenessValidator.hasUniqueObjectKeys(jsonText)) {
            return InternalParseResult.Error("Provisioned authorization record failed strict JSON uniqueness check")
        }
        val rootElem = runCatching { strictJson.parseToJsonElement(jsonText) }.getOrNull()
            ?: return InternalParseResult.Error("Provisioned authorization record is malformed JSON")
        if (containsForbiddenSecretKey(rootElem)) {
            return InternalParseResult.Error("Provisioned authorization record MUST NOT contain passwords or tokens")
        }

        val rec = runCatching { strictJson.decodeFromJsonElement(ExternalPilotAuthorizationJson.serializer(), rootElem) }
            .getOrElse {
                return InternalParseResult.Error("Provisioned authorization record schema invalid or has unexpected fields")
            }

        if (store.isConsumed(rec.authorizationId, actualSha256)) {
            return InternalParseResult.Error("Authorization ID or record digest already consumed; fresh external approval required")
        }

        val gov = rec.governanceApproval
        if (!gov.productOwnerApproved || !gov.leadMaintainerApproved) {
            return InternalParseResult.Error("External dual human approval flags must both be true")
        }
        val req = rec.operatingRequirements
        if (!req.immediateDisconnectButtonRequired ||
            !req.flagSecureEnforced ||
            !req.zeroCredentialPersistence ||
            !req.offlineDeviceChecklistPassed ||
            !req.physicalDeviceMonotonicExpiryVerified
        ) {
            return InternalParseResult.Error("Mandatory operating requirements and pre-live device readiness flags must all be true")
        }

        val requiredTextFields = listOf(
            rec.authorizationId,
            rec.authorizedOperator,
            gov.approvedByProductOwner,
            gov.approvedByLeadMaintainer,
            gov.approvalDate,
            gov.externalRecordRef,
            rec.targetAccountScope.authorizedAccountIdentifier,
            rec.targetAccountScope.authorizedAccountAlias,
            rec.targetRobotScope.expectedTargetThingName,
            rec.targetRobotScope.redactedTag
        )
        if (requiredTextFields.any { PilotAuthorizationGate.isPlaceholderOrMockToken(it) }) {
            return InternalParseResult.Error("Provisioned authorization record contains blank or placeholder governance/scope values")
        }

        val art = rec.artifactIdentity
        if (!hex40Regex.matches(art.candidateCommitSha.trim()) ||
            art.candidateCommitSha.trim() == "0".repeat(40) ||
            !hex64Regex.matches(art.verifiedPilotApkSha256.trim()) ||
            art.verifiedPilotApkSha256.trim() == "0".repeat(64)
        ) {
            return InternalParseResult.Error("Invalid or zero candidate commit SHA or verified APK SHA-256")
        }
        if (art.applicationId.trim() != runtimeAppIdentity.applicationId ||
            art.versionName.trim() != runtimeAppIdentity.versionName ||
            art.versionCode != runtimeAppIdentity.versionCode
        ) {
            return InternalParseResult.Error("Provisioned artifact identity mismatches runtime application identity")
        }
        if (expectedRuntimeIdentity != null) {
            if (art.candidateCommitSha.trim().lowercase() != expectedRuntimeIdentity.candidateCommitSha.lowercase() ||
                art.verifiedPilotApkSha256.trim().lowercase() != expectedRuntimeIdentity.apkSha256.lowercase() ||
                art.applicationId.trim() != expectedRuntimeIdentity.applicationId ||
                art.versionName.trim() != expectedRuntimeIdentity.versionName
            ) {
                return InternalParseResult.Error("Provisioned artifact identity mismatches expected candidate identity")
            }
        }
        val installedVerification = installedApkVerifier.verifyInstalledApk()
        if (installedVerification.supported) {
            val actualApk = installedVerification.actualApkSha256?.trim()?.lowercase()
            if (actualApk == null || !constantTimeHexEquals(actualApk, art.verifiedPilotApkSha256.trim().lowercase())) {
                return InternalParseResult.Error("Installed APK SHA-256 mismatches externally approved artifact")
            }
        }

        if (rec.targetAccountScope.scopeType.trim() != "DEDICATED_ISOLATED_TEST_ACCOUNT") {
            return InternalParseResult.Error("Target account scope must be DEDICATED_ISOLATED_TEST_ACCOUNT")
        }
        if (rec.targetRobotScope.expectedSubTypeFamily.trim() != PilotAuthorizationGate.REQUIRED_SUB_TYPE_FAMILY) {
            return InternalParseResult.Error("Target robot family must be LaserSlam")
        }

        val ops = rec.permittedOperationSet
        val permittedNorm = ops.permittedOperations.map { it.trim() }.toSet()
        val prohibitedNorm = ops.prohibitedOperations.map { it.trim() }.toSet()
        if (permittedNorm.isEmpty() ||
            !permittedNorm.all { it == "login" || it == "user_thing_list_get" } ||
            "ws_telemetry_observe" !in prohibitedNorm ||
            "send_to_device" !in prohibitedNorm
        ) {
            return InternalParseResult.Error("Permitted operation set violates FCT-004C zero-WS auth/inventory scope")
        }

        val b = rec.executionBoundaries
        if (!b.readOnlyMode ||
            b.physicalRobotCommandsPermitted ||
            b.liveMapStreamingPermitted ||
            b.maxLoginAttempts != 1 ||
            b.maxPasswordRetries != 0 ||
            b.maxCaptchaAutomation != 0 ||
            b.maxInventoryRequests != 1 ||
            b.maxInitialWsConnections != 0 ||
            b.maxWsReconnectAttempts != 0 ||
            b.maxOutboundWsMessages != 0 ||
            b.maxPhysicalRobotCommands != 0 ||
            b.httpCallTimeoutMs !in 1L..PilotRequestBudgets.MAX_HTTP_CALL_TIMEOUT_MS ||
            b.maxObservationWindowMs !in 1L..PilotRequestBudgets.MAX_AUTH_INVENTORY_WINDOW_MS
        ) {
            return InternalParseResult.Error("Execution boundaries violate FCT-004C safety limits")
        }

        val ep = rec.endpointTrustScope
        if (!ep.requireProvenanceVerification ||
            ep.approvedAuthPath.trim() != PilotAuthorizationGate.REQUIRED_AUTH_PATH ||
            ep.approvedAuthOrigin.trim() !in allowedAuthOrigins
        ) {
            return InternalParseResult.Error("Approved auth origin/path or provenance verification flag invalid")
        }
        val hasPinnedApi = !ep.pinnedApiOrigin.isNullOrBlank()
        val hasPinnedWss = !ep.pinnedWssOrigin.isNullOrBlank()
        if (hasPinnedApi || hasPinnedWss) {
            val provRef = ep.endpointProvenanceRef?.trim().orEmpty()
            if (PilotAuthorizationGate.isPlaceholderOrMockToken(provRef) ||
                provRef.equals("UI_CONFIRMED", ignoreCase = true)
            ) {
                return InternalParseResult.Error(
                    "Pinned API/WSS origins require an independently reviewed endpoint_provenance_ref"
                )
            }
        }

        val preview = ProvisionedAuthorizationPreview(
            authorizationId = rec.authorizationId.trim(),
            authorizedOperator = rec.authorizedOperator.trim(),
            approvedByProductOwner = gov.approvedByProductOwner.trim(),
            approvedByLeadMaintainer = gov.approvedByLeadMaintainer.trim(),
            approvalDate = gov.approvalDate.trim(),
            externalRecordRef = gov.externalRecordRef.trim(),
            candidateCommitSha = art.candidateCommitSha.trim().lowercase(),
            verifiedPilotApkSha256 = art.verifiedPilotApkSha256.trim().lowercase(),
            installedApkVerification = installedVerification,
            applicationId = art.applicationId.trim(),
            versionName = art.versionName.trim(),
            versionCode = art.versionCode,
            accountAliasRedacted = rec.targetAccountScope.authorizedAccountAlias.trim(),
            regionCallingCode = rec.targetAccountScope.authorizedRegionCallingCode.trim(),
            targetRedactedTag = rec.targetRobotScope.redactedTag.trim(),
            targetSubTypeFamily = rec.targetRobotScope.expectedSubTypeFamily.trim(),
            approvedAuthOrigin = ep.approvedAuthOrigin.trim(),
            approvedAuthPath = ep.approvedAuthPath.trim(),
            pinnedApiOrigin = ep.pinnedApiOrigin?.trim()?.takeIf { it.isNotEmpty() },
            pinnedWssOrigin = ep.pinnedWssOrigin?.trim()?.takeIf { it.isNotEmpty() },
            endpointProvenanceRef = ep.endpointProvenanceRef?.trim()?.takeIf { it.isNotEmpty() },
            maxObservationWindowMs = b.maxObservationWindowMs,
            httpCallTimeoutMs = b.httpCallTimeoutMs,
            recordSha256Prefix = actualSha256.take(8),
            recordSha256Suffix = actualSha256.takeLast(8)
        )
        return InternalParseResult.Valid(rec, preview, actualSha256)
    }

    private fun containsForbiddenSecretKey(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.entries.any { (key, value) ->
            key.trim().lowercase() in forbiddenSecretKeys || containsForbiddenSecretKey(value)
        }
        is JsonArray -> element.any { containsForbiddenSecretKey(it) }
        else -> false
    }

    private fun extractCompanionHex(raw: String): String? {
        val token = raw.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
        return if (hex64Regex.matches(token)) token else null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun constantTimeHexEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
}
