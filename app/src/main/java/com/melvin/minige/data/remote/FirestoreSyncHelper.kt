package com.melvin.minige.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FirestoreOfflineQueuedException(
    message: String = "Saved offline — will auto-sync when online",
    cause: Throwable? = null,
) : Exception(message, cause)

object FirestoreSyncHelper {

    const val FORCE_SYNC_TIMEOUT_MS = 15_000L
    const val MSG_SYNCED_TO_CLOUD = "Synced to Cloud ✓"
    const val MSG_SAVED_OFFLINE = "Saved offline — will auto-sync when online"
    const val MSG_SYNC_TIMEOUT = "Sync timed out (15s). Mahina o walang internet connection."

    /**
     * Safely enables Firestore persistent local cache before the instance executes queries.
     * Uses the caller's configured [FirebaseFirestore] instance (named database from strings.xml).
     */
    fun configurePersistentCache(db: FirebaseFirestore) {
        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
            db.firestoreSettings = settings
        } catch (_: IllegalStateException) {
            // Firestore already started for this named instance; existing settings remain active.
        }
    }

    /**
     * Resets Firestore's network socket (helps when switching between Wi-Fi and mobile data)
     * and flushes all pending local writes within a 15-second timeout.
     */
    suspend fun forceCloudSync(
        firestore: FirebaseFirestore,
        onSyncStart: () -> Unit,
        onSyncSuccess: () -> Unit,
        onSyncFailure: (String) -> Unit,
        extraPendingUpload: (suspend () -> Result<Int>)? = null,
    ) {
        onSyncStart()
        try {
            withTimeout(FORCE_SYNC_TIMEOUT_MS) {
                // 1. Reset Firestore network socket (helpful when switching Wi-Fi / mobile data)
                firestore.disableNetwork().await()
                firestore.enableNetwork().await()

                // 2. Upload any local Room pending records if supplied
                if (extraPendingUpload != null) {
                    val uploadResult = extraPendingUpload()
                    val error = uploadResult.exceptionOrNull()
                    if (error != null) {
                        throw error
                    }
                }

                // 3. Force and await upload of all pending local Firestore writes
                firestore.waitForPendingWrites().await()
            }
            onSyncSuccess()
        } catch (e: TimeoutCancellationException) {
            ensureNetworkRestored(firestore)
            onSyncFailure(MSG_SYNC_TIMEOUT)
        } catch (e: CancellationException) {
            ensureNetworkRestored(firestore)
            throw e
        } catch (e: Exception) {
            ensureNetworkRestored(firestore)
            val failureMessage = if (isOfflineFirestoreError(e)) {
                MSG_SAVED_OFFLINE
            } else {
                formatSyncErrorMessage(e)
            }
            onSyncFailure(failureMessage)
        }
    }

    private fun ensureNetworkRestored(firestore: FirebaseFirestore) {
        try {
            firestore.enableNetwork()
        } catch (_: Exception) {
        }
    }

    fun isOfflineFirestoreError(error: Throwable?): Boolean {
        if (error == null) return false
        return generateSequence(error) { it.cause }.any { cause ->
            when (cause) {
                is FirestoreOfflineQueuedException -> true
                is TimeoutCancellationException -> true
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException -> true
                is IOException -> {
                    val msg = cause.message?.lowercase().orEmpty()
                    msg.contains("offline") ||
                        msg.contains("network") ||
                        msg.contains("unable to resolve host") ||
                        msg.contains("timed out")
                }
                is FirebaseFirestoreException -> {
                    if (cause.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                        cause.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
                    ) {
                        true
                    } else {
                        val msg = cause.message?.lowercase().orEmpty()
                        msg.contains("client is offline") ||
                            msg.contains("failed to get document") ||
                            msg.contains("unavailable")
                    }
                }
                else -> {
                    val msg = cause.message?.lowercase().orEmpty()
                    msg.contains("client is offline") ||
                        msg.contains("failed to get document because the client is offline")
                }
            }
        }
    }

    fun isPermissionDeniedError(error: Throwable?): Boolean {
        if (error == null) return false
        return generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .any { it.code == FirebaseFirestoreException.Code.PERMISSION_DENIED }
    }

    fun formatSyncSuccessMessage(syncedCount: Int): String {
        return if (syncedCount > 0) {
            "Synced $syncedCount item(s) — $MSG_SYNCED_TO_CLOUD"
        } else {
            MSG_SYNCED_TO_CLOUD
        }
    }

    fun formatSyncErrorMessage(error: Throwable): String {
        return if (isOfflineFirestoreError(error)) {
            MSG_SAVED_OFFLINE
        } else {
            "Sync issue: ${error.localizedMessage ?: "Hindi makakonekta sa server."}"
        }
    }
}

suspend fun forceCloudSync(
    firestore: FirebaseFirestore,
    onSyncStart: () -> Unit,
    onSyncSuccess: () -> Unit,
    onSyncFailure: (String) -> Unit,
) {
    FirestoreSyncHelper.forceCloudSync(
        firestore = firestore,
        onSyncStart = onSyncStart,
        onSyncSuccess = onSyncSuccess,
        onSyncFailure = onSyncFailure,
    )
}
