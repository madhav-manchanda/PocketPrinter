# PocketPrint — Phase 2: Direct USB Printing Engine Implementation & Report

**Target Printer:** HP Ink Tank 310 series (VID: `0x03F0`, PID: `0x1053`)  
**Host Controller:** Android USB Host API (`android.hardware.usb.*`) on Samsung SM-G990E (Android 16 / API 36)  
**Printing Interface:** Interface #1 (Class `0x07` Printer, Subclass `0x01`, Protocol `0x02` Bi-directional)  
**Endpoints Utilized:** Bulk OUT `0x08` (MaxPacket 512 bytes), Bulk IN `0x89` (MaxPacket 512 bytes)  
**PDL / Command Set:** HP PCL3GUI wrapped in PJL (Printer Job Language)  

---

## 1. Technical Protocol Architecture

The HP Ink Tank 310 series is a host-rasterized inkjet peripheral. It does not parse high-level PostScript or standard PCL6 vectors; instead, it expects a compressed 1-bit raster binary stream formatted according to HP PCL3GUI specifications.

### Complete Stream Format Emitted by PocketPrint:
1. **Universal Exit Language & PJL Wrapper:**
   ```text
   \x1b%-12345X@PJL\r\n
   @PJL SET STRINGCODESET = UTF8\r\n
   @PJL JOB NAME = "PocketPrint Document"\r\n
   @PJL ENTER LANGUAGE = PCL3GUI\r\n
   ```
2. **PCL Initialization & Media Geometry:**
   - `\x1bE` — PCL software reset
   - `\x1b&l26A` — Media size selection: A4 (210 x 297 mm)
   - `\x1b&l0O` — Portrait orientation
   - `\x1b&l0E` — Top margin 0
   - `\x1b&l1X` — Single copy
   - `\x1b*t300R` — Resolution: 300 DPI
   - `\x1b*o0M` — Print quality: Normal
3. **Raster Graphics Mode:**
   - `\x1b*r0F` — Portrait raster presentation
   - `\x1b*r1A` — Start raster graphics at left graphics margin
   - `\x1b*b0M` — Compression mode 0 (uncompressed binary rows)
4. **Scanline Transfer & Blank Line Compression:**
   - Scanline packed into 1-bit monochrome bytes (MSB first, `1` = Black ink droplet, `0` = White paper).
   - Blank rows: `\x1b*b<count>Y` skips `<count>` vertical rows without sending data bytes, maximizing USB bandwidth.
   - Non-blank rows: `\x1b*b<len>W<data>` transfers `<len>` bytes of active dot data with trailing white-space trimmed.
5. **Job Termination:**
   - `\x1b*rB` — End raster graphics
   - `\x0c` — Form Feed (ejects page)
   - `\x1bE` — PCL reset
   - `\x1b%-12345X@PJL\r\n@PJL EOJ\r\n\x1b%-12345X` — Closes PJL context and resets the USB communication pipe.

---

## 2. Phase 2 Component Directory

- [`PdfRendererService.kt`](file:///C:/Users/madhav/Documents/vs%20code%20all%20project/usb%20to%20wifi%20printer/app/src/main/java/com/pocketprint/data/print/PdfRendererService.kt): Native Android `PdfRenderer` and canvas synthesizer generating a 300 DPI test page with borders, calibration circles, checkerboard grids, and typography.
- [`PrinterDataEncoder.kt`](file:///C:/Users/madhav/Documents/vs%20code%20all%20project/usb%20to%20wifi%20printer/app/src/main/java/com/pocketprint/data/print/encoder/PrinterDataEncoder.kt): Clean encoder interface extensible for future Canon, Epson, and Brother languages.
- [`HpPcl3GuiEncoder.kt`](file:///C:/Users/madhav/Documents/vs%20code%20all%20project/usb%20to%20wifi%20printer/app/src/main/java/com/pocketprint/data/print/encoder/HpPcl3GuiEncoder.kt): Exact HP PJL + PCL3GUI binary stream encoder.
- [`PrinterProtocolManager.kt`](file:///C:/Users/madhav/Documents/vs%20code%20all%20project/usb%20to%20wifi%20printer/app/src/main/java/com/pocketprint/data/print/PrinterProtocolManager.kt): Dynamic protocol resolver matching detected IEEE 1284 capabilities (`PCL3GUI, DW-PCL, DESKJET`).
- [`UsbPrintTransport.kt`](file:///C:/Users/madhav/Documents/vs%20code%20all%20project/usb%20to%20wifi%20printer/app/src/main/java/com/pocketprint/data/print/transport/UsbPrintTransport.kt): Manages `UsbDeviceConnection`, claims Interface #1, streams 8KB chunks to Bulk OUT `0x08`, and guarantees resource teardown.
- [`PrintJobManager.kt`](file:///C:/Users/madhav/Documents/vs%20code%20all%20project/usb%20to%20wifi%20printer/app/src/main/java/com/pocketprint/data/print/PrintJobManager.kt): Atomic single-job state machine preventing duplicate prints and piping progress to UI.
- [`PrintStatusManager.kt`](file:///C:/Users/madhav/Documents/vs%20code%20all%20project/usb%20to%20wifi%20printer/app/src/main/java/com/pocketprint/data/print/PrintStatusManager.kt): Real-time progress and error state dispatcher.

---

## 3. Physical Verification Procedure

1. Connect the HP Ink Tank 310 series to the phone via USB-C OTG cable.
2. Ensure the printer has A4 paper loaded and power is ON.
3. In PocketPrint:
   - Verify connection badge is green ("Printer Connected & Authorized").
   - Tap **Print Calibration Test Page**.
4. The application will render the page, encode to PCL3GUI, stream the packets to Bulk OUT `0x08`, and progress will update live to 100%.
5. The printer will feed an A4 sheet, print the test graphic and text, and eject the page.
