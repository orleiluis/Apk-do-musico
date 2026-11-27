package com.orleiluis.timbrechord;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.orleiluis.timbrechord.core.LiveAnalyzer;

/**
 * Serviço em primeiro plano que ouve o áudio enquanto ele toca e entrega ao analisador.
 * Nada é gravado em arquivo: as amostras são analisadas na memória e descartadas.
 * Fontes: áudio interno do aparelho (o som do vídeo, via captura de reprodução) ou microfone.
 */
public class CaptureService extends Service {
    static final String ACTION_START = "com.orleiluis.timbrechord.START";
    static final String ACTION_STOP = "com.orleiluis.timbrechord.STOP";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";
    static final String EXTRA_MIC = "mic";

    private static final String CHANNEL = "captura";
    private static final int NOTIFICATION_ID = 1;

    static volatile boolean running;
    static volatile String lastError = "";

    /** Quadrinho com o acorde por cima de outros apps: ligado pela tela quando o app sai da frente. */
    static volatile boolean bubbleWanted;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean micSource;
    private String shownInNotification = "";
    private LinearLayout bubble;
    private TextView bubbleChord, bubbleInfo;
    private WindowManager.LayoutParams bubbleParams;
    private int tick;

    /** Atualiza o quadrinho flutuante e a notificação enquanto a escuta está ligada. */
    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                removeBubble();
                return;
            }
            // som de outro app: não há posição da música, então o mapa segue o relógio da própria escuta
            // (é aqui, e não na tela, para continuar valendo com o app em segundo plano)
            if (AppState.chartId.isEmpty())
                AppState.recorder.update(AppState.chart, AppState.analyzer.chords, AppState.analyzer.seconds(), 1);
            String chord = AppState.analyzer.chords.current();
            if (bubbleWanted && Settings.canDrawOverlays(CaptureService.this)) {
                if (bubble == null) addBubble();
                if (bubble != null) {
                    bubbleChord.setText(chord.isEmpty() ? "—" : chord);
                    if (tick % 5 == 0) {
                        String key = AppState.analyzer.chords.key();
                        bubbleInfo.setText(key.isEmpty() ? "ouvindo…" : "Tom " + key.replaceAll(" \\(.*", ""));
                    }
                }
            } else {
                removeBubble();
            }
            if (++tick % 5 == 0 && !chord.equals(shownInNotification)) {
                shownInNotification = chord;
                try {
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification(micSource, chord));
                } catch (Exception ignored) { }
            }
            main.postDelayed(this, 200);
        }
    };

    private MediaProjection projection;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean stopRequested;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            stopCapture();
            stopSelf();
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopCapture();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean mic = intent.getBooleanExtra(EXTRA_MIC, false);
        micSource = mic;
        try {
            Notification notification = buildNotification(mic, "");
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification, mic
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else if (mic) {
                startForeground(NOTIFICATION_ID, notification);
            } else {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }
            stopCapture();
            lastError = "";

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(LiveAnalyzer.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int min = AudioRecord.getMinBufferSize(LiveAnalyzer.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferBytes = Math.max(min > 0 ? min : 0, 32768);
            AudioRecord.Builder builder = new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(bufferBytes);
            if (mic) {
                builder.setAudioSource(MediaRecorder.AudioSource.MIC);
            } else {
                MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                if (data == null) throw new IllegalStateException("permissão de captura ausente");
                projection = manager.getMediaProjection(resultCode, data);
                if (projection == null) throw new IllegalStateException("o sistema não liberou a captura");
                projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
                AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
                builder.setAudioPlaybackCaptureConfig(config);
            }
            record = builder.build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("não foi possível abrir a entrada de áudio");
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("a entrada de áudio está em uso por outro app");

            stopRequested = false;
            final AudioRecord source = record;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    short[] buffer = new short[4096];
                    while (!stopRequested) {
                        int n = source.read(buffer, 0, buffer.length);
                        if (n > 0) AppState.analyzer.feed(buffer, n);
                        else if (n < 0) break;
                    }
                }
            }, "timbrechord-captura");
            thread.start();
            running = true;
            shownInNotification = "";
            main.removeCallbacks(updater);
            main.post(updater);
        } catch (Exception e) {
            lastError = "Não consegui começar a ouvir: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            stopCapture();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private synchronized void stopCapture() {
        running = false;
        stopRequested = true;
        main.removeCallbacks(updater);
        main.post(new Runnable() {
            @Override
            public void run() { removeBubble(); }
        });
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) { }
        }
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(800); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (r != null) {
            try { r.release(); } catch (Exception ignored) { }
        }
        MediaProjection p = projection;
        projection = null;
        if (p != null) {
            try { p.unregisterCallback(projectionCallback); } catch (Exception ignored) { }
            try { p.stop(); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onDestroy() {
        stopCapture();
        main.removeCallbacksAndMessages(null);
        removeBubble();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ quadrinho flutuante

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void addBubble() {
        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(dp(14), dp(6), dp(14), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEE101418);
            bg.setCornerRadius(dp(14));
            bg.setStroke(dp(1), 0xFFFFB300);
            box.setBackground(bg);
            bubbleChord = new TextView(this);
            bubbleChord.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            bubbleChord.setTextColor(0xFFFFB300);
            bubbleChord.setTypeface(Typeface.DEFAULT_BOLD);
            bubbleChord.setIncludeFontPadding(false);
            bubbleChord.setGravity(Gravity.CENTER);
            bubbleChord.setMinWidth(dp(72));
            box.addView(bubbleChord);
            bubbleInfo = new TextView(this);
            bubbleInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            bubbleInfo.setTextColor(0xFF9AA7B4);
            bubbleInfo.setGravity(Gravity.CENTER);
            bubbleInfo.setText("ouvindo…");
            box.addView(bubbleInfo);

            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (bubbleParams != null) { lp.x = bubbleParams.x; lp.y = bubbleParams.y; }
            else { lp.x = dp(12); lp.y = dp(96); }
            bubbleParams = lp;

            // arrastar move o quadrinho; um toque simples volta para o app
            box.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY;
                private int startX, startY;
                private boolean moved;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX(); downY = e.getRawY();
                            startX = lp.x; startY = lp.y;
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                            if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                            if (moved) {
                                lp.x = Math.max(0, startX + Math.round(dx));
                                lp.y = Math.max(0, startY + Math.round(dy));
                                try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (!moved) {
                                try {
                                    startActivity(new Intent(CaptureService.this, MainActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                                } catch (Exception ignored) { }
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });
            wm.addView(box, lp);
            bubble = box;
        } catch (Exception e) {
            bubble = null;
            bubbleWanted = false;
        }
    }

    private void removeBubble() {
        LinearLayout b = bubble;
        bubble = null;
        if (b == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(b); } catch (Exception ignored) { }
    }

    private Notification buildNotification(boolean mic, String chord) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Escuta de áudio", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Aparece enquanto o Timbre Chord está ouvindo a música.");
        nm.createNotificationChannel(channel);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.tc_ic_stat_note)
                .setContentTitle(chord == null || chord.isEmpty() ? "Timbre Chord está ouvindo" : "Acorde: " + chord)
                .setOnlyAlertOnce(true)
                .setContentText(mic ? "Fonte: microfone" : "Fonte: áudio interno do aparelho")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, "Parar", stop).build())
                .build();
    }
}
