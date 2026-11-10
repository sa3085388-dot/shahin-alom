package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class LocationRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        try {
            FirebaseFirestore.getInstance(
                context.applicationContext.getString(com.example.R.string.firestore_database_id)
            )
        } catch (e: Exception) {
            Log.e("LocationRepo", "Error getting Firestore instance", e)
            FirebaseFirestore.getInstance()
        }
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid ?: "guest_user_id"
    }

    fun observeMyProfile(userId: String): Flow<UserLocation?> = callbackFlow {
        if (userId.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val docRef = db.collection("users").document(userId)
        val listener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("LocationRepo", "Error observing profile: ${error.message}", error)
                trySend(null)
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val user = try {
                    snapshot.toObject(UserLocation::class.java)
                } catch (e: Exception) {
                    Log.e("LocationRepo", "Error parsing profile document", e)
                    null
                }
                trySend(user)
            } else {
                trySend(null)
            }
        }
        awaitClose { listener.remove() }
    }

    suspend fun upsertMyProfile(
        displayName: String,
        email: String,
        shareCode: String,
        latitude: Double,
        longitude: Double,
        battery: Int,
        isSharing: Boolean
    ) {
        try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
            val data = mapOf(
                "userId" to uid,
                "displayName" to displayName,
                "email" to email,
                "shareCode" to shareCode,
                "latitude" to latitude,
                "longitude" to longitude,
                "battery" to battery,
                "isSharing" to isSharing,
                "lastUpdated" to FieldValue.serverTimestamp()
            )
            docRef.set(data, com.google.firebase.firestore.SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e("LocationRepo", "Error upserting profile: ${e.message}", e)
        }
    }

    suspend fun updateLocationAndStatus(
        latitude: Double,
        longitude: Double,
        battery: Int,
        isSharing: Boolean
    ) {
        try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid)
            val updates = mapOf(
                "latitude" to latitude,
                "longitude" to longitude,
                "battery" to battery,
                "isSharing" to isSharing,
                "lastUpdated" to FieldValue.serverTimestamp()
            )
            docRef.set(updates, com.google.firebase.firestore.SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e("LocationRepo", "Error updating location: ${e.message}", e)
        }
    }

    fun observeUserByShareCode(shareCode: String): Flow<UserLocation?> = callbackFlow {
        val cleanCode = shareCode.trim().uppercase()
        if (cleanCode.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val query = db.collection("users").whereEqualTo("shareCode", cleanCode)
        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("LocationRepo", "Error observing shareCode: ${error.message}", error)
                trySend(null)
                return@addSnapshotListener
            }
            if (snapshot != null && !snapshot.isEmpty) {
                val doc = snapshot.documents.firstOrNull()
                val user = try {
                    doc?.toObject(UserLocation::class.java)
                } catch (e: Exception) {
                    Log.e("LocationRepo", "Error parsing target user document", e)
                    null
                }
                trySend(user)
            } else {
                trySend(null)
            }
        }
        awaitClose { listener.remove() }
    }
}
