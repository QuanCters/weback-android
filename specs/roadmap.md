# Engineering Roadmap — WeBack Android Project

**Document ID:** SPEC-ROAD-01  
**Version:** 1.0.0 (Proposed Baseline)  
**Effective Date:** 2026-11-24  
**Author:** Principal Android Architect  
**Approved by:** Product Owner (Pending Formal Baseline Sign-off)  

---

## 1. Roadmap Architecture

The engineering roadmap follows strict **Verification-First Spec-Driven Development 2.0**.
Milestones are structured as incremental, vertical slices delivering verifiable outcomes. No milestone begins before its predecessor passes its designated Quality Gate.

```mermaid
flowchart TD
    INIT001["INIT-001: Repository Bootstrap & Constitution"]
    FCT000["FCT-000: Minimum Viable Verification Harness"]
    FCT001["FCT-001: Android Foundation & Debug APK"]
    FCT002["FCT-002: End-to-End Simulated Robot Control"]
    FCT003["FCT-003: SLAM Map Ingestion & Rendering"]
    FCT004["FCT-004: Suction Power, Water & Schedules"]
    FCT005["FCT-005: Controlled Hardware Pilot"]

    INIT001 -->|Gate G1/G2| FCT000
    FCT000 -->|Gate G4 (Harness)| FCT001
    FCT001 -->|Gate G4 (Build)| FCT002
    FCT002 -->|Gate G4 (Simulation)| FCT003
    FCT003 -->|Gate G4 (Map)| FCT004
    FCT004 -->|Gate G4 (Features)| FCT005
```

---

## 2. Milestone Specifications

### Milestone INIT-001: Repository Bootstrap & Engineering Constitution
- **Status:** `PROPOSED BASELINE (Awaiting PO Sign-off)`
- **Observable Outcome:** A clean, initialized Git repository containing the complete engineering constitution, architecture rules, frozen RE baseline, governance policies, and toolchain audit.
- **Allowed Scope:** Documentation, specifications, policies, `.gitignore`, `AGENTS.md`. Zero production application code.
- **Dependencies:** None.
- **Acceptance Gate:** `Gate G1 (Spec Approved)` & `Gate G2 (Design Ready)` sign-off by Product Owner and Lead Maintainer.

---

### Milestone FCT-000: Minimum Viable Verification Harness
- **Status:** `PLANNED`
- **Observable Outcome:** Fully functional, standalone Python verification runner (`verification/run.py`), check registry (`policies/checks.json`), and comprehensive selftests suite passing with 100% success rate in a clean checkout.
- **Allowed Scope:** `verification/`, `policies/checks.json`, `.github/workflows/ci.yml`. Zero Android code.
- **Dependencies:** INIT-001 approved.
- **Acceptance Gate:** `Gate G4 (Verification Passed)` — All runner selftests (intentional fail, timeout, dirty checkout, invalid report) pass with verified evidence.

---

### Milestone FCT-001: Android Foundation & Runnable Debug APK
- **Status:** `PLANNED`
- **Observable Outcome:** Provisioned Android SDK (compileSdk 35), Gradle wrapper 8.10.2, minimal Kotlin 2.0 / Compose skeleton compiling deterministically into a signed Debug APK (`app-debug.apk`) with verified SHA-256 sidecar.
- **Allowed Scope:** `app/build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `app/src/main/AndroidManifest.xml`, entry `MainActivity.kt`.
- **Dependencies:** FCT-000 passed; Product Owner authorization for toolchain installation.
- **Acceptance Gate:** `Gate G4 (Verification Passed)` — Gradle clean build passes, Android lint passes, and debug APK artifact digest verified.

---

### Milestone FCT-002: First End-to-End Simulated Robot Control Feature
- **Status:** `PLANNED`
- **Observable Outcome:** The first complete vertical slice: Domain entity `RobotInfo`, MVI state machine, local MockWebServer/WebSocket simulated robot endpoint, and Jetpack Compose dashboard allowing a user to toggle `Clean`, `Pause`, and `GoHome` commands with instant UI state feedback.
- **Allowed Scope:** `app/src/main/java/com/weback/domain/`, `app/src/main/java/com/weback/protocol/`, `app/src/main/java/com/weback/ui/`, unit and contract test suites.
- **Dependencies:** FCT-001 passed.
- **Acceptance Gate:** `Gate G4 (Verification Passed)` — 100% offline contract tests pass; end-to-end simulated control verified without network dependencies.

---

### Milestone FCT-003: SLAM 2D Binary Map Ingestion & Canvas Display
- **Status:** `FUTURE`
- **Observable Outcome:** Pure Kotlin ZLIB decompression engine decoding occupancy grid packets into matrix models, applying the forward/backward affine coordinate transform, and rendering the floor map smoothly on a Compose `Canvas`.
- **Allowed Scope:** Map decompression use case, coordinate transformer, `MapCanvas` composable, golden matrix tests.
- **Dependencies:** FCT-002 passed.
- **Acceptance Gate:** `Gate G4 (Verification Passed)` — Golden map fixtures decode and render with zero coordinate drift.

---

### Milestone FCT-004: Suction Power, Water Level & Scheduling Automation
- **Status:** `FUTURE`
- **Observable Outcome:** Suction power controls (`wind_power`), water level regulation (`water_level`), and REST scheduled countdown timer creation wrapping shadow state in `thing_shadow.state.desired`.
- **Dependencies:** FCT-003 passed.
- **Acceptance Gate:** `Gate G4 (Verification Passed)` — Protocol duality tests (`REG-01`) pass.

---

### Milestone FCT-005: Controlled Hardware Compatibility Validation
- **Status:** `FUTURE (Controlled Pilot)`
- **Observable Outcome:** Validation of the application against the Product Owner's physical Laser SLAM robot vacuum in a secured, isolated test environment under explicit human supervision.
- **Allowed Scope:** Live connection adapter, physical smoke test protocol.
- **Dependencies:** FCT-004 passed; separate written authorization from Product Owner.
- **Acceptance Gate:** `Gate G7 (Release Authorized)` & `Gate G8 (Release Verified)` — Physical robot responds accurately to commands and telemetry streams reliably without security faults.
