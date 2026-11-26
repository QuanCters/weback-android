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
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.security.FileBackedProvisionedPilotAuthorizationStore
import com.weback.robot.security.InstalledApkVerification
import com.weback.robot.security.ProvisionedAuthorizationLoadResult
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class Fct004CDurableConsumptionFaultTest {

    private lateinit var context: Context
    private lateinit var authDir: File
    private lateinit var server: MockWebServer

    private val commitSha = "c7747387e4fccc269c693b24079ba9e71ebbebb4"
    private val apkSha = "b".repeat(64)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        authDir = File(context.noBackupFilesDir, FileBackedProvisionedPilotAuthorizationStore.DIRECTORY_NAME)
        resetPermissionsAndDelete(authDir)
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        resetPermissionsAndDelete(authDir)
        server.shutdown()
    }

    private fun resetPermissionsAndDelete(dir: File) {
        if (dir.exists()) {
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
            dir.setReadable(true, true)
            dir.walkBottomUp().forEach { f ->
                f.setWritable(true, true)
                f.setReadable(true, true)
                if (f.isDirectory) f.setExecutable(true, true)
            }
            dir.deleteRecursively()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun validRecordJson(authId: String, account: String = "pilot-fault@test.invalid"): String {
        val originHttp = "http://127.0.0.1:${server.port}"
        return """
            {
              "authorization_id": "$authId",
              "authorized_operator": "Operator-Fault-Test",
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
                "authorized_account_identifier": "$account",
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
                "offline_device_checklist_passed": true,
                "physical_device_monotonic_expiry_verified": true
              }
            }
        """.trimIndent()
    }

    private fun provisionRecord(authId: String, account: String = "pilot-fault@test.invalid"): String {
        authDir.mkdirs()
        val bytes = validRecordJson(authId, account).toByteArray(Charsets.UTF_8)
        File(authDir, FileBackedProvisionedPilotAuthorizationStore.RECORD_FILE_NAME).writeBytes(bytes)
        val digest = sha256Hex(bytes)
        File(authDir, FileBackedProvisionedPilotAuthorizationStore.DIGEST_FILE_NAME).writeText(digest)
        return digest
    }

    private fun testContainer(withApkHook: Boolean = true): DefaultAppContainer {
        val originHttp = "http://127.0.0.1:${server.port}"
        return DefaultAppContainer(
            context = context,
            authClock = AuthMonotonicClock { 10_000L },
            pilotTestHooks = PilotContainerTestHooks(
                allowedAuthOriginsOverride = setOf(originHttp),
                expectedRuntimeIdentityOverride = ExpectedCandidateIdentity(
                    candidateCommitSha = commitSha,
                    applicationId = "com.weback.robot.pilot",
                    versionName = BuildConfig.VERSION_NAME,
                    apkSha256 = apkSha
                ),
                installedApkVerificationOverride = if (withApkHook) {
                    InstalledApkVerification(true, apkSha, "TEST_VERIFIED")
                } else {
                    null
                }
            )
        )
    }

    @Test
    fun unreadableMalformedAndUnwritableLedgerFailClosedWithZeroNetwork() {
        if (!BuildConfig.PILOT_INTERNET_CAPABLE) return

        // 1. Existing unreadable ledger (directory at consumed_records.txt path) -> FAIL CLOSED
        val digest1 = provisionRecord("FCT004C-LEDGER-UNREADABLE")
        val ledgerFile = File(authDir, FileBackedProvisionedPilotAuthorizationStore.CONSUMED_LEDGER_FILE_NAME)
        ledgerFile.mkdirs()
        testContainer().use { c ->
            assertTrue(c.loadProvisionedLiveAuthorization() is ProvisionedAuthorizationLoadResult.Invalid)
            val res = c.activateLivePilot(digest1)
            assertTrue("Unreadable ledger must reject activation, got $res", res is GateActivationResult.Rejected)
            assertFalse(c.pilotAuthorizationGate.isAuthorized)
            assertEquals(0, server.requestCount)
        }
        ledgerFile.deleteRecursively()

        // 2. Malformed ledger -> FAIL CLOSED
        val digest2 = provisionRecord("FCT004C-LEDGER-MALFORMED")
        ledgerFile.writeText("corrupted_garbage_without_delimiter\n")
        testContainer().use { c ->
            assertTrue(c.loadProvisionedLiveAuthorization() is ProvisionedAuthorizationLoadResult.Invalid)
            val res = c.activateLivePilot(digest2)
            assertTrue("Malformed ledger must reject activation, got $res", res is GateActivationResult.Rejected)
            assertFalse(c.pilotAuthorizationGate.isAuthorized)
            assertEquals(0, server.requestCount)
        }
        ledgerFile.delete()

        // 3. Ledger write failure (consumed_records.txt is read-only) -> FAIL CLOSED
        val digest3 = provisionRecord("FCT004C-LEDGER-WRITEFAIL")
        ledgerFile.writeText("")
        ledgerFile.setWritable(false, false)
        try {
            testContainer().use { c ->
                val res = c.activateLivePilot(digest3)
                assertTrue("Ledger write failure must reject activation, got $res", res is GateActivationResult.Rejected)
                assertFalse(c.pilotAuthorizationGate.isAuthorized)
                assertEquals(0, server.requestCount)
            }
        } finally {
            ledgerFile.setWritable(true, true)
            ledgerFile.delete()
        }
    }

    @Test
    fun reusedAuthIdReusedDigestActivationFailureAfterConsumptionAndPendingDeleteFailure() {
        if (!BuildConfig.PILOT_INTERNET_CAPABLE) return

        // 1. Activation failure AFTER durable consumption (gate has incomplete cleanup):
        // Record MUST be durably consumed before gate activation and NEVER restored when gate rejects!
        val digestFailGate = provisionRecord("FCT004C-POSTCONSUME-FAIL")
        testContainer().use { c ->
            val cleanupTok = c.pilotAuthorizationGate.beginCleanup(PilotAbortReason.UserEmergencyDisconnect)
            c.pilotAuthorizationGate.completeCleanup(cleanupTok, storageCleared = false)
            val failRes = c.activateLivePilot(digestFailGate)
            assertTrue("Gate with incomplete cleanup must reject", failRes is GateActivationResult.Rejected)
            assertFalse(c.pilotAuthorizationGate.isAuthorized)

            // Resolve incomplete cleanup, re-provision exact same authId/digest -> MUST STILL be rejected as consumed!
            val retryTok = c.pilotAuthorizationGate.beginCleanup(PilotAbortReason.UserEmergencyDisconnect)
            c.pilotAuthorizationGate.completeCleanup(retryTok, storageCleared = true)

            provisionRecord("FCT004C-POSTCONSUME-FAIL")
            val replayDigestRes = c.activateLivePilot(digestFailGate)
            assertTrue("Consumed digest must never be restored after gate rejection", replayDigestRes is GateActivationResult.Rejected)

            // Re-provision different content (different digest) with SAME authorization_id -> rejected!
            val newDigestSameId = provisionRecord("FCT004C-POSTCONSUME-FAIL", account = "other-acct@test.invalid")
            val replayIdRes = c.activateLivePilot(newDigestSameId)
            assertTrue("Consumed authorization_id must reject even with new digest", replayIdRes is GateActivationResult.Rejected)
            assertFalse(c.pilotAuthorizationGate.isAuthorized)
            assertEquals(0, server.requestCount)
        }

        // 2. Pending-record deletion failure is reported accurately and never activates live gate
        resetPermissionsAndDelete(authDir)
        val digestDelFail = provisionRecord("FCT004C-DELETE-FAIL")
        val ledgerFile = File(authDir, FileBackedProvisionedPilotAuthorizationStore.CONSUMED_LEDGER_FILE_NAME)
        ledgerFile.writeText("")
        authDir.setWritable(false, false)
        try {
            testContainer().use { c ->
                val res = c.activateLivePilot(digestDelFail)
                assertTrue("Pending record deletion failure must reject activation, got $res", res is GateActivationResult.Rejected)
                val msg = (res as GateActivationResult.Rejected).safeMessage
                assertTrue("Expected deletion failure message, got: $msg", msg.contains("delet", ignoreCase = true))
                assertFalse(c.pilotAuthorizationGate.isAuthorized)
                assertTrue(
                    "Durable consumption must still persist even when pending file deletion fails",
                    ledgerFile.readText().contains("FCT004C-DELETE-FAIL|$digestDelFail")
                )
            }
        } finally {
            authDir.setWritable(true, true)
        }
    }

    @Test
    fun realRuntimeModeRejectsUnsupportedOrMissingInstalledApkVerification() {
        if (!BuildConfig.PILOT_INTERNET_CAPABLE) return

        val digest = provisionRecord("FCT004C-REAL-APK-UNSUPPORTED")
        // 1. DefaultAppContainer without installedApkVerificationOverride (real-runtime verifier in Robolectric
        // where sourceDir is absent/not matching) MUST reject fail-closed!
        testContainer(withApkHook = false).use { c ->
            val review = c.loadProvisionedLiveAuthorization()
            assertTrue("Real-runtime mode must reject unsupported/mismatched APK verification in review, got $review", review is ProvisionedAuthorizationLoadResult.Invalid)
            val act = c.activateLivePilot(digest)
            assertTrue("Real-runtime mode must reject activation when installed APK verification fails/unsupported, got $act", act is GateActivationResult.Rejected)
            assertFalse(c.pilotAuthorizationGate.isAuthorized)
            assertEquals(0, server.requestCount)
        }

        // 2. Explicit supported=false (APK hashing failure on Android) MUST also reject fail-closed!
        DefaultAppContainer(
            context = context,
            authClock = AuthMonotonicClock { 10_000L },
            pilotTestHooks = PilotContainerTestHooks(
                allowedAuthOriginsOverride = setOf("http://127.0.0.1:${server.port}"),
                expectedRuntimeIdentityOverride = ExpectedCandidateIdentity(
                    candidateCommitSha = commitSha,
                    applicationId = "com.weback.robot.pilot",
                    versionName = BuildConfig.VERSION_NAME,
                    apkSha256 = apkSha
                ),
                installedApkVerificationOverride = InstalledApkVerification(
                    supported = false,
                    actualApkSha256 = null,
                    detail = "UNSUPPORTED_APK_HASH_FAILED"
                )
            )
        ).use { c ->
            val review = c.loadProvisionedLiveAuthorization()
            assertTrue("Unsupported APK verification must reject review, got $review", review is ProvisionedAuthorizationLoadResult.Invalid)
            val act = c.activateLivePilot(digest)
            assertTrue("Unsupported APK verification must reject activation, got $act", act is GateActivationResult.Rejected)
            assertFalse(c.pilotAuthorizationGate.isAuthorized)
            assertEquals(0, server.requestCount)
        }
    }
}
