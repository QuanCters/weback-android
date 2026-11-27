# LOC-001 HOSLAM Empirical Wire Contract, Evidence Register & Unknown Register

- **Change ID:** `LOC-001`
- **Revision:** `1.2.0 (Stage L01F2 Remediated & Frozen)`
- **Risk Tier:** `T4 (Critical Risk)`
- **References:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md)

---

## 1. Preserved Evidence Register (`E-001` .. `E-007`)

| ID | Artifact / Source | Verified / Reported Scope & Status |
|---|---|---|
| **`E-001`** | Owner Stage 0 audit notes (2026-11-27) | `REPORTED` (reconciled in Stage `L01`/`L01F`/`L01F2` against canonical `origin/main` `184175a14e5eb6659396c95624e2d3b08d1078a1`; earlier test counts reflected rejected `51bdd70`, and Stage 0 ZLIB claim for HOSLAM TCP corrected). |
| **`E-002`** | `hoslmap_20261127_075438.bin` (private capture outside repo), SHA-256 `f31a89d74346ec7f9d5e74970d454cd69c3a66759dff907d6150cfaeeb41358a` | `REPRODUCED_CAPTURE` in handoff pack: `4,602,152` bytes, `230` frames (`77` map, `77` aux, `76` trajectory), `28` `278x213` + `49` `278x214`, `73` unique raster SHA-256 digests (`68b8eded...` first, `cdd52f82...` final), first checksum `9,293,083`, last checksum `9,343,415`. **Stage L01 / L01F / L01F2 local re-run: `NOT_RUN`** (private file not supplied to this session; never copied into Git). |
| **`E-003`** | `robot_2.pcap` (private capture outside repo), SHA-256 `5a4fbda8a557a0d2de7c05239073aab806d2074f0c06f5596e0153773677942d` | `REPORTED` handshake + robot payload visibility; incomplete session visibility; not re-analyzed in Stage L01/L01F/L01F2. |
| **`E-004`** | `rapido_hoslmap_decode.py`, SHA-256 `b6073016895d5b7ca45efc9e66f2263a136848bda8275fa9a86e9f199917944c` | Prior diagnostic reference implementation only (`NOT` a production parser or unquestionable oracle; cap exceeds Android 2 MiB profile). |
| **`E-005`** | Prior WeBack 6.1.7 JADX inspection (`enums/p.java`, `LaserVisualActivity`, `ywvisual/k`, `utils/c`, `websocket`) | `STATIC_REPORTED`: UI/cloud schema context; does not establish local TCP 9999 command protocol or stock firmware equivalence. |
| **`E-006`** | Prior DNS/REST diagnostics (`elapp.grit-cloud.cn` NXDOMAIN/CNAME, 0086 OAuth, Zigma Free research notes) | `REPORTED`: historical research leads only; not proof of current cloud uptime or Zigma Free hardware compatibility. |
| **`E-007`** | Synthetic HOSLAM test vectors (`valid_mixed.bin` + 6 negative vectors + `manifest.json` + `verify_capture.py`) | **`SYNTHETIC_REPRODUCED_IN_L01_L01F_AND_L01F2`**: zero household pixels; all 7 binary SHA-256 digests verified (`valid_mixed.bin` `631aa7fa...` passes `verify_capture.py` with `4` maps / `1` aux / `1` trajectory, pads `0..3`; all 6 negative vectors fail with exit `1`). |

---

## 2. Empirical HOSLAM TCP Wire Layout & Epistemic Separation (`P-001` .. `P-010`, Solving `FIND-L01R2-06`)

Evidence and implementation rules below are strictly separated into three epistemic categories so unverified vendor hypotheses cannot be mistaken for capture-proven firmware behavior:

### Category A — Capture-Observed Framing & Payload Structure (`E-002`, `VERIFIED_CAPTURE`)
Observed empirically on Rapido R8S capture `hoslmap_20261127_075438.bin` (`E-002`); **not** a complete vendor specification or control protocol:

| Offset | Bytes | Observed Field / Layout | Observed Invariant (`E-002`) |
|---:|---:|---|---|
| `0` | `8` | Magic prefix | `68 6f 73 6c 6d 61 70 00` (`hoslmap\0`), `68 6f 73 6c 63 61 63 00` (`hoslcac\0`), `68 6f 73 6c 74 72 61 6a` (`hosltraj`) |
| `8` | `4` | Total frame length `L` (u32 LE) | Length of complete frame including 8-byte magic, 4-byte `L`, body, and 4-byte checksum |
| `12..L-5` | `L-16` | Type-specific body | `hoslmap\0` (`L >= 40`): `12..27` 16 opaque bytes, `28..31` `W` (u32 LE), `32..35` `H` (u32 LE), `36..36+W*H-1` uncompressed raw u8 grayscale (`ZERO ZLIB`), `0..3` zero padding bytes to 4-byte boundary (`aligned = (36 + W * H + 3) & ~3`, `L == aligned + 4`). `hoslcac\0` (`L == 20`): `12..15` 4 opaque bytes. `hosltraj` (`76` frames in `E-002`): `(L - 16) % 8 == 0` with `1..144` 8-byte slots observed. |
| `L-4` | `4` | Checksum (u32 LE) | `(sum(frame[0 .. L-5].toLong() and 0xFFL)) mod 2^32` |

### Category B — Defensive App Implementation Constraints (`L02A` / `L02B`, NOT Vendor Protocol Proof)
Engineering bounds enforced by Android `HoslamStreamFramer` (`L02A`) and `HoslamFrameDecoder` (`L02B`) to protect memory and fail closed without parsing unverified fields:
1. **Unsigned Length Bounds (`L02A`):** Decode u32 LE via per-byte `(b.toLong() and 0xFFL)` and enforce `16L <= L <= 2_097_152L` (`2 MiB` app cap) before `.toInt()` or buffer allocation.
2. **Raster Bounds (`L02B`):** Enforce `W` and `H` in `1..1024`, `W * H <= 1_048_576`, zero padding `[36 + W * H .. aligned - 1] == 0x00`, and `L == aligned + 4`. Render top-left unsigned grayscale `RGBA(v, v, v, 255)` with zero ZLIB/`Inflater`.
3. **Opaque Trajectory Byte-Length Rule (`L02B`, `FIND-L01R2-06`):** `HoslamFrameDecoder` checks purely on raw octet length `L >= 16 && (L - 16) % 8 == 0` (`slotCount = (L - 16) / 8`), keeping `frame[12 .. L-5]` strictly opaque with **zero IEEE-754 float parsing (`Float.fromBits`), zero coordinate arithmetic, and zero assumption that slots are float32**. Accepting an empty `16`-byte structurally valid trajectory frame (`slotCount == 0`, not observed in `E-002`) is an **explicit defensive codec choice** so an empty frame is not rejected solely due to sample absence; it is **not** evidence that firmware emits `0`-slot frames or float32 values. Arbitrary 8-byte payloads (including non-finite IEEE-754 bit patterns if viewed as floats) cannot crash `L02B` solely because zero float decoding occurs.

### Category C — Unverified Vendor Semantics (`U03`–`U05`, `U08`–`U11`, Unresolved Research)
1. Metadata bytes `12..27` in `hoslmap\0` and 4 bytes `12..15` in `hoslcac\0` remain completely unverified (`U03`, `U05`).
2. `hosltraj` 8-byte slot internal encoding (**research hypothesis: IEEE-754 float32 LE coordinate pairs vs fixed-point/int32/IDs**), firmware validity of `N=0` or non-finite values, metric scale, origin, robot position, and heading remain **UNRESOLVED (`U04`)** until physical evidence closes `U04` in `L07+`.

### Synthetic Fixture Verification Digests (`E-007`)
- `valid_mixed.bin`: `220` bytes, SHA-256 `631aa7fabc331da5b9245a193a4f00c0a242096e487db96dfca2707835a46fe5` -> `PASS` (`4` maps: `2x2` `007f80ff` sha `89273d2f...`, `3x1` `ff0080` sha `ef192b7a...`, `2x1` `807f` sha `e65aceb8...`, `1x1` `ff` sha `a8100ae6...`; `1` aux; `1` trajectory; pad lengths `0,1,2,3`; checksums `1424`, `943`, `1342`, `1297`, `1168`, `1167`).
- Negative fixtures (all fail with exit `1` in `verify_capture.py`):
  - `bad_checksum.bin` (`44` B, `47c0c4c613ddf1ac444c8fee89234fcec7d6236efd95c64c60cd7e3a791f5c82`) -> `checksum mismatch` (Kotlin oracle: `InvalidChecksum(0L)`)
  - `bad_padding.bin` (`44` B, `197a8fb2ccc787dfa6ae9018cb060ab399ecab8c277c4bc507384dd8eb6d74d7`) -> `layout/padding` (Kotlin oracle: `InvalidPadding(0L)`)
  - `truncated.bin` (`43` B, `73bfe975102a62cac5e14fb2012e0176d36de4d08560bf3923760faa8e701743`) -> `truncated body` (Kotlin oracle: `TruncatedFrame(0L, 43)`)
  - `oversized_header.bin` (`12` B, `9a27de4e74dca8188e505429a0abaf0e67bf8e46a5c8f91ce6de88ea3c4b9890`) -> `invalid length` (Kotlin oracle: `FrameTooLarge(0L, 2_097_153L)`)
  - `unknown_magic.bin` (`16` B, `afa798ad53b0e767064ac78da97455039e5f98db0c2777eb518c7bb4e95e6431`) -> `invalid magic` (Kotlin oracle: `InvalidMagic(0L)`)
  - `zero_width.bin` (`40` B, `40996e4c94c3a2d4f25cbdb24bc0f3b677b8bd5cee666329d602b5e749dc842c`) -> `dimensions` (Kotlin oracle: `InvalidDimensions(0L, 0L, 1L)`)

---

## 3. Unknown Register & Research Backlog (`U01` .. `U12`)

| ID | Unknown Domain | Non-Destructive Closure Experiment | Blocked Capability |
|---|---|---|---|
| **`U01`** | Exact firmware, chipset, board revision | Owner label + vendor docs + legitimate diagnostics | Firmware profile / install |
| **`U02`** | TCP stream reliability & WSL failure cause | Matched Windows/Android receive-only captures (source port/state); no NAT guessing | Reliability SLA claim |
| **`U03`** | Physical scale, origin, orientation, metadata `12..27` | Compare known physical landmarks vs repeated offline maps | Metric map / pose |
| **`U04`** | Trajectory 8-byte slot encoding (float32 hypothesis vs fixed-point), `N=0` / non-finite validity, & transform | Timestamped physical observations + held-out run fit | `LF-010` trajectory UI |
| **`U05`** | `hoslcac\0` 4-byte payload meaning | Correlate against independently recorded physical states | Heading / calibration |
| **`U06`** | Grayscale pixel value -> occupancy class meanings | Compare known free/obstacle/unknown regions with independent ground truth | Semantic room/obstacle view |
| **`U07`** | Local battery / suction / water / fault telemetry source | Identify documented or observed read-only source; zero invented fields | `LF-011` local status/settings |
| **`U08`** | Local control transport & command semantics | Vendor docs / maintainer collaboration / authorized traffic analysis; safety review before active test; zero guessed writes to `9999` | `LF-012` / `LF-013` physical control |
| **`U09`** | Zigma Free hardware/binary compatibility | Legitimate owner hardware/ABI verification + verified backup/restore + separate approval | `LF-015` local agent install |
| **`U10`** | WAN-independent operation | `HIL-06` device-scoped WAN isolation with preserved LAN & documented restore | `LF-014` offline-without-internet |
| **`U11`** | LAN endpoint authentication / identity | Investigate documented secure device protocols; current stream unauthenticated | Trusted command channel |
| **`U12`** | Canonical policy & build topology reconciliation | `L01` baseline audit (`DONE` in `L01`; `L04A` migration inventory, source-set plan, DEX rules & CI activation documented in `L01F2`; G0–G2 human decision pending) | `L02A`+ / `L04A` T4 |
