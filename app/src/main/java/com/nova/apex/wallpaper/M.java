package com.nova.apex.wallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;

public class M extends Activity {
    static final String ACTION_TALK = "com.nova.apex.wallpaper.TALK";
    private static final int REQ_MIC = 7;

    private WebView v;
    private SpeechRecognizer rec;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean pendingListen = false;
    private long lastLevel = 0;
    private int utterance = 0;

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
        if (isTalk(getIntent())) url += "&listen=1";
        v.loadUrl(url);
        setContentView(v);

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
        if (isTalk(i)) js("window.startListen&&startListen('direct')");
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
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(listener);
        } else {
            rec.cancel();
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        rec.startListening(i);
    }

    private void stopRec() {
        if (rec != null) rec.cancel();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_MIC) return;
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (ok && pendingListen) startRec();
        else if (!ok) voiceEvent("error", "permission");
        pendingListen = false;
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
            long now = System.currentTimeMillis();
            if (now - lastLevel < 80) return;
            lastLevel = now;
            voiceEvent("level", String.valueOf(rms));
        }

        @Override
        public void onPartialResults(Bundle b) {
            voiceEvent("partial", firstResult(b));
        }

        @Override
        public void onResults(Bundle b) {
            voiceEvent("final", firstResult(b));
        }

        @Override
        public void onError(int e) {
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
    private void post(String id, String url, String headers, String body) {
        new Thread(() -> {
            int status = 0;
            String out;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setDoOutput(true);
                JSONObject h = new JSONObject(headers);
                Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.setRequestProperty(k, h.getString(k));
                }
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
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
            post(id, url, headers, body);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (v != null) {
            v.onResume();
            js("window.onAppResume&&onAppResume()");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRec();
        if (tts != null) tts.stop();
        if (v != null) {
            v.evaluateJavascript("window.onAppPause&&onAppPause()", null);
            v.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        if (rec != null) rec.destroy();
        if (tts != null) tts.shutdown();
        if (v != null) v.destroy();
        super.onDestroy();
    }
}
