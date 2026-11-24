# Quality Gates Policy — WeBack Android Project

**Document ID:** POL-GATE-01  
**Version:** 1.0.0 (Proposed Baseline)  
**Effective Date:** 2026-11-24  
**Author:** Verification Architect & Engineering Governance Advisor  
**Playbook Reference:** SDD 2.0 Playbook, Section 3  

---

## 1. The Quality Gate Lifecycle (G0 through G8)

Every engineering deliverable must progress sequentially through defined Quality Gates. State transitions require verifiable evidence and designated human authority.

```text
DRAFT → [G0] → SPEC_APPROVED → [G1] → DESIGN_READY → [G2] → IMPLEMENTATION_READY → [G3]
      → IN_PROGRESS → VERIFYING → [G4] → REVIEWING → [G5] → MERGE_READY → [G6]
      → MERGED → RELEASE_CANDIDATE → [G7] → RELEASE_VERIFIED → [G8]
```

---

## 2. Gate Definitions & Exit Criteria

| Gate | Name | Mandatory Conditions & Required Evidence | Decision Maker |
| :--- | :--- | :--- | :--- |
| **G0** | **Scope Ready** | Problem statement, target user, desired outcome, explicit non-goals, and proposed Risk Tier (T0–T4) documented. | Product Owner |
| **G1** | **Spec Approved** | Functional requirements with persistent IDs (`REQ-xxx`), observable Acceptance Criteria (`AC-xxx`), zero unresolved blocking ambiguities. | Product Owner |
| **G2** | **Design Ready** | Interface boundaries, data models, state machines (FSM), error recovery states, and Threat Model (for T3/T4) approved. | Lead Maintainer |
| **G3** | **Implementation Ready** | Work decomposed into vertical slices (`tasks.md`), independent test oracles specified (`validation.md`), traceability map complete (`traceability.json`). | Lead Maintainer & Verification Agent |
| **G4** | **Verification Passed** | 100% of required checks in `policies/checks.json` pass with exit code 0 on a clean git checkout. Zero `SKIP`, `ERROR`, or `TIMEOUT`. Evidence report generated. | Automated Verification Harness |
| **G5** | **Review Passed** | Multi-perspective independent review complete (Correctness, Security, Architecture, Verification Integrity). All P0/P1 findings resolved. | Independent Reviewer |
| **G6** | **Merge Authorized** | Target base up to date, required CI status checks passing, zero policy modifications without approval, PR squash-merge prepared. | Lead Maintainer |
| **G7** | **Release Authorized** | Deterministic APK artifact built, SHA-256 sidecar verified, manual UAT passed, controlled pilot authorization signed. | Product Owner |
| **G8** | **Release Verified** | Smoke tests pass on target hardware within the observation window; zero critical telemetry alerts. | Product Owner & Release Operator |

---

## 3. Definition of Ready (DoR) & Definition of Done (DoD)

### 3.1 Definition of Ready (Gate G3 Exit)
A task slice is Ready for Implementation only when:
1. The active specification revision is approved and committed.
2. The Risk Tier is assigned and accepted.
3. Every requirement has observable acceptance criteria and an independent test oracle.
4. The allowed modification scope (files/modules) is explicitly bounded.

### 3.2 Definition of Done (Gate G6 Exit)
A task slice is Done only when:
1. Application source code and test code are fully synchronized with the spec.
2. All required checks pass via `verification/run.py` on the exact candidate commit SHA.
3. Traceability report confirms 100% of mandatory acceptance criteria are verified.
4. Independent code review blockers are closed.
5. Canonical specifications (`specs/capabilities/`) are updated within the same PR.

---

## 4. Policy Waiver Governance

1. **Waiver Authority:** Only the Product Owner and Lead Maintainer can grant an exceptional waiver. AI Agents cannot grant waivers.
2. **Mandatory Waiver Record:** Must be filed as a formal record stating:
   - Waiver ID, affected check/requirement, commit SHA, technical rationale, residual risk analysis, compensating control, expiration date, and tracking issue.
3. **No False Passes:** A waiver does not convert `FAIL` to `PASS`. The harness report retains `WAIVED` status, and the gate aggregator records the exception.
4. **Non-Waivable Invariants:** Secret exposure, verification tampering, and zero-evidence assertions can never be waived.
