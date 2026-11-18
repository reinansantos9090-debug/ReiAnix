package com.reianix.reianix_local.player.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.reianix.reianix_local.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Keeps the authenticated, read-only local HTTP endpoint reachable while the
 * screen is locked or the player Activity is stopped. Android owns the visible
 * foreground notification. Ending the Cast session closes the endpoint.
 */
class CastStreamingService : Service() {
    private var manager: SessionManager? = null
    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) = stopIfNotRoku()
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = stopIfNotRoku()
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = stopIfNotRoku()
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    private fun stopIfNotRoku() {
        if (activeRokuAddress == null) stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        manager = runCatching { CastContext.getSharedInstance(this).sessionManager }.getOrNull()
        manager?.addSessionManagerListener(listener, CastSession::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val rokuAddress = activeRokuAddress
            if (rokuAddress != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val ip = InetAddress.getByName(rokuAddress)
                        RokuEcpClient.command(RokuEcpClient.Device(ip, "Roku"), "stop")
                    }
                }
            } else {
                runCatching { manager?.endCurrentSession(true) }
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val rokuTarget = intent?.getStringExtra(EXTRA_ROKU_ADDRESS)
        if (rokuTarget != null) activeRokuAddress = rokuTarget
        val isRoku = activeRokuAddress != null
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Transmissão para TV", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Servidor local temporário de vídeos para TV" },
            )
        }
        val stopIntent = Intent(this, CastStreamingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ReiAnix • transmitindo para TV")
            .setContentText(if (isRoku) "O vídeo local está disponível na Roku" else "O vídeo local está disponível para o Chromecast")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar transmissão", stopPending)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        manager?.let { runCatching { it.removeSessionManagerListener(listener, CastSession::class.java) } }
        manager = null
        val wasRoku = activeRokuAddress != null
        activeRokuAddress = null
        LocalCastMediaServer.stop()
        if (wasRoku) RokuCastController.onStreamingServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "reianix_cast_stream"
        private const val NOTIFICATION_ID = 2308
        private const val ACTION_STOP = "com.reianix.reianix_local.STOP_CAST"
        private const val EXTRA_ROKU_ADDRESS = "roku_address"

        @Volatile var activeRokuAddress: String? = null
            private set

        fun ensureRunning(context: Context, rokuAddress: String? = null) {
            if (rokuAddress != null) activeRokuAddress = rokuAddress
            ContextCompat.startForegroundService(
                context,
                Intent(context, CastStreamingService::class.java).apply {
                    if (rokuAddress != null) putExtra(EXTRA_ROKU_ADDRESS, rokuAddress)
                },
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastStreamingService::class.java))
        }
    }
}
