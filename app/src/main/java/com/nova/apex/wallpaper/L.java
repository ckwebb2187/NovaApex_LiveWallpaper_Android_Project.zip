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
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Listens for the wake word while the app is closed (home screen, wallpaper, and optionally screen off).
// Android requires a visible notification while an app uses the mic in the background.
public class L extends Service {
    static final String ACTION_STOP = "com.nova.apex.wallpaper.STOP_LISTEN";
    static final String PREFS = "nova_listen";
    private static final String CHANNEL = "nova_listen";
    private static final String CHANNEL_ALERT = "nova_alert";
    private static final int NOTIF_ID = 41;

    static L instance;
    static volatile boolean appVisible = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer rec;
    private boolean active = false;
    private boolean screenOn = true;
    private boolean listenScreenOff = false;
    private String wake = "hey nova";
    private PowerManager.WakeLock wl;
    private AudioManager am;
    private boolean muted = false;

    private final Runnable listenRun = this::tryListen;
    private final Runnable unmuteRun = this::unmuteBeep;

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
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, "Nova heard you", NotificationManager.IMPORTANCE_HIGH));
        }
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
        if (rec != null) rec.cancel();
        unmuteBeep();
    }

    void resume() {
        schedule(800);
    }

    private void tryListen() {
        if (appVisible || (!screenOn && !listenScreenOff)) {
            // Idle until the app closes or the screen turns on
            schedule(1500);
            return;
        }
        if (rec == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                stopSelf();
                return;
            }
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        muteBeep();
        active = true;
        try {
            rec.startListening(i);
        } catch (Exception e) {
            active = false;
            schedule(2000);
        }
        h.postDelayed(unmuteRun, 700);
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

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rms) { }
        @Override public void onBufferReceived(byte[] buf) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle b) { }
        @Override public void onEvent(int type, Bundle p) { }

        @Override
        public void onResults(Bundle b) {
            if (!active) return;
            active = false;
            ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null) {
                for (String text : list) {
                    String rest = findWake(text, wake);
                    if (rest != null) {
                        heard(rest);
                        schedule(3000);
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
            boolean slow = e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    || e == SpeechRecognizer.ERROR_NETWORK
                    || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopSelf();
                return;
            }
            schedule(slow ? 2000 : 250);
        }
    };

    // Wake word heard: open Nova on top of whatever is showing
    private void heard(String rest) {
        Intent i = new Intent(this, M.class)
                .setAction(M.ACTION_TALK)
                .putExtra("say", rest)
                .putExtra("fromVoice", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean canPop = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        if (canPop) {
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        // Without "display over other apps", ask the user to tap a notification instead
        PendingIntent pi = PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ALERT) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Nova heard you")
                .setContentText(rest.isEmpty() ? "Tap to talk" : "Tap to run: " + rest)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(42, n);
        } catch (Exception ignored) {
        }
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            if (!screenOn && !listenScreenOff) {
                pause();
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
