package com.weback.robot.pilot

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weback.robot.BuildConfig
import com.weback.robot.MainActivity
import com.weback.robot.WeBackApplication
import com.weback.robot.core.DefaultAppContainer
import com.weback.robot.core.PilotContainerTestHooks
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.security.AndroidInstalledApkVerifier
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.FileBackedProvisionedPilotAuthorizationStore
import com.weback.robot.security.InMemoryProvisionedPilotAuthorizationStore
import com.weback.robot.security.InstalledApkVerification
import com.weback.robot.security.ProvisionedAuthorizationLoadResult
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.pilot.PilotEndpointConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Hermetic pilot-targeted device acceptance instrumentation (`FCT-004C` / `TEST-PILOT-469` Part B).
 *
 * Strictly zero production DNS queries, zero real credentials, zero real live network requests, and
 * zero fabricated production readiness flags. Verifies:
 * 1. Unmodified production container verifies the real installed `sourceDir` APK SHA-256 and fails
 *    closed on missing, malformed, and unapproved records (including `offline_device_checklist_passed=false`
 *    or `physical_device_monotonic_expiry_verified=false`).
 * 2. Isolated hermetic UI harness verifies Live vs Mock credential UI separation, `FLAG_SECURE` during
 *    sensitive review and `AuthorizedReady`, Back navigation preservation, `ActivityScenario.recreate()`
 *    live UI ownership, and Close revocation + cleanup.
 */
@RunWith(AndroidJUnit4::class)
class PilotDeviceAcceptanceInstrumentationTest {

    private val hex64Regex = Regex("^[0-9a-f]{64}$")

    @Test
    fun unmodifiedProductionContainerVerifiesInstalledApkAndFailsClosedWithoutFakeReadinessFlags() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val authDir = File(app.noBackupFilesDir, FileBackedProvisionedPilotAuthorizationStore.DIRECTORY_NAME)
        authDir.deleteRecursively()

        try {
            val apkVerification = AndroidInstalledApkVerifier(app).verifyInstalledApk()
            assertTrue("Installed pilot APK verification must be supported on Android", apkVerification.supported)
            val realApkSha256 = requireNotNull(apkVerification.actualApkSha256)
            assertTrue(hex64Regex.matches(realApkSha256))

            val productionContainer = DefaultAppContainer(context = app)
            try {
                // 1. Missing record fails closed
                assertTrue(productionContainer.loadProvisionedLiveAuthorization() is ProvisionedAuthorizationLoadResult.Missing)
                assertTrue(productionContainer.activateLivePilot("a".repeat(64)) is GateActivationResult.Rejected)
                assertFalse(productionContainer.pilotAuthorizationGate.isAuthorized)

                // 2. Malformed JSON fails closed
                authDir.mkdirs()
                val malformedBytes = "{ malformed json".toByteArray(Charsets.UTF_8)
                File(authDir, FileBackedProvisionedPilotAuthorizationStore.RECORD_FILE_NAME)
                    .writeBytes(malformedBytes)
                File(authDir, FileBackedProvisionedPilotAuthorizationStore.DIGEST_FILE_NAME)
                    .writeText(sha256Hex(malformedBytes))
                assertTrue(productionContainer.loadProvisionedLiveAuthorization() is ProvisionedAuthorizationLoadResult.Invalid)
                assertTrue(productionContainer.activateLivePilot(sha256Hex(malformedBytes)) is GateActivationResult.Rejected)
                assertFalse(productionContainer.pilotAuthorizationGate.isAuthorized)

                // 3. Unapproved record (offline checklist & physical expiry false — not fabricated) fails closed
                val unapprovedJson = buildSyntheticRecordJson(
                    authId = "AUTH-INST-UNAPPROVED-001",
                    apkSha256 = realApkSha256,
                    offlineChecklistPassed = false,
                    physicalExpiryVerified = false
                )
                val unapprovedBytes = unapprovedJson.toByteArray(Charsets.UTF_8)
                val unapprovedDigest = sha256Hex(unapprovedBytes)
                File(authDir, FileBackedProvisionedPilotAuthorizationStore.RECORD_FILE_NAME)
                    .writeBytes(unapprovedBytes)
                File(authDir, FileBackedProvisionedPilotAuthorizationStore.DIGEST_FILE_NAME)
                    .writeText(unapprovedDigest)
                assertTrue(productionContainer.loadProvisionedLiveAuthorization() is ProvisionedAuthorizationLoadResult.Invalid)
                assertTrue(productionContainer.activateLivePilot(unapprovedDigest) is GateActivationResult.Rejected)
                assertFalse(productionContainer.pilotAuthorizationGate.isAuthorized)
            } finally {
                productionContainer.close()
            }
        } finally {
            authDir.deleteRecursively()
        }
    }

    @Test
    fun hermeticLiveUiVerifiesLabelsFlagSecureBackRecreationAndCloseRevocation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<WeBackApplication>()
        val apkVerification = AndroidInstalledApkVerifier(app).verifyInstalledApk()
        assertTrue(apkVerification.supported)
        val realApkSha256 = requireNotNull(apkVerification.actualApkSha256)

        val isolatedStore = InMemoryProvisionedPilotAuthorizationStore()
        val syntheticJson = buildSyntheticRecordJson(
            authId = "AUTH-INST-HERMETIC-001",
            apkSha256 = realApkSha256,
            offlineChecklistPassed = true,
            physicalExpiryVerified = true
        )
        val syntheticDigest = sha256Hex(syntheticJson.toByteArray(Charsets.UTF_8))
        isolatedStore.provision(syntheticJson, syntheticDigest)

        val isolatedTokenStore = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
            override suspend fun load() = TokenLoadResult.Empty
            override suspend fun clear() = TokenClearResult.Success
        }

        val testContainer = DefaultAppContainer(
            context = app,
            tokenStoreOverride = isolatedTokenStore,
            pilotTestHooks = PilotContainerTestHooks(
                trustPolicyOverride = AuthorizedPilotEndpointTrustPolicy(
                    endpointConfig = PilotEndpointConfig(
                        approvedAuthOrigin = "https://loopback.offline.invalid:443",
                        approvedAuthPath = "/prod/oauth",
                        requireProvenanceVerification = true
                    ),
                    allowLoopbackForHermeticTests = true
                ),
                allowedAuthOriginsOverride = setOf("https://loopback.offline.invalid:443"),
                expectedRuntimeIdentityOverride = ExpectedCandidateIdentity(
                    candidateCommitSha = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                    applicationId = BuildConfig.APPLICATION_ID,
                    versionName = BuildConfig.VERSION_NAME,
                    apkSha256 = realApkSha256
                ),
                installedApkVerificationOverride = InstalledApkVerification(
                    supported = true,
                    actualApkSha256 = realApkSha256,
                    detail = "VERIFIED_INSTALLED_APK_SHA256"
                ),
                provisionedAuthStoreOverride = isolatedStore
            )
        )

        app.replaceContainerForTesting(testContainer)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.RESUMED)

                // Open Pilot Readiness sheet
                waitAndClickTag(scenario, "btn_open_pilot_readiness")
                waitForTag(scenario, "btn_start_mock_pilot")

                // Verify Mock mode displays clearly labeled synthetic credentials
                waitAndClickTag(scenario, "btn_start_mock_pilot")
                waitForTextContains(scenario, "Synthetic Credential Entry (Offline Mock Fixture, No Network)")
                waitForTextContains(scenario, "Synthetic Password")
                waitAndClickTag(scenario, "btn_pilot_emergency_disconnect")

                // Open Live Authorization Review -> verify FLAG_SECURE set
                waitAndClickTag(scenario, "btn_open_live_auth_review")
                waitForTag(scenario, "live_auth_review_panel")
                assertTrue(
                    "FLAG_SECURE must be set during sensitive live authorization review",
                    hasFlagSecure(scenario)
                )

                // Confirm digest & activate AuthorizedReady -> verify Live UI labels & FLAG_SECURE
                setTextByTag(scenario, "live_auth_input_record_sha256", syntheticDigest)
                waitAndClickTag(scenario, "btn_validate_live_auth")
                waitForTag(scenario, "pilot_auth_summary")
                waitForTextContains(scenario, "Real-Server Authentication (Ephemeral, Never Persisted)")
                waitForTextContains(scenario, "Real-Server Account Password")
                assertTrue(testContainer.pilotAuthorizationGate.isAuthorized)
                assertTrue(
                    "FLAG_SECURE must remain set during live credential entry",
                    hasFlagSecure(scenario)
                )

                // Back navigation must NOT silently hide active live session
                scenario.onActivity { activity ->
                    @Suppress("DEPRECATION")
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue(testContainer.pilotAuthorizationGate.isAuthorized)
                assertNotNull(findNodeByTag(scenario, "pilot_auth_summary"))
                assertNotNull(findNodeByTag(scenario, "btn_pilot_emergency_disconnect"))

                // Activity recreation must preserve live UI ownership and FLAG_SECURE
                scenario.recreate()
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitForTag(scenario, "pilot_auth_summary")
                waitForTextContains(scenario, "Real-Server Authentication (Ephemeral, Never Persisted)")
                assertTrue(testContainer.pilotAuthorizationGate.isAuthorized)
                assertTrue(hasFlagSecure(scenario))

                // Close revokes live authorization and starts generation-fenced cleanup
                waitAndClickTag(scenario, "btn_pilot_close_sheet")
                waitUntil("Close must revoke live authorization") {
                    !testContainer.pilotAuthorizationGate.isAuthorized &&
                        testContainer.pilotSessionController.pilotModeState.value is PilotModeState.OfflineDefault
                }
            }
        } finally {
            app.replaceContainerForTesting(DefaultAppContainer(context = app))
        }
    }

    private fun buildSyntheticRecordJson(
        authId: String,
        apkSha256: String,
        offlineChecklistPassed: Boolean,
        physicalExpiryVerified: Boolean
    ): String = """
        {
          "authorization_id": "$authId",
          "authorized_operator": "Instrumentation-Test",
          "governance_approval": {
            "product_owner_approved": true,
            "approved_by_product_owner": "PO-Instrumentation",
            "lead_maintainer_approved": true,
            "approved_by_lead_maintainer": "Lead-Instrumentation",
            "approval_date": "2026-11-26",
            "external_record_ref": "EXT-$authId"
          },
          "artifact_identity": {
            "candidate_commit_sha": "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            "verified_pilot_apk_sha256": "$apkSha256",
            "application_id": "${BuildConfig.APPLICATION_ID}",
            "version_name": "${BuildConfig.VERSION_NAME}",
            "version_code": ${BuildConfig.VERSION_CODE}
          },
          "target_account_scope": {
            "scope_type": "DEDICATED_ISOLATED_TEST_ACCOUNT",
            "authorized_account_alias": "i***@test.invalid",
            "authorized_account_identifier": "instrument@test.invalid",
            "authorized_region_calling_code": "+1"
          },
          "target_robot_scope": {
            "expected_sub_type_family": "LaserSlam",
            "expected_target_thing_name": "instrument_laser_01",
            "redacted_tag": "TARGET_INSTRUMENT_01"
          },
          "endpoint_trust_scope": {
            "approved_auth_origin": "https://loopback.offline.invalid:443",
            "approved_auth_path": "/prod/oauth",
            "pinned_api_origin": null,
            "pinned_wss_origin": null,
            "endpoint_provenance_ref": null,
            "require_provenance_verification": true
          },
          "permitted_operation_set": {
            "permitted_operations": ["login"],
            "prohibited_operations": ["user_thing_list_get", "ws_telemetry_observe", "send_to_device"]
          },
          "execution_boundaries": {
            "read_only_mode": true,
            "physical_robot_commands_permitted": false,
            "live_map_streaming_permitted": false,
            "max_login_attempts": 1,
            "max_password_retries": 0,
            "max_captcha_automation": 0,
            "max_inventory_requests": 0,
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
            "offline_device_checklist_passed": $offlineChecklistPassed,
            "physical_device_monotonic_expiry_verified": $physicalExpiryVerified
          }
        }
    """.trimIndent()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hasFlagSecure(scenario: ActivityScenario<MainActivity>): Boolean {
        var secured = false
        scenario.onActivity { activity ->
            secured = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        }
        return secured
    }

    private fun findNodeByTag(
        scenario: ActivityScenario<MainActivity>,
        tag: String
    ): SemanticsNode? {
        var found: SemanticsNode? = null
        scenario.onActivity { activity ->
            found = collectAllSemanticsNodes(activity).firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.TestTag) == tag
            }
        }
        return found
    }

    private fun waitForTag(scenario: ActivityScenario<MainActivity>, tag: String) {
        waitUntil("Semantics node with tag=$tag did not appear") {
            findNodeByTag(scenario, tag) != null
        }
    }

    private fun waitForTextContains(scenario: ActivityScenario<MainActivity>, substring: String) {
        waitUntil("Semantics text containing '$substring' did not appear") {
            var matched = false
            scenario.onActivity { activity ->
                matched = collectAllSemanticsNodes(activity).any { node ->
                    val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                    val editable = node.config.getOrNull(SemanticsProperties.EditableText)
                    texts.any { it.text.contains(substring) } ||
                        (editable?.text?.contains(substring) == true)
                }
            }
            matched
        }
    }

    private fun waitAndClickTag(scenario: ActivityScenario<MainActivity>, tag: String) {
        waitForTag(scenario, tag)
        scenario.onActivity { activity ->
            val node = collectAllSemanticsNodes(activity).first {
                it.config.getOrNull(SemanticsProperties.TestTag) == tag
            }
            val clicked = node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: false
            assertTrue("Semantics node tag=$tag is not clickable", clicked)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun setTextByTag(scenario: ActivityScenario<MainActivity>, tag: String, value: String) {
        waitForTag(scenario, tag)
        scenario.onActivity { activity ->
            val node = collectAllSemanticsNodes(activity).first {
                it.config.getOrNull(SemanticsProperties.TestTag) == tag
            }
            val updated = node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(
                AnnotatedString(value)
            ) ?: false
            assertTrue("Semantics node tag=$tag does not support SetText", updated)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun collectAllSemanticsNodes(activity: Activity): List<SemanticsNode> {
        val roots = mutableListOf<RootForTest>()
        fun walkViews(view: View) {
            if (view is RootForTest) {
                roots += view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walkViews(view.getChildAt(i))
                }
            }
        }
        walkViews(activity.window.decorView)
        val nodes = mutableListOf<SemanticsNode>()
        fun walkSemantics(node: SemanticsNode) {
            nodes += node
            node.children.forEach { walkSemantics(it) }
        }
        roots.forEach { root ->
            walkSemantics(root.semanticsOwner.unmergedRootSemanticsNode)
        }
        return nodes
    }

    private fun waitUntil(failureMessage: String, timeoutMs: Long = 10_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (predicate()) return
            Thread.sleep(50L)
        }
        assertTrue(failureMessage, predicate())
    }
}
