package com.weback.robot.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DomainPurityTest {

    @Test
    fun testDomainPackageHasZeroFrameworkOrNetworkImports() {
        val candidateRoots = listOf(
            File("src/main/java/com/weback/robot/domain"),
            File("app/src/main/java/com/weback/robot/domain")
        )
        val domainDir = candidateRoots.firstOrNull { it.isDirectory }
            ?: error("Could not locate domain directory from ${File(".").absolutePath}")

        val ktFiles = domainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Expected Kotlin source files in domain package", ktFiles.isNotEmpty())

        val prohibitedPrefixes = listOf(
            "import android.",
            "import androidx.",
            "import kotlinx.coroutines.",
            "import kotlinx.serialization.",
            "import okhttp3."
        )

        val violations = mutableListOf<String>()
        for (file in ktFiles) {
            file.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                for (prefix in prohibitedPrefixes) {
                    if (trimmed.startsWith(prefix)) {
                        violations.add("${file.name}:${idx + 1}: $trimmed")
                    }
                }
            }
        }

        assertTrue(
            "Prohibited imports detected in com.weback.robot.domain: $violations",
            violations.isEmpty()
        )
    }
}
