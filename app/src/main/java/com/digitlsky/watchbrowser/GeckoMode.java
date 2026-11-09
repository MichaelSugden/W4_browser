package com.digitlsky.watchbrowser;

import android.content.Context;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.ScreenLength;
import org.mozilla.geckoview.StorageController;

/**
 * Full browser mode using Mozilla's GeckoView engine (bundled, because Wear OS has no WebView).
 * Only created when the user switches to Full mode, so Text mode never pays its memory cost.
 */
final class GeckoMode {

    interface Listener {
        void onProgress(int percent);
        void onUrl(String url);
    }

    private static GeckoRuntime runtime;   // one per process

    final GeckoView view;
    final GeckoSession session;
    String url = "";
    boolean canBack, canForward;

    GeckoMode(Context ctx, boolean js, Listener l) {
        if (runtime == null) {
            GeckoRuntimeSettings s = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(js)
                    .contentBlocking(new ContentBlocking.Settings.Builder()
                            .antiTracking(ContentBlocking.AntiTracking.STRICT)  // blocks ads/trackers = less CPU & data
                            .build())
                    .build();
            runtime = GeckoRuntime.create(ctx.getApplicationContext(), s);
        }

        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build());

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String u) {
                url = u;
                l.onUrl(u);
                l.onProgress(5);
            }

            @Override
            public void onProgressChange(GeckoSession s, int p) {
                l.onProgress(p);
            }

            @Override
            public void onPageStop(GeckoSession s, boolean ok) {
                l.onProgress(100);
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession s, boolean b) {
                canBack = b;
            }

            @Override
            public void onCanGoForward(GeckoSession s, boolean b) {
                canForward = b;
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                session.loadUri(uri);   // open "new window" links in the same view
                return null;
            }
        });

        session.open(runtime);
        view = new GeckoView(ctx);
        view.setSession(session);
    }

    void load(String u) { session.loadUri(u); }
    void back() { session.goBack(); }
    void forward() { session.goForward(); }
    void reload() { session.reload(); }

    void scrollBy(int dy) {
        view.getPanZoomController().scrollBy(ScreenLength.zero(), ScreenLength.fromPixels(dy));
    }

    void setJs(boolean js) { runtime.getSettings().setJavaScriptEnabled(js); }

    void setActive(boolean active) { session.setActive(active); }

    static void clearAll() {
        if (runtime != null) runtime.getStorageController().clearData(StorageController.ClearFlags.ALL);
    }

    void close() {
        view.releaseSession();
        session.close();
    }
}
