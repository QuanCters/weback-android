# Deployment, Build & Environment Policy — WeBack Android

**Document ID:** SPEC-DEP-01  
**Version:** 1.1.0 (Proposed Calibrated Baseline)  
**Effective Date:** 2026-11-24  
**Author:** Principal Android Architect  
**Governance Authority:** Product Owner & Lead Maintainer  
**Status:** PROPOSED BASELINE (Awaiting Formal Human Review & Approval)  

---

## 1. Operational Contexts & Target Environments

Engineering activities are strictly partitioned across six distinct operational contexts with defined network access rules, trust boundaries, and credential permissions:

| Operational Context | Purpose | Network Access & Enforcement | Credentials Allowed |
| :--- | :--- | :--- | :--- |
| **1. Toolchain Provisioning** | Installation of approved JDK, Android SDKs, and build tooling. | External download permitted strictly from verified vendor domains (Google, Gradle, Maven Central) upon explicit human authorization. | None (Public mirrors with verified checksums). |
| **2. Trusted Dependency Resolution** | Fetching pinned libraries during build initialization. | Outbound HTTPS to pinned repositories (Maven Central, Google Maven); dependency lockfiles or explicit checksums required. | Repository credentials (read-only) via CI secrets if private; none for public OSS. |
| **3. Offline Verification** | Fast local verification, test harness execution, contract audits. | Offline by design. Verification runner executes locally without requiring external internet access. *Note: Network isolation must not be claimed unless enforced by active OS sandbox/container/namespace boundaries.* | Synthetic mock tokens and test fixtures only. |
| **4. Local Simulator Communication** | In-process MockWebServer and simulated robot communication. | Bound strictly to loopback (`127.0.0.1` / localhost). Local mock networking must never weaken production network security. | Ephemeral test keys and dummy JWTs only. |
| **5. Controlled Physical-Device Validation** | Physical robot compatibility testing (Milestone FCT-005). | Isolated Wi-Fi test VLAN without external route to internal corporate networks or internet. Physical line-of-sight E-Stop mandatory. | Dedicated test robot credentials; real production user credentials prohibited. |
| **6. Authorized Production Connectivity** | End-to-end cloud validation or live release operations. | Public Internet (AWS IoT Core, AWS Cognito, WeBack cloud) strictly under explicit, prior Product Owner authorization. | Secure ephemeral tokens injected via environment; never persisted to disk unencrypted. |

---

## 2. Network Security & Mock Isolation Invariants

### 2.1 Production Network Security Configuration
The application Network Security Config (`res/xml/network_security_config.xml`) must enforce strict transport security by default:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

### 2.2 Local Simulator Network Protections
1. **No Production Weakening:** Under no circumstances shall development or test fixtures alter `cleartextTrafficPermitted="false"` globally.
2. **Loopback Scoping:** If local HTTP is required for offline mock servers during debug instrumented testing, the bypass must be strictly scoped to `localhost` or `127.0.0.1` within a dedicated `debug/` build variant manifest, ensuring release builds remain completely non-bypassable.
3. **SoftAP Scoping:** If local device provisioning (e.g. SoftAP Wi-Fi pairing) requires local HTTP communication with robot gateway `192.168.4.1`, that route must be isolated to a dedicated network domain config, documented in an approved Security ADR, and never permitted across general application traffic.

---

## 3. Secret Management, Log Redaction & Evidence Retention

### 3.1 Zero-Secret Rule
- No real credentials, production Cognito Identity Pools, AWS access keys, private signing keys, or Wi-Fi PSKs shall ever be committed to Git or stored in cleartext.
- Configuration templates must reside in `.env.example` with descriptive placeholders. Real local environment values remain untracked in `.env`.

### 3.2 Log Redaction & Scrubbing
All loggers (logcat, harness stdout, and session capture logs) must implement automated redaction filters:
- **Redaction Targets:** Bearer tokens, passwords, Wi-Fi SSIDs/passwords, AWS session keys, robot authentication tokens, device serial numbers, and customer PII.
- **Physical Device Logs:** Hardware validation logs and packet traces captured during physical device testing must be scrubbed prior to archiving. Logs containing unredacted credentials or Wi-Fi passphrases are strictly invalid as public or shared evidence.

### 3.3 Evidence Retention & Access Control
- Test execution artifacts, verification logs, and coverage reports must be retained under version-controlled evidence paths or signed CI storage.
- Verification evidence files must include SHA-256 digests of the tested commit SHA and policies to guarantee provenance.
- Access to raw diagnostic captures containing proprietary device communications is restricted to authorized project maintainers.

---

## 4. Build Repeatability, Traceability & Artifact Verification

### 4.1 Repeatable and Traceable Builds
- All builds must be fully repeatable: given identical commit SHA, toolchain versions, and configuration, the build process must yield deterministic outputs.
- Build environments must record toolchain provenance (exact JDK version, Gradle wrapper hash, AGP version).

### 4.2 Artifact Reproducibility
- Bit-for-bit artifact reproducibility is a separately verified property, audited during release qualification gates.
- Every compiled application package (`.apk` / `.aab`) must be accompanied by an external SHA-256 sidecar file:
  ```text
  app-debug.apk
  app-debug.apk.sha256
  ```

### 4.3 Signing Key Governance
- **Debug Builds:** Use the standard reproducible Android debug keystore generated by the Gradle plugin.
- **Release Candidates:** Must be signed strictly in a dedicated, isolated environment with keys injected via secure CI secret stores. AI Agents and automated PR workflows shall never have access to release signing private keys.
