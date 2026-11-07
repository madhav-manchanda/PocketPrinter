# PocketPrint Print Engine Architecture

## Ownership

Local documents are persisted in `LocalPrintQueueStore` before execution. `LocalPrintForegroundService`
is the owner of queued work; activities only enqueue jobs and observe state. The service uses a bounded
partial wake lock only while a document is active and exposes cancellation through its notification.
It remains foreground and `START_STICKY` while idle, monitors the queue and USB state, and is requested
again after `BOOT_COMPLETED`. Idle monitoring never holds the wake lock.

`UsbJobArbiter` serializes local, Android-system, and discovery ownership. `PrintJobManager` renders,
encodes, transmits, and finalizes one physical page at a time. Verified HP PCL3GUI2 mono and colour
encoder bytes are unchanged.

## Durable recovery

Queue states that were active when the process stopped become `NEEDS_RECOVERY`. They are never
automatically replayed. A failed or cancelled job is retryable only when no payload bytes and no prior
pages were confirmed. `RECOVERY_REQUIRED` is latched for the attached printer until detach/reattach.

## Document pipeline

- PDF: Android `PdfRenderer`, one page at a time.
- PNG/JPEG/BMP/WEBP: Android `BitmapFactory`, one page.
- TXT: wrapped and paginated with the selected paper and margins.
- TIFF: bounded classic TIFF decoder, one IFD/page at a time.
- RTF/DOCX/XLSX/PPTX: direct selection is blocked because text extraction cannot preserve layout.
  Users print from the source viewer through Android's print framework, which supplies PocketPrint a PDF.

The internal bounded Office extractors remain diagnostic utilities only and are not advertised as
layout-faithful document printing.

## Progress semantics

`PrintProgress` keeps per-payload bytes separate from monotonic overall scheduled-item progress. A
100% USB payload means transmission completed, not that a physical page was observed. Physical
verification remains an operator/hardware test.

## Production print defaults

`PrintPreferences` is the single persisted source for user defaults. A fresh install uses AUTO mode,
hybrid requested, NORMAL quality, and 300 DPI. Existing persisted explicit color selections are migrated
to their equivalent printing mode. AUTO classifies each rendered page. Mixed pages may use the
experimental dual-plane hybrid path: text/table tiles route to K while tonal tiles route to neutral RGB.
Diagnostics distinguish requested and applied hybrid processing; non-mixed pages record the fallback.

## Safety boundaries

- Positive USB returns alone advance the confirmed offset.
- Partial writes continue from the exact confirmed offset.
- Negative returns are ambiguous and are not replayed by default.
- A partial-page cancellation requires recovery.
- ZLP finalization is separate from payload accounting; a failed required ZLP is uncertain.
- No non-printer Bulk OUT fallback is used.

Android may still stop a service after force-stop, revoke execution privileges, or apply vendor battery
policy. PocketPrint cannot guarantee literal permanent execution; opening the app restarts monitoring.
