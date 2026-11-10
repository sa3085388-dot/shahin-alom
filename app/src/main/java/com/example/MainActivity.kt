package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.example.ui.AuthScreen
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    enableHighRefreshRate()
    setContent {
      MyApplicationTheme {
        AppContent()
      }
    }
  }

  private fun enableHighRefreshRate() {
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val disp = display
        val modes = disp?.supportedModes
        val highestMode = modes?.maxByOrNull { it.refreshRate }
        if (highestMode != null && highestMode.refreshRate >= 60f) {
          window.attributes = window.attributes.apply {
            preferredDisplayModeId = highestMode.modeId
          }
        }
      } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        val modes = windowManager.defaultDisplay.supportedModes
        val highestMode = modes.maxByOrNull { it.refreshRate }
        if (highestMode != null && highestMode.refreshRate >= 60f) {
          window.attributes = window.attributes.apply {
            preferredDisplayModeId = highestMode.modeId
          }
        }
      }
    } catch (e: Exception) {
      // ignore
    }
  }
}

@Composable
fun AppContent() {
  var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }
  var isGuestMode by remember { mutableStateOf(false) }

  DisposableEffect(Unit) {
    val listener = FirebaseAuth.AuthStateListener { auth ->
      currentUser = auth.currentUser
    }
    Firebase.auth.addAuthStateListener(listener)
    onDispose {
      Firebase.auth.removeAuthStateListener(listener)
    }
  }

  if (currentUser == null && !isGuestMode) {
    AuthScreen(
      onAuthSuccess = { currentUser = Firebase.auth.currentUser },
      onGuestSuccess = { isGuestMode = true }
    )
  } else {
    val uid = currentUser?.uid ?: "guest_user_id"
    key(uid) {
      MainScreen(
        currentUserId = uid
      )
    }
  }
}
