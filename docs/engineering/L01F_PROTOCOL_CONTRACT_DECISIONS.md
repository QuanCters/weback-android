# L01F Protocol, Framer & Lifecycle Contract Decisions (`LOC-001`)

This document records the authoritative deterministic codec, framer, and lifecycle decisions established under Stage `L01F` (and updated in Stage `L01F2` rev `1.2.0` for `FIND-L01R2-04` and `FIND-L01R2-06`) for `Task 2` (`L02A`), `Task 3` (`L02B`), `Task 4` (`L03A`), and `Task 6` (`L04B`).

---

## 1. Explicit Unsigned Wire Math & Allocation Bounds (`L02A` / `L02B`)

All 32-bit little-endian wire integers (`declaredLength`, `checksum`, `width`, `height`) decode strictly through `Long` with an explicit `0xFFL` mask on every byte before shifting:

```kotlin
internal fun readU32Le(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
```

- **Pre-Allocation Length Gate (`L02A`):**
  - `declaredLength < 16L` (`0L..15L`) -> `LocalCodecError.InvalidLength(frameStartOffset, declaredLength)`.
  - `declaredLength > 2_097_152L` -> **`LocalCodecError.FrameTooLarge(frameStartOffset, declaredLength)`**.
  - Specifically, high-bit u32 values such as **`0x80000010`** (`2_147_483_664L`) and **`0xFFFFFFFF`** (`4_294_967_295L`) MUST decode positively via `readU32Le` and emit **`FrameTooLarge`**, never negative `Int` `InvalidLength`.
  - `.toInt()` casting and frame byte array allocation (`ByteArray(declaredLength.toInt())`) execute strictly **after** `declaredLength in 16L..2_097_152L` succeeds.
- **Modular Checksum (`L02A`):**
  - `computed = sum(frame[0 .. declaredLength - 5].toLong() and 0xFFL) and 0xFFFF_FFFFL`.
  - Expected trailing u32 LE at `declaredLength.toInt() - 4`. Any mismatch emits `LocalCodecError.InvalidChecksum(frameStartOffset)`.
- **Raster Decode Without ZLIB (`L02B`):**
  - **Zero** `java.util.zip.Inflater`, ZLIB, or LZ4 calls on HOSLAM TCP (`ADR-L004`).
  - Raw unsigned 8-bit grayscale at offset `36 .. 36 + W * H - 1`: `v = raw[offset].toInt() and 0xFF`, displayed top-left origin as `RGBA = (v, v, v, 255)` with zero flipping/transposition/metric inference. Metadata `[12..27]` (`metadata[0..3]`) remains opaque.

---

## 2. Public `HoslamStreamFramer` Contract (`L02A`, Preserved Intact Under `L01F2`)

- **Buffer & Memory Cap:** `HEADER_BYTES = 12`, `MIN_FRAME_BYTES = 16L`, `MAX_FRAME_BYTES = 2_097_152L` (`2 MiB`). Framer retains at most one `12`-byte staging header buffer and one active frame array (`<= 2_097_152` bytes) between `feed()` calls.
- **Chunk Ownership:** `fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size): HoslamFeedResult` checks `(offset >= 0 && length >= 0 && offset + length <= chunk.size)`, copies needed bytes synchronously into internal staging/frame storage, and never retains a reference to `chunk`. Caller mutation of `chunk` after `feed()` cannot affect parsed frames.
- **Ordered Delivery & Terminal Error Isolation:**
  - `HoslamFeedResult.Frames(val frames: List<RawHoslamFrame>)`: validated complete frames parsed in input order.
  - `HoslamFeedResult.Failed(val validFramesBeforeFailure: List<RawHoslamFrame>, val error: LocalCodecError)`: emits any complete validated frames completed before the failing frame in the same chunk, records `terminalError = error`, drops unvalidated/partial bytes, and ensures all subsequent `feed()` or `finish()` calls emit `emptyList()` + `terminalError` until `reset()`.
- **EOF (`finish()`) & Session Reset (`reset()`):**
  - `finish()` at an exact frame boundary returns **`HoslamFinishResult.EndOfStream(framesDelivered, bytesConsumed)`** — explicitly **not** a `LocalCodecError` (0-byte stream returns `EndOfStream(0L, 0L)`).
  - `finish()` with `1..11` header bytes or incomplete body bytes emits `HoslamFinishResult.Failed(LocalCodecError.TruncatedFrame(frameStartOffset, bytesReadInFrame))` and enters terminal failure state.
  - `reset()` clears buffers and resets `bytesConsumed`, `currentFrameStartOffset`, `framesDelivered`, and `terminalError = null`.
- **Diagnostics Offset & Responsibility Boundary:** Every `LocalCodecError` carries `frameStartOffset: Long` as the 0-based session-relative byte offset where the failing frame header began. Framer has zero socket operations, zero deadlines, and zero UI/replay acceptance rules.

---

## 3. Deterministic Multi-Fault Precedence, Opaque Trajectory & Defensive Buffers (`L02A` / `L02B`)

1. **`L02A` Envelope Precedence (`HoslamStreamFramer`):**
   `12`-byte header available (else EOF -> `TruncatedFrame`) -> `InvalidMagic` -> `InvalidLength` (`< 16L`) or `FrameTooLarge` (`> 2_097_152L`) -> body complete (else EOF -> `TruncatedFrame`) -> `InvalidChecksum`.
2. **`L02B` Type Layout Precedence (`HoslamFrameDecoder`, executed ONLY on valid `RawHoslamFrame`):**
   - `MAP` (`hoslmap\0`): `totalLength < 40` -> `UnsupportedLayout(..., "MAP_LENGTH_BELOW_40")` -> `width`/`height` outside `1L..1024L` or `width * height > 1_048_576L` -> `InvalidDimensions` -> `totalLength.toLong() != aligned + 4L` (where `aligned = (36L + width * height + 3L) and 3L.inv()`) -> `UnsupportedLayout(..., "MAP_LENGTH_LAYOUT_MISMATCH")` -> any padding byte `[36 + W * H .. aligned - 1]` `!= 0x00` -> `InvalidPadding`.
   - `AUX` (`hoslcac\0`): `totalLength != 20` -> `UnsupportedLayout(..., "AUX_LENGTH_NOT_20")`.
   - `TRAJECTORY` (`hosltraj`): pure octet-length validation `totalLength < 16 || (totalLength - 16) % 8 != 0` -> `UnsupportedLayout(..., "TRAJ_LENGTH_NOT_PAIR_ALIGNED")`. Payload slots `frame[12 .. totalLength - 5]` remain strictly **opaque raw octets** with zero IEEE-754 float parsing or coordinate arithmetic; permitting `totalLength == 16` (`0` slots) is an explicit defensive codec rule, while IEEE-754 float32 hypothesis and `N=0` firmware validity remain unresolved under `U04` (`FIND-L01R2-06`).
3. **Defensive Domain Value Ownership (`FIND-L01R-10`):**
   - `RawHoslamFrame`: copies input array on construction (`rawFrameBytes.copyOf()`), exposes `copyBytes(): ByteArray`, `bodyByteAt(offset: Int): Int`, content `equals`/`hashCode`, and redacted `toString()`.
   - `RasterSnapshot`: copies pixel buffer via `copyOf()` on construction and `copyPixels()`, exposes `pixelAt(x, y): Int`, overrides `equals`/`hashCode` via `contentEquals`/`contentHashCode`, and overrides `toString()` to emit only `width`, `height`, `frameGeneration`, and `rasterSha256`.
4. **Isolated Mutation Protocol (`TEST-LOCAL-511`, `L02B`):** Executed in a temporary workspace (`tempfile.TemporaryDirectory`) sequentially injecting 11 single-fault mutants into production codec/repository code, verifying every mutant fails RED, and deleting the temp directory.

---

## 4. Canonical Lifecycle FSM, Deadlines, Races & Map Retention (`L03A` / `L04B`, `FIND-L01R2-04`)

Authoritative normative state table and synchronized `stateDiagram-v2` live in [specs/changes/LOC-001/design.md](../../specs/changes/LOC-001/design.md) §3:
- **States:** `Idle`, `Connecting`, `WaitingForMap`, `Streaming`, `Stale`, `Disconnected(reason)`, `Error(error)`.
- **Uniform Active & Terminal Exit Rules:**
  - `UserStop` / `TargetChange` / `ScreenExit` (from any state) -> **`Idle`** (`socket.close() <= 1_000 ms`, clear map `null`).
  - `Background` (`ON_STOP` when `!isChangingConfigurations` / `ProcessLifecycleOwner`, from any active or terminal state) -> **`Disconnected(BACKGROUNDED)`** (`socket.close() <= 1_000 ms`, clear map `null`, explicit `"Disconnected (Backgrounded — Manual Reconnect Required)"` label).
  - `Rotation / ConfigChange` (from any active or terminal live state) -> **`Disconnected(CONFIG_CHANGE_RECONNECT_REQUIRED)`** (`socket.close() <= 1_000 ms`, clear map `null`, explicit `"Disconnected (Configuration Changed — Manual Reconnect Required)"` label, zero auto-reconnect).
  - `WaitingForMap` clean EOF -> **`Disconnected(EOF_BEFORE_FIRST_MAP)`** (`null` map; fails view acceptance); `Streaming`/`Stale` clean EOF -> **`Disconnected(REMOTE_EOF)`** (retained map labeled **`DISCONNECTED / STALE`**, never live).
  - `180_000 ms` session cap (from `WaitingForMap`, `Streaming`, or `Stale`) -> **`Disconnected(SESSION_LIMIT_REACHED)`** (clean close, not codec corruption; retains map ONLY if `>= 1` valid map received, labeled **`DISCONNECTED / STALE (180s session limit)`**).
  - `TrustReject` / `ConnectTimeout(5s)` / `FirstMap(30s)` / `InFrame(15s)` / `CodecErr` / `IOException` -> **`Error(typedCode)`** (retains map ONLY if `>= 1` valid map received, labeled **`ERROR / STALE`**, never live).
  - `Disconnected(*)` / `Error(*)` + `UserStart / ManualUserReconnect(validEndpoint)` -> **`Connecting`** (new `activeGenerationId`, clears previous retained map).
- **Monotonic Budgets (`elapsedRealtimeMs()`):** Connect `5_000 ms`, first map `30_000 ms`, staleness `10_000 ms` (not reset by `aux`/`traj`), absolute in-frame deadline `15_000 ms` from 1st frame byte (never extended by partial trickle), max session `180_000 ms`, close SLA `<= 1_000 ms`. Serialized `activeGenerationId` mutex arbitrates races (`Error` wins if already committed ahead of `SESSION_LIMIT_REACHED`, otherwise `SESSION_LIMIT_REACHED` wins cleanly).
