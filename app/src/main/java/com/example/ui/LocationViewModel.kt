package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LocationService
import com.example.data.LocationRepository
import com.example.data.LocationTracker
import com.example.data.UserLocation
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LocationState(
    val myProfile: UserLocation? = null,
    val isSharing: Boolean = true, // Default to active sharing
    val myShareCode: String = "",
    val currentLat: Double = 23.8103, // Default Dhaka fallback
    val currentLng: Double = 90.4125,
    val battery: Int = 100,
    val trackedShareCode: String = "",
    val trackedUser: UserLocation? = null,
    val distanceMeters: Double? = null,
    val errorMessage: String? = null,
    val isLoading: Boolean = false
)

class LocationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocationRepository(application)
    private val locationTracker = LocationTracker(application)

    private val _state = MutableStateFlow(LocationState())
    val state: StateFlow<LocationState> = _state.asStateFlow()

    private var locationJob: Job? = null
    private var trackedUserJob: Job? = null

    init {
        try {
            initUserSession()
            startLocationUpdates()
        } catch (e: Exception) {
            Log.e("LocationViewModel", "Error in initUserSession", e)
        }
    }

    private val prefs = application.getSharedPreferences("livetracker_device_prefs", android.content.Context.MODE_PRIVATE)

    private fun getOrGenerateDeviceShareCode(): String {
        return try {
            var code = prefs.getString("device_unique_code", null)
            if (code.isNullOrBlank()) {
                val letters = ('A'..'Z').shuffled().take(2).joinToString("")
                val digits = (1000..9999).random()
                code = "$letters$digits"
                prefs.edit().putString("device_unique_code", code).apply()
            }
            code
        } catch (e: Exception) {
            "LT${(1000..9999).random()}"
        }
    }

    private fun initUserSession() {
        val user = try { Firebase.auth.currentUser } catch (e: Exception) { null }
        val defaultCode = getOrGenerateDeviceShareCode()
        val uid = user?.uid ?: "GUEST_$defaultCode"

        _state.update {
            it.copy(
                myShareCode = defaultCode,
                isSharing = true,
                myProfile = UserLocation(
                    userId = uid,
                    displayName = user?.displayName ?: "Guest User",
                    email = user?.email ?: "guest@tracker.app",
                    shareCode = defaultCode,
                    latitude = it.currentLat,
                    longitude = it.currentLng,
                    battery = locationTracker.getBatteryLevel(),
                    isSharing = true
                )
            )
        }

        viewModelScope.launch {
            try {
                repository.upsertMyProfile(
                    displayName = user?.displayName ?: "Guest User",
                    email = user?.email ?: "guest@tracker.app",
                    shareCode = defaultCode,
                    latitude = _state.value.currentLat,
                    longitude = _state.value.currentLng,
                    battery = _state.value.battery,
                    isSharing = true
                )
            } catch (e: Exception) {
                Log.e("LocationViewModel", "Initial upsert error", e)
            }
        }

        if (user != null) {
            viewModelScope.launch {
                repository.observeMyProfile(uid)
                    .catch { e -> Log.e("LocationViewModel", "Profile observe error", e) }
                    .collectLatest { profile ->
                        if (profile != null) {
                            _state.update {
                                it.copy(
                                    myProfile = profile,
                                    myShareCode = if (profile.shareCode.isBlank()) defaultCode else profile.shareCode,
                                    isSharing = profile.isSharing
                                )
                            }
                        }
                    }
            }
        }
    }

    fun toggleSharing(enable: Boolean, hasPermission: Boolean) {
        _state.update { it.copy(isSharing = enable, errorMessage = null) }

        viewModelScope.launch {
            try {
                repository.upsertMyProfile(
                    displayName = _state.value.myProfile?.displayName ?: "User",
                    email = _state.value.myProfile?.email ?: "",
                    shareCode = _state.value.myShareCode,
                    latitude = _state.value.currentLat,
                    longitude = _state.value.currentLng,
                    battery = _state.value.battery,
                    isSharing = enable
                )
            } catch (e: Exception) {
                Log.e("LocationViewModel", "toggleSharing error: ${e.message}")
            }
        }

        if (enable) {
            startLocationUpdates()
        } else {
            stopLocationUpdates()
        }
    }

    fun startLocationUpdates() {
        try {
            LocationService.startService(getApplication())
        } catch (e: Exception) {
            Log.e("LocationViewModel", "Failed to start LocationService", e)
        }

        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationTracker.observeLocationUpdates()
                .catch { e -> Log.e("LocationViewModel", "Location update stream error", e) }
                .collectLatest { info ->
                    _state.update {
                        it.copy(
                            currentLat = info.latitude,
                            currentLng = info.longitude,
                            battery = info.battery
                        )
                    }

                    if (_state.value.isSharing) {
                        try {
                            repository.updateLocationAndStatus(
                                info.latitude,
                                info.longitude,
                                info.battery,
                                true
                            )
                        } catch (e: Exception) {
                            Log.w("LocationViewModel", "Error pushing location update: ${e.message}")
                        }
                    }

                    recalculateDistance()
                }
        }
    }

    fun stopLocationUpdates() {
        try {
            LocationService.stopService(getApplication())
        } catch (e: Exception) {
            Log.e("LocationViewModel", "Failed to stop LocationService", e)
        }
        locationJob?.cancel()
        locationJob = null
    }

    fun setTrackedShareCode(code: String) {
        val cleanCode = code.trim().uppercase()
        _state.update { it.copy(trackedShareCode = cleanCode, trackedUser = null, distanceMeters = null) }

        trackedUserJob?.cancel()
        if (cleanCode.length >= 4) {
            trackedUserJob = viewModelScope.launch {
                repository.observeUserByShareCode(cleanCode)
                    .catch { e -> Log.e("LocationViewModel", "Tracked user flow error", e) }
                    .collectLatest { targetUser ->
                        _state.update { it.copy(trackedUser = targetUser) }
                        recalculateDistance()
                    }
            }
        }
    }

    private fun recalculateDistance() {
        try {
            val current = _state.value
            val target = current.trackedUser
            if (target != null && target.latitude != 0.0 && target.longitude != 0.0) {
                val dist = calculateDistanceMeters(
                    current.currentLat, current.currentLng,
                    target.latitude, target.longitude
                )
                _state.update { it.copy(distanceMeters = dist) }
            } else {
                _state.update { it.copy(distanceMeters = null) }
            }
        } catch (e: Exception) {
            Log.e("LocationViewModel", "Error calculating distance", e)
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
