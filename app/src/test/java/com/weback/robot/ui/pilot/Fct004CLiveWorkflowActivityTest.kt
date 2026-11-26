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
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.BuildConfig
import com.weback.robot.MainActivity
import com.weback.robot.WeBackApplication
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.DefaultAppContainer
import com.weback.robot.core.LivePilotActivationRequest
import com.weback.robot.core.PilotContainerTestHooks
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.protocol.auth.RegionEndpointRouter
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import com.weback.robot.security.UriValidator
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class Fct004CLiveWorkflowActivityTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun memoryTokenStore(): TokenStore = object : TokenStore {
        var saved: PersistedSessionRecord? = null
        override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
            saved = record
            return TokenSaveResult.Success
        }
        override suspend fun load(): TokenLoadResult =
            saved?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
        override suspend fun clear(): TokenClearResult {
            saved = null
            return TokenClearResult.Success
        }
    }

    @Test
    fun activityLiveWorkflowSeparatesOfflineBuildEnforcesQuarantineAndCompletesPreApprovedTargetInventory() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val app = ApplicationProvider.getApplicationContext<Application>() as WeBackApplication
        val nowMs = 50_000L
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
                pinnedApiOrigin = null,
                pinnedWssOrigin = null
            ),
            allowedAuthOrigins = setOf(originHttp),
            allowLoopbackForHermeticTests = true
        )
        val expectedId = ExpectedCandidateIdentity(
            candidateCommitSha = "bc7f6d6589b4962c092574d998f6dfce532b17c7",
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            apkSha256 = "a".repeat(64)
        )
        val tokenStore = memoryTokenStore()
        app.replaceContainerForTesting(
            DefaultAppContainer(
                context = app,
                authClock = AuthMonotonicClock { nowMs },
                tokenStoreOverride = tokenStore,
                pilotTestHooks = PilotContainerTestHooks(
                    routerOverride = RegionEndpointRouter(
                        cnOverrideUrl = "$originHttp/prod/oauth",
                        globalOverrideUrl = "$originHttp/prod/oauth"
                    ),
                    trustPolicyOverride = pilotTrust,
                    gatewayTrustPolicyOverride = loopbackPolicy,
                    allowedAuthOriginsOverride = setOf(originHttp),
                    expectedRuntimeIdentityOverride = expectedId
                )
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                // 1. Default OFFLINE startup
                composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
                assertFalse(app.container.pilotAuthorizationGate.isAuthorized)

                // Open Pilot Readiness sheet
                composeRule.onNodeWithTag("btn_open_pilot_readiness").performScrollTo().performClick()
                composeRule.waitForIdle()

                if (!BuildConfig.PILOT_INTERNET_CAPABLE) {
                    // Offline build MUST NOT show or allow live pilot activation
                    assertTrue(
                        composeRule.onAllNodesWithTag("btn_open_live_auth_review").fetchSemanticsNodes().isEmpty()
                    )
                    val directReject = app.container.activateLivePilot(
                        LivePilotActivationRequest(
                            authorizationId = "FCT004C-OFFLINE-REJECT",
                            approvedByProductOwner = "PO-Human",
                            approvedByLeadMaintainer = "Lead-Human",
                            externalRecordRef = "EXT-01",
                            candidateCommitSha = expectedId.candidateCommitSha,
                            apkSha256 = expectedId.apkSha256,
                            regionCallingCode = "+1",
                            authorizedAccountIdentifier = "pilot@test.invalid",
                            expectedTargetThingName = "laser_target_01",
                            approvedAuthOrigin = originHttp
                        )
                    )
                    assertTrue(directReject is GateActivationResult.Rejected)
                    assertEquals(0, server.requestCount)
                    return@use
                }

                // 2. Pilot build: Explicit entry into live authorization review screen
                composeRule.onNodeWithTag("btn_open_live_auth_review").performScrollTo().performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("live_auth_review_panel").assertIsDisplayed()

                // 3a. Missing/placeholder authorization rejected cleanly in UI
                composeRule.onNodeWithTag("btn_validate_live_auth").performScrollTo().performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("live_auth_validation_error").performScrollTo().assertIsDisplayed()
                assertFalse(app.container.pilotAuthorizationGate.isAuthorized)

                // 3b. Fill valid externally approved unpinned context (to test quarantine termination first)
                fun fillLiveAuth(authId: String, pinnedApi: String = "", pinnedWss: String = "") {
                    composeRule.onNodeWithTag("live_auth_input_id").performScrollTo().performTextInput(authId)
                    composeRule.onNodeWithTag("live_auth_input_po").performScrollTo().performTextInput("PO-Human")
                    composeRule.onNodeWithTag("live_auth_input_lead").performScrollTo().performTextInput("Lead-Human")
                    composeRule.onNodeWithTag("live_auth_input_ref").performScrollTo().performTextInput("EXT-$authId")
                    composeRule.onNodeWithTag("live_auth_input_commit_sha").performScrollTo().performTextInput(expectedId.candidateCommitSha)
                    composeRule.onNodeWithTag("live_auth_input_apk_sha").performScrollTo().performTextInput(expectedId.apkSha256)
                    composeRule.onNodeWithTag("live_auth_input_calling_code").performScrollTo().performTextInput("+1")
                    composeRule.onNodeWithTag("live_auth_input_account").performScrollTo().performTextInput("pilot@test.invalid")
                    composeRule.onNodeWithTag("live_auth_input_thing_name").performScrollTo().performTextInput("laser_target_01")
                    composeRule.onNodeWithTag("live_auth_input_auth_origin").performScrollTo().performTextInput(originHttp)
                    if (pinnedApi.isNotEmpty()) {
                        composeRule.onNodeWithTag("live_auth_input_pinned_api").performScrollTo().performTextInput(pinnedApi)
                    }
                    if (pinnedWss.isNotEmpty()) {
                        composeRule.onNodeWithTag("live_auth_input_pinned_wss").performScrollTo().performTextInput(pinnedWss)
                    }
                    composeRule.onNodeWithTag("btn_validate_live_auth").performScrollTo().performClick()
                    composeRule.waitForIdle()
                }

                fillLiveAuth("FCT004C-UI-QUAR-01")
                assertTrue(app.container.pilotAuthorizationGate.isAuthorized)
                assertTrue(app.container.pilotSessionController.pilotModeState.value is PilotModeState.AuthorizedReady)

                // 4. FLAG_SECURE active during credential entry
                scenario.onActivity { act ->
                    assertTrue(
                        "FLAG_SECURE must be active during live credential entry",
                        (act.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
                    )
                }

                // 5. Submit login with unpinned dynamic origins -> Quarantine termination
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"code":1,"data":{"api_url":"https://dyn-api.example.invalid/api","wss_url":"wss://dyn-wss.example.invalid/ws","region_name":"us","jwt_token":"jwt-quar","expired_time":3600}}"""
                    )
                )
                composeRule.onNodeWithTag("pilot_input_region").performScrollTo().performTextInput("+1")
                composeRule.onNodeWithTag("pilot_input_account").performScrollTo().performTextInput("pilot@test.invalid")
                composeRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("LivePass1!")
                composeRule.onNodeWithTag("btn_pilot_submit_login").performScrollTo().performClick()
                composeRule.waitUntil(5_000L) {
                    !app.container.pilotAuthorizationGate.isAuthorized &&
                        app.container.pilotSessionController.lastObservedOrigins.value != null
                }
                composeRule.waitForIdle()

                // 7a. Sanitized observation displayed, session discarded, returned to OFFLINE_DEFAULT, zero inventory calls
                assertEquals(1, server.requestCount)
                assertEquals(PilotModeState.OfflineDefault, app.container.pilotSessionController.pilotModeState.value)
                composeRule.onNodeWithTag("pilot_endpoint_review").performScrollTo().assertTextContains("dyn-api.example.invalid", substring = true)
                composeRule.onNodeWithTag("pilot_disconnect_status").performScrollTo().assertTextContains("UNTRUSTED_ORIGIN_REJECTED", substring = true)
                assertTrue(composeRule.onAllNodesWithTag("btn_pilot_fetch_target_inventory").fetchSemanticsNodes().isEmpty())

                // 7b. New approved authorization with pre-approved pinned origins -> login + single inventory
                composeRule.onNodeWithTag("btn_open_live_auth_review").performScrollTo().performClick()
                composeRule.waitForIdle()
                fillLiveAuth(
                    authId = "FCT004C-UI-OK-02",
                    pinnedApi = "https://127.0.0.1:${server.port}",
                    pinnedWss = "wss://127.0.0.1:${server.port}"
                )
                assertTrue(app.container.pilotAuthorizationGate.isAuthorized)

                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-live-ok","expired_time":3600}}"""
                    )
                )
                composeRule.onNodeWithTag("pilot_input_region").performScrollTo().performTextInput("+1")
                composeRule.onNodeWithTag("pilot_input_account").performScrollTo().performTextInput("pilot@test.invalid")
                composeRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("LivePass2!")
                composeRule.onNodeWithTag("btn_pilot_submit_login").performScrollTo().performClick()
                composeRule.waitUntil(5_000L) {
                    app.container.pilotSessionController.pilotModeState.value is PilotModeState.AuthenticatedInventoryReady
                }
                composeRule.waitForIdle()

                // 6. Safe authentication result shown (no JWT leaked)
                composeRule.onNodeWithTag("live_auth_result_banner").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithText("jwt-live-ok", substring = true).assertDoesNotExist()
                assertEquals(2, server.requestCount)

                // 8. Single read-only inventory request
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"code":1,"data":{"thing_list":[
                            {"thing_name":"other_robot_secret","sub_type":"xs-x8-eu","thing_nickname":"Other","is_admin":true},
                            {"thing_name":"laser_target_01","sub_type":"xs-x8-eu","thing_nickname":"Target Living Laser","is_admin":true,"thing_status":{"battery_level":95,"working_status":"dock","error_info":0}}
                        ]}}"""
                    )
                )
                composeRule.onNodeWithTag("btn_pilot_fetch_target_inventory").performScrollTo().performClick()
                composeRule.waitUntil(5_000L) {
                    app.container.pilotSessionController.pilotModeState.value is PilotModeState.InventoryVerified
                }
                composeRule.waitForIdle()

                // 9. Exact target robot & capability result shown; other robot & raw thing_name hidden; zero WS buttons
                assertEquals(3, server.requestCount)
                composeRule.onNodeWithTag("target_inventory_summary").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithTag("target_inventory_summary").assertTextContains("Target Living Laser", substring = true)
                composeRule.onNodeWithTag("target_inventory_summary").assertTextContains("xs-x8-eu-", substring = true)
                composeRule.onNodeWithText("other_robot_secret", substring = true).assertDoesNotExist()
                composeRule.onNodeWithText("laser_target_01", substring = true).assertDoesNotExist()
                assertTrue(composeRule.onAllNodesWithTag("btn_observe_laser_target_01").fetchSemanticsNodes().isEmpty())

                // 10 & 11. Emergency Disconnect & Wipe -> returns to OFFLINE_DEFAULT & preserves simulator
                composeRule.onNodeWithTag("btn_pilot_emergency_disconnect").performScrollTo().performClick()
                composeRule.waitUntil(5_000L) {
                    !app.container.pilotAuthorizationGate.isAuthorized &&
                        app.container.pilotSessionController.pilotModeState.value == PilotModeState.OfflineDefault
                }
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("pilot_auth_summary").performScrollTo().assertTextContains("OFFLINE_DEFAULT", substring = true)
                composeRule.onNodeWithTag("btn_pilot_close_sheet").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
                composeRule.onNodeWithTag("btn_clean").performScrollTo().assertIsEnabled()
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun resumingMainActivityAfterMonotonicDeepSleepDeadlineExpiresAndWipesWithoutManualClicks() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val app = ApplicationProvider.getApplicationContext<Application>() as WeBackApplication
        var nowMs = 50_000L
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
            candidateCommitSha = "bc7f6d6589b4962c092574d998f6dfce532b17c7",
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            apkSha256 = "b".repeat(64)
        )
        val tokenStore = memoryTokenStore()
        app.replaceContainerForTesting(
            DefaultAppContainer(
                context = app,
                authClock = AuthMonotonicClock { nowMs },
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
                    enableUiCountdownTicker = false
                )
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                if (BuildConfig.PILOT_INTERNET_CAPABLE) {
                    val act = app.container.activateLivePilot(
                        LivePilotActivationRequest(
                            authorizationId = "FCT004C-RESUME-EXP-01",
                            approvedByProductOwner = "PO-Human",
                            approvedByLeadMaintainer = "Lead-Human",
                            externalRecordRef = "EXT-RESUME-01",
                            candidateCommitSha = expectedId.candidateCommitSha,
                            apkSha256 = expectedId.apkSha256,
                            regionCallingCode = "+1",
                            authorizedAccountIdentifier = "pilot@test.invalid",
                            expectedTargetThingName = "laser_target_01",
                            approvedAuthOrigin = originHttp,
                            pinnedApiOrigin = "https://127.0.0.1:${server.port}",
                            pinnedWssOrigin = "wss://127.0.0.1:${server.port}",
                            windowMinutes = 15
                        )
                    )
                    assertTrue(act is GateActivationResult.Activated)
                    server.enqueue(
                        MockResponse().setResponseCode(200).setBody(
                            """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-sleep-wake","expired_time":3600}}"""
                        )
                    )
                    runBlocking {
                        app.container.pilotSessionController.submitCredentials(
                            AuthCredentials("+1", "pilot@test.invalid", "SecretWake1!".toCharArray())
                        )
                    }
                    assertTrue(app.container.pilotAuthorizationGate.isAuthorized)
                    assertTrue(runBlocking { tokenStore.load() } is TokenLoadResult.Loaded)

                    // Simulate screen-off deep sleep past 15-min deadline, then Activity resume
                    scenario.moveToState(Lifecycle.State.CREATED)
                    nowMs += 900_000L
                    scenario.moveToState(Lifecycle.State.RESUMED)

                    composeRule.waitUntil(5_000L) {
                        !app.container.pilotAuthorizationGate.isAuthorized &&
                            app.container.pilotSessionController.pilotModeState.value == PilotModeState.OfflineDefault
                    }
                    assertEquals(
                        PilotAbortReason.ObservationWindowExpired,
                        app.container.pilotSessionController.lastAbortReason.value
                    )
                    assertTrue(runBlocking { tokenStore.load() } is TokenLoadResult.Empty)
                } else {
                    // Offline build: exercise preserved mock pilot resume expiry
                    assertTrue(app.container.startOfflineMockPilot() is GateActivationResult.Activated)
                    assertTrue(app.container.offlineMockAuthorizationGate.isAuthorized)
                    scenario.moveToState(Lifecycle.State.CREATED)
                    nowMs += 1_800_000L
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    composeRule.waitUntil(5_000L) {
                        !app.container.offlineMockAuthorizationGate.isAuthorized &&
                            app.container.offlineMockPilotController.pilotModeState.value == PilotModeState.OfflineDefault
                    }
                    assertEquals(
                        PilotAbortReason.ObservationWindowExpired,
                        app.container.offlineMockPilotController.lastAbortReason.value
                    )
                }
            } finally {
                server.shutdown()
            }
        }
    }
}
