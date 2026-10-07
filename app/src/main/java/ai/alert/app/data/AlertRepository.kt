package ai.alert.app.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.Firebase

class AlertRepository(private val context: Context) {

    private var ready = false

    fun initialize(): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }

            ready = FirebaseApp.getApps(context).isNotEmpty()
            if (ready) {
                ensureAnonymousUser()
            }
            ready
        } catch (_: Exception) {
            ready = false
            false
        }
    }

    fun sendFastAlert(
        latitude: Double,
        longitude: Double,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ready) {
            onError("Firebase is not configured yet")
            return
        }

        val auth = try {
            Firebase.auth
        } catch (_: Exception) {
            onError("Firebase Authentication is unavailable")
            return
        }

        val user = auth.currentUser
        if (user == null) {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    createAlert(latitude, longitude, onSuccess, onError)
                }
                .addOnFailureListener {
                    onError("Could not connect to alert.ai")
                }
        } else {
            createAlert(latitude, longitude, onSuccess, onError)
        }
    }

    private fun ensureAnonymousUser() {
        val auth = try {
            Firebase.auth
        } catch (_: Exception) {
            return
        }

        if (auth.currentUser != null) {
            registerMessagingToken(auth.currentUser!!.uid)
            return
        }

        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                registerMessagingToken(result.user?.uid)
            }
    }

    private fun registerMessagingToken(uid: String?) {
        if (uid == null) return

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Firebase.firestore.collection("users")
                    .document(uid)
                    .set(
                        mapOf(
                            "fcmToken" to token,
                            "lastSeen" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
            }
    }

    private fun createAlert(
        latitude: Double,
        longitude: Double,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val uid = Firebase.auth.currentUser?.uid
        if (uid == null) {
            onError("Authentication is not ready")
            return
        }

        val alert = hashMapOf(
            "type" to "FAST",
            "latitude" to latitude,
            "longitude" to longitude,
            "senderId" to uid,
            "createdAt" to FieldValue.serverTimestamp()
        )

        Firebase.firestore.collection("alerts")
            .add(alert)
            .addOnSuccessListener {
                Firebase.firestore.collection("users")
                    .document(uid)
                    .set(
                        mapOf(
                            "latitude" to latitude,
                            "longitude" to longitude,
                            "lastSeen" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                onSuccess()
            }
            .addOnFailureListener {
                onError("Could not send the alert")
            }
    }
}
