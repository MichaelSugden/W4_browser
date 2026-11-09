package com.digitlsky.watchbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal browser for round Wear OS watches (Galaxy Watch 4 Classic).
 *
 * Two engines:
 *  - TEXT (default): fetches pages itself and shows text + links. Tiny RAM/CPU, no WebView needed.
 *  - FULL: Mozilla GeckoView, created only on demand, for pages that need JavaScript.
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://html.duckduckgo.com/html/";
    private static final String SEARCH = "https://html.duckduckgo.com/html/?q=";

    private static final class Entry {
        final String url;
        int scrollY;
        Entry(String u) { url = u; }
    }

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private float scrollFactor;

    private FrameLayout content;
    private ScrollView textScroll;
    private TextView textView;
    private GeckoMode gecko;
    private ProgressBar progress;
    private View overlay;
    private EditText urlBox;
    private TextView modeBtn, jsBtn;

    private boolean full, js;
    private int loadGen;

    // Text-mode history
    private final ArrayList<Entry> back = new ArrayList<>();
    private final ArrayList<Entry> fwd = new ArrayList<>();
    private Entry current;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("p", MODE_PRIVATE);
        js = prefs.getBoolean("js", true);
        full = prefs.getBoolean("full", false);
        scrollFactor = ViewConfiguration.get(this).getScaledVerticalScrollFactor();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // --- Text mode view: round-screen padding so nothing is clipped by the circle ---
        int w = getResources().getDisplayMetrics().widthPixels;
        textView = new TextView(this);
        textView.setTextColor(0xFFE0E0E0);
        textView.setLinkTextColor(0xFF8AB4F8);
        textView.setTextSize(14);
        textView.setLineSpacing(0, 1.15f);
        textView.setPadding(w * 11 / 100, w * 16 / 100, w * 11 / 100, w * 30 / 100);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setHighlightColor(0x408AB4F8);
        textScroll = new ScrollView(this);
        textScroll.setVerticalScrollBarEnabled(false);
        textScroll.setFocusable(true);
        textScroll.setFocusableInTouchMode(true);
        textScroll.addView(textView, new FrameLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(80), dp(4), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pl.topMargin = dp(8);
        root.addView(progress, pl);

        TextView menuBtn = pill("☰", 0xCC333333);
        menuBtn.setOnClickListener(v -> showOverlay(true));
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(dp(40), dp(26), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ml.bottomMargin = dp(6);
        root.addView(menuBtn, ml);

        overlay = buildOverlay();
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        String start = prefs.getString("last", HOME);
        if (full) switchToFull(start); else switchToText(start);
    }

    // ================= Mode switching =================

    private void switchToText(String url) {
        full = false;
        prefs.edit().putBoolean("full", false).apply();
        if (gecko != null) {             // free the heavy engine's page memory
            content.removeView(gecko.view);
            gecko.close();
            gecko = null;
        }
        if (textScroll.getParent() == null) content.addView(textScroll, new FrameLayout.LayoutParams(-1, -1));
        back.clear();
        fwd.clear();
        current = null;
        navigateText(url, true);
        refreshLabels();
    }

    private void switchToFull(String url) {
        full = true;
        prefs.edit().putBoolean("full", true).apply();
        content.removeView(textScroll);
        try {
            if (gecko == null) {
                gecko = new GeckoMode(this, js, new GeckoMode.Listener() {
                    @Override
                    public void onProgress(int p) {
                        progress.setIndeterminate(false);
                        progress.setProgress(p);
                        progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                    }

                    @Override
                    public void onUrl(String u) {
                        prefs.edit().putString("last", u).apply();
                    }
                });
                content.addView(gecko.view, new FrameLayout.LayoutParams(-1, -1));
            }
            gecko.load(url);
        } catch (Throwable t) {
            gecko = null;
            if (textScroll.getParent() == null) content.addView(textScroll, new FrameLayout.LayoutParams(-1, -1));
            full = false;
            prefs.edit().putBoolean("full", false).apply();
            showText(null, "Full mode failed to start", t.toString());
        }
        refreshLabels();
    }

    private String currentUrl() {
        if (full && gecko != null) return gecko.url;
        return current != null ? current.url : HOME;
    }

    // ================= Text mode =================

    private void navigateText(String url, boolean pushHistory) {
        if (pushHistory && current != null) {
            current.scrollY = textScroll.getScrollY();
            back.add(current);
            fwd.clear();
        }
        current = new Entry(url);
        loadText(current, 0);
    }

    private void loadText(Entry e, int restoreScroll) {
        final int gen = ++loadGen;
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        net.execute(() -> {
            TextEngine.Page p = null;
            String err = null;
            try {
                p = TextEngine.load(e.url, url -> ui.post(() -> navigateText(url, true)));
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final TextEngine.Page page = p;
            final String error = err;
            ui.post(() -> {
                if (gen != loadGen || full) return;  // a newer load or mode switch won
                progress.setVisibility(View.GONE);
                if (page == null) {
                    showText(null, "Couldn't load page", e.url + "\n\n" + error + "\n\nCheck Wi-Fi/LTE, or try ☰ → Full.");
                    return;
                }
                prefs.edit().putString("last", page.url).apply();
                showText(page.title, page.title, page.body);
                textScroll.post(() -> textScroll.scrollTo(0, restoreScroll));
            });
        });
    }

    private void showText(String unused, String title, CharSequence body) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (title != null && !title.isEmpty()) {
            sb.append(title);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(1.1f), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("\n\n");
        }
        sb.append(body);
        textView.setText(sb);
        textScroll.scrollTo(0, 0);
        textScroll.requestFocus();
    }

    private void goBack() {
        if (full && gecko != null) { gecko.back(); return; }
        if (back.isEmpty()) return;
        current.scrollY = textScroll.getScrollY();
        fwd.add(current);
        current = back.remove(back.size() - 1);
        loadText(current, current.scrollY);
    }

    private void goForward() {
        if (full && gecko != null) { gecko.forward(); return; }
        if (fwd.isEmpty()) return;
        current.scrollY = textScroll.getScrollY();
        back.add(current);
        current = fwd.remove(fwd.size() - 1);
        loadText(current, current.scrollY);
    }

    private void reload() {
        if (full && gecko != null) gecko.reload();
        else if (current != null) loadText(current, textScroll.getScrollY());
    }

    private void open(String url) {
        if (full && gecko != null) gecko.load(url);
        else navigateText(url, true);
    }

    // ================= Rotating bezel =================

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (overlay.getVisibility() != View.VISIBLE
                && ev.getAction() == MotionEvent.ACTION_SCROLL
                && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            int delta = Math.round(-ev.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor * 1.5f);
            if (full && gecko != null) gecko.scrollBy(delta);
            else textScroll.smoothScrollBy(0, delta);
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    // ================= Menu overlay =================

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
                btn("◀", v -> { goBack(); showOverlay(false); }),
                btn("⟳", v -> { reload(); showOverlay(false); }),
                btn("▶", v -> { goForward(); showOverlay(false); })));

        modeBtn = btn("", v -> {
            String u = currentUrl();
            showOverlay(false);
            if (full) switchToText(u); else switchToFull(u);
        });
        col.addView(row(btn("Home", v -> { open(HOME); showOverlay(false); }), modeBtn));

        jsBtn = btn("", v -> {
            js = !js;
            prefs.edit().putBoolean("js", js).apply();
            if (gecko != null) { gecko.setJs(js); gecko.reload(); }
            refreshLabels();
        });
        col.addView(row(jsBtn, btn("Clear", v -> confirmClear())));
        refreshLabels();
        return col;
    }

    private void refreshLabels() {
        if (modeBtn == null) return;
        modeBtn.setText(full ? "Text" : "Full");
        jsBtn.setText(js ? "JS ✓" : "JS ✗");
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("Clear history, cookies & cache?")
                .setPositiveButton("Yes", (d, w) -> {
                    back.clear();
                    fwd.clear();
                    GeckoMode.clearAll();
                    prefs.edit().remove("last").apply();
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void showOverlay(boolean show) {
        overlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            urlBox.setText(currentUrl());
        } else {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
            if (!full) textScroll.requestFocus();
        }
    }

    private void go(String in) {
        in = in.trim();
        if (in.isEmpty()) return;
        String url;
        if (in.matches("(?i)^https?://.*")) url = in;
        else if (!in.contains(" ") && in.contains(".")) url = "https://" + in;
        else url = SEARCH + android.net.Uri.encode(in);
        showOverlay(false);
        open(url);
    }

    // ================= UI helpers =================

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            r.addView(v, lp);
        }
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

    // ================= Lifecycle: save battery & RAM =================

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (overlay.getVisibility() == View.VISIBLE) { showOverlay(false); return true; }
            if (full && gecko != null && gecko.canBack) { gecko.back(); return true; }
            if (!full && !back.isEmpty()) { goBack(); return true; }
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gecko != null) gecko.setActive(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gecko != null) gecko.setActive(true);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Under memory pressure in the background, drop the heavy engine entirely.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && gecko != null && full) {
            content.removeView(gecko.view);
            gecko.close();
            gecko = null;
            pendingFullReload = true;
        }
    }

    private boolean pendingFullReload;

    @Override
    protected void onStart() {
        super.onStart();
        if (pendingFullReload) {
            pendingFullReload = false;
            switchToFull(prefs.getString("last", HOME));
        }
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        if (gecko != null) gecko.close();
        super.onDestroy();
    }
}
