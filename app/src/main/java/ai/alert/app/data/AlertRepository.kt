package ai.alert.app.data

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.messaging.FirebaseMessaging

class AlertRepository(private val context: Context) {

    private var ready = false

    fun initialize(): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }

            ready = FirebaseApp.getApps(context).isNotEmpty()
            if (ready) ensureAnonymousUser()
            ready
        } catch (_: Exception) {
            ready = false
            false
        }
    }

    fun registerPresence(
        latitude: Double,
        longitude: Double,
        onError: ((String) -> Unit)? = null
    ) {
        if (!ready) {
            onError?.invoke("Firebase is not configured yet")
            return
        }

        val uid = Firebase.auth.currentUser?.uid
        if (uid == null) {
            Firebase.auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    savePresence(result.user?.uid, latitude, longitude, onError)
                }
                .addOnFailureListener {
                    onError?.invoke("Could not connect to alert.ai")
                }
        } else {
            savePresence(uid, latitude, longitude, onError)
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

        val uid = Firebase.auth.currentUser?.uid
        if (uid == null) {
            Firebase.auth.signInAnonymously()
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
        val auth = Firebase.auth
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

    private fun savePresence(
        uid: String?,
        latitude: Double,
        longitude: Double,
        onError: ((String) -> Unit)?
    ) {
        if (uid == null) {
            onError?.invoke("Authentication is not ready")
            return
        }

        val geohash = GeoHash.encode(latitude, longitude)

        Firebase.firestore.collection("users")
            .document(uid)
            .set(
                mapOf(
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "geohash" to geohash,
                    "lastSeen" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnFailureListener {
                onError?.invoke("Could not update nearby-alert availability")
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
                savePresence(uid, latitude, longitude, onError)
                onSuccess()
            }
            .addOnFailureListener {
                onError("Could not send the alert")
            }
    }
}
