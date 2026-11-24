# FCT-001 Technical Design — Android Foundation & First Runnable APK

**Change ID:** `FCT-001`  
**Milestone:** `FCT-001`  
**Document ID:** `SPEC-DES-FCT001`  
**Version:** `1.0.0`  
**Status:** `DRAFT (Proposed for G3 Implementation Authorization)`  
**Author:** Principal Android Architect  
**Effective Date:** 2026-11-24  

---

## 1. Architectural Overview

`FCT-001` establishes the foundational Android application module (`:app`) for the WeBack clean-room implementation. In accordance with `specs/tech-stack.md`, the architecture employs a single-module structure with package-level separation of concerns, declarative Jetpack Compose UI with Material 3, a pure Kotlin domain model, manual dependency injection via `AppContainer`, and deterministic Gradle Wrapper build orchestration.

```mermaid
graph TD
    subgraph "Single Application Module (:app)"
        subgraph "com.weback.robot.ui (Jetpack Compose & Material 3)"
            MainActivity["MainActivity (ComponentActivity)"]
            Theme["WeBackTheme (Material 3 Tokens)"]
            DashboardScreen["DashboardScreen (Composable)"]
            ViewModel["DashboardViewModel (MVI / StateFlow)"]
        end

        subgraph "com.weback.robot.domain (Pure Kotlin — Zero android.*)"
            RobotState["RobotState (Data Class)"]
            TelemetryModel["TelemetryData (Value Objects)"]
        end

        subgraph "com.weback.robot.protocol (Clean-Room Transport Contracts)"
            TransportInterface["RobotTransport (Interface)"]
            SimulatorMock["SimulatorRobotTransport (Offline Default)"]
        end

        subgraph "com.weback.robot.core (Infrastructure & DI)"
            AppContainer["AppContainer (Manual DI Graph)"]
            DispatcherProvider["CoroutineDispatcherProvider"]
        end
    end

    MainActivity --> Theme
    MainActivity --> DashboardScreen
    DashboardScreen --> ViewModel
    ViewModel --> RobotState
    ViewModel --> TransportInterface
    TransportInterface <|.. SimulatorMock
    AppContainer --> ViewModel
    AppContainer --> SimulatorMock
```

---

## 2. Repository Layout & Target Directory Tree

```
weback-android/
├── .github/
│   └── workflows/
│       └── ci.yml                      # Updated with Android build & test execution
├── app/
│   ├── build.gradle.kts                # Application module build script (minSdk 31, JVM target 17)
│   ├── proguard-rules.pro              # Release Proguard configuration
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml     # Application manifest (com.weback.robot, minSdk 31)
│       │   ├── java/com/weback/robot/
│       │   │   ├── MainActivity.kt     # Launch activity hosting Compose UI
│       │   │   ├── WeBackApp.kt        # Application subclass initializing AppContainer
│       │   │   ├── core/
│       │   │   │   ├── AppContainer.kt # Pure Kotlin manual dependency injection
│       │   │   │   └── Dispatchers.kt  # Coroutine dispatcher provider
│       │   │   ├── domain/
│       │   │   │   └── model/
│       │   │   │       └── RobotState.kt # Pure Kotlin state models (zero android.*)
│       │   │   ├── protocol/
│       │   │   │   ├── RobotTransport.kt # Transport interface
│       │   │   │   └── SimulatorTransport.kt # Offline mock transport
│       │   │   └── ui/
│       │   │       ├── screens/
│       │   │       │   └── DashboardScreen.kt # Compose dashboard with simulator badge
│       │   │       ├── theme/
│       │   │       │   ├── Color.kt    # Material 3 color palette
│       │   │       │   ├── Theme.kt    # Material 3 theme composable
│       │   │       │   └── Type.kt     # Material 3 typography definitions
│       │   │       └── viewmodel/
│       │   │           └── DashboardViewModel.kt # StateFlow-backed UI ViewModel
│       │   └── res/
│       │       ├── values/
│       │       │   └── strings.xml     # Application name and localized strings
│       │       └── xml/
│       │           └── network_security_config.xml # Strict cleartext traffic denial
│       └── test/
│           └── java/com/weback/robot/
│               ├── core/
│               │   └── AppContainerTest.kt # Container injection integrity tests
│               ├── domain/
│               │   └── RobotStateTest.kt   # Pure domain model unit tests
│               └── ui/
│                   └── DashboardViewModelTest.kt # ViewModel StateFlow tests
├── gradle/
│   ├── libs.versions.toml              # Pinned version catalog (Kotlin 2.1.21, AGP 8.7.2)
│   └── wrapper/
│       ├── gradle-wrapper.jar          # Official binary (SHA-256 2db75c40782f...)
│       └── gradle-wrapper.properties   # Wrapper properties (Gradle 8.10.2 + distributionSha256Sum)
├── build.gradle.kts                    # Root build script
├── settings.gradle.kts                 # Project settings (:app module)
├── gradlew                             # POSIX Gradle wrapper script
├── gradlew.bat                         # Windows Gradle wrapper script
├── policies/
│   ├── checks.json                     # Approved checks policy (FCT-000 baseline, digest 4a004ac8...)
│   ├── bootstrap_proposal.json         # Approved FCT-000 bootstrap record
│   └── fct001_checks_proposal.json     # Proposed FCT-001 extended checks policy
├── specs/
│   └── changes/
│       └── FCT-001/
│           ├── proposal.md             # Feature proposal
│           ├── requirements.md         # Requirements and ACs (minSdk 31)
│           ├── design.md               # Technical design & toolchain calibration
│           ├── validation.md           # Validation scenarios & test matrix
│           ├── tasks.md                # Vertical implementation tasks
│           ├── traceability.json       # Traceability mapping matrix
│           └── risk-assessment.md      # T4 Risk assessment and mitigation plan
└── verification/
    ├── checks/
    │   ├── apk_validator.py            # SDK-backed APK validator (apkanalyzer, apksigner, zipfile)
    │   ├── gradle_wrapper_check.py     # Verifies wrapper JAR & distribution SHA-256
    │   ├── gradle_test_adapter.py      # Structured JUnit XML test execution adapter
    │   └── android_smoke_test.py       # Explicitly authorized device smoke test
    └── selftests/                      # Verification harness selftests (including adapter & trust boundary tests)
```

---

## 3. Pinned Version Catalog (`gradle/libs.versions.toml`)

To guarantee repeatable compilation across developer workstations and CI runners, all dependency coordinates and plugins are centralized in `gradle/libs.versions.toml`. Bytecode compilation target is aligned to Java 17 for both Java and Kotlin:

```toml
[versions]
agp = "8.7.2"
kotlin = "2.1.21"
coreKtx = "1.15.0"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.10.01"
material3 = "1.3.1"
coroutines = "1.9.0"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3", version.ref = "material3" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

---

## 4. Manual Dependency Injection (`AppContainer`)

The application avoids third-party DI frameworks (Dagger/Hilt/Koin) in Milestone FCT-001 to prevent annotation-processing performance degradation and build complexity:

```kotlin
package com.weback.robot.core

import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.SimulatorRobotTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface AppContainer {
    val robotTransport: RobotTransport
    val dispatcherProvider: DispatcherProvider
}

class DefaultAppContainer : AppContainer {
    override val dispatcherProvider: DispatcherProvider by lazy {
        DefaultDispatcherProvider()
    }

    override val robotTransport: RobotTransport by lazy {
        SimulatorRobotTransport()
    }
}

interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}
```

---

## 5. Verification Harness Extension Check Architecture

Milestone `FCT-001` introduces specialized Python verification modules designed to extend the test runner without modifying approved policy before Gate G3:

### 5.1 Gradle Wrapper Integrity Checker (`gradle_wrapper_check.py`)
- **Execution:** `python3 verification/checks/gradle_wrapper_check.py`
- **Verification Logic:**
  1. Inspects `gradle/wrapper/gradle-wrapper.properties` and verifies `distributionUrl` specifies HTTPS and version `8.10.2`.
  2. Asserts `distributionSha256Sum` matches the official Gradle release binary ZIP checksum (`31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26`).
  3. Computes the SHA-256 digest of `gradle/wrapper/gradle-wrapper.jar`.
  4. Asserts the computed hash matches the official Gradle release Wrapper JAR checksum (`2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`).
  5. Fails closed with exit code 1 on any checksum mismatch.

### 5.2 Structured Test Execution Adapter (`gradle_test_adapter.py`)
- **Execution:** `python3 verification/checks/gradle_test_adapter.py --task testDebugUnitTest --results-dir app/build/test-results/testDebugUnitTest`
- **Verification Logic:**
  1. Records execution start timestamp and cleans prior XML results in the target results directory.
  2. Executes Gradle JVM unit test task (`./gradlew testDebugUnitTest --no-daemon`) via subprocess, preserving its exit code.
  3. Discovers fresh JUnit XML results in `app/build/test-results/testDebugUnitTest/` and rejects stale results (older than start timestamp) or missing XML files.
  4. Parses `<testsuite>` and `<testcase>` XML structures, validating declared counts against parsed child elements to reject contradictory counts.
  5. Enforces unique testcase identities (`classname#name`) across all XML reports to prevent duplicate counting.
  6. Rejects zero tests, malformed XML, unapproved skipped tests, and failed tests, preserving full failure stack traces in diagnostics.
  7. Emits standard human-readable results and machine-readable marker:
     `TEST_SUMMARY_JSON: {"total": T, "passed": P, "failures": F, "errors": E, "skipped": S, "expected_failures": 0, "unexpected_successes": 0}`
  8. Preserves the Gradle process exit code when non-zero.

### 5.3 Debug APK Artifact Validator (`apk_validator.py`)
- **Execution:** `python3 verification/checks/apk_validator.py`
- **Verification Logic:**
  1. Verifies `app/build/outputs/apk/debug/app-debug.apk` exists and is non-empty (arbitrary 1 MB size threshold is eliminated).
  2. Computes and records the SHA-256 digest of the built APK.
  3. Uses official Android SDK tools for manifest and signature inspection:
     - `apkanalyzer manifest application-id` asserts `com.weback.robot`.
     - `apkanalyzer manifest min-sdk` asserts `31`.
     - `apkanalyzer manifest target-sdk` asserts `35`.
     - `apkanalyzer manifest version-name` asserts `0.1.0-alpha`.
     - `apksigner verify --verbose` asserts valid cryptographic signature.
  4. Uses standard Python `zipfile` module to inspect internal archive structure:
     - Confirms `AndroidManifest.xml`, `classes.dex`, and `resources.arsc` are present and valid (`testzip()` returns None).
     - Scans `lib/` entries and asserts zero occurrences of prohibited vendor binaries (e.g. `libIOTCAPIs.so`, `libAVAPIs.so`).
  5. Fails closed with exit code 1 on any violation.

### 5.4 Device Installation & Smoke Test Semantics (`android_smoke_test.py`)
- **Execution:** `python3 verification/checks/android_smoke_test.py`
- **Verification Logic:**
  1. Requires explicit device serial selection via `WEBACK_TEST_DEVICE_SERIAL` environment variable or `--serial` parameter.
  2. Requires verified owner authorization; silent detection and selection of an arbitrary connected device is strictly prohibited.
  3. If no authorized device is configured or available:
     - Emits `[SMOKE TEST SKIPPED: No authorized test device specified]` and records `NOT_RUN` / `SKIPPED`.
     - Separates verification metrics: Build Verified, APK Validated, Installation NOT_RUN, Launch NOT_RUN. Does not claim a passed smoke test solely from a zero exit code.
  4. When an authorized device is provided:
     - Installs APK via `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`.
     - Launches activity via `adb -s <serial> shell am start -W -n com.weback.robot/.MainActivity`.
     - Verifies process survival for 5 seconds via `adb -s <serial> shell pidof com.weback.robot`.
     - Inspects application logcat via `adb -s <serial> logcat -d --pid=<pid>` for fatal exceptions. Does not clear unrelated device logs or modify external user packages.
     - Exits 0 on verified launch and process survival; exits 1 on crash or launch failure.

---

## 6. Local Toolchain Setup & Provisioning Guide

For local development on machines without an existing Android SDK (such as the verified workstation):

1. **Prerequisite:** OpenJDK 21 LTS (verified present at `/usr/lib/jvm/java-21-openjdk-amd64`), configured with JVM bytecode compilation target 17.
2. **SDK Provisioning Directory:** `~/.android/sdk` or `/home/quan/Android/Sdk`.
3. **Official Google Command-Line Tools:**
   - Download official `commandlinetools-linux-11076708_latest.zip` from Google repository:
     `https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip`
   - Verify official published SHA-256 checksum: `2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258`.
   - Extract to `~/.android/sdk/cmdline-tools/latest/`.
4. **License Acceptance & Package Installation:**
   ```bash
   yes | ~/.android/sdk/cmdline-tools/latest/bin/sdkmanager --licenses
   ~/.android/sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   ```
5. **Environment Configuration:**
   Export `ANDROID_HOME=~/.android/sdk` and prepend `$ANDROID_HOME/platform-tools` and `$ANDROID_HOME/cmdline-tools/latest/bin` to `PATH`.

---

## 7. Policy Activation & Transition Plan

To preserve continuous verification on both the baseline `main` branch and candidate implementation branches without invalidating repository status checks:

1. **Phase 1 — G3 Specification Approval (Current Baseline):**
   - Active approved policy remains `policies/checks.json` (SHA-256: `4a004ac8d07178c4164193d827983dfa5a7d86c5245fbcb6df69497e976137fd`).
   - Required checks: `spec-hygiene` and `harness-selftests`.
   - `APPROVED_POLICY_SHA256` is NOT modified at G3.
   - Proposed checks are staged in `policies/fct001_checks_proposal.json`.
2. **Phase 2 — Isolated Implementation & Verification:**
   - Tasks 1 through 8 are executed on an isolated feature branch/worktree.
   - Proposed Android checks are run in a clearly labeled diagnostic context using `--config policies/fct001_checks_proposal.json`.
   - The baseline checks continue to pass against `policies/checks.json`.
3. **Phase 3 — G6 Merge & Authoritative Policy Activation:**
   - Upon successful completion and candidate verification of all implementation deliverables, the Lead Maintainer authorizes activating the new policy.
   - In a single atomic commit, `policies/checks.json` is replaced with the approved check catalog, `APPROVED_POLICY_SHA256` is updated, and CI required status checks are synchronized.

---

## 8. Security & Integrity Invariants

- **C-004 (Zero Live Credentials):** No credentials or API keys will be committed in git.
- **C-010 (Transport Security):** Manifest enforces `cleartextTrafficPermitted="false"`. Network security config denies HTTP.
- **C-014 (Clean-Room Boundary):** Zero proprietary binaries or decompiled vendor Dalvik bytecode will be incorporated into `:app`. Observed legacy behaviors are reference-only.
