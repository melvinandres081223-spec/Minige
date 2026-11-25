package com.melvin.minige.data

import com.melvin.minige.data.local.AppSettingsEntity
import com.melvin.minige.data.local.DailyLedgerEntity
import com.melvin.minige.data.local.DeliveryEntity
import com.melvin.minige.data.local.ExpenseEntity
import com.melvin.minige.data.local.MinigeDao
import com.melvin.minige.data.local.UtangPaymentEntity
import com.melvin.minige.data.remote.MinigeFirestoreDataSource
import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.DailyLedgerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.MinigeSettings
import androidx.annotation.VisibleForTesting
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.util.BulkUtangFifoHelper
import com.melvin.minige.util.BulkUtangSettlementSummary
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PartialPaymentHelper
import com.melvin.minige.util.PaymentBreakdownHelper
import com.melvin.minige.util.SmallGallonHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import com.melvin.minige.sync.DriveBackupManager
import com.melvin.minige.sync.DriveRestoreSummary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import kotlin.math.roundToInt

class MinigeRepository(
    private val dao: MinigeDao,
    private val remote: MinigeFirestoreDataSource,
    private val pricingRepository: PricingRepository = PricingRepository(dao, remote),
    private val backgroundSyncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _localDataChangeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
    val localDataChangeEvents: SharedFlow<Unit> = _localDataChangeEvents.asSharedFlow()

    @VisibleForTesting
    internal var utangSyncOverrideForTest: (suspend (userId: String, payment: UtangPaymentEntity, delivery: DeliveryEntity) -> Pair<Result<Unit>, Result<Unit>>)? = null

    private fun notifyLocalDataChanged() {
        _localDataChangeEvents.tryEmit(Unit)
    }

    suspend fun backupToDrive(
        accessToken: String? = null,
        userId: String,
        context: Context,
    ): Result<Long> {
        return DriveBackupManager.backupToDrive(accessToken, dao, userId, context)
    }

    suspend fun exportLocalBackup(
        userId: String,
        context: Context,
    ): Result<String> {
        return DriveBackupManager.exportLocalBackupJson(dao, userId, context)
    }

    suspend fun restoreFromDrive(
        accessToken: String? = null,
        userId: String,
        context: Context,
    ): Result<DriveRestoreSummary> {
        val result = DriveBackupManager.restoreFromDrive(accessToken, dao, userId, context)
        if (result.isSuccess) {
            notifyLocalDataChanged()
        }
        return result
    }
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

    fun observeCustomers(userId: String): Flow<List<CustomerRecord>> =
        pricingRepository.observeCustomers(userId)

    fun observePendingSyncCount(userId: String): Flow<Int> =
        dao.observeTotalPendingSyncCount(userId)

    /**
     * Streams Firestore collections into local Room cache while preserving any un-synced local edits.
     */
    suspend fun startCloudSync(
        userId: String,
        onError: (Throwable) -> Unit,
        onAutoSynced: (() -> Unit)? = null,
    ) = coroutineScope {
        remote.ensureNetworkEnabled()
        launch {
            val res = syncPendingToCloud(userId)
            if (res.isSuccess) {
                onAutoSynced?.invoke()
            }
        }
        launch {
            remote.observeServerOnlineTrigger(userId)
                .collectLatest {
                    val hasPending = hasPendingItems(userId)
                    if (hasPending) {
                        val res = syncPendingToCloud(userId)
                        if (res.isSuccess) {
                            onAutoSynced?.invoke()
                        }
                    } else {
                        onAutoSynced?.invoke()
                    }
                }
        }
        launch {
            remote.observeDeliveries(userId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    onError(e)
                }
                .collectLatest { remoteList ->
                    withContext(Dispatchers.IO + NonCancellable) {
                        dao.mergeRemoteDeliveriesSafely(userId, remoteList)
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
                    withContext(Dispatchers.IO + NonCancellable) {
                        dao.mergeRemoteUtangPaymentsSafely(userId, remoteList)
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
                    withContext(Dispatchers.IO + NonCancellable) {
                        dao.mergeRemoteExpensesSafely(userId, remoteList)
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
                    withContext(Dispatchers.IO + NonCancellable) {
                        dao.mergeRemoteDailyLedgersSafely(userId, remoteList)
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
                        withContext(Dispatchers.IO + NonCancellable) {
                            val local = dao.getSettingsOnce()
                            if (local == null || !local.pendingSync) {
                                dao.upsertSettings(remoteSettings)
                            }
                        }
                    }
                }
        }
        launch {
            remote.observeCustomers(userId)
                .catch { e ->
                    if (e is CancellationException) throw e
                    onError(e)
                }
                .collectLatest { remoteCustomers ->
                    pricingRepository.applyRemoteCustomers(userId, remoteCustomers)
                }
        }
    }

    private suspend fun hasPendingItems(userId: String): Boolean {
        val settings = dao.getSettingsOnce()
        val settingsPending = settings != null && settings.pendingSync && settings.userId == userId
        return settingsPending ||
            dao.getPendingDeliveries(userId).isNotEmpty() ||
            dao.getPendingUtangPayments(userId).isNotEmpty() ||
            dao.getPendingExpenses(userId).isNotEmpty() ||
            dao.getPendingDailyLedgers(userId).isNotEmpty() ||
            dao.getPendingCustomers(userId).isNotEmpty()
    }

    suspend fun forceCloudSync(
        userId: String,
        onSyncStart: () -> Unit,
        onSyncSuccess: (Int) -> Unit,
        onSyncFailure: (String) -> Unit,
    ) {
        remote.forceCloudSync(
            userId = userId,
            onSyncStart = onSyncStart,
            onSyncSuccess = onSyncSuccess,
            onSyncFailure = onSyncFailure,
            syncPendingItems = { uploadPendingRoomItems(userId) },
        )
    }

    suspend fun syncPendingToCloud(userId: String): Result<Int> {
        remote.ensureNetworkEnabled()
        val uploadRes = uploadPendingRoomItems(userId)
        if (uploadRes.isFailure) return uploadRes

        val flushRes = remote.flushPendingWrites()
        return if (flushRes.isFailure) {
            Result.failure(flushRes.exceptionOrNull()!!)
        } else {
            MinigeFirestoreDataSource.clearLastSyncError(null, userId)
            uploadRes
        }
    }

    /**
     * Reloads today's Room records immediately and runs a bounded, non-destructive
     * background cloud sync so pending local (`pendingSync = true`) rows are never lost.
     */
    suspend fun refreshTodayData(userId: String, dateKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            dao.getDeliveriesForDateOnce(userId, dateKey)
            dao.getExpensesForDateOnce(userId, dateKey)
            dao.getAllUtangPaymentsOnce(userId)

            val cloudSyncOutcome = withTimeoutOrNull(REFRESH_CLOUD_TIMEOUT_MS) {
                syncPendingToCloud(userId)
                remote.fetchDeliveriesOnce(userId).onSuccess { remoteDeliveries ->
                    withContext(NonCancellable) {
                        dao.mergeRemoteDeliveriesSafely(userId, remoteDeliveries)
                    }
                }
                remote.fetchUtangPaymentsOnce(userId).onSuccess { remotePayments ->
                    withContext(NonCancellable) {
                        dao.mergeRemoteUtangPaymentsSafely(userId, remotePayments)
                    }
                }
                remote.fetchExpensesOnce(userId).onSuccess { remoteExpenses ->
                    withContext(NonCancellable) {
                        dao.mergeRemoteExpensesSafely(userId, remoteExpenses)
                    }
                }
                true
            }

            if (cloudSyncOutcome == true) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Cloud refresh timed out; showing local records."))
            }
        }

    private suspend fun uploadPendingRoomItems(userId: String): Result<Int> {
        var syncedCount = 0
        var lastError: Throwable? = null

        val deliveriesOutcome = remote.flushInChunks(dao.getPendingDeliveries(userId)) { entity ->
            if (entity.isDeleted) {
                remote.deleteDelivery(userId, entity.id).also { res ->
                    if (res.isSuccess) dao.hardDeleteDelivery(entity.id)
                }
            } else {
                remote.upsertDelivery(userId, entity).also { res ->
                    if (res.isSuccess) dao.markDeliverySynced(entity.id)
                }
            }
        }
        syncedCount += deliveriesOutcome.syncedCount
        if (deliveriesOutcome.lastError != null) lastError = deliveriesOutcome.lastError

        val paymentsOutcome = remote.flushInChunks(dao.getPendingUtangPayments(userId)) { entity ->
            if (entity.isDeleted) {
                remote.deleteUtangPayment(userId, entity.id).also { res ->
                    if (res.isSuccess) dao.hardDeleteUtangPayment(entity.id)
                }
            } else {
                remote.upsertUtangPayment(userId, entity).also { res ->
                    if (res.isSuccess) dao.markUtangPaymentSynced(entity.id)
                }
            }
        }
        syncedCount += paymentsOutcome.syncedCount
        if (paymentsOutcome.lastError != null) lastError = paymentsOutcome.lastError

        val expensesOutcome = remote.flushInChunks(dao.getPendingExpenses(userId)) { entity ->
            if (entity.isDeleted) {
                remote.deleteExpense(userId, entity.id).also { res ->
                    if (res.isSuccess) dao.hardDeleteExpense(entity.id)
                }
            } else {
                remote.upsertExpense(userId, entity).also { res ->
                    if (res.isSuccess) dao.markExpenseSynced(entity.id)
                }
            }
        }
        syncedCount += expensesOutcome.syncedCount
        if (expensesOutcome.lastError != null) lastError = expensesOutcome.lastError

        val ledgersOutcome = remote.flushInChunks(dao.getPendingDailyLedgers(userId)) { entity ->
            remote.upsertDailyLedger(userId, entity).also { res ->
                if (res.isSuccess) dao.markDailyLedgerSynced(entity.dateKey)
            }
        }
        syncedCount += ledgersOutcome.syncedCount
        if (ledgersOutcome.lastError != null) lastError = ledgersOutcome.lastError

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

        val (custSynced, custError) = pricingRepository.syncPendingCustomers(userId)
        syncedCount += custSynced
        if (custError != null) lastError = custError

        return if (lastError != null) {
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
        customUnitPrice: Double? = null,
        overrideCommissionRate: Double? = null,
        hasCommission: Boolean = true,
    ): Result<Unit> {
        val cleanName = customerName.trim().take(80)
        val totalGals = purifiedGallons + alkalineGallons
        require(cleanName.isNotEmpty()) { "Customer name is required." }
        require(totalGals >= 1) { "Enter at least 1 gallon." }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        val (entity, customerEntity) = withContext(Dispatchers.IO + NonCancellable) {
            val settings = dao.getSettingsOnce()?.toDomain() ?: MinigeSettings()
            val validCustomPrice = customUnitPrice?.takeIf { it >= 0.0 }
            val usedPurifiedPrice = if (validCustomPrice != null && purifiedGallons > 0) {
                validCustomPrice.roundToInt()
            } else {
                settings.purifiedPrice
            }
            val usedAlkalinePrice = if (validCustomPrice != null && alkalineGallons > 0) {
                validCustomPrice.roundToInt()
            } else {
                settings.alkalinePrice
            }

            val totalBill = when {
                paymentType == PaymentType.PICK_UP || paymentType == PaymentType.PAID_WALK_IN -> 0
                validCustomPrice != null -> (totalGals * validCustomPrice).roundToInt()
                else -> (purifiedGallons * usedPurifiedPrice) + (alkalineGallons * usedAlkalinePrice)
            }

            val amountPaid = when (paymentType) {
                PaymentType.CASH, PaymentType.BANK_DEPOSIT -> totalBill
                PaymentType.PAID_WALK_IN, PaymentType.PAUTANG, PaymentType.PICK_UP -> 0
            }

            val effectiveRate = overrideCommissionRate ?: settings.commissionRate.toDouble()
            val storedRateInt = effectiveRate.roundToInt().coerceAtLeast(0)
            val commissionEarned = when (paymentType) {
                PaymentType.CASH, PaymentType.BANK_DEPOSIT ->
                    CommissionRateHistory.calculateCommissionAmount(totalGals, effectiveRate)
                PaymentType.PAID_WALK_IN ->
                    if (hasCommission) CommissionRateHistory.calculateCommissionAmount(totalGals, effectiveRate) else 0
                PaymentType.PAUTANG, PaymentType.PICK_UP -> 0
            }

            val settledDateKey = when (paymentType) {
                PaymentType.CASH, PaymentType.BANK_DEPOSIT, PaymentType.PAID_WALK_IN -> dateKey
                PaymentType.PAUTANG, PaymentType.PICK_UP -> null
            }

            val normalizedNotes = if (paymentType == PaymentType.PAID_WALK_IN) {
                PaymentBreakdownHelper.applyPaidWalkInTags(notes, hasCommission)
            } else {
                PaymentBreakdownHelper.stripPaidWalkInTags(notes).trim().take(300)
            }

            val deliveryEntity = DeliveryEntity(
                id = id,
                userId = userId,
                dateKey = dateKey,
                customerName = cleanName,
                purifiedGallons = purifiedGallons,
                alkalineGallons = alkalineGallons,
                purifiedUnitPrice = usedPurifiedPrice,
                alkalineUnitPrice = usedAlkalinePrice,
                totalAmount = totalBill,
                amountPaid = amountPaid,
                paymentType = paymentType.name,
                commissionRate = storedRateInt,
                commissionEarned = commissionEarned,
                settledDateKey = settledDateKey,
                notes = normalizedNotes,
                calendarEventId = null,
                pendingSync = true,
                isDeleted = false,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )

            dao.upsertDelivery(deliveryEntity)

            val isSmallGallon = SmallGallonHelper.isMaliitTagged(normalizedNotes)
            val savedCustomer = pricingRepository.rememberCustomerPriceLocal(
                userId = userId,
                customerName = cleanName,
                unitPriceToSave = if (!isSmallGallon && paymentType != PaymentType.PICK_UP && paymentType != PaymentType.PAID_WALK_IN) {
                    validCustomPrice
                        ?: if (alkalineGallons > 0 && purifiedGallons == 0) settings.alkalinePrice.toDouble()
                        else settings.purifiedPrice.toDouble()
                } else {
                    null
                },
                now = now,
            )

            deliveryEntity to savedCustomer
        }

        notifyLocalDataChanged()

        backgroundSyncScope.launch {
            try {
                val syncResult = remote.upsertDelivery(userId, entity)
                if (syncResult.isSuccess) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        val latest = dao.getDeliveryById(id)
                        if (latest != null && latest.updatedAtEpochMs == entity.updatedAtEpochMs) {
                            dao.markDeliverySynced(id)
                        }
                    }
                }
                if (customerEntity != null) {
                    pricingRepository.syncCustomerPriceToCloud(userId, customerEntity)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Local Room write already committed with pendingSync = true; background sync will retry.
            }
        }

        return Result.success(Unit)
    }

    suspend fun updateDelivery(
        userId: String,
        deliveryId: String,
        customerName: String,
        purifiedGallons: Int,
        alkalineGallons: Int,
        paymentType: PaymentType,
        notes: String,
        customUnitPrice: Double? = null,
        hasCommission: Boolean = true,
        overrideCommissionRate: Double? = null,
        customPurifiedUnitPrice: Double? = null,
        customAlkalineUnitPrice: Double? = null,
    ): Result<Unit> {
        val cleanName = customerName.trim().take(80)
        val totalGals = purifiedGallons + alkalineGallons
        require(cleanName.isNotEmpty()) { "Customer name is required." }
        require(totalGals >= 1) { "Enter at least 1 gallon." }

        val now = System.currentTimeMillis()
        val persisted = withContext(Dispatchers.IO + NonCancellable) {
            val existing = dao.getDeliveryById(deliveryId) ?: return@withContext null
            val settings = dao.getSettingsOnce()?.toDomain() ?: MinigeSettings()

            val validCustomPrice = customUnitPrice?.takeIf { it >= 0.0 }
            val validCustomPurified = customPurifiedUnitPrice?.takeIf { it >= 0.0 }
            val validCustomAlkaline = customAlkalineUnitPrice?.takeIf { it >= 0.0 }
            val hasSeparatePrices = validCustomPurified != null || validCustomAlkaline != null

            val usedPurifiedPrice = when {
                validCustomPurified != null -> validCustomPurified.roundToInt()
                !hasSeparatePrices && validCustomPrice != null && purifiedGallons > 0 -> validCustomPrice.roundToInt()
                else -> existing.purifiedUnitPrice.takeIf { it > 0 } ?: settings.purifiedPrice
            }
            val usedAlkalinePrice = when {
                validCustomAlkaline != null -> validCustomAlkaline.roundToInt()
                !hasSeparatePrices && validCustomPrice != null && alkalineGallons > 0 -> validCustomPrice.roundToInt()
                else -> existing.alkalineUnitPrice.takeIf { it > 0 } ?: settings.alkalinePrice
            }

            val totalBill = when {
                paymentType == PaymentType.PICK_UP || paymentType == PaymentType.PAID_WALK_IN -> 0
                hasSeparatePrices -> (
                    (purifiedGallons * (validCustomPurified ?: usedPurifiedPrice.toDouble())) +
                        (alkalineGallons * (validCustomAlkaline ?: usedAlkalinePrice.toDouble()))
                    ).roundToInt()
                validCustomPrice != null -> (totalGals * validCustomPrice).roundToInt()
                else -> (purifiedGallons * usedPurifiedPrice) + (alkalineGallons * usedAlkalinePrice)
            }

            val amountPaid = when (paymentType) {
                PaymentType.CASH, PaymentType.BANK_DEPOSIT -> totalBill
                PaymentType.PAUTANG -> existing.amountPaid.coerceIn(0, totalBill)
                PaymentType.PAID_WALK_IN, PaymentType.PICK_UP -> 0
            }

            val isSettled = paymentType == PaymentType.CASH ||
                paymentType == PaymentType.BANK_DEPOSIT ||
                paymentType == PaymentType.PAID_WALK_IN ||
                (paymentType == PaymentType.PAUTANG && (totalBill - amountPaid) <= 0)

            val effectiveRate = overrideCommissionRate
                ?: if (existing.commissionRate > 0) existing.commissionRate.toDouble() else settings.commissionRate.toDouble()
            val storedRateInt = effectiveRate.roundToInt().coerceAtLeast(0)
            val commissionEarned = when (paymentType) {
                PaymentType.CASH, PaymentType.BANK_DEPOSIT ->
                    CommissionRateHistory.calculateCommissionAmount(totalGals, effectiveRate)
                PaymentType.PAID_WALK_IN ->
                    if (hasCommission) CommissionRateHistory.calculateCommissionAmount(totalGals, effectiveRate) else 0
                PaymentType.PAUTANG ->
                    if (isSettled) CommissionRateHistory.calculateCommissionAmount(totalGals, effectiveRate) else 0
                PaymentType.PICK_UP -> 0
            }

            val settledDateKey = when (paymentType) {
                PaymentType.CASH, PaymentType.BANK_DEPOSIT, PaymentType.PAID_WALK_IN -> existing.dateKey
                PaymentType.PAUTANG -> if (isSettled) (existing.settledDateKey ?: existing.dateKey) else null
                PaymentType.PICK_UP -> null
            }

            val normalizedNotes = if (paymentType == PaymentType.PAID_WALK_IN) {
                PaymentBreakdownHelper.applyPaidWalkInTags(notes, hasCommission)
            } else {
                PaymentBreakdownHelper.stripPaidWalkInTags(notes).trim().take(300)
            }

            val updated = existing.copy(
                customerName = cleanName,
                purifiedGallons = purifiedGallons,
                alkalineGallons = alkalineGallons,
                purifiedUnitPrice = usedPurifiedPrice,
                alkalineUnitPrice = usedAlkalinePrice,
                totalAmount = totalBill,
                amountPaid = amountPaid,
                paymentType = paymentType.name,
                commissionRate = storedRateInt,
                commissionEarned = commissionEarned,
                settledDateKey = settledDateKey,
                notes = normalizedNotes,
                pendingSync = true,
                updatedAtEpochMs = now,
            )

            dao.upsertDelivery(updated)
            val isSmallGallon = SmallGallonHelper.isMaliitTagged(normalizedNotes)
            val savedCustomer = pricingRepository.rememberCustomerPriceLocal(
                userId = userId,
                customerName = cleanName,
                unitPriceToSave = if (!isSmallGallon && paymentType != PaymentType.PICK_UP && paymentType != PaymentType.PAID_WALK_IN) {
                    if (hasSeparatePrices) {
                        if (purifiedGallons > 0) validCustomPurified else null
                    } else {
                        validCustomPrice
                            ?: if (alkalineGallons > 0 && purifiedGallons == 0) settings.alkalinePrice.toDouble()
                            else settings.purifiedPrice.toDouble()
                    }
                } else {
                    null
                },
                now = now,
            )
            updated to savedCustomer
        } ?: return Result.failure(IllegalArgumentException("Delivery not found."))

        val (updatedEntity, customerEntity) = persisted
        notifyLocalDataChanged()

        backgroundSyncScope.launch {
            try {
                val syncResult = remote.upsertDelivery(userId, updatedEntity)
                if (syncResult.isSuccess) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        val latest = dao.getDeliveryById(deliveryId)
                        if (latest != null && latest.updatedAtEpochMs == updatedEntity.updatedAtEpochMs) {
                            dao.markDeliverySynced(deliveryId)
                        }
                    }
                }
                if (customerEntity != null) {
                    pricingRepository.syncCustomerPriceToCloud(userId, customerEntity)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Local Room write already committed with pendingSync = true; background sync will retry.
            }
        }

        return Result.success(Unit)
    }

    /**
     * Records a partial or full payment toward an unpaid Pautang delivery.
     * Peso-only partial payments earn ₱0 commission until settled; gallon-based partial payments
     * (via [recordPartialGallonPayment]) tag paid gallons & immediate commission in the payment notes
     * so they count immediately on [paymentDateKey] without double-counting upon full settlement.
     */
    suspend fun recordUtangPayment(
        userId: String,
        deliveryId: String,
        amount: Int,
        method: UtangMethod,
        paymentDateKey: String,
        notes: String,
        overrideCommissionRate: Double? = null,
        paymentId: String = UUID.randomUUID().toString(),
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
        val effectiveRate = overrideCommissionRate ?: if (
            SmallGallonHelper.isMaliitTagged(existing.notes) && existing.commissionRate > 0
        ) {
            existing.commissionRate.toDouble()
        } else {
            settings.commissionRate.toDouble()
        }
        val now = System.currentTimeMillis()

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

        dao.upsertUtangPayment(paymentEntity)

        val totalGals = existing.purifiedGallons + existing.alkalineGallons
        val allPaymentsForDelivery = dao.getUtangPaymentsForDelivery(deliveryId).map { it.toDomain() }
        val taggedGallons = allPaymentsForDelivery.sumOf { PartialPaymentHelper.extractPaidGallons(it) }
        val taggedCommission = allPaymentsForDelivery.sumOf { PartialPaymentHelper.extractCommissionEarned(it) }
        val untaggedRemainderGallons = (totalGals - taggedGallons).coerceAtLeast(0)

        val newCommissionRate = if (isNowFullyPaid) effectiveRate.roundToInt().coerceAtLeast(0) else existing.commissionRate
        val newCommissionEarned = if (isNowFullyPaid) {
            taggedCommission + CommissionRateHistory.calculateCommissionAmount(untaggedRemainderGallons, effectiveRate)
        } else {
            0
        }
        val newSettledDate = if (isNowFullyPaid) paymentDateKey else null

        val updatedDelivery = existing.copy(
            amountPaid = newAmountPaid,
            commissionRate = newCommissionRate,
            commissionEarned = newCommissionEarned,
            settledDateKey = newSettledDate,
            pendingSync = true,
            updatedAtEpochMs = now,
        )

        dao.upsertDelivery(updatedDelivery)

        val (pRes, dRes) = if (utangSyncOverrideForTest != null) {
            utangSyncOverrideForTest!!.invoke(userId, paymentEntity, updatedDelivery)
        } else {
            remote.upsertUtangPayment(userId, paymentEntity) to remote.upsertDelivery(userId, updatedDelivery)
        }
        if (pRes.isSuccess) dao.markUtangPaymentSynced(paymentId)
        if (dRes.isSuccess) dao.markDeliverySynced(deliveryId)

        notifyLocalDataChanged()
        return if (pRes.isFailure) Result.failure(pRes.exceptionOrNull()!!)
        else if (dRes.isFailure) Result.failure(dRes.exceptionOrNull()!!)
        else Result.success(isNowFullyPaid)
    }

    /**
     * Applies a lump-sum payment across all open Pautang deliveries for [customerName]
     * in FIFO order (`dateKey ASC, createdAtEpochMs ASC`), delegating each entry allocation
     * to [recordUtangPayment] so payment history, partial-gallon commission, settled dates,
     * and Firestore sync queue stay completely consistent.
     */
    suspend fun recordBulkUtangPayment(
        userId: String,
        customerName: String,
        amount: Int,
        method: UtangMethod,
        paymentDateKey: String,
        notes: String,
        overrideCommissionRate: Double? = null,
    ): Result<BulkUtangSettlementSummary> {
        val cleanCustomer = customerName.trim()
        if (cleanCustomer.isEmpty()) {
            return Result.failure(IllegalArgumentException("Customer name is required."))
        }
        if (amount <= 0) {
            return Result.failure(IllegalArgumentException("Payment amount must be greater than ₱0."))
        }

        val targetKey = BulkUtangFifoHelper.normalizeCustomerKey(cleanCustomer)
        val openUtang = dao.getAllDeliveriesOnce(userId)
            .map { it.toDomain() }
            .filter {
                it.paymentType == PaymentType.PAUTANG &&
                    it.remainingBalance > 0 &&
                    BulkUtangFifoHelper.normalizeCustomerKey(it.customerName) == targetKey
            }
            .sortedWith(
                compareBy<DeliveryRecord> { it.dateKey }
                    .thenBy { it.createdAtEpochMs }
                    .thenBy { it.id }
            )

        if (openUtang.isEmpty()) {
            return Result.failure(IllegalStateException("No unpaid pautang entries found for $cleanCustomer."))
        }

        val totalOpenBefore = openUtang.sumOf { it.remainingBalance }
        var remainingToAllocate = amount
        var totalApplied = 0
        var affectedCount = 0
        var fullySettledCount = 0
        var partiallyPaidCount = 0
        val settledDateKeys = mutableListOf<String>()
        val partiallyPaidDateKeys = mutableListOf<String>()
        var anyPendingCloudSync = false

        for (delivery in openUtang) {
            if (remainingToAllocate <= 0) break
            val remainingForEntry = delivery.remainingBalance
            if (remainingForEntry <= 0) continue

            val chunk = minOf(remainingToAllocate, remainingForEntry)
            val amountPaidBefore = delivery.amountPaid

            val stepRes = recordUtangPayment(
                userId = userId,
                deliveryId = delivery.id,
                amount = chunk,
                method = method,
                paymentDateKey = paymentDateKey,
                notes = notes,
                overrideCommissionRate = overrideCommissionRate,
            )

            val afterDelivery = dao.getDeliveryById(delivery.id)?.toDomain()
            val appliedLocally = if (afterDelivery != null) {
                (afterDelivery.amountPaid - amountPaidBefore).coerceAtLeast(0)
            } else {
                0
            }

            if (stepRes.isSuccess || appliedLocally > 0) {
                if (stepRes.isFailure) {
                    anyPendingCloudSync = true
                }
                val actualApplied = if (appliedLocally > 0) appliedLocally else chunk
                remainingToAllocate = (remainingToAllocate - actualApplied).coerceAtLeast(0)
                totalApplied += actualApplied
                affectedCount++
                val isSettled = afterDelivery?.isUtangFullyPaid == true || stepRes.getOrNull() == true
                if (isSettled) {
                    fullySettledCount++
                    settledDateKeys += delivery.dateKey
                } else {
                    partiallyPaidCount++
                    partiallyPaidDateKeys += delivery.dateKey
                }
            } else {
                if (totalApplied == 0) {
                    return Result.failure(
                        stepRes.exceptionOrNull()
                            ?: IllegalStateException("Failed to record bulk pautang payment.")
                    )
                } else {
                    anyPendingCloudSync = true
                    break
                }
            }
        }

        return Result.success(
            BulkUtangSettlementSummary(
                customerName = openUtang.first().customerName.trim(),
                requestedAmount = amount,
                totalAppliedAmount = totalApplied,
                excessAmount = (amount - totalApplied).coerceAtLeast(0),
                remainingCustomerBalance = (totalOpenBefore - totalApplied).coerceAtLeast(0),
                affectedCount = affectedCount,
                fullySettledCount = fullySettledCount,
                partiallyPaidCount = partiallyPaidCount,
                settledDateKeys = settledDateKeys,
                partiallyPaidDateKeys = partiallyPaidDateKeys,
                pendingCloudSync = anyPendingCloudSync,
            )
        )
    }

    suspend fun recordPartialGallonPayment(
        userId: String,
        deliveryId: String,
        paidGallons: Int,
        amount: Int,
        channel: PaidChannel,
        paymentDateKey: String,
        notes: String,
        overrideCommissionRate: Double? = null,
        paymentId: String = UUID.randomUUID().toString(),
    ): Result<Boolean> {
        val settings = dao.getSettingsOnce()?.toDomain() ?: MinigeSettings()
        val effectiveRate = overrideCommissionRate ?: settings.commissionRate.toDouble()
        val chunkCommission = CommissionRateHistory.calculateCommissionAmount(paidGallons, effectiveRate)
        val taggedNotes = PartialPaymentHelper.applyPartialGallonTag(
            notes = notes,
            paidGallons = paidGallons,
            commissionEarned = chunkCommission,
            channel = channel,
        )
        return recordUtangPayment(
            userId = userId,
            deliveryId = deliveryId,
            amount = amount,
            method = channel.storedUtangMethod,
            paymentDateKey = paymentDateKey,
            notes = taggedNotes,
            overrideCommissionRate = effectiveRate,
            paymentId = paymentId,
        )
    }

    suspend fun getDeliverySnapshot(deliveryId: String): DeliveryRecord? =
        withContext(Dispatchers.IO) {
            dao.getDeliveryById(deliveryId)?.toDomain()
        }

    suspend fun restoreDelivery(userId: String, snapshot: DeliveryRecord): Result<Unit> {
        val now = System.currentTimeMillis()
        val entity = DeliveryEntity(
            id = snapshot.id,
            userId = userId,
            dateKey = snapshot.dateKey,
            customerName = snapshot.customerName,
            purifiedGallons = snapshot.purifiedGallons,
            alkalineGallons = snapshot.alkalineGallons,
            purifiedUnitPrice = snapshot.purifiedUnitPrice,
            alkalineUnitPrice = snapshot.alkalineUnitPrice,
            totalAmount = snapshot.totalAmount,
            amountPaid = snapshot.amountPaid,
            paymentType = snapshot.paymentType.name,
            commissionRate = snapshot.commissionRate,
            commissionEarned = snapshot.commissionEarned,
            settledDateKey = snapshot.settledDateKey,
            notes = snapshot.notes,
            calendarEventId = snapshot.calendarEventId,
            pendingSync = true,
            isDeleted = false,
            createdAtEpochMs = snapshot.createdAtEpochMs,
            updatedAtEpochMs = now,
        )
        withContext(Dispatchers.IO + NonCancellable) {
            dao.upsertDelivery(entity)
        }
        notifyLocalDataChanged()
        backgroundSyncScope.launch {
            try {
                val res = remote.upsertDelivery(userId, entity)
                if (res.isSuccess) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        val latest = dao.getDeliveryById(snapshot.id)
                        if (latest != null && latest.updatedAtEpochMs == entity.updatedAtEpochMs) {
                            dao.markDeliverySynced(snapshot.id)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result.success(Unit)
    }

    suspend fun undoUtangPayment(
        userId: String,
        previousDelivery: DeliveryRecord,
        createdPaymentId: String,
    ): Result<Unit> {
        withContext(Dispatchers.IO + NonCancellable) {
            dao.softDeleteUtangPayment(createdPaymentId)
        }
        val restoreRes = restoreDelivery(userId, previousDelivery)
        val deleteRes = remote.deleteUtangPayment(userId, createdPaymentId)
        if (deleteRes.isSuccess) {
            withContext(Dispatchers.IO + NonCancellable) {
                dao.hardDeleteUtangPayment(createdPaymentId)
            }
        }
        notifyLocalDataChanged()
        return if (restoreRes.isFailure) restoreRes else deleteRes
    }

    suspend fun deleteDelivery(userId: String, deliveryId: String): Result<Unit> {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO + NonCancellable) {
            dao.softDeleteDelivery(deliveryId, now)
        }
        notifyLocalDataChanged()
        backgroundSyncScope.launch {
            try {
                val res = remote.deleteDelivery(userId, deliveryId)
                if (res.isSuccess) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        val latest = dao.getDeliveryById(deliveryId)
                        if (latest != null && latest.isDeleted && latest.updatedAtEpochMs == now) {
                            dao.hardDeleteDelivery(deliveryId)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result.success(Unit)
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
        withContext(Dispatchers.IO + NonCancellable) {
            dao.upsertExpense(entity)
        }
        notifyLocalDataChanged()
        backgroundSyncScope.launch {
            try {
                val res = remote.upsertExpense(userId, entity)
                if (res.isSuccess) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        dao.markExpenseSynced(id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result.success(Unit)
    }

    suspend fun deleteExpense(userId: String, expenseId: String): Result<Unit> {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO + NonCancellable) {
            dao.softDeleteExpense(expenseId, now)
        }
        notifyLocalDataChanged()
        backgroundSyncScope.launch {
            try {
                val res = remote.deleteExpense(userId, expenseId)
                if (res.isSuccess) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        dao.hardDeleteExpense(expenseId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return Result.success(Unit)
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
        withContext(Dispatchers.IO + NonCancellable) {
            dao.upsertDailyLedger(entity)
        }
        notifyLocalDataChanged()
        val res = remote.upsertDailyLedger(userId, entity)
        if (res.isSuccess) {
            withContext(Dispatchers.IO + NonCancellable) {
                dao.markDailyLedgerSynced(dateKey)
            }
        }
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
        withContext(Dispatchers.IO + NonCancellable) {
            dao.upsertSettings(entity)
        }
        notifyLocalDataChanged()
        val res = remote.upsertSettings(userId, entity)
        if (res.isSuccess) {
            withContext(Dispatchers.IO + NonCancellable) {
                dao.upsertSettings(entity.copy(pendingSync = false))
            }
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
            val updated = withContext(Dispatchers.IO + NonCancellable) {
                val current = dao.getDeliveryById(id) ?: return@withContext null
                val next = current.copy(
                    calendarEventId = calendarEventId,
                    pendingSync = true,
                    updatedAtEpochMs = now,
                )
                dao.upsertDelivery(next)
                next
            } ?: return@forEach
            val res = remote.upsertDelivery(userId, updated)
            if (res.isSuccess) {
                withContext(Dispatchers.IO + NonCancellable) {
                    dao.markDeliverySynced(id)
                }
            }
        }
    }

    companion object {
        private const val REFRESH_CLOUD_TIMEOUT_MS = 8_000L
    }
}
