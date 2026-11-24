# FCT-001 Validation Plan & Test Matrix — Android Foundation & First Runnable APK

**Change ID:** `FCT-001`  
**Milestone:** `FCT-001`  
**Document ID:** `SPEC-VAL-FCT001`  
**Version:** `1.0.0`  
**Status:** `DRAFT (Proposed for G3 Implementation Authorization)`  
**Author:** Principal Android Quality & Verification Lead  
**Effective Date:** 2026-11-24  

---

## 1. Quality Strategy & Validation Philosophy

In strict alignment with Verification-First SDD 2.0 and the Project Constitution ([specs/constitution.md](../../../specs/constitution.md)), validation for `FCT-001` establishes multi-tiered verification spanning static configuration audits, Gradle Wrapper integrity, compilation correctness, JVM unit tests, Android Lint static analysis, debug APK binary inspection, and environment-gated device smoke testing.

---

## 2. Planned Test Scenarios

The following 18 planned validation scenarios establish the verification matrix for Milestone `FCT-001`:

| Test ID | Category | Target Component | Description & Verification Oracle | Success Criteria |
| :--- | :--- | :--- | :--- | :--- |
| `TEST-ANDR-101` | Security | Gradle Wrapper | `gradle_wrapper_check.py` verifies `gradle/wrapper/gradle-wrapper.jar` SHA-256 against official release digest. | Matches `3229ae333d45...` exactly. Exit code 0. |
| `TEST-ANDR-102` | Config | Wrapper Properties | `gradle_wrapper_check.py` inspects `distributionUrl` in `gradle-wrapper.properties`. | Uses HTTPS and Gradle 8.10.2 bin distribution. |
| `TEST-ANDR-103` | Config | Version Catalog | Static inspection of `gradle/libs.versions.toml`. | All dependencies have exact versions (no dynamic `+`). |
| `TEST-ANDR-104` | Isolation | Package Identity | Inspection of `settings.gradle.kts` and `app/build.gradle.kts`. | `applicationId = "com.weback.robot"`; zero `com.yugong.Backome`. |
| `TEST-ANDR-105` | Boundary | SDK Floor | Inspection of `app/build.gradle.kts` SDK properties. | `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`. |
| `TEST-ANDR-106` | Security | Clean-Room Libs | `apk_validator.py` scans `lib/` in APK. | Zero occurrences of `libIOTCAPIs.so` or `libAVAPIs.so`. |
| `TEST-ANDR-107` | Security | Cleartext HTTP | Android Lint & static inspection of `network_security_config.xml`. | `cleartextTrafficPermitted="false"` globally. |
| `TEST-ANDR-108` | Security | Zero Secrets | Static grep scan across all committed files in `app/`. | Zero API keys, secrets, AWS keys, or test credentials. |
| `TEST-ANDR-109` | UI | Compose Tokens | Inspection of `com.weback.robot.ui.theme` composables. | `Theme.kt`, `Color.kt`, `Type.kt` define Material 3 tokens. |
| `TEST-ANDR-110` | UI | Dashboard Screen | UI composable renders `DashboardScreen`. | Displays title, version, and simulator status badge. |
| `TEST-ANDR-111` | Architecture | Domain Isolation | Static import inspection of `com.weback.robot.domain`. | Zero imports from `android.*` packages. |
| `TEST-ANDR-112` | DI | AppContainer | `AppContainerTest.kt` unit test execution. | Container instantiates `SimulatorRobotTransport` singleton. |
| `TEST-ANDR-113` | Unit Test | Domain State | `RobotStateTest.kt` unit test execution. | Verifies immutability, default telemetry values, and predicates. |
| `TEST-ANDR-114` | Unit Test | ViewModel State | `DashboardViewModelTest.kt` unit test execution. | Verifies initial `RobotUiState` emission and coroutine dispatch. |
| `TEST-ANDR-115` | Static Analysis | Android Lint | `./gradlew lintDebug` execution. | Zero errors in `lint-results-debug.xml`. Exit code 0. |
| `TEST-ANDR-116` | Build | Debug Assembly | `./gradlew assembleDebug` execution. | Generates `app/build/outputs/apk/debug/app-debug.apk`. Exit 0. |
| `TEST-ANDR-117` | Artifact | APK Verification | `apk_validator.py` inspection of debug APK. | Size > 1 MB, manifest valid, SHA-256 computed and logged. |
| `TEST-ANDR-118` | Smoke Test | Device Launch | `android_smoke_test.py` via ADB install and start. | Activity reaches RESUMED; zero fatal exceptions in logcat. |

---

## 3. Test Execution Details

### 3.1 TEST-ANDR-101: Gradle Wrapper Binary Checksum Verification
- **Command:** `python3 verification/checks/gradle_wrapper_check.py`
- **Precondition:** `gradle/wrapper/gradle-wrapper.jar` exists.
- **Oracle:**
  $$\text{SHA-256}(\text{gradle-wrapper.jar}) == \text{"3229ae333d45c8ad0c9d7fe3160a2b005177242e20ff9d997f35b44ec6dbf737"}$$
- **Failure Condition:** Checksum mismatch triggers exit code 1 with actionable mismatch diagnostic.

### 3.2 TEST-ANDR-106: Clean-Room Proprietary Binary Exclusion
- **Command:** `python3 verification/checks/apk_validator.py`
- **Precondition:** `app/build/outputs/apk/debug/app-debug.apk` is generated.
- **Oracle:** Open APK as ZIP archive via Python `zipfile`. List all members under `lib/`.
  $$\forall f \in \text{zipfile.namelist()},\quad f \not\equiv \text{"lib/.*libIOTCAPIs\.so"}$$
- **Failure Condition:** If any proprietary binary is packaged, `apk_validator.py` aborts with exit code 1.

### 3.3 TEST-ANDR-114: ViewModel Reactive State Emission
- **Command:** `./gradlew testDebugUnitTest --tests "com.weback.robot.ui.DashboardViewModelTest"`
- **Precondition:** Android SDK platform and build tools provisioned.
- **Oracle:** Using `kotlinx-coroutines-test` `StandardTestDispatcher`, verify `viewModel.uiState.value` initially emits:
  - `robotName == "WeBack Robot"`
  - `isSimulator == true`
  - `batteryLevel == 100`
- **Failure Condition:** Assertion failure in test emits failure stack trace and causes `./gradlew test` exit code 1.

### 3.4 TEST-ANDR-117: Debug APK Binary Inspection
- **Command:** `python3 verification/checks/apk_validator.py`
- **Precondition:** `app-debug.apk` compiled.
- **Oracle:**
  - File size is strictly greater than `1,000,000` bytes.
  - Manifest binary string pool contains `"com.weback.robot"`.
  - Non-empty `classes.dex` and `resources.arsc` are verified inside archive.
- **Failure Condition:** Missing archive components or corrupt APK terminates check with exit code 1.

### 3.5 TEST-ANDR-118: Device & Emulator Installation Smoke Test
- **Command:** `python3 verification/checks/android_smoke_test.py`
- **Precondition:** Optional environment-gated check. If `adb devices` returns 0 devices, check logs skip notice and exits 0.
- **Oracle (When Device Connected):**
  - `adb install -r app/build/outputs/apk/debug/app-debug.apk` returns `"Success"`.
  - `adb shell am start -n com.weback.robot/.MainActivity` returns `"Starting: Intent { ... }"`.
  - `adb shell pidof com.weback.robot` returns non-empty integer PID.
  - `adb logcat -d` contains 0 occurrences of `"FATAL EXCEPTION"`.
- **Failure Condition:** Installation failure, activity crash, or process death within 5 seconds terminates check with exit code 1.
