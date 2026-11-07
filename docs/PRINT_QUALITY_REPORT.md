# PocketPrint Print Quality Engineering Report

## Diagnosis

No supplied print photographs or source documents were present in the workspace, so the physical
samples could not be measured directly. Static pipeline analysis isolated these software defects:

1. The production mono encoder converted every pixel below luminance 160 into solid black. It had no
   halftoning and did not alpha-composite partial transparency. Mid-gray graphics and shadows therefore
   became large black regions before Mode 9 compression.
2. Color Mode 10 is lossy in its blue delta representation. The encoder incorrectly used the original
   RGB row as the next seed instead of the row reconstructed by the printer. Vertical gradients could
   drift, band, or lose blue detail.
3. Color K extraction collapsed all neutral RGB values below 40 into full binary black. This caused a
   hard shadow discontinuity and could create black patches or registration halos.
4. Raster images ignored EXIF orientation, decoded at full source size, and treated one image pixel as
   one printer dot for actual-size printing even when source DPI metadata existed.
5. Text pagination collapsed whitespace and tabs. Direct Office text extraction could not preserve
   original layout and is no longer presented as document-fidelity printing.

Stride, `(width + 7) / 8` row sizing, MSB-first packing, zero padding, polarity (`1 = K ink`), and
Mode 9 lossless round trips were verified and were not the black-patch cause.

## Changes

- Added alpha-safe BT.709 luminance conversion.
- Added automatic page classification with three explicit 1-bit strategies:
  - text/table thresholding (default threshold 144);
  - deterministic Bayer ordered halftoning for mixed pages;
  - Floyd-Steinberg diffusion for photographs.
- Wired the tested rasterizer into the production PCL3GUI2 K Mode 9 encoder without changing framing,
  CRD, compression mode, DPI, page termination, or USB transport.
- Made color K extraction content-aware and limited it to true/near-black neutral pixels on photographic
  and mixed pages.
- Corrected Mode 10 seed tracking to use the printer-reconstructed row and fixed long literal decoding.
- Added sampled ARGB image decode, all-orientation EXIF transform, source-DPI actual sizing, white alpha
  composition, and filtered/dithered Canvas scaling.
- Replaced manual text wrapping with Android `StaticLayout` for Unicode line breaking, bidi, tabs, and
  clipping.
- Preview now renders at the verified printer DPI, applies the selected mono/grayscale processing, and
  stores the latest intermediate raster at `cache/quality_diagnostics/latest-final-raster.png`.
- Added raster dimension, DPI, stride, strategy, color-plane, and payload diagnostics.
- Expanded the diagnostic page with 6/8/10/12-point text, a financial table, one-pixel borders,
  a 21-step neutral ramp, continuous tones, checkerboards, skin references, shadow/highlight ranges,
  and deterministic photo-like continuous-tone content.

## Quality profiles

- `DRAFT`: verified 300 DPI, fast text thresholding at 132.
- `NORMAL`: verified 300 DPI, automatic text/mixed/photo classification at threshold 144.
- `PHOTO`: verified 300 DPI, Floyd-Steinberg monochrome processing and conservative K extraction.
- `HIGH`: experimental 600 DPI; hidden unless a 600-DPI PCL3GUI2 profile has been physically saved.
- `MAXIMUM`: experimental 1200-DPI protocol strip only; hidden from document printing until a
  bounded-memory striped page encoder is available.

The in-app Quality Benchmark prints the same deterministic source with each profile and color mode.
600/1200 DPI remain explicitly labeled experimental and are not claimed physically verified.

## Final production defaults

- Printing mode: `AUTO`
- Hybrid processing requested: `true`
- Quality: `NORMAL`
- Raster input: `300 DPI`

These values live in `PrintOptions` and `PrintPreferences`, not in individual encoders. User overrides
persist across restarts. AUTO records detected page content, confidence, concrete selected mode,
effective DPI, rendering/encoding time, whether hybrid was actually applied, and its fallback reason.
Mixed AUTO pages can use an experimental region-level hybrid inside the existing dual-plane scanline:
text/table tiles route to K and tonal tiles route to neutral RGB grayscale. It does not overlay separate
PCL jobs. It remains physically unverified, while non-mixed pages report a page-level fallback.

## Fidelity policy

PDF is rendered directly with Android `PdfRenderer` at final page dimensions. Office and RTF files are
not rebuilt as plain text for normal printing. They must be printed from a capable source app through
Android Print, giving PocketPrint a paginated PDF with the source app's fonts, tables, images, and page
geometry.

## Unchanged verified boundaries

- HP Ink Tank 310 PCL3GUI2 language and CRD structures.
- K Mode 9 and dual-plane RGB Mode 10 framing.
- 300 DPI verified printer profiles.
- UEL/PJL, form feed, ZLP, soft reset, and reliable USB transfer accounting.

## Physical status

Automated validation does not prove physical quality. The updated diagnostic pages still require A/B
printing on the HP Ink Tank 310. No claim is made that the physical samples have passed.

## Remaining limits

- Direct classic TIFF support is bounded and rejects unsupported orientation, tiling, fax compression,
  color spaces, and unsafe alpha declarations.
- Color management assumes decoded Android RGB/sRGB; custom ICC workflows are not implemented.
- A 600-DPI full A4 ARGB page exceeds the configured memory budget and is rejected rather than risking
  process termination. The verified 300-DPI printer profile remains the production default.
