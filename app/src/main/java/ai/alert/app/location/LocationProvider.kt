package ai.alert.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationProvider(context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    fun getCurrentLocation(
        onSuccess: (android.location.Location) -> Unit,
        onError: (String) -> Unit
    ) {
        val fine = contextCheck(
            context = client,
            permission = Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarse = contextCheck(
            context = client,
            permission = Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (!fine && !coarse) {
            onError("Location permission is required")
            return
        }

        val request = CurrentLocationRequest.Builder()
            .setPriority(
                if (fine) Priority.PRIORITY_HIGH_ACCURACY
                else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            )
            .setMaxUpdateAgeMillis(5_000)
            .setDurationMillis(15_000)
            .build()

        try {
            client.getCurrentLocation(request, null)
                .addOnSuccessListener { location ->
                    if (location != null) onSuccess(location)
                    else onError("Could not get a current location")
                }
                .addOnFailureListener {
                    onError("Location services failed")
                }
        } catch (_: SecurityException) {
            onError("Location permission is required")
        }
    }

    private fun contextCheck(
        context: FusedLocationProviderClient,
        permission: String
    ): Boolean {
        val appContext = context.javaClass
            .getDeclaredField("mContext")
            .let { field ->
                field.isAccessible = true
                field.get(context) as Context
            }

        return appContext.checkSelfPermission(permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
