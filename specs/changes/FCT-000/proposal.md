# FCT-000 Feature Proposal — Minimum Viable Verification Harness

**Change ID:** `FCT-000`  
**Milestone:** `FCT-000`  
**Risk Tier:** `T4 — Critical (Verification Trust Boundary & Policy Assets per POL-RISK-01)`  
**Status:** `DRAFT (Prepared for Implementation Baseline Sign-Off)`  
**Author:** Verification Architect & Engineering Governance Advisor  
**Proposed Effective Date:** 2026-11-24  

---

## 1. Executive Summary & Problem Statement

In the **Verification-First Spec-Driven Development (SDD) 2.0** framework, software correctness and compliance are established through verifiable evidence rather than narrative assertions. 

The WeBack Android project faces an immediate architectural challenge upon repository initialization:
1. **The Verification Bootstrap Deadlock:** Traditional Android projects rely on `./gradlew test` and Android SDK linting for verification. However, requiring Gradle test execution before an Android project, build tooling, or SDK is provisioned causes immediate build failure (`Harness ERROR`). Conversely, generating Android scaffolding and Gradle modules before establishing an automated verification harness violates Constitution Invariant `C-002` (Spec-First Implementation) and `C-003` (Verification Design Precedes Implementation).
2. **Autonomous Agent Verification Hazards:** When autonomous agents author both application code and test suites, inherent incentives exist to loosen timeouts, weaken assertions, bypass failing checks, or produce synthetic reports to claim completion. Without an independent, non-bypassable verification engine enforcing clean checkouts and cryptographic provenance, automated quality gates cannot be trusted.

**Solution:** `FCT-000` establishes a lightweight, standalone, standard-library Python verification harness (`verification/run.py`), a declarative check registry (`policies/checks.json`), a proposed bootstrap policy authorization manifest (`policies/bootstrap_proposal.json`), dual verification lanes (Fast Local vs. Clean Candidate), a structured test reporting adapter (`test_adapter.py`), machine-readable evidence generation, an isolated selftest testbed, and an institutionalized Defect-to-Regression mechanism. 

---

## 2. Business & Engineering Objectives

- **O1 (Zero-Dependency Bootstrap):** Implement the verification harness using only the Python 3 standard library (`subprocess`, `hashlib`, `json`, `os`, `sys`, `time`, `signal`), requiring zero third-party Python packages, zero Gradle plugins, and zero Android SDK tools during bootstrap.
- **O2 (Fail-Closed Gate Enforcement):** Enforce fail-closed gate evaluation where any command non-zero exit code, unhandled timeout, missing required check, unexpected skip, or post-run working tree mutation results in a strict `FAIL` verdict. Reject forged reports or inconsistent status codes.
- **O3 (Progressive Check Activation):** Provide a decoupled registry model where verification checks for documentation hygiene, spec traceability, Gradle builds, contract tests, and physical hardware validation are activated progressively as project milestones mature.
- **O4 (Dual-Lane Operation & Candidate Locking):** Separate fast iterative developer verification (which tolerates dirty worktrees for rapid feedback) from candidate verification (which strictly mandates a clean git checkout, canonical policy locking, prohibits targeted checks, records tested commit SHAs, and generates immutable cryptographic evidence).
- **O5 (Structured Test Execution):** Eliminate fragile console scraping; test suites execute via a structured test runner adapter (`verification/checks/test_adapter.py`) emitting verifiable machine-readable summaries.
- **O6 (Self-Improving Quality Lifecycle):** Establish a structured schema and workflow (`skills/defect-to-regression/SKILL.md`, `verification/defects.jsonl`, `verification/antipatterns.json`) that converts every discovered bug, spec mismatch, or security regression into a permanent regression test or lint rule with explicit classification.
- **O7 (CI Trust Boundary & Multi-Tier Governance):** Provide a minimal, least-privilege GitHub Actions CI configuration (`.github/workflows/ci.yml`) that executes the verification harness on pull requests without requiring live cloud credentials or write access. Explicitly separate local execution results from CI status and human governance sign-off.

---

## 3. Scope Definition

### 3.1 In-Scope Components
1. **Runner Entry Point (`verification/run.py`):** CLI driver handling `--lane {candidate,fast}`, `--check <id>`, `--report-dir <path>`, `--config <path>`, canonical policy locking in candidate lane, and fail-closed exit codes.
2. **Check Registry (`policies/checks.json`):** Declarative catalog specifying check IDs, execution commands (`argv`), required flags, timeouts, and descriptions. Enforces that at least one required check exists and rejects all-optional registries.
3. **Bootstrap Policy Proposal (`policies/bootstrap_proposal.json`):** Proposed manifest establishing the intended initial policy digest and required checks for FCT-000 with status `PROPOSED`. Authoritative evidence validation binds to externally supplied expected digest parameters rather than treating in-tree files as self-authorizing trust anchors.
4. **Internal Check Modules (`verification/checks/`):**
   - Registry loader and schema validator.
   - Process-group subprocess executor with POSIX process-tree termination on timeout, bounded cleanup, and `cleanup_failed` tracking.
   - Structured test reporting adapter (`test_adapter.py`) using `unittest.TestResult` for exact count and status extraction (total, passed, failures, errors, skips, expected failures, unexpected successes).
   - Evidence collector generating `report.json` and raw hashed `.log` captures, with independent verdict recomputation.
   - Git checkout cleanliness auditor and base branch policy diff inspector.
   - Spec hygiene and requirement traceability validator.
5. **Harness Selftest Suite (`verification/selftests/`):** Automated tests executing within temporary synthetic Git repositories to verify runner behavior under success, failure, timeout, missing binary, malformed registry, and checkout mutation conditions.
6. **Defect-to-Regression Framework:** Machine-readable defect registry schema (`verification/defects.jsonl`) with honest classification enum and null unexecuted evidence fields, anti-pattern rule schema (`verification/antipatterns.json`), and initial seed regressions.
7. **CI Workflow Specification:** `.github/workflows/ci.yml` template configured with read-only permissions, candidate SHA checkout, and clean-checkout runner invocation.

### 3.2 Explicit Non-Goals & Trust Boundaries (Out of Scope for FCT-000)
- **Zero Gradle / Android Code:** No Gradle wrapper generation, no `build.gradle.kts`, no Kotlin source files, and no Android SDK dependencies are introduced in FCT-000.
- **No Process Sandboxing Claims:** The harness executes commands under the invoking host user account; it does not claim to provide an OS-level container sandbox or security boundary against adversarial host code. Realistic POSIX process group limitations are acknowledged: processes detaching via `setsid` or in kernel D-state cannot be guaranteed to terminate; such cases record `cleanup_failed: true`.
- **Four-Tier Governance Separation & External Trust Anchors:**
  1. *Local execution result:* What `run.py` outputs.
  2. *Evidence validation result:* What `evidence.py` verifies against externally supplied expectations (`--expected-sha`, `--expected-base-sha`, `--expected-policy-sha256`, `--expected-required-checks`).
  3. *Remote CI status:* What GitHub Actions evaluates on clean runners. Local YAML structural validation does NOT constitute CI execution.
  4. *Governance authorization:* Explicit sign-off by designated human authorities (Product Owner for scope/milestones, Lead Maintainer for architecture/policy/merge).
  A valid local report is NOT merge authorization. G3 authorization authorizes implementation, not automatic approval of the future policy.
- **No Pre-Emptive Speculative Checks:** No checks shall be activated for Android or network implementations that do not yet exist in the repository.

---

## 4. Risk Classification

- **Proposed Risk Tier:** `T4 — Critical (Elevated from T3 per POL-RISK-01)`
- **Classification Rationale:** Under `policies/risk-tiers.md`, modifications to `policies/` or creation of `verification/run.py` are explicitly classified as `T4 — Critical`. While FCT-000 introduces no production application code or external network protocols, it defines the **verification trust boundary** and the execution harness that will evaluate Quality Gates G1 through G6 for the entire lifecycle of the WeBack Android product. Under `POL-RISK-01`, self-downgrading this risk tier is strictly prohibited.
- **Applicable Quality Gates:** G0 (Scope Ready), G1 (Spec Approved), G2 (Design Ready), G3 (Implementation Ready), G4 (Verification Passed), G5 (Review Passed), G6 (Merge Authorized).
- **Approval Authorities:** Dual human authorization required:
  - Product Owner: Scope, Requirements, and Feature Acceptance (G0, G1, G5, G6).
  - Lead Maintainer: Technical Design, Task Decomposition, Verification Integrity, and Final Merge (G2, G3, G6).
