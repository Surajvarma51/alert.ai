package ai.alert.app.data

import kotlinx.serialization.Serializable

@Serializable
data class ProfileRow(
    val id: String,
    val display_name: String? = null,
    val email: String? = null,
    val fcm_token: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val last_seen: String? = null
)

@Serializable
data class AlertRow(
    val id: String? = null,
    val sender_id: String,
    val latitude: Double,
    val longitude: Double,
    val delivery_mode: String,
    val created_at: String? = null
)

@Serializable
data class ReceiptRow(
    val id: String? = null,
    val alert_id: String,
    val receiver_id: String,
    val status: String,
    val delivered_at: String? = null,
    val acknowledged_at: String? = null
)

@Serializable
data class NearbyAlertPayload(
    val version: Int = 1,
    val type: String = "SEND_ALERT",
    val alertId: String,
    val senderId: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long,
    val senderName: String? = null
)

@Serializable
data class NearbyAckPayload(
    val version: Int = 1,
    val type: String = "ACK",
    val alertId: String,
    val receiverId: String
)
