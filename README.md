# LinkView

LinkView is a lightweight Android application that turns a web URL into a simple full-screen app experience. It opens a configurable website inside an Android `WebView`, remembers the selected URL, and provides a hidden URL editor for maintenance.

The default URL is:

```text
http://rtxa.duckdns.org:8000
```

## Features

- Full-screen Android `WebView` wrapper.
- One-time intro screen before the first launch.
- Saved current URL through `SharedPreferences`.
- Hidden URL editor gesture for changing or reloading the target page.
- JavaScript, DOM storage, wide viewport, mixed content, and no-cache loading enabled.
- Cleartext HTTP traffic allowed through a network security config.
- Automatic retry for main-frame `ERR_CONTENT_LENGTH_MISMATCH` errors.
- Debug WebView inspection enabled only for debuggable builds.

## Hidden URL Editor

To open the URL editor:

1. Long-press the WebView for about one second.
2. Release.
3. Long-press again for about one second within the next two seconds.

The dialog lets you open a new URL, cancel, or reload the current page. URLs without a protocol are normalized to `http://`.

## Requirements

- Android Studio / Android SDK.
- JDK 21. The Android Studio bundled JBR works well.
- Windows PowerShell, or another shell capable of running the Gradle wrapper.

If your default `JAVA_HOME` points to an older JDK, set it to Android Studio's JBR before building:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

## Build

From the project root:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Useful output paths:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

The release build currently uses the debug signing config, so replace it with a real release signing setup before distributing outside local/internal use.

## Project Structure

```text
app/src/main/java/com/aes/linkview/MainActivity.java   Main Android activity and WebView logic
app/src/main/AndroidManifest.xml                       App manifest and permissions
app/src/main/res/xml/network_security_config.xml       Cleartext HTTP configuration
app/src/main/res/values/                               App name and theme resources
android_page.html                                      Reference/snapshot web page, not packaged in the APK
gradle/libs.versions.toml                              Gradle plugin and test dependency versions
```

## Configuration

- Default web URL: `DEFAULT_WEB_URL` in `MainActivity.java`.
- Application id: `com.aes.linkview`.
- Version: `versionCode = 2`, `versionName = "1.1"` in `app/build.gradle.kts`.
- Minimum SDK: 24.
- Target SDK: 36.

## Testing Notes

The project includes template unit and instrumented tests. The Android instrumented test still references the old template package name (`com.example.navigation`), so update it before relying on instrumentation results.

## Maintenance Notes

- Arabic UI copy is currently hard-coded in `MainActivity.java`; keep files encoded as UTF-8 when editing text.
- `android_page.html` is a standalone web page snapshot and is not loaded from local Android assets.
- The Git repository root appears to be above this project directory, so check paths carefully before committing.
