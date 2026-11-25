package com.apkdomusico.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.apkdomusico.MainActivity
import com.apkdomusico.audio.NativeAudio

/**
 * Mantém o metrônomo tocando com a tela apagada ou com outro app aberto.
 * Sem um serviço em primeiro plano, o Android congela o app em segundo plano e o clique para.
 */
class MetronomeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NativeAudio.metronomeStop()
            stopSelf()
            return START_NOT_STICKY
        }
        val bpm = intent?.getIntExtra(EXTRA_BPM, 0) ?: 0
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MetronomeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Metrônomo tocando")
            .setContentText(if (bpm > 0) "$bpm BPM" else "Toque para abrir")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Metrônomo", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "metronomo"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "parar"
        private const val EXTRA_BPM = "bpm"

        fun start(context: Context, bpm: Int) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, MetronomeService::class.java).putExtra(EXTRA_BPM, bpm),
                )
            } catch (e: Exception) {
                // Alguns aparelhos recusam iniciar o serviço; o metrônomo continua tocando com o app aberto.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MetronomeService::class.java))
        }
    }
}
