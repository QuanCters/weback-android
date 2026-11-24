# WeBack Android Application

**Modern, Secure, Clean-Room Android Client for Smart Robot Vacuums**

[![Verification Status](https://img.shields.io/badge/verification-INIT--001%20Proposed-yellow.svg)](policies/quality-gates.md)
[![Engineering Playbook](https://img.shields.io/badge/playbook-SDD%202.0-blue.svg)](specs/constitution.md)
[![Architecture](https://img.shields.io/badge/architecture-Clean%20%2B%20MVI%20%2B%20Compose-green.svg)](specs/tech-stack.md)

---

## 1. Overview

WeBack Android is a modern, high-performance, and secure Android application designed to control Laser SLAM robotic vacuum cleaners. The project is engineered clean-room from the verified technical baseline established by the reverse engineering of WeBack 6.1.7 (`com.yugong.Backome`), eliminating legacy architectural flaws, unencrypted communication pathways, and bloated dependency trees.

## 2. Engineering Methodology

This project strictly adheres to **Verification-First Spec-Driven Development (SDD) 2.0**:
- **Simulator-First:** 100% of core workflows, protocol serializers, state machines, and map rendering engines are verified against deterministic offline simulators before physical hardware testing.
- **Evidence-Based Quality Gates:** Code transitions through formal gates (G0 through G8) backed by verifiable execution evidence.
- **Zero-Bypass Verification Integrity:** Verification policies are fail-closed; test weakening or assertion removal is strictly prevented.

## 3. Current Project State

- **Milestone:** `INIT-001 — Repository Bootstrap & Engineering Constitution` (Baseline Preparation & Review Phase).
- **Branch:** `main` (Local baseline awaiting Product Owner formal sign-off).
- **Production Code Status:** Pre-implementation (No production Kotlin or Gradle modules have been authored).

## 4. Repository Structure

```text
weback-android/
├── AGENTS.md                                # Agent execution charter and boundaries
├── README.md                                # Project overview and entry point
├── .gitignore                               # Git hygiene exclusions
├── specs/
│   ├── constitution.md                      # Foundational engineering principles
│   ├── mission.md                           # Product mission, user problems, scope
│   ├── tech-stack.md                        # Technology stack and architectural rules
│   ├── roadmap.md                           # Incremental delivery milestones
│   └── deployment.md                        # Build, artifact, and environment policies
├── docs/
│   ├── reference/
│   │   └── RE_BASELINE.md                   # Frozen reverse-engineering reference data
│   └── engineering/
│       ├── INIT-001_DISCOVERY_CORRECTIONS.md# Resolution of discovery inaccuracies
│       ├── INIT-001_TOOLCHAIN_READINESS.md  # Detailed toolchain audit & remediation
│       ├── INIT-001_REVIEW_REPORT.md        # Independent audit of INIT-001 baseline
│       └── FCT-000_BOOTSTRAP_PROPOSAL.md    # Incremental harness bootstrap design
└── policies/
    ├── risk-tiers.md                        # T0–T4 Risk classification
    ├── quality-gates.md                     # G0–G8 Gate definitions & exit criteria
    └── verification-integrity.md            # Defect-to-regression & anti-tampering policy
```

## 5. Getting Started (For Contributors & Maintainers)

1. **Read the Constitution:** Review [specs/constitution.md](specs/constitution.md) and [AGENTS.md](AGENTS.md).
2. **Review the RE Reference:** Review [docs/reference/RE_BASELINE.md](docs/reference/RE_BASELINE.md).
3. **Verify Toolchain:** See [docs/engineering/INIT-001_TOOLCHAIN_READINESS.md](docs/engineering/INIT-001_TOOLCHAIN_READINESS.md) for required JDK and Android SDK setup.

## 6. License & Legal Notice

This software is developed clean-room. It bundles **zero** proprietary binary code or copyrighted assets from original vendors. All reverse-engineering references are used strictly for interoperability research in accordance with applicable legal frameworks.
