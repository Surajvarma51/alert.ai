package ai.alert.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import ai.alert.app.data.AlertRepository
import ai.alert.app.data.AuthRepository
import ai.alert.app.location.LocationProvider
import ai.alert.app.ui.theme.AlertAiTheme

class MainActivity : ComponentActivity() {
    private lateinit var repository: AlertRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var locationProvider: LocationProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AlertRepository(applicationContext)
        authRepository = AuthRepository(applicationContext)
        locationProvider = LocationProvider(applicationContext)

        setContent {
            AlertAiTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF08090B)) {
                    AlertApp(repository, authRepository, locationProvider)
                }
            }
        }
    }
}

private enum class Screen { AUTH, HOME, ACCOUNT, EDIT_PROFILE, SECURITY }

@Composable
private fun AlertApp(
    repository: AlertRepository,
    authRepository: AuthRepository,
    locationProvider: LocationProvider
) {
    var screen by remember { mutableStateOf<Screen?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var authRefresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        initialized = authRepository.initialize()
        if (initialized) {
            repository.initialize()
            screen = if (authRepository.currentUser() == null) Screen.AUTH else Screen.HOME
        }
    }

    if (!initialized || screen == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    when (screen) {
        Screen.AUTH -> AuthScreen(authRepository) {
            authRefresh++
            screen = Screen.HOME
        }
        Screen.HOME -> HomeScreen(
            repository = repository,
            authRepository = authRepository,
            locationProvider = locationProvider,
            onAccount = { screen = Screen.ACCOUNT }
        )
        Screen.ACCOUNT -> AccountScreen(
            authRepository = authRepository,
            onBack = { screen = Screen.HOME },
            onEditProfile = { screen = Screen.EDIT_PROFILE },
            onSecurity = { screen = Screen.SECURITY },
            onSignedOut = { authRefresh++; screen = Screen.AUTH },
            onDeleted = { authRefresh++; screen = Screen.AUTH }
        )
        Screen.EDIT_PROFILE -> EditProfileScreen(
            authRepository,
            onBack = { screen = Screen.ACCOUNT }
        )
        Screen.SECURITY -> SecurityScreen(
            authRepository,
            onBack = { screen = Screen.ACCOUNT }
        )
        null -> Unit
    }
}

@Composable
private fun AuthScreen(auth: AuthRepository, onSignedIn: () -> Unit) {
    val activity = LocalContext.current as? Activity
    var register by remember { mutableStateOf(false) }
    var verificationMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var resetMode by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("alert.ai", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        if (verificationMode) {
            Text("Verify your email", color = Color(0xFF9EA3AA))
            Spacer(Modifier.height(8.dp))
            Text(email, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Text(
                "We sent a 6-digit verification code to your email.",
                color = Color(0xFF9EA3AA),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Field("6-digit code", otp) { otp = it.take(6).filter(Char::isDigit) }
            Spacer(Modifier.height(10.dp))
            if (message.isNotBlank()) {
                Text(message, color = Color(0xFFFFB4AE), textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
            }
            Button(
                enabled = !busy && otp.length == 6,
                onClick = {
                    busy = true
                    message = ""
                    auth.verifyEmailOtp(
                        otp,
                        onSuccess = {
                            busy = false
                            message = "Email verified."
                            onSignedIn()
                        },
                        onError = {
                            busy = false
                            message = it
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("VERIFY EMAIL")
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    message = ""
                    auth.requestEmailOtp(
                        onSuccess = {
                            busy = false
                            message = "A new verification code was sent."
                        },
                        onError = {
                            busy = false
                            message = it
                        }
                    )
                }
            ) {
                Text("RESEND CODE")
            }
        } else {
            Text(
                if (resetMode) "Reset your password"
                else if (register) "Create your account"
                else "Sign in to alert.ai",
                color = Color(0xFF9EA3AA)
            )
            Spacer(Modifier.height(28.dp))

            if (!resetMode) {
                OutlinedButton(
                    enabled = !busy && activity != null,
                    onClick = {
                        val host = activity ?: return@OutlinedButton
                        busy = true
                        message = ""
                        auth.signInWithGoogle(
                            activity = host,
                            onSuccess = {
                                busy = false
                                onSignedIn()
                            },
                            onError = {
                                busy = false
                                message = it
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("G  CONTINUE WITH GOOGLE", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(Modifier.weight(1f), color = Color(0xFF30343A))
                    Text("  OR  ", color = Color(0xFF777C84), fontSize = 12.sp)
                    HorizontalDivider(Modifier.weight(1f), color = Color(0xFF30343A))
                }
                Spacer(Modifier.height(14.dp))
            }

            if (register && !resetMode) Field("Name", name) { name = it }
            Field("Email", email) { email = it }
            Field("Password", password, true) { password = it }

            if (message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(message, color = Color(0xFFFFB4AE), textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(18.dp))
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    message = ""
                    if (resetMode) {
                        auth.sendPasswordReset(
                            email,
                            { busy = false; message = "Password reset email sent." },
                            { busy = false; message = it }
                        )
                    } else if (register) {
                        auth.signUp(
                            name,
                            email,
                            password,
                            {
                                busy = false
                                verificationMode = true
                                message = ""
                            },
                            {
                                busy = false
                                message = it
                            }
                        )
                    } else {
                        auth.signIn(
                            email,
                            password,
                            { busy = false; onSignedIn() },
                            { busy = false; message = it }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (resetMode) "SEND RESET EMAIL" else if (register) "CREATE ACCOUNT" else "SIGN IN")
            }

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = {
                message = ""
                resetMode = false
                register = !register
            }) {
                Text(if (register) "Already have an account? Sign in" else "Create a new account")
            }
            if (!register && !resetMode) {
                TextButton(onClick = { resetMode = true; message = "" }) {
                    Text("Forgot password?")
                }
            }
            if (resetMode) {
                TextButton(onClick = { resetMode = false; message = "" }) {
                    Text("Back to sign in")
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    repository: AlertRepository,
    authRepository: AuthRepository,
    locationProvider: LocationProvider,
    onAccount: () -> Unit
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Ready to send") }
    var locationReady by remember { mutableStateOf(false) }
    var firebaseReady by remember { mutableStateOf(false) }

    val registerNearbyPresence: () -> Unit = {
        if (locationProvider.hasLocationPermission()) {
            locationProvider.getCurrentLocation(
                onSuccess = { location ->
                    repository.registerPresence(location.latitude, location.longitude) {
                        status = it
                    }
                    locationReady = true
                    status = "Nearby alerts enabled"
                },
                onError = { status = it }
            )
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) registerNearbyPresence()
        else status = "Location permission is required for nearby alerts"
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        firebaseReady = repository.initialize()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (locationProvider.hasLocationPermission()) registerNearbyPresence()
        else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun sendFast() {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return
        }
        status = "Getting current location…"
        locationProvider.getCurrentLocation(
            onSuccess = { location ->
                status = "Sending FAST alert…"
                repository.sendFastAlert(location.latitude, location.longitude, {
                    status = "Alert sent"; locationReady = true
                }, { status = it })
            },
            onError = { status = it }
        )
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF08090B)).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("alert.ai", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Fast local hazard reporting", color = Color(0xFF8D929A), fontSize = 14.sp)
            }
            TextButton(onClick = onAccount) { Text("ACCOUNT") }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(230.dp).clip(CircleShape).background(Color(0xFF7F1D1D)), contentAlignment = Alignment.Center) {
            Button(
                onClick = ::sendFast,
                modifier = Modifier.size(196.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30), contentColor = Color.White)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("FAST", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                    Text("ALERT", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        StatusCard("Status", status, status == "Ready to send" || status == "Alert sent" || status == "Nearby alerts enabled")
        Spacer(Modifier.height(12.dp))
        StatusCard("Location", if (locationReady) "Nearby alerts enabled" else "Permission required", locationReady)
        Spacer(Modifier.height(12.dp))
        StatusCard("Network", if (firebaseReady) "Online backend ready" else "Firebase not configured", firebaseReady)
        Spacer(Modifier.height(20.dp))
        Text(
            "Your location is refreshed when the app opens and when you send FAST. alert.ai does not continuously track your location in V1.",
            color = Color(0xFF777C84), fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AccountScreen(
    authRepository: AuthRepository,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onSecurity: () -> Unit,
    onSignedOut: () -> Unit,
    onDeleted: () -> Unit
) {
    val user = authRepository.currentUser()
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("BACK") }
            Text("MY ACCOUNT", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(user?.displayName ?: "User", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(user?.email ?: "", color = Color(0xFF9EA3AA))
        Spacer(Modifier.height(12.dp))
        StatusCard("Email verification", if (user?.isEmailVerified == true) "Verified" else "Not verified", user?.isEmailVerified == true)
        Spacer(Modifier.height(18.dp))
        AccountAction("EDIT PROFILE", onEditProfile)
        AccountAction("SECURITY", onSecurity)
        if (user?.isEmailVerified != true) AccountAction("SEND VERIFICATION EMAIL") {
            authRepository.sendVerification({ message = "Verification email sent." }, { message = it })
        }
        AccountAction("REFRESH ACCOUNT STATUS") {
            authRepository.refreshUser({ message = "Account status refreshed." }, { message = it })
        }
        AccountAction("SIGN OUT") {
            authRepository.signOut()
            onSignedOut()
        }
        AccountAction("DELETE ACCOUNT") { showDelete = true }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = Color(0xFFB9BDC5))
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDelete = false },
            title = { Text("Delete account?") },
            text = {
                Column {
                    Text("This permanently deletes your alert.ai account and profile. This cannot be undone.")
                    Spacer(Modifier.height(12.dp))
                    Field("Current password", deletePassword, true) { deletePassword = it }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    authRepository.deleteAccount(deletePassword, { busy = false; showDelete = false; onDeleted() }, { busy = false; message = it })
                }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun EditProfileScreen(auth: AuthRepository, onBack: () -> Unit) {
    val user = auth.currentUser()
    var name by remember { mutableStateOf(user?.displayName ?: "") }
    var email by remember { mutableStateOf(user?.email ?: "") }
    var currentPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("BACK") }
        Text("EDIT PROFILE", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Field("Name", name) { name = it }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { auth.updateDisplayName(name, { message = "Name updated." }, { message = it }) }, Modifier.fillMaxWidth()) { Text("SAVE NAME") }
        Spacer(Modifier.height(24.dp))
        Text("Change email", color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Field("New email", email) { email = it }
        Field("Current password", currentPassword, true) { currentPassword = it }
        Button(onClick = { auth.updateEmail(email, currentPassword, { message = "Email updated. Verification email sent." }, { message = it }) }, Modifier.fillMaxWidth()) { Text("UPDATE EMAIL") }
        Spacer(Modifier.height(14.dp))
        Text(message, color = Color(0xFFB9BDC5))
    }
}

@Composable
private fun SecurityScreen(auth: AuthRepository, onBack: () -> Unit) {
    var newPassword by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val hasPassword = auth.hasPasswordProvider()

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("BACK") }
        Text("SECURITY", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        if (hasPassword) {
            Field("Current password", current, true) { current = it }
            Field("New password", newPassword, true) { newPassword = it }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    auth.changePassword(
                        current,
                        newPassword,
                        { message = "Password changed." },
                        { message = it }
                    )
                },
                Modifier.fillMaxWidth()
            ) {
                Text("CHANGE PASSWORD")
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Password changes require recent authentication.",
                color = Color(0xFF777C84),
                fontSize = 12.sp
            )
        } else {
            Text(
                "Your account currently uses Google sign-in. Set an alert.ai password to also sign in with your email.",
                color = Color(0xFF9EA3AA),
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(16.dp))
            Field("New password", newPassword, true) { newPassword = it }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    auth.setPassword(
                        newPassword,
                        { message = "Password set. You can now sign in with email and password." },
                        { message = it }
                    )
                },
                Modifier.fillMaxWidth()
            ) {
                Text("SET PASSWORD")
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(message, color = Color(0xFFB9BDC5))
    }
}

@Composable
private fun AccountAction(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(label) }
}

@Composable
private fun Field(label: String, value: String, password: Boolean = false, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        singleLine = true,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

@Composable
private fun StatusCard(title: String, value: String, good: Boolean) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), Color(0xFF12151A)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color(0xFF737982), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = if (good) Color(0xFFE7E9EC) else Color(0xFFFFB4AE), fontSize = 14.sp)
        }
    }
}
