# CodeCapture — Architecture & Engineering Decision Records (v1.1)

This document records the technical evaluations, trade-offs, and implementation decisions made for **CodeCapture** (SRS v1.0 + Revision 1.1) targeting the **Google Pixel 8a** (Android 14–16, Tensor G3).

---

## 1. System Overview & Module Boundaries

CodeCapture separates real-time capture from offline extraction so that camera and Bluetooth HID resources are never held open during OCR processing (Section 15.5):

- `com.codecapture.bluetooth` — `HidReportDescriptor`, `BluetoothHidController` (`BluetoothHidDevice` API 28+).
- `com.codecapture.camera` — `CameraCaptureManager` (CameraX Preview + ImageCapture + ImageAnalysis), `ImageQualityAnalyzer`.
- `com.codecapture.vision` — `ScreenEditorDetector`, `VisualOverlapMatcher`, `AdaptiveScrollController`, `EndOfContentDetector`, `CaptureSessionOrchestrator`.
- `com.codecapture.extraction` — Pluggable `CodeOcrEngine` (`MlKitCodeOcrEngine`), `CodeLayoutReconstructor`, `MultiImageCodeAssembler`, `LanguageAndSyntaxValidator`, 11-stage `ExtractionPipeline`, WorkManager `ExtractionWorker`.
- `com.codecapture.storage` — Room `CodeCaptureDatabase`, dual-output `SessionRepository`, `SessionExporter` (ZIP, PDF, ShareSheet).
- `com.codecapture.ui` — Jetpack Compose Material 3 screens (`MainCaptureScreen`, `DeviceManagerScreen`, `SessionGalleryScreen`, `ReviewCorrectionScreen`).

---

## 2. Engineering Decision Records (Section 2 & Section 15)

### ADR-01: Bluetooth HID Connectivity (`BluetoothHidDevice` Composite Descriptor)
- **Problem**: Control vertical scrolling (and optional keyboard navigation) on Windows, Linux, macOS, and Bluetooth-capable VMs without installing host software (FR-01).
- **Decision**: Register a single unified composite HID descriptor (`BluetoothHidDevice.SUBCLASS1_COMBO`) using Android's [`BluetoothHidDevice`](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice) API with Report ID 1 (8-byte boot-compatible Keyboard) and Report ID 2 (4-byte Mouse with signed 8-bit vertical wheel), inspired by the dual-report pattern documented in [ADR-002](https://github.com/ShubhamJ010/ghost-hid/blob/main/docs/decisions/ADR-002-dual-report-descriptor-for-keyboard-and-mouse.md).
- **Rationale**:
  - Works natively on Pixel 8a (API 34+) without root or companion daemons.
  - Sending wheel reports in small chunks (`<= 3` ticks spaced by `28 ms`) avoids host OS scroll-acceleration spikes on macOS and Windows.
  - Per-device `ScrollCalibration` stores `invertScrollDirection` for macOS Natural Scrolling vs. Windows/Linux standard scrolling.
- **Known Limitations**: Some locked-down enterprise hosts disable Bluetooth HID pairing by policy; hosts with non-standard high-resolution scroll multipliers require the closed-loop controller to adapt online.

### ADR-02: Camera Pipeline (CameraX Dual-Stream)
- **Problem**: Capture crisp high-resolution photographs of monitors while running fast low-latency verification frames during scrolling (FR-03, NFR-02).
- **Decision**: Use [CameraX 1.5.2](https://developer.android.com/jetpack/androidx/releases/camera) with three bound use cases: `Preview`, `ImageCapture` (`CAPTURE_MODE_MAXIMIZE_QUALITY`), and `ImageAnalysis` (`STRATEGY_KEEP_ONLY_LATEST`, capped at `960x720` RGBA).
- **Rationale**: Avoids decoding full 12MP–64MP JPEGs during closed-loop scroll verification, keeping verification latency low and battery consumption manageable on Pixel 8a (NFR-05).

### ADR-03: Closed-Loop Adaptive Scrolling & Overlap Verification
- **Problem**: Fixed wheel increments skip lines when font size, DPI scaling, or OS scroll speed varies across computers (FR-07, FR-08, FR-09).
- **Decision**: Implement a two-stage proportional-adaptive controller (`AdaptiveScrollController`) backed by coarse-to-fine 2D Normalized Cross-Correlation (`VisualOverlapMatcher`) on downsampled grayscale editor crops, plus static-border motion estimation (`ScreenEditorDetector`) for handheld mode.
- **Rationale**:
  - Computes initial scroll from `estimatedTicksPerScreenHeight * (1 - targetOverlap) * safetyFactor` (`0.82` Fixed, `0.68` Handheld) to bias against overshoot.
  - Continuously updates `estimatedTicksPerScreenHeight` via an Exponential Moving Average (`0.65` prior + `0.35` observed).
  - If overlap drops below the safe floor (`targetOverlap - 6%`, min `10%`), immediately issues a corrective upward scroll (`SCROLL_BACK_UP_OVERSHOOT`) instead of accepting a frame with missed lines.

### ADR-04: On-Device Offline OCR Engine Selection (Section 15.3)
| Engine | Offline Support | Model Size | Android Integration | Code/Symbol Accuracy | Licensing | Decision |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **[ML Kit Text Recognition v2 (Bundled)](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)** | 100% offline (`com.google.mlkit:text-recognition:16.0.1`) | ~4–6 MB | Native AAR, zero JNI boilerplate, per-element confidence & bounding boxes | Strong on clean Latin/code; occasional `0/O`, `1/l/I` ambiguity | Google ML Kit Terms | **Selected Primary** |
| **PaddleOCR (PP-OCRv4 via ONNX/NCNN)** | 100% offline | ~15–25 MB | Requires native ONNX Runtime/NCNN JNI wrapper & custom post-processing | Very strong on dense/small monitor fonts | Apache 2.0 | **Supported via `CodeOcrEngine` interface** |
| **Tesseract 5 (Tess-Two)** | 100% offline | ~12–30 MB | Native NDK build | Sensitive to monitor moire & dark themes without heavy binarization | Apache 2.0 | Not selected |

- **Decision**: Ship bundled offline ML Kit v2 as the default `MlKitCodeOcrEngine` behind the pluggable `CodeOcrEngine` interface so sessions can be re-extracted with PaddleOCR or another engine later without re-capturing photos (AC-20).

### ADR-05: Hybrid Multi-Image Reconstruction & Syntax Validation (Section 15.2 & 15.4)
- **Problem**: Reconstruct continuous editable source code without duplicating overlapping lines or accidentally deleting legitimate repeated code blocks (FR-17), and validate syntax without silently rewriting code (FR-20).
- **Decision**:
  - `MultiImageCodeAssembler` ranks overlap candidates using (1) optional line-number anchors, (2) multi-line Levenshtein similarity across suffix/prefix windows, and (3) visual overlap ratio priors. Single-line matches are rejected unless long and distinctive (`>= 18` chars, `>= 90%` similarity) so repeated `}` or blank lines are never erroneously collapsed. Unverified transitions insert an explicit `[CodeCapture GAP]` marker.
  - `LanguageAndSyntaxValidator` performs heuristic language classification (defaulting safely to `.txt` below `50%` confidence) and non-destructive stack-based delimiter/quote validation. Full Tree-sitter JNI bindings can be plugged into `LanguageAndSyntaxValidator` as an optional native module in a future iteration without changing the pipeline contract.

### ADR-06: Resilient Background Processing & Dual-Output Storage (Section 14.10 & 15.5)
- **Decision**: Persist accepted photographs immediately to `CodeCapture/Sessions/<Name>/<YYYY-MM-DD>/<id>/Photos/` and index them in [Room 2.8.0](https://developer.android.com/jetpack/androidx/releases/room). Trigger [WorkManager 2.12.0](https://developer.android.com/jetpack/androidx/releases/work) `ExtractionWorker` automatically upon session completion, checkpointing per-frame OCR JSON to Room so process termination resumes cleanly. Original photographs are never modified or deleted by extraction or cancellation (AC-14, AC-22).
