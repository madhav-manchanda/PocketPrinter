# PocketPrint — Phase 1/2 Audit, Plan and Status

Concise audit of the printer-side Android application against the master
spec, plus the immediate implementation plan.

## 1. Repository audit (as of 2026-11-05)

### Stack

- Module: `:app` only (`settings.gradle.kts`).
- Kotlin 1.9.24, AGP 8.5.2, compileSdk 34, minSdk 26, targetSdk 34, JVM target 17.
- No Room / no WorkManager / no PDF/GUI rendering libraries — all decoding uses
  Android framework APIs (`android.graphics.pdf.PdfRenderer`, `BitmapFactory`).
- Single module. `backend/`, `web/`, `hp-apk-analysis/` are not part of the APK.

### Verified working components (do not regress)

| Area | File | Status |
|---|---|---|
| USB discovery | `data/usb/UsbDeviceManager.kt`, `UsbPermissionManager.kt`, `UsbDescriptorParser.kt`, `UsbConnectionManager.kt`, `PrinterDetectionManager.kt` | WORKING |
| Connection state | `data/usb/PrinterConnectionManager.kt` | WORKING |
| Chunked streaming engine | `data/print/transport/UsbStreamWriter.kt` (`UsbStreamEngine`) | WORKING (unit-verified) |
| USB print transport | `data/print/transport/UsbPrintTransport.kt` | WORKING (Phase 5) |
| PCL3GUI2 K-only (Mode 9) | `data/print/encoder/HpPcl3Gui2Encoder.kt` | VERIFIED ON HARDWARE |
| PCL3GUI2 dual-plane colour (K Mode 9 + RGB Mode 10) | `data/print/encoder/HpPcl3Gui2ColorEncoder.kt` | VERIFIED ON HARDWARE |
| Print job journal / recovery | `data/print/PrintJobJournal.kt` | WORKING (no auto-replay) |
| Status manager | `data/print/PrintStatusManager.kt` | WORKING |

### Multi-page pipeline status

- `PrintJobManager.printPdfDocument` now renders → encodes → transmits **one page
  at a time** inside a copy/page loop (uncommitted working-tree changes on top of
  `d4b0ecb "print reliable now"`). Multi-page is therefore **implemented in the
  working tree** but not yet covered by tests and not yet fully wired from the UI.

### Build / test baseline

- `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL** (4 test classes,
  all green) after `--rerun-tasks`.
- Compiler warnings only (unused parameters in legacy paths) — no build failures.

## 2. Feature classification

| Master-prompt area | State |
|---|---|
| USB discovery / permission / transport | WORKING |
| PCL3GUI2 mono + colour encoding | VERIFIED ON HARDWARE |
| Multi-page printing | IMPLEMENTED (working tree) / NOT regression-tested / NOT UI-wired |
| Page range parsing | PARTIALLY IMPLEMENTED — parser bug: invalid range falls back to "all pages" |
| Copies | IMPLEMENTED (sequential, not collated) |
| Colour modes | PARTIAL — `GRAYSCALE_8BIT` not wired into direct-print path |
| Paper sizes / orientation / scaling | MODEL ONLY — no UI, margins not implemented |
| Print preview | MISSING |
| Local print queue | MISSING (remote-only queue store exists) |
| Page/job state machine | COMPLETE (incl. page-adjacent states) |
| Printer-side UI (queue/preview/settings/diagnostics) | PARTIAL — main screen only |
| Local formats (TXT, TIFF, DOCX, …) | MISSING beyond PDF + PNG/JPG/BMP/WEBP |
| VPS integration | ARCHITECTURE ONLY (agent service + stores present) |
| Lifecycle / background | PARTIAL |
| Performance / memory | PARTIAL (per-page render OK; payload in-memory OK) |
| Tests | MINIMAL — streaming/compressor/regression only; missing page-range, options, job-manager tests |

## 3. Phase 1 + Phase 2 implementation plan (started)

1. **Phase 1 — Stabilization**
   - Keep verified byte stream untouched.
   - Add JVM unit tests: `PageRangeParserTest`, `PrintOptionsTest`.
   - Fix invalid/empty page-range handling to yield an empty selection (job then
     fails with a meaningful error instead of silently printing all pages).

2. **Phase 2 — Multi-page PDF printing**
   - Preserve per-page render → encode → transmit → finalize loop.
   - Add operator cancellation (`PrintJobManager.cancelJob()` → transport cancel).
   - Add per-page progress messaging and honest completion semantics (delivery
     complete ≠ physical output confirmed).
   - Wire explicit `PrintOptions` / MIME type from UI entry point.

## 4. Known limitations / remaining work

- Physical hardware validation pending (HP Ink Tank 310).
- Cold-start OTG behaviour deferred.
- No real-time physical print confirmation available; operator verification required.
- Grayscale direct-print encoder not yet integrated.
- No local (non-VPS) print queue or preview screen.
- Extended document formats not implemented.

## 6. Phase 4 progress (2026-11-05)

Implemented during the Phase 4 continuation:

1. Added `GrayscaleConverter` (`app/src/main/java/com/pocketprint/data/print/util/GrayscaleConverter.kt`).
   Pure BT.709 luminance conversion with alpha blending toward white; produces an
   opaque grayscale ARGB buffer. No Android rendering types required, so it is
   unit-testable in isolation.
2. Added `PrintEncoding` enum and `PrintEncodingResolver`
   (`app/src/main/java/com/pocketprint/data/print/model/PrintEncoding.kt` and
   `app/src/main/java/com/pocketprint/data/print/encoder/PrintEncodingResolver.kt`).
   Maps `PrintColorMode` plus the resolved candidate protocol to a concrete
   printer encoding pipeline.
3. Wired the document print path (`PrintJobManager.printPdfDocument`):
   - `MONOCHROME_1BIT` -> verified PCL3GUI2 K-only Mode 9 encoding (unchanged).
   - `GRAYSCALE_8BIT` -> new grayscale path: rendered bitmap converted via
     `GrayscaleConverter`, encoded through PCL3GUI Mode 0 with 8-bit grayscale.
   - `COLOR_RGB` -> verified dual-plane K Mode 9 + RGB Mode 10 encoding; degrades
     to monochrome with a diagnostic when the resolved candidate is not
     colour-capable.

## 5. Phase 1 + Phase 2 implementation log (2026-11-05)

Completed in this session:

1. Baseline re-confirmed: `./gradlew :app:testDebugUnitTest` - BUILD SUCCESSFUL
   (4 suites, 35 tests green) before changes.
2. `PageRangeParser` fixed: invalid / fully-out-of-range selections now return an
   empty list instead of silently falling back to the full page set.
3. `PrintJobManager` hardened:
   - Added operator cancellation (`cancelJob()` -> transport cancel + flag).
   - Cancellation observed between pages; job unwinds without sending more bytes.
   - Final completion message now distinguishes delivery-complete from physical
     output confirmation (operator verification pending).
4. New JVM regression tests added and green:
   - `PageRangeParserTest` (12 tests).
   - `PrintOptionsTest` (8 tests).

Verification (2026-11-05): `./gradlew :app:testDebugUnitTest` - BUILD SUCCESSFUL,
all 6 suites / 51 tests green, 0 failures.

Remaining limitations / next steps:

- Physical hardware validation still pending (HP Ink Tank 310).
- Grayscale direct-print encoder still not wired into the document print path.
- Print settings UI still absent (options only flow from remote jobs / defaults).
