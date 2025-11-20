# VeilRenderer

An Android application for rendering stereoscopic video from UVC (USB Video Class) cameras, designed for the Veil Visor system. The app provides real-time video passthrough with configurable eye separation and convergence adjustments via voice commands.

## Features

- **Stereoscopic Video Rendering**: Splits dual-camera video feed into left/right eye views for 3D display
- **UVC Camera Support**: Compatible with USB Video Class cameras (tested with ELP 3D-1080p V85)
- **Voice Command Control**: Hands-free adjustment of eye offsets, convergence, and lens coefficients
- **Lens Distortion Correction**: Post-process radial warp with Quest 2 defaults and live tuning
- **High-Performance Rendering**: OpenGL ES 3.0 with support for 120Hz displays
- **Fullscreen Immersive Mode**: Optimized for head-mounted display usage
- **Persistent Configuration**: Saves calibration settings between sessions

## Hardware Requirements

- **Android Device**: Android 7.0 (API 24) or higher
- **USB Host Support**: Device must support USB host mode (USB OTG)
- **Camera**: UVC-compatible stereo camera (3840x1080 resolution recommended)
- **Display**: Capable of 120Hz refresh rate (optional, for smoother experience)
- **OpenGL ES 3.0**: Required for rendering

## Software Requirements

- **Android Studio**: Arctic Fox or later
- **Gradle**: 8.2.0+
- **Kotlin**: 1.9.20+
- **Android SDK**: Compile SDK 35, Min SDK 24, Target SDK 35

## Dependencies

- `androidx.core:core-ktx:1.12.0`
- `androidx.appcompat:appcompat:1.6.1`
- `com.google.android.material:material:1.11.0`
- `com.herohan:UVCAndroid:1.0.7` - USB camera control
- `org.tensorflow:tensorflow-lite:2.14.0` - For future frame interpolation features
- `org.tensorflow:tensorflow-lite-gpu:2.14.0` - GPU acceleration
- `org.tensorflow:tensorflow-lite-support:0.4.4` - TensorFlow utilities

## Setup

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd VeilRenderer
   ```

2. **Open in Android Studio**:
   - Open the project in Android Studio
   - Wait for Gradle sync to complete

3. **Configure USB Device Filter**:
   - The app includes a USB device filter in `app/src/main/res/xml/device_filter.xml`
   - Modify if your camera has a different USB class or vendor ID

4. **Build and Run**:
   - Connect your Android device via USB (or use an emulator with USB support)
   - Ensure USB debugging is enabled
   - Build and install the app: `Build > Make Project` then `Run > Run 'app'`

## Usage

### Initial Setup

1. **Connect Camera**: Attach your UVC stereo camera to the Android device via USB OTG
2. **Grant Permissions**: Allow camera and microphone permissions when prompted
3. **Automatic Detection**: The app will automatically detect and connect to the camera
4. **Fullscreen Mode**: The app launches in landscape fullscreen mode

### Voice Commands

The app supports continuous voice recognition for hands-free calibration:

- **Adjust Left Eye**: 
  - `"left in [number]"` - Move left eye view toward center (positive offset)
  - `"left out [number]"` - Move left eye view away from center (negative offset)
  - Example: `"left in 50"` moves the left eye 50 pixels toward center

- **Adjust Right Eye**:
  - `"right in [number]"` - Move right eye view toward center (negative offset)
  - `"right out [number]"` - Move right eye view away from center (positive offset)
  - Example: `"right out 30"` moves the right eye 30 pixels away from center

- **Set Convergence**:
  - `"convergence [value]"` or `"converge [value]"` - Set convergence factor (0.0 to 1.0)
  - Example: `"convergence 0.3"` sets convergence to 30%
  - 0.0 = full separation, 1.0 = fully overlapped

### Calibration Workflow

1. Connect camera and wait for video feed to appear
2. Put on your visor/headset
3. Use voice commands to adjust eye alignment:
   - Start with convergence: `"convergence 0.3"`
   - Fine-tune left eye: `"left in 20"`
   - Fine-tune right eye: `"right out 15"`
4. Settings are automatically saved and restored on next launch

### Lens Distortion Calibration

The distortion pass uses a radial model `p' = p * (1 + k1*r^2 + k2*r^4 + k3*r^6)` with default Quest 2-friendly values:

- `k1 = -0.34`
- `k2 = 0.12`
- `k3 = -0.02`
- Lens centers start at `(0.5, 0.5)` per eye (normalized eye UV)

You can live-adjust the coefficients via voice commands while wearing the headset:

- `"lens k1 -0.36"`
- `"lens k2 0.08"`
- `"lens k3 -0.015"`

Each command updates the specified coefficient, displays the new value, and persists it to `SharedPreferences`. Iterate on-device until lines look straight through your optics, then note the values for future builds.

If you need to reset coefficients, clear the app data or delete the `lensK*` keys inside `VeilRendererConfig.xml` (device path: `/data/data/com.veil.renderer/shared_prefs/`).

## Project Structure

```
VeilRenderer/
├── app/
│   ├── build.gradle.kts          # App-level Gradle configuration
│   ├── src/
│   │   └── main/
│   │       ├── AndroidManifest.xml
│   │       ├── assets/
│   │       │   └── shaders/       # OpenGL shader files
│   │       │       ├── passthrough.vert
│   │       │       ├── passthrough.frag
│   │       │       ├── distort.vert
│   │       │       └── distort.frag
│   │       ├── java/com/veil/renderer/
│   │       │   └── MainActivity.kt # Main application logic
│   │       └── res/
│   │           ├── values/        # Strings, colors
│   │           └── xml/
│   │               └── device_filter.xml # USB device filter
│   └── build/                     # Build outputs (gitignored)
├── build.gradle.kts               # Project-level Gradle configuration
├── settings.gradle.kts            # Gradle settings
└── gradle/                        # Gradle wrapper
```

## Architecture

### Main Components

- **MainActivity**: Manages app lifecycle, USB camera connection, and voice recognition
- **StereoRenderer**: OpenGL ES 3.0 renderer that handles stereoscopic video display
  - Splits 3840x1080 camera feed into two 1920x1080 views
  - Applies eye offsets and convergence adjustments
  - Maintains aspect ratio with letterboxing/pillarboxing

### Camera Configuration

- **Default Resolution**: 3840x1080 @ 60fps
- **Format**: MJPEG (preferred) or YUYV fallback
- **Aspect Ratio**: Maintains camera aspect ratio on screen

### Rendering Pipeline

1. UVC camera captures dual-camera video
2. Frames decoded to `SurfaceTexture` via MediaCodec path
3. OpenGL shaders render left/right eye views with applied offsets
4. Display refreshes at up to 120Hz (when supported)

## Troubleshooting

### Camera Not Detected

- Verify USB OTG cable and adapter are working
- Check `device_filter.xml` matches your camera's USB class (14 = Video)
- Enable USB debugging in Developer Options
- Try different USB ports/cables

### No Video Feed

- Check camera permissions in Android Settings
- Verify camera supports UVC protocol
- Check logcat for error messages: `adb logcat | grep VeilRenderer`
- Ensure camera is not being used by another app

### Voice Commands Not Working

- Grant microphone/audio permission
- Check Google Speech Recognition is available on device
- View logs: `adb logcat | grep VeilRenderer | grep Recognized`

### Poor Performance

- Reduce camera resolution if device can't handle 3840x1080
- Check if 120Hz mode is active in logcat
- Disable other background apps

## Configuration Storage

Settings are saved in `SharedPreferences` under the key `VeilRendererConfig`:
- `leftEyeOffset`: Integer pixel offset for left eye
- `rightEyeOffset`: Integer pixel offset for right eye  
- `convergenceFactor`: Float value (0.0-1.0) for convergence

## Future Enhancements

- TensorFlow Lite integration for frame interpolation
- Additional voice commands for advanced settings
- On-screen calibration UI overlay
- Support for different camera resolutions
- Recording and playback functionality

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]

## Acknowledgments

- UVCAndroid library by HeroHan for USB camera support
- Android OpenGL ES documentation
- Veil Visor project team





