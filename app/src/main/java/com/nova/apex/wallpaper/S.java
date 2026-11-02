package com.nova.apex.wallpaper;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class S extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new Engine() {
            private WebView v;
            private final Handler h = new Handler(Looper.getMainLooper());
            private boolean vis = false;
            private int w = 1080, ht = 2400;

            @Override
            public void onCreate(SurfaceHolder sh) {
                super.onCreate(sh);
                setTouchEventsEnabled(true);
                h.post(() -> {
                    v = new WebView(S.this);
                    WebSettings s = v.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setAllowFileAccess(true);
                    s.setMediaPlaybackRequiresUserGesture(false);
                    v.setWebChromeClient(new WebChromeClient());
                    v.setWebViewClient(new WebViewClient());
                    v.loadUrl("file:///android_asset/a.html?mode=wallpaper");
                    v.layout(0, 0, w, ht);
                });
            }

            @Override
            public void onSurfaceChanged(SurfaceHolder sh, int f, int width, int height) {
                w = width;
                ht = height;
                h.post(() -> {
                    if (v != null) v.layout(0, 0, w, ht);
                });
            }

            @Override
            public void onVisibilityChanged(boolean b) {
                vis = b;
                h.post(() -> {
                    if (v != null) {
                        if (vis) {
                            v.onResume();
                            v.evaluateJavascript("if(window.syncConfig)window.syncConfig();", null);
                            l();
                        } else {
                            v.onPause();
                        }
                    }
                });
            }

            void l() {
                if (!vis) return;
                SurfaceHolder sh = getSurfaceHolder();
                Canvas c = null;
                try {
                    c = sh.lockCanvas();
                    if (c != null && v != null) v.draw(c);
                } catch (Exception e) {
                } finally {
                    if (c != null) sh.unlockCanvasAndPost(c);
                }
                h.postDelayed(this::l, 16);
            }

            @Override
            public void onTouchEvent(MotionEvent e) {
                h.post(() -> {
                    if (v != null) v.dispatchTouchEvent(e);
                });
            }

            @Override
            public void onDestroy() {
                super.onDestroy();
                h.post(() -> {
                    if (v != null) {
                        v.destroy();
                        v = null;
                    }
                });
            }
        };
    }
}