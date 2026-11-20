package com.melvin.minige.data

import com.melvin.minige.data.local.AppSettingsEntity
import com.melvin.minige.data.local.DailyLedgerEntity
import com.melvin.minige.data.local.DeliveryEntity
import com.melvin.minige.data.local.ExpenseEntity
import com.melvin.minige.data.local.MinigeDao
import com.melvin.minige.data.local.UtangPaymentEntity
import com.melvin.minige.data.remote.MinigeFirestoreDataSource
import com.melvin.minige.domain.DailyLedgerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.MinigeSettings
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class MinigeRepository(
    private val dao: MinigeDao,
    private val remote: MinigeFirestoreDataSource,
) {
    fun observeDeliveries(userId: String): Flow<List<DeliveryRecord>> =
        dao.observeDeliveries(userId).map { list -> list.map { it.toDomain() } }

    fun observeUtangPayments(userId: String): Flow<List<UtangPaymentRecord>> =
        dao.observeUtangPayments(userId).map { list -> list.map { it.toDomain() } }

    fun observeExpenses(userId: String): Flow<List<ExpenseRecord>> =
        dao.observeExpenses(userId).map { list -> list.map { it.toDomain() } }

    fun observeDailyLedgers(userId: String): Flow<List<DailyLedgerRecord>> =
        dao.observeDailyLedgers(userId).map { list -> list.map { it.toDomain() } }

    fun observeSettings(): Flow<MinigeSettings> =
        dao.observeSettings().map { it?.toDomain() ?: MinigeSettings() }

    /**
     * Streams Firestore collections into local Room cache while preserving any un-synced local edits.
     */
    suspend fun startCloudSync(userId: String, onError: (Throwable) -> Unit) = coroutineScope {
        launch {
            syncPendingToCloud(userId)
        }
        launch {
            remote.observeDeliveries(userId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    onError(e)
                }
                .collectLatest { remoteList ->
                    val pendingIds = dao.getPendingDeliveries(userId).map { it.id }.toSet()
                    val filtered = remoteList.filterNot { it.id in pendingIds }
                    if (filtered.isNotEmpty()) {
                        dao.upsertDeliveries(filtered)
                    }
                }
        }
        launch {
            remote.observeUtangPayments(userId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    onError(e)
                }
                .collectLatest { remoteList ->
                    val pendingIds = dao.getPendingUtangPayments(userId).map { it.id }.toSet()
                    val filtered = remoteList.filterNot { it.id in pendingIds }
                    if (filtered.isNotEmpty()) {
                        dao.upsertUtangPayments(filtered)
                    }
                }
        }
        launch {
            remote.observeExpenses(userId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    onError(e)
                }
                .collectLatest { remoteList ->
                    val pendingIds = dao.getPendingExpenses(userId).map { it.id }.toSet()
                    val filtered = remoteList.filterNot { it.id in pendingIds }
                    if (filtered.isNotEmpty()) {
                        dao.upsertExpenses(filtered)
                    }
                }
        }
        launch {
            remote.observeDailyLedgers(userId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    onError(e)
                }
                .collectLatest { remoteList ->
                    val pendingKeys = dao.getPendingDailyLedgers(userId).map { it.dateKey }.toSet()
                    val filtered = remoteList.filterNot { it.dateKey in pendingKeys }
                    if (filtered.isNotEmpty()) {
                        dao.upsertDailyLedgers(filtered)
                    }
                }
        }
        launch {
            remote.observeSettings(userId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    onError(e)
                }
                .collectLatest { remoteSettings ->
                    if (remoteSettings != null) {
                        val local = dao.getSettingsOnce()
                        if (local == null || !local.pendingSync) {
                            dao.upsertSettings(remoteSettings)
                        }
                    }
                }
        }
    }

    suspend fun syncPendingToCloud(userId: String): Result<Int> {
        var syncedCount = 0
        var lastError: Throwable? = null

        dao.getPendingDeliveries(userId).forEach { entity ->
            val result = if (entity.isDeleted) {
                remote.deleteDelivery(userId, entity.id).also { res ->
                    if (res.isSuccess) dao.hardDeleteDelivery(entity.id)
                }
            } else {
                remote.upsertDelivery(userId, entity).also { res ->
                    if (res.isSuccess) dao.markDeliverySynced(entity.id)
                }
            }
            if (result.isSuccess) syncedCount++ else lastError = result.exceptionOrNull()
        }

        dao.getPendingUtangPayments(userId).forEach { entity ->
            val result = remote.upsertUtangPayment(userId, entity)
            if (result.isSuccess) {
                dao.markUtangPaymentSynced(entity.id)
                syncedCount++
            } else {
                lastError = result.exceptionOrNull()
            }
        }

        dao.getPendingExpenses(userId).forEach { entity ->
            val result = if (entity.isDeleted) {
                remote.deleteExpense(userId, entity.id).also { res ->
                    if (res.isSuccess) dao.hardDeleteExpense(entity.id)
                }
            } else {
                remote.upsertExpense(userId, entity).also { res ->
                    if (res.isSuccess) dao.markExpenseSynced(entity.id)
                }
            }
            if (result.isSuccess) syncedCount++ else lastError = result.exceptionOrNull()
        }

        dao.getPendingDailyLedgers(userId).forEach { entity ->
            val result = remote.upsertDailyLedger(userId, entity)
            if (result.isSuccess) {
                dao.markDailyLedgerSynced(entity.dateKey)
                syncedCount++
            } else {
                lastError = result.exceptionOrNull()
            }
        }

        val settings = dao.getSettingsOnce()
        if (settings != null && settings.pendingSync && settings.userId == userId) {
            val result = remote.upsertSettings(userId, settings)
            if (result.isSuccess) {
                dao.upsertSettings(settings.copy(pendingSync = false))
                syncedCount++
            } else {
                lastError = result.exceptionOrNull()
            }
        }

        return if (lastError != null && syncedCount == 0) {
            Result.failure(lastError)
        } else {
            Result.success(syncedCount)
        }
    }

    suspend fun addDelivery(
        userId: String,
        dateKey: String,
        customerName: String,
        purifiedGallons: Int,
        alkalineGallons: Int,
        paymentType: PaymentType,
        notes: String,
    ): Result<Unit> {
        val cleanName = customerName.trim().take(80)
        val totalGals = purifiedGallons + alkalineGallons
        require(cleanName.isNotEmpty()) { "Customer name is required." }
        require(totalGals >= 1) { "Enter at least 1 gallon." }

        val settings = dao.getSettingsOnce()?.toDomain() ?: MinigeSettings()
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        val totalBill = if (paymentType == PaymentType.PICK_UP) {
            0
        } else {
            (purifiedGallons * settings.purifiedPrice) + (alkalineGallons * settings.alkalinePrice)
        }

        val amountPaid = when (paymentType) {
            PaymentType.CASH, PaymentType.BANK_DEPOSIT -> totalBill
            PaymentType.PAUTANG, PaymentType.PICK_UP -> 0
        }

        val commissionEarned = when (paymentType) {
            PaymentType.CASH, PaymentType.BANK_DEPOSIT -> totalGals * settings.commissionRate
            PaymentType.PAUTANG, PaymentType.PICK_UP -> 0
        }

        val settledDateKey = when (paymentType) {
            PaymentType.CASH, PaymentType.BANK_DEPOSIT -> dateKey
            PaymentType.PAUTANG, PaymentType.PICK_UP -> null
        }

        val entity = DeliveryEntity(
            id = id,
            userId = userId,
            dateKey = dateKey,
            customerName = cleanName,
            purifiedGallons = purifiedGallons,
            alkalineGallons = alkalineGallons,
            purifiedUnitPrice = settings.purifiedPrice,
            alkalineUnitPrice = settings.alkalinePrice,
            totalAmount = totalBill,
            amountPaid = amountPaid,
            paymentType = paymentType.name,
            commissionRate = settings.commissionRate,
            commissionEarned = commissionEarned,
            settledDateKey = settledDateKey,
            notes = notes.trim().take(300),
            calendarEventId = null,
            pendingSync = true,
            isDeleted = false,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        dao.upsertDelivery(entity)
        val syncResult = remote.upsertDelivery(userId, entity)
        if (syncResult.isSuccess) {
            dao.markDeliverySynced(id)
        }
        return syncResult
    }

    /**
     * Records a partial or full payment toward an unpaid Pautang delivery.
     * Partial payments earn ₱0 commission; reaching ₱0 remaining balance awards full commission
     * (totalGallons * activeCommissionRate) on paymentDateKey.
     */
    suspend fun recordUtangPayment(
        userId: String,
        deliveryId: String,
        amount: Int,
        method: UtangMethod,
        paymentDateKey: String,
        notes: String,
    ): Result<Boolean> {
        val existing = dao.getDeliveryById(deliveryId)
            ?: return Result.failure(IllegalArgumentException("Utang delivery not found."))
        val remaining = (existing.totalAmount - existing.amountPaid).coerceAtLeast(0)
        if (remaining <= 0) {
            return Result.failure(IllegalStateException("This utang is already fully paid."))
        }
        val appliedAmount = amount.coerceIn(1, remaining)
        val newAmountPaid = existing.amountPaid + appliedAmount
        val isNowFullyPaid = (existing.totalAmount - newAmountPaid) <= 0

        val settings = dao.getSettingsOnce()?.toDomain() ?: MinigeSettings()
        val now = System.currentTimeMillis()
        val paymentId = UUID.randomUUID().toString()

        val paymentEntity = UtangPaymentEntity(
            id = paymentId,
            deliveryId = deliveryId,
            userId = userId,
            dateKey = paymentDateKey,
            amount = appliedAmount,
            method = method.name,
            notes = notes.trim().take(300),
            pendingSync = true,
            isDeleted = false,
            createdAtEpochMs = now,
        )

        val totalGals = existing.purifiedGallons + existing.alkalineGallons
        val newCommissionRate = if (isNowFullyPaid) settings.commissionRate else existing.commissionRate
        val newCommissionEarned = if (isNowFullyPaid) totalGals * settings.commissionRate else 0
        val newSettledDate = if (isNowFullyPaid) paymentDateKey else null

        val updatedDelivery = existing.copy(
            amountPaid = newAmountPaid,
            commissionRate = newCommissionRate,
            commissionEarned = newCommissionEarned,
            settledDateKey = newSettledDate,
            pendingSync = true,
            updatedAtEpochMs = now,
        )

        dao.upsertUtangPayment(paymentEntity)
        dao.upsertDelivery(updatedDelivery)

        val pRes = remote.upsertUtangPayment(userId, paymentEntity)
        if (pRes.isSuccess) dao.markUtangPaymentSynced(paymentId)

        val dRes = remote.upsertDelivery(userId, updatedDelivery)
        if (dRes.isSuccess) dao.markDeliverySynced(deliveryId)

        return if (pRes.isFailure) Result.failure(pRes.exceptionOrNull()!!)
        else if (dRes.isFailure) Result.failure(dRes.exceptionOrNull()!!)
        else Result.success(isNowFullyPaid)
    }

    suspend fun deleteDelivery(userId: String, deliveryId: String): Result<Unit> {
        val now = System.currentTimeMillis()
        dao.softDeleteDelivery(deliveryId, now)
        val res = remote.deleteDelivery(userId, deliveryId)
        if (res.isSuccess) {
            dao.hardDeleteDelivery(deliveryId)
        }
        return res
    }

    suspend fun addExpense(
        userId: String,
        dateKey: String,
        category: ExpenseCategory,
        amount: Int,
        notes: String,
    ): Result<Unit> {
        require(amount > 0) { "Expense amount must be greater than ₱0." }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val entity = ExpenseEntity(
            id = id,
            userId = userId,
            dateKey = dateKey,
            category = category.name,
            amount = amount,
            notes = notes.trim().take(300),
            pendingSync = true,
            isDeleted = false,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        dao.upsertExpense(entity)
        val res = remote.upsertExpense(userId, entity)
        if (res.isSuccess) dao.markExpenseSynced(id)
        return res
    }

    suspend fun deleteExpense(userId: String, expenseId: String): Result<Unit> {
        val now = System.currentTimeMillis()
        dao.softDeleteExpense(expenseId, now)
        val res = remote.deleteExpense(userId, expenseId)
        if (res.isSuccess) dao.hardDeleteExpense(expenseId)
        return res
    }

    suspend fun updateStartingSukli(
        userId: String,
        dateKey: String,
        startingSukli: Int,
        notes: String = "",
    ): Result<Unit> {
        val entity = DailyLedgerEntity(
            dateKey = dateKey,
            userId = userId,
            startingSukli = startingSukli.coerceAtLeast(0),
            notes = notes.trim().take(300),
            pendingSync = true,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        dao.upsertDailyLedger(entity)
        val res = remote.upsertDailyLedger(userId, entity)
        if (res.isSuccess) dao.markDailyLedgerSynced(dateKey)
        return res
    }

    suspend fun updateSettings(
        userId: String,
        purifiedPrice: Int,
        alkalinePrice: Int,
        commissionRate: Int,
        defaultSukli: Int,
    ): Result<Unit> {
        val entity = AppSettingsEntity(
            id = "default",
            userId = userId,
            purifiedPrice = purifiedPrice.coerceAtLeast(0),
            alkalinePrice = alkalinePrice.coerceAtLeast(0),
            commissionRate = commissionRate.coerceAtLeast(0),
            defaultSukli = defaultSukli.coerceAtLeast(0),
            pendingSync = true,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        dao.upsertSettings(entity)
        val res = remote.upsertSettings(userId, entity)
        if (res.isSuccess) {
            dao.upsertSettings(entity.copy(pendingSync = false))
        }
        return res
    }

    suspend fun linkCalendarEventToDeliveries(
        userId: String,
        deliveryIds: List<String>,
        calendarEventId: String,
    ) {
        val now = System.currentTimeMillis()
        deliveryIds.forEach { id ->
            val current = dao.getDeliveryById(id) ?: return@forEach
            val updated = current.copy(
                calendarEventId = calendarEventId,
                pendingSync = true,
                updatedAtEpochMs = now,
            )
            dao.upsertDelivery(updated)
            val res = remote.upsertDelivery(userId, updated)
            if (res.isSuccess) dao.markDeliverySynced(id)
        }
    }
}
