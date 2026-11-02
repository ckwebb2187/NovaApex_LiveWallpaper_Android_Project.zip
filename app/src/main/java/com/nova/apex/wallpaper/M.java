package com.nova.apex.wallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class M extends Activity {
    private WebView v;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v = new WebView(this);
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        v.setWebChromeClient(new WebChromeClient());
        v.setWebViewClient(new WebViewClient());
        v.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void applyWallpaper() {
                runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(M.this, S.class));
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
                        } catch (Exception ex) {
                            Toast.makeText(M.this, "Select Nova Apex in Wallpaper Settings", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
            @JavascriptInterface
            public void showToast(String msg) {
                runOnUiThread(() -> Toast.makeText(M.this, msg, Toast.LENGTH_SHORT).show());
            }
        }, "Android");
        v.loadUrl("file:///android_asset/a.html?mode=studio");
        setContentView(v);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (v != null) v.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (v != null) v.onPause();
    }
}