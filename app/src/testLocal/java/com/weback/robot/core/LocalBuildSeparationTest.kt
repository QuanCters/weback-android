package com.weback.robot.core

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.BuildConfig
import com.weback.robot.MainActivity
import com.weback.robot.R
import com.weback.robot.WeBackApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalBuildSeparationTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun testLocalVariantIdentityManifestPermissionsAndCompositionRoot() {
        val app = ApplicationProvider.getApplicationContext<WeBackApplication>()
        val context: Context = app

        assertEquals("com.weback.robot.local", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.LOCAL_LAN_CAPABLE)
        assertFalse(BuildConfig.PILOT_INTERNET_CAPABLE)
        assertEquals("LOCAL_READ_ONLY", BuildConfig.APP_CONNECTIVITY_MODE)
        assertEquals("WeBack Robot Local Map (Read-Only)", context.getString(R.string.app_name))

        assertTrue(
            "Expected OfflineAppContainer in local variant, got ${app.container::class.java.name}",
            app.container is OfflineAppContainer
        )
        assertNull(app.container.pilotCapabilityBridge)

        @Suppress("DEPRECATION")
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue("Local manifest must declare android.permission.INTERNET", requested.contains("android.permission.INTERNET"))
        val allowedPerms = setOf(
            "android.permission.INTERNET",
            "com.weback.robot.local.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        val unexpectedPerms = requested - allowedPerms
        assertTrue("Unexpected permissions in local manifest: $unexpectedPerms", unexpectedPerms.isEmpty())

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
            composeRule.onNodeWithTag("btn_open_local_map").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("btn_open_pilot_readiness").assertDoesNotExist()
        }
    }

    @Test
    fun testCloudPilotClassesAndCloudTestSuitesAbsentFromLocalClasspath() {
        val forbiddenClasses = listOf(
            // Relocated production cloudPilot classes
            "com.weback.robot.PilotUiWorkflow",
            "com.weback.robot.core.PilotUiWorkflow",
            "com.weback.robot.core.DefaultAppContainer",
            "com.weback.robot.core.DefaultPilotContainer",
            "com.weback.robot.core.PilotAppContainer",
            "com.weback.robot.core.PilotCapabilityBridgeImpl",
            "com.weback.robot.core.PilotCapabilityBridgeImplKt",
            "com.weback.robot.core.PilotContainerTestHooks",
            "com.weback.robot.core.InMemoryMockTokenStore",
            "com.weback.robot.core.NonPersistentSessionStore",
            "com.weback.robot.app.pilot.PilotSessionController",
            "com.weback.robot.app.session.SessionCoordinator",
            "com.weback.robot.app.session.DeviceInventoryRepository",
            "com.weback.robot.protocol.auth.AuthGateway",
            "com.weback.robot.protocol.auth.OfflineFixtureAuthGateway",
            "com.weback.robot.protocol.auth.OkHttpAuthGateway",
            "com.weback.robot.protocol.auth.RegionEndpointRouter",
            "com.weback.robot.protocol.inventory.DeviceInventoryGateway",
            "com.weback.robot.protocol.inventory.OfflineFixtureInventoryGateway",
            "com.weback.robot.protocol.inventory.OkHttpDeviceInventoryGateway",
            "com.weback.robot.protocol.pilot.PilotCapabilityRestrictedAuthGateway",
            "com.weback.robot.protocol.ws.ConnectionConfig",
            "com.weback.robot.protocol.ws.OkHttpWebSocketTransport",
            "com.weback.robot.protocol.ws.RealtimeSessionTransport",
            "com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy",
            "com.weback.robot.security.EndpointTrustPolicy",
            "com.weback.robot.security.ExternalPilotAuthorizationLoader",
            "com.weback.robot.security.InstalledApkVerifier",
            "com.weback.robot.security.KeystoreAeadTokenStore",
            "com.weback.robot.security.EncryptedPayload",
            "com.weback.robot.security.KeyDeletionResult",
            "com.weback.robot.security.ConsumeAuthorizationResult",
            "com.weback.robot.security.PasswordDigestStrategy",
            "com.weback.robot.security.PilotSanitizedExporter",
            "com.weback.robot.security.TokenStore",
            "com.weback.robot.security.TokenSaveResult",
            "com.weback.robot.security.TokenLoadResult",
            "com.weback.robot.security.TokenClearResult",
            "com.weback.robot.security.UriValidator",
            "com.weback.robot.ui.pilot.PilotReadinessSheetKt",
            // Relocated testCloudPilot test suites
            "com.weback.robot.ConnectivityRecoveryTest",
            "com.weback.robot.core.AppContainerTest",
            "com.weback.robot.core.PilotBuildSeparationTest",
            "com.weback.robot.app.pilot.Fct004CApprovedOperationScopeTest",
            "com.weback.robot.app.local.LocalAppContainerNonFallbackTest",
            "com.weback.robot.ui.map.LocalMapActivityNavigationTest",
            // Deferred L04B live-TCP classes must not exist yet in L04A
            "com.weback.robot.protocol.local.ReceiveOnlyTcpSource",
            "com.weback.robot.security.LocalLanTrustPolicy",
            "com.weback.robot.security.LocalDiagnosticSanitizer"
        )

        val presentClasses = mutableListOf<String>()
        for (fqcn in forbiddenClasses) {
            try {
                Class.forName(fqcn, false, this::class.java.classLoader)
                presentClasses.add(fqcn)
            } catch (_: ClassNotFoundException) {
                // Expected structural absence
            }
        }
        assertTrue(
            "Forbidden cloudPilot / testCloudPilot / deferred L04B classes found in local classpath: $presentClasses",
            presentClasses.isEmpty()
        )
    }
}
