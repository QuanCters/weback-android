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
│   ├── build.gradle.kts                # Application module build script
│   ├── proguard-rules.pro              # Release Proguard configuration
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml     # Application manifest (com.weback.robot)
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
│   ├── libs.versions.toml              # Pinned version catalog
│   └── wrapper/
│       ├── gradle-wrapper.jar          # Official binary (SHA-256 verified)
│       └── gradle-wrapper.properties   # Wrapper properties (Gradle 8.10.2)
├── build.gradle.kts                    # Root build script
├── settings.gradle.kts                 # Project settings (:app module)
├── gradlew                             # POSIX Gradle wrapper script
├── gradlew.bat                         # Windows Gradle wrapper script
├── policies/
│   ├── checks.json                     # Approved checks policy (FCT-000 baseline)
│   ├── bootstrap_proposal.json         # Approved FCT-000 bootstrap record
│   └── fct001_checks_proposal.json     # Proposed FCT-001 extended checks policy
└── verification/
    ├── checks/
    │   ├── apk_validator.py            # Validates app-debug.apk identity and contents
    │   ├── gradle_wrapper_check.py     # Verifies wrapper JAR SHA-256
    │   └── android_smoke_test.py       # ADB install and launch smoke test
    └── selftests/                      # Verification harness selftests (106 tests)
```

---

## 3. Pinned Version Catalog (`gradle/libs.versions.toml`)

To guarantee reproducible compilation across machines, all dependency coordinates are centralized in `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.21"
coreKtx = "1.15.0"
lifecycleRuntimeKtx = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.10.01"
material3 = "1.3.1"
coroutines = "1.9.0"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3", version.ref = "material3" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
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

Milestone `FCT-001` introduces three specialized Python verification modules designed to extend the test runner without modifying approved policy before Gate G3:

### 5.1 Gradle Wrapper Integrity Checker (`gradle_wrapper_check.py`)
- **Execution:** `python3 verification/checks/gradle_wrapper_check.py`
- **Verification Logic:**
  1. Inspects `gradle/wrapper/gradle-wrapper.properties` and verifies `distributionUrl` specifies HTTPS and version `8.10.2`.
  2. Computes the SHA-256 digest of `gradle/wrapper/gradle-wrapper.jar`.
  3. Asserts the computed hash matches the official Gradle release checksum (`3229ae333d45c8ad0c9d7fe3160a2b005177242e20ff9d997f35b44ec6dbf737` for Gradle 8.10.2).
  4. Fails closed with exit code 1 if checksum does not match.

### 5.2 Debug APK Artifact Validator (`apk_validator.py`)
- **Execution:** `python3 verification/checks/apk_validator.py`
- **Verification Logic:**
  1. Verifies `app/build/outputs/apk/debug/app-debug.apk` exists and has size > 1 MB.
  2. Computes and records the SHA-256 digest of the built APK.
  3. Uses standard `zipfile` module to inspect internal APK structure:
     - Confirms `AndroidManifest.xml`, `classes.dex`, and `resources.arsc` are present.
     - Confirms zero occurrences of prohibited vendor binaries in `lib/` (e.g. `libIOTCAPIs.so`, `libAVAPIs.so`).
  4. Parses binary `AndroidManifest.xml` string pool to verify `package="com.weback.robot"`.
  5. Fails closed with exit code 1 on any violation.

### 5.3 Device Installation & Smoke Test (`android_smoke_test.py`)
- **Execution:** `python3 verification/checks/android_smoke_test.py`
- **Verification Logic:**
  1. Runs `adb devices` to check for connected emulators or authorized physical devices.
  2. If 0 devices connected, emits `[SMOKE TEST SKIPPED: No ADB device connected]` and exits with code 0 (environment-gated optional check).
  3. If device detected:
     - Installs APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
     - Launches activity: `adb shell am start -n com.weback.robot/.MainActivity`.
     - Polls `adb shell pidof com.weback.robot` for 5 seconds to verify process is alive.
     - Inspects `adb logcat -d` for fatal exceptions (`FATAL EXCEPTION: main`).
     - Exits 0 on successful launch, exits 1 on crash or launch failure.

---

## 6. Local Toolchain Setup & Provisioning Guide

For local development on machines without an existing Android SDK (such as the verified workstation):

1. **Prerequisite:** OpenJDK 21 LTS (verified present at `/usr/lib/jvm/java-21-openjdk-amd64`).
2. **SDK Provisioning Directory:** `~/.android/sdk` or `/home/quan/Android/Sdk`.
3. **Official Google Command-Line Tools:**
   - Download official `commandlinetools-linux-11076708_latest.zip` from Google repository.
   - Verify official SHA-256 checksum: `2d2d50857e4eb553692b6431bebb711564ea74e25f0da4e2a88cb61996e00178`.
   - Extract to `~/.android/sdk/cmdline-tools/latest/`.
4. **License Acceptance & Package Installation:**
   ```bash
   yes | ~/.android/sdk/cmdline-tools/latest/bin/sdkmanager --licenses
   ~/.android/sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   ```
5. **Environment Configuration:**
   Export `ANDROID_HOME=~/.android/sdk` and prepend `$ANDROID_HOME/platform-tools` and `$ANDROID_HOME/cmdline-tools/latest/bin` to `PATH`.

---

## 7. Security & Integrity Invariants

- **C-004 (Zero Live Credentials):** No credentials or API keys will be committed in git.
- **C-010 (Transport Security):** Manifest enforces `cleartextTrafficPermitted="false"`. Network security config denies HTTP.
- **C-014 (Clean-Room Boundary):** Zero proprietary binaries or decompiled vendor Dalvik bytecode will be incorporated into `:app`.
