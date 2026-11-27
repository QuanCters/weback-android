# L01F T4 Transition, Variant Isolation & LAN Policy Decisions (`LOC-001`)

This document records the authoritative architectural, Gradle, validator, and security boundary decisions updated under Stage `L01F2` (`LOC-001` rev `1.2.0` solving `FIND-L01R-03`..`06` and `FIND-L01R2-01`, `02`, `03`, `05`, `07`). All `T4` code, Gradle, manifest, policy, and CI variable edits remain **proposed only for future stage `L04A` / `L04B`**.

---

## 1. Structural Variant Isolation, Composition-Root Split & Test Migration (`L04A` Day-1)

### 1.1 Why Boolean Flags & Partial Decoupling Failed (`FIND-L01R-03`, `FIND-L01R2-01`)
On canonical `origin/main` (`184175a`), `MainActivity.kt` (`lines 63, 71, 75`) reads `app.container.pilotSessionController` and `pilotAuthorizationGate`, `DefaultAppContainer.kt` (`lines 140-351`) directly constructs OkHttp/WS/Keystore classes, and `13` unit tests + `1` instrumentation test construct `DefaultAppContainer(...)` or access cloud/mock members on `AppContainer`. Gating solely by booleans leaks cloud DEX classes into `app-local.apk`, while stripping `DefaultAppContainer` out of `src/main/` and falsely claiming `app/src/test/java/` remains untouched breaks compilation.

### 1.2 Adopted Clean-Room Composition-Root & Additive Source-Set Partition (`L04A`, `FIX-01` & `FIX-02`)
Preserving the single `:app` module, `DomainPurityTest` (`src/main/java/com/weback/robot/domain/`), `Fct004BPreInternetSecurityTest.kt:411`, and all tested behavior/assertions/invariants:
1. **`src/main/` (Shared Across `debug`, `pilot`, `release`, `local`):**
   - `WeBackApplication` calls build-type-provided `VariantAppContainerFactory.create(applicationContext)` and keeps `replaceContainerForTesting(AppContainer)`.
   - `AppContainer` exposes strictly common offline/local dependencies plus nullable `pilotCapabilityBridge: PilotCapabilityBridge?` (`null` in `local` & `release`); `OfflineAppContainer` implements it cleanly; `MainActivity` renders Pilot UI/lifecycle strictly via `pilotCapabilityBridge != null`.
   - Retains all `domain/**`, `core/{BootSessionTimeProvider,DispatcherProvider,MonotonicClock}.kt`, `protocol/{RobotTransport,SimulatorConfig,SimulatorRobotTransport}.kt`, `security/{DiagnosticLogger,SecretRedactor}.kt` (unmodified), `ui/{DashboardViewModel,screens/DashboardScreen,theme/*}`, and `LOC-001` common code (`protocol/local/codec/*`, `protocol/local/*`, `app/local/*`, `ui/map/*`).
2. **`src/cloudPilot/` (Wired ONLY to `debug` and `pilot`; EXCLUDED from `local` & `release` DEX):**
   - Holds `PilotAppContainer : AppContainer`, `DefaultPilotContainer` (preserving former `DefaultAppContainer` behavior), `PilotCapabilityBridgeImpl`, `PilotContainerTestHooks`, `PilotSessionController`, `SessionCoordinator`, `DeviceInventoryRepository`, `protocol/{auth,inventory,pilot,ws}/*`, `security/{AuthorizedPilotEndpointTrustPolicy,EndpointTrustPolicy,ExternalPilotAuthorization,KeystoreAeadTokenStore,PasswordDigestStrategy,PilotSanitizedExporter,UriValidator}.kt`, and `ui/pilot/PilotReadinessSheet.kt`.
3. **Common & Variant Test Source Sets (Zero `setSrcDirs` Exclusion!):**
   - **`app/src/test/{java,resources}`:** Retained natively for **all variants** (`testDebugUnitTest`, `testLocalUnitTest`, `testPilotUnitTest`). Contains all `20` existing pure offline/common files (`67` tests incl. `DashboardScreenControlTest`, `DomainPurityTest`, `TestDispatcherProvider`, `DashboardViewModel*Test`, `SimulatorRobotTransportTest`, `DiagnosticLoggerTest`, `SecretRedactorTest`), all `L02A`–`L03A` HOSLAM/replay tests, and `app/src/test/resources/hoslam/`.
   - **`app/src/testCloudPilot/java/`:** Added additively to `testDebug` and `testPilot` (`srcDir`), receiving the `45` migrated cloud/pilot/session/keystore/container unit test files (`90` tests) under the approved `design.md` §1.1 Migration Inventory (`13` adapted solely on `DefaultPilotContainer`/`PilotAppContainer`, `32` relocated untouched except `EndpointTrustAndRedirectTest` scanning `src/cloudPilot` too).
   - **`app/src/testLocal/java/`:** Holds `L04A` isolation tests (`LocalAppContainerTest`, `LocalBuildSeparationTest`, `LocalSourceSecurityScanTest`) and `L04B` socket/lifecycle/privacy tests (`testLocalUnitTest` only).
   - **`app/src/androidTest/java/`:** Preserves pinned `testBuildType = "pilot"` (`PilotDeviceAcceptanceInstrumentationTest` adapted on `DefaultPilotContainer`/`PilotAppContainer` + untouched `KeystoreHardwareSecurityLevelTest`).
   - **`L04A` Prerequisite:** Approved Migration Inventory, clean compile, and `gradle_test_adapter.py` JUnit XML test identity match across all executed suites before `local` passes `L04A`.

---

## 2. Exact `app/build.gradle.kts` & Instrumentation Governance (`L04A`, `FIND-L01R-04`)

- **`defaultConfig`:** Adds `LOCAL_LAN_CAPABLE = false`.
- **`buildTypes.create("local")`:** `initWith(getByName("debug"))`, `applicationIdSuffix = ".local"`, `matchingFallbacks += listOf("debug")`, `PILOT_INTERNET_CAPABLE = false`, `LOCAL_LAN_CAPABLE = true`, `APP_CONNECTIVITY_MODE = "LOCAL_READ_ONLY"`.
- **Required Variant Compose Test Dependencies (`localImplementation`):**
  ```kotlin
  add("localImplementation", platform(libs.androidx.compose.bom))
  add("localImplementation", libs.androidx.compose.ui.test.manifest)
  ```
- **Pinned `testBuildType = "pilot"`:** Preserved unchanged in `L04A`.

---

## 3. Symmetric 3-Way `$`-Aware `apk_validator.py` & Governed CI Activation (`L04A`, `FIND-L01R2-03` & `FIND-L01R2-07`)

*(Full class-family stems, test oracles, and 9-step CI lifecycle specified in [t4-transition-proposal.md](../../specs/changes/LOC-001/t4-transition-proposal.md) §3–§4; zero edits to `apk_validator.py` or `.github/` during `L01F2`.)*

1. **`$`-Aware Class-Family & Package Matching Across ALL `.dex` Files:**
   - Replace broken `;`-terminated `startswith` with `cls == f"{stem};" or cls.startswith(f"{stem}$")` for explicit stems and `cls.startswith(pkg_prefix)` for cloud-only packages (`app/pilot/`, `app/session/`, `protocol/pilot/`, `ui/pilot/`), plus explicit concrete stems (`DefaultPilotContainer`, `PilotCapabilityBridgeImpl`, `PilotCapabilityRestrictedInventoryGateway`, `PilotReadOnlyWebSocketTransport`, `AndroidKeystoreAeadCipherProvider`, `OkHttp*`, `KeystoreAeadTokenStore`, `AuthorizedPilotEndpointTrustPolicy`, `ExternalPilotAuthorizationLoader`, etc.) for `local`, and `ReceiveOnlyTcpSource` / `LocalLanTrustPolicy` / `LocalDiagnosticSanitizer` for `offline`/`pilot`.
   - Line 370 rejects cross-variant substitution across `("app-debug.apk", "app-pilot.apk", "app-local.apk")`, tested by 7 positive/negative oracle categories (`$Companion`, `$1` lambda, secondary `classes2.dex`, unrelated similar names, 3-way substitution).
2. **Atomic `checks.json`, `checks.baseline.json`, & Post-Merge `vars.APPROVED_POLICY_SHA256` Sync:**
   - `L04A` adds 4 `local-*` checks (`15` total), updates `checks.baseline.json` (`e688b6...` -> `fd870ee6...`), promotes `traceability.json` provisional bindings, and activates under candidate-bound `TRANSITION_AUTHORIZATION` + single-parent squash-merge (`transition.py` lines `154-165`).
   - Immediately after `L04A` lands, maintainers MUST synchronize `vars.APPROVED_POLICY_SHA256` from `fd870ee6...` to the landed 15-check `canonical_policy_sha256` and clear `vars.TRANSITION_AUTHORIZATION` prior to `L04B` (preventing `transition.py` lines `271-287` silent fallback to 11-check `checks.baseline.json`).

---

## 4. `LocalLanTrustPolicy`, Receive-Only Boundary & Shared `SecretRedactor.kt` Preservation (`L04B`, `FIND-L01R2-05`)

- **Strict Canonical Endpoint Rule:** Dotted-decimal RFC1918 IPv4, last octet `1..254` (reject `.0` and `.255`), port `9999`, `InetAddress.getByAddress(fourBytes)` (zero DNS), `Socket(Proxy.NO_PROXY)`, post-connect peer check, explicit per-session operator confirmation, application-level notice, `0` outbound bytes (`ReceiveOnlyTcpSource`).
- **Untouched Shared `SecretRedactor.kt` & Construction-Time Local Privacy (`FIX-05`):**
  Shared `SecretRedactor.kt` and `SecretRedactorTest.kt` stay **completely untouched**. `LocalDiagnosticEvent` + `LocalDiagnosticLogger` (`app/local/`, cap `<= 200` entries x `<= 512` chars, monotonic `elapsedSessionMs`) plus `LocalDiagnosticSanitizer` (`src/local/java/com/weback/robot/security/`) exclude raw/formatted IPs, SSIDs, raw socket exception messages, arbitrary `Throwable`s, JWTs/credentials, binary payloads, and raster pixels by construction, verified by negative tests in `LocalPrivacyRedactionTest` (`TEST-LOCAL-517`).
- **Manual HIL Distinction:** `TEST-LOCAL-523` (`HIL-01`..`06`) targets physical `com.weback.robot.local` under human authorization (`NOT_RUN` in CI; distinct from offline `android_smoke_test.py`).
