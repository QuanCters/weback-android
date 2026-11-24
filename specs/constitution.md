# Project Constitution — WeBack Android Project

**Document ID:** SPEC-CONST-01  
**Version:** 1.0.0 (Proposed Baseline)  
**Effective Date:** 2026-11-24  
**Governance Authority:** Product Owner & Lead Maintainer  

---

## 1. Fundamental Principles (The 14 Invariants)

### C-001 (MUST): Evidence-First Development
All technical statements, protocol implementations, and behavior claims must be grounded in verified evidence. No assertion of correctness, compatibility, or security is valid without reproducible evidence. Narrative summaries cannot override bytecode or executable test results.

### C-002 (MUST): Spec-First Implementation
No production source code or public API shall be authored without an approved specification (`specs/capabilities/` or `specs/changes/`). All features must be defined in terms of problem statement, observable requirements, and explicit non-goals before implementation begins.

### C-003 (MUST): Verification Design Precedes Implementation
The verification plan, test cases, and independent test oracles must be designed alongside the technical specification, strictly before implementation code is written. Code without a predefined verification mechanism is dead on arrival.

### C-004 (MUST): Independent Test Oracles
Test assertions must calculate expected outcomes independently from the system under test. Tests must never re-invoke or mirror the production implementation logic to compute golden values.

### C-005 (MUST): Incremental Delivery & Vertical Slices
Changes must be decomposed into small, independently reviewable, testable vertical slices (Tasks). Each slice must result in runnable, verified code rather than broad horizontal layers of speculative architecture.

### C-006 (MUST): Risk-Based Quality Gates (G0–G8)
Every change must be classified into a risk tier (T0 through T4) and pass all mandatory quality gates defined in `policies/quality-gates.md`. No gate transition may occur without verifiable execution artifacts.

### C-007 (MUST): Separation of Authority
AI Agents and automated tools are executors within delegated bounds; they can never grant themselves authority. The Implementation Agent cannot approve specifications, waive policies, approve PRs, or authorize releases. Product and governance decisions remain the sole domain of human owners.

### C-008 (MUST): Verification Integrity & Anti-Weakening
Existing regression tests, golden fixtures, test assertions, coverage thresholds, and verification policies are protected assets. An agent must never delete, disable, ignore, or weaken an existing test or assertion to make a build pass.

### C-009 (MUST): Defect-to-Regression 10-Step Lifecycle
Every confirmed defect must be converted into a permanent regression test following the 10-step lifecycle:
$$\text{Detect} \rightarrow \text{Reproduce} \rightarrow \text{Root Cause} \rightarrow \text{RED Test} \rightarrow \text{Fix} \rightarrow \text{GREEN} \rightarrow \text{Generalize} \rightarrow \text{Verify Verifier} \rightarrow \text{Review} \rightarrow \text{Permanent Protection}$$

### C-010 (MUST): Security & Privacy by Design
No real credentials, production access tokens, private keys, or customer PII shall ever be committed to Git or unredacted test logs. Network communication must default to secure TLS; global cleartext HTTP is prohibited. Sensitive local storage must utilize Android Keystore hardware-backed encryption.

### C-011 (MUST): Reproducible Builds & Pinned Dependencies
All compilers, toolchains, build tools, libraries, and plugins must be pinned to explicit, stable versions. Build outputs must be deterministic.

### C-012 (MUST): Evidence-Based Completion
Completion claims require verifiable proof. `SKIP`, `ERROR`, `NOT_RUN`, or dirty repository state shall be treated as an absolute `FAIL` at mandatory gates.

### C-013 (MUST): Human-Controlled Merge and Release Authorization
The merge of code into the `main` branch and the release of application packages require explicit human approval. Automation and agents may prepare candidates, but only human owners can authorize distribution.

### C-014 (MUST): Clean-Room Implementation & IP Respect
The application source code must be 100% clean-room authored. Reverse-engineering evidence is reference-only for interoperability. No proprietary third-party binary blobs, decompiled Java/Smali code, or copyrighted proprietary assets may be bundled or redistributed.

---

## 2. Separation of Roles & Authority Matrix

| Role | Human / Agent | Authorized Scope | Explicit Restrictions |
| :--- | :--- | :--- | :--- |
| **Product Owner** | Human | Approves product mission, requirements, roadmap, scope changes, and final release (Gates G0, G1, G7, G8). | Cannot declare technical checks passed without evidence. |
| **Lead Maintainer** | Human | Approves technical architecture, ADRs, Constitution amendments, quality policies, and merge decisions (Gates G2, G3, G6). | Must uphold Constitution invariants. |
| **Specification Agent** | AI Agent | Drafts proposals, requirement IDs, acceptance criteria, and risk assessments. | Cannot approve requirements or resolve blocking business ambiguities. |
| **Architecture Agent** | AI Agent | Designs interfaces, data flows, FSM models, and validation plans. | Cannot alter Constitution or eliminate required security boundaries. |
| **Implementation Agent**| AI Agent | Writes application code, unit tests, and task completion reports within assigned task scope. | Cannot modify protected policies, weaken tests, or approve own code. |
| **Verification Agent** | AI Agent | Audits test oracles, runs Verification Harness, checks evidence logs, and measures coverage. | Cannot grant waivers or accept modified golden files without human approval. |
| **Review Agent** | AI Agent | Conducts read-only multi-perspective code and architecture reviews (correctness, security, integrity). | Read-only; cannot modify code or approve PRs. |

---

## 3. Amendment and Exception Policies

### 3.1 Constitutional Amendments
Any amendment to this Constitution:
1. Must be proposed via a dedicated Pull Request affecting solely `specs/constitution.md`.
2. Must document the rationale, blast radius, and historical context.
3. Must be unanimously approved by the Product Owner and Lead Maintainer.

### 3.2 Policy Waivers
Waivers for mandatory quality gates are exceptional, temporary, and tightly controlled:
- Must have a unique Waiver ID, explicit scope, technical justification, compensating controls, expiration date, and follow-up tracking issue.
- **Waivers can never transform a `FAIL` into a `PASS`.** They represent an explicit decision to proceed with documented residual risk.
- Zero-tolerance policies (e.g., hardcoded credentials, verification tampering) are strictly non-waivable.
