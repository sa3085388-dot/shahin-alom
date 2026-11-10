package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val battery: Int
)

class LocationTracker(private val context: Context) {
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun getBatteryLevel(): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100).toInt()
            } else {
                100
            }
        } catch (e: Exception) {
            100
        }
    }

    @SuppressLint("MissingPermission")
    fun getLastLocation(onSuccess: (LocationInfo) -> Unit, onFailure: (String) -> Unit) {
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        Log.d("LiveTracker", "Location found: " + location.latitude + ", " + location.longitude)
                        val battery = getBatteryLevel()
                        onSuccess(LocationInfo(location.latitude, location.longitude, battery))
                    } else {
                        Log.d("LiveTracker", "Location is null, requesting new updates.")
                        onFailure("Location is null, requesting new updates.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("LiveTracker", "Error getting location: " + e.message)
                    onFailure("Error getting location: " + (e.message ?: "Unknown error"))
                }
        } catch (e: Exception) {
            Log.e("LiveTracker", "Exception getting location: " + e.message)
            onFailure("Exception: " + (e.message ?: "Unknown exception"))
        }
    }

    @SuppressLint("MissingPermission")
    fun observeLocationUpdates(intervalMs: Long = 300L): Flow<LocationInfo> = callbackFlow {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(150L)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                try {
                    result.lastLocation?.let { location ->
                        Log.d("LiveTracker", "Live update location found: " + location.latitude + ", " + location.longitude)
                        val battery = getBatteryLevel()
                        trySend(LocationInfo(location.latitude, location.longitude, battery))
                    }
                } catch (e: Exception) {
                    Log.e("LiveTracker", "Error processing location result", e)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e("LiveTracker", "Failed to request location updates", e)
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    Log.d("LiveTracker", "Initial lastLocation found: " + location.latitude + ", " + location.longitude)
                    val battery = getBatteryLevel()
                    trySend(LocationInfo(location.latitude, location.longitude, battery))
                }
            }.addOnFailureListener { e ->
                Log.w("LiveTracker", "Failed to get last location: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("LiveTracker", "Failed to access last location", e)
        }

        awaitClose {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (e: Exception) {
                Log.e("LiveTracker", "Error removing location updates", e)
            }
        }
    }
}
