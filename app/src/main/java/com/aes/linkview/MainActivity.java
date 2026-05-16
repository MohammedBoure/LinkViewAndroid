package com.aes.linkview;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String DEFAULT_WEB_URL = "http://sw4.duckdns.org/gitea";
    private static final String LOCAL_DEMO_URL = "file:///android_asset/linkview-demo/index.html";
    private static final String PREFS_NAME = "webview_settings";
    private static final String PREF_URL = "current_url";
    private static final String PREF_MANAGED_LINKS = "managed_links";
    private static final String PREF_INTRO_SEEN = "intro_seen";
    private static final String TAG = "LinkViewWebView";
    private static final int APP_PERMISSION_REQUEST_CODE = 1001;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1002;
    private static final long URL_EDITOR_HOLD_MS = 1000L;
    private static final long URL_EDITOR_SECOND_PRESS_WINDOW_MS = 2000L;
    private static final int MAX_MANAGED_LINKS = 30;

    private final Handler secretGestureHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private String lastLoadingUrl;
    private String currentWebUrl = DEFAULT_WEB_URL;
    private int mainFrameRetryCount;
    private int secretGestureStep;
    private long secondPressDeadline;
    private boolean pressStartedWithinSecondWindow;
    private Runnable pendingSecretHold;
    private PermissionRequest pendingWebPermissionRequest;
    private String pendingGeolocationOrigin;
    private GeolocationPermissions.Callback pendingGeolocationCallback;
    private String pendingJsPermissionCallbackId;
    private List<String> pendingJsPermissionAliases;
    private ValueCallback<Uri[]> pendingFilePathCallback;
    private boolean appPermissionRequestInFlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        requestAppPermissions();

        if (hasSeenIntro()) {
            startWebView();
        } else {
            showIntroScreen();
        }
    }

    private boolean hasSeenIntro() {
        return preferences().getBoolean(PREF_INTRO_SEEN, false);
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private void showIntroScreen() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(246, 248, 251));
        scrollView.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dp(24), dp(36), dp(24), dp(24));
        scrollView.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("LinkView");
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(25, 31, 42));
        title.setGravity(Gravity.CENTER);
        container.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("عارض روابط بسيط");
        subtitle.setTextSize(20);
        subtitle.setTextColor(Color.rgb(118, 80, 9));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(22));
        container.addView(subtitle, matchWrap());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(cardBackground(Color.WHITE, Color.rgb(216, 224, 235), dp(14)));
        container.addView(card, matchWrap());

        TextView body = new TextView(this);
        body.setText(
                "هذا التطبيق يحول أي رابط ويب إلى واجهة تطبيق خفيفة.\n\n"
                        + "في التشغيل الأول سيستعمل الرابط الافتراضي، وبعدها يمكنك تغيير الرابط عند الحاجة فقط.\n\n"
                        + "لإظهار نافذة تغيير الرابط: اضغط على الشاشة لمدة ثانية، اتركها، ثم اضغط مرة أخرى لمدة ثانية خلال ثانيتين."
        );
        body.setTextSize(18);
        body.setLineSpacing(4f, 1.0f);
        body.setTextColor(Color.rgb(45, 55, 72));
        body.setGravity(Gravity.RIGHT);
        card.addView(body, matchWrap());

        TextView url = new TextView(this);
        url.setText(currentSavedUrl());
        url.setTextSize(16);
        url.setTextColor(Color.rgb(20, 89, 135));
        url.setGravity(Gravity.CENTER);
        url.setTextDirection(View.TEXT_DIRECTION_LTR);
        url.setPadding(dp(12), dp(12), dp(12), dp(12));
        url.setBackground(cardBackground(Color.rgb(235, 244, 255), Color.rgb(189, 216, 245), dp(10)));
        LinearLayout.LayoutParams urlParams = matchWrap();
        urlParams.setMargins(0, dp(18), 0, 0);
        card.addView(url, urlParams);

        Button startButton = new Button(this);
        startButton.setText("بدء الاستخدام");
        startButton.setTextSize(18);
        startButton.setTextColor(Color.WHITE);
        startButton.setAllCaps(false);
        startButton.setBackground(cardBackground(Color.rgb(166, 111, 0), Color.rgb(166, 111, 0), dp(12)));
        startButton.setOnClickListener(view -> {
            preferences().edit().putBoolean(PREF_INTRO_SEEN, true).apply();
            startWebView();
        });
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.setMargins(0, dp(22), 0, 0);
        container.addView(startButton, buttonParams);

        setContentView(scrollView);
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void startWebView() {
        boolean isDebuggable =
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(isDebuggable);

        webView = new WebView(this);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(
                        TAG,
                        consoleMessage.messageLevel() + ": " + consoleMessage.message()
                                + " (" + consoleMessage.sourceId() + ":"
                                + consoleMessage.lineNumber() + ")"
                );
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermissionRequest == request) {
                    pendingWebPermissionRequest = null;
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback
            ) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                    return;
                }

                pendingGeolocationOrigin = origin;
                pendingGeolocationCallback = callback;
                requestAppPermissions();
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (pendingFilePathCallback != null) {
                    pendingFilePathCallback.onReceiveValue(null);
                }
                pendingFilePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (ActivityNotFoundException exception) {
                    pendingFilePathCallback = null;
                    Toast.makeText(
                            MainActivity.this,
                            "لا يوجد تطبيق لاختيار الملفات",
                            Toast.LENGTH_SHORT
                    ).show();
                    return false;
                }
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                currentWebUrl = url;
                if (lastLoadingUrl == null || !lastLoadingUrl.equals(url)) {
                    lastLoadingUrl = url;
                    mainFrameRetryCount = 0;
                }
                Log.d(TAG, "Loading " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Finished " + url + " title=" + view.getTitle());
                injectLinkViewApi(view);
                view.evaluateJavascript(
                        "document.body ? document.body.innerText.slice(0, 200) : 'NO_BODY'",
                        text -> Log.d(TAG, "Body preview: " + text)
                );
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                if (request.isForMainFrame()) {
                    Log.e(TAG, "Main frame error " + error.getErrorCode() + ": "
                            + error.getDescription());
                    retryMainFrameIfNeeded(view, request, error);
                    return;
                }
                super.onReceivedError(view, request, error);
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        webView.addJavascriptInterface(new LinkViewBridge(), "LinkViewNative");
        webView.setLongClickable(true);
        webView.setOnLongClickListener(view -> true);
        webView.setOnTouchListener((view, event) -> {
            handleSecretGestureTouch(event);
            return false;
        });

        setContentView(webView);
        webView.loadUrl(currentSavedUrl());
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != APP_PERMISSION_REQUEST_CODE) {
            return;
        }

        appPermissionRequestInFlight = false;
        resolvePendingWebPermissionRequest();
        resolvePendingGeolocationRequest();
        resolvePendingJsPermissionRequest();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (pendingFilePathCallback != null) {
                Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                pendingFilePathCallback.onReceiveValue(results);
                pendingFilePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void requestAppPermissions() {
        requestRuntimePermissions(appRuntimePermissions());
    }

    private void requestRuntimePermissions(List<String> permissions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || appPermissionRequestInFlight) {
            return;
        }

        List<String> missingPermissions = missingPermissions(permissions);
        if (!missingPermissions.isEmpty()) {
            appPermissionRequestInFlight = true;
            requestPermissions(
                    missingPermissions.toArray(new String[0]),
                    APP_PERMISSION_REQUEST_CODE
            );
        }
    }

    private List<String> appRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            }
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        return permissions;
    }

    private List<String> missingPermissions(List<String> permissions) {
        List<String> missingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (!hasPermission(permission)) {
                missingPermissions.add(permission);
            }
        }
        return missingPermissions;
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        List<String> requiredPermissions = androidPermissionsForWebResources(request.getResources());
        List<String> missingPermissions = missingPermissions(requiredPermissions);
        if (missingPermissions.isEmpty()) {
            grantAllowedWebResources(request);
            return;
        }

        pendingWebPermissionRequest = request;
        requestAppPermissions();
    }

    private List<String> androidPermissionsForWebResources(String[] resources) {
        List<String> permissions = new ArrayList<>();
        for (String resource : resources) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                addPermissionIfMissing(permissions, Manifest.permission.RECORD_AUDIO);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                addPermissionIfMissing(permissions, Manifest.permission.CAMERA);
            }
        }
        return permissions;
    }

    private void addPermissionIfMissing(List<String> permissions, String permission) {
        if (!permissions.contains(permission)) {
            permissions.add(permission);
        }
    }

    private void resolvePendingWebPermissionRequest() {
        if (pendingWebPermissionRequest == null) {
            return;
        }

        PermissionRequest request = pendingWebPermissionRequest;
        pendingWebPermissionRequest = null;
        grantAllowedWebResources(request);
    }

    private void grantAllowedWebResources(PermissionRequest request) {
        List<String> grantedResources = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                grantedResources.add(resource);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && hasPermission(Manifest.permission.CAMERA)) {
                grantedResources.add(resource);
            }
        }

        if (grantedResources.isEmpty()) {
            request.deny();
            return;
        }
        request.grant(grantedResources.toArray(new String[0]));
    }

    private void resolvePendingGeolocationRequest() {
        if (pendingGeolocationCallback == null || pendingGeolocationOrigin == null) {
            return;
        }

        GeolocationPermissions.Callback callback = pendingGeolocationCallback;
        String origin = pendingGeolocationOrigin;
        pendingGeolocationCallback = null;
        pendingGeolocationOrigin = null;
        callback.invoke(origin, hasLocationPermission(), false);
    }

    private void handleJsPermissionRequest(String rawAliases, String callbackId) {
        if (callbackId == null || callbackId.trim().isEmpty()) {
            return;
        }
        if (pendingJsPermissionCallbackId != null) {
            sendJsBridgeCallback(callbackId, bridgeError("permission_request_in_progress"));
            return;
        }

        List<String> aliases = parsePermissionAliases(rawAliases);
        List<String> androidPermissions = androidPermissionsForAliases(aliases);
        List<String> missingPermissions = missingPermissions(androidPermissions);
        if (missingPermissions.isEmpty()) {
            sendJsBridgeCallback(callbackId, permissionStatusJson(aliases));
            return;
        }

        pendingJsPermissionCallbackId = callbackId;
        pendingJsPermissionAliases = aliases;
        requestRuntimePermissions(androidPermissions);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !appPermissionRequestInFlight) {
            resolvePendingJsPermissionRequest();
        }
    }

    private void resolvePendingJsPermissionRequest() {
        if (pendingJsPermissionCallbackId == null) {
            return;
        }

        String callbackId = pendingJsPermissionCallbackId;
        List<String> aliases = pendingJsPermissionAliases;
        pendingJsPermissionCallbackId = null;
        pendingJsPermissionAliases = null;
        sendJsBridgeCallback(callbackId, permissionStatusJson(aliases));
    }

    private List<String> parsePermissionAliases(String rawAliases) {
        List<String> aliases = new ArrayList<>();
        if (rawAliases == null || rawAliases.trim().isEmpty()) {
            aliases.addAll(supportedPermissionAliases());
            return aliases;
        }

        try {
            JSONArray array = new JSONArray(rawAliases);
            for (int index = 0; index < array.length(); index++) {
                addPermissionAlias(aliases, array.optString(index, ""));
            }
        } catch (JSONException exception) {
            String[] parts = rawAliases.split(",");
            for (String part : parts) {
                addPermissionAlias(aliases, part);
            }
        }

        if (aliases.isEmpty()) {
            aliases.addAll(supportedPermissionAliases());
        }
        return aliases;
    }

    private void addPermissionAlias(List<String> aliases, String alias) {
        String normalizedAlias = normalizePermissionAlias(alias);
        if (!normalizedAlias.isEmpty() && !aliases.contains(normalizedAlias)) {
            aliases.add(normalizedAlias);
        }
    }

    private String normalizePermissionAlias(String alias) {
        if (alias == null) {
            return "";
        }
        String normalizedAlias = alias.trim().toLowerCase(Locale.US)
                .replace('-', '_')
                .replace(' ', '_');
        if ("mic".equals(normalizedAlias) || "audio".equals(normalizedAlias)) {
            return "microphone";
        }
        if ("gps".equals(normalizedAlias) || "geolocation".equals(normalizedAlias)) {
            return "location";
        }
        if ("files".equals(normalizedAlias)) {
            return "storage";
        }
        return normalizedAlias;
    }

    private List<String> supportedPermissionAliases() {
        List<String> aliases = new ArrayList<>();
        aliases.add("camera");
        aliases.add("microphone");
        aliases.add("location");
        aliases.add("storage");
        aliases.add("media_images");
        aliases.add("media_video");
        aliases.add("media_audio");
        return aliases;
    }

    private List<String> androidPermissionsForAliases(List<String> aliases) {
        List<String> permissions = new ArrayList<>();
        for (String alias : aliases) {
            for (String permission : androidPermissionsForAlias(alias)) {
                addPermissionIfMissing(permissions, permission);
            }
        }
        return permissions;
    }

    private List<String> androidPermissionsForAlias(String alias) {
        List<String> permissions = new ArrayList<>();
        switch (alias) {
            case "camera":
                permissions.add(Manifest.permission.CAMERA);
                break;
            case "microphone":
                permissions.add(Manifest.permission.RECORD_AUDIO);
                break;
            case "location":
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                break;
            case "storage":
                addStoragePermissions(permissions);
                break;
            case "media_images":
                addMediaPermission(permissions, Manifest.permission.READ_MEDIA_IMAGES);
                break;
            case "media_video":
                addMediaPermission(permissions, Manifest.permission.READ_MEDIA_VIDEO);
                break;
            case "media_audio":
                addMediaPermission(permissions, Manifest.permission.READ_MEDIA_AUDIO);
                break;
            default:
                break;
        }
        return permissions;
    }

    private void addStoragePermissions(List<String> permissions) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            }
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
    }

    private void addMediaPermission(List<String> permissions, String mediaPermission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(mediaPermission);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && Manifest.permission.READ_MEDIA_IMAGES.equals(mediaPermission)) {
                permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            }
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    private JSONObject permissionStatusJson(List<String> aliases) {
        JSONObject result = new JSONObject();
        JSONArray permissions = new JSONArray();
        try {
            result.put("ok", true);
            result.put("permissions", permissions);
            for (String alias : aliases) {
                JSONObject permission = new JSONObject();
                permission.put("name", alias);
                permission.put("supported", isSupportedPermissionAlias(alias));
                permission.put("granted", isPermissionAliasGranted(alias));
                permissions.put(permission);
            }
        } catch (JSONException exception) {
            Log.w(TAG, "Could not build permission status", exception);
        }
        return result;
    }

    private boolean isSupportedPermissionAlias(String alias) {
        return supportedPermissionAliases().contains(alias);
    }

    private boolean isPermissionAliasGranted(String alias) {
        List<String> permissions = androidPermissionsForAlias(alias);
        if (permissions.isEmpty()) {
            return false;
        }
        if ("location".equals(alias)) {
            return hasLocationPermission();
        }
        for (String permission : permissions) {
            if (!hasPermission(permission)) {
                return false;
            }
        }
        return true;
    }

    private void injectLinkViewApi(WebView view) {
        String script = "(function(){"
                + "if(window.LinkView&&window.LinkView.__nativeBridgeVersion>=1){return;}"
                + "var callbacks={};"
                + "var nextId=1;"
                + "function parse(value,fallback){try{return JSON.parse(value);}catch(e){return fallback;}}"
                + "function safeJson(action,fallback){"
                + "try{return parse(action(),fallback||{});}"
                + "catch(e){return {ok:false,error:String(e)};}"
                + "}"
                + "function safeVoid(action){"
                + "try{action();return {ok:true};}"
                + "catch(e){return {ok:false,error:String(e)};}"
                + "}"
                + "function requestNativePermissions(permissions){"
                + "return new Promise(function(resolve,reject){"
                + "var id='cb_'+Date.now()+'_'+(nextId++);"
                + "callbacks[id]={resolve:resolve,reject:reject};"
                + "try{LinkViewNative.requestPermissions(JSON.stringify(permissions||[]),id);}"
                + "catch(e){delete callbacks[id];reject({ok:false,error:String(e)});}"
                + "});"
                + "}"
                + "window.__LinkViewNativeCallback=function(id,payload){"
                + "var callback=callbacks[id];"
                + "if(!callback){return;}"
                + "delete callbacks[id];"
                + "if(payload&&payload.ok===false){callback.reject(payload);}else{callback.resolve(payload);}"
                + "};"
                + "window.LinkView={"
                + "__nativeBridgeVersion:1,"
                + "isNative:true,"
                + "getCapabilities:function(){return safeJson(function(){return LinkViewNative.getCapabilities();},{});},"
                + "getDeviceInfo:function(){return safeJson(function(){return LinkViewNative.getDeviceInfo();},{});},"
                + "getCaptureDefaults:function(){return safeJson(function(){return LinkViewNative.getCaptureDefaults();},{});},"
                + "getPermissions:function(permissions){return safeJson(function(){return LinkViewNative.getPermissionStatus(JSON.stringify(permissions||[]));},{});},"
                + "requestPermissions:function(permissions){return requestNativePermissions(permissions);},"
                + "startCaptureService:function(config){return safeJson(function(){return LinkViewNative.startCaptureService(JSON.stringify(config||{}));},{});},"
                + "stopCaptureService:function(){return safeJson(function(){return LinkViewNative.stopCaptureService();},{});},"
                + "getCurrentUrl:function(){return LinkViewNative.getCurrentUrl();},"
                + "openUrl:function(url){return safeVoid(function(){LinkViewNative.openUrl(String(url||''));});},"
                + "reload:function(){return safeVoid(function(){LinkViewNative.reload();});},"
                + "showUrlManager:function(){return safeVoid(function(){LinkViewNative.showUrlManager();});},"
                + "getLinks:function(){return safeJson(function(){return LinkViewNative.getLinks();},[]);},"
                + "saveLink:function(name,url){return safeJson(function(){return LinkViewNative.saveLink(String(name||''),String(url||''));},{});},"
                + "deleteLink:function(url){return safeJson(function(){return LinkViewNative.deleteLink(String(url||''));},{});},"
                + "toast:function(message){return safeVoid(function(){LinkViewNative.toast(String(message||''));});}"
                + "};"
                + "try{document.dispatchEvent(new CustomEvent('linkviewready',{detail:window.LinkView.getCapabilities()}));}"
                + "catch(e){}"
                + "})();";
        view.evaluateJavascript(script, null);
    }

    private void sendJsBridgeCallback(String callbackId, JSONObject payload) {
        if (webView == null) {
            return;
        }

        runOnUiThread(() -> {
            if (webView != null) {
                String script = "window.__LinkViewNativeCallback&&window.__LinkViewNativeCallback("
                        + JSONObject.quote(callbackId) + "," + payload.toString() + ");";
                webView.evaluateJavascript(script, null);
            }
        });
    }

    private JSONObject bridgeError(String error) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("error", error);
        } catch (JSONException exception) {
            Log.w(TAG, "Could not build bridge error", exception);
        }
        return result;
    }

    private JSONObject bridgeSuccess() {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
        } catch (JSONException exception) {
            Log.w(TAG, "Could not build bridge success", exception);
        }
        return result;
    }

    private JSONObject linkEntryJson(LinkEntry link) {
        JSONObject object = new JSONObject();
        try {
            object.put("name", link.name);
            object.put("url", link.url);
        } catch (JSONException exception) {
            Log.w(TAG, "Could not build link JSON", exception);
        }
        return object;
    }

    private void showUrlEditor() {
        ensureManagedLinksSeeded();

        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(6));
        container.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scrollView.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView helper = new TextView(this);
        helper.setText("احفظ الروابط المهمة وافتحها أو عدلها بسرعة لاحقاً.");
        helper.setTextColor(Color.rgb(77, 88, 106));
        helper.setTextSize(15);
        helper.setGravity(Gravity.RIGHT);
        helper.setPadding(0, 0, 0, dp(12));
        container.addView(helper, matchWrap());

        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("اسم الرابط");
        nameInput.setText(suggestLinkName(activeWebUrl()));
        nameInput.setSelectAllOnFocus(true);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        nameInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        container.addView(nameInput, matchWrap());

        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setText(activeWebUrl());
        urlInput.setSelectAllOnFocus(true);
        urlInput.setHint("http://example.com");
        urlInput.setGravity(Gravity.CENTER_VERTICAL);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams urlParams = matchWrap();
        urlParams.setMargins(0, dp(8), 0, 0);
        container.addView(urlInput, urlParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.setMargins(0, dp(12), 0, dp(12));
        container.addView(actions, actionsParams);

        Button openButton = dialogActionButton("فتح");
        Button saveButton = dialogActionButton("حفظ");
        Button reloadButton = dialogActionButton("تحديث");
        actions.addView(openButton, weightedActionParams());
        actions.addView(saveButton, weightedActionParams());
        actions.addView(reloadButton, weightedActionParams());

        TextView savedTitle = new TextView(this);
        savedTitle.setText("الروابط المحفوظة");
        savedTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        savedTitle.setTextColor(Color.rgb(25, 31, 42));
        savedTitle.setTextSize(17);
        savedTitle.setGravity(Gravity.RIGHT);
        savedTitle.setPadding(0, dp(4), 0, dp(8));
        container.addView(savedTitle, matchWrap());

        LinearLayout linksContainer = new LinearLayout(this);
        linksContainer.setOrientation(LinearLayout.VERTICAL);
        container.addView(linksContainer, matchWrap());

        String[] editingUrl = new String[1];
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("إدارة الروابط")
                .setView(scrollView)
                .setNegativeButton("إغلاق", (d, which) -> {
                    hideKeyboard(nameInput);
                    hideKeyboard(urlInput);
                })
                .create();

        Runnable[] refreshLinks = new Runnable[1];
        refreshLinks[0] = () -> renderManagedLinks(
                linksContainer,
                nameInput,
                urlInput,
                dialog,
                editingUrl,
                refreshLinks[0]
        );

        openButton.setOnClickListener(view -> {
            if (saveManagedLinkFromInputs(nameInput, urlInput, editingUrl)) {
                openUrl(urlInput.getText() != null ? urlInput.getText().toString() : "");
                hideKeyboard(urlInput);
                dialog.dismiss();
            }
        });
        saveButton.setOnClickListener(view -> {
            if (saveManagedLinkFromInputs(nameInput, urlInput, editingUrl)) {
                hideKeyboard(nameInput);
                hideKeyboard(urlInput);
                refreshLinks[0].run();
            }
        });
        reloadButton.setOnClickListener(view -> {
            webView.reload();
            hideKeyboard(urlInput);
            dialog.dismiss();
        });
        urlInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    && saveManagedLinkFromInputs(nameInput, urlInput, editingUrl)) {
                openUrl(urlInput.getText() != null ? urlInput.getText().toString() : "");
                hideKeyboard(urlInput);
                dialog.dismiss();
                return true;
            }
            return false;
        });

        refreshLinks[0].run();
        dialog.show();
    }

    private void handleSecretGestureTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startSecretHold();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPendingSecretHold();
                break;
            default:
                break;
        }
    }

    private void startSecretHold() {
        long now = SystemClock.elapsedRealtime();
        pressStartedWithinSecondWindow =
                secretGestureStep == 1 && now <= secondPressDeadline;

        if (secretGestureStep == 1 && now > secondPressDeadline) {
            resetSecretGesture();
            pressStartedWithinSecondWindow = false;
        }

        pendingSecretHold = () -> {
            completeSecretHold(pressStartedWithinSecondWindow);
            pendingSecretHold = null;
        };
        secretGestureHandler.postDelayed(pendingSecretHold, URL_EDITOR_HOLD_MS);
    }

    private void cancelPendingSecretHold() {
        if (pendingSecretHold != null) {
            secretGestureHandler.removeCallbacks(pendingSecretHold);
            pendingSecretHold = null;
        }
    }

    private void completeSecretHold(boolean startedWithinSecondWindow) {
        if (secretGestureStep == 1 && startedWithinSecondWindow) {
            resetSecretGesture();
            showUrlEditor();
            return;
        }

        secretGestureStep = 1;
        secondPressDeadline =
                SystemClock.elapsedRealtime() + URL_EDITOR_SECOND_PRESS_WINDOW_MS;
        secretGestureHandler.postDelayed(() -> {
            if (secretGestureStep == 1
                    && pendingSecretHold == null
                    && SystemClock.elapsedRealtime() >= secondPressDeadline) {
                resetSecretGesture();
            }
        }, URL_EDITOR_SECOND_PRESS_WINDOW_MS);
    }

    private void resetSecretGesture() {
        secretGestureStep = 0;
        secondPressDeadline = 0L;
        pressStartedWithinSecondWindow = false;
        cancelPendingSecretHold();
    }

    private void openUrl(String value) {
        String url = normalizeUrl(value);
        currentWebUrl = url;
        preferences().edit().putString(PREF_URL, url).apply();
        webView.loadUrl(url);
    }

    private String currentSavedUrl() {
        String savedUrl = preferences().getString(PREF_URL, DEFAULT_WEB_URL);
        if (savedUrl == null || savedUrl.trim().isEmpty()) {
            savedUrl = DEFAULT_WEB_URL;
        }
        return normalizeUrl(savedUrl);
    }

    private String activeWebUrl() {
        if (webView != null && webView.getUrl() != null && !webView.getUrl().trim().isEmpty()) {
            return normalizeUrl(webView.getUrl());
        }
        return currentSavedUrl();
    }

    private void ensureManagedLinksSeeded() {
        List<LinkEntry> links = loadManagedLinks();
        boolean changed = false;
        if (!preferences().contains(PREF_MANAGED_LINKS) || links.isEmpty()) {
            String currentUrl = currentSavedUrl();
            links.add(new LinkEntry(suggestLinkName(currentUrl), currentUrl));
            changed = true;
        }

        if (!containsLinkUrl(links, LOCAL_DEMO_URL)) {
            links.add(new LinkEntry("اختبار LinkView", LOCAL_DEMO_URL));
            changed = true;
        }
        if (changed) {
            persistManagedLinks(links);
        }
    }

    private void renderManagedLinks(
            LinearLayout linksContainer,
            EditText nameInput,
            EditText urlInput,
            AlertDialog dialog,
            String[] editingUrl,
            Runnable refreshLinks
    ) {
        linksContainer.removeAllViews();
        List<LinkEntry> links = loadManagedLinks();
        if (links.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("لا توجد روابط محفوظة بعد.");
            empty.setTextColor(Color.rgb(105, 116, 132));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(14), 0, dp(14));
            linksContainer.addView(empty, matchWrap());
            return;
        }

        for (LinkEntry link : links) {
            linksContainer.addView(createManagedLinkRow(
                    link,
                    nameInput,
                    urlInput,
                    dialog,
                    editingUrl,
                    refreshLinks
            ));
        }
    }

    private View createManagedLinkRow(
            LinkEntry link,
            EditText nameInput,
            EditText urlInput,
            AlertDialog dialog,
            String[] editingUrl,
            Runnable refreshLinks
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(cardBackground(Color.WHITE, Color.rgb(218, 226, 238), dp(10)));
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rowParams);

        TextView name = new TextView(this);
        name.setText(link.name);
        name.setTextColor(Color.rgb(30, 38, 52));
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextSize(16);
        name.setGravity(Gravity.RIGHT);
        row.addView(name, matchWrap());

        TextView url = new TextView(this);
        url.setText(link.url);
        url.setTextColor(Color.rgb(20, 89, 135));
        url.setTextSize(13);
        url.setTextDirection(View.TEXT_DIRECTION_LTR);
        url.setGravity(Gravity.LEFT);
        url.setPadding(0, dp(4), 0, dp(8));
        row.addView(url, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        row.addView(actions, matchWrap());

        Button open = compactButton("فتح");
        Button edit = compactButton("تعديل");
        Button delete = compactButton("حذف");
        actions.addView(open, weightedActionParams());
        actions.addView(edit, weightedActionParams());
        actions.addView(delete, weightedActionParams());

        open.setOnClickListener(view -> {
            openUrl(link.url);
            hideKeyboard(urlInput);
            dialog.dismiss();
        });
        edit.setOnClickListener(view -> {
            editingUrl[0] = link.url;
            nameInput.setText(link.name);
            urlInput.setText(link.url);
            urlInput.requestFocus();
            urlInput.selectAll();
            InputMethodManager inputMethodManager =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            inputMethodManager.showSoftInput(urlInput, InputMethodManager.SHOW_IMPLICIT);
        });
        delete.setOnClickListener(view -> confirmDeleteManagedLink(link, refreshLinks));

        return row;
    }

    private void confirmDeleteManagedLink(LinkEntry link, Runnable refreshLinks) {
        new AlertDialog.Builder(this)
                .setTitle("حذف الرابط")
                .setMessage("هل تريد حذف \"" + link.name + "\" من الروابط المحفوظة؟")
                .setPositiveButton("حذف", (dialog, which) -> {
                    deleteManagedLink(link.url);
                    refreshLinks.run();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private boolean saveManagedLinkFromInputs(
            EditText nameInput,
            EditText urlInput,
            String[] editingUrl
    ) {
        String rawUrl = urlInput.getText() != null ? urlInput.getText().toString() : "";
        String url = normalizeUrl(rawUrl);
        String name = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
        if (name.isEmpty()) {
            name = suggestLinkName(url);
        }

        nameInput.setText(name);
        urlInput.setText(url);
        String originalEditingUrl = editingUrl[0];
        if (originalEditingUrl != null && !normalizeUrl(originalEditingUrl).equals(url)) {
            deleteManagedLink(originalEditingUrl);
        }
        upsertManagedLink(new LinkEntry(name, url));
        editingUrl[0] = originalEditingUrl == null ? null : url;
        Toast.makeText(this, "تم حفظ الرابط", Toast.LENGTH_SHORT).show();
        return true;
    }

    private List<LinkEntry> loadManagedLinks() {
        List<LinkEntry> links = new ArrayList<>();
        String rawLinks = preferences().getString(PREF_MANAGED_LINKS, "[]");
        if (rawLinks == null || rawLinks.trim().isEmpty()) {
            return links;
        }

        try {
            JSONArray array = new JSONArray(rawLinks);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.getJSONObject(index);
                String url = normalizeUrl(object.optString("url", ""));
                String name = object.optString("name", "").trim();
                if (name.isEmpty()) {
                    name = suggestLinkName(url);
                }
                if (!containsLinkUrl(links, url)) {
                    links.add(new LinkEntry(name, url));
                }
            }
        } catch (JSONException exception) {
            Log.w(TAG, "Could not read managed links", exception);
        }
        return links;
    }

    private void persistManagedLinks(List<LinkEntry> links) {
        JSONArray array = new JSONArray();
        int count = Math.min(links.size(), MAX_MANAGED_LINKS);
        for (int index = 0; index < count; index++) {
            LinkEntry link = links.get(index);
            JSONObject object = new JSONObject();
            try {
                object.put("name", link.name);
                object.put("url", link.url);
                array.put(object);
            } catch (JSONException exception) {
                Log.w(TAG, "Could not store managed link " + link.url, exception);
            }
        }
        preferences().edit().putString(PREF_MANAGED_LINKS, array.toString()).apply();
    }

    private void upsertManagedLink(LinkEntry newLink) {
        List<LinkEntry> links = loadManagedLinks();
        List<LinkEntry> updatedLinks = new ArrayList<>();
        updatedLinks.add(newLink);
        for (LinkEntry link : links) {
            if (!link.url.equals(newLink.url)) {
                updatedLinks.add(link);
            }
        }
        persistManagedLinks(updatedLinks);
    }

    private void deleteManagedLink(String url) {
        List<LinkEntry> links = loadManagedLinks();
        List<LinkEntry> updatedLinks = new ArrayList<>();
        String normalizedUrl = normalizeUrl(url);
        for (LinkEntry link : links) {
            if (!link.url.equals(normalizedUrl)) {
                updatedLinks.add(link);
            }
        }
        persistManagedLinks(updatedLinks);
    }

    private boolean containsLinkUrl(List<LinkEntry> links, String url) {
        for (LinkEntry link : links) {
            if (link.url.equals(url)) {
                return true;
            }
        }
        return false;
    }

    private String suggestLinkName(String url) {
        String normalizedUrl = normalizeUrl(url);
        if (normalizedUrl.equals(LOCAL_DEMO_URL)) {
            return "اختبار LinkView";
        }
        if (normalizedUrl.equals(DEFAULT_WEB_URL)) {
            return "الرابط الافتراضي";
        }

        String withoutProtocol = normalizedUrl
                .replaceFirst("^https?://", "")
                .replaceFirst("/$", "");
        int slashIndex = withoutProtocol.indexOf('/');
        if (slashIndex > 0) {
            withoutProtocol = withoutProtocol.substring(0, slashIndex);
        }
        return withoutProtocol.isEmpty() ? "رابط محفوظ" : withoutProtocol;
    }

    private void retryMainFrameIfNeeded(
            WebView view,
            WebResourceRequest request,
            WebResourceError error
    ) {
        CharSequence description = error.getDescription();
        if (description == null
                || !description.toString().contains("ERR_CONTENT_LENGTH_MISMATCH")) {
            return;
        }
        if (mainFrameRetryCount >= 2) {
            return;
        }

        mainFrameRetryCount += 1;
        String retryUrl = request.getUrl().toString();
        Log.w(TAG, "Retrying " + retryUrl + " after content length mismatch");
        view.postDelayed(() -> view.loadUrl(retryUrl), 900L);
    }

    private String normalizeUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_WEB_URL;
        }
        if (trimmed.startsWith("http://")
                || trimmed.startsWith("https://")
                || trimmed.startsWith("file:///android_asset/")) {
            return trimmed;
        }
        return "http://" + trimmed;
    }

    private void hideKeyboard(EditText input) {
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void configureSystemBars() {
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);

        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private GradientDrawable cardBackground(int fillColor, int strokeColor, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private Button dialogActionButton(String text) {
        Button button = compactButton(text);
        button.setTextSize(15);
        return button;
    }

    private Button compactButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private LinearLayout.LayoutParams weightedActionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class LinkViewBridge {
        @JavascriptInterface
        public String getCapabilities() {
            JSONObject result = new JSONObject();
            JSONArray permissions = new JSONArray();
            JSONArray apis = new JSONArray();
            try {
                result.put("ok", true);
                result.put("name", "LinkView");
                result.put("bridgeVersion", 1);
                result.put("androidSdk", Build.VERSION.SDK_INT);
                for (String alias : supportedPermissionAliases()) {
                    permissions.put(alias);
                }
                result.put("permissions", permissions);
                apis.put("getCapabilities");
                apis.put("getDeviceInfo");
                apis.put("getCaptureDefaults");
                apis.put("getPermissions");
                apis.put("requestPermissions");
                apis.put("startCaptureService");
                apis.put("stopCaptureService");
                apis.put("getCurrentUrl");
                apis.put("openUrl");
                apis.put("reload");
                apis.put("showUrlManager");
                apis.put("getLinks");
                apis.put("saveLink");
                apis.put("deleteLink");
                apis.put("toast");
                result.put("apis", apis);
            } catch (JSONException exception) {
                Log.w(TAG, "Could not build bridge capabilities", exception);
            }
            return result.toString();
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            JSONObject result = new JSONObject();
            try {
                result.put("ok", true);
                result.put("manufacturer", Build.MANUFACTURER);
                result.put("brand", Build.BRAND);
                result.put("model", Build.MODEL);
                result.put("device", Build.DEVICE);
                result.put("androidSdk", Build.VERSION.SDK_INT);
                result.put("androidRelease", Build.VERSION.RELEASE);
            } catch (JSONException exception) {
                Log.w(TAG, "Could not build device info", exception);
            }
            return result.toString();
        }

        @JavascriptInterface
        public String getCaptureDefaults() {
            JSONObject result = bridgeSuccess();
            try {
                result.put("server_url", BuildConfig.LINKVIEW_DEFAULT_SERVER_URL);
                result.put("api_token", BuildConfig.LINKVIEW_DEFAULT_API_TOKEN);
                result.put("device_id", BuildConfig.LINKVIEW_DEFAULT_DEVICE_ID);
                result.put("location_interval_ms", 10000);
                result.put("audio_chunk_ms", 10000);
                result.put("photo_interval_ms", 15000);
            } catch (JSONException exception) {
                Log.w(TAG, "Could not build capture defaults", exception);
            }
            return result.toString();
        }

        @JavascriptInterface
        public String getPermissionStatus(String rawAliases) {
            return permissionStatusJson(parsePermissionAliases(rawAliases)).toString();
        }

        @JavascriptInterface
        public String startCaptureService(String rawConfig) {
            if (!hasPermission(Manifest.permission.CAMERA)
                    || !hasPermission(Manifest.permission.RECORD_AUDIO)
                    || !hasLocationPermission()) {
                requestRuntimePermissions(androidPermissionsForAliases(parsePermissionAliases(
                        "[\"camera\",\"microphone\",\"location\"]"
                )));
                return bridgeError("permissions_required").toString();
            }

            JSONObject config;
            try {
                config = rawConfig == null || rawConfig.trim().isEmpty()
                        ? new JSONObject()
                        : new JSONObject(rawConfig);
            } catch (JSONException exception) {
                return bridgeError("invalid_config").toString();
            }

            String serverUrl = config.optString("server_url", BuildConfig.LINKVIEW_DEFAULT_SERVER_URL).trim();
            if (serverUrl.isEmpty()) {
                return bridgeError("server_url_required").toString();
            }

            Intent intent = new Intent(MainActivity.this, CaptureUploadService.class);
            intent.setAction(CaptureUploadService.ACTION_START);
            intent.putExtra(CaptureUploadService.EXTRA_SERVER_URL, serverUrl);
            intent.putExtra(
                    CaptureUploadService.EXTRA_API_TOKEN,
                    config.optString("api_token", BuildConfig.LINKVIEW_DEFAULT_API_TOKEN)
            );
            intent.putExtra(
                    CaptureUploadService.EXTRA_DEVICE_ID,
                    config.optString("device_id", BuildConfig.LINKVIEW_DEFAULT_DEVICE_ID)
            );
            intent.putExtra(
                    CaptureUploadService.EXTRA_LOCATION_INTERVAL_MS,
                    config.optLong("location_interval_ms", 10000L)
            );
            intent.putExtra(
                    CaptureUploadService.EXTRA_AUDIO_CHUNK_MS,
                    config.optLong("audio_chunk_ms", 10000L)
            );
            intent.putExtra(
                    CaptureUploadService.EXTRA_PHOTO_INTERVAL_MS,
                    config.optLong("photo_interval_ms", 15000L)
            );

            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            });
            return bridgeSuccess().toString();
        }

        @JavascriptInterface
        public String stopCaptureService() {
            Intent intent = new Intent(MainActivity.this, CaptureUploadService.class);
            intent.setAction(CaptureUploadService.ACTION_STOP);
            runOnUiThread(() -> startService(intent));
            return bridgeSuccess().toString();
        }

        @JavascriptInterface
        public void requestPermissions(String rawAliases, String callbackId) {
            runOnUiThread(() -> handleJsPermissionRequest(rawAliases, callbackId));
        }

        @JavascriptInterface
        public String getCurrentUrl() {
            return currentWebUrl != null ? currentWebUrl : currentSavedUrl();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> MainActivity.this.openUrl(url));
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.reload();
                }
            });
        }

        @JavascriptInterface
        public void showUrlManager() {
            runOnUiThread(MainActivity.this::showUrlEditor);
        }

        @JavascriptInterface
        public String getLinks() {
            ensureManagedLinksSeeded();
            JSONArray result = new JSONArray();
            for (LinkEntry link : loadManagedLinks()) {
                result.put(linkEntryJson(link));
            }
            return result.toString();
        }

        @JavascriptInterface
        public String saveLink(String name, String url) {
            String normalizedUrl = normalizeUrl(url);
            String normalizedName = name == null ? "" : name.trim();
            if (normalizedName.isEmpty()) {
                normalizedName = suggestLinkName(normalizedUrl);
            }
            LinkEntry link = new LinkEntry(normalizedName, normalizedUrl);
            upsertManagedLink(link);

            JSONObject result = bridgeSuccess();
            try {
                result.put("link", linkEntryJson(link));
            } catch (JSONException exception) {
                Log.w(TAG, "Could not build save link response", exception);
            }
            return result.toString();
        }

        @JavascriptInterface
        public String deleteLink(String url) {
            deleteManagedLink(url);
            return bridgeSuccess().toString();
        }

        @JavascriptInterface
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(
                    MainActivity.this,
                    message == null ? "" : message,
                    Toast.LENGTH_SHORT
            ).show());
        }
    }

    private static class LinkEntry {
        final String name;
        final String url;

        LinkEntry(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
