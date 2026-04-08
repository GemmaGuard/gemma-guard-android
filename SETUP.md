# Setup Guide

This guide covers everything needed to build and run Gemma Guard on a physical Android device.

## Requirements

- **Android device** — Android 10+ (API 29+), physical device strongly recommended
- **Android Studio** — Hedgehog (2023.1.1) or later
- **ADB** — Android Debug Bridge, installed and on your PATH
- **Gemma 4 model file** — `gemma-4-E2B-it.litertlm` (not included in repo, see below)

## 1. Obtain the Model File

The Gemma 4 model file is not included in this repository due to its size. Download it from Kaggle:

- [google/gemma — Kaggle Models](https://www.kaggle.com/models/google/gemma)

Select the **Gemma 4 E2B Instruct** variant in `.litertlm` format.

## 2. Stage the Model on Your Device

During development, the model is pushed to a temporary ADB staging path. The app copies it into private app storage on first launch.

```bash
# Connect your device and verify ADB sees it
adb devices

# Create the staging directory
adb shell mkdir -p /data/local/tmp/llm

# Push the model (this may take a few minutes depending on file size)
adb push gemma-4-E2B-it.litertlm /data/local/tmp/llm/gemma-4-E2B-it.litertlm

# Verify the file is there
adb shell ls -lh /data/local/tmp/llm/
```

## 3. Build and Run

1. Open the project root (`gemma-guard/`) in Android Studio
2. Let Gradle sync and download all wrapper dependencies
3. Select the `app` run configuration
4. Connect your Android device via USB with USB debugging enabled
5. Run — Android Studio will build and install the APK

> **Note:** The emulator can run the app but is not suitable for Gemma 4 inference. Use a physical device for real analysis.

## 4. First Launch

On first launch, Gemma Guard will:

1. Detect the staged model at `/data/local/tmp/llm/gemma-4-E2B-it.litertlm`
2. Copy it into private app storage at `filesDir/models/gemma-4-E2B-it.litertlm`
3. Show a progress indicator during the copy

Once the copy completes, the app is ready for analysis.

## 5. Grant Permissions

Two permissions are required:

| Permission | Why |
|------------|-----|
| **Display over other apps** (Overlay) | Required for the floating trigger button that appears over other apps |
| **Screen capture** (MediaProjection) | Required to take a screenshot when the trigger is tapped |

Both will be requested on first use. Grant them from the system settings dialogs when prompted.

## Model Storage Paths

| Path | Purpose |
|------|---------|
| `/data/local/tmp/llm/gemma-4-E2B-it.litertlm` | ADB staging path (development only) |
| `filesDir/models/gemma-4-E2B-it.litertlm` | App-managed private storage (runtime) |

The app always loads the model from private app storage, never directly from the ADB staging path. The staging path is only used as a source for the initial copy.

## Troubleshooting

**"Gemma 4 is not ready yet" on launch**
The model file was not found. Re-run the ADB push command and reopen the app.

**App falls back to local analyzer**
The Gemma 4 model is still initializing on first use after copy. Wait a moment and try again. The local analyzer provides a real verdict in the meantime.

**Analysis takes longer than expected**
Gemma 4 inference time depends on device hardware. On mid-range devices expect 10–30 seconds. A physical device with a capable SoC will be significantly faster than a low-end device or emulator.
