# Spec-Only Verification Policy Design Proposal (OPT-09)

**Document ID:** PROP-PERF-OPT09  
**Status:** DESIGN PROPOSAL ONLY (Not Activated; Requires T4 Policy Transition Authorization)  
**Target Scope:** Documentation & Specification-Only Pull Requests (`specs/**`, `docs/**`)  

---

## 1. Problem Statement

Under the active 11-check policy ([policies/checks.json](../../policies/checks.json)), specification-only pull requests (which modify only Markdown specifications, schemas, or engineering documentation) execute the entire Android Lint, unit-test, APK build, and APK validation pipeline (`~575s–583s` per CI run).

Because GitHub branch protection requires the `Candidate Verification Lane` job to report `success` on every pull request targeting `main`, skipping the workflow via `paths-ignore` in [.github/workflows/ci.yml](../../.github/workflows/ci.yml) is **unsafe** (it leaves required status checks in a permanently pending state and provides zero `spec-hygiene` / `harness-selftests` verification).

---

## 2. Proposed Architecture (Fail-Closed Scope Routing)

Rather than skipping the CI job, the workflow continues to run `Candidate Verification Lane` and `Authoritative Evidence Audit` on every PR and `push`, while [verification/checks/transition.py](../../verification/checks/transition.py) and [verification/checks/evidence.py](../../verification/checks/evidence.py) deterministically select an explicitly approved spec-only policy **only** when the git diff is proven to touch exclusively allowlisted documentation paths.

### 2.1 Candidate Spec-Only Policy (`policies/checks.spec-only.json` — Proposed)
Contains the 3 non-Android governance and harness integrity checks:
1. `spec-hygiene` (`verification/checks/spec_validator.py`)
2. `harness-selftests` (`verification/checks/test_adapter.py --discover verification/selftests`)
3. `gradle-wrapper-validation` (`verification/checks/gradle_wrapper_check.py`)

### 2.2 Strict Fail-Closed Diff Allowlist
Scope-aware routing may select the spec-only policy **only if ALL** of the following hold:
1. `APPROVED_SPEC_POLICY_SHA256` is explicitly configured in repository variables and matches `policies/checks.spec-only.json`.
2. `git diff --name-only <base_sha>...<candidate_sha>` is non-empty and **every** changed path matches:
   - `specs/**/*.md` or `specs/changes/*/traceability.json`
   - `docs/**/*.md`
   - `policies/*_checks_proposal.json` (status `PROPOSED` only)
3. **Zero** files outside the allowlist are modified (any change to `app/`, `gradle/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `verification/`, `.github/`, `scripts/`, `policies/checks.json`, or `policies/checks.spec-only.json` immediately falls back to full 11-check `policies/checks.json`).
4. Both `transition.py` (context resolution) and `evidence.py` (authoritative post-run audit) independently re-verify the git diff allowlist and exact `APPROVED_SPEC_POLICY_SHA256` digest so a modified runner or forged `report.json` cannot downgrade an Android/harness PR to spec-only checks.

---

## 3. Governance & Activation Requirements

Per [policies/risk-tiers.md](../../policies/risk-tiers.md) and [policies/quality-gates.md](../../policies/quality-gates.md):
- Modifying active policy digests or transition logic in `policies/` and `verification/checks/transition.py` is a **T4 Protected Verification Asset** change.
- This document is a **design proposal only**: PR B does **not** add `policies/checks.spec-only.json`, alter `policies/checks.json`, or activate spec-only policy selection in [verification/run.py](../../verification/run.py).
- Activation requires a dedicated T4 PR with:
  1. Product Owner and Lead Maintainer sign-off.
  2. Negative regression tests in `verification/selftests/test_transition.py` and `verification/selftests/test_evidence.py` proving that adding 1 byte to `app/`, `verification/`, `.github/`, or `policies/checks.json` fails closed back to full 11-check verification.
  3. Explicit `APPROVED_SPEC_POLICY_SHA256` and `TRANSITION_AUTHORIZATION` configuration.
