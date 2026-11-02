package com.nova.apex.wallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.view.WindowManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;

public class M extends Activity {
    static final String ACTION_TALK = "com.nova.apex.wallpaper.TALK";
    private static final String ACTION_INSTALLED = "com.nova.apex.wallpaper.INSTALL_RESULT";
    private static final int REQ_MIC = 7;

    private WebView v;
    private SpeechRecognizer rec;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean pendingListen = false;
    private boolean pendingBg = false;
    private String bgWake = "hey nova";
    private boolean bgScreenOff = false;
    private long lastLevel = 0;
    private int utterance = 0;
    private boolean recActive = false;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable beginRec = this::beginRec;

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
        v.addJavascriptInterface(new Bridge(), "Android");
        String url = "file:///android_asset/a.html?mode=studio";
        Intent start = getIntent();
        if (isTalk(start)) {
            String say = start.getStringExtra("say");
            url += (say != null && !say.isEmpty()) ? "&say=" + Uri.encode(say) : "&listen=1";
            if (start.getBooleanExtra("fromVoice", false)) showOverLock(true);
        }
        v.loadUrl(url);
        setContentView(v);

        IntentFilter f = new IntentFilter(ACTION_INSTALLED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(installReceiver, f);

        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (!ttsReady) return;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { js("window.onTts&&onTts('start')"); }
                @Override public void onDone(String id) { js("window.onTts&&onTts('done')"); }
                @Override public void onError(String id) { js("window.onTts&&onTts('done')"); }
            });
        });
    }

    private static boolean isTalk(Intent i) {
        return i != null && ACTION_TALK.equals(i.getAction());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (!isTalk(i)) return;
        if (i.getBooleanExtra("fromVoice", false)) showOverLock(true);
        String say = i.getStringExtra("say");
        if (say != null && !say.isEmpty()) js("window.voiceCommand&&voiceCommand(" + JSONObject.quote(say) + ")");
        else js("window.startListen&&startListen('direct')");
    }

    private void js(String code) {
        runOnUiThread(() -> {
            if (v != null) v.evaluateJavascript(code, null);
        });
    }

    private void voiceEvent(String type, String text) {
        js("window.onVoice&&onVoice(" + JSONObject.quote(type) + "," + JSONObject.quote(text) + ")");
    }

    // ---- Speech recognition ----
    private void startRec() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingListen = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceEvent("error", "unavailable");
            return;
        }
        // Cancel any old session first and start the new one a moment later.
        // Starting right away makes many phones report "busy" and drop the new session.
        h.removeCallbacks(beginRec);
        recActive = false;
        if (rec != null) rec.cancel();
        h.postDelayed(beginRec, 250);
    }

    private void beginRec() {
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recActive = true;
        try {
            rec.startListening(i);
        } catch (Exception e) {
            recActive = false;
            voiceEvent("error", "busy");
        }
    }

    private void stopRec() {
        h.removeCallbacks(beginRec);
        recActive = false;
        if (rec != null) rec.cancel();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_MIC) return;
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (ok && pendingListen) startRec();
        if (ok && pendingBg) startBg();
        if (!ok) voiceEvent("error", "permission");
        pendingListen = false;
        pendingBg = false;
    }

    // Show on top of the lock screen and wake the screen (only when opened by the wake word)
    private void showOverLock(boolean on) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on);
            setTurnScreenOn(on);
        } else {
            int f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (on) getWindow().addFlags(f); else getWindow().clearFlags(f);
        }
    }

    // ---- Background listening (wallpaper / home screen / screen off) ----
    private void startBg() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingBg = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8);
        }
        Intent i = new Intent(this, L.class).putExtra("wake", bgWake).putExtra("screenOff", bgScreenOff);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't start background listening", Toast.LENGTH_LONG).show();
        }
    }

    private static String firstResult(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onBufferReceived(byte[] buf) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int type, Bundle p) { }

        @Override
        public void onRmsChanged(float rms) {
            if (!recActive) return;
            long now = System.currentTimeMillis();
            if (now - lastLevel < 80) return;
            lastLevel = now;
            voiceEvent("level", String.valueOf(rms));
        }

        @Override
        public void onPartialResults(Bundle b) {
            if (!recActive) return;
            voiceEvent("partial", firstResult(b));
        }

        @Override
        public void onResults(Bundle b) {
            if (!recActive) return;
            recActive = false;
            voiceEvent("final", firstResult(b));
        }

        @Override
        public void onError(int e) {
            // Ignore errors from a session we already cancelled
            if (!recActive) return;
            recActive = false;
            String code;
            switch (e) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: code = "no-match"; break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: code = "permission"; break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: code = "busy"; break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: code = "network"; break;
                case SpeechRecognizer.ERROR_CLIENT: code = "cancelled"; break;
                default: code = "error " + e;
            }
            voiceEvent("error", code);
        }
    };

    // ---- Network (done here so the page has no cross-site limits) ----
    private void request(String id, String method, String url, String headers, String body) {
        new Thread(() -> {
            int status = 0;
            String out;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                JSONObject h = new JSONObject(headers);
                Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.setRequestProperty(k, h.getString(k));
                }
                if (body != null) {
                    c.setDoOutput(true);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                out = in == null ? "" : read(in);
            } catch (Exception e) {
                out = String.valueOf(e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
            js("window.onHttp&&onHttp(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(out) + ")");
        }).start();
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- In-app updates ----
    private void updateEvent(String type, String text) {
        js("window.onUpdate&&onUpdate(" + JSONObject.quote(type) + "," + JSONObject.quote(text) + ")");
    }

    private void downloadAndInstall(String url) {
        new Thread(() -> {
            File apk = new File(getCacheDir(), "update.apk");
            try {
                // Download (GitHub redirects release files to its storage)
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                int status = c.getResponseCode();
                if (status >= 400) throw new Exception("Download failed (" + status + ")");
                long total = c.getContentLengthLong();
                long got = 0;
                int lastPct = -1;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            updateEvent("progress", String.valueOf(pct));
                        }
                    }
                }
                c.disconnect();

                // Hand the file to Android's installer; it asks the user to confirm
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sid = pi.createSession(params);
                try (PackageInstaller.Session session = pi.openSession(sid)) {
                    try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("nova", 0, apk.length())) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    Intent done = new Intent(ACTION_INSTALLED).setPackage(getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pend = PendingIntent.getBroadcast(this, sid, done, flags);
                    session.commit(pend.getIntentSender());
                }
                updateEvent("installing", "");
            } catch (Exception e) {
                updateEvent("error", String.valueOf(e.getMessage()));
            }
        }).start();
    }

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirm);
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                updateEvent("done", "");
            } else {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    msg = "This update was signed differently from the installed app. Uninstall Nova Apex once, then install the new APK by hand.";
                } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    msg = "Update cancelled.";
                }
                updateEvent("error", msg == null ? "Install failed" : msg);
            }
        }
    };

    private class Bridge {
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

        @JavascriptInterface
        public void startListening() {
            runOnUiThread(M.this::startRec);
        }

        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(M.this::stopRec);
        }

        @JavascriptInterface
        public void speak(String text, float rate) {
            if (!ttsReady) {
                js("window.onTts&&onTts('done')");
                return;
            }
            tts.setSpeechRate(rate);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova" + (++utterance));
        }

        @JavascriptInterface
        public void stopSpeaking() {
            if (tts != null) tts.stop();
        }

        @JavascriptInterface
        public void httpPost(String id, String url, String headers, String body) {
            request(id, "POST", url, headers, body);
        }

        @JavascriptInterface
        public void setBackgroundListen(boolean on, String wake, boolean screenOff) {
            runOnUiThread(() -> {
                bgWake = wake == null || wake.trim().isEmpty() ? "hey nova" : wake;
                bgScreenOff = screenOff;
                if (on) startBg();
                else stopService(new Intent(M.this, L.class));
            });
        }

        @JavascriptInterface
        public boolean isBackgroundListening() {
            return L.instance != null;
        }

        @JavascriptInterface
        public boolean bgStoppedByUser() {
            return getSharedPreferences(L.PREFS, MODE_PRIVATE).getBoolean("stoppedByUser", false);
        }

        @JavascriptInterface
        public boolean canPopUp() {
            return Settings.canDrawOverlays(M.this);
        }

        @JavascriptInterface
        public void openPopUpSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(M.this, "Turn on 'Display over other apps' for Nova Apex in Settings", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void httpGet(String id, String url, String headers) {
            request(id, "GET", url, headers, null);
        }

        @JavascriptInterface
        public String getVersion() {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return "{\"code\":" + pi.versionCode + ",\"name\":" + JSONObject.quote(pi.versionName) + "}";
            } catch (Exception e) {
                return "{\"code\":0,\"name\":\"?\"}";
            }
        }

        @JavascriptInterface
        public void installUpdate(String url) {
            downloadAndInstall(url);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The app listens by itself while open; pause the background listener
        L.appVisible = true;
        if (L.instance != null) L.instance.pause();
        if (v != null) {
            v.onResume();
            js("window.onAppResume&&onAppResume()");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        L.appVisible = false;
        if (L.instance != null) L.instance.resume();
        stopRec();
        if (tts != null) tts.stop();
        if (v != null) {
            v.evaluateJavascript("window.onAppPause&&onAppPause()", null);
            v.onPause();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        showOverLock(false);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(installReceiver); } catch (Exception ignored) { }
        if (rec != null) rec.destroy();
        if (tts != null) tts.shutdown();
        if (v != null) v.destroy();
        super.onDestroy();
    }
}
