# Deployment, Build & Environment Policy — WeBack Android

**Document ID:** SPEC-DEP-01  
**Version:** 1.0.0 (Proposed Baseline)  
**Effective Date:** 2026-11-24  
**Author:** Principal Android Architect  
**Approved by:** Lead Maintainer (Pending Formal Baseline Sign-off)  

---

## 1. Target Environments

| Environment | Purpose | Network Access | Credentials Allowed |
| :--- | :--- | :--- | :--- |
| **Local Workstation** | Task implementation, fast local verification | Offline / Localhost only | Synthetic test tokens only |
| **Verification CI** | Pull Request gates, full clean-checkout harness | Isolated / No external network | Ephemeral CI test secrets only |
| **Android Emulator** | Headless UI tests, orientation, accessibility | Virtual isolated subnet | None |
| **Controlled Test Device** | Physical compatibility validation (FCT-005) | Local Wi-Fi subnet only | Dedicated test account only |
| **Production Cloud** | Live end-user operation | Public Internet / AWS IoT | Separate human authorization required |

---

## 2. Configuration & Secret Management

### 2.1 The Zero-Secret Rule
- Under no circumstances shall real user credentials, AWS Cognito Identity IDs, production API keys, or private signing keys be committed to Git or stored in plain text.
- Configuration parameters must be defined via a template file: `.env.example` containing descriptive variable names and mock values.
- Actual local development overrides may reside in an untracked `.env` file (enforced via `.gitignore`).

### 2.2 Network Security Configuration
The Android Network Security Config (`res/xml/network_security_config.xml`) must enforce:
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
Cleartext HTTP traffic is disabled globally by default. If a local device provisioning protocol (e.g., SoftAP setup) strictly requires local HTTP on subnet `192.168.4.1`, that exemption must be narrowly scoped to that specific IP and documented in an approved Security ADR.

---

## 3. Artifact Packaging & Integrity Verification

### 3.1 Build Reproducibility
- All Gradle builds must produce deterministic outputs given identical source commits and toolchains.
- Every compiled application artifact (`.apk`) must be accompanied by an external SHA-256 sidecar file:
  ```text
  app-debug.apk
  app-debug.apk.sha256
  ```

### 3.2 Signing Key Management
- **Debug Builds:** Uses the standard reproducible Android debug keystore generated locally by the Gradle plugin.
- **Release Candidates:** Must be signed in a dedicated, isolated build environment with credentials injected via secure environment variables. Agents and automated PR runners shall never have access to release signing private keys.

---

## 4. Controlled Hardware Pilot Procedure (Milestone FCT-005)

Direct interaction with physical hardware is governed by strict safety protocols:

1. **Pre-requisite:** All automated unit, contract, state-machine, and simulated UI tests must achieve 100% PASS on the candidate commit.
2. **Network Isolation:** Physical robot testing must be conducted on an isolated, dedicated Wi-Fi test VLAN without cross-access to private enterprise networks.
3. **Emergency Stop (E-Stop):** Testing must be conducted within physical line-of-sight with immediate physical access to the robot's physical power switch.
4. **Audit Trail:** All outbound WebSocket messages and inbound telemetry frames during the physical pilot must be logged to a local session capture for independent review.
