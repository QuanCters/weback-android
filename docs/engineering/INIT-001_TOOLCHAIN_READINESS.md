# INIT-001 — Android Toolchain Readiness Audit & Remediation Plan

**Document ID:** AUD-TOOL-01  
**Evaluation Date:** 2026-11-24  
**Operating System:** Linux 6.6.87.2-microsoft-standard-WSL2 (x86_64, Ubuntu 24.04 LTS)  
**Author:** Principal Android Architect & Verification Architect  

---

## 1. Toolchain Component Status Matrix

| Component | Required Specification | Actual Status | Classification | Detailed Location & Observations |
| :--- | :--- | :--- | :--- | :--- |
| **Java Development Kit (JDK)** | OpenJDK 17 or 21 | OpenJDK `21.0.13.1` 64-Bit | **VERIFIED** | `/usr/lib/jvm/java-21-openjdk-amd64/bin/java`, `JAVA_HOME` configured. Compatible with AGP 8.7+ and Gradle 8.10+. |
| **Android Debug Bridge (ADB)** | ADB 1.0.41+ | ADB `1.0.41` (37.0.3-16887417) | **CONFIGURED** | Located at `/home/quan/tools/platform-tools/adb`. Not currently in default system `$PATH`. |
| **Android SDK Root** | Standard Android SDK Directory | None configured | **MISSING** | Neither `$ANDROID_HOME` nor `$ANDROID_SDK_ROOT` is exported. No standard SDK directory in `/home/quan/Android/Sdk` or `/opt`. |
| **Android Command-line Tools** | `sdkmanager`, `avdmanager` | Not installed | **MISSING** | `cmdline-tools` package is absent from system paths. |
| **Android SDK Platforms** | `android-34` or `android-35` | None installed | **MISSING** | Target platforms are missing; required for compiling Android source. |
| **Android SDK Build Tools** | `34.0.0` or `35.0.0` | None installed | **MISSING** | `aapt2`, `d8`, `zipalign` are absent. |
| **Android Emulator** | QEMU-based Android Emulator | Not installed | **MISSING** | No `emulator` binary found on host. |
| **System Images (x86_64)** | Google APIs / Play Intel Atom | None installed | **MISSING** | Required if running local headless emulator CI. |
| **Hardware Acceleration (KVM)** | `/dev/kvm` accessible | Accessible to group `kvm` | **PARTIAL** | `/dev/kvm` exists (`crw-rw---- 1 root kvm`). User `quan` belongs to `adm, sudo, docker`, but is not in `kvm` group. |
| **Gradle** | Gradle 8.7+ | None in system PATH | **MISSING** | No global Gradle. Standard Android practice is to use the Gradle Wrapper (`gradlew`), which will be bootstrapped in FCT-001. |
| **Available Storage** | $\ge 20$ GB for SDK & builds | 905 GB Available | **VERIFIED** | Root filesystem `/dev/sdd` has 905 GB free ($6\%$ utilization). |
| **Python** | Python 3.10+ | Python `3.12.3` | **VERIFIED** | `/usr/bin/python3`. Ready for Verification Harness runner. |
| **Git** | Git 2.30+ | Git `2.43.0` | **VERIFIED** | `/usr/bin/git`. Ready for worktrees and governance. |
| **GitHub CLI** | `gh` 2.0+ | `gh 2.92.0` | **VERIFIED** | `/usr/bin/gh`. Ready for pull request and checks management. |

---

## 2. Analysis & Critical Dependency Cycle Resolution

### 2.1 The "ADB Alone Does Not Equal Build Readiness" Reality
While ADB is available in `/home/quan/tools/platform-tools/adb`, ADB is solely a device communication transport. The absence of the Android SDK (`cmdline-tools`, `platforms/android-35`, `build-tools/35.0.0`) means that **Android Gradle builds cannot be executed on this machine today**.

### 2.2 Resolving the FCT-000 Bootstrap Cycle
A naive Verification Harness would immediately configure `./gradlew test` as a required check in `policies/checks.json`. However, because:
1. No Gradle project or wrapper exists in INIT-001,
2. The Android SDK is not yet provisioned,
Requiring Gradle checks in FCT-000 would cause an unavoidable `FAIL` or `ERROR`, blocking the harness bootstrap.

**Approved Resolution:**
- **INIT-001:** Repository hygiene, markdown syntax, and provenance checks only.
- **FCT-000:** Runner selftests, check registry schema validation, and failure-injection tests in pure Python standard library.
- **FCT-001:** Android SDK provisioning, Gradle wrapper bootstrap, and first debug compilation verification.

---

## 3. Remediation Procedure for Milestone FCT-001

When the Product Owner authorizes toolchain setup in Milestone FCT-001, the following exact steps will be executed:

### Step 1: Provision Android Command-line Tools
Download and extract Android Command-line Tools (latest Linux package) to `/home/quan/tools/android-sdk/cmdline-tools/latest`.

### Step 2: Configure Environment Variables
Add to `~/.bashrc` (or load via local project environment):
```bash
export ANDROID_HOME="/home/quan/tools/android-sdk"
export ANDROID_SDK_ROOT="/home/quan/tools/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

### Step 3: Install Required SDK Packages via `sdkmanager`
```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

### Step 4: KVM Acceleration Permissions (If Emulator Needed)
If the Product Owner requires local headless emulator testing rather than a physical Android test device:
```bash
sudo usermod -aG kvm quan
```
*(Note: If physical device testing via ADB over USB/Wi-Fi is selected, local KVM is not required).*

### Step 5: Bootstrap Gradle Wrapper
Initialize a modern Gradle wrapper (`gradle-8.10.2`) pinned to JDK 21.
