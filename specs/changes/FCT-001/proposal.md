# FCT-001 Feature Proposal — Android Foundation & First Runnable APK

**Change ID:** `FCT-001`  
**Milestone:** `FCT-001`  
**Risk Tier:** `T3 — Moderate (Application Foundation & Toolchain Integration per POL-RISK-01)`  
**Status:** `DRAFT (Proposed for G3 Implementation Authorization)`  
**Author:** Principal Android Architect  
**Effective Date:** 2026-11-24  

---

## 1. Executive Summary & Problem Statement

Following the successful authorization, verification, and squash-merge of Milestone `FCT-000` (Minimum Viable Verification Harness), the WeBack Android project transitions from verification bootstrap to application development.

The objective of `FCT-001` is to establish the complete Android development toolchain, generate the single application module (`app/`), configure pinned dependency versions via Gradle version catalogs, implement a minimal Jetpack Compose UI, produce the first installable and runnable debug APK (`com.weback.robot`), and integrate Android-specific build and unit testing checks into the verification harness.

### Key Engineering Challenges
1. **Clean-Room Application Identity:** The legacy decompiled application (`com.yugong.Backome`) was entangled with proprietary vendor binaries (`libIOTCAPIs.so`), deprecated crypto (`EncryptedSharedPreferences`), global cleartext HTTP permissions (`usesCleartextTraffic="true"`), and legacy XML UI layouts. `FCT-001` establishes a clean-room application identity (`com.weback.robot`) strictly avoiding legacy libraries.
2. **Toolchain Pinning & Reproducibility:** To prevent build flakiness across developer workstations and CI runners, all toolchain versions (JDK 21, Gradle 8.10.2, AGP 8.7.2, Kotlin 2.0.21, Compose BOM 2024.10.01) must be explicitly pinned and verified.
3. **Verification Harness Extension:** The Python verification harness established in `FCT-000` must be extended with Android-specific checks (`gradle-wrapper-validation`, `android-build`, `android-unit-tests`, `android-lint`, `apk-validation`, `android-smoke-test`) while preserving approved baseline checks (`spec-hygiene`, `harness-selftests`).

---

## 2. Business & Engineering Objectives

- **O1 (Verified Development Toolchain):** Establish and document a reproducible Android development toolchain using host OpenJDK 21, Gradle Wrapper 8.10.2, and pinned Android SDK components (compileSdk 35, targetSdk 35, minSdk 26).
- **O2 (Clean-Room Single Module Architecture):** Establish a single Android application module (`:app`) with namespace and application ID `com.weback.robot`, cleanly decoupled from legacy proprietary vendor artifacts.
- **O3 (Modern Compose Presentation):** Implement a minimal, accessible UI using Jetpack Compose and Material 3 displaying application identity, build version, and an offline simulator status card.
- **O4 (Pure Kotlin Domain & Manual DI):** Structure code into strict package boundaries (`ui`, `domain`, `protocol`, `core`) with pure Kotlin domain models and manual dependency injection via `AppContainer`, deferring complex DI frameworks (Dagger/Hilt/Koin) and annotation processing.
- **O5 (Quality Gate Enforcement):** Integrate Gradle Wrapper validation, Android Lint (zero errors), and JVM unit tests into the verification harness.
- **O6 (First Runnable Debug APK):** Successfully assemble `app-debug.apk`, verify its package identity and SHA-256 digest, and validate installation and launch capability via ADB smoke test.

---

## 3. Scope Definition

### 3.1 In-Scope Components
1. **Gradle Build Configuration:**
   - `gradle/wrapper/gradle-wrapper.properties` and `gradle-wrapper.jar` pinned to Gradle `8.10.2`.
   - `gradle/libs.versions.toml` version catalog pinning AGP `8.7.2`, Kotlin `2.0.21`, Compose BOM `2024.10.01`, Material 3 `1.3.1`, AndroidX Core KTX `1.15.0`, Lifecycle `2.8.7`, Activity Compose `1.9.3`, JUnit `4.13.2`.
   - Root `build.gradle.kts` and `settings.gradle.kts`.
   - Module `app/build.gradle.kts` with `com.android.application` and `org.jetbrains.kotlin.android`.
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
   - `verification/checks/gradle_wrapper_check.py`: Verifies wrapper JAR SHA-256.
   - `verification/checks/apk_validator.py`: Verifies debug APK presence, SHA-256, package name, minSdk.
   - `verification/checks/android_smoke_test.py`: Environment-gated ADB installation and launch test.
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
| **Java Runtime / JDK** | OpenJDK `21.0.13.1` at `/usr/lib/jvm/java-21-openjdk-amd64` | JDK 21 LTS | **READY**: Host JDK is fully compatible with Gradle 8.10.2 and AGP 8.7.2. |
| **Android SDK (Local)** | Not currently provisioned in PATH or `~/Android/Sdk` | Android SDK `compileSdk 35`, Build Tools `35.0.0`, Platform `android-35` | **RESOLVABLE**: Local setup plan installs official Google `cmdline-tools` into `~/.android/sdk` with pinned checksum during Task 1. |
| **Android SDK (CI)** | Pre-installed on GitHub Actions `ubuntu-latest` (`$ANDROID_HOME`) | Platforms `android-34`, `android-35` | **READY**: Remote CI has pre-installed Android SDK. |
| **Gradle** | System Gradle not installed | Gradle Wrapper `8.10.2` (`./gradlew`) | **READY**: Pinned wrapper will be committed to repo with official checksum. |
| **Hardware Acceleration** | `/dev/kvm` exists with RW permissions (`0o20660`) | KVM kernel support | **READY**: Hardware virtualization available for local emulator acceleration if configured. |
| **System Resources** | 7.0 GiB available RAM, 889 GiB available disk | >= 4 GiB RAM, >= 10 GiB disk | **READY**: Host resources exceed minimum compilation requirements. |
| **Connected Devices** | No ADB devices connected | Emulator or physical device | **FEASIBLE**: Local smoke test runs if device connected; optional/environment-gated in CI. |

---

## 5. Governance & Policy Evolution Workflow

1. **Policy Preservation:** The approved FCT-000 policy (`policies/checks.json`, digest `4a004ac8d07178c4164193d827983dfa5a7d86c5245fbcb6df69497e976137fd`) remains active and binding until formal human approval of FCT-001 at Gate G3.
2. **Policy Proposal:** Proposed check extensions are authored in `policies/fct001_checks_proposal.json` with status `PROPOSED`.
3. **Activation Gate:** Upon human Lead Maintainer G3 authorization of FCT-001, the approved checks in `policies/fct001_checks_proposal.json` will replace `policies/checks.json`, and repository variable `APPROVED_POLICY_SHA256` will be updated to the new digest.
4. **Defect-to-Regression Lifecycle:** Any defects discovered during FCT-001 implementation will follow the defect-to-regression skill, registering records in `verification/defects.jsonl` and test oracles in `verification/selftests/` or `app/src/test/`.
