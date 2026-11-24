# INIT-001 — Discovery Corrections & Errata Resolution

**Document ID:** ERR-INIT-01  
**Target:** INIT-000 Discovery Report Reconciliation  
**Date:** 2026-11-24  
**Author:** Principal Android Architect & Verification Architect  

---

## 1. Executive Summary

During the review of the INIT-000 Discovery Report, seven specific technical assumptions and reporting inaccuracies were identified by the Product Owner and Technical Auditor. 

In accordance with Verification-First principles, this document formally reconciles each item against Dalvik bytecode evidence, binary headers, and filesystem inspection before any new specifications or implementations are authored.

---

## 2. Itemized Technical Resolutions

### Item 1: Map Decompression — ZLIB Confirmed; GZIP Claim Expunged
- **INIT-000 Flawed Assertion:** Claimed binary map packets arrive as "ZLIB/GZIP-compressed byte streams".
- **Source Bytecode Audit:**
  Inspection of decompiled class `com.yugong.Backome.utils.f1` reveals the exact decompression implementation:
  ```java
  public static byte[] c(InputStream inputStream) {
      InflaterInputStream inflaterInputStream = new InflaterInputStream(inputStream);
      // Reads raw uncompressed payload using java.util.zip.Inflater
  }
  ```
  Decompression strictly utilizes `java.util.zip.Inflater` / `InflaterInputStream`, which implements the **ZLIB format (RFC 1950)**. There is zero usage of `GZIPInputStream` (RFC 1952) anywhere in the map parsing pipeline (`com.yg.mapfactory.h` or `utils.f1`).
- **Correction:** The canonical protocol specification for `PROT-MAP-01` is strictly restricted to **ZLIB**. All references to GZIP decompression are expunged.

---

### Item 2: XMPP Signaling — Distinct Transport, Not Automatic Fallback
- **INIT-000 Flawed Assertion:** Described Smack XMPP signaling as an "automatic fallback for legacy robots when WebSocket drops".
- **Source Bytecode Audit:**
  Inspection of `com.yugong.Backome.fragment.f` (lines 708–720) and `MainActivity.java` shows:
  ```java
  if (this.mXmppFacade != null && com.yugong.Backome.utils.c.X0(robotInfo.getContact()) && !robotInfo.isSimpleWifi()) {
      this.mXmppFacade.a(msg);
  } else if (...) {
      // WebSocket send_to_device
  }
  ```
  XMPP is invoked exclusively based on device identity metadata (`robotInfo.getContact()` is a valid JID, and device is not flagged as `isSimpleWifi()`). There is no dynamic catch-and-retry logic that redirects failed WebSocket frames to XMPP stanzas.
- **Correction:** XMPP (`PROT-XMPP-01`) is classified as a **distinct transport architecture for legacy devices**, not an automatic runtime fallback.

---

### Item 3: BLE GATT Chunking — Three-Tier Capacity Disambiguation
- **INIT-000 Flawed Assertion:** Conflated Java string slicing, Base64 character counts, and Bluetooth physical packet capacity.
- **Source Bytecode Audit:**
  Bytecode in `com.yugong.Backome.bluetooth.c` lines 430–448 reveals a multi-layered encapsulation:
  1. **Inner Payload:** Robot configuration JSON is converted to bytes and Base64-encoded (`String encode`).
  2. **Java String Slicing:** Method `c.E()` calculates slice length: `this.f41938j - q.a().toJson(hashMap).length()`. `f41938j` is hardcoded to $128$. An empty envelope sample produces a length of $42$ characters. Thus, `slice_size = 128 - 42 = 86` characters.
  3. **Outer Envelope:** The 86-character Base64 chunk is placed in JSON `{"data":"<86 chars>","type":"...","offset":...}`.
  4. **Wire Transmission:** The entire outer JSON is Base64-encoded *a second time* before being passed to `writeCharacteristic()`.
  5. **Physical ATT/GATT MTU:** The app requests MTU 500 via `c.B(str, 500)`. However, the slicing logic in `c.E()` ignores the negotiated MTU and slices strictly against the hardcoded constant `f41938j = 128`.
- **Correction:** Specifications must explicitly distinguish:
  - Java string slice size = **86 UTF-16 characters**.
  - Outer envelope JSON = **~120-130 characters**.
  - Final Base64 wire payload = **~170-180 bytes**.
  - Physical GATT characteristic payload = fits comfortably within MTU 500 but does not dynamically scale with MTU.

---

### Item 4: SoftAP Network Topology — Separation of 3 Distinct Use Cases
- **INIT-000 Flawed Assertion:** Blended various SoftAP gateway IPs into a single undifferentiated provisioning flow.
- **Source Bytecode Audit:**
  Decompiled sources demonstrate three separate, decoupled networking mechanisms:
  1. **Wi-Fi Credentials Provisioning Gateway:** Handled by `com.yugong.Backome.utils.net.g`. Connects to robot AP (`http://192.168.4.1/config?command=setwifi`) and sends `Connect_Station` JSON payload.
  2. **Router Setup In-App WebView:** Handled by `com.yugong.Backome.activity.WifiConfigActivity:34`. Loads `http://192.168.99.1` in an Android `WebView` to configure third-party router hardware.
  3. **Dynamic Device Discovery:** Handled by `com.yugong.Backome.activity.config.f:411`. Resolves the local gateway IP dynamically via DHCP lease / SSDP multicast when connected to non-standard robot AP subnets.
- **Correction:** The new architecture treats these as three independent components: Provisioning Client (`192.168.4.1`), Discovery Client (Dynamic DHCP), and Router WebView (Out of Scope for MVP).

---

### Item 5: Legacy HTTP Port 8000 — Refutation of Unsupported Claim
- **INIT-000 Flawed Assertion:** Claimed a "Local Direct HTTP" transport existed using "direct HTTP POST to robot port 8000".
- **Source Bytecode Audit:**
  A comprehensive search across the 16,296 classes in the decompiled DEX bytecode revealed **zero** occurrences of a TCP/HTTP socket or URL targeting port `8000`. Occurrences of `8000` were solely:
  - Hex masks (`0xffffffff80000000`) in Kotlin Coroutines.
  - Standard Bluetooth Base UUID strings (`-0000-1000-8000-00805f9b34fb`).
  - Android compiled resource IDs (`tab_q8_sp8000 = 2131232243`, `timer_txt_btn_left = 2131298000`).
- **Correction:** The claim of a local HTTP port 8000 control interface was an unsupported assumption inherited from unverified narrative notes. It is **formally rejected and expunged** from all canonical specifications.

---

### Item 6: Native Shared Libraries — ELF Inventory vs Behavioral Coverage
- **INIT-000 Flawed Assertion:** Implied that 100% native library inventory represented complete behavioral coverage.
- **Source Binary Audit:**
  All 12 `arm64-v8a` shared objects were verified for cryptographic hash, ELF dynamic header, SONAME, and JNI function export symbols. However:
  - Compiled C/C++ algorithms inside `libIOTCAPIs.so` (NAT hole punching state machine, STUN/TURN heuristics) and `libsCHL.so` (DTLS session keys) cannot be fully reconstructed or proven from static analysis alone.
- **Correction:** In accordance with the acceptance decision `ACCEPTED_WITH_LIMITATIONS`, native binary coverage is strictly defined as **100% Interface & Static Symbol Mapping**, while internal native runtime heuristics are classified as `UNKNOWN`.

---

### Item 7: Workspace Repository Status — Non-Git Classification
- **INIT-000 Flawed Assertion:** Classified the reverse-engineering workspace as a "clean Git checkout".
- **Filesystem Audit:**
  Execution of `git status` inside `/home/quan/workspace/project/weback` produces `fatal: not a git repository`.
- **Correction:** The reverse-engineering directory `/home/quan/workspace/project/weback` is an unversioned filesystem archive. The new Android repository `/home/quan/workspace/project/weback-android` is now properly initialized as a genuine Git repository with tracked revisions.
