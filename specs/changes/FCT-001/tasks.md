# FCT-001 Implementation Plan — Android Foundation & First Runnable APK

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the complete Android development toolchain, configure root and application Gradle builds with pinned version catalogs, implement clean architecture domain models and manual dependency injection (`AppContainer`), create a minimal Material 3 Compose UI dashboard, produce the first verifiable debug APK (`com.weback.robot`), and extend the verification harness with Android-specific quality gates.

**Architecture:** A clean-room Android application module (`:app`) structured into strict package boundaries (`ui`, `domain`, `protocol`, `core`), completely decoupled from legacy proprietary vendor binaries and decompiled packages. Pure Kotlin manual dependency injection via `AppContainer` avoids annotation processing overhead. Verification harness is extended with modular check scripts for Gradle wrapper integrity, Android Lint, JVM unit testing, APK artifact validation, and device smoke testing.

**Tech Stack:** Host OpenJDK 21 LTS, Java & Kotlin JVM bytecode compilation target 17, Android SDK Platform 35 (`compileSdk 35`, `targetSdk 35`, `minSdk 31`), Android Gradle Plugin 8.7.2, Gradle 8.10.2, Kotlin 2.1.21, Kotlin Compose compiler plugin 2.1.21, Jetpack Compose (BOM 2024.10.01), Material 3 1.3.1, Coroutines 1.9.0, Python 3.12+ (standard library only for harness checks).

**Spec:** [proposal.md](proposal.md), [requirements.md](requirements.md), [design.md](design.md), [validation.md](validation.md), [risk-assessment.md](risk-assessment.md).

## Global Constraints
- Zero proprietary vendor binaries (`libIOTCAPIs.so`, `libAVAPIs.so`) or legacy decompiled package references (`com.yugong.Backome`).
- Zero production cloud connections, live AWS IoT endpoints, or physical robot hardware communication during FCT-001.
- Zero credentials or tokens committed to git; simulator-first by default.
- Global cleartext HTTP traffic strictly disabled via Android Network Security Config.
- No heavy DI frameworks (Dagger, Hilt, Koin) or ORM libraries; pure Kotlin manual DI only.
- Verification harness check scripts must use Python standard library only; zero pip dependencies.
- Approved baseline checks (`spec-hygiene`, `harness-selftests`) must remain intact and passing.

## Review Focus
- **Toolchain Determinism:** Ensure all dependencies in `gradle/libs.versions.toml` are strictly pinned with no dynamic `+` versions.
- **Wrapper Integrity:** Automated SHA-256 validation of `gradle-wrapper.jar` (official digest `2db75c40782f...`) and distribution binary ZIP (`31c55713e402...`).
- **Domain Layer Purity:** Absolute separation of `com.weback.robot.domain` with zero imports from `android.*`.
- **Security Invariants:** Manifest and `network_security_config.xml` must prohibit cleartext traffic globally.
- **APK Verification:** Automated inspection verifies package ID `com.weback.robot`, minSdk 31, signature validity, and zero vendor SO binaries via official Android SDK tools (`apkanalyzer`, `apksigner`).

---

### Task 1: Android SDK & Toolchain Provisioning Plan

**Files:**
- Document: `docs/toolchain/ANDROID_SDK_SETUP.md`
- Script: `scripts/setup_android_sdk.sh`

**Interfaces:**
- Consumes: Host OpenJDK 21 LTS (`/usr/lib/jvm/java-21-openjdk-amd64`).
- Produces: Local Android SDK directory structure (`~/.android/sdk` or `$ANDROID_HOME`) containing `cmdline-tools/latest`, `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`.

- [ ] **Step 1: Author Android SDK Provisioning Script**
Create `scripts/setup_android_sdk.sh` that downloads official Google Android command-line tools `11076708_latest` (Linux) from `https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip`, verifies official published SHA-256 digest `2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258`, installs SDK packages non-interactively accepting licenses, and exports `ANDROID_HOME`.

- [ ] **Step 2: Document Toolchain Provisioning Guide**
Create `docs/toolchain/ANDROID_SDK_SETUP.md` documenting prerequisite Java 21 environment, bytecode compilation target 17, headless SDK installation, CI environment variable parity, and KVM hardware acceleration configuration.

- [ ] **Step 3: Verify Toolchain Detection**
Verify that `./scripts/setup_android_sdk.sh --check` correctly detects existing SDK or outputs actionable installation instructions.

---

### Task 2: Root Project & Version Catalog Configuration

**Files:**
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradlew`, `gradlew.bat`
- Create: `build.gradle.kts` (root)
- Create: `settings.gradle.kts` (root)

**Interfaces:**
- Consumes: Gradle 8.10.2 distribution.
- Produces: Pinned version catalog, root Gradle configuration, and executable wrapper scripts.

- [ ] **Step 1: Create Centralized Version Catalog**
Create `gradle/libs.versions.toml` declaring pinned versions:
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

- [ ] **Step 2: Create Root build.gradle.kts and settings.gradle.kts**
Configure `settings.gradle.kts` with `pluginManagement`, `dependencyResolutionManagement` using `FAIL_ON_PROJECT_REPOS`, and `include(":app")`. Configure root `build.gradle.kts` applying plugins with `apply false`.

- [ ] **Step 3: Setup Official Gradle 8.10.2 Wrapper**
Populate `gradle/wrapper/gradle-wrapper.properties` with HTTPS distribution URL for Gradle 8.10.2 and `distributionSha256Sum = 31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26`. Place verified `gradle-wrapper.jar` matching official SHA-256 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`. Make `gradlew` executable (`chmod +x gradlew`).

---

### Task 3: Application Module (`:app`) & Security Manifest Configuration

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/network_security_config.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/colors.xml`
- Create: `app/.gitignore`

**Interfaces:**
- Consumes: Pinned dependencies from version catalog.
- Produces: Compiled `:app` module configuration, hardened Android manifest, and network security configuration.

- [ ] **Step 1: Author app/build.gradle.kts**
Configure `:app` build script with plugins `android-application`, `kotlin-android`, `compose-compiler`. Set `namespace = "com.weback.robot"`, `compileSdk = 35`, `defaultConfig { applicationId = "com.weback.robot"; minSdk = 31; targetSdk = 35; versionCode = 1; versionName = "0.1.0-alpha" }`, Compose build features enabled, Java 17 / Kotlin JVM target 17 compilation compatibility, and Android Lint options (`abortOnError = true`).

- [ ] **Step 2: Author Hardened AndroidManifest.xml**
Create `app/src/main/AndroidManifest.xml` with:
- `android:usesCleartextTraffic="false"`
- `android:networkSecurityConfig="@xml/network_security_config"`
- `android:allowBackup="false"`
- Single launch activity `MainActivity` with exported intent filter (`MAIN` / `LAUNCHER`).

- [ ] **Step 3: Author network_security_config.xml**
Create `app/src/main/res/xml/network_security_config.xml` declaring `<base-config cleartextTrafficPermitted="false" />`.

- [ ] **Step 4: Create Baseline XML Resources**
Create `strings.xml` defining `app_name = "WeBack Robot Control"`, `app_version = "v0.1.0-alpha (FCT-001 Foundation)"`, and basic UI labels.

---

### Task 4: Clean Architecture Domain & Core Infrastructure (`AppContainer`)

**Files:**
- Create: `app/src/main/java/com/weback/robot/domain/model/RobotState.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/RobotMode.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/RobotTransport.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/SimulatorRobotTransport.kt`
- Create: `app/src/main/java/com/weback/robot/core/DispatcherProvider.kt`
- Create: `app/src/main/java/com/weback/robot/core/AppContainer.kt`
- Create: `app/src/main/java/com/weback/robot/WeBackApplication.kt`

**Interfaces:**
- Consumes: Pure Kotlin standard library and coroutines.
- Produces: Domain models, simulator protocol transport, and manual DI application container.

- [ ] **Step 1: Implement Pure Kotlin Domain State Models**
Implement `RobotState` immutable data class in `com.weback.robot.domain.model` containing battery level, robot working mode (`DOCK`, `CLEAN`, `PAUSED`, `IDLE`), dustbin status, error code, and docked predicate (`isDocked`). Strictly prohibit `android.*` imports.

- [ ] **Step 2: Implement Robot Transport & Simulator Mock**
Implement `RobotTransport` interface in `com.weback.robot.protocol` with state flow emission and command dispatch. Implement `SimulatorRobotTransport` emitting initial docked telemetry (100% battery, docked, bin clean).

- [ ] **Step 3: Implement Dispatcher Provider & AppContainer**
Implement `DispatcherProvider` interface (providing `main`, `io`, `default` dispatchers). Implement `AppContainer` interface and `DefaultAppContainer` providing singleton transport and dispatchers. Create `WeBackApplication` initializing `AppContainer`.

---

### Task 5: Jetpack Compose UI & Material 3 Screen Presentation

**Files:**
- Create: `app/src/main/java/com/weback/robot/ui/theme/Color.kt`
- Create: `app/src/main/java/com/weback/robot/ui/theme/Type.kt`
- Create: `app/src/main/java/com/weback/robot/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/weback/robot/ui/DashboardViewModel.kt`
- Create: `app/src/main/java/com/weback/robot/ui/screens/DashboardScreen.kt`
- Create: `app/src/main/java/com/weback/robot/MainActivity.kt`

**Interfaces:**
- Consumes: Compose Material 3 design tokens and domain state via `DashboardViewModel`.
- Produces: Launch activity and responsive, accessible dashboard UI.

- [ ] **Step 1: Implement Material 3 Theme System**
Create `Color.kt`, `Type.kt`, and `Theme.kt` defining color schemes (light and dark), typography hierarchy, and `WeBackTheme` composable wrapper.

- [ ] **Step 2: Implement DashboardViewModel**
Implement `DashboardViewModel` exposing immutable `StateFlow<RobotUiState>`. Expose robot name, simulator badge status, battery percentage, work state string, and dustbin status.

- [ ] **Step 3: Implement DashboardScreen Composable**
Create `DashboardScreen` displaying:
1. Top app bar with `"WeBack Robot Control"`.
2. Header with version label `"v0.1.0-alpha (FCT-001 Foundation)"`.
3. Offline status badge: `"Simulator Mode (Offline)"`.
4. Telemetry card displaying Battery (100%), State (Docked), Dustbin (Clean).

- [ ] **Step 4: Implement MainActivity**
Create `MainActivity` extending `ComponentActivity`. Set Compose content wrapped in `WeBackTheme` and render `DashboardScreen` backed by `DashboardViewModel`.

---

### Task 6: Unit Test Implementation & Test Adapter Integration

**Files:**
- Create: `app/src/test/java/com/weback/robot/domain/RobotStateTest.kt`
- Create: `app/src/test/java/com/weback/robot/ui/DashboardViewModelTest.kt`
- Create: `app/src/test/java/com/weback/robot/core/AppContainerTest.kt`
- Create: `app/src/test/java/com/weback/robot/testutils/TestDispatcherProvider.kt`

**Interfaces:**
- Consumes: Kotlin test runners and JUnit 4.
- Produces: Passing JVM unit test suite with structured JUnit XML output.

- [ ] **Step 1: Write RobotStateTest**
Verify default state values, immutability of data class copy, state equality, and `isDocked` predicate logic.

- [ ] **Step 2: Write AppContainerTest**
Verify container instantiation, singleton instance scoping of `RobotTransport`, and dispatcher resolution.

- [ ] **Step 3: Write DashboardViewModelTest**
Using `StandardTestDispatcher`, verify initial `RobotUiState` emission, simulator mode flag, and telemetry presentation.

- [ ] **Step 4: Verify Unit Test Execution**
Run `python3 verification/checks/gradle_test_adapter.py --task testDebugUnitTest --results-dir app/build/test-results/testDebugUnitTest` and confirm generated JUnit XML reports, freshness validation, and emitted structured metrics (`TEST_SUMMARY_JSON:`).

---

### Task 7: Verification Harness Extension Modules

**Files:**
- Create: `verification/checks/gradle_wrapper_check.py`
- Create: `verification/checks/gradle_test_adapter.py`
- Create: `verification/checks/apk_validator.py`
- Create: `verification/checks/android_smoke_test.py`
- Create: `verification/selftests/test_gradle_wrapper_check.py`
- Create: `verification/selftests/test_gradle_test_adapter.py`
- Create: `verification/selftests/test_apk_validator.py`

**Interfaces:**
- Consumes: Python 3 standard library, Android SDK tools (apkanalyzer, apksigner), compiled APK, and ADB device state.
- Produces: Deterministic exit codes (0 for pass, 1 for fail, 2 for error) and structured log output for verification harness.

- [ ] **Step 1: Implement gradle_wrapper_check.py**
Inspect `gradle/wrapper/gradle-wrapper.properties` (verifying HTTPS, Gradle 8.10.2 version, and `distributionSha256Sum` equals `31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26`). Compute SHA-256 of `gradle/wrapper/gradle-wrapper.jar` and verify match against official release checksum `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`.

- [ ] **Step 2: Implement gradle_test_adapter.py**
Implement `verification/checks/gradle_test_adapter.py` executing Gradle unit tests via subprocess, verifying XML timestamp freshness, validating declared counts against child elements, preventing duplicate test ID counting, rejecting contradictory counts or unapproved skipped tests, preserving failure stack traces, and emitting the structured `TEST_SUMMARY_JSON:` contract.

- [ ] **Step 3: Implement apk_validator.py**
Implement `verification/checks/apk_validator.py` using official Android SDK tools (`apkanalyzer` for manifest application-id `com.weback.robot`, minSdk `31`, targetSdk `35`, version-name `0.1.0-alpha`; `apksigner verify` for signature). Use Python `zipfile` to verify archive integrity and assert zero occurrences of prohibited vendor binaries (`libIOTCAPIs.so`, `libAVAPIs.so`) in `lib/`. Eliminate arbitrary 1 MB size threshold.

- [ ] **Step 4: Implement android_smoke_test.py**
Require explicit device serial selection via `WEBACK_TEST_DEVICE_SERIAL` and owner authorization. If unauthorized or absent, record `NOT_RUN` / `SKIPPED (No authorized device)`. When authorized, execute `adb install -r`, start `MainActivity`, verify process survival for 5s via PID check, and confirm zero fatal exceptions in logcat without clearing unrelated device logs.

- [ ] **Step 5: Implement Selftests for New Harness Checks**
Create `test_gradle_wrapper_check.py`, `test_gradle_test_adapter.py`, and `test_apk_validator.py` in `verification/selftests/`, testing valid inputs, corrupted archives, checksum mismatches, contradictory counts, and prohibited binary detection.

---

### Task 8: Build Assembly, Lint Execution & Verification Gate

**Files:**
- Modify: `app/build.gradle.kts` (lint options)
- Artifact: `app/build/outputs/apk/debug/app-debug.apk`
- Artifact: `app/build/reports/lint-results-debug.xml`

**Interfaces:**
- Consumes: Complete project source code and Android SDK.
- Produces: Compiled debug APK, lint audit report, and candidate verification evidence.

- [ ] **Step 1: Execute Android Lint**
Run `./gradlew lintDebug --no-daemon` and verify 0 errors and zero security regressions.

- [ ] **Step 2: Execute Debug APK Assembly**
Run `./gradlew assembleDebug --no-daemon` and confirm generation of `app-debug.apk`.

- [ ] **Step 3: Run Full Verification Suite**
Execute proposed FCT-001 checks catalog:
- `spec-hygiene`
- `harness-selftests`
- `gradle-wrapper-validation`
- `android-lint`
- `android-unit-tests`
- `android-build`
- `apk-validation`
- `android-smoke-test` (environment-gated)

Confirm all checks pass and generate complete verification evidence package.
