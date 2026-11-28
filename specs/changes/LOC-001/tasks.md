# LOC-001 Implementation & Verification Roadmap (`Task 1` .. `Task 8`)

- **Change ID:** `LOC-001`
- **Revision:** `1.3.0 (Stage L04A-S Inventory Reconciliation)`
- **Risk Tier:** `T4 (Critical Risk)`
- **Canonical Base SHA:** `184175a14e5eb6659396c95624e2d3b08d1078a1` *(Reconciled against `948c0b23a70a73a83bf2d8cb02e88e071618e2d4` in Stage `L04A-S`)*
- **References:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md) | [t4-transition-proposal.md](t4-transition-proposal.md)

---

### Task 1: Canonical Baseline Reconciliation & Spec-Only Handoff (`L01` / `L01F` / `L01F2` — Current Stage)
- **Planning Alias:** `L01A` + `L01B` + `L01F` + `L01F2`
- **Allowed Paths (`L01`/`L01F`/`L01F2`):** `specs/changes/LOC-001/*`, `docs/engineering/L01_*.md`, `docs/engineering/L01F_*.md`, `docs/engineering/L01F2_*.md` inside `.worktrees/rapido-local-spec` (`spec/rapido-local-first`). Zero edits to `app/`, `gradle/`, `policies/`, `verification/`, `.github/`, root `AGENTS.md`, or rejected `51bdd70`.
- **Scope:** Fetch and verify `origin/main` (`184175a14e5eb6659396c95624e2d3b08d1078a1`), reproduce all 11 canonical candidate checks (`l01-baseline`), verify synthetic HOSLAM fixtures via `verify_capture.py`, remediate all `L01R` and `L01R2` findings, freeze `LOC-001` rev `1.2.0`, and stop for `L01R3` review and `G0`–`G2` / `G3` (`L02A`) sign-offs.
- **Exit Condition:** Verified `L01R2` content manifest match prior to remediation, `spec-hygiene` & `harness-selftests` PASS on frozen `1.2.0`, all `10` `L01R` + `7` `L01R2` findings addressed, zero unauthorized file diffs, external content manifest generated.
- **Maps to:** `REQ-LOCAL-501` (`AC-501.1`, `AC-501.2`), `REQ-LOCAL-509` (`AC-509.2`).

### Task 2: Pure HOSLAM Envelope Framer, Modular Checksum & Typed Errors (`L02A` — Next After Approval)
- **Planning Alias:** `L02A` (`T3`)
- **Mandatory Prerequisites (`POL-GATE-01` §3.1):**
  1. Independent `L01R3` review `PASS` on frozen `LOC-001` rev `1.2.0`.
  2. Explicit PO + Lead Maintainer approvals for `G0`, `G1`, `G2`, and `Task 2` (`L02A`) `G3`.
  3. Approved `LOC-001` spec-only PR cleanly committed and landed on `main` (`--lane candidate` PASS on clean checkout) prior to `L02A` implementation branch creation.
- **Allowed Paths (`L02A`):**
  - `app/src/main/java/com/weback/robot/domain/map/` (`HoslamFrameKind`, `RawHoslamFrame`, `LocalCodecError` envelope cases)
  - `app/src/main/java/com/weback/robot/protocol/local/codec/` (`HoslamStreamFramer`, `HoslamByteUtils`, `HoslamFeedResult`, `HoslamFinishResult`)
  - `app/src/test/java/com/weback/robot/protocol/local/codec/` *(shared common test source set inherited by `testDebug`, `testPilot`, and future `testLocal` without relocation in `L04A`)*
  - `app/src/test/resources/hoslam/` *(strictly synthetic authored vectors ONLY — see import procedure below; shared common test resources)*
  - `specs/changes/LOC-001/traceability.json` *(status/evidence updates for `AC-502.1`, `AC-502.2` only)*
- **Reproducible, Hash-Verified Synthetic Fixture Import Procedure (`L02A`):**
  1. Copy strictly the 8 synthetic files from `docs/rapido_spec_pack/fixtures/` (`valid_mixed.bin`, `bad_checksum.bin`, `bad_padding.bin`, `truncated.bin`, `oversized_header.bin`, `unknown_magic.bin`, `zero_width.bin`, and `manifest.json`) into `app/src/test/resources/hoslam/`.
  2. Verify every SHA-256 against `manifest.json` (`valid_mixed.bin` = `631aa7fabc331da5b9245a193a4f00c0a242096e487db96dfca2707835a46fe5`, etc.) and run `python3 docs/rapido_spec_pack/tools/verify_capture.py` on `valid_mixed.bin` (`PASS`) and all 6 negative files (`EXIT=1`).
  3. Confirm zero private capture files (`hoslmap_20261127_075438.bin`, `robot_2.pcap`) or household pixels enter the repository.
- **Scope:** Pure-JVM `HoslamStreamFramer`, unsigned `readU32Le` (`Long` + `0xFFL`), `<= 2 MiB` pre-allocation cap (`0x80000010L`/`0xFFFFFFFFL` -> `FrameTooLarge`), modular unsigned byte-sum checksum, defensive chunk copies, ordered delivery, clean boundary `EndOfStream`, `TruncatedFrame`, deterministic precedence, session `reset()`, `DomainPurityTest`, synthetic chunking/negative/property tests. Zero `Inflater`, zero sockets, zero deadlines, zero policy edits.
- **Maps to:** `REQ-LOCAL-502` (`AC-502.1`, `AC-502.2`).

### Task 3: Raw Unsigned 8-Bit Raster Decoder, Aux/Trajectory Structural Validator & Mutation/Oracle Verification (`L02B`)
- **Planning Alias:** `L02B` (`T3`)
- **Prerequisite:** `Task 2` (`L02A`) merged on `main` + `G3` for `L02B`.
- **Allowed Paths (`L02B`):**
  - `app/src/main/java/com/weback/robot/domain/map/` (`HoslamFrame`, `RasterSnapshot` with defensive copy + custom `equals`/`hashCode`/redacted `toString`, layout `LocalCodecError` cases)
  - `app/src/main/java/com/weback/robot/protocol/local/codec/` (`HoslamFrameDecoder`)
  - `app/src/test/java/com/weback/robot/protocol/local/codec/` *(common to all variants incl. future `testLocalUnitTest`)*
  - `specs/changes/LOC-001/traceability.json` *(status/evidence updates for `AC-503.1`, `AC-503.2`, `AC-504.1`, `AC-504.2`)*
- **Scope:** Implement `HoslamFrameDecoder` (`hoslmap\0` `< 40` -> `UnsupportedLayout`, `W`/`H` -> `InvalidDimensions`, `L != aligned + 4` -> `UnsupportedLayout`, nonzero pad -> `InvalidPadding`, raw u8 at offset 36 without `Inflater`, opaque `12..27`), `hoslcac\0` (`== 20`) & `hosltraj` (`>= 16`, `% 8 == 0` opaque octet-length rule, zero IEEE-754 float parsing/coordinate arithmetic; `N=0` defensive rule without vendor semantic claim), buffer mutation tests, isolated temporary-workspace mutation-kill verification (`TEST-LOCAL-511`, 11 mutants, zero mutations in `main`), and optional local owner private capture run (`TEST-LOCAL-509`, `NOT_RUN` in CI).
- **Maps to:** `REQ-LOCAL-503` (`AC-503.1`, `AC-503.2`), `REQ-LOCAL-504` (`AC-504.1`, `AC-504.2`).

### Task 4: Offline Replay Repository, Truthful Compose Map Viewer & Non-Fallback UI (`L03A`)
- **Planning Alias:** `L03A` (`T2`)
- **Prerequisite:** `Task 3` (`L02B`) merged on `main` + `G3` for `L03A`.
- **Allowed Paths (`L03A`):**
  - `app/src/main/java/com/weback/robot/protocol/local/` (`LocalTelemetryStreamSource`, `SyntheticReplayTelemetrySource`)
  - `app/src/main/java/com/weback/robot/app/local/` (`LocalMapRepository`, `LocalDiagnosticLogger`, `LocalDiagnosticEvent`)
  - `app/src/main/java/com/weback/robot/ui/map/` (`MapViewModel`, `MapScreen`, `MapCanvas`)
  - `app/src/main/assets/synthetic_fixtures/` *(strictly authored `valid_mixed.bin` + `manifest.json` ONLY, hash-verified against pack `manifest.json`)*
  - `app/src/main/java/com/weback/robot/core/AppContainer.kt` *(temporarily wiring `LocalMapRepository` + `SyntheticReplayTelemetrySource` on existing `AppContainer` prior to `L04A` split)*
  - `app/src/main/java/com/weback/robot/MainActivity.kt`, `app/src/main/java/com/weback/robot/ui/screens/DashboardScreen.kt`, `app/src/main/res/values/strings.xml` *(explicit navigation to map viewer; simulator/pilot preserved)*
  - `app/src/test/java/com/weback/robot/ui/map/`, `app/src/test/java/com/weback/robot/app/local/` *(shared common test source set inherited by `testDebug`, `testPilot`, and future `testLocal`)*
  - `specs/changes/LOC-001/traceability.json`
- **Scope:** Implement offline synthetic replay (`Play`/`Pause`/`Restart`), enforce zero-map replay rejection & exact boundary EOF, `MapCanvas` cached `ImageBitmap` per frame generation with purely visual pan/zoom/reset, replay survival across config change (`recreate`), truthful source/freshness/unsupported labels (`LF-010`..`LF-013`, `LF-015` disabled/absent & labeled unsupported), zero automatic fallback to simulator or cloud pilot (asserted by provider/factory spy counters), and verify offline `debug` APK remains `INTERNET`-free (`apk-validation`).
- **Maps to:** `REQ-LOCAL-501` (`AC-501.3`), `REQ-LOCAL-505` (`AC-505.1`, `AC-505.2`), `REQ-LOCAL-510` (`AC-510.1`).

### Task 5: Governed T4 Variant, Composition-Root Split, Test Migration, Validator & Policy Transition (`L04A`)
- **Planning Alias:** `L04A` (`T4`)
- **Prerequisite:** `Task 4` (`L03A`, `948c0b2`) and `Stage L04A-S` inventory reconciliation spec PR merged on `main`, approved Test Migration Inventory (`45` legacy + `2` `L03A` delta files) & Class-Family DEX Inventory (`design.md` §1.1, `t4-transition-proposal.md` §2–§3, `docs/engineering/L04A_S_*`), explicit dual PO + Lead Maintainer T4 review, and external `TRANSITION_AUTHORIZATION`.
- **Allowed Paths (`L04A`):**
  - `app/build.gradle.kts` (`local` buildType, `localImplementation` Compose BOM + `ui-test-manifest`, `cloudPilot`/`local` source sets, `testDebug`/`testPilot` adding `src/testCloudPilot/java` while preserving default `src/test/` common to all variants — zero `setSrcDirs` replacement — and `testBuildType = "pilot"` preserved)
  - `app/src/main/java/com/weback/robot/WeBackApplication.kt`, `MainActivity.kt`, `core/AppContainer.kt` (retaining `AppContainerDependency`), `core/PilotCapabilityBridge.kt`, `core/OfflineAppContainer.kt` (exposing R1 provider/resolution seams)
  - `app/src/cloudPilot/java/...` *(relocating `DefaultPilotContainer`, `PilotAppContainer`, `PilotCapabilityBridgeImpl` with `PilotUiWorkflow`, `PilotContainerTestHooks`, `PilotSessionController`, `SessionCoordinator`, `DeviceInventoryRepository`, `protocol/{auth,inventory,pilot,ws}/*`, `security/{AuthorizedPilotEndpointTrustPolicy,EndpointTrustPolicy,ExternalPilotAuthorization,KeystoreAeadTokenStore,PasswordDigestStrategy,PilotSanitizedExporter,TokenStore,UriValidator}.kt`, and `ui/pilot/PilotReadinessSheet.kt` out of `src/main/`, wired solely to `debug` and `pilot`)*
  - `app/src/debug/java/com/weback/robot/core/`, `app/src/pilot/java/com/weback/robot/core/`, `app/src/release/java/com/weback/robot/core/`, `app/src/local/java/com/weback/robot/core/` (`VariantAppContainerFactory`)
  - `app/src/local/AndroidManifest.xml` (`INTERNET` only), `app/src/local/res/values/strings.xml`
  - `app/src/test/java/` *(strictly moving ONLY the `45` legacy + `2` `L03A` cloud-dependent files listed in `design.md` §1.1 out to `app/src/testCloudPilot/java/`, leaving `148` eligible existing cloud-independent tests = `20` legacy common files [`67` tests] + `15` `L02A`–`L03A` cloud-independent files [`81` tests] & `app/src/test/resources/hoslam/` in `app/src/test/`, PLUS adding `OfflineLocalMapActivityNavigationTest.kt` [`2` tests])*
  - `app/src/testCloudPilot/java/...` *(relocated `32` cloud/gateway/keystore tests untouched + `13` legacy container/UI-pilot test files + `2` `L03A` cloud-dependent test files [`LocalAppContainerNonFallbackTest.kt`, `LocalMapActivityNavigationTest.kt`] adapted solely on `DefaultPilotContainer`/`PilotAppContainer` references while preserving 100% of assertions & JUnit identities)*
  - `app/src/androidTest/java/com/weback/robot/pilot/PilotDeviceAcceptanceInstrumentationTest.kt` *(container factory/accessor adaptation only; assertions unchanged)*
  - `app/src/testLocal/java/com/weback/robot/...` *(`LocalAppContainerTest`, `LocalBuildSeparationTest`, `LocalSourceSecurityScanTest`)*
  - `verification/checks/apk_validator.py` (symmetric 3-way policy, `$`-aware class-family + `protocol/{auth,inventory,ws}/` prefix matching across ALL `.dex` files, line 370 3-way tuple), `verification/selftests/test_apk_validator.py`, `verification/checks/spec_validator.py` *(optional schema/HIL-target hardening if authorized)*
  - `policies/checks.json` (`11 -> 15` checks), `policies/checks.baseline.json` (updated to `fd870ee6...`)
  - `specs/changes/LOC-001/traceability.json` *(promoting `AC-506.1`..`AC-508.2` `verification_check_id` directly to `local-*` checks)*
  - *(Post-merge maintainer action required before `L04B`)* GitHub repository variables: synchronize `vars.APPROVED_POLICY_SHA256` to landed 15-check `canonical_policy_sha256` and clear `vars.TRANSITION_AUTHORIZATION`.
- **Scope:** Enact isolated `local` variant (`com.weback.robot.local`, `INTERNET` only) with zero concrete cloud factories/controllers in `app-local.apk` DEX from Day 1, verify all `241` preserved JUnit XML test identities in `testDebugUnitTest` & `testPilotUnitTest` plus `148` eligible existing cloud-independent tests + local equivalent/isolation suites in `testLocalUnitTest` via `gradle_test_adapter.py` (and clean `assemblePilotAndroidTest`), execute `$`-aware multi-DEX `apk_validator.py` selftests, perform a mandatory second DEX inventory reconciliation against built `L04A` `debug`/`pilot`/`local` APKs prior to `G5`/`G6`, and activate 15-check T4 policy atomically + synchronize `vars.APPROVED_POLICY_SHA256` post-merge.
- **Maps to:** `REQ-LOCAL-506` (`AC-506.1`), `REQ-LOCAL-509` (`AC-509.1`).

### Task 6: Local Receive-Only Socket Source, RFC1918 Trust Policy, Lifecycle & Diagnostic Privacy (`L04B`)
- **Planning Alias:** `L04B` (`T4`)
- **Prerequisite:** `Task 5` (`L04A`) approved and landed on `main`, post-merge `vars.APPROVED_POLICY_SHA256` verified matching 15-check `policies/checks.json` (hard stop before live networking if stale fallback to 11-check `checks.baseline.json` occurs), and `G3` for `L04B`.
- **Allowed Paths (`L04B`):**
  - `app/src/local/java/com/weback/robot/protocol/local/` (`ReceiveOnlyTcpSource`)
  - `app/src/local/java/com/weback/robot/security/` (`LocalLanTrustPolicy`, `LocalDiagnosticSanitizer`)
  - `app/src/local/java/com/weback/robot/core/` (wiring `ReceiveOnlyTcpSource` into `VariantAppContainerFactory`)
  - `app/src/main/java/com/weback/robot/app/local/`, `app/src/main/java/com/weback/robot/ui/map/` (explicit IP:9999 confirmation, unauthenticated LAN notice, `<= 1_000 ms` lifecycle close on stop/background/exit/rotation/target-change, canonical normative FSM)
  - `app/src/testLocal/java/com/weback/robot/...` (`LocalLanTrustPolicyTest`, `ReceiveOnlyTcpBoundaryTest`, `LocalPrivacyRedactionTest`, `LocalTimeoutAndEofTest`, `LocalLifecycleTeardownTest`, `LocalBackpressureBoundsTest`)
  - `specs/changes/LOC-001/traceability.json`
  *(Note: Shared `SecretRedactor.kt` and `SecretRedactorTest.kt` are explicitly EXCLUDED from `Task 6` and remain unmodified.)*
- **Scope:** Implement `LocalLanTrustPolicy` (canonical RFC1918 IPv4, last octet `1..254`, port `9999`, `Socket(Proxy.NO_PROXY)`, zero DNS, post-connect peer check), `ReceiveOnlyTcpSource` (`0` app bytes written), canonical normative FSM (`Stale -> Streaming/Error/Disconnected`, `Background` -> `Disconnected(BACKGROUNDED)`, `Rotation` -> `Disconnected(CONFIG_CHANGE_RECONNECT_REQUIRED)`, `Stop`/`TargetChange`/`ScreenExit` -> `Idle`, `180s` `SESSION_LIMIT_REACHED`, absolute `15s` in-frame deadline, `<= 1_000 ms` close), construction-time sensitive-data-free `LocalDiagnosticEvent` + `LocalDiagnosticSanitizer` (`<= 200` entries), and fake-server tests (`src/testLocal` only; zero hardware contact in CI).
- **Maps to:** `REQ-LOCAL-506` (`AC-506.2`), `REQ-LOCAL-507` (`AC-507.1`, `AC-507.2`), `REQ-LOCAL-508` (`AC-508.1`, `AC-508.2`).

### Task 7: Human-Authorized Physical Receive-Only Acceptance (`L05` — HIL Gate)
- **Planning Alias:** `L05` (`T4`)
- **Prerequisite:** `Task 6` (`L04B`) merged, exact `local` APK hash bound to candidate SHA, and explicit human operator/PO/Lead HIL authorization.
- **Allowed Paths (`L05`):** External private acceptance log artifacts (zero raw maps/IPs/PCAPs in Git); sanitized status updates in `specs/changes/LOC-001/traceability.json` upon review.
- **Scope:** Execute `HIL-01`..`HIL-05` on authorized test Android >= 12 phone observing owned Rapido R8S with router capture verifying `0` outbound application bytes and `>= 1` valid map (`>= 2` distinct raster hashes for live-update claims).
- **Maps to:** `REQ-LOCAL-510` (`AC-510.2`).

### Task 8: Separately Authorized WAN Isolation (`L06`) & Unknown Closure Backlog (`L07+`)
- **Planning Alias:** `L06` + `L07+` (`T4` / Research)
- **Prerequisite:** `Task 7` (`L05`) passed and separate written authorization for device-scoped WAN isolation (`HIL-06`) or research closure experiments (`U01`..`U12`).
- **Scope:** Execute `HIL-06` (device-scoped WAN block preserving LAN, reversible, no router-wide disruption) and independent closure studies for `LF-010`..`LF-015` via separate per-feature specification PRs.
- **Maps to:** `REQ-LOCAL-510` (`AC-510.3`).
