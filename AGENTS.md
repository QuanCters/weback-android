# Agent Operating Instructions — WeBack Android Project

## 1. Prime Directive

This project strictly follows **Verification-First Spec-Driven Development (SDD) 2.0** and disciplined Superpowers execution.
Every code modification must be preceded by an approved specification, an approved technical design, and an independently defined verification test oracle.
**Never write production application code or modify contracts without prior specification approval.**

## 2. Reading Order Before Any Action

Before executing any task, every agent MUST read:
1. `specs/constitution.md` — Fundamental engineering laws, authority boundaries, and invariant principles.
2. `specs/tech-stack.md` — Technology choices, architectural conventions, and prohibited dependencies.
3. `docs/reference/RE_BASELINE.md` — Ground truth reverse engineering reference and known limitations.
4. The active Change Specification in `specs/changes/<CHANGE_ID>/` (or active milestone in `specs/roadmap.md`).

## 3. Authority and Role Boundaries

| Role | Responsibility | Authority Limits |
| :--- | :--- | :--- |
| **Product Owner (Human)** | Product mission, business outcomes, scope approval, acceptance decisions | Sole authority for G0, G1, and final release sign-off. |
| **Lead Maintainer (Human / Lead)** | Architecture decisions, engineering policy, harness modifications | Sole authority for Constitution changes, G2, G6 merge. |
| **Implementation Agent** | Executes assigned vertical slice, creates scoped source & new tests | **CANNOT** alter approved requirements, weaken tests, or approve own PR. |
| **Verification Agent** | Audits test oracles, executes Verification Harness, measures coverage | **CANNOT** grant waivers, approve test removals, or fudge evidence. |
| **Review Agent** | Independent review of diffs, security, architecture, and integrity | Read-only by default; reports findings with evidence. |

Agents MUST NOT attempt to self-grant authority, bypass quality gates, or approve their own deliverables.

## 4. Verification Integrity Rules

1. **No Test Weakening:** Never delete, disable, `@Ignore`, or comment out existing regression tests or assertions to achieve a passing build.
2. **No Golden Tampering:** Never update snapshot or golden fixtures without documented rationale and explicit maintainer approval.
3. **No Mocking the Oracle:** Test assertions must verify against independent calculations, synthetic schemas, or real invariants, not by re-invoking the code under test.
4. **Fail-Closed Verification:** `SKIP`, `ERROR`, `NOT_RUN`, or dirty repository state is treated as a strict `FAIL`.
5. **Defect-to-Regression Lifecycle:** Any defect identified in code or specifications must follow:
   $$\text{Detect} \rightarrow \text{Reproduce} \rightarrow \text{Root Cause} \rightarrow \text{RED Test} \rightarrow \text{Fix} \rightarrow \text{GREEN} \rightarrow \text{Verify Verifier} \rightarrow \text{Review} \rightarrow \text{Permanent Protection}$$

## 5. Security & Privacy Non-Negotiables

- **Zero Credentials in Git:** Never commit secrets, auth tokens, Wi-Fi credentials, or live test account credentials.
- **Simulator-First by Default:** All automated tests must execute against synthetic fixtures and local mock servers. No automated connection to physical robots or production AWS/WeBack cloud without separate, explicit authorization.
- **No Global Cleartext HTTP:** Android Network Security Config must deny cleartext traffic by default.
- **No Deprecated Storage:** Do not use `EncryptedSharedPreferences`. Use modern Android Keystore primitives with authenticated AES-256-GCM.

## 6. Execution Discipline & Stop Conditions

Stop immediately and request clarification if:
- A task requires modifying a contract, protocol schema, or architecture boundary not authorized in the spec.
- An existing regression test fails unexpectedly.
- A proposed action requires modifying protected policies (`policies/`) or harness scripts (`verification/`).
- Destructive git operations or dependency upgrades are proposed.
