package com.weback.robot.app.pilot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.BuildConfig
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.DefaultAppContainer
import com.weback.robot.core.PilotContainerTestHooks
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.security.InstalledApkVerification
import com.weback.robot.security.ProvisionedAuthorizationLoadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class Fct004CExternalApprovalTrustBoundaryTest {

    private lateinit var context: Context
    private lateinit var authDir: File

    private val commitSha = "77ad87f11e3cf7f0b10f02a42073f2682571ecb0"
    private val apkSha = "c".repeat(64)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        authDir = File(context.noBackupFilesDir, "pilot_external_auth")
        authDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun buildRecordJson(
        authId: String = "FCT004C-EXT-AUTH-001",
        poApproved: Boolean = true,
        leadApproved: Boolean = true,
        account: String = "isolated-pilot@test.invalid",
        thingName: String = "laser_target_01",
        pinnedApi: String? = null,
        pinnedWss: String? = null,
        provenanceRef: String? = null,
        apkDigest: String = apkSha,
        extraSecretField: String = ""
    ): String {
        val pinnedApiJson = pinnedApi?.let { "\"$it\"" } ?: "null"
        val pinnedWssJson = pinnedWss?.let { "\"$it\"" } ?: "null"
        val provRefJson = provenanceRef?.let { "\"$it\"" } ?: "null"
        return """
            {
              "authorization_id": "$authId",
              "authorized_operator": "Operator-One",
              "governance_approval": {
                "product_owner_approved": $poApproved,
                "approved_by_product_owner": "PO-Human",
                "lead_maintainer_approved": $leadApproved,
                "approved_by_lead_maintainer": "Lead-Human",
                "approval_date": "2026-11-26",
                "external_record_ref": "EXT-REC-$authId"
              },
              "artifact_identity": {
                "candidate_commit_sha": "$commitSha",
                "verified_pilot_apk_sha256": "$apkDigest",
                "application_id": "com.weback.robot.pilot",
                "version_name": "${BuildConfig.VERSION_NAME}",
                "version_code": ${BuildConfig.VERSION_CODE}
              },
              "target_account_scope": {
                "scope_type": "DEDICATED_ISOLATED_TEST_ACCOUNT",
                "authorized_account_alias": "i***@test.invalid",
                "authorized_account_identifier": "$account",
                "authorized_region_calling_code": "+1"
              },
              "target_robot_scope": {
                "expected_sub_type_family": "LaserSlam",
                "expected_target_thing_name": "$thingName",
                "redacted_tag": "TARGET_LASER_01"
              },
              "endpoint_trust_scope": {
                "approved_auth_origin": "https://user.grit-cloud.com:443",
                "approved_auth_path": "/prod/oauth",
                "pinned_api_origin": $pinnedApiJson,
                "pinned_wss_origin": $pinnedWssJson,
                "endpoint_provenance_ref": $provRefJson,
                "require_provenance_verification": true
              },
              "permitted_operation_set": {
                "permitted_operations": ["login", "user_thing_list_get"],
                "prohibited_operations": ["ws_telemetry_observe", "send_to_device"]
              },
              "execution_boundaries": {
                "read_only_mode": true,
                "physical_robot_commands_permitted": false,
                "live_map_streaming_permitted": false,
                "max_login_attempts": 1,
                "max_password_retries": 0,
                "max_captcha_automation": 0,
                "max_inventory_requests": 1,
                "max_initial_ws_connections": 0,
                "max_ws_reconnect_attempts": 0,
                "max_outbound_ws_messages": 0,
                "max_physical_robot_commands": 0,
                "http_call_timeout_ms": 15000,
                "max_observation_window_ms": 900000
              },
              "operating_requirements": {
                "immediate_disconnect_button_required": true,
                "flag_secure_enforced": true,
                "zero_credential_persistence": true,
                "offline_device_checklist_passed": true,
                "physical_device_monotonic_expiry_verified": true
              }$extraSecretField
            }
        """.trimIndent()
    }

    private fun writeProvisionedRecord(json: String, companionDigestOverride: String? = null): String {
        authDir.mkdirs()
        val jsonFile = File(authDir, "authorized_pilot_record.json")
        val bytes = json.toByteArray(Charsets.UTF_8)
        jsonFile.writeBytes(bytes)
        val digest = sha256Hex(bytes)
        File(authDir, "authorized_pilot_record.sha256").writeText(companionDigestOverride ?: digest)
        return digest
    }

    private fun newContainer(
        nowMs: Long = 10_000L,
        installedApkVerification: InstalledApkVerification? = InstalledApkVerification(
            supported = true,
            actualApkSha256 = apkSha,
            detail = "TEST_VERIFIED"
        )
    ) = DefaultAppContainer(
        context = context,
        authClock = AuthMonotonicClock { nowMs },
        pilotTestHooks = PilotContainerTestHooks(
            expectedRuntimeIdentityOverride = ExpectedCandidateIdentity(
                candidateCommitSha = commitSha,
                applicationId = "com.weback.robot.pilot",
                versionName = BuildConfig.VERSION_NAME,
                apkSha256 = apkSha
            ),
            installedApkVerificationOverride = installedApkVerification
        )
    )

    @Test
    fun unprovisionedOrDigestMismatchedOrModifiedRecordCannotActivateLiveSession() {
        newContainer().use { container ->
            // 1. Missing provisioned record fails closed (offline build reports disabled/Invalid)
            val initialLoad = container.loadProvisionedLiveAuthorization()
            if (BuildConfig.PILOT_INTERNET_CAPABLE) {
                assertTrue(initialLoad is ProvisionedAuthorizationLoadResult.Missing)
            } else {
                assertTrue(initialLoad is ProvisionedAuthorizationLoadResult.Invalid)
            }
            val missingRes = container.activateLivePilot("a".repeat(64))
            assertTrue("Missing record must reject activation", missingRes is GateActivationResult.Rejected)
            assertFalse(container.pilotAuthorizationGate.isAuthorized)

            // 2. Syntactically valid digest without matching provisioned file fails closed
            val json = buildRecordJson()
            val expectedDigest = writeProvisionedRecord(json)
            val wrongDigestRes = container.activateLivePilot("b".repeat(64))
            assertTrue("Unverified/wrong record digest must reject", wrongDigestRes is GateActivationResult.Rejected)
            assertFalse(container.pilotAuthorizationGate.isAuthorized)

            // 3. Modifying the provisioned record on disk after external approval fails closed
            val modifiedJson = buildRecordJson(account = "attacker@evil.invalid")
            File(authDir, "authorized_pilot_record.json").writeText(modifiedJson)
            val tamperedLoad = container.loadProvisionedLiveAuthorization()
            assertTrue("Modified file vs companion digest must load as Invalid", tamperedLoad is ProvisionedAuthorizationLoadResult.Invalid)
            val tamperedActivate = container.activateLivePilot(expectedDigest)
            assertTrue("Modified record must reject activation", tamperedActivate is GateActivationResult.Rejected)
            assertFalse(container.pilotAuthorizationGate.isAuthorized)
        }
    }

    @Test
    fun rejectsPasswordInRecordMissingProvenanceRefApkMismatchAndReuseAcrossRestart() {
        // 1. Embedded password in authorization record MUST be rejected
        writeProvisionedRecord(buildRecordJson(extraSecretField = """, "pwd": "LeakyPassword1!" """))
        newContainer().use { c ->
            assertTrue(c.loadProvisionedLiveAuthorization() is ProvisionedAuthorizationLoadResult.Invalid)
            val d = sha256Hex(File(authDir, "authorized_pilot_record.json").readBytes())
            assertTrue(c.activateLivePilot(d) is GateActivationResult.Rejected)
        }

        // 2. Pinned origin without endpoint_provenance_ref MUST be rejected
        val unverifiedPinDigest = writeProvisionedRecord(
            buildRecordJson(
                authId = "FCT004C-EXT-PIN-NO-PROV",
                pinnedApi = "https://api-us.grit-cloud.com:443",
                pinnedWss = "wss://wss-us.grit-cloud.com:443",
                provenanceRef = null
            )
        )
        newContainer().use { c ->
            assertTrue(c.loadProvisionedLiveAuthorization() is ProvisionedAuthorizationLoadResult.Invalid)
            assertTrue(c.activateLivePilot(unverifiedPinDigest) is GateActivationResult.Rejected)
        }

        // 3. Installed APK SHA mismatch MUST be rejected when supported
        val validDigest = writeProvisionedRecord(buildRecordJson(authId = "FCT004C-EXT-APK-MISMATCH"))
        newContainer(
            installedApkVerification = InstalledApkVerification(
                supported = true,
                actualApkSha256 = "f".repeat(64),
                detail = "MISMATCH"
            )
        ).use { c ->
            val res = c.activateLivePilot(validDigest)
            assertTrue("APK SHA mismatch must reject activation", res is GateActivationResult.Rejected)
            assertFalse(c.pilotAuthorizationGate.isAuthorized)
        }

        // 4. Valid provisioned record activates ONLY on pilot build, and CANNOT be reused after completion or process restart
        val okDigest = writeProvisionedRecord(buildRecordJson(authId = "FCT004C-EXT-SINGLE-USE"))
        newContainer().use { c ->
            val previewRes = c.loadProvisionedLiveAuthorization()
            if (BuildConfig.PILOT_INTERNET_CAPABLE) {
                assertTrue("Valid provisioned record should load preview, got $previewRes", previewRes is ProvisionedAuthorizationLoadResult.Loaded)
                val preview = (previewRes as ProvisionedAuthorizationLoadResult.Loaded).preview
                assertEquals("i***@test.invalid", preview.accountAliasRedacted)
                assertEquals("TARGET_LASER_01", preview.targetRedactedTag)
            } else {
                assertTrue("Offline build must reject review load", previewRes is ProvisionedAuthorizationLoadResult.Invalid)
            }

            val act = c.activateLivePilot(okDigest)
            if (BuildConfig.PILOT_INTERNET_CAPABLE) {
                assertTrue("Pilot build should activate valid provisioned record, got $act", act is GateActivationResult.Activated)
                assertTrue(c.pilotAuthorizationGate.isAuthorized)
            } else {
                assertTrue("Offline build must still reject live activation", act is GateActivationResult.Rejected)
                assertFalse(c.pilotAuthorizationGate.isAuthorized)
                return
            }
        }

        // Simulate process restart (brand-new DefaultAppContainer on same app noBackupFilesDir)
        newContainer().use { restarted ->
            assertFalse("Restart must always boot OFFLINE_DEFAULT", restarted.pilotAuthorizationGate.isAuthorized)
            assertEquals(PilotModeState.OfflineDefault, restarted.pilotSessionController.pilotModeState.value)
            assertTrue(restarted.loadProvisionedLiveAuthorization() is ProvisionedAuthorizationLoadResult.Missing)

            // Even if the exact same approved record is re-pushed, reuse of authorization_id or digest MUST be rejected
            writeProvisionedRecord(buildRecordJson(authId = "FCT004C-EXT-SINGLE-USE"))
            val replayRes = restarted.activateLivePilot(okDigest)
            assertTrue("Reused authorization record after process restart must be rejected", replayRes is GateActivationResult.Rejected)
            assertFalse(restarted.pilotAuthorizationGate.isAuthorized)
        }
    }
}
