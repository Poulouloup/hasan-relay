package com.hasan.v1.network

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hasan.v1.MainActivity
import com.hasan.v1.R
import com.hasan.v1.utils.MarkdownUtils

/**
 * Affiche la notification Android pour un message proactif (canal `proactive`
 * du relay) — extrait de [ProactiveMessageHandler] pour être réutilisable
 * depuis [HasanFirebaseMessagingService] (réveil FCM, app fermée), qui n'a
 * aucune dépendance à [ChannelMultiplexer]/[ConnectionManager] (contexte
 * d'exécution différent, sans WebSocket).
 */
object ProactiveNotifier {
    private const val NOTIF_ID_PROACTIVE = 5
    private const val CHANNEL_PROACTIVE = "hasan_proactive_v1"

    fun show(context: Context, content: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROACTIVE,
                "Messages proactifs Hasan",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Messages envoyés par Hasan sans action de votre part"
                enableVibration(true)
            }
        )

        val body = MarkdownUtils.stripMarkdown(content).take(200)

        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_PROACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hasan")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        nm.notify(NOTIF_ID_PROACTIVE, notif)
    }
}
