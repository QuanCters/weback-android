# Developer Verification & Fast Feedback Workflow (OPT-09)

## 1. Overview

The verification harness ([verification/run.py](../../verification/run.py)) provides two operational lanes backed by [policies/checks.json](../../policies/checks.json):

1. **`--lane fast` (Local Developer Feedback):** Permits targeted single-check execution (`--check`), targeted unit-test filtering (`--tests`), and dirty working trees while developing.
2. **`--lane candidate` (Authoritative Pre-Push / CI Gate):** Strictly requires a clean git checkout, executes all 11 required checks, and rejects `--check`, `--tests`, and unapproved `--config` files.

---

## 2. Fast Local Developer Commands

### 2.1 Pure-Python Spec & Harness Checks (~0.1s–16s, Zero Gradle)
Run specification link/traceability hygiene or the full Python harness selftest suite before committing:

```bash
python3 verification/run.py --lane fast --check spec-hygiene
python3 verification/run.py --lane fast --check harness-selftests
python3 verification/run.py --lane fast --check gradle-wrapper-validation
```

### 2.2 Targeted Android Unit Tests (`--tests`)
Iterate on a single Kotlin test class or method without running all 11 checks or recompiling unaffected modules:

```bash
python3 verification/run.py --lane fast --check android-unit-tests \
  --tests com.weback.robot.app.pilot.PilotEmergencyCancellationTest
```

Or invoke the adapter directly (`--tests` may be repeated):

```bash
python3 -m verification.checks.gradle_test_adapter \
  --task testDebugUnitTest \
  --results-dir app/build/test-results/testDebugUnitTest \
  --tests com.weback.robot.ConnectivityRecoveryTest
```

> [!NOTE]
> The adapter deletes prior XML reports (`*.xml`) in `--results-dir` before running Gradle, enforces `mtime >= start_time`, and forces test-task execution via `--rerun` (preserving incremental upstream compilation without serving cached test outputs).

### 2.3 Single-Variant Lint, Build, and APK Validation
```bash
python3 verification/run.py --lane fast --check android-lint
python3 verification/run.py --lane fast --check android-build
python3 verification/run.py --lane fast --check apk-validation
```

---

## 3. Authoritative Pre-Push Candidate Check

Before pushing a candidate commit for Gate G4 / PR review, commit your changes and run the full clean-tree candidate lane:

```bash
python3 verification/run.py --lane candidate --base-ref origin/main
```

By default, `verification/run.py` runs with `--max-parallel 2`, executing independent Python/APK checks concurrently with a single serialized Gradle lane while preserving exact check ordering in `report.json`. Pass `--max-parallel 1` to force strictly sequential execution when debugging.

`--lane candidate` fails closed (`exit 2`) if `--check` or `--tests` is supplied, or if the working tree has uncommitted/untracked changes outside `artifacts/verification/`.

---

## 4. Spec-Only PR Verification (Design Proposal Only)

A design proposal for scope-aware verification on specification-only PRs (`specs/**`, `docs/**`) is documented in [SPEC_ONLY_VERIFICATION_PROPOSAL.md](../engineering/SPEC_ONLY_VERIFICATION_PROPOSAL.md). It is **not active** and requires explicit Product Owner and Lead Maintainer T4 transition approval before enabling.
