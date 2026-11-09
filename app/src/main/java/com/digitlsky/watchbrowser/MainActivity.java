package com.digitlsky.watchbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Minimal browser for round Wear OS watches.
 * - One WebView, no AndroidX, no libraries (APK ~30 KB).
 * - Rotating bezel / crown scrolls the page.
 * - Data-saver toggles: images, JavaScript, reader mode, round padding.
 * - Pauses all timers when off-screen to save battery.
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://html.duckduckgo.com/html/";
    private static final String SEARCH = "https://html.duckduckgo.com/html/?q=";

    private WebView web;
    private View overlay;
    private EditText urlBox;
    private ProgressBar progress;
    private TextView toggleImg, toggleJs, toggleRound;
    private SharedPreferences prefs;
    private float scrollFactor;

    private boolean images, js, round;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("p", MODE_PRIVATE);
        images = prefs.getBoolean("img", false);   // images OFF by default: biggest saver
        js = prefs.getBoolean("js", true);
        round = prefs.getBoolean("round", true);
        scrollFactor = ViewConfiguration.get(this).getScaledVerticalScrollFactor();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        try {
            web = new WebView(this);
        } catch (Throwable t) {
            TextView err = new TextView(this);
            err.setText("No WebView on this watch.\nInstall/enable Android System WebView.");
            err.setGravity(Gravity.CENTER);
            err.setTextColor(Color.WHITE);
            err.setPadding(40, 40, 40, 40);
            root.addView(err);
            setContentView(root);
            return;
        }

        setupWebView();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(80), dp(3), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pl.topMargin = dp(6);
        root.addView(progress, pl);

        // Small floating menu button at the bottom (in the safe area of the circle)
        TextView menuBtn = pill("☰", 0xCC333333);
        menuBtn.setOnClickListener(v -> showOverlay(true));
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(dp(36), dp(24), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ml.bottomMargin = dp(6);
        root.addView(menuBtn, ml);

        overlay = buildOverlay();
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        String start = prefs.getString("last", HOME);
        if (b != null) web.restoreState(b); else web.loadUrl(start);
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(js);
        s.setLoadsImagesAutomatically(images);
        s.setBlockNetworkImage(!images);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(90);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Ask sites for their mobile version
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setBackgroundColor(Color.WHITE);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                return !(u.startsWith("http://") || u.startsWith("https://")); // ignore intents, market:// etc.
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                progress.setVisibility(View.GONE);
                prefs.edit().putString("last", url).apply();
                if (round) applyRoundPadding();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
            }
        });
    }

    /** Pads the page so text isn't cut off by the circular screen edges. */
    private void applyRoundPadding() {
        web.evaluateJavascript(
                "(function(){var s=document.getElementById('__rnd');if(!s){s=document.createElement('style');s.id='__rnd';"
                        + "s.textContent='html{padding:12vw 9vw 22vw 9vw!important;box-sizing:border-box}"
                        + "img,video,iframe,table{max-width:100%!important;height:auto}';"
                        + "(document.head||document.documentElement).appendChild(s);}})()", null);
    }

    /** Strips the page down to readable text — huge savings on heavy pages. */
    private static final String READER_JS =
            "(function(){var m=document.querySelector('article')||document.querySelector('main')||document.body;"
                    + "var t=document.title;var c=m.cloneNode(true);"
                    + "c.querySelectorAll('script,style,iframe,video,svg,nav,aside,footer,form,noscript,button').forEach(function(e){e.remove()});"
                    + "c.querySelectorAll('*').forEach(function(e){e.removeAttribute('style');e.removeAttribute('class')});"
                    + "document.head.innerHTML='<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                    + "<style>body{background:#000;color:#ddd;font:16px/1.45 sans-serif;padding:14vw 10vw 25vw}"
                    + "a{color:#8ab4f8}img{max-width:100%;height:auto}h1{font-size:19px}</style>';"
                    + "document.body.innerHTML='<h1>'+t+'</h1>'+c.innerHTML;window.scrollTo(0,0);})()";

    // ---------- Rotating bezel / crown ----------
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (web != null && overlay.getVisibility() != View.VISIBLE
                && ev.getAction() == MotionEvent.ACTION_SCROLL
                && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor * 1.5f;
            web.scrollBy(0, Math.round(delta));
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    // ---------- Overlay menu ----------
    private View buildOverlay() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(0xF0000000);
        col.setPadding(dp(22), dp(18), dp(22), dp(18));
        col.setOnClickListener(v -> showOverlay(false));

        urlBox = new EditText(this);
        urlBox.setSingleLine(true);
        urlBox.setHint("Search or URL");
        urlBox.setTextSize(13);
        urlBox.setTextColor(Color.WHITE);
        urlBox.setHintTextColor(0xFF888888);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBox.setSelectAllOnFocus(true);
        urlBox.setOnEditorActionListener((v, id, e) -> {
            go(urlBox.getText().toString());
            return true;
        });
        col.addView(urlBox, new LinearLayout.LayoutParams(-1, -2));

        col.addView(row(
                btn("◀", v -> { if (web.canGoBack()) web.goBack(); showOverlay(false); }),
                btn("⟳", v -> { web.reload(); showOverlay(false); }),
                btn("▶", v -> { if (web.canGoForward()) web.goForward(); showOverlay(false); })));

        col.addView(row(
                btn("Home", v -> { web.loadUrl(HOME); showOverlay(false); }),
                btn("Read", v -> { web.evaluateJavascript(READER_JS, null); showOverlay(false); })));

        toggleImg = btn("", v -> { images = !images; applyToggles(); web.reload(); });
        toggleJs = btn("", v -> { js = !js; applyToggles(); web.reload(); });
        toggleRound = btn("", v -> { round = !round; applyToggles(); web.reload(); });
        col.addView(row(toggleImg, toggleJs));
        col.addView(row(toggleRound, btn("Clear", v -> confirmClear())));
        refreshToggleLabels();
        return col;
    }

    private void applyToggles() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(js);
        s.setLoadsImagesAutomatically(images);
        s.setBlockNetworkImage(!images);
        prefs.edit().putBoolean("img", images).putBoolean("js", js).putBoolean("round", round).apply();
        refreshToggleLabels();
    }

    private void refreshToggleLabels() {
        toggleImg.setText(images ? "Img ✓" : "Img ✗");
        toggleJs.setText(js ? "JS ✓" : "JS ✗");
        toggleRound.setText(round ? "Fit ✓" : "Fit ✗");
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("Clear cache, cookies & history?")
                .setPositiveButton("Yes", (d, w) -> {
                    web.clearCache(true);
                    web.clearHistory();
                    CookieManager.getInstance().removeAllCookies(null);
                    getSharedPreferences("p", MODE_PRIVATE).edit().remove("last").apply();
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void showOverlay(boolean show) {
        overlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            urlBox.setText(web.getUrl());
        } else {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
            web.requestFocus();
        }
    }

    private void go(String in) {
        in = in.trim();
        if (in.isEmpty()) return;
        String url;
        if (in.matches("^https?://.*")) url = in;
        else if (!in.contains(" ") && in.contains(".")) url = "https://" + in;
        else url = SEARCH + android.net.Uri.encode(in);
        web.loadUrl(url);
        showOverlay(false);
    }

    // ---------- Tiny UI helpers ----------
    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            r.addView(v, lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        r.setLayoutParams(rl);
        return r;
    }

    private TextView btn(String label, View.OnClickListener l) {
        TextView t = pill(label, 0xFF2A2A2A);
        t.setOnClickListener(l);
        return t;
    }

    private TextView pill(String label, int color) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(16));
        t.setBackground(g);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- Lifecycle: save battery & RAM ----------
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (overlay != null && overlay.getVisibility() == View.VISIBLE) { showOverlay(false); return true; }
            if (web != null && web.canGoBack()) { web.goBack(); return true; }
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) { web.onPause(); web.pauseTimers(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.resumeTimers(); }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (web != null && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) web.clearCache(false);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
