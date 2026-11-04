package com.nova.apex.wallpaper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

// After an app update or a phone restart Android stops background listening, and it can only be
// restarted from the app's own screen. If it was on, show a notification that turns it back on.
public class R2 extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!c.getSharedPreferences(L.PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("nova_resume", "Resume listening", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(c, M.class).setAction(M.ACTION_RESUME_LISTEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 5, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "nova_resume") : new Notification.Builder(c);
        Notification n = b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Nova stopped listening")
                .setContentText("Tap to turn \"Hey Nova\" back on")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
        try {
            nm.notify(43, n);
        } catch (Exception ignored) {
        }
    }
}
