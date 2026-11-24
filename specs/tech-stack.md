# Technology Stack & Architecture Specification — WeBack Android

**Document ID:** SPEC-TECH-01  
**Version:** 1.1.0 (Proposed Calibrated Baseline)  
**Effective Date:** 2026-11-24  
**Author:** Principal Android Architect  
**Governance Authority:** Product Owner & Lead Maintainer  
**Status:** PROPOSED BASELINE (Awaiting Formal Human Review & Approval)  

---

## 1. Toolchain & Dependency Lifecycle Classification

To avoid conflating preliminary proposals with confirmed environments, tooling choices are categorized into four distinct lifecycle states:

| Lifecycle State | Description | Current Status / Target |
| :--- | :--- | :--- |
| **Actually Installed Toolchain** | Tools and runtimes verified present in the host environment. | Host OpenJDK `21.0.13.1`, Python `3.12.3`, Git `2.43.0`, Linux x86_64. *(Android SDK and Gradle wrapper are NOT yet installed in INIT-001; installation deferred to authorized FCT-001).* |
| **Proposed Dependency Versions** | Candidate target versions recommended for the application. | Host JDK 21 LTS, Java & Kotlin JVM compilation target `17`, Gradle `8.10.2`, AGP `8.7.2`, Kotlin `2.1.21`, Kotlin Compose compiler plugin `2.1.21`, Compose BOM `2024.10.01`, Material 3 `1.3.1`, Coroutines `1.9.0`, OkHttp `4.12.0`, kotlinx.serialization `1.7.3`. |
| **Verified Compatibility** | Compatibility matrices validated against official vendor documentation. | Verified per Android/Google and Kotlin release tables: AGP 8.7+ supports JDK 21 execution; Kotlin 2.1.21 integrates with Compose Compiler 2.1.21; JVM compilation target is pinned to bytecode level 17 for both Java and Kotlin. |
| **Pinned Project Configuration** | Finalized versions committed to `gradle/libs.versions.toml` and wrapper properties. | Locked and pinned in Milestone FCT-001. Establishes repeatable build environments; bit-for-bit deterministic APK reproducibility is a separately verified qualification property. |

### 1.1 Target SDK Floor & Boundaries
- **`compileSdk`:** `35` (Android 15) — Aligned with modern AndroidX dependencies.
- **`targetSdk`:** `35` (Android 15) — Complies with modern Google Play distribution requirements.
- **`minSdk`:** `31` (Android 12) — Mandated baseline. Ensures platform-level Scoped Storage, modern Bluetooth permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`), and standard hardware-backed cryptographic primitives. Preserved unless the Product Owner separately approves broader legacy Android support.
- **Application ID:** `com.weback.robot` (Strictly independent and cleanly separated from legacy package `com.yugong.Backome`).

---

## 2. Minimal Modular Architecture

To avoid premature abstraction and unnecessary build complexity, the MVP adopts a **single Android application module (`app/`)** with strict package-level separation:

```mermaid
graph TD
    subgraph "Single Application Module (:app)"
        subgraph "com.weback.robot.ui (Jetpack Compose)"
            Screen["DashboardScreen / MapCanvas"]
            ViewModel["RobotViewModel (MVI)"]
        end

        subgraph "com.weback.robot.domain (Pure Kotlin)"
            UseCase["RobotControlUseCases"]
            StateModel["RobotUiState / RobotCommand"]
        end

        subgraph "com.weback.robot.protocol (Clean-Room Transports)"
            AdapterInterface["RobotTransportAdapter (Interface)"]
            SimulatedAdapter["SimulatedRobotAdapter (Offline)"]
            LiveWsAdapter["OkHttpWebSocketAdapter (Cloud)"]
            ZlibDecoder["ZlibMapDecoder"]
        end

        subgraph "com.weback.robot.core (DI & Common)"
            AppContainer["AppContainer (Manual DI)"]
            Dispatchers["CoroutineDispatcherProvider"]
        end
    end

    Screen --> ViewModel
    ViewModel --> UseCase
    UseCase --> AdapterInterface
    AdapterInterface <|.. SimulatedAdapter
    AdapterInterface <|.. LiveWsAdapter
    AppContainer --> ViewModel
    AppContainer --> AdapterInterface
```

### 2.1 Architectural Conventions
1. **Single Module Initially:** Defer multi-module Gradle setups (`:core`, `:domain`, `:feature`) until codebase size or parallel compilation explicitly justifies the overhead. Package boundaries enforce separation of concerns cleanly.
2. **Pure Kotlin Domain:** `com.weback.robot.domain` contains zero Android framework imports (`android.*`), enabling fast JVM-only unit testing.
3. **Pluggable Transports:** All robot communication is abstracted behind `RobotTransportAdapter`. The application runtime defaults to `SimulatedRobotAdapter` (offline test) and activates `OkHttpWebSocketAdapter` only under explicit network configuration.
4. **Manual Dependency Injection:** Pure Kotlin manual dependency injection via `AppContainer`. Dagger, Hilt, and Koin are deferred in MVP to eliminate annotation processing overhead, KSP bloat, and slow build cycles.

---

## 3. Technology Selection & Justifications

### 3.1 UI & Presentation
- **Jetpack Compose (BOM `2024.10.01`):** Declarative, reactive UI replacing 150 legacy XML Activities.
- **Material 3 (`1.3.1`):** Modern accessible design tokens and standard components.
- **Lifecycle & ViewModel Dependencies (Explicit):** `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7`, `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7`, `androidx.lifecycle:lifecycle-runtime-compose:2.8.7`.
- **Kotlin Coroutines (`1.9.0`) & StateFlow:** Structured concurrency and reactive state streams via `kotlinx-coroutines-core` and `kotlinx-coroutines-android`.

### 3.2 Networking & Serialization
- **OkHttp (`4.12.0`):** High-performance HTTP/WebSocket client with robust connection pooling.
- **Kotlinx.serialization (`1.7.3`):** Compile-time type safety replacing legacy Gson; zero reflection, strict null safety.
- **ZLIB Decompression:** Standard Java `java.util.zip.Inflater` (zero third-party dependencies required for binary map decompression).

### 3.3 Security & Cryptographic Storage Strategy
- **Baseline Foundation:** Android Keystore is the mandated foundation for cryptographic keys and token security.
- **Architecture Decision Record (ADR) Requirement:** The project will not prematurely commit to a specific custom storage implementation before an approved Threat Model and Security ADR are completed in Milestone FCT-003.
- **Prohibited Deprecated APIs:** Legacy `EncryptedSharedPreferences` is deprecated by Google and prohibited due to known data loss and deadlocks.
- **Network Security:** Manifest enforces `cleartextTrafficPermitted="false"` globally.

### 3.4 Verification & Testing Stack
- **JUnit 5 / JUnit 4:** Standard JVM unit test runner.
- **MockWebServer (`4.12.0`):** In-process, deterministic HTTP/WebSocket mock server.
- **Kotlinx Coroutines Test (`1.9.0`):** Virtual time testing with `TestScope` and `StandardTestDispatcher`.
- **Truth / AssertK:** Fluent, human-readable assertion diffs.
- **Compose UI Testing (`androidx.compose.ui:ui-test-junit4`):** Composable verification.

---

## 4. Prohibited Technologies & Anti-Patterns

| Prohibited Technology | Why Prohibited | Approved Alternative |
| :--- | :--- | :--- |
| **ThroughTek Native Libraries (`libIOTCAPIs.so`, etc.)** | Closed-source proprietary binary SDK from legacy APK cannot be bundled or redistributed in an open clean-room project without vendor licensing; excluded from MVP. | Excluded from MVP; pure clean-room Kotlin only. |
| **GreenRobot EventBus** | Global mutable state, untraceable event spaghetti, difficult to test. | Kotlin StateFlow and SharedFlow within ViewModel. |
| **Gson** | Runtime reflection, silent nullability bugs, slow performance. | `kotlinx.serialization.json`. |
| **`EncryptedSharedPreferences`** | Deprecated by Google, known concurrency crashes and data corruption. | Android Keystore authenticated solution via approved ADR. |
| **MD5 / SHA-1 for Credentials** | Cryptographically broken algorithms (`SEC-04`). | SHA-256 / PBKDF2 / Modern OAuth Bearer tokens. |
| **Global Cleartext HTTP** | Insecure transport leaking credentials (`SEC-01`). | Strict TLS and localized, isolated mock transports. |
| **Dagger / Hilt (in MVP)** | Excessive boilerplate, generated code overhead, slow builds. | Pure Kotlin `AppContainer` manual dependency injection. |
