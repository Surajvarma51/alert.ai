package ai.alert.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ai.alert.app.MainActivity
import ai.alert.app.R
import ai.alert.app.data.AlertRepository

class AlertMessagingService : FirebaseMessagingService() {
    private val repository by lazy { AlertRepository(applicationContext) }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        repository.registerFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data["type"] != "SEND_ALERT") return

        val alertId = data["alertId"] ?: return
        val distance = data["distanceKm"]
        repository.markDelivered(alertId)

        showNotification(
            alertId = alertId,
            title = "⚠️ ALERT RECEIVED",
            body = if (distance != null) "A SEND ALERT was reported $distance km from you." else "A SEND ALERT was reported near you."
        )
    }

    private fun showNotification(alertId: String, title: String, body: String) {
        val channelId = "send_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sound = Uri.parse("android.resource://" + packageName + "/" + R.raw.alert_sound)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(channelId, "SEND ALERT", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nearby hazard alerts from alert.ai"
                setSound(sound, attributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("alert_id", alertId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            alertId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_alert_ai)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body + "\n\nOpen alert.ai to confirm receipt."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(alertId.hashCode(), notification)
    }
}
