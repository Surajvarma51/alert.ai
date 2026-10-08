package ai.alert.app.data

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class ProfilePatch(
    val id: String,
    val display_name: String? = null,
    val email: String? = null,
    val fcm_token: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

class AlertRepository(private val context: Context) {
    private val supabase = SupabaseClient.client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(): Boolean {
        registerFcmToken()
        return true
    }

    fun registerPresence(latitude: Double, longitude: Double, onError: ((String) -> Unit)? = null) {
        val user = supabase.auth.currentUserOrNull()
        if (user == null) { onError?.invoke("Please sign in first"); return }
        scope.launch {
            try {
                supabase.from("profiles").upsert(
                    ProfilePatch(
                        id = user.id,
                        email = user.email,
                        latitude = latitude,
                        longitude = longitude
                    )
                )
            } catch (t: Throwable) {
                onError?.invoke(t.message ?: "Could not update nearby availability")
            }
        }
    }

    fun sendAlert(
        latitude: Double,
        longitude: Double,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val user = supabase.auth.currentUserOrNull()
        if (user == null) { onError("Please sign in first"); return }

        scope.launch {
            try {
                val alert = AlertRow(
                    sender_id = user.id,
                    latitude = latitude,
                    longitude = longitude,
                    delivery_mode = "internet"
                )
                val created = supabase.from("alerts").insert(alert) {
                    select()
                }.decodeSingle<AlertRow>()

                val id = created.id ?: error("Alert ID was not returned")
                supabase.functions.invoke(
                    function = "dispatch-alert",
                    body = buildJsonObject {
                        put("alert_id", id)
                    }
                )
                registerPresence(latitude, longitude)
                onSuccess(id)
            } catch (t: Throwable) {
                onError(t.message ?: "Could not send the alert")
            }
        }
    }

    fun getReceipts(alertId: String, onResult: (List<ReceiptRow>) -> Unit) {
        scope.launch {
            try {
                val rows = supabase.from("alert_receipts")
                    .select()
                    {
                        filter { eq("alert_id", alertId) }
                    }
                    .decodeList<ReceiptRow>()
                onResult(rows)
            } catch (_: Throwable) {
                onResult(emptyList())
            }
        }
    }

    fun markDelivered(alertId: String) {
        val user = supabase.auth.currentUserOrNull() ?: return
        scope.launch {
            try {
                supabase.from("alert_receipts").upsert(
                    ReceiptRow(alert_id = alertId, receiver_id = user.id, status = "DELIVERED")
                ) {
                    onConflict = "alert_id,receiver_id"
                }
            } catch (_: Throwable) {}
        }
    }

    fun acknowledgeAlert(alertId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val user = supabase.auth.currentUserOrNull()
        if (user == null) { onError("Please sign in first"); return }
        scope.launch {
            try {
                supabase.from("alert_receipts").upsert(
                    ReceiptRow(alert_id = alertId, receiver_id = user.id, status = "ACKNOWLEDGED")
                ) {
                    onConflict = "alert_id,receiver_id"
                }
                onSuccess()
            } catch (t: Throwable) {
                onError(t.message ?: "Could not confirm receipt")
            }
        }
    }

    fun registerFcmToken(tokenOverride: String? = null) {
        val user = supabase.auth.currentUserOrNull() ?: return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                scope.launch {
                    try {
                        supabase.from("profiles").upsert(
                            ProfilePatch(
                                id = user.id,
                                email = user.email,
                                fcm_token = token
                            )
                        )
                    } catch (_: Throwable) {}
                }
            }
    }
}
