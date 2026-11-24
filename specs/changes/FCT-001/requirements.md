# FCT-001 Requirements Specification — Android Foundation & First Runnable APK

**Change ID:** `FCT-001`  
**Milestone:** `FCT-001`  
**Document ID:** `SPEC-REQ-FCT001`  
**Version:** `1.0.0`  
**Status:** `DRAFT (Proposed for G3 Implementation Authorization)`  
**Author:** Principal Android Architect  
**Effective Date:** 2026-11-24  

---

## 1. Overview & Traceability Context

This document specifies the functional, technical, and architectural requirements for Milestone `FCT-001`: Android Foundation & First Runnable APK. In accordance with Verification-First SDD 2.0 and the Project Constitution ([specs/constitution.md](../../../specs/constitution.md)), every requirement is decomposed into verifiable acceptance criteria mapped directly to implementation tasks and verification test oracles in `specs/changes/FCT-001/traceability.json`.

---

## 2. Requirements & Acceptance Criteria

### REQ-ANDR-101 (MUST): Pinned Android Development Toolchain & Gradle Wrapper

The project build system must establish a fully pinned, deterministic build environment using the Gradle Wrapper and a centralized Gradle version catalog.

- **AC-101.1:** The project must include `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.properties` configured for Gradle `8.10.2` over HTTPS (`https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip`) and must declare `distributionSha256Sum = 31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26`.
- **AC-101.2:** The binary `gradle/wrapper/gradle-wrapper.jar` must match the official Gradle `8.10.2` release SHA-256 checksum (`2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`), verified by automated check `gradle-wrapper-validation`.
- **AC-101.3:** The project must define `gradle/libs.versions.toml` version catalog pinning Android Gradle Plugin (AGP) `8.7.2`, Kotlin `2.1.21`, Kotlin Compose compiler plugin `2.1.21`, Jetpack Compose BOM `2024.10.01`, Material 3 `1.3.1`, AndroidX Core KTX `1.15.0`, Lifecycle ViewModel Compose & Runtime `2.8.7`, Activity Compose `1.9.3`, and Coroutines `1.9.0` with exact version numbers (no dynamic `+` versions or ranges).
- **AC-101.4:** The build must execute successfully under host OpenJDK 21 LTS targeting Java and Kotlin JVM bytecode compilation target 17, without requiring third-party build daemons or deprecated Gradle APIs.

### REQ-ANDR-102 (MUST): Application Module Configuration & Identity Isolation

The application build must configure a single Android application module (`:app`) with clean-room package identity, strictly isolated from legacy proprietary vendor code.

- **AC-102.1:** Root `settings.gradle.kts` must include `:app` as the single application module and configure plugin repositories (`google()`, `mavenCentral()`).
- **AC-102.2:** Module `app/build.gradle.kts` must set `namespace = "com.weback.robot"` and `applicationId = "com.weback.robot"`.
- **AC-102.3:** The SDK boundaries must be strictly configured as: `compileSdk = 35`, `targetSdk = 35`, and `minSdk = 31` (Android 12), ensuring platform-level Scoped Storage, modern Bluetooth permissions, and standard hardware-backed cryptographic primitives. Preserved unless the Product Owner separately approves broader legacy Android support.
- **AC-102.4:** The project must contain zero occurrences of the legacy package name `com.yugong.Backome` in production application code and zero bundled ThroughTek native binaries (`libIOTCAPIs.so`, `libAVAPIs.so`).

### REQ-ANDR-103 (MUST): Declarative Jetpack Compose UI & Material 3 Presentation

The user interface must be implemented using declarative Jetpack Compose and Material 3 design tokens.

- **AC-103.1:** Compose build feature must be enabled in `app/build.gradle.kts` with `compose = true` and `org.jetbrains.kotlin.plugin.compose` Kotlin plugin (`2.1.21`).
- **AC-103.2:** The application must define a Material 3 design system in `com.weback.robot.ui.theme` (`Theme.kt`, `Color.kt`, `Type.kt`) with accessible typography, color tokens, and light/dark theme support.
- **AC-103.3:** The application must define a single launch activity `MainActivity` extending `ComponentActivity` that sets content using `WeBackTheme`.
- **AC-103.4:** The application home screen must render `DashboardScreen` displaying:
  1. Application title: `"WeBack Robot Control"`.
  2. Application version: `"v0.1.0-alpha (FCT-001 Foundation)"`.
  3. Connection state badge displaying `"Simulator Mode (Offline)"`.
  4. Mock robot telemetry card (Battery: 100%, State: Docked, Bin: Clean).

### REQ-ANDR-104 (MUST): Clean Architecture Package Boundaries & Manual Dependency Injection

The application codebase must adhere to strict package-level separation of concerns and pure Kotlin manual dependency injection.

- **AC-104.1:** Source code in `app/src/main/java/com/weback/robot/` must be partitioned into four distinct packages:
  - `com.weback.robot.ui`: Presentation layer (Compose screens, ViewModels, theme).
  - `com.weback.robot.domain`: Business logic and state models.
  - `com.weback.robot.protocol`: Transport interfaces and simulator mocks.
  - `com.weback.robot.core`: Application container, dispatchers, and common utilities.
- **AC-104.2:** Package `com.weback.robot.domain` must be pure Kotlin and contain zero imports from Android framework packages (`android.*`).
- **AC-104.3:** Dependencies must be provided through pure Kotlin manual dependency injection via `com.weback.robot.core.AppContainer` instantiated in `WeBackApplication` or `MainActivity`, with zero annotation-processing or reflection overhead (Dagger, Hilt, and Koin are excluded).

### REQ-ANDR-105 (MUST): Security & Network Privacy Configuration

The application manifest and security configuration must enforce non-negotiable transport security and credential hygiene per Constitution Invariants `C-004` and `C-010`.

- **AC-105.1:** `app/src/main/AndroidManifest.xml` must set `android:usesCleartextTraffic="false"` and reference `android:networkSecurityConfig="@xml/network_security_config"`.
- **AC-105.2:** `network_security_config.xml` must declare `<base-config cleartextTrafficPermitted="false" />` globally.
- **AC-105.3:** The codebase must contain zero hardcoded secrets, test credentials, AWS keys, or Wi-Fi passwords.

### REQ-ANDR-106 (MUST): JVM Unit Testing & Structured Metric Reporting

The application module must establish automated JVM unit tests covering domain models, ViewModels, and container injection.

- **AC-106.1:** Unit test suite must execute via `python3 verification/checks/gradle_test_adapter.py --task testDebugUnitTest` and achieve a 100% pass rate.
- **AC-106.2:** Unit tests must cover:
  1. `RobotStateTest`: Verifies domain model default values, state copy immutability, and docked state predicate.
  2. `DashboardViewModelTest`: Verifies initial UI state emission (`RobotUiState`), telemetry presentation, and coroutine execution with `StandardTestDispatcher`.
  3. `AppContainerTest`: Verifies dependency instantiation, singleton repository scoping, and manual injection integrity.
- **AC-106.3:** Unit test execution must output fresh JUnit XML reports in `app/build/test-results/testDebugUnitTest/` parsed by `verification/checks/gradle_test_adapter.py`, which validates XML freshness, prevents duplicate test counts, rejects contradictory counts, and emits the structured `TEST_SUMMARY_JSON:` contract.

### REQ-ANDR-107 (MUST): Android Lint & Static Code Quality Gate

The application module must execute Android Lint to detect defects, accessibility issues, performance antipatterns, and security regressions.

- **AC-107.1:** Android Lint must execute via `./gradlew lintDebug` and terminate with exit code 0.
- **AC-107.2:** The lint configuration in `app/build.gradle.kts` must set `abortOnError = true` and `warningsAsErrors = false`.
- **AC-107.3:** Lint execution must generate structured reports (`lint-results-debug.xml` and `lint-results-debug.html`) with zero fatal errors.

### REQ-ANDR-108 (MUST): Deterministic Debug APK Generation & Artifact Validation

The build system must assemble a verifiable debug APK artifact and validate its binary attributes.

- **AC-108.1:** `./gradlew assembleDebug` must compile and package `app/build/outputs/apk/debug/app-debug.apk` with exit code 0.
- **AC-108.2:** Verification harness check `apk-validation` (`verification/checks/apk_validator.py`) must inspect the generated APK using official Android SDK tools and Python `zipfile`, verifying:
  1. File exists at `app/build/outputs/apk/debug/app-debug.apk` and is non-empty (arbitrary 1 MB threshold removed).
  2. SHA-256 digest is computed and recorded in verification evidence.
  3. Package identity `com.weback.robot` verified via `apkanalyzer manifest application-id`.
  4. SDK boundaries `minSdk = 31` and `targetSdk = 35` verified via `apkanalyzer manifest min-sdk` and `target-sdk`.
  5. Version metadata `versionName = 0.1.0-alpha` verified via `apkanalyzer manifest version-name`.
  6. Required launcher activity `com.weback.robot.MainActivity` verified in manifest.
  7. Signature validity verified via `apksigner verify`.
  8. Structural archive integrity verified via Python `zipfile`, confirming `classes.dex`, `resources.arsc`, `AndroidManifest.xml` present and zero prohibited proprietary native binaries (`libIOTCAPIs.so`, `libAVAPIs.so`) in `lib/`.
- **AC-108.3:** When an explicitly selected and authorized connected Android test device serial (e.g. `WEBACK_TEST_DEVICE_SERIAL`) is provided, verification harness check `android-smoke-test` (`verification/checks/android_smoke_test.py`) must install `app-debug.apk` via `adb -s <serial> install -r`, start `com.weback.robot/.MainActivity`, verify process survival via PID check, and confirm zero application crash exceptions in logcat. If no authorized device is provided or selected, the check must explicitly report `NOT_RUN` / `SKIPPED (No authorized device)` with distinct status classification without masquerading as `PASS`. Silent selection of arbitrary physical devices is strictly prohibited.
