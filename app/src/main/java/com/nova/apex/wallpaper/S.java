package com.nova.apex.wallpaper;

import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class S extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new E();
    }

    class E extends Engine {
        private final Handler h = new Handler(Looper.getMainLooper());
        private WebView v;
        private VirtualDisplay vd;
        private Presentation pres;
        private boolean vis = false;
        private boolean fallback = false;
        private int w = 1080, ht = 2400;
        private final Runnable drawLoop = this::drawFrame;

        @Override
        public void onCreate(SurfaceHolder sh) {
            super.onCreate(sh);
            setTouchEventsEnabled(true);
        }

        private WebView makeWebView(Context c) {
            WebView wv = new WebView(c);
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(true);
            wv.setWebViewClient(new WebViewClient());
            wv.setBackgroundColor(Color.parseColor("#060913"));
            wv.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void openAssistant() {
                    try {
                        Intent i = new Intent(S.this, M.class)
                                .setAction(M.ACTION_TALK)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(i);
                    } catch (Exception ignored) {
                    }
                }
            }, "Android");
            wv.loadUrl("file:///android_asset/a.html?mode=wallpaper");
            return wv;
        }

        // Draw the page straight onto the wallpaper surface through a private virtual display.
        // This uses the GPU and is much smoother than copying the page by hand every frame.
        private void setup(SurfaceHolder sh) {
            if (fallback) {
                layoutFallback();
                return;
            }
            int dpi = getResources().getDisplayMetrics().densityDpi;
            try {
                if (vd == null) {
                    DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                    vd = dm.createVirtualDisplay("NovaApexWallpaper", w, ht, dpi, sh.getSurface(), 0);
                    pres = new Presentation(S.this, vd.getDisplay(), android.R.style.Theme_DeviceDefault_NoActionBar);
                    if (pres.getWindow() != null) pres.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                    v = makeWebView(pres.getContext());
                    pres.setContentView(v);
                    pres.show();
                } else {
                    vd.setSurface(sh.getSurface());
                    vd.resize(w, ht, dpi);
                }
            } catch (Exception e) {
                // Some phones don't allow this; fall back to the slower copy method
                releasePresentation();
                fallback = true;
                v = makeWebView(S.this);
                layoutFallback();
                if (vis) startDrawLoop();
            }
        }

        private void releasePresentation() {
            try {
                if (pres != null) pres.dismiss();
            } catch (Exception ignored) {
            }
            pres = null;
            if (vd != null) vd.release();
            vd = null;
            if (v != null) v.destroy();
            v = null;
        }

        private void layoutFallback() {
            if (v == null) return;
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ht, View.MeasureSpec.EXACTLY));
            v.layout(0, 0, w, ht);
        }

        private void startDrawLoop() {
            h.removeCallbacks(drawLoop);
            h.post(drawLoop);
        }

        private void drawFrame() {
            if (!vis || !fallback || v == null) return;
            SurfaceHolder sh = getSurfaceHolder();
            Canvas c = null;
            try {
                c = sh.lockCanvas();
                if (c != null) v.draw(c);
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try {
                        sh.unlockCanvasAndPost(c);
                    } catch (Exception ignored) {
                    }
                }
            }
            h.postDelayed(drawLoop, 33);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder sh, int format, int width, int height) {
            super.onSurfaceChanged(sh, format, width, height);
            w = width;
            ht = height;
            setup(sh);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder sh) {
            h.removeCallbacks(drawLoop);
            if (vd != null) vd.setSurface(null);
            super.onSurfaceDestroyed(sh);
        }

        @Override
        public void onVisibilityChanged(boolean b) {
            vis = b;
            if (v == null) return;
            if (b) {
                v.onResume();
                v.evaluateJavascript("window.syncConfig&&syncConfig();window.setPaused&&setPaused(false)", null);
                if (fallback) startDrawLoop();
            } else {
                // Stop animating while hidden so the wallpaper doesn't drain the battery
                v.evaluateJavascript("window.setPaused&&setPaused(true)", null);
                h.removeCallbacks(drawLoop);
                v.onPause();
            }
        }

        @Override
        public void onTouchEvent(MotionEvent e) {
            if (v != null) v.dispatchTouchEvent(e);
        }

        @Override
        public void onDestroy() {
            h.removeCallbacks(drawLoop);
            releasePresentation();
            super.onDestroy();
        }
    }
}
