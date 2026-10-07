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
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
            ready = FirebaseApp.getApps(context).isNotEmpty()
            if (ready) ensureUser()
            ready
        } catch (_: Exception) {
            ready = false
            false
        }
    }

    fun registerPresence(latitude: Double, longitude: Double, onError: ((String) -> Unit)? = null) {
        if (!ready) { onError?.invoke("Firebase is not configured yet"); return }
        ensureUserAndRun({ uid -> savePresence(uid, latitude, longitude, onError) }, onError)
    }

    fun sendFastAlert(latitude: Double, longitude: Double, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!ready) { onError("Firebase is not configured yet"); return }
        ensureUserAndRun({ uid -> createAlert(uid, latitude, longitude, onSuccess, onError) }, onError)
    }

    private fun ensureUser() {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        registerMessagingToken(uid)
    }

    private fun ensureUserAndRun(run: (String) -> Unit, onError: ((String) -> Unit)?) {
        val uid = Firebase.auth.currentUser?.uid
        if (uid != null) {
            registerMessagingToken(uid)
            run(uid)
        } else {
            onError?.invoke("Please sign in to alert.ai first")
        }
    }

    private fun registerMessagingToken(uid: String) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            Firebase.firestore.collection("users").document(uid).set(
                mapOf("fcmToken" to token, "lastSeen" to FieldValue.serverTimestamp()),
                SetOptions.merge()
            )
        }
    }

    private fun savePresence(uid: String, latitude: Double, longitude: Double, onError: ((String) -> Unit)?) {
        Firebase.firestore.collection("users").document(uid).set(
            mapOf(
                "latitude" to latitude,
                "longitude" to longitude,
                "geohash" to GeoHash.encode(latitude, longitude),
                "lastSeen" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).addOnFailureListener { onError?.invoke("Could not update nearby-alert availability") }
    }

    private fun createAlert(uid: String, latitude: Double, longitude: Double, onSuccess: () -> Unit, onError: (String) -> Unit) {
        Firebase.firestore.collection("alerts").add(
            hashMapOf(
                "type" to "FAST",
                "latitude" to latitude,
                "longitude" to longitude,
                "senderId" to uid,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).addOnSuccessListener {
            savePresence(uid, latitude, longitude, onError)
            onSuccess()
        }.addOnFailureListener { onError("Could not send the alert") }
    }
}
