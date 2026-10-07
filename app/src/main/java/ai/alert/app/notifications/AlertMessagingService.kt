package ai.alert.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ai.alert.app.MainActivity
import ai.alert.app.R

class AlertMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        try {
            val uid = Firebase.auth.currentUser?.uid ?: return
            Firebase.firestore.collection("users")
                .document(uid)
                .set(
                    mapOf(
                        "fcmToken" to token,
                        "lastSeen" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
        } catch (_: Exception) {
            // Firebase may not be initialized yet.
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val title = data["title"] ?: "⚡ FAST ALERT"
        val distance = data["distanceKm"]
        val body = data["body"] ?: if (distance != null) {
            "A FAST alert was reported " + distance + " km from you."
        } else {
            "A FAST alert was reported near you."
        }
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "fast_alerts"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.default_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Nearby FAST alerts from alert.ai"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_alert_ai)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
