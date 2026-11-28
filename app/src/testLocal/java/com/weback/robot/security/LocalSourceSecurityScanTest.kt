package com.weback.robot.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalSourceSecurityScanTest {

    private fun resolveAppRoot(): File {
        val candidates = listOf(File(".").canonicalFile, File("app").canonicalFile)
        return candidates.firstOrNull { dir ->
            File(dir, "src/main/java").isDirectory && File(dir, "src/local/AndroidManifest.xml").isFile
        } ?: error("Cannot locate app module root from ${File(".").canonicalPath}")
    }

    @Test
    fun testMainAndLocalSourcesEnforceSecurityPrivacyAndCloudIsolation() {
        val appRoot = resolveAppRoot()
        val mainDir = File(appRoot, "src/main/java")
        val localDir = File(appRoot, "src/local/java")
        assertTrue(mainDir.isDirectory)
        assertTrue(localDir.isDirectory)

        val forbiddenTokens = listOf(
            "setDefaultSSLSocketFactory",
            "SSLContext.setDefault",
            "EncryptedSharedPreferences",
            "MasterKey",
            "FirebaseAnalytics",
            "com.yugong",
            "com.tutk",
            "com.weback.cleaner",
            "libIOTCAPIs",
            "libAVAPIs",
            "libtutk_ffmpeg",
            "X509TrustManager",
            "HostnameVerifier",
            "SecretKeySpec",
            "getSharedPreferences",
            "DefaultAppContainer",
            "DefaultPilotContainer",
            "PilotCapabilityBridgeImpl",
            "PilotUiWorkflow",
            "com.weback.robot.app.pilot",
            "com.weback.robot.app.session",
            "com.weback.robot.protocol.auth",
            "com.weback.robot.protocol.inventory",
            "com.weback.robot.protocol.pilot",
            "com.weback.robot.protocol.ws",
            "com.weback.robot.ui.pilot",
            "AuthorizedPilotEndpointTrustPolicy",
            "KeystoreAeadTokenStore",
            "TokenStore",
            "ReceiveOnlyTcpSource",
            "LocalLanTrustPolicy",
            "LocalDiagnosticSanitizer",
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.InetAddress"
        )

        val violations = mutableListOf<String>()
        listOf(mainDir, localDir).forEach { rootDir ->
            rootDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                var inBlockComment = false
                file.readLines().forEachIndexed { idx, rawLine ->
                    val trimmed = rawLine.trim()
                    if (inBlockComment) {
                        if (trimmed.contains("*/")) {
                            inBlockComment = false
                        }
                        return@forEachIndexed
                    }
                    if (trimmed.startsWith("/*")) {
                        if (!trimmed.contains("*/")) {
                            inBlockComment = true
                        }
                        return@forEachIndexed
                    }
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                        return@forEachIndexed
                    }
                    forbiddenTokens.forEach { token ->
                        if (trimmed.contains(token)) {
                            violations.add("${file.relativeTo(appRoot).path}:${idx + 1}: $token")
                        }
                    }
                }
            }
        }
        assertTrue("Prohibited tokens or cloud/L04B leaks found in main/local sources: $violations", violations.isEmpty())

        // Verify AndroidManifest boundaries directly
        val docFactory = DocumentBuilderFactory.newInstance()
        val mainManifest = docFactory.newDocumentBuilder().parse(File(appRoot, "src/main/AndroidManifest.xml"))
        assertEquals(0, mainManifest.getElementsByTagName("uses-permission").length)
        val appElems = mainManifest.getElementsByTagName("application")
        assertEquals(1, appElems.length)
        val appAttrs = appElems.item(0).attributes
        assertEquals("false", appAttrs.getNamedItem("android:usesCleartextTraffic")?.nodeValue)
        assertEquals("false", appAttrs.getNamedItem("android:allowBackup")?.nodeValue)

        val localManifest = docFactory.newDocumentBuilder().parse(File(appRoot, "src/local/AndroidManifest.xml"))
        val localPerms = localManifest.getElementsByTagName("uses-permission")
        assertEquals(1, localPerms.length)
        assertEquals(
            "android.permission.INTERNET",
            localPerms.item(0).attributes.getNamedItem("android:name")?.nodeValue
        )

        // Verify Gradle build script isolates cloudPilot/testCloudPilot exclusively to debug and pilot
        val gradleText = File(appRoot, "build.gradle.kts").readText()
        assertFalse("app/build.gradle.kts must not use destructive setSrcDirs", gradleText.contains("setSrcDirs"))
        assertTrue(gradleText.contains("testBuildType = \"pilot\""))
        assertFalse(gradleText.contains("getByName(\"local\").java.srcDir(\"src/cloudPilot"))
        assertFalse(gradleText.contains("getByName(\"testLocal\").java.srcDir(\"src/testCloudPilot"))
    }
}
