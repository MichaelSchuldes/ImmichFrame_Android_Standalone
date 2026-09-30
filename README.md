<div align="center">
  <a href="https://github.com/immichFrame/ImmichFrame_Android">
    <img src="https://github.com/immichFrame/ImmichFrame_Desktop/blob/main/src-tauri/icons/icon.png" alt="Logo" width="160" height="160">
  </a>

  <h2 align="center">immichframe-standalone</h2>

  <p align="center">
    Standalone Android photo frame slideshow client connecting directly to <a href="https://immich.app">Immich</a> without an intermediate server.
    <br />
    Compatible with <strong>Android 6.0+ (API 23+)</strong> and low-memory digital photo frames (Frameo, etc.).
  </p>
</div>

---

## 🚀 Features

- **Direct Immich Connection**: Communicates directly with the Immich REST API (no intermediate ASP.NET server required).
- **Native Android 6.0+ Rendering**: Fast, native `ImageView` rendering with memory-optimized bitmap decoders (`RGB_565`), bypassing legacy WebView limitations on older Android frames.
- **Album Filtering**: Supports filtering by both human-readable album names and album UUIDs.
- **Content Filtering**: Exclude photos containing specific people by name or person UUID.
- **Launcher Mode**: Can be set as the default Android Home launcher app with single-task protection.
- **RPC & Active Hours**: Built-in HTTP RPC server (port `53287`) and scheduled screen sleep/wake.

---

## 🛠️ How to Build the APK

### Option 1: Build with Docker (Recommended - No local SDK setup needed)

You only need Docker installed. This builds a reproducible Android SDK environment.

1. **Create the builder image** (if not already built):
   ```bash
   docker build -t immich-android-builder:latest - << 'EOF'
   FROM ghcr.io/cirruslabs/android-sdk:34
   RUN yes | sdkmanager "platforms;android-36" "build-tools;34.0.0"
   EOF
   ```

2. **Compile the APK**:
   ```bash
   docker run --rm \
     -v "$(pwd)":/project \
     -w /project \
     immich-android-builder:latest \
     sh -c "./gradlew assembleDebug && chown -R $(id -u):$(id -g) app/build .gradle"
   ```

3. **Output location**:
   The generated APK will be located at:
   ```
   app/build/outputs/apk/debug/immichframe-standalone-debug.apk
   ```

---

### Option 2: Build Natively with Gradle

Requirements:
- JDK 17 or higher (`JAVA_HOME` set)
- Android SDK installed with `platforms;android-36` and `build-tools;34.0.0` or `35.0.0`
- `ANDROID_HOME` or `ANDROID_SDK_ROOT` environment variable set

Run:
```bash
./gradlew assembleDebug
```

For a release build (requires signing config):
```bash
./gradlew assembleRelease
```

---

## 📲 How to Install via ADB

Ensure ADB is installed and developer options / USB debugging is enabled on your frame.

1. **Connect to device**:
   - **Via USB cable**:
     ```bash
     adb devices
     ```
   - **Via Wi-Fi / Network**:
     ```bash
     adb connect <device-ip-address>:5555
     ```

2. **Install or update the APK**:
   ```bash
   adb install -r app/build/outputs/apk/debug/immichframe-standalone-debug.apk
   ```
   *(The `-r` flag reinstalls/updates the existing app while preserving all settings).*

---

## ▶️ How to Launch via ADB

### Launch the Slideshow App
```bash
adb shell monkey -p com.immichframe.immichframe -c android.intent.category.LAUNCHER 1
```
*Alternatively, start `MainActivity` directly:*
```bash
adb shell am start -n com.immichframe.immichframe/.MainActivity
```

### Launch Settings Screen Directly
If you need to reconfigure the Immich server URL, API key, or display parameters:
```bash
adb shell am start -n com.immichframe.immichframe/.SettingsActivity
```

---

## 🏠 Setting as Default Launcher / Home App

### From App Settings
1. Open ImmichFrame Settings.
2. Scroll down to **Android Settings**.
3. Tap **Set as Default Launcher** and choose **ImmichFrame** $\rightarrow$ **Always**.

### On Dedicated Frames (e.g., Frameo) via ADB
To prevent the stock Frameo launcher from taking over the Home button on boot, you can disable the stock launcher:
```bash
adb shell pm disable-user --user 0 net.frameo.app
```

*(To re-enable the Frameo launcher at any time: `adb shell pm enable net.frameo.app`)*
