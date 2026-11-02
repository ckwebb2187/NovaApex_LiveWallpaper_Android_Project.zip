package com.nova.apex.wallpaper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

// Listens for the wake word while the app is closed (home screen, wallpaper, and optionally screen off)
// and answers right there: a hidden copy of the Nova page runs the commands and AI, the voice is
// spoken here, and the wallpaper is told to show listening / thinking / speaking.
// Android requires a visible notification while an app uses the mic in the background.
public class L extends Service {
    static final String ACTION_STOP = "com.nova.apex.wallpaper.STOP_LISTEN";
    static final String PREFS = "nova_listen";
    private static final String CHANNEL = "nova_listen";
    private static final int NOTIF_ID = 41;

    static L instance;
    static volatile boolean appVisible = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer rec;
    private boolean active = false;
    private boolean direct = false;      // listening for a command (after "Hey Nova") instead of the wake word
    private boolean busy = false;        // the assistant is listening, thinking or speaking
    private boolean screenOn = true;
    private boolean listenScreenOff = false;
    private String wake = "hey nova";
    private PowerManager.WakeLock wl;
    private AudioManager am;
    private boolean muted = false;
    private long lastLevel = 0;

    private WebView agent;
    private boolean agentReady = false;
    private String pendingWake = null;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private int utterance = 0;

    private final Runnable listenRun = this::tryListen;
    private final Runnable unmuteRun = this::unmuteBeep;
    // If the assistant never reports back, don't stay stuck: go back to listening for the wake word
    private final Runnable watchdog = () -> {
        busy = false;
        S.toWalls("window.setLiveState&&setLiveState('')");
        schedule(300);
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        screenOn = pm == null || pm.isInteractive();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, f);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Nova listening", NotificationManager.IMPORTANCE_LOW));
        }
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (!ttsReady) return;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { agentJs("window.onTts&&onTts('done')"); }
                @Override public void onError(String id) { agentJs("window.onTts&&onTts('done')"); }
            });
        });
        createAgent();
    }

    // Hidden copy of the Nova page that runs commands and talks to the AI
    private void createAgent() {
        agent = new WebView(this);
        WebSettings s = agent.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        agent.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                agentReady = true;
                if (pendingWake != null) {
                    String w = pendingWake;
                    pendingWake = null;
                    sendWake(w);
                }
            }
        });
        agent.addJavascriptInterface(new AgentBridge(), "Android");
        agent.loadUrl("file:///android_asset/a.html?mode=agent");
    }

    private void agentJs(String code) {
        h.post(() -> {
            if (agent != null) agent.evaluateJavascript(code, null);
        });
    }

    private void wallJs(String code) {
        h.post(() -> S.toWalls(code));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            p.edit().putBoolean("stoppedByUser", true).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            if (intent.hasExtra("wake")) wake = intent.getStringExtra("wake");
            listenScreenOff = intent.getBooleanExtra("screenOff", false);
        }
        p.edit().putBoolean("stoppedByUser", false).apply();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
        } catch (Exception e) {
            // Android refused (e.g. started from the background); give up quietly
            stopSelf();
            return START_NOT_STICKY;
        }
        updateWakeLock();
        schedule(500);
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, M.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, L.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Nova is listening")
                .setContentText("Say \"" + wake + "\"" + (listenScreenOff ? " · screen off too" : ""))
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .setOngoing(true)
                .build();
    }

    private void updateWakeLock() {
        if (listenScreenOff) {
            if (wl == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaApex:listen");
                wl.setReferenceCounted(false);
            }
            wl.acquire();
        } else if (wl != null && wl.isHeld()) {
            wl.release();
        }
    }

    private void schedule(long ms) {
        h.removeCallbacks(listenRun);
        h.postDelayed(listenRun, ms);
    }

    // Called by the app when it comes to the front (it does its own listening then)
    void pause() {
        h.removeCallbacks(listenRun);
        active = false;
        direct = false;
        if (rec != null) rec.cancel();
        if (tts != null) tts.stop();
        unmuteBeep();
        if (busy) {
            busy = false;
            wallJs("window.setLiveState&&setLiveState('')");
        }
    }

    void resume() {
        schedule(800);
    }

    // Wake-word loop: keeps listening unless the app is open, the screen is off (when not allowed) or Nova is busy
    private void tryListen() {
        if (busy) return;
        if (appVisible || (!screenOn && !listenScreenOff)) {
            schedule(1500);
            return;
        }
        startRecognizer(false);
    }

    private void startRecognizer(boolean forCommand) {
        h.removeCallbacks(listenRun);
        if (rec == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                stopSelf();
                return;
            }
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(listener);
        } else {
            rec.cancel();
        }
        direct = forCommand;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, forCommand);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        if (!forCommand) muteBeep();
        // Start a moment after the cancel above; starting right away makes many phones drop the session
        h.postDelayed(() -> {
            active = true;
            try {
                rec.startListening(i);
            } catch (Exception e) {
                active = false;
                if (direct) agentJs("window.onVoice&&onVoice('error','busy')");
                else schedule(2000);
            }
            h.postDelayed(unmuteRun, 700);
        }, 250);
    }

    // The recognizer plays a tone each time it starts; mute it unless music is playing
    private void muteBeep() {
        try {
            if (!muted && am != null && !am.isMusicActive()) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                muted = true;
            }
        } catch (Exception ignored) {
        }
    }

    private void unmuteBeep() {
        h.removeCallbacks(unmuteRun);
        try {
            if (muted && am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) {
        }
        muted = false;
    }

    private void voiceToAgent(String type, String text) {
        agentJs("window.onVoice&&onVoice(" + JSONObject.quote(type) + "," + JSONObject.quote(text) + ")");
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onBufferReceived(byte[] buf) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int type, Bundle p) { }

        @Override
        public void onRmsChanged(float rms) {
            if (!active || !direct) return;
            long now = System.currentTimeMillis();
            if (now - lastLevel < 80) return;
            lastLevel = now;
            wallJs("window.setVoiceLevel&&setVoiceLevel(" + rms + ")");
        }

        @Override
        public void onPartialResults(Bundle b) {
            if (!active || !direct) return;
            String t = first(b);
            if (!t.isEmpty()) voiceToAgent("partial", t);
        }

        @Override
        public void onResults(Bundle b) {
            if (!active) return;
            active = false;
            ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (direct) {
                direct = false;
                voiceToAgent("final", list == null || list.isEmpty() ? "" : list.get(0));
                return;
            }
            if (list != null) {
                for (String text : list) {
                    String rest = findWake(text, wake);
                    if (rest != null) {
                        heard(rest);
                        return;
                    }
                }
            }
            schedule(200);
        }

        @Override
        public void onError(int e) {
            if (!active) return;
            active = false;
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopSelf();
                return;
            }
            if (direct) {
                direct = false;
                boolean noMatch = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                voiceToAgent("error", noMatch ? "no-match" : "error " + e);
                return;
            }
            boolean slow = e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    || e == SpeechRecognizer.ERROR_NETWORK
                    || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
            schedule(slow ? 2000 : 250);
        }
    };

    private static String first(Bundle b) {
        ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    // Wake word heard: answer right here without opening the app
    private void heard(String rest) {
        busy = true;
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, 90000);
        S.toWalls("window.setLiveState&&setLiveState('listening');window.pulseNow&&pulseNow()");
        if (agentReady) sendWake(rest);
        else pendingWake = rest;
    }

    private void sendWake(String rest) {
        agentJs("window.wakeHeard&&wakeHeard(" + JSONObject.quote(rest) + ")");
    }

    // Methods the hidden assistant page calls (same names as in the app)
    private class AgentBridge {
        @JavascriptInterface
        public void startListening() {
            h.post(() -> startRecognizer(true));
        }

        @JavascriptInterface
        public void stopListening() {
            h.post(() -> {
                active = false;
                direct = false;
                if (rec != null) rec.cancel();
            });
        }

        @JavascriptInterface
        public void speak(String text, float rate) {
            if (!ttsReady) {
                agentJs("window.onTts&&onTts('done')");
                return;
            }
            tts.setSpeechRate(rate);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bg" + (++utterance));
        }

        @JavascriptInterface
        public void stopSpeaking() {
            if (tts != null) tts.stop();
        }

        // listening / thinking / speaking, or "" when finished
        @JavascriptInterface
        public void setState(String state) {
            h.post(() -> {
                S.toWalls("window.setLiveState&&setLiveState(" + JSONObject.quote(state) + ")");
                if (state == null || state.isEmpty()) {
                    // Done: let the wallpaper pick up any theme/scene change, then go back to the wake word
                    S.toWalls("window.syncConfig&&syncConfig()");
                    busy = false;
                    h.removeCallbacks(watchdog);
                    schedule(600);
                } else {
                    busy = true;
                    h.removeCallbacks(watchdog);
                    h.postDelayed(watchdog, 90000);
                }
            });
        }

        @JavascriptInterface
        public void setCaption(String you, String text) {
            wallJs("window.showCaption&&showCaption(" + JSONObject.quote(you) + "," + JSONObject.quote(text) + ")");
        }

        @JavascriptInterface
        public void httpPost(String id, String url, String headers, String body) {
            request(id, "POST", url, headers, body);
        }

        @JavascriptInterface
        public void httpGet(String id, String url, String headers) {
            request(id, "GET", url, headers, null);
        }

        @JavascriptInterface
        public void showToast(String msg) {
        }
    }

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
                JSONObject hd = new JSONObject(headers);
                Iterator<String> it = hd.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.setRequestProperty(k, hd.getString(k));
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
            agentJs("window.onHttp&&onHttp(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(out) + ")");
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

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            if (busy) return;
            if (!screenOn && !listenScreenOff) {
                h.removeCallbacks(listenRun);
                active = false;
                if (rec != null) rec.cancel();
                unmuteBeep();
                schedule(1500);
            } else {
                schedule(500);
            }
        }
    };

    // ---- Loose wake word matching ("hey noah", "a nova", "nova") ----
    static String findWake(String text, String wakePhrase) {
        List<String> words = split(text);
        List<String> wakeWords = split(wakePhrase);
        if (words.isEmpty() || wakeWords.isEmpty()) return null;
        String target = join(wakeWords, 0, wakeWords.size());
        int limit = Math.max(1, Math.round(target.length() * 0.3f));
        int bestD = Integer.MAX_VALUE, bestEnd = -1;
        for (int size = Math.max(1, wakeWords.size() - 1); size <= wakeWords.size() + 1; size++) {
            for (int i = 0; i + size <= words.size(); i++) {
                int d = dist(join(words, i, i + size), target);
                if (d <= limit && d < bestD) {
                    bestD = d;
                    bestEnd = i + size;
                }
            }
        }
        if (bestEnd < 0 && wakeWords.size() > 1) {
            String name = wakeWords.get(wakeWords.size() - 1);
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                if (w.equals(name) || (name.length() > 4 && dist(w, name) <= 1)) {
                    bestEnd = i + 1;
                    break;
                }
            }
        }
        return bestEnd < 0 ? null : join(words, bestEnd, words.size());
    }

    private static List<String> split(String s) {
        String n = (s == null ? "" : s).toLowerCase().replaceAll("[^a-z0-9\\s]", " ").trim();
        return n.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(n.split("\\s+")));
    }

    private static String join(List<String> w, int a, int b) {
        StringBuilder sb = new StringBuilder();
        for (int i = a; i < b; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.get(i));
        }
        return sb.toString();
    }

    private static int dist(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + c);
            }
        }
        return d[a.length()][b.length()];
    }

    @Override
    public void onDestroy() {
        instance = null;
        h.removeCallbacksAndMessages(null);
        unmuteBeep();
        if (rec != null) rec.destroy();
        if (tts != null) tts.shutdown();
        if (agent != null) agent.destroy();
        S.toWalls("window.setLiveState&&setLiveState('')");
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
