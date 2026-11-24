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
| `TEST-ANDR-101` | Security | Gradle Wrapper | `gradle_wrapper_check.py` verifies `gradle/wrapper/gradle-wrapper.jar` SHA-256 against official release digest. | Matches `2db75c40782f...` exactly. Exit code 0. |
| `TEST-ANDR-102` | Config | Wrapper Properties | `gradle_wrapper_check.py` inspects `distributionUrl` and `distributionSha256Sum` in `gradle-wrapper.properties`. | Uses HTTPS, Gradle 8.10.2, and matching distribution SHA-256 `31c55713...`. |
| `TEST-ANDR-103` | Config | Version Catalog | Static inspection of `gradle/libs.versions.toml`. | All dependencies have exact versions (Kotlin 2.1.21, AGP 8.7.2, zero dynamic `+`). |
| `TEST-ANDR-104` | Isolation | Package Identity | Inspection of `settings.gradle.kts` and `app/build.gradle.kts`. | `applicationId = "com.weback.robot"`; zero `com.yugong.Backome`. |
| `TEST-ANDR-105` | Boundary | SDK Floor | Inspection of `app/build.gradle.kts` SDK properties. | `compileSdk = 35`, `targetSdk = 35`, `minSdk = 31`. |
| `TEST-ANDR-106` | Security | Clean-Room Libs | `apk_validator.py` scans `lib/` in APK. | Zero occurrences of `libIOTCAPIs.so` or `libAVAPIs.so`. |
| `TEST-ANDR-107` | Security | Cleartext HTTP | Android Lint & static inspection of `network_security_config.xml`. | `cleartextTrafficPermitted="false"` globally. |
| `TEST-ANDR-108` | Security | Zero Secrets | Static grep scan across all committed files in `app/`. | Zero API keys, secrets, AWS keys, or test credentials. |
| `TEST-ANDR-109` | UI | Compose Tokens | Inspection of `com.weback.robot.ui.theme` composables. | `Theme.kt`, `Color.kt`, `Type.kt` define Material 3 tokens. |
| `TEST-ANDR-110` | UI | Dashboard Screen | UI composable renders `DashboardScreen`. | Displays title, version, and simulator status badge. |
| `TEST-ANDR-111` | Architecture | Domain Isolation | Static import inspection of `com.weback.robot.domain`. | Zero imports from `android.*` packages. |
| `TEST-ANDR-112` | DI | AppContainer | `AppContainerTest.kt` unit test execution. | Container instantiates `SimulatorRobotTransport` singleton. |
| `TEST-ANDR-113` | Unit Test | Domain State | `RobotStateTest.kt` unit test execution. | Verifies immutability, default telemetry values, and predicates. |
| `TEST-ANDR-114` | Unit Test | ViewModel State | `DashboardViewModelTest.kt` unit test execution via `gradle_test_adapter.py`. | Verifies initial `RobotUiState` emission and coroutine dispatch. Structured summary emitted. |
| `TEST-ANDR-115` | Static Analysis | Android Lint | `./gradlew lintDebug` execution. | Zero errors in `lint-results-debug.xml`. Exit code 0. |
| `TEST-ANDR-116` | Build | Debug Assembly | `./gradlew assembleDebug` execution. | Generates `app/build/outputs/apk/debug/app-debug.apk`. Exit 0. |
| `TEST-ANDR-117` | Artifact | APK Verification | `apk_validator.py` inspection via `apkanalyzer`, `apksigner`, and `zipfile`. | Manifest application ID, minSdk 31, signature valid, zero vendor SOs. |
| `TEST-ANDR-118` | Smoke Test | Device Launch | `android_smoke_test.py` on explicitly authorized device serial. | Activity reaches RESUMED; zero fatal exceptions; reports NOT_RUN if unauthorized. |

---

## 3. Test Execution Details

### 3.1 TEST-ANDR-101: Gradle Wrapper Binary Checksum Verification
- **Command:** `python3 verification/checks/gradle_wrapper_check.py`
- **Precondition:** `gradle/wrapper/gradle-wrapper.jar` exists.
- **Oracle:**
  $$\text{SHA-256}(\text{gradle-wrapper.jar}) == \text{"2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046"}$$
- **Failure Condition:** Checksum mismatch triggers exit code 1 with actionable mismatch diagnostic.

### 3.2 TEST-ANDR-102: Gradle Wrapper Properties Distribution Verification
- **Command:** `python3 verification/checks/gradle_wrapper_check.py`
- **Precondition:** `gradle/wrapper/gradle-wrapper.properties` exists.
- **Oracle:**
  - `distributionUrl` uses HTTPS and points to `gradle-8.10.2-bin.zip`.
  - `distributionSha256Sum` equals `"31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26"`.
- **Failure Condition:** Missing HTTPS, wrong version, or invalid checksum triggers exit code 1.

### 3.3 TEST-ANDR-106: Clean-Room Proprietary Binary Exclusion
- **Command:** `python3 verification/checks/apk_validator.py`
- **Precondition:** `app/build/outputs/apk/debug/app-debug.apk` is generated.
- **Oracle:** Open APK as ZIP archive via Python `zipfile`. List all members under `lib/`.
  $$\forall f \in \text{zipfile.namelist()},\quad f \not\equiv \text{"lib/.*libIOTCAPIs\.so"}$$
- **Failure Condition:** If any proprietary binary is packaged, `apk_validator.py` aborts with exit code 1.

### 3.4 TEST-ANDR-114: ViewModel Reactive State Emission & Adapter Extraction
- **Command:** `python3 verification/checks/gradle_test_adapter.py --task testDebugUnitTest --results-dir app/build/test-results/testDebugUnitTest`
- **Precondition:** Android SDK platform and build tools provisioned.
- **Oracle:**
  - `gradle_test_adapter.py` executes unit test task with exit code 0.
  - Generates fresh JUnit XML results and asserts `total > 0`, `failures == 0`, `errors == 0`, `skipped == 0`.
  - Emits `TEST_SUMMARY_JSON: {"total": T, "passed": P, "failures": 0, "errors": 0, "skipped": 0, ...}`.
- **Failure Condition:** Assertion failure in test, stale results, or contradictory XML counts triggers exit code 1.

### 3.5 TEST-ANDR-117: Debug APK Binary Inspection via SDK Tools
- **Command:** `python3 verification/checks/apk_validator.py`
- **Precondition:** `app-debug.apk` compiled.
- **Oracle:**
  - `apkanalyzer manifest application-id app-debug.apk` returns `"com.weback.robot"`.
  - `apkanalyzer manifest min-sdk app-debug.apk` returns `"31"`.
  - `apkanalyzer manifest target-sdk app-debug.apk` returns `"35"`.
  - `apkanalyzer manifest version-name app-debug.apk` returns `"0.1.0-alpha"`.
  - `apksigner verify app-debug.apk` returns verification success with exit code 0.
  - Python `zipfile` confirms valid archive (`testzip() is None`), containing `classes.dex`, `resources.arsc`, `AndroidManifest.xml`.
- **Failure Condition:** Attribute mismatch, invalid signature, or corrupt archive terminates check with exit code 1.

### 3.6 TEST-ANDR-118: Explicitly Authorized Device Smoke Test
- **Command:** `python3 verification/checks/android_smoke_test.py`
- **Precondition:** Environment variable `WEBACK_TEST_DEVICE_SERIAL` specifies an authorized test device serial.
- **Oracle (When Authorized Device Connected):**
  - `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk` returns `"Success"`.
  - `adb -s <serial> shell am start -W -n com.weback.robot/.MainActivity` returns `"Status: ok"`.
  - `adb -s <serial> shell pidof com.weback.robot` returns non-empty integer PID.
  - Process remains alive for 5 seconds without fatal crash in logcat.
- **Oracle (When Unauthorized or No Device Connected):**
  - Check emits `[SMOKE TEST SKIPPED: No authorized test device specified]` and exits with separate `NOT_RUN` classification without masquerading as `PASS`.
- **Failure Condition:** On authorized device: installation failure, activity crash, or process death within 5 seconds terminates check with exit code 1.
