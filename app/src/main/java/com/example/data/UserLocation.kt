package com.example.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserLocation(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val shareCode: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val battery: Int = 100,
    val isSharing: Boolean = false,
    @ServerTimestamp val lastUpdated: Timestamp? = null
)
