package com.melvin.minige.data.remote

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import com.melvin.minige.R
import com.melvin.minige.data.local.AppSettingsEntity
import com.melvin.minige.data.local.CustomerEntity
import com.melvin.minige.data.local.DailyLedgerEntity
import com.melvin.minige.data.local.DeliveryEntity
import com.melvin.minige.data.local.ExpenseEntity
import com.melvin.minige.data.local.UtangPaymentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class MinigeFirestoreDataSource(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    init {
        FirestoreSyncHelper.configurePersistentCache(db)
    }

    private fun requireUserId(expectedUid: String): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        check(uid == expectedUid) { "Signed-in user changed; refusing cross-user Firestore operation." }
        return uid
    }

    suspend fun ensureNetworkEnabled() {
        try {
            withTimeoutOrNull(NETWORK_ENABLE_TIMEOUT_MS) {
                db.enableNetwork().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Non-fatal: Firestore local persistent cache remains active.
        }
    }

    suspend fun flushPendingWrites(): Result<Unit> {
        return try {
            ensureNetworkEnabled()
            val flushed = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) {
                db.waitForPendingWrites().await()
                true
            }
            if (flushed == true) {
                Result.success(Unit)
            } else {
                Result.failure(FirestoreOfflineQueuedException(FirestoreSyncHelper.MSG_SAVED_OFFLINE))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (FirestoreSyncHelper.isOfflineFirestoreError(e)) {
                Result.failure(FirestoreOfflineQueuedException(FirestoreSyncHelper.MSG_SAVED_OFFLINE, e))
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * Emits whenever Firestore receives a confirmed, non-cached snapshot with zero pending writes,
     * allowing Room's pending queue to drain automatically once mobile data or Wi-Fi reconnects.
     */
    fun observeServerOnlineTrigger(userId: String): Flow<Unit> = flow {
        val uid = requireUserId(userId)
        emitAll(
            db.collection("users").document(uid).collection("deliveries")
                .limit(1)
                .snapshots(MetadataChanges.INCLUDE)
                .filter { snap -> !snap.metadata.isFromCache && !snap.metadata.hasPendingWrites() }
                .map { Unit }
                .catch { error ->
                    if (error is CancellationException) throw error
                }
        )
    }

    fun observeDeliveries(userId: String): Flow<List<DeliveryEntity>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/deliveries"
        emitAll(
            db.collection("users").document(uid).collection("deliveries")
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        FirestoreDelivery::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    ).map { it.toEntity(pendingSync = false) }
                }
                .catch { error ->
                    logSnapshotErrorIfActive(error, uid, path)
                    throw error
                }
        )
    }

    fun observeUtangPayments(userId: String): Flow<List<UtangPaymentEntity>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/utangPayments"
        emitAll(
            db.collection("users").document(uid).collection("utangPayments")
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        FirestoreUtangPayment::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    ).map { it.toEntity(pendingSync = false) }
                }
                .catch { error ->
                    logSnapshotErrorIfActive(error, uid, path)
                    throw error
                }
        )
    }

    fun observeExpenses(userId: String): Flow<List<ExpenseEntity>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/expenses"
        emitAll(
            db.collection("users").document(uid).collection("expenses")
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        FirestoreExpense::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    ).map { it.toEntity(pendingSync = false) }
                }
                .catch { error ->
                    logSnapshotErrorIfActive(error, uid, path)
                    throw error
                }
        )
    }

    fun observeDailyLedgers(userId: String): Flow<List<DailyLedgerEntity>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/dailyLedgers"
        emitAll(
            db.collection("users").document(uid).collection("dailyLedgers")
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        FirestoreDailyLedger::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    ).map { it.toEntity(pendingSync = false) }
                }
                .catch { error ->
                    logSnapshotErrorIfActive(error, uid, path)
                    throw error
                }
        )
    }

    fun observeSettings(userId: String): Flow<AppSettingsEntity?> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/settings/config"
        emitAll(
            db.collection("users").document(uid).collection("settings").document("config")
                .snapshots()
                .map { snap ->
                    if (!snap.exists()) null
                    else snap.toObject(
                        FirestoreUserSettings::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    )?.toEntity(pendingSync = false)
                }
                .catch { error ->
                    logSnapshotErrorIfActive(error, uid, path)
                    throw error
                }
        )
    }

    suspend fun upsertDelivery(userId: String, entity: DeliveryEntity): Result<Unit> {
        val path = "users/$userId/deliveries/${entity.id}"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid).collection("deliveries").document(entity.id)
            upsertDocumentResilient(
                docRef = docRef,
                path = path,
                createMap = entity.toFirestoreCreateMap(uid),
                updateMap = entity.toFirestoreUpdateMap(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteDelivery(userId: String, deliveryId: String): Result<Unit> {
        val path = "users/$userId/deliveries/$deliveryId"
        return deleteDocumentResilient(
            userId = userId,
            path = path,
        ) { uid ->
            db.collection("users").document(uid).collection("deliveries").document(deliveryId)
        }
    }

    suspend fun upsertUtangPayment(userId: String, entity: UtangPaymentEntity): Result<Unit> {
        val path = "users/$userId/utangPayments/${entity.id}"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid).collection("utangPayments").document(entity.id)
            upsertDocumentResilient(
                docRef = docRef,
                path = path,
                createMap = entity.toFirestoreCreateMap(uid),
                updateMap = entity.toFirestoreUpdateMap(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteUtangPayment(userId: String, paymentId: String): Result<Unit> {
        val path = "users/$userId/utangPayments/$paymentId"
        return deleteDocumentResilient(
            userId = userId,
            path = path,
        ) { uid ->
            db.collection("users").document(uid).collection("utangPayments").document(paymentId)
        }
    }

    suspend fun upsertExpense(userId: String, entity: ExpenseEntity): Result<Unit> {
        val path = "users/$userId/expenses/${entity.id}"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid).collection("expenses").document(entity.id)
            upsertDocumentResilient(
                docRef = docRef,
                path = path,
                createMap = entity.toFirestoreCreateMap(uid),
                updateMap = entity.toFirestoreUpdateMap(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteExpense(userId: String, expenseId: String): Result<Unit> {
        val path = "users/$userId/expenses/$expenseId"
        return deleteDocumentResilient(
            userId = userId,
            path = path,
        ) { uid ->
            db.collection("users").document(uid).collection("expenses").document(expenseId)
        }
    }

    suspend fun upsertDailyLedger(userId: String, entity: DailyLedgerEntity): Result<Unit> {
        val path = "users/$userId/dailyLedgers/${entity.dateKey}"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid).collection("dailyLedgers").document(entity.dateKey)
            upsertDocumentResilient(
                docRef = docRef,
                path = path,
                createMap = entity.toFirestoreCreateMap(uid),
                updateMap = entity.toFirestoreUpdateMap(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun upsertSettings(userId: String, entity: AppSettingsEntity): Result<Unit> {
        val path = "users/$userId/settings/config"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid).collection("settings").document("config")
            upsertDocumentResilient(
                docRef = docRef,
                path = path,
                createMap = entity.toFirestoreCreateMap(uid),
                updateMap = entity.toFirestoreUpdateMap(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    fun observeCustomers(userId: String): Flow<List<CustomerEntity>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/customers"
        emitAll(
            db.collection("users").document(uid).collection("customers")
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        FirestoreCustomer::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    ).map { it.toEntity(pendingSync = false) }
                }
                .catch { error ->
                    logSnapshotErrorIfActive(error, uid, path)
                    throw error
                }
        )
    }

    suspend fun upsertCustomer(userId: String, entity: CustomerEntity): Result<Unit> {
        val path = "users/$userId/customers/${entity.id}"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid).collection("customers").document(entity.id)
            upsertDocumentResilient(
                docRef = docRef,
                path = path,
                createMap = entity.toFirestoreCreateMap(uid),
                updateMap = entity.toFirestoreUpdateMap(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    /**
     * Reads with [Source.DEFAULT] (never SERVER-only) after enabling Firestore network.
     * If Firestore transiently reports client offline or uncached read failure, falls back to
     * [Source.CACHE] and proceeds cleanly with local persistent write queueing.
     */
    private suspend fun getDocumentWithOfflineFallback(docRef: DocumentReference): DocumentSnapshot? {
        ensureNetworkEnabled()
        return try {
            val snap = withTimeoutOrNull(DOC_GET_TIMEOUT_MS) {
                docRef.get(Source.DEFAULT).await()
            }
            if (snap != null) {
                snap
            } else {
                // Timed out waiting for remote response; inspect local cache only.
                try {
                    docRef.get(Source.CACHE).await()
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!FirestoreSyncHelper.isOfflineFirestoreError(e)) {
                throw e
            }
            // Retry once after explicitly waking network; if still offline, inspect local cache cleanly.
            ensureNetworkEnabled()
            try {
                withTimeoutOrNull(DOC_RETRY_TIMEOUT_MS) {
                    docRef.get(Source.DEFAULT).await()
                } ?: try {
                    docRef.get(Source.CACHE).await()
                } catch (_: Exception) {
                    null
                }
            } catch (retryErr: CancellationException) {
                throw retryErr
            } catch (retryErr: Exception) {
                if (!FirestoreSyncHelper.isOfflineFirestoreError(retryErr)) {
                    throw retryErr
                }
                try {
                    docRef.get(Source.CACHE).await()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private suspend fun upsertDocumentResilient(
        docRef: DocumentReference,
        path: String,
        createMap: Map<String, Any>,
        updateMap: Map<String, Any>,
    ): Result<Unit> {
        val existingSnap = getDocumentWithOfflineFallback(docRef)
        val isExistingDoc = existingSnap?.exists() == true
        val writeTask = if (isExistingDoc) {
            docRef.update(updateMap)
        } else {
            docRef.set(createMap)
        }

        return try {
            val confirmed = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) {
                writeTask.await()
                true
            }
            if (confirmed == true) {
                Result.success(Unit)
            } else {
                // Write is already queued in Firestore's persistent offline cache.
                Result.failure(FirestoreOfflineQueuedException(FirestoreSyncHelper.MSG_SAVED_OFFLINE))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Edge case: uncached offline fallback chose set(createMap), but document actually exists on server
            // with an immutable createdAt timestamp. Retry as update(updateMap) if server says it exists.
            if (!isExistingDoc && FirestoreSyncHelper.isPermissionDeniedError(e)) {
                val refreshed = try {
                    withTimeoutOrNull(DOC_GET_TIMEOUT_MS) {
                        docRef.get(Source.DEFAULT).await()
                    }
                } catch (_: Exception) {
                    null
                }
                if (refreshed?.exists() == true) {
                    return try {
                        val updateAck = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) {
                            docRef.update(updateMap).await()
                            true
                        }
                        if (updateAck == true) {
                            Result.success(Unit)
                        } else {
                            Result.failure(FirestoreOfflineQueuedException(FirestoreSyncHelper.MSG_SAVED_OFFLINE))
                        }
                    } catch (uErr: CancellationException) {
                        throw uErr
                    } catch (uErr: Exception) {
                        if (FirestoreSyncHelper.isOfflineFirestoreError(uErr)) {
                            Result.failure(FirestoreOfflineQueuedException(FirestoreSyncHelper.MSG_SAVED_OFFLINE, uErr))
                        } else {
                            handleFirestoreError(uErr, OperationType.WRITE, path)
                            Result.failure(uErr)
                        }
                    }
                }
            }

            if (FirestoreSyncHelper.isOfflineFirestoreError(e)) {
                Result.failure(FirestoreOfflineQueuedException(FirestoreSyncHelper.MSG_SAVED_OFFLINE, e))
            } else {
                handleFirestoreError(e, OperationType.WRITE, path)
                Result.failure(e)
            }
        }
    }

    private suspend fun deleteDocumentResilient(
        userId: String,
        path: String,
        refProvider: (String) -> DocumentReference,
    ): Result<Unit> {
        return try {
            val uid = requireUserId(userId)
            ensureNetworkEnabled()
            val deleteTask = refProvider(uid).delete()
            val confirmed = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) {
                deleteTask.await()
                true
            }
            if (confirmed == true) {
                Result.success(Unit)
            } else {
                Result.failure(FirestoreOfflineQueuedException(FirestoreSyncHelper.MSG_SAVED_OFFLINE))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (FirestoreSyncHelper.isOfflineFirestoreError(e)) {
                Result.failure(FirestoreOfflineQueuedException(FirestoreSyncHelper.MSG_SAVED_OFFLINE, e))
            } else {
                handleFirestoreError(e, OperationType.DELETE, path)
                Result.failure(e)
            }
        }
    }

    private fun logSnapshotErrorIfActive(error: Throwable, uid: String, path: String) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
        }
    }

    companion object {
        private const val NETWORK_ENABLE_TIMEOUT_MS = 4_000L
        private const val DOC_GET_TIMEOUT_MS = 5_000L
        private const val DOC_RETRY_TIMEOUT_MS = 3_000L
        private const val WRITE_ACK_TIMEOUT_MS = 8_000L
    }
}
