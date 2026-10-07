package ai.alert.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import ai.alert.app.location.LocationProvider
import ai.alert.app.ui.theme.AlertAiTheme

class MainActivity : ComponentActivity() {

    private lateinit var repository: AlertRepository
    private lateinit var locationProvider: LocationProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = AlertRepository(applicationContext)
        locationProvider = LocationProvider(applicationContext)

        setContent {
            AlertAiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF08090B)
                ) {
                    AlertHome(
                        repository = repository,
                        locationProvider = locationProvider
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertHome(
    repository: AlertRepository,
    locationProvider: LocationProvider
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Ready to send") }
    var firebaseReady by remember { mutableStateOf<Boolean?>(null) }
    var locationReady by remember { mutableStateOf(false) }

    val registerNearbyPresence: () -> Unit = {
        if (locationProvider.hasLocationPermission()) {
            locationProvider.getCurrentLocation(
                onSuccess = { location ->
                    repository.registerPresence(
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                    locationReady = true
                    status = "Nearby alerts enabled"
                },
                onError = { status = it }
            )
        }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted =
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            registerNearbyPresence()
        } else {
            status = "Location permission is required for nearby alerts"
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        firebaseReady = repository.initialize()

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (locationProvider.hasLocationPermission()) {
            registerNearbyPresence()
        } else {
            permissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    fun sendFast() {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) {
            permissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        status = "Getting current location…"
        locationProvider.getCurrentLocation(
            onSuccess = { location ->
                status = "Sending FAST alert…"
                repository.sendFastAlert(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    onSuccess = {
                        status = "Alert sent"
                        locationReady = true
                    },
                    onError = { error -> status = error }
                )
            },
            onError = { error -> status = error }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08090B))
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "alert.ai",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Fast local hazard reporting",
            color = Color(0xFF8D929A),
            fontSize = 14.sp
        )

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(230.dp)
                .clip(CircleShape)
                .background(Color(0xFF7F1D1D)),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = ::sendFast,
                modifier = Modifier.size(196.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF3B30),
                    contentColor = Color.White
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "FAST",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "ALERT",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        StatusCard(
            title = "Status",
            value = status,
            good = status == "Ready to send" ||
                status == "Alert sent" ||
                status == "Nearby alerts enabled"
        )

        Spacer(Modifier.height(12.dp))

        StatusCard(
            title = "Location",
            value = if (locationReady) "Nearby alerts enabled" else "Permission required",
            good = locationReady
        )

        Spacer(Modifier.height(12.dp))

        StatusCard(
            title = "Network",
            value = when (firebaseReady) {
                true -> "Online backend ready"
                false -> "Firebase not configured"
                null -> "Checking…"
            },
            good = firebaseReady == true
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Your location is refreshed when the app opens and when you send FAST. alert.ai does not continuously track your location in V1.",
            color = Color(0xFF777C84),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = {
            status = "Nearby alerts use your recent app-open location."
        }) {
            Text("Privacy by design", color = Color(0xFFB7BBC2))
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    good: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF12151A)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                title,
                color = Color(0xFF737982),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                value,
                color = if (good) Color(0xFFE7E9EC) else Color(0xFFFFB4AE),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
