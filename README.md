# CodeCapture (Android — Pixel 8a)

**CodeCapture** is an offline-first Android application (Kotlin + Jetpack Compose) that turns a smartphone into an independent monitor code-capture and Bluetooth HID scrolling device, automatically producing **dual output**:

1. **Original ordered photographs** of the computer monitor (`Photos/image_001.jpg`, ...), always retained as the primary visual record.
2. **Automatically reconstructed editable source code** (`Extracted/source_001.<ext>` + `extraction_report.json`) with per-line confidence scores, ambiguity flags, gap markers, and source-photo provenance.

No software, scripts, browser extensions, or admin permissions are required on the target computer.

---

## Requirements Implemented

- **FR-01 / FR-02**: Android `BluetoothHidDevice` combo keyboard + mouse-wheel peripheral, multi-computer profile storage, per-device scroll calibration & macOS Natural Scroll inversion.
- **FR-03 / FR-04 / FR-06**: CameraX high-res `ImageCapture` + low-res `ImageAnalysis` verification stream, Laplacian blur & specular glare scoring, automatic editor viewport detection with manual override, Fixed & Handheld modes.
- **FR-07 / FR-08 / FR-09 / FR-12**: Closed-loop proportional-adaptive scrolling with coarse-to-fine NCC visual overlap matching, overshoot rollback, handheld camera-motion detection, and End-of-Content detection with user override.
- **FR-10 / FR-11 / FR-13**: Manual shutter/scroll controls always accessible alongside automatic capture (`CAPTURE -> ANALYSE -> SCROLL -> VERIFY -> REPEAT -> AUTO-EXTRACT`).
- **Revision 1.1 (FR-16 – FR-22, AC-12 – AC-22)**: Automatic WorkManager background extraction with per-frame checkpoint resume, bundled offline ML Kit OCR behind pluggable `CodeOcrEngine`, indentation/blank-line/wrapped-line reconstruction, hybrid multi-image deduplication, language & non-destructive syntax validation, image-linked Review & Correction screen, and dual-output ZIP / PDF / ShareSheet export.

---

## Building & Running on Google Pixel 8a

1. Open the `CodeCapture/` folder in **Android Studio** (Ladybug / Meerkat or newer with JDK 17 and Android SDK 35).
2. Let Gradle sync dependencies from Google Maven and Maven Central (`libs.versions.toml`).
3. Run unit tests:
   ```bash
   ./gradlew test
   ```
4. Connect your **Pixel 8a** via USB debugging and install:
   ```bash
   ./gradlew installDebug
   ```
5. Grant **Camera**, **Nearby Devices (Bluetooth)**, and **Notifications** permissions on first launch.

### Pairing with a Computer (Windows / Linux / macOS)
1. Pair your Pixel 8a with your computer in standard Android Bluetooth settings.
2. Open the **Computers** tab in CodeCapture, tap **Refresh**, select your computer, and tap **Connect HID**.
3. If your computer uses macOS Natural Scrolling, toggle **Invert scroll direction** on that computer's profile card.
4. Click inside your code editor window on the computer, point the Pixel 8a at the monitor, tap **Calibrate**, and tap **Auto**.

---

## Local Dual-Output Storage Layout

```text
<app-files>/CodeCapture/Sessions/<SessionName>/<YYYY-MM-DD>/<sessionPrefix>/
├── Photos/
│   ├── image_001.jpg
│   ├── image_002.jpg
│   └── ...
├── Extracted/
│   ├── source_001.<ext>
│   └── extraction_report.json
└── session_metadata.json
```

---

## Important OCR Limitation (Section 16.3)

Photograph-based OCR cannot guarantee exact recovery of source code, especially under small font sizes, glare, monitor moire, or ambiguous symbol pairs (`0/O`, `1/l/I`, `{/(`, `}/)`). CodeCapture explicitly flags uncertain lines and preserves every original photograph so you can verify and correct extracted text side-by-side.
