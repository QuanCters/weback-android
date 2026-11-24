# FCT-001 Feature Proposal — Android Foundation & First Runnable APK

**Change ID:** `FCT-001`  
**Milestone:** `FCT-001`  
**Risk Tier:** `T4 — Critical (Governed by highest subsystem: verification trust boundaries, adapter contracts, policy transition)`  
**Status:** `DRAFT (Proposed for G3 Implementation Authorization)`  
**Author:** Principal Android Architect  
**Effective Date:** 2026-11-24  

---

## 1. Executive Summary & Problem Statement

Following the successful authorization, verification, and squash-merge of Milestone `FCT-000` (Minimum Viable Verification Harness), the WeBack Android project transitions from verification bootstrap to application development.

The objective of `FCT-001` is to establish the complete Android development toolchain, generate the single application module (`app/`), configure pinned dependency versions via Gradle version catalogs, implement a minimal Jetpack Compose UI, produce the first installable and runnable debug APK (`com.weback.robot`), and integrate Android-specific build and unit testing checks into the verification harness.

### Key Engineering Challenges
1. **Clean-Room Application Identity:** The legacy decompiled application (`com.yugong.Backome`) was entangled with proprietary vendor binaries (`libIOTCAPIs.so`), deprecated crypto (`EncryptedSharedPreferences`), global cleartext HTTP permissions (`usesCleartextTraffic="true"`), and legacy XML UI layouts. `FCT-001` establishes a clean-room application identity (`com.weback.robot`) strictly avoiding legacy libraries.
2. **Toolchain Pinning & Reproducibility:** To prevent build flakiness across developer workstations and CI runners, all toolchain versions (Host OpenJDK 21 LTS, JVM bytecode target 17, Gradle 8.10.2, AGP 8.7.2, Kotlin 2.1.21, Compose Compiler Plugin 2.1.21, Compose BOM 2024.10.01) must be explicitly pinned and verified. Pinned toolchains provide repeatability; bit-for-bit reproducible packaging remains a distinct qualification evaluation.
3. **Verification Harness Extension:** The Python verification harness established in `FCT-000` must be extended with Android-specific checks (`gradle-wrapper-validation`, `android-build`, `android-unit-tests` via `gradle_test_adapter.py`, `android-lint`, `apk-validation`, `android-smoke-test`) while preserving approved baseline checks (`spec-hygiene`, `harness-selftests`) and strict trust boundaries.

---

## 2. Business & Engineering Objectives

- **O1 (Verified Development Toolchain):** Establish and document a reproducible Android development toolchain using host OpenJDK 21, JVM bytecode compilation target 17, Gradle Wrapper 8.10.2, and pinned Android SDK components (compileSdk 35, targetSdk 35, minSdk 31).
- **O2 (Clean-Room Single Module Architecture):** Establish a single Android application module (`:app`) with namespace and application ID `com.weback.robot`, cleanly decoupled from legacy proprietary vendor artifacts.
- **O3 (Modern Compose Presentation):** Implement a minimal, accessible UI using Jetpack Compose and Material 3 displaying application identity, build version, and an offline simulator status card.
- **O4 (Pure Kotlin Domain & Manual DI):** Structure code into strict package boundaries (`ui`, `domain`, `protocol`, `core`) with pure Kotlin domain models and manual dependency injection via `AppContainer`, deferring complex DI frameworks (Dagger/Hilt/Koin) and annotation processing.
- **O5 (Structured Metric Enforcement):** Integrate Gradle Wrapper validation, Android Lint (zero errors), and JVM unit tests into the verification harness via `gradle_test_adapter.py` emitting the structured `TEST_SUMMARY_JSON:` contract.
- **O6 (First Runnable Debug APK):** Successfully assemble `app-debug.apk`, verify its package identity, SDK boundaries, signature, and SHA-256 digest via `apk_validator.py` using official SDK tools (`apkanalyzer`, `apksigner`), and validate installation and launch capability via ADB smoke test on explicitly authorized hardware.

---

## 3. Scope Definition

### 3.1 In-Scope Components
1. **Gradle Build Configuration:**
   - `gradle/wrapper/gradle-wrapper.properties` and `gradle-wrapper.jar` pinned to Gradle `8.10.2` with `distributionSha256Sum`.
   - `gradle/libs.versions.toml` version catalog pinning AGP `8.7.2`, Kotlin `2.1.21`, Kotlin Compose compiler plugin `2.1.21`, Compose BOM `2024.10.01`, Material 3 `1.3.1`, AndroidX Core KTX `1.15.0`, Lifecycle ViewModel Compose & Runtime `2.8.7`, Activity Compose `1.9.3`, Coroutines `1.9.0`, JUnit `4.13.2`.
   - Root `build.gradle.kts` and `settings.gradle.kts`.
   - Module `app/build.gradle.kts` with `com.android.application`, `org.jetbrains.kotlin.android`, and `org.jetbrains.kotlin.plugin.compose`.
2. **Application Shell & Manifest:**
   - `app/src/main/AndroidManifest.xml` with package `com.weback.robot`, `android:usesCleartextTraffic="false"`, single launch activity `MainActivity`.
   - Network security config `app/src/main/res/xml/network_security_config.xml` denying cleartext traffic.
3. **Compose UI & Core Infrastructure:**
   - `MainActivity.kt` hosting Compose content.
   - `com.weback.robot.ui.theme`: `Theme.kt`, `Color.kt`, `Type.kt` using Material 3 design tokens.
   - `com.weback.robot.ui.screens.DashboardScreen.kt`: Minimal dashboard with simulator status badge.
   - `com.weback.robot.core.AppContainer.kt`: Manual dependency container.
   - `com.weback.robot.domain.model.RobotState.kt`: Pure Kotlin state model.
4. **Unit Testing Suite:**
   - `app/src/test/java/com/weback/robot/`: JVM unit tests covering domain state models, ViewModel state transitions, and AppContainer injection.
5. **Harness Extension Modules:**
   - `verification/checks/gradle_wrapper_check.py`: Verifies wrapper JAR SHA-256 and wrapper properties checksum.
   - `verification/checks/gradle_test_adapter.py`: Executes Gradle unit tests, validates XML freshness and counts, and emits structured metrics.
   - `verification/checks/apk_validator.py`: Verifies debug APK presence, SHA-256, package name, minSdk 31, signature, and native binary exclusion.
   - `verification/checks/android_smoke_test.py`: Environment-gated ADB installation and launch test on explicitly authorized device serial.
   - Proposed checks catalog: `policies/fct001_checks_proposal.json`.

### 3.2 Explicit Non-Goals (Out of Scope for FCT-001)
- **No Robot Hardware Communication:** No BLE scanning, Wi-Fi SoftAP pairing, or Tuya protocol implementation (deferred to FCT-002+).
- **No Cloud Authentication / WebSocket:** No network requests to live WeBack or AWS IoT cloud (deferred to FCT-003+).
- **No Map Canvas / Binary SLAM Rendering:** No map decompression or canvas rendering (deferred to FCT-004+).
- **No Multi-Module Complexity:** Multi-module Gradle configuration is deferred until architectural complexity warrants it.
- **No Heavy DI / ORM Libraries:** Dagger, Hilt, Koin, Room, and SQLDelight are deferred.
- **No Commercial Application Release:** Debug APK only; public signing and release are not authorized.

---

## 4. Toolchain Discovery & Feasibility Assessment

Toolchain discovery performed on the local workspace (`2026-11-24`):

| Component | Discovered Host Status | Required for FCT-001 | Feasibility & Mitigation |
| :--- | :--- | :--- | :--- |
| **Java Runtime / JDK** | OpenJDK `21.0.13.1` at `/usr/lib/jvm/java-21-openjdk-amd64` | JDK 21 LTS, Target JVM 17 | **READY**: Host JDK 21 compiles Java and Kotlin to JVM bytecode target 17. |
| **Android SDK (Local)** | Not currently provisioned in PATH or `~/Android/Sdk` | Android SDK `compileSdk 35`, Build Tools `35.0.0`, Platform `android-35` | **RESOLVABLE**: Local setup installs official Google `commandlinetools-linux-11076708_latest.zip` with verified SHA-256 `2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258`. |
| **Android SDK (CI)** | Pre-installed on GitHub Actions `ubuntu-latest` (`$ANDROID_HOME`) | Platforms `android-34`, `android-35` | **READY**: Remote CI has pre-installed Android SDK. |
| **Gradle** | System Gradle not installed | Gradle Wrapper `8.10.2` (`./gradlew`) | **READY**: Pinned wrapper committed with verified official SHA-256 checksums for JAR and distribution. |
| **Hardware Acceleration** | `/dev/kvm` exists with RW permissions (`0o20660`) | KVM kernel support | **READY**: Hardware virtualization available for local emulator acceleration if configured. |
| **System Resources** | 7.0 GiB available RAM, 889 GiB available disk | >= 4 GiB RAM, >= 10 GiB disk | **READY**: Host resources exceed minimum compilation requirements. |
| **Connected Devices** | No ADB devices connected | Authorized emulator or physical device | **FEASIBLE**: Local smoke test executes only when explicitly authorized device serial is provided; otherwise recorded as NOT_RUN/SKIPPED. |

---

## 5. Governance & Policy Evolution Workflow

1. **Policy Preservation:** The approved FCT-000 policy (`policies/checks.json`, digest `4a004ac8d07178c4164193d827983dfa5a7d86c5245fbcb6df69497e976137fd`) remains active and binding throughout specification authorization (Gate G3). `APPROVED_POLICY_SHA256` is NOT modified during specification engineering.
2. **Policy Proposal:** Proposed check extensions reside in `policies/fct001_checks_proposal.json` with status `PROPOSED`. They are executed solely in an explicitly identified diagnostic context.
3. **Controlled Policy Transition:** During feature branch implementation, Android checks are developed and tested against candidate code. When all implementation deliverables and candidate evidence are fully verified, the new policy is presented for Lead Maintainer G6 merge authorization.
4. **Preserved Verification:** The policy transition preserves verification on both the baseline main revision and the implementation candidate revision without globally altering repository variables or invalidating branch protection status checks.
5. **Defect-to-Regression Lifecycle:** Any defects discovered during FCT-001 implementation will follow the 10-step lifecycle, registering records in `verification/defects.jsonl` and permanent regression tests in `verification/selftests/` or `app/src/test/`.
