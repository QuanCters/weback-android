package com.weback.robot.app.pilot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.BuildConfig
import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.core.DefaultPilotContainer
import com.weback.robot.core.PilotContainerTestHooks
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.RuntimeAppIdentity
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.protocol.auth.RegionEndpointRouter
import com.weback.robot.protocol.inventory.OkHttpDeviceInventoryGateway
import com.weback.robot.protocol.pilot.PilotCapabilityRestrictedAuthGateway
import com.weback.robot.protocol.pilot.PilotCapabilityRestrictedInventoryGateway
import com.weback.robot.protocol.ws.ConnectionConfig
import com.weback.robot.protocol.ws.OkHttpWebSocketTransport
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.ExternalPilotAuthorizationLoader
import com.weback.robot.security.InMemoryProvisionedPilotAuthorizationStore
import com.weback.robot.security.InstalledApkVerification
import com.weback.robot.security.InstalledApkVerifier
import com.weback.robot.security.PilotActiveCallRegistry
import com.weback.robot.security.PilotClientFactory
import com.weback.robot.security.ProvisionedActivationPreparation
import com.weback.robot.security.ProvisionedAuthorizationLoadResult
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import com.weback.robot.security.UriValidator
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class Fct004CApprovedOperationScopeTest {

    private val commitSha = "c7747387e4fccc269c693b24079ba9e71ebbebb4"
    private val apkSha = "a".repeat(64)

    private val runtimeAppId = RuntimeAppIdentity(
        applicationId = "com.weback.robot.pilot",
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        pilotBuildEnabled = true
    )

    private val expectedId = ExpectedCandidateIdentity(
        candidateCommitSha = commitSha,
        applicationId = "com.weback.robot.pilot",
        versionName = BuildConfig.VERSION_NAME,
        apkSha256 = apkSha
    )

    private val apkVerifier = InstalledApkVerifier {
        InstalledApkVerification(supported = true, actualApkSha256 = apkSha, detail = "TEST_VERIFIED")
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun buildJson(
        authId: String,
        permittedOps: List<String>,
        prohibitedOps: List<String>,
        maxInventoryRequests: Int,
        approvedAuthOrigin: String = "https://user.grit-cloud.com:443",
        pinnedApi: String? = null,
        pinnedWss: String? = null,
        provenanceRef: String? = null
    ): String {
        val permJson = permittedOps.joinToString(",") { "\"$it\"" }
        val prohJson = prohibitedOps.joinToString(",") { "\"$it\"" }
        val pApi = pinnedApi?.let { "\"$it\"" } ?: "null"
        val pWss = pinnedWss?.let { "\"$it\"" } ?: "null"
        val pRef = provenanceRef?.let { "\"$it\"" } ?: "null"
        return """
            {
              "authorization_id": "$authId",
              "authorized_operator": "Operator-Scope-Test",
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
                "application_id": "com.weback.robot.pilot",
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
                "approved_auth_origin": "$approvedAuthOrigin",
                "approved_auth_path": "/prod/oauth",
                "pinned_api_origin": $pApi,
                "pinned_wss_origin": $pWss,
                "endpoint_provenance_ref": $pRef,
                "require_provenance_verification": true
              },
              "permitted_operation_set": {
                "permitted_operations": [$permJson],
                "prohibited_operations": [$prohJson]
              },
              "execution_boundaries": {
                "read_only_mode": true,
                "physical_robot_commands_permitted": false,
                "live_map_streaming_permitted": false,
                "max_login_attempts": 1,
                "max_password_retries": 0,
                "max_captcha_automation": 0,
                "max_inventory_requests": $maxInventoryRequests,
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
    }

    @Test
    fun loginOnlyExternalRecordProducesLoginOnlyRuntimeAuthorizationAndZeroInventoryRequests() = runTest {
        if (!BuildConfig.PILOT_INTERNET_CAPABLE) return@runTest
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val originHttp = "http://127.0.0.1:${server.port}"
            val json = buildJson(
                authId = "FCT004C-LOGIN-ONLY-01",
                permittedOps = listOf("login"),
                prohibitedOps = listOf("user_thing_list_get", "ws_telemetry_observe", "send_to_device"),
                maxInventoryRequests = 0,
                approvedAuthOrigin = originHttp,
                pinnedApi = "https://127.0.0.1:${server.port}",
                pinnedWss = "wss://127.0.0.1:${server.port}",
                provenanceRef = "PROV-LOOPBACK-TRUSTED"
            )
            val digest = sha256Hex(json)
            val store = InMemoryProvisionedPilotAuthorizationStore().apply {
                provision(json, digest)
            }

            val prep = ExternalPilotAuthorizationLoader.prepareActivation(
                store = store,
                expectedRecordSha256 = digest,
                runtimeAppIdentity = runtimeAppId,
                expectedRuntimeIdentity = expectedId,
                installedApkVerifier = apkVerifier,
                allowedAuthOrigins = setOf(originHttp),
                nowElapsedMs = 10_000L
            )
            assertTrue("Expected LOGIN-only record to prepare Ready, got $prep", prep is ProvisionedActivationPreparation.Ready)
            val ctx = (prep as ProvisionedActivationPreparation.Ready).context
            assertEquals(setOf(PilotOperation.LOGIN), ctx.permittedOperations)
            assertEquals(0, ctx.budgets.maxInventoryRequests)

            // Wire via DefaultAppContainer with MockWebServer & trusted pinned origins:
            // Direct inventory invocation MUST produce 0 inventory HTTP requests even when origins are trusted.
            val nowMs = 10_000L
            val authClock = AuthMonotonicClock { nowMs }
            val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
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
            val appCtx = ApplicationProvider.getApplicationContext<Context>()
            val storeForContainer = InMemoryProvisionedPilotAuthorizationStore().apply {
                provision(json, digest)
            }
            val container = DefaultPilotContainer(
                context = appCtx,
                dispatcherProvider = dispatchers,
                authClock = authClock,
                tokenStoreOverride = object : TokenStore {
                    override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
                    override suspend fun load() = TokenLoadResult.Empty
                    override suspend fun clear() = TokenClearResult.Success
                },
                pilotTestHooks = PilotContainerTestHooks(
                    routerOverride = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth"),
                    trustPolicyOverride = pilotTrust,
                    gatewayTrustPolicyOverride = loopbackPolicy,
                    allowedAuthOriginsOverride = setOf(originHttp),
                    expectedRuntimeIdentityOverride = expectedId,
                    installedApkVerificationOverride = InstalledApkVerification(true, apkSha, "TEST_VERIFIED"),
                    provisionedAuthStoreOverride = storeForContainer
                )
            )
            try {
                val act = container.activateLivePilot(digest)
                assertTrue("Expected LOGIN-only activation to succeed, got $act", act is GateActivationResult.Activated)
                assertEquals(
                    setOf(PilotOperation.LOGIN),
                    container.pilotAuthorizationGate.currentContext?.permittedOperations
                )

                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-login-only","expired_time":3600}}"""
                    )
                )
                val authRes = container.pilotSessionController.submitCredentials(
                    AuthCredentials("+1", "pilot@test.invalid", "Secret1!".toCharArray())
                )
                assertTrue(authRes is AuthResult.Success)
                assertEquals(1, server.requestCount)

                // Direct inventory attempt under LOGIN-only approval MUST fail closed with ZERO inventory requests
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"thing_list":[]}}"""))
                assertNull(container.pilotSessionController.fetchAndValidateTargetInventory())
                assertFalse(container.pilotSessionController.refreshReadOnlyInventory())
                assertEquals("Inventory must make 0 HTTP requests under LOGIN-only approval", 1, server.requestCount)
                assertFalse(container.pilotAuthorizationGate.isAuthorized)
            } finally {
                container.close()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun loginPlusInventoryPermitsAtMostOneInventoryAndContradictoryListsFailClosed() = runTest {
        if (!BuildConfig.PILOT_INTERNET_CAPABLE) return@runTest
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val originHttp = "http://127.0.0.1:${server.port}"

            // 1. Contradictory operation lists MUST fail closed
            val contradictoryCases = listOf(
                buildJson(
                    authId = "FCT004C-CONTRA-01",
                    permittedOps = listOf("login", "user_thing_list_get"),
                    prohibitedOps = listOf("user_thing_list_get", "ws_telemetry_observe", "send_to_device"),
                    maxInventoryRequests = 1,
                    approvedAuthOrigin = originHttp
                ),
                buildJson(
                    authId = "FCT004C-CONTRA-02",
                    permittedOps = listOf("login"),
                    prohibitedOps = listOf("ws_telemetry_observe", "send_to_device"),
                    maxInventoryRequests = 1,
                    approvedAuthOrigin = originHttp
                ),
                buildJson(
                    authId = "FCT004C-CONTRA-03",
                    permittedOps = listOf("login", "user_thing_list_get"),
                    prohibitedOps = listOf("ws_telemetry_observe", "send_to_device"),
                    maxInventoryRequests = 0,
                    approvedAuthOrigin = originHttp
                )
            )
            contradictoryCases.forEach { badJson ->
                val d = sha256Hex(badJson)
                val store = InMemoryProvisionedPilotAuthorizationStore().apply { provision(badJson, d) }
                val load = ExternalPilotAuthorizationLoader.loadForReview(
                    store, runtimeAppId, expectedId, apkVerifier, setOf(originHttp)
                )
                assertTrue("Contradictory ops/budget must fail review: $load", load is ProvisionedAuthorizationLoadResult.Invalid)
                val prep = ExternalPilotAuthorizationLoader.prepareActivation(
                    store, d, runtimeAppId, expectedId, apkVerifier, setOf(originHttp), 10_000L
                )
                assertTrue("Contradictory ops/budget must fail activation: $prep", prep is ProvisionedActivationPreparation.Rejected)
            }

            // 2. LOGIN + USER_THING_LIST_GET permits at most 1 inventory request
            val invJson = buildJson(
                authId = "FCT004C-LOGIN-INV-01",
                permittedOps = listOf("login", "user_thing_list_get"),
                prohibitedOps = listOf("ws_telemetry_observe", "send_to_device"),
                maxInventoryRequests = 1,
                approvedAuthOrigin = originHttp,
                pinnedApi = "https://127.0.0.1:${server.port}",
                pinnedWss = "wss://127.0.0.1:${server.port}",
                provenanceRef = "PROV-LOOPBACK-INV"
            )
            val invDigest = sha256Hex(invJson)
            val appCtx = ApplicationProvider.getApplicationContext<Context>()
            val container = DefaultPilotContainer(
                context = appCtx,
                dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                authClock = AuthMonotonicClock { 10_000L },
                tokenStoreOverride = object : TokenStore {
                    override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
                    override suspend fun load() = TokenLoadResult.Empty
                    override suspend fun clear() = TokenClearResult.Success
                },
                pilotTestHooks = PilotContainerTestHooks(
                    routerOverride = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth"),
                    trustPolicyOverride = AuthorizedPilotEndpointTrustPolicy(
                        endpointConfig = PilotEndpointConfig(
                            approvedAuthOrigin = originHttp,
                            approvedAuthPath = "/prod/oauth",
                            pinnedApiOrigin = "https://127.0.0.1:${server.port}",
                            pinnedWssOrigin = "wss://127.0.0.1:${server.port}"
                        ),
                        allowedAuthOrigins = setOf(originHttp),
                        allowLoopbackForHermeticTests = true
                    ),
                    gatewayTrustPolicyOverride = TestLoopbackTrustPolicy(
                        setOf(
                            UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port),
                            UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
                        )
                    ),
                    allowedAuthOriginsOverride = setOf(originHttp),
                    expectedRuntimeIdentityOverride = expectedId,
                    installedApkVerificationOverride = InstalledApkVerification(true, apkSha, "TEST_VERIFIED"),
                    provisionedAuthStoreOverride = InMemoryProvisionedPilotAuthorizationStore().apply {
                        provision(invJson, invDigest)
                    }
                )
            )
            try {
                assertTrue(container.activateLivePilot(invDigest) is GateActivationResult.Activated)
                assertEquals(
                    setOf(PilotOperation.LOGIN, PilotOperation.USER_THING_LIST_GET),
                    container.pilotAuthorizationGate.currentContext?.permittedOperations
                )
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-inv","expired_time":3600}}"""
                    )
                )
                assertTrue(
                    container.pilotSessionController.submitCredentials(
                        AuthCredentials("+1", "pilot@test.invalid", "Secret1!".toCharArray())
                    ) is AuthResult.Success
                )
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"code":1,"data":{"thing_list":[{"thing_name":"laser_target_01","sub_type":"xs-x8-eu","thing_nickname":"Target","is_admin":true}]}}"""
                    )
                )
                assertNotNull(container.pilotSessionController.fetchAndValidateTargetInventory())
                assertEquals(2, server.requestCount)
                // Second inventory attempt rejected with 0 extra requests
                assertNull(container.pilotSessionController.fetchAndValidateTargetInventory())
                assertEquals(2, server.requestCount)
            } finally {
                container.close()
            }
        } finally {
            server.shutdown()
        }
    }
}
