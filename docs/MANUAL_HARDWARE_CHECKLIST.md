# PocketPrint — HP Ink Tank 310 Manual Hardware Validation Checklist

Physical validation checklist for the HP Ink Tank 310 series (USB VID `0x03F0`, PID `0x1053`).

> **Never claim a feature works without physical validation.** A successful build or
> emulator/unit test does not prove physical output.

## 1. Pre-print setup

- [ ] OTG adapter connected, printer powered on, phone charged.
- [ ] Android USB permission granted to PocketPrint.
- [ ] Interface claimed; Bulk OUT `0x08` / Bulk IN `0x89` endpoints selected.
- [ ] Verified profile loaded (PCL3GUI2 K-Mode 9 for mono, dual-plane for colour).

## 2. Monochrome test page

- [ ] Diagnostic test page prints, correct paper fed, frame/text present.
- [ ] Line fidelity / ramp acceptable.

## 3. Colour test page

- [ ] Dual-plane colour diagnostic page prints; colour blocks registered correctly.
- [ ] Grayscale document print (luminance conversion path) - pending hardware validation.

## 4. Multi-page document (e.g. 25-page PDF)

- [ ] Page count detected correctly (25).
- [ ] Pages printed sequentially in order.
- [ ] Selected range (`1-3,5,8-10`) honoured.
- [ ] Copies behave as expected (sequential pacing).

## 5. Failure / recovery

- [ ] Transient bulk stall - retry behaviour (bounded, no silent duplicate replay).
- [ ] Ambiguous failure - job marked `NEEDS_RECOVERY`, no auto-replay.

## 6. Cold start (deferred)

- [ ] OTG cable attached while printer off/on - known issue, deferred.
