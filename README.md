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

3. **Persistent Settings**:
   Settings are automatically saved and mirrored to `/sdcard/immichframe_settings.json`. Even if the app is uninstalled or updated, settings are automatically restored upon the next launch. You can also seed or backup settings anytime:
   ```bash
   # Pull current settings to your computer
   adb pull /sdcard/immichframe_settings.json .

   # Push settings to device
   adb push immichframe_settings.json /sdcard/immichframe_settings.json
   ```

---

## ▶️ How to Launch via ADB

- **Package Name (Application ID)**: `com.immichframe.standalone`
- **Main Activity**: `com.immichframe.standalone/.MainActivity`

### Launch the Slideshow App

Start the main slideshow activity directly:
```bash
adb shell am start -n com.immichframe.standalone/.MainActivity
```

*Alternatively, launch via monkey intent:*
```bash
adb shell monkey -p com.immichframe.standalone -c android.intent.category.LAUNCHER 1
```

### Force-Stop or Restart the App
```bash
# Force stop
adb shell am force-stop com.immichframe.standalone

# Restart
adb shell am force-stop com.immichframe.standalone && adb shell am start -n com.immichframe.standalone/.MainActivity
```

### Open the Settings Screen
If you need to reconfigure settings without using the touchscreen:
- **Via D-Pad Key Event** (triggers Settings in `MainActivity`):
  ```bash
  adb shell input keyevent 19    # KEYCODE_DPAD_UP
  ```
- **Via Local RPC Server**:
  ```bash
  curl -X POST http://<device-ip-address>:53287/settings
  ```
- **Via Direct Activity Start** (requires root):
  ```bash
  adb shell su -c "am start -n com.immichframe.standalone/.SettingsActivity"
  ```

---

## 🔒 Let's Encrypt & HTTPS Certificates (Android 6.0)

### Out-of-the-Box Support
Older Android versions (Android 6.0 and earlier) lack the modern **ISRG Root X1** CA certificate used by Let's Encrypt (due to the expiration of the legacy *DST Root CA X3* in September 2021). 

**You do NOT need to modify system certificates for ImmichFrame.**
`immichframe-standalone` comes with the **ISRG Root X1** certificate directly bundled into its internal OkHttp/Retrofit SSL engine. Connecting to `https://immich.yourdomain.com` with a standard Let's Encrypt certificate works immediately out-of-the-box.

---

### (Optional) How to Update Root CAs on the Android Device

If you are using a self-signed CA, custom enterprise CA, or want the entire Android OS to trust ISRG Root X1:

#### Method A: Install as User Certificate via GUI (No root required)
1. Download the [ISRG Root X1 PEM / CRT certificate](https://letsencrypt.org/certs/isrgrootx1.pem).
2. Push the certificate to device storage:
   ```bash
   adb push isrgrootx1.pem /sdcard/isrgrootx1.crt
   ```
3. On the device, open **Android Settings** $\rightarrow$ **Security** (or **Lock screen and security**).
4. Tap **Install from storage** (or **Install from SD card**).
5. Select `isrgrootx1.crt` and name the certificate (e.g. `ISRG Root X1`).
   *(Note: Android will prompt you to set a lock screen PIN/Pattern if one is not already configured).*

#### Method B: Install into System CA Store via ADB (Rooted devices only)
If your Frameo device has root access (`adb root`), you can add the certificate permanently to `/system/etc/security/cacerts/` without requiring a lock screen PIN:
```bash
# 1. Download certificate
curl -o isrgrootx1.pem https://letsencrypt.org/certs/isrgrootx1.pem

# 2. Rename to Android's subject hash format (ISRG Root X1 hash is 4042bcee.0)
cp isrgrootx1.pem 4042bcee.0

# 3. Remount system partition as writable
adb root
adb remount

# 4. Push to system certificate directory and set permissions
adb push 4042bcee.0 /system/etc/security/cacerts/
adb shell chmod 644 /system/etc/security/cacerts/4042bcee.0

# 5. Reboot device
adb reboot
```

---

## 🏠 Setting as Default Launcher / Home App

### From App Settings
1. Open immichframe-standalone Settings.
2. Scroll down to **Android Settings**.
3. Tap **Set as Default Launcher** and choose **immichframe-standalone** $\rightarrow$ **Always**.

### On Dedicated Frames (e.g., Frameo) via ADB
To prevent the stock Frameo launcher from taking over the Home button on boot, you can disable the stock launcher:
```bash
adb shell pm disable-user --user 0 net.frameo.app
```

*(To re-enable the Frameo launcher at any time: `adb shell pm enable net.frameo.app`)*
