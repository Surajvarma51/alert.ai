package ai.alert.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import ai.alert.app.data.*
import ai.alert.app.location.LocationProvider
import ai.alert.app.nearby.NearbyAlertManager
import ai.alert.app.ui.theme.AlertAiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : FragmentActivity() {
    private lateinit var alerts: AlertRepository
    private lateinit var auth: AuthRepository
    private lateinit var location: LocationProvider
    private lateinit var nearby: NearbyAlertManager
    private var incomingAlert by mutableStateOf<String?>(null)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alerts = AlertRepository(applicationContext)
        auth = AuthRepository(applicationContext)
        location = LocationProvider(applicationContext)
        nearby = NearbyAlertManager(applicationContext)
        handleIntent(intent)
        nearby.onAlertReceived = { alert, endpoint -> runOnUiThread { incomingAlert = alert.alertId + "|" + endpoint } }
        setContent { AlertAiTheme { Surface(Modifier.fillMaxSize(), color = Color(0xFF08090B)) { App() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("alert_id")?.let { incomingAlert = it }
    }

    private fun requestPermissions() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_ADVERTISE
            p += Manifest.permission.BLUETOOTH_CONNECT
            p += Manifest.permission.BLUETOOTH_SCAN
        }
        if (android.os.Build.VERSION.SDK_INT >= 32) p += Manifest.permission.NEARBY_WIFI_DEVICES
        permissions.launch(p.toTypedArray())
    }

    private fun online(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun biometricAvailable() =
        BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS

    private fun biometric(onSuccess: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                runOnUiThread(onSuccess)
                executor.shutdown()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { executor.shutdown() }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock alert.ai")
                .setSubtitle("Use fingerprint or device credential")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
        )
    }

    @Composable
    private fun App() {
        var signedIn by remember { mutableStateOf(auth.currentUser() != null) }
        var locked by remember { mutableStateOf(signedIn && getSharedPreferences("alertai", 0).getBoolean("biometric", false)) }
        var tab by remember { mutableIntStateOf(0) }
        val received = incomingAlert
        LaunchedEffect(locked) { if (locked) biometric { locked = false } }

        if (!signedIn) {
            AuthScreen { signedIn = true; alerts.initialize(); requestPermissions(); nearby.start() }
            return
        }
        if (locked) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Unlocking alert.ai…", color = Color.White) }
            return
        }

        Scaffold(
            containerColor = Color(0xFF08090B),
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0E1014)) {
                    NavItem("Home", "⌂", tab == 0) { tab = 0 }
                    NavItem("Nearby", "◉", tab == 1) { tab = 1 }
                    NavItem("My Account", "●", tab == 2) { tab = 2 }
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp)) {
                when (tab) {
                    0 -> HomeScreen()
                    1 -> NearbyScreen()
                    2 -> AccountScreen { signedIn = false }
                }
            }
        }

        received?.let {
            val parts = it.split("|", limit = 2)
            ReceivedAlertDialog(
                onConfirm = {
                    alerts.acknowledgeAlert(parts[0], {}, {})
                    if (parts.size == 2) nearby.sendNearbyAck(parts[0], parts[1], auth.currentUser()?.id.orEmpty())
                    incomingAlert = null
                },
                onDismiss = { incomingAlert = null }
            )
        }
    }

    @Composable
    private fun AuthScreen(onSignedIn: () -> Unit) {
        var register by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val activity = LocalContext.current as Activity

        Column(
            Modifier.fillMaxSize().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("alert.ai", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            Text("Community hazard alerts", color = Color(0xFF8F959D))
            Spacer(Modifier.height(28.dp))
            OutlinedButton(
                enabled = !busy,
                onClick = { busy = true; auth.signInWithGoogle(activity, { busy = false; onSignedIn() }, { busy = false; message = it }) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("G  CONTINUE WITH GOOGLE", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(18.dp))
            Text(if (register) "Create account" else "Sign in", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (register) Field("Name", name) { name = it }
            Field("Email", email) { email = it }
            Field("Password", password, true) { password = it }
            if (message.isNotBlank()) Text(message, color = Color(0xFFFFB4AE), textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    if (register) auth.signUp(name, email, password, { busy = false; onSignedIn() }, { busy = false; message = it })
                    else auth.signIn(email, password, { busy = false; onSignedIn() }, { busy = false; message = it })
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (register) "CREATE ACCOUNT" else "SIGN IN") }
            TextButton(onClick = { register = !register; message = "" }) { Text(if (register) "Already have an account?" else "Create an account") }
            if (!register) TextButton(onClick = { auth.sendPasswordReset(email, { message = "Reset email sent." }, { message = it }) }) { Text("Forgot password?") }
        }
    }

    @Composable
    private fun HomeScreen() {
        var status by remember { mutableStateOf("Ready") }
        var locationReady by remember { mutableStateOf(false) }
        var lastAlert by remember { mutableStateOf<String?>(null) }
        var receipts by remember { mutableStateOf<List<ReceiptRow>>(emptyList()) }

        LaunchedEffect(lastAlert) {
            while (isActive && lastAlert != null) {
                alerts.getReceipts(lastAlert!!) { receipts = it }
                delay(2000)
            }
        }

        fun sendAlert() {
            status = "Getting current location…"
            location.getCurrentLocation({ loc ->
                locationReady = true
                val nearbyPayload = NearbyAlertPayload(
                    alertId = UUID.randomUUID().toString(),
                    senderId = auth.currentUser()?.id.orEmpty(),
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    createdAt = System.currentTimeMillis()
                )
                if (online()) {
                    status = "Sending alert…"
                    alerts.sendAlert(loc.latitude, loc.longitude,
                        { id -> lastAlert = id; status = "Alert sent"; nearby.broadcastAlert(nearbyPayload) },
                        { status = it }
                    )
                } else {
                    nearby.broadcastAlert(nearbyPayload)
                    status = if (nearby.connectedCount() > 0) "Nearby alert sent" else "No network or nearby device"
                }
            }, { status = it })
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("alert.ai", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Report a hazard", color = Color(0xFF858B93))
                }
                StatusDot(online())
            }
            Spacer(Modifier.height(28.dp))
            Box(Modifier.size(250.dp).clip(CircleShape).background(Color(0xFF451212)), contentAlignment = Alignment.Center) {
                Button(
                    onClick = ::sendAlert,
                    modifier = Modifier.size(214.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE52B22))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SEND", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                        Text("ALERT", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            StatusCard("Status", status)
            StatusCard("Location", if (locationReady) "Current location ready" else "Location used only when needed")
            StatusCard("Connection", if (online()) "Internet available" else "Offline — nearby mode")
            if (lastAlert != null) {
                Text("DELIVERY REPORT", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                StatusCard("Sent", receipts.count { it.status == "SENT" }.toString())
                StatusCard("Delivered", receipts.count { it.status == "DELIVERED" }.toString())
                StatusCard("Acknowledged", receipts.count { it.status == "ACKNOWLEDGED" }.toString())
            }
            Text(
                "Your location is refreshed only when needed. alert.ai does not continuously track you.",
                color = Color(0xFF6F757D), fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
    }

    @Composable
    private fun NearbyScreen() {
        var status by remember { mutableStateOf("Nearby mode ready") }
        LaunchedEffect(Unit) { nearby.onStatus = { status = it }; nearby.start() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 20.dp)) {
            Text("WITHOUT INTERNET", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Nearby alerts", color = Color(0xFF858B93), fontSize = 15.sp)
            Spacer(Modifier.height(20.dp))
            StatusCard("Status", status)
            StatusCard("Range", "Nearby radio range; not a 5 km guarantee")
            StatusCard("Internet", if (online()) "Available" else "Not available")
            Text(
                "Nearby Connections can exchange small alert messages without internet using Bluetooth/BLE/Wi‑Fi. Android nearby permissions are required.",
                color = Color(0xFF8D929A), lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    @Composable
    private fun AccountScreen(onSignedOut: () -> Unit) {
        val user = auth.currentUser()
        val prefs = getSharedPreferences("alertai", 0)
        var biometricEnabled by remember { mutableStateOf(prefs.getBoolean("biometric", false)) }
        var message by remember { mutableStateOf("") }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 20.dp)) {
            Text("MY ACCOUNT", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text(user?.email.orEmpty(), color = Color(0xFF858B93))
            Spacer(Modifier.height(20.dp))
            StatusCard("Account", "Signed in")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Biometric app unlock", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Fingerprint or device credential", color = Color(0xFF7D838B), fontSize = 12.sp)
                }
                Switch(
                    checked = biometricEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !biometricAvailable()) message = "Biometric or device credential is not available."
                        else { biometricEnabled = enabled; prefs.edit().putBoolean("biometric", enabled).apply() }
                    }
                )
            }
            AccountAction("PASSWORD RESET") { message = "Use Forgot password from the sign-in screen." }
            AccountAction("SIGN OUT") { auth.signOut { onSignedOut() } }
            if (message.isNotBlank()) Text(message, color = Color(0xFFB9BDC5), modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(18.dp))
            Text("Privacy", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Fingerprint data stays on the device. alert.ai receives only the success/failure result from Android's biometric system.", color = Color(0xFF777D85), fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable private fun RowScope.NavItem(label: String, icon: String, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick, icon = { Text(icon, fontSize = 18.sp) }, label = { Text(label, fontSize = 11.sp) })
}

@Composable private fun StatusDot(online: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (online) Color(0xFF41D17D) else Color(0xFFE0A12A)))
        Spacer(Modifier.width(6.dp))
        Text(if (online) "ONLINE" else "OFFLINE", color = Color(0xFF8B9198), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun StatusCard(title: String, value: String) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 5.dp), RoundedCornerShape(16.dp), Color(0xFF12151A)) {
        Column(Modifier.padding(15.dp)) {
            Text(title, color = Color(0xFF727880), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable private fun AccountAction(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(label) }
}

@Composable private fun Field(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), singleLine = true,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

@Composable private fun ReceivedAlertDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("⚠️ ALERT RECEIVED") },
        text = { Text("A SEND ALERT was received. Confirm that you received it so the sender can see the acknowledgement.") },
        confirmButton = { Button(onClick = onConfirm) { Text("I RECEIVED") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("LATER") } }
    )
}
