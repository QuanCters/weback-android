# Technology Stack & Architecture Specification — WeBack Android

**Document ID:** SPEC-TECH-01  
**Version:** 1.0.0 (Proposed Baseline)  
**Effective Date:** 2026-11-24  
**Author:** Principal Android Architect  
**Approved by:** Lead Maintainer (Pending Formal Baseline Sign-off)  

---

## 1. Pinned Toolchain & SDK Versions

| Component | Pinned Version | Rationale |
| :--- | :--- | :--- |
| **JDK** | OpenJDK `21.0.13.1` | Long-Term Support (LTS), verified in host environment, compatible with modern Gradle and AGP. |
| **Kotlin** | `2.0.21` | Modern Kotlin compiler with K2 enabled by default; unified Compose compiler support. |
| **Android Gradle Plugin (AGP)**| `8.7.2` | Stable release supporting JDK 21, compileSdk 35, and Kotlin 2.0. |
| **Gradle** | `8.10.2` (via Wrapper) | Compatible with AGP 8.7+ and JDK 21. |
| **`compileSdk`** | `35` (Android 15) | Required by modern AndroidX libraries; provides access to latest platform APIs. |
| **`targetSdk`** | `34` (Android 14) / `35` | Aligned with current Google Play and Android platform distribution standards. |
| **`minSdk`** | `31` (Android 12) | **Approved PO Direction:** Ensures modern security defaults, Scoped Storage, granular Bluetooth permissions, and modern cryptographic hardware acceleration. |

---

## 2. Core Architectural Framework

The application adopts **Clean Architecture + MVI (Model-View-Intent)** with reactive StateFlow streams:

```mermaid
graph TD
    subgraph UI Layer (Jetpack Compose)
        Screen["DashboardScreen (Declarative UI)"]
        Canvas["MapCanvas (Custom Compose DrawScope)"]
    end

    subgraph Presentation Layer
        ViewModel["RobotViewModel (MVI State Machine)"]
        State["RobotUiState (Immutable Data Class)"]
        Intent["RobotUserIntent (Sealed Interface)"]
    end

    subgraph Domain Layer (Pure Kotlin)
        SendCommandUseCase["SendCommandUseCase"]
        IngestMapUseCase["IngestMapUseCase"]
        FSM["RobotStateValidator"]
    end

    subgraph Data & Protocol Layer
        ProtocolAdapter["ProtocolAdapter (Interface)"]
        LiveWS["OkHttpWebSocketAdapter"]
        SimulatedWS["SimulatedRobotAdapter"]
        MapDecoder["ZlibOccupancyGridDecoder"]
    end

    Screen --> Intent
    Intent --> ViewModel
    ViewModel --> State
    State --> Screen
    State --> Canvas

    ViewModel --> SendCommandUseCase
    ViewModel --> IngestMapUseCase
    SendCommandUseCase --> FSM
    FSM --> ProtocolAdapter
    ProtocolAdapter <|.. LiveWS
    ProtocolAdapter <|.. SimulatedWS
    IngestMapUseCase --> MapDecoder
```

### 2.1 Architectural Conventions
1. **Unidirectional Data Flow (UDF):** The UI emits user intents (`RobotUserIntent`); the ViewModel processes them through domain use cases and emits a single immutable `RobotUiState` via `StateFlow`.
2. **Pure Kotlin Domain:** The domain layer contains zero Android framework imports (`android.*`), enabling instantaneous, JVM-only unit testing.
3. **Pluggable Transports:** All networking logic is hidden behind `ProtocolAdapter` interfaces. The application runtime can switch seamlessly between `SimulatedRobotAdapter` (offline test) and `LiveOkHttpAdapter` (production).
4. **Manual Dependency Injection:** To avoid bloated annotation processors (Dagger/Hilt) and reduce compile times, MVP uses clean, explicit manual dependency injection via a top-level application container (`AppContainer`).

---

## 3. Technology Selection & Justifications

### 3.1 UI & Presentation
- **Jetpack Compose (BOM 2024.10.01):** Replaces 150 legacy XML Activities with declarative, modular composables.
- **Material 3:** Modern design tokens, accessible color palettes, and adaptive layouts.
- **Kotlin Coroutines (`1.9.0`) & StateFlow:** Structured concurrency with lifecycle-aware collection via `collectAsStateWithLifecycle()`.

### 3.2 Network & Serialization
- **OkHttp (`4.12.0`):** High-performance, robust HTTP and WebSocket client.
- **Kotlinx.serialization (`1.7.3`):** Strictly replaces legacy Gson. Provides compile-time type safety, reflection-free speed, and robust handling of default values and polymorphic schemas.
- **ZLIB Decompression:** Uses standard `java.util.zip.Inflater` (zero third-party dependencies required for map decompression).

### 3.3 Security & Cryptography
- **Android Keystore:** Hardware-backed key generation (`KeyGenParameterSpec`) with `AES/GCM/NoPadding`.
- **Modern Secure Storage:** Replaces deprecated `EncryptedSharedPreferences` with custom lightweight Keystore-backed preferences, eliminating Jetpack Security alpha bugs and deprecation debt.
- **Network Security Config:** Enforces HTTPS/WSS and denies cleartext traffic unconditionally.

### 3.4 Verification & Testing Libraries
- **JUnit 5 / JUnit 4:** Unit test execution runner.
- **MockWebServer (`4.12.0`):** Local deterministic HTTP/WebSocket mock server.
- **Kotlinx Coroutines Test (`1.9.0`):** `TestScope` and `StandardTestDispatcher` for virtual time test execution.
- **AssertK or Truth:** Fluent, readable assertions with clear diffs.
- **Compose UI Testing (`androidx.compose.ui:ui-test-junit4`):** Headless and on-device composable verification.

---

## 4. Prohibited Technologies & Anti-Patterns

| Prohibited Technology | Why Prohibited | Approved Alternative |
| :--- | :--- | :--- |
| **ThroughTek Native Libraries (`libIOTCAPIs.so`, etc.)** | Closed-source proprietary binary, licensing violation, security risk. | Excluded from MVP; pure clean-room Kotlin only. |
| **GreenRobot EventBus** | Global mutable state, untraceable event spaghetti, difficult to test. | Kotlin StateFlow and SharedFlow within ViewModel. |
| **Gson** | Runtime reflection, silent nullability bugs, slow performance. | `kotlinx.serialization.json`. |
| **`EncryptedSharedPreferences`** | Deprecated by Google, known concurrency crashes and data corruption. | Android Keystore authenticated AES-GCM wrapper. |
| **MD5 / SHA-1 for Credentials** | Broken cryptographic algorithms (`SEC-04`). | SHA-256 / PBKDF2 / Modern OAuth Bearer tokens. |
| **Global Cleartext HTTP** | Transmits network credentials in plain text (`SEC-01`). | Strict TLS and local encrypted communication. |
| **Dagger / Hilt (in MVP)** | Excessive boilerplate, generated code overhead, slow builds. | Pure Kotlin `AppContainer` manual dependency injection. |
