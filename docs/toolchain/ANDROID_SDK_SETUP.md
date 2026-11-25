# Android SDK & Toolchain Setup Guide — WeBack Android (FCT-001)

## 1. Overview

This document specifies the prerequisite environment, toolchain dependencies, and verification procedures for building and testing the WeBack Android application.

## 2. Prerequisite Environment

| Component | Target Version | Verification Command | Notes |
| :--- | :--- | :--- | :--- |
| **Operating System** | Linux x86_64 (Ubuntu 24.04+ / Debian 12+) | `uname -s -m` | Standard Linux environment. |
| **Host JDK** | OpenJDK 21 LTS (`21.0.x`) | `java -version` | Host compiler runtime executing Gradle. |
| **Java / Kotlin JVM Target** | JVM Bytecode Target 17 | `javac -version` | Compatible with Android 12+ (API 31+). |
| **Gradle** | 8.10.2 | `./gradlew --version` | Pinned via official Gradle Wrapper with SHA-256. |
| **Android Gradle Plugin** | 8.7.2 | Verified in `gradle/libs.versions.toml` | Official Google AGP release. |
| **Kotlin** | 2.1.21 | Verified in `gradle/libs.versions.toml` | Language and Compose Compiler plugin. |
| **compileSdk / targetSdk** | 35 (Android 15) | Verified in `app/build.gradle.kts` | Modern Android 15 SDK baseline. |
| **minSdk** | 31 (Android 12) | Verified in `app/build.gradle.kts` | Scoped storage, modern BT, Keystore. |

## 3. Headless Android SDK Installation

The Android SDK is provisioned into the user-local directory `${HOME}/.android/sdk` (or custom `${ANDROID_HOME}`):

1. **Official Google Command-Line Tools:**
   - Package: `commandlinetools-linux-11076708_latest.zip`
   - URL: `https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip`
   - Pinned SHA-256 Digest: `2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258`
   - Target Directory: `${ANDROID_HOME}/cmdline-tools/latest/`

2. **Automated Provisioning:**
   ```bash
   ./scripts/setup_android_sdk.sh
   ```

3. **Verification of Existing SDK:**
   ```bash
   ./scripts/setup_android_sdk.sh --check
   ```

## 4. Environment Variables

To ensure reproducible builds between local developer environments and CI runners, configure:

```bash
export ANDROID_HOME="${HOME}/.android/sdk"
export PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/35.0.0:${PATH}"
```

## 5. Resource Constraints & Gradle Daemon Settings

To preserve memory on resource-constrained development and CI environments, `gradle.properties` enforces:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.workers.max=2
org.gradle.parallel=false
android.useAndroidX=true
```

## 6. Local SDK Isolation Rules

Per Project Constitution `C-010` and `C-011`:
- `local.properties` is strictly ignored by `.gitignore` and must never be committed.
- Downloaded SDK archives, toolchain caches, and temporary files must not be tracked in git.
