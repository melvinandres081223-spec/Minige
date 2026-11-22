package com.melvin.minige.ui

import android.content.IntentSender
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.melvin.minige.R
import com.melvin.minige.data.MinigeRepository
import com.melvin.minige.data.PricingRepository
import com.melvin.minige.data.local.MinigeDatabase
import com.melvin.minige.data.remote.FirestoreSyncHelper
import com.melvin.minige.data.remote.MinigeFirestoreDataSource
import com.melvin.minige.domain.BossPickupFormatter
import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.DailySummary
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.MinigeCalculator
import com.melvin.minige.domain.MinigeDateUtils
import com.melvin.minige.domain.MinigeSettings
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.domain.WeeklyDaySummary
import com.melvin.minige.domain.WeeklySummary
import com.melvin.minige.auth.DriveAuthResult
import com.melvin.minige.auth.GoogleAuthManager
import com.melvin.minige.sync.CalendarAuthResult
import com.melvin.minige.sync.CalendarSyncManager
import com.melvin.minige.sync.DriveBackupManager
import com.melvin.minige.sync.DriveBackupPreview
import com.melvin.minige.sync.BackupMetadataSummary
import com.melvin.minige.sync.DriveBackupStatus
import com.melvin.minige.ui.components.DailyQuotaPrefs
import com.melvin.minige.ui.components.SukiAnalyticsHelper
import com.melvin.minige.ui.components.SundayMakeupHelper
import com.melvin.minige.ui.components.WeeklySukiAnalytics
import com.melvin.minige.util.CommissionRateEntry
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.CustomerDirectoryHelper
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PaymentBreakdownHelper
import com.melvin.minige.util.PaymentChannelBreakdown
import com.melvin.minige.util.SukiCustomerProfile
import com.melvin.minige.util.UndoStateHelper
import com.melvin.minige.util.UndoableAction
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.util.UUID

@Immutable
data class MinigeUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedDateKey: String = MinigeDateUtils.toKey(LocalDate.now()),
    val settings: MinigeSettings = MinigeSettings(),
    val deliveriesForDay: List<DeliveryRecord> = emptyList(),
    val pickupsForDay: List<DeliveryRecord> = emptyList(),
    val expensesForDay: List<ExpenseRecord> = emptyList(),
    val activeUtangList: List<DeliveryRecord> = emptyList(),
    val settledUtangList: List<DeliveryRecord> = emptyList(),
    val utangPayments: List<UtangPaymentRecord> = emptyList(),
    val dailySummary: DailySummary = MinigeCalculator.calculateDailySummary(
        dateKey = MinigeDateUtils.toKey(LocalDate.now()),
        deliveries = emptyList(),
        utangPayments = emptyList(),
        expenses = emptyList(),
        startingSukli = 0,
    ),
    val weeklySummary: WeeklySummary = MinigeCalculator.calculateWeeklySummary(
        anchorDate = LocalDate.now(),
        deliveries = emptyList(),
        utangPayments = emptyList(),
        expenses = emptyList(),
    ),
    val todayPaymentBreakdown: PaymentChannelBreakdown = PaymentBreakdownHelper.calculateBreakdown(
        dateKey = MinigeDateUtils.toKey(LocalDate.now()),
        deliveries = emptyList(),
        utangPayments = emptyList(),
        expenses = emptyList(),
    ),
    val sundayMakeupEnabled: Boolean = false,
    val sundayMakeupDaySummary: WeeklyDaySummary = SundayMakeupHelper.calculateSundayDaySummary(
        monday = MinigeDateUtils.weekMonToSat(LocalDate.now()).first(),
        deliveries = emptyList(),
        utangPayments = emptyList(),
        expenses = emptyList(),
    ),
    val bossPickupMessage: String = "",
    val customers: List<CustomerRecord> = emptyList(),
    val sukiDirectory: List<SukiCustomerProfile> = emptyList(),
    val commissionRateHistory: List<CommissionRateEntry> = emptyList(),
    val activeCommissionRate: Double = CommissionRateHistory.DEFAULT_RATE,
    val dailyQuotaTarget: Int = DailyQuotaPrefs.DEFAULT_QUOTA_GALLONS,
    val weeklySukiAnalytics: WeeklySukiAnalytics = WeeklySukiAnalytics(),
    val pendingSyncCount: Int = 0,
    val isSyncing: Boolean = false,
    val isCalendarSyncing: Boolean = false,
    val statusMessage: String? = null,
    val driveBackupStatus: DriveBackupStatus = DriveBackupStatus.NEEDS_PERMISSION,
    val driveLastBackupTimestamp: Long = 0L,
    val isDriveBackingUp: Boolean = false,
    val driveBackupSummary: com.melvin.minige.sync.BackupMetadataSummary? = null,
    val driveBackupPreview: DriveBackupPreview? = null,
    val showRestorePreviewDialog: Boolean = false,
)

sealed interface MinigeEvent {
    data class ShowMessage(val message: String) : MinigeEvent
    data class LaunchCalendarConsent(val intentSender: IntentSender) : MinigeEvent
    data class LaunchDriveConsent(val intentSender: IntentSender) : MinigeEvent
}

class MinigeViewModel(
    private val repository: MinigeRepository,
    private val pricingRepository: PricingRepository,
    private val currentUserId: String,
    private val appContext: android.content.Context? = null,
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    private val _sundayMakeupEnabled = MutableStateFlow(
        appContext?.let { com.melvin.minige.ui.components.SundayMakeupPrefs.isEnabled(it, currentUserId) } ?: false
    )
    private val _commissionRateHistory = MutableStateFlow(
        appContext?.let { CommissionRateHistory.loadHistory(it, currentUserId) } ?: emptyList()
    )
    private val _dailyQuotaTarget = MutableStateFlow(
        appContext?.let { DailyQuotaPrefs.loadQuotaTarget(it, currentUserId) }
            ?: DailyQuotaPrefs.DEFAULT_QUOTA_GALLONS
    )
    private val _isSyncing = MutableStateFlow(false)
    private val _isCalendarSyncing = MutableStateFlow(false)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _driveBackupStatus = MutableStateFlow(
        appContext?.let {
            if (GoogleAuthManager.hasDrivePermission(it)) DriveBackupStatus.ACTIVE
            else DriveBackupStatus.NEEDS_PERMISSION
        } ?: DriveBackupStatus.NEEDS_PERMISSION
    )
    private val _driveLastBackupTimestamp = MutableStateFlow(
        appContext?.let { DriveBackupManager.getLastBackupTimestamp(it, currentUserId) } ?: 0L
    )
    private val _isDriveBackingUp = MutableStateFlow(false)
    private val _driveBackupPreview = MutableStateFlow<DriveBackupPreview?>(null)
    private val _showRestorePreviewDialog = MutableStateFlow(false)

    private var autoBackupJob: Job? = null
    private var cachedDriveToken: String? = null

    private val _undoableAction = MutableStateFlow<UndoableAction?>(null)
    val undoableAction: StateFlow<UndoableAction?> = _undoableAction.asStateFlow()
    private var undoDismissJob: Job? = null
    @Volatile
    private var latestBundle: LocalBundle? = null
    private var pendingCalendarSyncDate: LocalDate? = null

    private val _events = MutableSharedFlow<MinigeEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MinigeEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.localDataChangeEvents.collect {
                scheduleAutoBackup()
            }
        }
        viewModelScope.launch {
            try {
                repository.startCloudSync(
                    userId = currentUserId,
                    onError = { error ->
                        Log.w(TAG, "Cloud sync stream error", error)
                        _statusMessage.value = FirestoreSyncHelper.formatSyncErrorMessage(error)
                    },
                    onAutoSynced = {
                        _statusMessage.value = null
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cloud sync initialization failed", e)
            }
        }
    }

    private val localDataFlow = combine(
        repository.observeDeliveries(currentUserId),
        repository.observeUtangPayments(currentUserId),
        repository.observeExpenses(currentUserId),
        repository.observeDailyLedgers(currentUserId),
        repository.observeSettings(),
        repository.observeCustomers(currentUserId),
        repository.observePendingSyncCount(currentUserId),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val deliveries = values[0] as List<DeliveryRecord>
        @Suppress("UNCHECKED_CAST")
        val payments = values[1] as List<UtangPaymentRecord>
        @Suppress("UNCHECKED_CAST")
        val expenses = values[2] as List<ExpenseRecord>
        @Suppress("UNCHECKED_CAST")
        val ledgers = values[3] as List<com.melvin.minige.domain.DailyLedgerRecord>
        val settings = values[4] as MinigeSettings
        @Suppress("UNCHECKED_CAST")
        val customers = values[5] as List<CustomerRecord>
        val pendingCount = values[6] as Int
        LocalBundle(deliveries, payments, expenses, ledgers, settings, customers, pendingCount)
    }

    private data class ViewPrefsSnapshot(
        val date: LocalDate,
        val sundayEnabled: Boolean,
        val rateHistory: List<CommissionRateEntry>,
        val quotaTarget: Int,
    )

    private data class DriveBackupSnapshot(
        val status: DriveBackupStatus,
        val lastTimestamp: Long,
        val isBackingUp: Boolean,
        val preview: DriveBackupPreview?,
        val showPreview: Boolean,
    )

    val uiState: StateFlow<MinigeUiState> = combine(
        combine(_selectedDate, _sundayMakeupEnabled, _commissionRateHistory, _dailyQuotaTarget) { date, sundayEnabled, rateHist, quotaTarget ->
            ViewPrefsSnapshot(date, sundayEnabled, rateHist, quotaTarget)
        },
        localDataFlow,
        combine(_driveBackupStatus, _driveLastBackupTimestamp, _isDriveBackingUp, _driveBackupPreview, _showRestorePreviewDialog) { status, lastTs, backingUp, preview, showPreview ->
            DriveBackupSnapshot(status, lastTs, backingUp, preview, showPreview)
        },
        _isSyncing,
        combine(_isCalendarSyncing, _statusMessage) { calSyncing, status ->
            Pair(calSyncing, status)
        },
    ) { prefs, bundle, driveSnap, syncing, calAndStatus ->
        val calSyncing = calAndStatus.first
        val status = calAndStatus.second
        val date = prefs.date
        val sundayEnabled = prefs.sundayEnabled
        val rateHist = prefs.rateHistory
        val quotaTarget = prefs.quotaTarget
        latestBundle = bundle
        val dateKey = MinigeDateUtils.toKey(date)
        val deliveriesForDay = bundle.deliveries.filter { it.dateKey == dateKey }
        val pickupsForDay = deliveriesForDay.filter { it.paymentType == PaymentType.PICK_UP }
        val expensesForDay = bundle.expenses.filter { it.dateKey == dateKey }

        val ledgerSukli = bundle.ledgers
            .firstOrNull { it.dateKey == dateKey }
            ?.startingSukli
            ?: bundle.settings.defaultSukli

        val dailySummary = MinigeCalculator.calculateDailySummary(
            dateKey = dateKey,
            deliveries = bundle.deliveries,
            utangPayments = bundle.payments,
            expenses = bundle.expenses,
            startingSukli = ledgerSukli,
        )

        val weeklySummary = MinigeCalculator.calculateWeeklySummary(
            anchorDate = date,
            deliveries = bundle.deliveries,
            utangPayments = bundle.payments,
            expenses = bundle.expenses,
        )

        val todayPaymentBreakdown = PaymentBreakdownHelper.calculateBreakdown(
            dateKey = dateKey,
            deliveries = bundle.deliveries,
            utangPayments = bundle.payments,
            expenses = bundle.expenses,
        )

        val sundayMakeupDaySummary = SundayMakeupHelper.calculateSundayDaySummary(
            monday = weeklySummary.monday,
            deliveries = bundle.deliveries,
            utangPayments = bundle.payments,
            expenses = bundle.expenses,
        )

        val allUtang = bundle.deliveries.filter { it.paymentType == PaymentType.PAUTANG }
        val activeUtang = allUtang.filter { !it.isUtangFullyPaid }
        val settledUtang = allUtang.filter { it.isUtangFullyPaid }

        val mergedCustomers = pricingRepository.mergeWithHistoricalDeliveries(
            savedCustomers = bundle.customers,
            deliveries = bundle.deliveries,
        )
        val sukiDirectory = CustomerDirectoryHelper.buildDirectory(
            pricingCustomers = mergedCustomers,
            deliveries = bundle.deliveries,
            utangPayments = bundle.payments,
        )
        val activeRate = CommissionRateHistory.effectiveRateForDate(
            history = rateHist,
            targetDate = date,
            fallbackRate = bundle.settings.commissionRate.toDouble(),
        )

        val baseWeekDates = MinigeDateUtils.weekMonToSat(date)
        val effectiveWeekDates = if (sundayEnabled) {
            baseWeekDates + baseWeekDates.last().plusDays(1)
        } else {
            baseWeekDates
        }
        val weeklySukiAnalytics = SukiAnalyticsHelper.compute(
            weekDates = effectiveWeekDates,
            deliveries = bundle.deliveries,
            utangPayments = bundle.payments,
        )

        val pendingCount = bundle.pendingCount
        val driveMetaSummary = appContext?.let { DriveBackupManager.getBackupMetadataSummary(it, currentUserId) }

        MinigeUiState(
            selectedDate = date,
            selectedDateKey = dateKey,
            settings = bundle.settings,
            deliveriesForDay = deliveriesForDay,
            pickupsForDay = pickupsForDay,
            expensesForDay = expensesForDay,
            activeUtangList = activeUtang,
            settledUtangList = settledUtang,
            utangPayments = bundle.payments,
            dailySummary = dailySummary,
            weeklySummary = weeklySummary,
            todayPaymentBreakdown = todayPaymentBreakdown,
            sundayMakeupEnabled = sundayEnabled,
            sundayMakeupDaySummary = sundayMakeupDaySummary,
            bossPickupMessage = BossPickupFormatter.format(date, pickupsForDay),
            customers = mergedCustomers,
            sukiDirectory = sukiDirectory,
            commissionRateHistory = rateHist,
            activeCommissionRate = activeRate,
            dailyQuotaTarget = quotaTarget,
            weeklySukiAnalytics = weeklySukiAnalytics,
            pendingSyncCount = pendingCount,
            isSyncing = syncing,
            isCalendarSyncing = calSyncing,
            statusMessage = status,
            driveBackupStatus = driveSnap.status,
            driveLastBackupTimestamp = driveSnap.lastTimestamp,
            isDriveBackingUp = driveSnap.isBackingUp,
            driveBackupSummary = driveMetaSummary,
            driveBackupPreview = driveSnap.preview,
            showRestorePreviewDialog = driveSnap.showPreview,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = MinigeUiState(),
    )

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun shiftDate(days: Long) {
        _selectedDate.value = _selectedDate.value.plusDays(days)
    }

    fun jumpToToday() {
        _selectedDate.value = LocalDate.now()
    }

    fun setSundayMakeupMode(enabled: Boolean) {
        _sundayMakeupEnabled.value = enabled
        appContext?.let { com.melvin.minige.ui.components.SundayMakeupPrefs.setEnabled(it, currentUserId, enabled) }
        scheduleAutoBackup()
    }

    fun updateDailyQuotaTarget(targetGallons: Int) {
        val saved = appContext?.let { DailyQuotaPrefs.saveQuotaTarget(it, currentUserId, targetGallons) }
            ?: targetGallons.coerceIn(DailyQuotaPrefs.MIN_QUOTA_GALLONS, DailyQuotaPrefs.MAX_QUOTA_GALLONS)
        _dailyQuotaTarget.value = saved
        _events.tryEmit(MinigeEvent.ShowMessage("Daily hakot quota set to $saved gal"))
        scheduleAutoBackup()
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    private fun showUndoableAction(action: UndoableAction) {
        undoDismissJob?.cancel()
        _undoableAction.value = action
        undoDismissJob = viewModelScope.launch {
            delay(UndoStateHelper.UNDO_DURATION_MS)
            _undoableAction.value = null
        }
    }

    fun dismissUndoAction() {
        undoDismissJob?.cancel()
        _undoableAction.value = null
    }

    fun executeUndo() {
        val action = _undoableAction.value ?: return
        dismissUndoAction()
        viewModelScope.launch {
            val result = when (action) {
                is UndoableAction.DeliveryDeleted -> repository.restoreDelivery(currentUserId, action.snapshot)
                is UndoableAction.DeliveryUpdated -> repository.restoreDelivery(currentUserId, action.previousSnapshot)
                is UndoableAction.UtangPaymentRecorded -> repository.undoUtangPayment(
                    userId = currentUserId,
                    previousDelivery = action.previousDelivery,
                    createdPaymentId = action.createdPaymentId,
                )
            }
            val msg = if (result.isSuccess) {
                "Undone: ${action.message}"
            } else {
                "Undone locally (queued for sync)"
            }
            _events.tryEmit(MinigeEvent.ShowMessage(msg))
            scheduleAutoBackup()
        }
    }

    fun addDelivery(
        customerName: String,
        purifiedGallons: Int,
        alkalineGallons: Int,
        paymentType: PaymentType,
        notes: String,
        customUnitPrice: Double? = null,
    ) {
        val dateKey = MinigeDateUtils.toKey(_selectedDate.value)
        val activeRate = uiState.value.activeCommissionRate
        viewModelScope.launch {
            val result = repository.addDelivery(
                userId = currentUserId,
                dateKey = dateKey,
                customerName = customerName,
                purifiedGallons = purifiedGallons,
                alkalineGallons = alkalineGallons,
                paymentType = paymentType,
                notes = notes,
                customUnitPrice = customUnitPrice,
                overrideCommissionRate = activeRate,
            )
            val msg = if (result.isSuccess) {
                "Saved ${customerName.trim()} (${paymentType.label})"
            } else {
                "Saved locally — will sync to cloud when online."
            }
            _events.tryEmit(MinigeEvent.ShowMessage(msg))
            scheduleAutoBackup()
        }
    }

    fun updateDelivery(
        deliveryId: String,
        customerName: String,
        purifiedGallons: Int,
        alkalineGallons: Int,
        paymentType: PaymentType,
        notes: String,
        customUnitPrice: Double? = null,
    ) {
        viewModelScope.launch {
            val previousSnapshot = repository.getDeliverySnapshot(deliveryId)
            val result = repository.updateDelivery(
                userId = currentUserId,
                deliveryId = deliveryId,
                customerName = customerName,
                purifiedGallons = purifiedGallons,
                alkalineGallons = alkalineGallons,
                paymentType = paymentType,
                notes = notes,
                customUnitPrice = customUnitPrice,
            )
            if (previousSnapshot != null) {
                val actionMsg = if (previousSnapshot.paymentType != paymentType) {
                    "Swapped ${customerName.trim()} to ${paymentType.label}"
                } else {
                    "Updated ${customerName.trim()}"
                }
                showUndoableAction(
                    UndoableAction.DeliveryUpdated(
                        previousSnapshot = previousSnapshot,
                        message = actionMsg,
                    )
                )
            } else {
                val msg = if (result.isSuccess) {
                    "Updated ${customerName.trim()} (${paymentType.label})"
                } else {
                    "Updated locally — will sync to cloud when online."
                }
                _events.tryEmit(MinigeEvent.ShowMessage(msg))
            }
            scheduleAutoBackup()
        }
    }

    fun deleteDelivery(deliveryId: String) {
        viewModelScope.launch {
            val snapshot = repository.getDeliverySnapshot(deliveryId)
            val result = repository.deleteDelivery(currentUserId, deliveryId)
            if (snapshot != null) {
                showUndoableAction(
                    UndoableAction.DeliveryDeleted(
                        snapshot = snapshot,
                        message = "Deleted ${snapshot.customerName}",
                    )
                )
            } else {
                val msg = if (result.isSuccess) "Entry deleted" else "Deleted locally (pending cloud sync)"
                _events.tryEmit(MinigeEvent.ShowMessage(msg))
            }
            scheduleAutoBackup()
        }
    }

    fun recordUtangPayment(
        deliveryId: String,
        amount: Int,
        method: UtangMethod,
        notes: String,
    ) {
        val paymentDateKey = MinigeDateUtils.toKey(_selectedDate.value)
        val activeRate = uiState.value.activeCommissionRate
        val paymentId = UUID.randomUUID().toString()
        viewModelScope.launch {
            val previousDelivery = repository.getDeliverySnapshot(deliveryId)
            val result = repository.recordUtangPayment(
                userId = currentUserId,
                deliveryId = deliveryId,
                amount = amount,
                method = method,
                paymentDateKey = paymentDateKey,
                notes = notes,
                overrideCommissionRate = activeRate,
                paymentId = paymentId,
            )
            result.onSuccess { fullyPaid ->
                if (previousDelivery != null) {
                    val undoText = if (fullyPaid) {
                        "Marked ${previousDelivery.customerName} paid"
                    } else {
                        "Paid ₱$amount (${previousDelivery.customerName})"
                    }
                    showUndoableAction(
                        UndoableAction.UtangPaymentRecorded(
                            previousDelivery = previousDelivery,
                            createdPaymentId = paymentId,
                            message = undoText,
                        )
                    )
                } else {
                    val msg = if (fullyPaid) {
                        "Utang fully paid! Commission awarded for $paymentDateKey."
                    } else {
                        "Partial payment recorded (₱0 commission until fully paid)."
                    }
                    _events.tryEmit(MinigeEvent.ShowMessage(msg))
                }
                scheduleAutoBackup()
            }.onFailure { err ->
                _events.tryEmit(MinigeEvent.ShowMessage(err.localizedMessage ?: "Payment saved locally."))
            }
        }
    }

    fun recordPartialGallonPayment(
        deliveryId: String,
        paidGallons: Int,
        amount: Int,
        channel: PaidChannel,
        notes: String,
    ) {
        val paymentDateKey = MinigeDateUtils.toKey(_selectedDate.value)
        val activeRate = uiState.value.activeCommissionRate
        val immediateComm = CommissionRateHistory.calculateCommissionAmount(paidGallons, activeRate)
        val paymentId = UUID.randomUUID().toString()
        viewModelScope.launch {
            val previousDelivery = repository.getDeliverySnapshot(deliveryId)
            val result = repository.recordPartialGallonPayment(
                userId = currentUserId,
                deliveryId = deliveryId,
                paidGallons = paidGallons,
                amount = amount,
                channel = channel,
                paymentDateKey = paymentDateKey,
                notes = notes,
                overrideCommissionRate = activeRate,
                paymentId = paymentId,
            )
            result.onSuccess { fullyPaid ->
                if (previousDelivery != null) {
                    val undoText = if (fullyPaid) {
                        "Settled ${previousDelivery.customerName} (+₱$immediateComm)"
                    } else {
                        "Partial pay ${paidGallons}g (${previousDelivery.customerName})"
                    }
                    showUndoableAction(
                        UndoableAction.UtangPaymentRecorded(
                            previousDelivery = previousDelivery,
                            createdPaymentId = paymentId,
                            message = undoText,
                        )
                    )
                } else {
                    val msg = if (fullyPaid) {
                        "Settled $paidGallons gal(s)! +₱$immediateComm commission awarded for $paymentDateKey."
                    } else {
                        "Partial pay: $paidGallons gal(s) (₱$amount ${channel.label}) → +₱$immediateComm commission today!"
                    }
                    _events.tryEmit(MinigeEvent.ShowMessage(msg))
                }
                scheduleAutoBackup()
            }.onFailure { err ->
                _events.tryEmit(MinigeEvent.ShowMessage(err.localizedMessage ?: "Partial payment saved locally."))
            }
        }
    }

    fun addExpense(category: ExpenseCategory, amount: Int, notes: String) {
        val dateKey = MinigeDateUtils.toKey(_selectedDate.value)
        viewModelScope.launch {
            val result = repository.addExpense(
                userId = currentUserId,
                dateKey = dateKey,
                category = category,
                amount = amount,
                notes = notes,
            )
            val msg = if (result.isSuccess) {
                "Expense recorded: ${category.label} ₱$amount"
            } else {
                "Expense saved locally (pending sync)"
            }
            _events.tryEmit(MinigeEvent.ShowMessage(msg))
            scheduleAutoBackup()
        }
    }

    fun deleteExpense(expenseId: String) {
        viewModelScope.launch {
            repository.deleteExpense(currentUserId, expenseId)
            _events.tryEmit(MinigeEvent.ShowMessage("Expense removed"))
            scheduleAutoBackup()
        }
    }

    fun updateStartingSukli(sukli: Int) {
        val dateKey = MinigeDateUtils.toKey(_selectedDate.value)
        viewModelScope.launch {
            repository.updateStartingSukli(currentUserId, dateKey, sukli)
            _events.tryEmit(MinigeEvent.ShowMessage("Starting sukli updated to ₱$sukli"))
            scheduleAutoBackup()
        }
    }

    fun updateSettings(
        purifiedPrice: Int,
        alkalinePrice: Int,
        commissionRate: Int,
        defaultSukli: Int,
    ) {
        viewModelScope.launch {
            val res = repository.updateSettings(
                userId = currentUserId,
                purifiedPrice = purifiedPrice,
                alkalinePrice = alkalinePrice,
                commissionRate = commissionRate,
                defaultSukli = defaultSukli,
            )
            val msg = if (res.isSuccess) {
                "Settings saved (historical commissions remain unchanged)"
            } else {
                "Settings saved locally (pending sync)"
            }
            _events.tryEmit(MinigeEvent.ShowMessage(msg))
            scheduleAutoBackup()
        }
    }

    fun scheduleCommissionRate(ratePerGallon: Double, effectiveDate: LocalDate) {
        val updatedHistory = CommissionRateHistory.addOrUpdateEntry(
            history = _commissionRateHistory.value,
            effectiveDate = effectiveDate,
            ratePerGallon = ratePerGallon,
        )
        _commissionRateHistory.value = updatedHistory
        appContext?.let { ctx ->
            CommissionRateHistory.saveHistory(ctx, currentUserId, updatedHistory)
        }
        val dateKey = MinigeDateUtils.toKey(effectiveDate)
        val formattedRate = CommissionRateHistory.formatRate(ratePerGallon)
        viewModelScope.launch {
            if (!effectiveDate.isAfter(LocalDate.now())) {
                val currentSettings = uiState.value.settings
                repository.updateSettings(
                    userId = currentUserId,
                    purifiedPrice = currentSettings.purifiedPrice,
                    alkalinePrice = currentSettings.alkalinePrice,
                    commissionRate = kotlin.math.round(ratePerGallon).toInt().coerceAtLeast(0),
                    defaultSukli = currentSettings.defaultSukli,
                )
            }
            _events.tryEmit(
                MinigeEvent.ShowMessage(
                    "Commission rate ₱$formattedRate/gal effective $dateKey (past commissions preserved)"
                )
            )
            scheduleAutoBackup()
        }
    }

    fun scheduleAutoBackup() {
        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch(Dispatchers.IO) {
            delay(5_000L) // 5 seconds debounce
            performDriveBackup(isSilent = true)
        }
    }

    fun performDriveBackup(isSilent: Boolean) {
        val context = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isDriveBackingUp.value = true
            try {
                var token = cachedDriveToken ?: DriveBackupManager.getCachedToken(context, currentUserId)
                if (token.isNullOrBlank()) {
                    when (val auth = GoogleAuthManager.checkOrRequestDriveAuthorization(context)) {
                        is DriveAuthResult.Authorized -> {
                            token = auth.accessToken
                            cachedDriveToken = auth.accessToken
                            DriveBackupManager.saveCachedToken(context, currentUserId, auth.accessToken)
                        }
                        is DriveAuthResult.NeedsConsent -> {
                            _driveBackupStatus.value = DriveBackupStatus.NEEDS_PERMISSION
                            _isDriveBackingUp.value = false
                            if (!isSilent) {
                                _events.tryEmit(MinigeEvent.LaunchDriveConsent(auth.intentSender))
                            }
                            return@launch
                        }
                        is DriveAuthResult.Failure -> {
                            _driveBackupStatus.value = DriveBackupStatus.ERROR
                            _isDriveBackingUp.value = false
                            if (!isSilent) {
                                _events.tryEmit(MinigeEvent.ShowMessage(auth.message))
                            }
                            return@launch
                        }
                    }
                }

                _driveBackupStatus.value = DriveBackupStatus.BACKING_UP
                val res = repository.backupToDrive(token, currentUserId, context)
                res.onSuccess { ts ->
                    _driveLastBackupTimestamp.value = ts
                    _driveBackupStatus.value = DriveBackupStatus.ACTIVE
                    if (!isSilent) {
                        _events.tryEmit(MinigeEvent.ShowMessage("Backed up to Google Drive (Minige Backup folder) ✓"))
                    }
                }.onFailure { err ->
                    _driveBackupStatus.value = DriveBackupStatus.ERROR
                    val msg = if (FirestoreSyncHelper.isOfflineFirestoreError(err)) {
                        "Drive backup saved offline — will auto-backup when online"
                    } else {
                        "Drive backup failed: ${err.localizedMessage ?: "Unknown error"}"
                    }
                    if (!isSilent) {
                        _events.tryEmit(MinigeEvent.ShowMessage(msg))
                    }
                }
            } catch (e: Exception) {
                _driveBackupStatus.value = DriveBackupStatus.ERROR
                if (!isSilent) {
                    _events.tryEmit(MinigeEvent.ShowMessage("Drive backup error: ${e.localizedMessage ?: "Unknown error"}"))
                }
            } finally {
                _isDriveBackingUp.value = false
            }
        }
    }

    fun triggerDriveConsent(context: android.content.Context) {
        viewModelScope.launch {
            when (val auth = GoogleAuthManager.checkOrRequestDriveAuthorization(context)) {
                is DriveAuthResult.Authorized -> {
                    cachedDriveToken = auth.accessToken
                    DriveBackupManager.saveCachedToken(context, currentUserId, auth.accessToken)
                    _driveBackupStatus.value = DriveBackupStatus.ACTIVE
                    _events.tryEmit(MinigeEvent.ShowMessage("Google Drive access verified ✓"))
                    performDriveBackup(isSilent = false)
                }
                is DriveAuthResult.NeedsConsent -> {
                    _events.tryEmit(MinigeEvent.LaunchDriveConsent(auth.intentSender))
                }
                is DriveAuthResult.Failure -> {
                    _driveBackupStatus.value = DriveBackupStatus.ERROR
                    _events.tryEmit(MinigeEvent.ShowMessage(auth.message))
                }
            }
        }
    }

    fun onDriveConsentGranted(token: String) {
        cachedDriveToken = token
        appContext?.let { DriveBackupManager.saveCachedToken(it, currentUserId, token) }
        _driveBackupStatus.value = DriveBackupStatus.ACTIVE
        _events.tryEmit(MinigeEvent.ShowMessage("Google Drive access granted ✓"))
        performDriveBackup(isSilent = false)
    }

    fun manualBackupNow(context: android.content.Context) {
        performDriveBackup(isSilent = false)
    }

    fun previewDriveRestore(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _isDriveBackingUp.value = true
            try {
                var token = cachedDriveToken ?: DriveBackupManager.getCachedToken(context, currentUserId)
                if (token.isNullOrBlank()) {
                    when (val auth = GoogleAuthManager.checkOrRequestDriveAuthorization(context)) {
                        is DriveAuthResult.Authorized -> {
                            token = auth.accessToken
                            cachedDriveToken = auth.accessToken
                            DriveBackupManager.saveCachedToken(context, currentUserId, auth.accessToken)
                        }
                        is DriveAuthResult.NeedsConsent -> {
                            _isDriveBackingUp.value = false
                            _events.tryEmit(MinigeEvent.LaunchDriveConsent(auth.intentSender))
                            return@launch
                        }
                        is DriveAuthResult.Failure -> {
                            _isDriveBackingUp.value = false
                            _events.tryEmit(MinigeEvent.ShowMessage("Drive auth failed: ${auth.message}"))
                            return@launch
                        }
                    }
                }

                val res = DriveBackupManager.fetchBackupPreview(token)
                res.onSuccess { preview ->
                    _driveBackupPreview.value = preview
                    _showRestorePreviewDialog.value = true
                }.onFailure { err ->
                    _events.tryEmit(MinigeEvent.ShowMessage("Failed to preview backup: ${err.localizedMessage ?: "Unknown error"}"))
                }
            } catch (e: Exception) {
                _events.tryEmit(MinigeEvent.ShowMessage("Preview error: ${e.localizedMessage ?: "Unknown error"}"))
            } finally {
                _isDriveBackingUp.value = false
            }
        }
    }

    fun dismissRestorePreview() {
        _showRestorePreviewDialog.value = false
        _driveBackupPreview.value = null
    }

    fun restoreFromDrive(context: android.content.Context) {
        dismissRestorePreview()
        viewModelScope.launch(Dispatchers.IO) {
            _isDriveBackingUp.value = true
            try {
                var token = cachedDriveToken ?: DriveBackupManager.getCachedToken(context, currentUserId)
                if (token.isNullOrBlank()) {
                    when (val auth = GoogleAuthManager.checkOrRequestDriveAuthorization(context)) {
                        is DriveAuthResult.Authorized -> {
                            token = auth.accessToken
                            cachedDriveToken = auth.accessToken
                            DriveBackupManager.saveCachedToken(context, currentUserId, auth.accessToken)
                        }
                        is DriveAuthResult.NeedsConsent -> {
                            _isDriveBackingUp.value = false
                            _events.tryEmit(MinigeEvent.LaunchDriveConsent(auth.intentSender))
                            return@launch
                        }
                        is DriveAuthResult.Failure -> {
                            _isDriveBackingUp.value = false
                            _events.tryEmit(MinigeEvent.ShowMessage("Drive auth failed: ${auth.message}"))
                            return@launch
                        }
                    }
                }

                val res = repository.restoreFromDrive(token, currentUserId, context)
                res.onSuccess { summary ->
                    _driveLastBackupTimestamp.value = summary.backupTimestamp
                    _driveBackupStatus.value = DriveBackupStatus.ACTIVE
                    summary.restoredQuota?.let { q -> _dailyQuotaTarget.value = q }
                    summary.restoredSundayMakeup?.let { sm -> _sundayMakeupEnabled.value = sm }
                    _commissionRateHistory.value = CommissionRateHistory.loadHistory(context, currentUserId)
                    _events.tryEmit(
                        MinigeEvent.ShowMessage(
                            "Restored ${summary.deliveryCount} deliveries, ${summary.paymentCount} payments, preferences, at customer directory mula sa 'Minige Backup' folder ✓"
                        )
                    )
                    scheduleAutoBackup()
                }.onFailure { err ->
                    _events.tryEmit(MinigeEvent.ShowMessage("Restore failed: ${err.localizedMessage ?: "Unknown error"}"))
                }
            } catch (e: Exception) {
                _events.tryEmit(MinigeEvent.ShowMessage("Restore error: ${e.localizedMessage ?: "Unknown error"}"))
            } finally {
                _isDriveBackingUp.value = false
            }
        }
    }

    fun syncNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            repository.forceCloudSync(
                userId = currentUserId,
                onSyncStart = { _isSyncing.value = true },
                onSyncSuccess = { count ->
                    _isSyncing.value = false
                    _statusMessage.value = null
                    _events.tryEmit(
                        MinigeEvent.ShowMessage(FirestoreSyncHelper.formatSyncSuccessMessage(count))
                    )
                },
                onSyncFailure = { message ->
                    _isSyncing.value = false
                    _statusMessage.value = message
                    _events.tryEmit(MinigeEvent.ShowMessage(message))
                },
            )
        }
    }

    fun triggerCalendarSync(
        context: android.content.Context,
        targetDate: LocalDate? = null,
    ) {
        pendingCalendarSyncDate = targetDate
        viewModelScope.launch {
            _isCalendarSyncing.value = true
            when (val auth = CalendarSyncManager.requestCalendarAuthorization(context)) {
                is CalendarAuthResult.Authorized -> {
                    syncDayWithCalendarToken(auth.accessToken, targetDate)
                }
                is CalendarAuthResult.NeedsConsent -> {
                    _isCalendarSyncing.value = false
                    _events.tryEmit(MinigeEvent.LaunchCalendarConsent(auth.intentSender))
                }
                is CalendarAuthResult.Failure -> {
                    pendingCalendarSyncDate = null
                    _isCalendarSyncing.value = false
                    _events.tryEmit(MinigeEvent.ShowMessage(auth.message))
                }
            }
        }
    }

    fun syncDayWithCalendarToken(
        accessToken: String,
        overrideDate: LocalDate? = null,
    ) {
        val snapshot = uiState.value
        val effectiveDate = overrideDate ?: pendingCalendarSyncDate ?: snapshot.selectedDate
        pendingCalendarSyncDate = null
        val targetDateKey = MinigeDateUtils.toKey(effectiveDate)
        val bundle = latestBundle
        val targetDeliveries: List<DeliveryRecord>
        val targetSummary: DailySummary

        if (bundle != null && targetDateKey != snapshot.selectedDateKey) {
            targetDeliveries = bundle.deliveries.filter { it.dateKey == targetDateKey }
            val targetSukli = bundle.ledgers
                .firstOrNull { it.dateKey == targetDateKey }
                ?.startingSukli
                ?: bundle.settings.defaultSukli
            targetSummary = MinigeCalculator.calculateDailySummary(
                dateKey = targetDateKey,
                deliveries = bundle.deliveries,
                utangPayments = bundle.payments,
                expenses = bundle.expenses,
                startingSukli = targetSukli,
            )
        } else {
            targetDeliveries = snapshot.deliveriesForDay
            targetSummary = snapshot.dailySummary
        }

        viewModelScope.launch {
            _isCalendarSyncing.value = true
            val res = CalendarSyncManager.syncDailyDeliveriesToCalendar(
                accessToken = accessToken,
                dateKey = targetDateKey,
                deliveries = targetDeliveries,
                summary = targetSummary,
            )
            res.onSuccess { eventId ->
                repository.linkCalendarEventToDeliveries(
                    userId = currentUserId,
                    deliveryIds = targetDeliveries.map { it.id },
                    calendarEventId = eventId,
                )
                _events.tryEmit(
                    MinigeEvent.ShowMessage("Synced $targetDateKey to Google Calendar")
                )
            }.onFailure { err ->
                _events.tryEmit(
                    MinigeEvent.ShowMessage("Calendar sync failed: ${err.localizedMessage ?: "Error"}")
                )
            }
            _isCalendarSyncing.value = false
        }
    }

    private data class LocalBundle(
        val deliveries: List<DeliveryRecord>,
        val payments: List<UtangPaymentRecord>,
        val expenses: List<ExpenseRecord>,
        val ledgers: List<com.melvin.minige.domain.DailyLedgerRecord>,
        val settings: MinigeSettings,
        val customers: List<CustomerRecord>,
        val pendingCount: Int,
    )

    companion object {
        private const val TAG = "MinigeVM"

        fun factory(currentUserId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(databaseId).also {
                    FirestoreSyncHelper.configurePersistentCache(it)
                }
                val roomDb = MinigeDatabase.getInstance(app)
                val dao = roomDb.minigeDao()
                val remote = MinigeFirestoreDataSource(firestore)
                val pricingRepo = PricingRepository(dao = dao, remote = remote)
                val repo = MinigeRepository(
                    dao = dao,
                    remote = remote,
                    pricingRepository = pricingRepo,
                )
                MinigeViewModel(
                    repository = repo,
                    pricingRepository = pricingRepo,
                    currentUserId = currentUserId,
                    appContext = app.applicationContext,
                )
            }
        }
    }
}
