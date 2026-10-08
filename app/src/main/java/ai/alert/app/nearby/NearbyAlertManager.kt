package ai.alert.app.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import ai.alert.app.data.NearbyAckPayload
import ai.alert.app.data.NearbyAlertPayload
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.UUID

class NearbyAlertManager(private val context: Context) {
    companion object { private const val SERVICE_ID = "ai.alert.app.nearby" }
    private val client = Nearby.getConnectionsClient(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val connected = mutableSetOf<String>()
    var onAlertReceived: ((NearbyAlertPayload, String) -> Unit)? = null
    var onAckReceived: ((NearbyAckPayload) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    private val strategy = Strategy.P2P_CLUSTER

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val text = payload.asBytes()?.toString(StandardCharsets.UTF_8) ?: return
            try {
                when {
                    text.contains("\"type\":\"SEND_ALERT\"") -> {
                        val alert = json.decodeFromString<NearbyAlertPayload>(text)
                        onAlertReceived?.invoke(alert, endpointId)
                        send(endpointId, Json.encodeToString(NearbyAckPayload(alertId = alert.alertId, receiverId = UUID.randomUUID().toString())))
                    }
                    text.contains("\"type\":\"ACK\"") -> onAckReceived?.invoke(json.decodeFromString<NearbyAckPayload>(text))
                }
            } catch (_: Throwable) {}
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) { client.acceptConnection(endpointId, payloadCallback) }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) { connected += endpointId; onStatus?.invoke("Nearby: " + connected.size + " device(s) connected") }
            else { connected -= endpointId; onStatus?.invoke("Nearby connection failed") }
        }
        override fun onDisconnected(endpointId: String) { connected -= endpointId; onStatus?.invoke("Nearby: " + connected.size + " device(s) connected") }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            client.requestConnection("alert.ai", endpointId, lifecycle, ConnectionOptions.Builder().build())
        }
        override fun onEndpointLost(endpointId: String) = Unit
    }

    fun hasPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        if (android.os.Build.VERSION.SDK_INT >= 32) permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun start() {
        if (!hasPermissions()) { onStatus?.invoke("Nearby permissions required"); return }
        client.startAdvertising("alert.ai", SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).setLowPower(false).build())
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
        onStatus?.invoke("Nearby mode active")
    }
    fun stop() { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints(); connected.clear(); onStatus?.invoke("Nearby mode stopped") }
    fun broadcastAlert(alert: NearbyAlertPayload) {
        val bytes = Json.encodeToString(alert).toByteArray(StandardCharsets.UTF_8)
        if (connected.isEmpty()) { onStatus?.invoke("No nearby devices connected"); return }
        client.sendPayload(connected.toList(), Payload.fromBytes(bytes))
    }
    fun sendNearbyAck(alertId: String, endpointId: String, receiverId: String) {
        send(endpointId, Json.encodeToString(NearbyAckPayload(alertId = alertId, receiverId = receiverId)))
    }

    private fun send(endpointId: String, text: String) {
        client.sendPayload(endpointId, Payload.fromBytes(text.toByteArray(StandardCharsets.UTF_8)))
    }

    fun connectedCount(): Int = connected.size
}