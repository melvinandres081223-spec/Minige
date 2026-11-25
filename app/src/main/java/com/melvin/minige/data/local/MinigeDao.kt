package com.melvin.minige.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MinigeDao {

    @Query("SELECT * FROM deliveries WHERE userId = :userId AND isDeleted = 0 ORDER BY createdAtEpochMs DESC")
    fun observeDeliveries(userId: String): Flow<List<DeliveryEntity>>

    @Query("SELECT * FROM deliveries WHERE userId = :userId AND dateKey = :dateKey AND isDeleted = 0 ORDER BY createdAtEpochMs DESC")
    suspend fun getDeliveriesForDateOnce(userId: String, dateKey: String): List<DeliveryEntity>

    @Query("SELECT * FROM deliveries WHERE userId = :userId AND pendingSync = 1")
    suspend fun getPendingDeliveries(userId: String): List<DeliveryEntity>

    @Query("SELECT * FROM deliveries WHERE id = :id LIMIT 1")
    suspend fun getDeliveryById(id: String): DeliveryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDelivery(entity: DeliveryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeliveries(entities: List<DeliveryEntity>)

    /**
     * Atomically merges incoming cloud deliveries without deleting or overwriting
     * any local rows that still have `pendingSync = true`.
     */
    @Transaction
    suspend fun mergeRemoteDeliveriesSafely(userId: String, remoteList: List<DeliveryEntity>) {
        if (remoteList.isEmpty()) return
        val pendingIds = getPendingDeliveries(userId).map { it.id }.toSet()
        val safeRemote = remoteList.filterNot { it.id in pendingIds }
        if (safeRemote.isNotEmpty()) {
            upsertDeliveries(safeRemote)
        }
    }

    @Query("UPDATE deliveries SET pendingSync = 0 WHERE id = :id")
    suspend fun markDeliverySynced(id: String)

    @Query("UPDATE deliveries SET isDeleted = 1, pendingSync = 1, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun softDeleteDelivery(id: String, updatedAt: Long)

    @Query("DELETE FROM deliveries WHERE id = :id")
    suspend fun hardDeleteDelivery(id: String)

    @Query("SELECT * FROM utang_payments WHERE userId = :userId AND isDeleted = 0 ORDER BY createdAtEpochMs DESC")
    fun observeUtangPayments(userId: String): Flow<List<UtangPaymentEntity>>

    @Query("SELECT * FROM utang_payments WHERE deliveryId = :deliveryId AND isDeleted = 0 ORDER BY createdAtEpochMs ASC")
    suspend fun getUtangPaymentsForDelivery(deliveryId: String): List<UtangPaymentEntity>

    @Query("SELECT * FROM utang_payments WHERE userId = :userId AND pendingSync = 1")
    suspend fun getPendingUtangPayments(userId: String): List<UtangPaymentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUtangPayment(entity: UtangPaymentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUtangPayments(entities: List<UtangPaymentEntity>)

    @Transaction
    suspend fun mergeRemoteUtangPaymentsSafely(userId: String, remoteList: List<UtangPaymentEntity>) {
        if (remoteList.isEmpty()) return
        val pendingIds = getPendingUtangPayments(userId).map { it.id }.toSet()
        val safeRemote = remoteList.filterNot { it.id in pendingIds }
        if (safeRemote.isNotEmpty()) {
            upsertUtangPayments(safeRemote)
        }
    }

    @Query("UPDATE utang_payments SET pendingSync = 0 WHERE id = :id")
    suspend fun markUtangPaymentSynced(id: String)

    @Query("UPDATE utang_payments SET isDeleted = 1, pendingSync = 1 WHERE id = :id")
    suspend fun softDeleteUtangPayment(id: String)

    @Query("DELETE FROM utang_payments WHERE id = :id")
    suspend fun hardDeleteUtangPayment(id: String)

    @Query("SELECT * FROM expenses WHERE userId = :userId AND isDeleted = 0 ORDER BY createdAtEpochMs DESC")
    fun observeExpenses(userId: String): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE userId = :userId AND dateKey = :dateKey AND isDeleted = 0 ORDER BY createdAtEpochMs DESC")
    suspend fun getExpensesForDateOnce(userId: String, dateKey: String): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE userId = :userId AND pendingSync = 1")
    suspend fun getPendingExpenses(userId: String): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExpense(entity: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExpenses(entities: List<ExpenseEntity>)

    @Transaction
    suspend fun mergeRemoteExpensesSafely(userId: String, remoteList: List<ExpenseEntity>) {
        if (remoteList.isEmpty()) return
        val pendingIds = getPendingExpenses(userId).map { it.id }.toSet()
        val safeRemote = remoteList.filterNot { it.id in pendingIds }
        if (safeRemote.isNotEmpty()) {
            upsertExpenses(safeRemote)
        }
    }

    @Query("UPDATE expenses SET pendingSync = 0 WHERE id = :id")
    suspend fun markExpenseSynced(id: String)

    @Query("UPDATE expenses SET isDeleted = 1, pendingSync = 1, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun softDeleteExpense(id: String, updatedAt: Long)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun hardDeleteExpense(id: String)

    @Query("SELECT * FROM daily_ledgers WHERE userId = :userId")
    fun observeDailyLedgers(userId: String): Flow<List<DailyLedgerEntity>>

    @Query("SELECT * FROM daily_ledgers WHERE userId = :userId AND pendingSync = 1")
    suspend fun getPendingDailyLedgers(userId: String): List<DailyLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyLedger(entity: DailyLedgerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyLedgers(entities: List<DailyLedgerEntity>)

    @Transaction
    suspend fun mergeRemoteDailyLedgersSafely(userId: String, remoteList: List<DailyLedgerEntity>) {
        if (remoteList.isEmpty()) return
        val pendingKeys = getPendingDailyLedgers(userId).map { it.dateKey }.toSet()
        val safeRemote = remoteList.filterNot { it.dateKey in pendingKeys }
        if (safeRemote.isNotEmpty()) {
            upsertDailyLedgers(safeRemote)
        }
    }

    @Query("UPDATE daily_ledgers SET pendingSync = 0 WHERE dateKey = :dateKey")
    suspend fun markDailyLedgerSynced(dateKey: String)

    @Query("SELECT * FROM app_settings WHERE id = 'default' LIMIT 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 'default' LIMIT 1")
    suspend fun getSettingsOnce(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(entity: AppSettingsEntity)

    @Query("SELECT * FROM customers WHERE userId = :userId ORDER BY updatedAtEpochMs DESC")
    fun observeCustomers(userId: String): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE userId = :userId AND pendingSync = 1")
    suspend fun getPendingCustomers(userId: String): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getCustomerById(id: String): CustomerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomer(entity: CustomerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomers(entities: List<CustomerEntity>)

    @Transaction
    suspend fun mergeRemoteCustomersSafely(userId: String, remoteList: List<CustomerEntity>) {
        if (remoteList.isEmpty()) return
        val pendingIds = getPendingCustomers(userId).map { it.id }.toSet()
        val safeRemote = remoteList.filterNot { it.id in pendingIds }
        if (safeRemote.isNotEmpty()) {
            upsertCustomers(safeRemote)
        }
    }

    @Query("UPDATE customers SET pendingSync = 0 WHERE id = :id")
    suspend fun markCustomerSynced(id: String)

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM deliveries WHERE userId = :userId AND pendingSync = 1) +
            (SELECT COUNT(*) FROM utang_payments WHERE userId = :userId AND pendingSync = 1) +
            (SELECT COUNT(*) FROM expenses WHERE userId = :userId AND pendingSync = 1) +
            (SELECT COUNT(*) FROM daily_ledgers WHERE userId = :userId AND pendingSync = 1) +
            (SELECT COUNT(*) FROM customers WHERE userId = :userId AND pendingSync = 1) +
            (SELECT COUNT(*) FROM app_settings WHERE id = 'default' AND userId = :userId AND pendingSync = 1)
        """
    )
    fun observeTotalPendingSyncCount(userId: String): Flow<Int>

    @Query("SELECT * FROM deliveries WHERE userId = :userId AND isDeleted = 0")
    suspend fun getAllDeliveriesOnce(userId: String): List<DeliveryEntity>

    @Query("SELECT * FROM utang_payments WHERE userId = :userId AND isDeleted = 0")
    suspend fun getAllUtangPaymentsOnce(userId: String): List<UtangPaymentEntity>

    @Query("SELECT * FROM expenses WHERE userId = :userId AND isDeleted = 0")
    suspend fun getAllExpensesOnce(userId: String): List<ExpenseEntity>

    @Query("SELECT * FROM daily_ledgers WHERE userId = :userId")
    suspend fun getAllDailyLedgersOnce(userId: String): List<DailyLedgerEntity>

    @Query("SELECT * FROM customers WHERE userId = :userId")
    suspend fun getAllCustomersOnce(userId: String): List<CustomerEntity>
}
