# FCT-001 Risk Assessment & Governance Classification

**Change ID:** `FCT-001`  
**Milestone:** `FCT-001` (Android Foundation & First Runnable APK)  
**Document ID:** `SPEC-RISK-FCT001`  
**Version:** `1.0.0`  
**Effective Date:** 2026-11-24  
**Governance Authority:** Product Owner & Lead Maintainer (Formal Sign-off Required)  
**Status:** DRAFT (Proposed for G3 Implementation Authorization)  

---

## 1. Executive Summary & Highest Risk Classification

Milestone `FCT-001` establishes the foundational clean-room Android application module, Gradle build toolchain, Material 3 presentation layer, pure Kotlin domain models, and Android-specific verification adapters.

In accordance with `policies/quality-gates.md` and Constitution Invariant `C-006`, deliverables across `FCT-001` span multiple risk tiers. Because the milestone introduces verification trust-boundary extensions and proposed check policies, the overall milestone is governed by its highest applicable tier:

$$\text{Highest Applicable Governance Tier: } \mathbf{T4\ (Critical\ Governance\ Tier)}$$

---

## 2. Risk Tier Classification by Subsystem

| Subsystem / Deliverable Area | Risk Tier | Rationale | Governing Gates |
| :--- | :---: | :--- | :--- |
| **Presentation & UI (`com.weback.robot.ui`)** | **T2** | Declarative Jetpack Compose UI, Material 3 tokens, `DashboardScreen`, `DashboardViewModel`. Pure presentation logic with simulated telemetry; fully reversible without architectural blast radius. | G1, G2, G3, G4, G5 |
| **Domain Models & Manual DI (`domain`, `core`)** | **T2** | Pure Kotlin state models (`RobotState`), immutability semantics, and `AppContainer` manual dependency injection. Zero Android framework dependencies (`android.*`). | G1, G2, G3, G4, G5 |
| **Toolchain & SDK Provisioning (`scripts/`, `gradle/`)** | **T3** | Host JDK 21 LTS, Gradle 8.10.2, Android Gradle Plugin 8.7.2, Kotlin 2.1.21, and Android command-line tools provisioning (`commandlinetools-linux-11076708_latest.zip`). Affects build determinism and environment reproducibility. | G2, G3, G4, G5, G6 |
| **Security & Network Configuration (`network_security_config.xml`)** | **T3** | Global denial of cleartext HTTP traffic (`cleartextTrafficPermitted="false"`), zero credentials hygiene, and manifest hardening. Affects transport layer security. | G2, G3, G4, G5, G6 |
| **Verification Trust Boundaries (`spec_validator.py`)** | **T4** | Enforces trust boundaries between active approved policy (`policies/checks.json`) and proposed checks (`policies/fct001_checks_proposal.json`). Prevents unverified proposals from masquerading as execution evidence. | G3, G4, G5, G6 |
| **Structured Test Adapter (`gradle_test_adapter.py`)** | **T4** | Bridges Gradle JVM unit test execution with the verification harness contract. Enforces XML freshness, detects contradictory counts, rejects skipped tests, and prevents duplicate metric extraction. | G3, G4, G5, G6 |
| **APK Binary & Signature Audit (`apk_validator.py`)** | **T4** | Verifies artifact integrity using official SDK tooling (`apkanalyzer`, `apksigner`) and Python `zipfile`, proving package identity, signature validity, and clean-room exclusion of proprietary vendor binaries. | G3, G4, G5, G6 |
| **Policy Transition & Activation Procedure** | **T4** | Governs the migration from FCT-000 two-check policy to FCT-001 expanded policy without breaking verification on existing main revisions or bypassing CI status checks. | G3, G6 |

---

## 3. Detailed Threat & Risk Analysis with Mitigations

### 3.1 Toolchain & Dependency Supply Chain Risks (T3)
- **Risk R-01: Wrapper Binary Tampering:**
  - *Threat:* An unauthorized or corrupted `gradle-wrapper.jar` or binary distribution ZIP executes arbitrary code during build.
  - *Mitigation:* The Wrapper JAR is pinned to official Gradle release SHA-256 (`2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`). The binary distribution is verified via `distributionSha256Sum = 31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26` in `gradle-wrapper.properties` and validated by `gradle-wrapper-validation`.
- **Risk R-02: Android SDK Download Source Conflict:**
  - *Threat:* Conflicting or unverified SDK command-line tools checksums introduce corrupted platform binaries.
  - *Mitigation:* Downloads use official Google repository (`https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip`) strictly verified against official published SHA-256 (`2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258`).
- **Risk R-03: Bytecode Target Mismatch:**
  - *Threat:* Incompatible bytecode compilation levels between Java and Kotlin compilers lead to runtime linkage failures.
  - *Mitigation:* Explicitly pin both Java source/target compatibility and Kotlin JVM compilation target to bytecode level `17` running under OpenJDK 21 LTS host.

### 3.2 Verification Trust-Boundary & Governance Risks (T4)
- **Risk R-04: Proposed Checks Masquerading as Authoritative Evidence:**
  - *Threat:* Milestone proposed checks (`policies/fct001_checks_proposal.json`) are mistakenly treated as approved policy before candidate verification and human sign-off.
  - *Mitigation:* `spec_validator.py` strictly restricts approved checks to `policies/checks.json`. Verified ACs must reference approved checks; proposed checks referenced by verified ACs fail closed with explicit diagnostic errors. Arbitrary JSON files in `policies/` cannot establish valid check identities.
- **Risk R-05: Test Metric Evaporation in Gradle Unit Tests:**
  - *Threat:* Gradle unit test task passes or fails without structured metric reporting, violating harness contract.
  - *Mitigation:* `verification/checks/gradle_test_adapter.py` executes Gradle unit tests, enforces XML timestamp freshness, detects duplicate test IDs, validates XML count consistency, and emits the structured `TEST_SUMMARY_JSON:` contract.
- **Risk R-06: Premature Policy Replacement Invalidation:**
  - *Threat:* Overwriting `policies/checks.json` during specification PR breaks verification of baseline main commits.
  - *Mitigation:* Active policy remains pinned to `4a004ac8d07178c4164193d827983dfa5a7d86c5245fbcb6df69497e976137fd` during specification PR. Proposed checks are evaluated in diagnostic lane. Formal policy activation occurs solely at G6 merge via authorized PR.

### 3.3 Security, Clean-Room & Intellectual Property Risks (T3/T4)
- **Risk R-07: Proprietary Binary Contamination:**
  - *Threat:* Vendor closed-source binaries (e.g. `libIOTCAPIs.so`, `libAVAPIs.so`) from the legacy APK are accidentally packaged into the new application.
  - *Mitigation:* Automated check `apk-validation` opens the compiled APK via `zipfile` and inspects `lib/` entries, failing closed if any unauthorized native library is packaged. Application is 100% clean-room Kotlin.
- **Risk R-08: Global Insecure Cleartext HTTP:**
  - *Threat:* HTTP cleartext transport leaks telemetry or credentials.
  - *Mitigation:* Android Network Security Config strictly enforces `<base-config cleartextTrafficPermitted="false" />` globally. Android Lint and static checks verify this invariant.
- **Risk R-09: Uncontrolled Device Smoke Test Execution:**
  - *Threat:* Automated smoke test indiscriminately installs to unauthorized physical devices, clears user logs, or damages device state.
  - *Mitigation:* Device smoke test requires explicit device serial selection (`WEBACK_TEST_DEVICE_SERIAL`) and applicable owner authorization. If unauthorized or unavailable, it records `NOT_RUN` / `SKIPPED` without masquerading as `PASS`. No device logs are cleared.

---

## 4. Governance Requirements for T4 Gate Exit

To achieve G3 Authorization and subsequently G6 Merge Authorization:
1. **Dual Human Sign-Off:** Formal written approval is required from both the Product Owner (product scope, acceptance criteria) and Lead Maintainer (technical design, toolchain checksums, verification adapters, policy transition).
2. **Fail-Closed Verification:** 100% of required checks in active policy must pass on clean git checkout. Any `SKIP`, `ERROR`, or dirty repository state strictly blocks candidate progression.
3. **Traceability Parity:** 100% of functional requirements (`REQ-ANDR-101` through `REQ-ANDR-108`) and acceptance criteria (`AC-101.1` through `AC-108.3`) must map to concrete tasks, test oracles, and verification checks in `traceability.json`.
4. **Deterministic Version Pinning:** Centralized version catalog (`gradle/libs.versions.toml`) must contain exact version strings with zero dynamic ranges or `+` operators. Bit-for-bit reproducible packaging remains a separately verified property evaluated during release qualification.
