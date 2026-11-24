# WeBack 6.1.7 Reverse Engineering Reference Baseline

**Document ID:** REF-RE-01  
**Status:** FROZEN REFERENCE BASELINE (READ-ONLY)  
**Accepted Date:** 2026-11-23  

---

## 1. Provenance and Cryptographic Verification

This document establishes the authoritative, verified technical reference baseline derived from the static reverse engineering of the original WeBack Android client application.

| Attribute | Verified Value |
| :--- | :--- |
| **Original Package Name** | `com.yugong.Backome` |
| **Target Version** | `6.1.7` (VersionCode `127`) |
| **Original APK SHA-256** | `b5668f88ca2cf1e7b86b9d1ea36a320d8ba18c0b8eb7f9c7960304296fa71bd3` |
| **Evidence Archive File** | `WEBack_6.1.7_FINAL_REVERSE_ENGINEERING_PACKAGE.zip` |
| **Evidence Archive SHA-256**| `fd34a682a834011637d322cc4529c3ca72d49c9d8a9661c7f6c0089fa7246fe4` |
| **Acceptance Decision** | `ACCEPTED_WITH_LIMITATIONS` (Sign-off: 2026-11-23) |
| **Archive Location** | `/home/quan/workspace/project/weback/artifacts/WEBack_6.1.7_FINAL_REVERSE_ENGINEERING_PACKAGE.zip` |

---

## 2. Finding Classifications & Precedence

### 2.1 Finding Counts
- **Total Historical Findings:** 131 findings recorded in registry `findings.jsonl`.
- **Active Grounded Findings:** 128 findings classified as `STATIC_CONFIRMED`.
- **Formally Superseded Findings:** 2 (`REG-01`, `REG-03`).
- **Formally Expunged Findings:** 1 (`REG-02`).

### 2.2 Evidence Precedence Rule
When resolving technical questions regarding the original application behavior, the order of precedence is strictly:
1. **Decompiled Dalvik Bytecode & Smali Instructions** (`decompiled/jadx/`, `decompiled/apktool/`)
2. **Exported ELF Headers, Dynamic Symbols & String Tables** (`lib/arm64-v8a/`)
3. **Decoded Android Resources & XML Manifests** (`AndroidManifest.xml`, `res/`)
4. **Machine-Readable Registry Records** (`final/findings.jsonl`, `final/protocols.json`)
5. **Narrative Reports and Engineering Summaries** (`final/*.md`, `reports/*.md`)

*Narrative summaries can never override direct bytecode evidence.*

---

## 3. Canonical Registry Index

The canonical reverse engineering registries remain frozen in read-only workspace `/home/quan/workspace/project/weback/final/`:

- **Master Architecture:** `final/01_MASTER_ARCHITECTURE.md`
- **Protocol Registry:** `final/02_PROTOCOL_REGISTRY.md` & `final/protocols.json`
- **Data Model Catalog:** `final/03_DATA_MODEL_CATALOG.md`
- **Feature Inventory & Call Chains:** `final/04_FEATURE_CATALOG.md` & `final/feature_inventory.json`
- **State Machine Specifications:** `final/05_STATE_MACHINES.md`
- **Native Integration Registry:** `final/06_NATIVE_INTEGRATION.md`
- **Security Assessment:** `final/07_SECURITY_FINDINGS.md`
- **Coverage & Limitations:** `final/10_COVERAGE_AND_LIMITATIONS.md`
- **Supersession Registry:** `final/supersession_registry.json`

---

## 4. Reconciled Discrepancies (Historical Errata & Grounded Findings)

The following discrepancies from early reverse-engineering phases were conclusively resolved through Dalvik bytecode analysis and must not recur:

1. **Shadow Wire Format Duality (`REG-01`)**:
   - Outbound real-time WebSocket robot control transmits a flat `state` object without a `desired` wrapper:
     `{"opt":"send_to_device","sub_type":"...","thing_name":"...","topic_name":"$aws/things/{thing}/shadow/update","topic_payload":{"state":{"working_status":"Clean"}}}`.
   - REST scheduled countdown timers wrap desired state in `thing_shadow.state.desired`.
2. **SoftAP Gateway IP Elimination & Port Assumptions (`REG-02`, `REG-05`)**:
   - Speculative claims regarding `192.168.200.1` and `192.168.10.1` have 0 occurrences across the entire DEX string pool and are **expunged**.
   - Speculative claims regarding HTTP communication on robot port 8000 have 0 bytecode evidence and are **expunged**.
   - Verified SoftAP endpoints serve distinct use cases:
     - `192.168.4.1`: Espressif Wi-Fi provisioning gateway.
     - `192.168.99.1`: Embedded router setup webview.
     - Dynamic local network discovery: Relies on local SSDP/DHCP broadcast discovery.
3. **BLE Fixed Slicing Capacity & Encoding Nuance (`REG-03`)**:
   - Speculative `MTU - 10` variable slicing formulas were refuted by bytecode in `bluetooth.c.E`.
   - The legacy slicing capacity is hardcoded to **86 characters** ($128 - 42$), derived from buffer constant `f41938j = 128` minus the 42-character sample envelope overhead. Negotiated MTU 500 is completely ignored during payload fragmentation.
   - **Wire Byte Length vs String Length:** The legacy code performs slicing via `String.substring` (UTF-16 code units / character count). New clean-room designs must explicitly account for the distinction between character count and UTF-8 encoded wire byte length to prevent buffer overruns on non-ASCII payloads.
4. **Binary Map Decompression: ZLIB vs Speculative GZIP (`REG-04`)**:
   - Binary SLAM map frames are compressed using standard **ZLIB** (deflate stream with ZLIB header) and decompressed via `java.util.zip.Inflater`.
   - Speculative claims of GZIP compression are refuted by bytecode and expunged.
5. **Distinct XMPP Routing vs WebSocket (No Dynamic Fallback)**:
   - XMPP routing (`Smack` client) is statically partitioned for legacy robot hardware categories based on device model classification.
   - There is **no automatic dynamic fallback** from WebSocket to XMPP at runtime; each robot communication channel is determined by device capability routing.

---

## 5. Known Limitations & External Boundaries

The following systems reside outside the observable client APK boundary and are classified as `UNKNOWN` or `OUT_OF_SCOPE`:

| ID | Domain | Classification | Rationale |
| :--- | :--- | :--- | :--- |
| `UNK-01` | ThroughTek WAN Relays | `UNKNOWN` | Runtime IP selection for TURN relay servers is dynamic. |
| `UNK-02` | ThroughTek NAT Traversal | `UNKNOWN` | Proprietary C UDP hole punching in `libIOTCAPIs.so`. |
| `UNK-03` | Camera ISP Sensor Config | `UNKNOWN` | Hardware sensor exposure tables managed by DSP. |
| `UNK-04` | Audio Echo Canceler Filter | `UNKNOWN` | Adaptive FIR convergence coefficients in `libSLCAec.so`. |
| `OOS-01` | Robot MCU Firmware | `OUT_OF_SCOPE` | Real-time PID motor controls on STM32/GD32 microcontroller. |
| `OOS-02` | Robot SLAM Lidar Engine | `OUT_OF_SCOPE` | Embedded Linux SLAM point-cloud calculation. |
| `OOS-03` | AWS Cloud Routing Backend | `OUT_OF_SCOPE` | AWS IoT Core SQL topic rules and Lambda microservices. |
| `OOS-04` | XMPP Server Topology | `OUT_OF_SCOPE` | Server-side clustering and offline queue management. |

---

## 6. Third-Party Libraries & Licensing Constraints

1. **ThroughTek Kalay P2P SDK (`libIOTCAPIs.so`, `libAVAPIs.so`, `libsCHL.so`):** Closed-source proprietary binary. Re-distribution or direct bundling in an open clean-room project is legally prohibited. Excluded from MVP.
2. **FFmpeg (`libtutk_ffmpeg.so`):** LGPL/GPL v2.1+. Excluded from MVP.
3. **OpenCV 3.x (`libopencv_java3.so`):** 3-clause BSD license.
4. **Smack XMPP Client:** Apache 2.0 license.
5. **RxAndroidBle:** Apache 2.0 license.

---

## 7. Operational Distinction & Requirements Integrity Notice

> [!IMPORTANT]
> 1. **Observed Behavior vs Approved Product Behavior:**
>    The findings in this baseline document record the **OBSERVED REVERSE-ENGINEERED BEHAVIOR** of legacy application `com.yugong.Backome`.
>    They **DO NOT** constitute automatically approved product requirements, nor do they imply that legacy security vulnerabilities, design flaws, or undocumented transport assumptions should be replicated in the new application.
> 2. **No Hardcoded Speculative Constants:**
>    Exact command constants (e.g. `working_status` strings, mode values, error codes) must not be hardcoded into generic application requirements without source-grounded, model-specific confirmation against the intended physical target device.
