package com.aes.linkview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String DEFAULT_WEB_URL = "http://rtxa.duckdns.org:8000";
    private static final String PREFS_NAME = "webview_settings";
    private static final String PREF_URL = "current_url";
    private static final String PREF_INTRO_SEEN = "intro_seen";
    private static final String TAG = "LinkViewWebView";
    private static final long URL_EDITOR_HOLD_MS = 1000L;
    private static final long URL_EDITOR_SECOND_PRESS_WINDOW_MS = 2000L;

    private final Handler secretGestureHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private String lastLoadingUrl;
    private int mainFrameRetryCount;
    private int secretGestureStep;
    private long secondPressDeadline;
    private boolean pressStartedWithinSecondWindow;
    private Runnable pendingSecretHold;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();

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
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
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

    private void showUrlEditor() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(webView.getUrl() != null ? webView.getUrl() : currentSavedUrl());
        input.setSelectAllOnFocus(true);
        input.setHint("http://example.com");
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("تغيير الرابط")
                .setMessage("اضغط ثانية واحدة، اترك، ثم اضغط ثانية واحدة خلال ثانيتين.")
                .setView(input)
                .setPositiveButton("فتح", (d, which) -> {
                    openUrl(input.getText() != null ? input.getText().toString() : "");
                    hideKeyboard(input);
                })
                .setNegativeButton("إلغاء", (d, which) -> hideKeyboard(input))
                .setNeutralButton("تحديث", (d, which) -> {
                    webView.reload();
                    hideKeyboard(input);
                })
                .create();

        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openUrl(input.getText() != null ? input.getText().toString() : "");
                hideKeyboard(input);
                dialog.dismiss();
                return true;
            }
            return false;
        });

        dialog.setOnShowListener(d -> {
            input.requestFocus();
            input.post(() -> {
                InputMethodManager inputMethodManager =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                inputMethodManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            });
        });
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
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
