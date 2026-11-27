package com.orleiluis.timbrechord;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.Locale;

/**
 * Mantém o app vivo enquanto a separação roda (ela leva minutos) e mostra o andamento na notificação.
 * Para sozinho quando a separação termina.
 */
public class SeparationService extends Service {
    private static final String CHANNEL = "separacao";
    private static final int NOTIFICATION_ID = 7;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String shown = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            SeparationJob job = SeparationJob.shared;
            if (!job.isRunning()) {
                finish(job);
                return;
            }
            String text = text(job);
            if (!text.equals(shown)) {
                shown = text;
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIFICATION_ID, build(text, Math.round(job.progress() * 100), true));
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build(text(SeparationJob.shared), 0, true);
        try {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    private static String text(SeparationJob job) {
        StringBuilder t = new StringBuilder();
        t.append(Math.round(job.progress() * 100)).append('%');
        int eta = job.etaSeconds();
        if (eta >= 0) t.append(" · ").append(eta(eta));
        String stage = job.stage();
        if (!stage.isEmpty()) t.append(" · ").append(stage);
        return t.toString();
    }

    static String eta(int seconds) {
        if (seconds < 60) return "falta menos de 1 min";
        int min = Math.round(seconds / 60f);
        return String.format(Locale.US, "faltam cerca de %d min", min);
    }

    private void finish(SeparationJob job) {
        handler.removeCallbacks(tick);
        stopForeground(STOP_FOREGROUND_REMOVE);
        // aviso do fim, para quem saiu do app enquanto esperava
        String text = job.state() == SeparationJob.DONE ? "Faixas prontas: " + job.title()
                : job.state() == SeparationJob.FAILED ? job.error() : "";
        if (!text.isEmpty()) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID + 1, build(text, 100, false));
        }
        stopSelf();
    }

    private Notification build(String text, int percent, boolean ongoing) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Separação de faixas", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Andamento da separação da música em faixas.");
        if (nm != null) nm.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.tc_ic_stat_note)
                .setContentTitle(ongoing ? "Separando: " + SeparationJob.shared.title() : "Timbre Chord")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);
        if (ongoing) b.setProgress(100, percent, percent == 0);
        return b.build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
