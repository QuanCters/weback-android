package com.weback.robot.ui.pilot

import android.app.Application
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.BuildConfig
import com.weback.robot.MainActivity
import com.weback.robot.WeBackApplication
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.DefaultPilotContainer
import com.weback.robot.core.PilotAppContainer
import com.weback.robot.core.PilotContainerTestHooks
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.protocol.auth.RegionEndpointRouter
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.FileBackedProvisionedPilotAuthorizationStore
import com.weback.robot.security.InstalledApkVerification
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import com.weback.robot.security.UriValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Fct004CLiveLifecycleRegressionTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val commitSha = "77ad87f11e3cf7f0b10f02a42073f2682571ecb0"
    private val apkSha = "e".repeat(64)

    @Before
    fun cleanAuthDir() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.noBackupFilesDir, FileBackedProvisionedPilotAuthorizationStore.DIRECTORY_NAME)
            .deleteRecursively()
    }

    private fun provisionRecord(
        app: Application,
        authId: String,
        originHttp: String,
        port: Int,
        pinned: Boolean = true
    ): String {
        val dir = File(app.noBackupFilesDir, FileBackedProvisionedPilotAuthorizationStore.DIRECTORY_NAME)
        dir.mkdirs()
        val pinnedApi = if (pinned) "\"https://127.0.0.1:$port\"" else "null"
        val pinnedWss = if (pinned) "\"wss://127.0.0.1:$port\"" else "null"
        val provRef = if (pinned) "\"PROV-LIFE-$authId\"" else "null"
        val json = """
            {
              "authorization_id": "$authId",
              "authorized_operator": "Operator-Test",
              "governance_approval": {
                "product_owner_approved": true,
                "approved_by_product_owner": "PO-Human",
                "lead_maintainer_approved": true,
                "approved_by_lead_maintainer": "Lead-Human",
                "approval_date": "2026-11-26",
                "external_record_ref": "EXT-$authId"
              },
              "artifact_identity": {
                "candidate_commit_sha": "$commitSha",
                "verified_pilot_apk_sha256": "$apkSha",
                "application_id": "${BuildConfig.APPLICATION_ID}",
                "version_name": "${BuildConfig.VERSION_NAME}",
                "version_code": ${BuildConfig.VERSION_CODE}
              },
              "target_account_scope": {
                "scope_type": "DEDICATED_ISOLATED_TEST_ACCOUNT",
                "authorized_account_alias": "p***@test.invalid",
                "authorized_account_identifier": "pilot@test.invalid",
                "authorized_region_calling_code": "+1"
              },
              "target_robot_scope": {
                "expected_sub_type_family": "LaserSlam",
                "expected_target_thing_name": "laser_target_01",
                "redacted_tag": "TARGET_LASER_01"
              },
              "endpoint_trust_scope": {
                "approved_auth_origin": "$originHttp",
                "approved_auth_path": "/prod/oauth",
                "pinned_api_origin": $pinnedApi,
                "pinned_wss_origin": $pinnedWss,
                "endpoint_provenance_ref": $provRef,
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
              }
            }
        """.trimIndent()
        val bytes = json.toByteArray(Charsets.UTF_8)
        File(dir, FileBackedProvisionedPilotAuthorizationStore.RECORD_FILE_NAME).writeBytes(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        File(dir, FileBackedProvisionedPilotAuthorizationStore.DIGEST_FILE_NAME).writeText(digest)
        return digest
    }

    private fun installContainer(
        app: Application,
        server: MockWebServer,
        tokenStore: TokenStore
    ) {
        val originHttp = "http://127.0.0.1:${server.port}"
        val loopbackPolicy = TestLoopbackTrustPolicy(
            setOf(
                UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port),
                UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
            )
        )
        val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
            endpointConfig = PilotEndpointConfig(
                approvedAuthOrigin = originHttp,
                approvedAuthPath = "/prod/oauth",
                pinnedApiOrigin = "https://127.0.0.1:${server.port}",
                pinnedWssOrigin = "wss://127.0.0.1:${server.port}"
            ),
            allowedAuthOrigins = setOf(originHttp),
            allowLoopbackForHermeticTests = true
        )
        val expectedId = ExpectedCandidateIdentity(
            candidateCommitSha = commitSha,
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            apkSha256 = apkSha
        )
        (app as WeBackApplication).replaceContainerForTesting(
            DefaultPilotContainer(
                context = app,
                authClock = AuthMonotonicClock { 50_000L },
                tokenStoreOverride = tokenStore,
                pilotTestHooks = PilotContainerTestHooks(
                    routerOverride = RegionEndpointRouter(
                        cnOverrideUrl = "$originHttp/prod/oauth",
                        globalOverrideUrl = "$originHttp/prod/oauth"
                    ),
                    trustPolicyOverride = pilotTrust,
                    gatewayTrustPolicyOverride = loopbackPolicy,
                    allowedAuthOriginsOverride = setOf(originHttp),
                    expectedRuntimeIdentityOverride = expectedId,
                    installedApkVerificationOverride = InstalledApkVerification(
                        supported = true,
                        actualApkSha256 = apkSha,
                        detail = "TEST_VERIFIED"
                    )
                )
            )
        )
    }

    private fun openLiveSheetAndActivate(app: Application, digest: String) {
        composeRule.onNodeWithTag("btn_open_pilot_readiness").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("btn_open_live_auth_review").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("live_auth_input_record_sha256").performScrollTo().performTextInput(digest)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("btn_validate_live_auth").performScrollTo().performClick()
        composeRule.waitUntil(5_000L) {
            ((app as WeBackApplication).container as PilotAppContainer).pilotSessionController.pilotModeState.value is PilotModeState.AuthorizedReady
        }
        composeRule.waitForIdle()
    }

    @Test
    fun closeDuringAuthorizedReadyRevokesAuthorizationAndBackDoesNotHideActiveLiveSession() {
        if (!BuildConfig.PILOT_INTERNET_CAPABLE) return
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val app = ApplicationProvider.getApplicationContext<Application>() as WeBackApplication
        val tokenStore = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
            override suspend fun load() = TokenLoadResult.Empty
            override suspend fun clear() = TokenClearResult.Success
        }
        installContainer(app, server, tokenStore)
        try {
            val digest = provisionRecord(app, "FCT004C-CLOSE-READY-01", "http://127.0.0.1:${server.port}", server.port)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openLiveSheetAndActivate(app, digest)
                assertTrue((app.container as PilotAppContainer).pilotAuthorizationGate.isAuthorized)
                assertTrue((app.container as PilotAppContainer).pilotSessionController.pilotModeState.value is PilotModeState.AuthorizedReady)

                // Back navigation MUST NOT silently hide an active live session
                scenario.onActivity { act -> act.onBackPressedDispatcher.onBackPressed() }
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("btn_pilot_emergency_disconnect").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithTag("btn_pilot_submit_login").performScrollTo().assertIsDisplayed()
                assertTrue((app.container as PilotAppContainer).pilotAuthorizationGate.isAuthorized)

                // Close during AuthorizedReady MUST revoke authorization and initiate cleanup
                composeRule.onNodeWithTag("btn_pilot_close_sheet").performScrollTo().performClick()
                composeRule.waitUntil(5_000L) {
                    !(app.container as PilotAppContainer).pilotAuthorizationGate.isAuthorized &&
                        !(app.container as PilotAppContainer).pilotAuthorizationGate.hasInFlightOrIncompleteCleanup()
                }
                composeRule.waitForIdle()

                assertFalse((app.container as PilotAppContainer).pilotAuthorizationGate.isAuthorized)
                assertEquals(PilotModeState.OfflineDefault, (app.container as PilotAppContainer).pilotSessionController.pilotModeState.value)
                assertEquals(PilotAbortReason.UserEmergencyDisconnect, (app.container as PilotAppContainer).pilotSessionController.lastAbortReason.value)
                composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
                scenario.onActivity { act ->
                    assertEquals(0, act.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun closeWhileLoginPendingRevokesNetworkImmediatelyWithoutWaitingForSlowDiskCleanup() {
        if (!BuildConfig.PILOT_INTERNET_CAPABLE) return
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val app = ApplicationProvider.getApplicationContext<Application>() as WeBackApplication
        val diskClearGate = CompletableDeferred<Unit>()
        var clearCalled = false
        val tokenStore = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
            override suspend fun load() = TokenLoadResult.Empty
            override suspend fun clear(): TokenClearResult {
                clearCalled = true
                diskClearGate.await()
                return TokenClearResult.Success
            }
        }
        installContainer(app, server, tokenStore)
        try {
            val digest = provisionRecord(app, "FCT004C-CLOSE-INFLIGHT-01", "http://127.0.0.1:${server.port}", server.port)
            server.enqueue(
                MockResponse().setResponseCode(200).setBodyDelay(5, TimeUnit.SECONDS).setBody(
                    """{"code":1,"data":{"api_url":"http://127.0.0.1:${server.port}/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-pending","expired_time":3600}}"""
                )
            )
            ActivityScenario.launch(MainActivity::class.java).use {
                openLiveSheetAndActivate(app, digest)
                composeRule.onNodeWithTag("pilot_input_region").performScrollTo().performTextInput("+1")
                composeRule.onNodeWithTag("pilot_input_account").performScrollTo().performTextInput("pilot@test.invalid")
                composeRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("LivePass1!")
                composeRule.onNodeWithTag("btn_pilot_submit_login").performScrollTo().performClick()

                composeRule.waitUntil(5_000L) { server.requestCount == 1 }
                composeRule.onNodeWithTag("btn_pilot_emergency_disconnect").performScrollTo().assertIsDisplayed().assertIsEnabled()

                // Close while login is pending AND disk cleanup is blocked
                composeRule.onNodeWithTag("btn_pilot_close_sheet").performScrollTo().performClick()
                composeRule.waitUntil(1_500L) {
                    !(app.container as PilotAppContainer).pilotAuthorizationGate.isAuthorized &&
                        (app.container as PilotAppContainer).pilotSessionController.pilotModeState.value == PilotModeState.OfflineDefault
                }
                assertFalse("Gate must be revoked immediately before slow disk clear", (app.container as PilotAppContainer).pilotAuthorizationGate.isAuthorized)
                assertEquals(PilotAbortReason.UserEmergencyDisconnect, (app.container as PilotAppContainer).pilotSessionController.lastAbortReason.value)
                assertTrue("Cleanup must be marked in-flight while disk clear blocks", (app.container as PilotAppContainer).pilotAuthorizationGate.hasInFlightOrIncompleteCleanup())

                // Release slow disk cleanup and verify completion
                diskClearGate.complete(Unit)
                composeRule.waitUntil(5_000L) {
                    clearCalled && !(app.container as PilotAppContainer).pilotAuthorizationGate.hasInFlightOrIncompleteCleanup()
                }
            }
        } finally {
            diskClearGate.complete(Unit)
            server.shutdown()
        }
    }

    @Test
    fun activityRecreationWhileAuthenticatedRestoresLiveUiOwnership() {
        if (!BuildConfig.PILOT_INTERNET_CAPABLE) return
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val app = ApplicationProvider.getApplicationContext<Application>() as WeBackApplication
        val tokenStore = object : TokenStore {
            var saved: PersistedSessionRecord? = null
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                saved = record
                return TokenSaveResult.Success
            }
            override suspend fun load() = saved?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
            override suspend fun clear(): TokenClearResult {
                saved = null
                return TokenClearResult.Success
            }
        }
        installContainer(app, server, tokenStore)
        try {
            val digest = provisionRecord(app, "FCT004C-RECREATE-01", "http://127.0.0.1:${server.port}", server.port)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"api_url":"http://127.0.0.1:${server.port}/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-rec","expired_time":3600}}"""
                )
            )
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openLiveSheetAndActivate(app, digest)
                composeRule.onNodeWithTag("pilot_input_region").performScrollTo().performTextInput("+1")
                composeRule.onNodeWithTag("pilot_input_account").performScrollTo().performTextInput("pilot@test.invalid")
                composeRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("LivePass1!")
                composeRule.onNodeWithTag("btn_pilot_submit_login").performScrollTo().performClick()
                composeRule.waitUntil(5_000L) {
                    (app.container as PilotAppContainer).pilotSessionController.pilotModeState.value is PilotModeState.AuthenticatedInventoryReady
                }
                composeRule.waitForIdle()

                // Recreate Activity while authenticated
                scenario.recreate()
                composeRule.waitForIdle()

                // Recreated Activity MUST restore live UI ownership & Emergency Disconnect
                composeRule.onNodeWithTag("live_auth_result_banner").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithTag("btn_pilot_fetch_target_inventory").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithTag("btn_pilot_emergency_disconnect").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithTag("pilot_mode_badge").assertTextContains("CONTROLLED AUTH", substring = true)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun mockSwitchingBlockedDuringAndAfterCleanupFailureAndFailureRemainsVisible() {
        if (!BuildConfig.PILOT_INTERNET_CAPABLE) return
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val app = ApplicationProvider.getApplicationContext<Application>() as WeBackApplication
        val tokenStore = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
            override suspend fun load() = TokenLoadResult.Empty
            override suspend fun clear() = TokenClearResult.Failure("DISK_WIPE_FAILED")
        }
        installContainer(app, server, tokenStore)
        try {
            val digest = provisionRecord(app, "FCT004C-CLEANUP-FAIL-01", "http://127.0.0.1:${server.port}", server.port)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"api_url":"http://127.0.0.1:${server.port}/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-fail","expired_time":3600}}"""
                )
            )
            ActivityScenario.launch(MainActivity::class.java).use {
                openLiveSheetAndActivate(app, digest)
                composeRule.onNodeWithTag("pilot_input_region").performScrollTo().performTextInput("+1")
                composeRule.onNodeWithTag("pilot_input_account").performScrollTo().performTextInput("pilot@test.invalid")
                composeRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("LivePass1!")
                composeRule.onNodeWithTag("btn_pilot_submit_login").performScrollTo().performClick()
                composeRule.waitUntil(5_000L) {
                    (app.container as PilotAppContainer).pilotSessionController.pilotModeState.value is PilotModeState.AuthenticatedInventoryReady
                }

                composeRule.onNodeWithTag("btn_pilot_emergency_disconnect").performScrollTo().performClick()
                composeRule.waitUntil(5_000L) {
                    (app.container as PilotAppContainer).pilotSessionController.lastDisconnectResult.value?.storageCleared == false
                }
                composeRule.waitForIdle()

                // Cleanup failure remains visible and mock switch is blocked
                composeRule.onNodeWithTag("pilot_disconnect_status").performScrollTo().assertTextContains("DISK_WIPE_FAILED", substring = true)
                assertTrue(composeRule.onAllNodesWithTag("btn_start_mock_pilot").fetchSemanticsNodes().isEmpty())

                // Unauthorized live reactivation blocked while cleanup is incomplete
                val nextDigest = provisionRecord(app, "FCT004C-CLEANUP-FAIL-02", "http://127.0.0.1:${server.port}", server.port)
                val reactivate = (app.container as PilotAppContainer).activateLivePilot(nextDigest)
                assertTrue(reactivate is GateActivationResult.Rejected)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun lateResponseFollowingRevocationCannotRestoreAuthorizationOrOverwriteNewerSession() {
        if (!BuildConfig.PILOT_INTERNET_CAPABLE) return
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val app = ApplicationProvider.getApplicationContext<Application>() as WeBackApplication
        var savedToken: PersistedSessionRecord? = null
        val tokenStore = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                savedToken = record
                return TokenSaveResult.Success
            }
            override suspend fun load() = savedToken?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
            override suspend fun clear(): TokenClearResult {
                savedToken = null
                return TokenClearResult.Success
            }
        }
        installContainer(app, server, tokenStore)
        try {
            val digest1 = provisionRecord(app, "FCT004C-LATE-01", "http://127.0.0.1:${server.port}", server.port)
            // Delayed response for Run 1
            server.enqueue(
                MockResponse().setResponseCode(200).setBodyDelay(500, TimeUnit.MILLISECONDS).setBody(
                    """{"code":1,"data":{"api_url":"http://127.0.0.1:${server.port}/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-stale-run1","expired_time":3600}}"""
                )
            )
            ActivityScenario.launch(MainActivity::class.java).use {
                openLiveSheetAndActivate(app, digest1)
                composeRule.onNodeWithTag("pilot_input_region").performScrollTo().performTextInput("+1")
                composeRule.onNodeWithTag("pilot_input_account").performScrollTo().performTextInput("pilot@test.invalid")
                composeRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("LivePass1!")
                composeRule.onNodeWithTag("btn_pilot_submit_login").performScrollTo().performClick()

                composeRule.waitUntil(5_000L) { server.requestCount == 1 }
                composeRule.onNodeWithTag("btn_pilot_close_sheet").performScrollTo().performClick()
                composeRule.waitUntil(5_000L) {
                    !(app.container as PilotAppContainer).pilotAuthorizationGate.isAuthorized &&
                        !(app.container as PilotAppContainer).pilotAuthorizationGate.hasInFlightOrIncompleteCleanup()
                }

                // Activate newer Run 2 BEFORE or around Run 1 late HTTP arrival
                val digest2 = provisionRecord(app, "FCT004C-LATE-02", "http://127.0.0.1:${server.port}", server.port)
                val act2 = (app.container as PilotAppContainer).activateLivePilot(digest2)
                assertTrue(act2 is GateActivationResult.Activated)

                // Wait past Run 1's 500ms server delay
                runBlocking { kotlinx.coroutines.delay(700L) }
                composeRule.waitForIdle()

                // Run 2 must still be AuthorizedReady (Run 1 late callback did NOT overwrite Run 2 or save stale token)
                assertTrue((app.container as PilotAppContainer).pilotAuthorizationGate.isAuthorized)
                val mode2 = (app.container as PilotAppContainer).pilotSessionController.pilotModeState.value
                assertTrue("Expected Run 2 to remain AuthorizedReady, got $mode2", mode2 is PilotModeState.AuthorizedReady)
                assertEquals("FCT004C-LATE-02", (mode2 as PilotModeState.AuthorizedReady).authorizationId)
                assertEquals(null, savedToken)
            }
        } finally {
            server.shutdown()
        }
    }
}
