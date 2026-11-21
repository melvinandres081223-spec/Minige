package com.melvin.minige.data.remote

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class MinigeFirestoreDataSource(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(expectedUid: String): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        check(uid == expectedUid) { "Signed-in user changed; refusing cross-user Firestore operation." }
        return uid
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
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                docRef.update(entity.toFirestoreUpdateMap()).await()
            } else {
                docRef.set(entity.toFirestoreCreateMap(uid)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteDelivery(userId: String, deliveryId: String): Result<Unit> {
        val path = "users/$userId/deliveries/$deliveryId"
        return try {
            val uid = requireUserId(userId)
            db.collection("users").document(uid).collection("deliveries").document(deliveryId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun upsertUtangPayment(userId: String, entity: UtangPaymentEntity): Result<Unit> {
        val path = "users/$userId/utangPayments/${entity.id}"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid).collection("utangPayments").document(entity.id)
            docRef.set(entity.toFirestoreCreateMap(uid)).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun upsertExpense(userId: String, entity: ExpenseEntity): Result<Unit> {
        val path = "users/$userId/expenses/${entity.id}"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid).collection("expenses").document(entity.id)
            docRef.set(entity.toFirestoreCreateMap(uid)).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteExpense(userId: String, expenseId: String): Result<Unit> {
        val path = "users/$userId/expenses/$expenseId"
        return try {
            val uid = requireUserId(userId)
            db.collection("users").document(uid).collection("expenses").document(expenseId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun upsertDailyLedger(userId: String, entity: DailyLedgerEntity): Result<Unit> {
        val path = "users/$userId/dailyLedgers/${entity.dateKey}"
        return try {
            val uid = requireUserId(userId)
            val docRef = db.collection("users").document(uid).collection("dailyLedgers").document(entity.dateKey)
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(entity.toFirestoreUpdateMap()).await()
            } else {
                docRef.set(entity.toFirestoreCreateMap(uid)).await()
            }
            Result.success(Unit)
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
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(entity.toFirestoreUpdateMap()).await()
            } else {
                docRef.set(entity.toFirestoreCreateMap(uid)).await()
            }
            Result.success(Unit)
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
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(entity.toFirestoreUpdateMap()).await()
            } else {
                docRef.set(entity.toFirestoreCreateMap(uid)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
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
}
