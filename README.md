# LinkView

LinkView is a lightweight Android application that turns a web URL into a simple full-screen app experience. It opens a configurable website inside an Android `WebView`, remembers the selected URL, and provides a hidden URL editor for maintenance.

The default URL is:

```text
http://sw4.duckdns.org/gitea
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
- Native JavaScript bridge exposed as `window.LinkView` for web apps running inside the `WebView`.

## Hidden URL Editor

To open the URL editor:

1. Long-press the WebView for about one second.
2. Release.
3. Long-press again for about one second within the next two seconds.

The dialog lets you manage saved links, open a link, save a new link, edit existing links, delete links, or reload the current page. URLs without a protocol are normalized to `http://`.

## Native JavaScript Bridge

Pages loaded inside the Android `WebView` receive a high-level `window.LinkView` API after the `linkviewready` event. The lower-level Android interface is also available as `window.LinkViewNative`, but web apps should prefer `window.LinkView`.

Example:

```js
document.addEventListener("linkviewready", async () => {
  console.log(LinkView.getCapabilities());

  const permissions = await LinkView.requestPermissions([
    "camera",
    "microphone",
    "location",
    "storage"
  ]);

  console.log(permissions);
  LinkView.toast("Native bridge is ready");
});
```

Available `window.LinkView` methods:

```text
getCapabilities()                    Returns bridge version, supported permissions, and API names.
getDeviceInfo()                      Returns Android device and OS information.
getPermissions(names)                Returns current permission status.
requestPermissions(names)            Prompts Android runtime permissions and resolves with status.
getCurrentUrl()                      Returns the current WebView URL known by the native host.
openUrl(url)                         Saves and opens a URL in the WebView.
reload()                             Reloads the current WebView page.
showUrlManager()                     Opens the hidden saved-links manager dialog.
getLinks()                           Returns saved links.
saveLink(name, url)                  Saves or updates a managed link.
deleteLink(url)                      Deletes a managed link by URL.
toast(message)                       Shows a native Android toast.
```

Supported permission names:

```text
camera
microphone
location
storage
media_images
media_video
media_audio
```

`requestPermissions()` returns a Promise. `getPermissions()` returns immediately. Both use the same response shape:

```json
{
  "ok": true,
  "permissions": [
    {
      "name": "camera",
      "supported": true,
      "granted": true
    }
  ]
}
```

The bridge intentionally grants only the Android permissions already declared by the app. Camera and microphone requests from browser APIs such as `getUserMedia()` are also handled through the WebView permission flow.

Security note: every page loaded in the configured WebView can access this bridge, so production builds should load only trusted web apps.

## Built-In Bridge Demo

The APK includes a lightweight local web app for validating the native bridge:

```text
file:///android_asset/linkview-demo/index.html
```

It appears in the saved links manager as `اختبار LinkView`. The demo checks the injected bridge, requests permissions through `LinkView.requestPermissions()`, opens camera and microphone streams, reads geolocation, and verifies file selection through the WebView file picker.

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
backend/                                               Standalone Python receiver for location and media data
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
