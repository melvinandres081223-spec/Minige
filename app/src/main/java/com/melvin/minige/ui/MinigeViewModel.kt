package com.melvin.minige.ui

import android.content.Intent
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
import com.melvin.minige.domain.InactiveSukiAlert
import com.melvin.minige.domain.InactiveSukiAlertHelper
import com.melvin.minige.domain.MinigeCalculator
import com.melvin.minige.domain.MinigeCustomerUtils
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
import com.melvin.minige.sync.DriveConsentRequiredException
import com.melvin.minige.ui.components.DailyQuotaPrefs
import com.melvin.minige.ui.components.DeliveryFilterChip
import com.melvin.minige.ui.components.DeliveryFilterHelper
import com.melvin.minige.ui.components.PickupChecklistHelper
import com.melvin.minige.ui.components.SukiAnalyticsHelper
import com.melvin.minige.ui.components.SundayMakeupHelper
import com.melvin.minige.ui.components.WeeklySukiAnalytics
import com.melvin.minige.util.AppDiagnosticReport
import com.melvin.minige.util.AppDiagnosticsRunner
import com.melvin.minige.util.BossReceivableEntry
import com.melvin.minige.util.BossReceivablesPrefs
import com.melvin.minige.util.BulkUtangSettlementSummary
import com.melvin.minige.util.CommissionRateEntry
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.CustomerDirectoryHelper
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PaymentBreakdownHelper
import com.melvin.minige.util.PaymentChannelBreakdown
import com.melvin.minige.util.SmallGallonHelper
import com.melvin.minige.util.SmallGallonPrefs
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
    val todaySearchQuery: String = "",
    val todayFilterChip: DeliveryFilterChip = DeliveryFilterChip.ALL,
    val filteredDeliveriesForDay: List<DeliveryRecord> = emptyList(),
    val pickupsForDay: List<DeliveryRecord> = emptyList(),
    val completedDeliveriesForDay: List<DeliveryRecord> = emptyList(),
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
    val bossHakotPaidGallons: Int = 0,
    val bossHakotPautangGallons: Int = 0,
    val bossHakotPautangPaidGallons: Int = 0,
    val bossHakotMayCommissionGallons: Int = 0,
    val customers: List<CustomerRecord> = emptyList(),
    val sukiDirectory: List<SukiCustomerProfile> = emptyList(),
    val commissionRateHistory: List<CommissionRateEntry> = emptyList(),
    val activeCommissionRate: Double = CommissionRateHistory.DEFAULT_RATE,
    val smallGallonPrice: Double = SmallGallonHelper.DEFAULT_PRICE,
    val smallGallonCommissionRate: Double = SmallGallonHelper.DEFAULT_COMMISSION_RATE,
    val dailyQuotaTarget: Int = DailyQuotaPrefs.DEFAULT_QUOTA_GALLONS,
    val weeklySukiAnalytics: WeeklySukiAnalytics = WeeklySukiAnalytics(),
    val pendingSyncCount: Int = 0,
    val isSyncing: Boolean = false,
    val isRefreshingToday: Boolean = false,
    val isCalendarSyncing: Boolean = false,
    val statusMessage: String? = null,
    val driveBackupStatus: DriveBackupStatus = DriveBackupStatus.NEEDS_PERMISSION,
    val driveLastBackupTimestamp: Long = 0L,
    val isDriveBackingUp: Boolean = false,
    val driveBackupSummary: com.melvin.minige.sync.BackupMetadataSummary? = null,
    val driveBackupPreview: DriveBackupPreview? = null,
    val showRestorePreviewDialog: Boolean = false,
    val autoBackupBeforeUpdate: Boolean = true,
    val lastLocalBackupPath: String = DriveBackupManager.LOCAL_BACKUP_DISPLAY_PATH,
    val bulkSettlementConfirmation: BulkUtangSettlementSummary? = null,
    val inactiveSukiAlerts: List<InactiveSukiAlert> = emptyList(),
    val inactiveAlertsByCustomerId: Map<String, InactiveSukiAlert> = emptyMap(),
    val diagnosticReport: AppDiagnosticReport? = null,
    val isRunningDiagnostics: Boolean = false,
    val bossReceivables: List<BossReceivableEntry> = emptyList(),
) {
    val inactiveSukiCount: Int
        get() = inactiveSukiAlerts.size

    val unpaidBossReceivableTotal: Int
        get() = BossReceivablesPrefs.totalUnpaidBalance(bossReceivables)
}

sealed interface MinigeEvent {
    data class ShowMessage(val message: String) : MinigeEvent
    data class LaunchCalendarConsent(val intentSender: IntentSender) : MinigeEvent
    data class LaunchDriveConsent(val intentSender: IntentSender) : MinigeEvent
    data class LaunchDriveRecoveryConsent(val intent: Intent) : MinigeEvent
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
    private val _smallGallonPrice = MutableStateFlow(
        appContext?.let { SmallGallonPrefs.loadPrice(it, currentUserId) }
            ?: SmallGallonHelper.DEFAULT_PRICE
    )
    private val _smallGallonCommissionRate = MutableStateFlow(
        appContext?.let { SmallGallonPrefs.loadCommissionRate(it, currentUserId) }
            ?: SmallGallonHelper.DEFAULT_COMMISSION_RATE
    )
    private val _isSyncing = MutableStateFlow(false)
    private val _isRefreshingToday = MutableStateFlow(false)
    private val _refreshNonce = MutableStateFlow(0L)
    private val _todaySearchQuery = MutableStateFlow("")
    private val _todayFilterChip = MutableStateFlow(DeliveryFilterChip.ALL)
    private val _isCalendarSyncing = MutableStateFlow(false)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _driveBackupStatus = MutableStateFlow(
        appContext?.let {
            if (GoogleAuthManager.hasDrivePermission(it) ||
                DriveBackupManager.getLastSuccessfulDriveAuthEpochMs(it, currentUserId) > 0L
            ) {
                DriveBackupStatus.ACTIVE
            } else {
                DriveBackupStatus.NEEDS_PERMISSION
            }
        } ?: DriveBackupStatus.NEEDS_PERMISSION
    )
    private val _driveLastBackupTimestamp = MutableStateFlow(
        appContext?.let { DriveBackupManager.getLastBackupTimestamp(it, currentUserId) } ?: 0L
    )
    private val _isDriveBackingUp = MutableStateFlow(false)
    private val _driveBackupPreview = MutableStateFlow<DriveBackupPreview?>(null)
    private val _showRestorePreviewDialog = MutableStateFlow(false)
    private val _autoBackupBeforeUpdate = MutableStateFlow(
        appContext?.let { DriveBackupManager.isAutoBackupBeforeUpdateEnabled(it, currentUserId) } ?: true
    )
    private val _lastLocalBackupPath = MutableStateFlow(
        appContext?.let { DriveBackupManager.getLastLocalBackupPath(it, currentUserId) }
            ?: DriveBackupManager.LOCAL_BACKUP_DISPLAY_PATH
    )
    private val _bulkSettlementConfirmation = MutableStateFlow<BulkUtangSettlementSummary?>(null)
    private val _diagnosticReport = MutableStateFlow<AppDiagnosticReport?>(null)
    private val _isRunningDiagnostics = MutableStateFlow(false)
    private val _bossReceivables = MutableStateFlow(
        appContext?.let { BossReceivablesPrefs.loadEntries(it, currentUserId) } ?: emptyList()
    )

    private enum class PendingDriveOperation {
        BACKUP,
        PREVIEW_RESTORE,
        RESTORE,
    }

    private var autoBackupJob: Job? = null
    private var pendingDriveOperation: PendingDriveOperation? = null

    private val _undoableAction = MutableStateFlow<UndoableAction?>(null)
    val undoableAction: StateFlow<UndoableAction?> = _undoableAction.asStateFlow()
    private var undoDismissJob: Job? = null
    @Volatile
    private var latestBundle: LocalBundle? = null
    private var pendingCalendarSyncDate: LocalDate? = null

    private val _events = MutableSharedFlow<MinigeEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MinigeEvent> = _events.asSharedFlow()

    init {
        appContext?.let { ctx ->
            viewModelScope.launch(Dispatchers.IO) {
                val authorized = GoogleAuthManager.refreshDriveAuthorizationSilently(ctx)
                if (authorized) {
                    DriveBackupManager.saveLastSuccessfulDriveAuthEpochMs(ctx, currentUserId, System.currentTimeMillis())
                    _driveBackupStatus.value = DriveBackupStatus.ACTIVE
                } else if (!GoogleAuthManager.hasDrivePermission(ctx) &&
                    DriveBackupManager.getLastSuccessfulDriveAuthEpochMs(ctx, currentUserId) <= 0L
                ) {
                    _driveBackupStatus.value = DriveBackupStatus.NEEDS_PERMISSION
                }
            }
        }
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
        val autoBackupBeforeUpdate: Boolean,
        val lastLocalBackupPath: String,
        val smallGallonPrice: Double,
        val smallGallonCommissionRate: Double,
    )

    private data class DriveBackupSnapshot(
        val status: DriveBackupStatus,
        val lastTimestamp: Long,
        val isBackingUp: Boolean,
        val preview: DriveBackupPreview?,
        val showPreview: Boolean,
    )

    val uiState: StateFlow<MinigeUiState> = combine(
        combine(
            _selectedDate,
            _sundayMakeupEnabled,
            _commissionRateHistory,
            _dailyQuotaTarget,
            combine(
                _autoBackupBeforeUpdate,
                _lastLocalBackupPath,
                _smallGallonPrice,
                _smallGallonCommissionRate,
            ) { autoBackup, localPath, smallPrice, smallRate ->
                Pair(autoBackup to localPath, smallPrice to smallRate)
            },
        ) { date, sundayEnabled, rateHist, quotaTarget, backupAndSmall ->
            ViewPrefsSnapshot(
                date = date,
                sundayEnabled = sundayEnabled,
                rateHistory = rateHist,
                quotaTarget = quotaTarget,
                autoBackupBeforeUpdate = backupAndSmall.first.first,
                lastLocalBackupPath = backupAndSmall.first.second,
                smallGallonPrice = backupAndSmall.second.first,
                smallGallonCommissionRate = backupAndSmall.second.second,
            )
        },
        localDataFlow,
        combine(_driveBackupStatus, _driveLastBackupTimestamp, _isDriveBackingUp, _driveBackupPreview, _showRestorePreviewDialog) { status, lastTs, backingUp, preview, showPreview ->
            DriveBackupSnapshot(status, lastTs, backingUp, preview, showPreview)
        },
        combine(
            _isSyncing,
            _bossReceivables,
            _todaySearchQuery,
            _todayFilterChip,
            combine(_isRefreshingToday, _refreshNonce) { refreshing, nonce -> refreshing to nonce },
        ) { syncing, receivables, searchQuery, filterChip, refreshPair ->
            SyncFilterSnapshot(
                syncing = syncing,
                bossReceivables = receivables,
                todaySearchQuery = searchQuery,
                todayFilterChip = filterChip,
                isRefreshingToday = refreshPair.first,
                refreshNonce = refreshPair.second,
            )
        },
        combine(
            _isCalendarSyncing,
            _statusMessage,
            _bulkSettlementConfirmation,
            _diagnosticReport,
            _isRunningDiagnostics,
        ) { calSyncing, status, bulkSummary, diagReport, runningDiag ->
            UiExtrasSnapshot(calSyncing, status, bulkSummary, diagReport, runningDiag)
        },
    ) { prefs, bundle, driveSnap, syncAndFilters, extras ->
        val syncing = syncAndFilters.syncing
        val bossReceivables = syncAndFilters.bossReceivables
        val todaySearchQuery = syncAndFilters.todaySearchQuery
        val todayFilterChip = syncAndFilters.todayFilterChip
        val isRefreshingToday = syncAndFilters.isRefreshingToday
        val calSyncing = extras.calSyncing
        val status = extras.status
        val bulkSummary = extras.bulkSummary
        val date = prefs.date
        val sundayEnabled = prefs.sundayEnabled
        val rateHist = prefs.rateHistory
        val quotaTarget = prefs.quotaTarget
        latestBundle = bundle
        val dateKey = MinigeDateUtils.toKey(date)
        val deliveriesForDay = bundle.deliveries.filter { it.dateKey == dateKey }
        val filteredDeliveriesForDay = DeliveryFilterHelper.filterDeliveries(
            deliveries = deliveriesForDay,
            searchQuery = todaySearchQuery,
            selectedChip = todayFilterChip,
        )
        val pickupsForDay = PickupChecklistHelper.filterPendingPickupsForDay(
            deliveriesForDay = deliveriesForDay,
            searchQuery = todaySearchQuery,
            selectedChip = todayFilterChip,
        )
        val completedDeliveriesForDay = filteredDeliveriesForDay.filter { it.paymentType != PaymentType.PICK_UP }
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

        val hakotReport = BossPickupFormatter.buildReport(
            date = date,
            deliveries = bundle.deliveries,
            utangPayments = bundle.payments,
        )
        val inactiveSukiAlerts = InactiveSukiAlertHelper.findInactiveCustomers(
            customers = mergedCustomers,
            deliveries = bundle.deliveries,
            referenceDate = date,
        )
        val inactiveAlertsByCustomerId = inactiveSukiAlerts.associateBy { it.customerId }

        MinigeUiState(
            selectedDate = date,
            selectedDateKey = dateKey,
            settings = bundle.settings,
            deliveriesForDay = deliveriesForDay,
            todaySearchQuery = todaySearchQuery,
            todayFilterChip = todayFilterChip,
            filteredDeliveriesForDay = filteredDeliveriesForDay,
            pickupsForDay = pickupsForDay,
            completedDeliveriesForDay = completedDeliveriesForDay,
            expensesForDay = expensesForDay,
            activeUtangList = activeUtang,
            settledUtangList = settledUtang,
            utangPayments = bundle.payments,
            dailySummary = dailySummary,
            weeklySummary = weeklySummary,
            todayPaymentBreakdown = todayPaymentBreakdown,
            sundayMakeupEnabled = sundayEnabled,
            sundayMakeupDaySummary = sundayMakeupDaySummary,
            bossPickupMessage = hakotReport.message,
            bossHakotPaidGallons = hakotReport.paidGallons,
            bossHakotPautangGallons = hakotReport.unpaidPautangGallons,
            bossHakotPautangPaidGallons = hakotReport.pautangPaidGallons,
            bossHakotMayCommissionGallons = hakotReport.mayCommissionGallons,
            customers = mergedCustomers,
            sukiDirectory = sukiDirectory,
            commissionRateHistory = rateHist,
            activeCommissionRate = activeRate,
            smallGallonPrice = prefs.smallGallonPrice,
            smallGallonCommissionRate = prefs.smallGallonCommissionRate,
            dailyQuotaTarget = quotaTarget,
            weeklySukiAnalytics = weeklySukiAnalytics,
            pendingSyncCount = pendingCount,
            isSyncing = syncing,
            isRefreshingToday = isRefreshingToday,
            isCalendarSyncing = calSyncing,
            statusMessage = status,
            driveBackupStatus = driveSnap.status,
            driveLastBackupTimestamp = driveSnap.lastTimestamp,
            isDriveBackingUp = driveSnap.isBackingUp,
            driveBackupSummary = driveMetaSummary,
            driveBackupPreview = driveSnap.preview,
            showRestorePreviewDialog = driveSnap.showPreview,
            autoBackupBeforeUpdate = prefs.autoBackupBeforeUpdate,
            lastLocalBackupPath = prefs.lastLocalBackupPath,
            bulkSettlementConfirmation = bulkSummary,
            inactiveSukiAlerts = inactiveSukiAlerts,
            inactiveAlertsByCustomerId = inactiveAlertsByCustomerId,
            diagnosticReport = extras.diagnosticReport,
            isRunningDiagnostics = extras.isRunningDiagnostics,
            bossReceivables = bossReceivables,
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

    fun setTodaySearchQuery(query: String) {
        _todaySearchQuery.value = query
    }

    fun setTodayFilterChip(chip: DeliveryFilterChip) {
        _todayFilterChip.value = chip
    }

    fun refreshTodayData() {
        if (_isRefreshingToday.value) return
        val dateKey = MinigeDateUtils.toKey(_selectedDate.value)
        viewModelScope.launch {
            _isRefreshingToday.value = true
            try {
                _refreshNonce.value = _refreshNonce.value + 1L
                val result = repository.refreshTodayData(currentUserId, dateKey)
                _refreshNonce.value = _refreshNonce.value + 1L
                if (result.isFailure) {
                    _events.tryEmit(MinigeEvent.ShowMessage("Showing saved offline entries."))
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isRefreshingToday.value = false
            }
        }
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

    fun updateSmallGallonSettings(price: Double, commissionRate: Double) {
        val savedPrice = appContext?.let { SmallGallonPrefs.savePrice(it, currentUserId, price) }
            ?: price.coerceAtLeast(0.0)
        val savedRate = appContext?.let { SmallGallonPrefs.saveCommissionRate(it, currentUserId, commissionRate) }
            ?: commissionRate.coerceAtLeast(0.0)
        _smallGallonPrice.value = savedPrice
        _smallGallonCommissionRate.value = savedRate
        _events.tryEmit(
            MinigeEvent.ShowMessage(
                "Maliit na galon updated: ₱${MinigeCustomerUtils.formatPrice(savedPrice)}/gal • Comm ₱${CommissionRateHistory.formatRate(savedRate)}/gal"
            )
        )
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
        hasCommission: Boolean = true,
    ) {
        val dateKey = MinigeDateUtils.toKey(_selectedDate.value)
        val snapshot = uiState.value
        val activeRate = if (SmallGallonHelper.isMaliitTagged(notes)) {
            snapshot.smallGallonCommissionRate
        } else {
            snapshot.activeCommissionRate
        }
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
                hasCommission = hasCommission,
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
        hasCommission: Boolean = true,
        customPurifiedUnitPrice: Double? = null,
        customAlkalineUnitPrice: Double? = null,
    ) {
        val snapshot = uiState.value
        val rateForOrder = if (SmallGallonHelper.isMaliitTagged(notes)) {
            snapshot.smallGallonCommissionRate
        } else {
            snapshot.activeCommissionRate
        }
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
                hasCommission = hasCommission,
                overrideCommissionRate = rateForOrder,
                customPurifiedUnitPrice = customPurifiedUnitPrice,
                customAlkalineUnitPrice = customAlkalineUnitPrice,
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
        val snapshot = uiState.value
        val existingNotes = latestBundle?.deliveries?.firstOrNull { it.id == deliveryId }?.notes.orEmpty()
        val activeRate = if (SmallGallonHelper.isMaliitTagged(existingNotes)) {
            snapshot.smallGallonCommissionRate
        } else {
            snapshot.activeCommissionRate
        }
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
        val snapshot = uiState.value
        val existingNotes = latestBundle?.deliveries?.firstOrNull { it.id == deliveryId }?.notes.orEmpty()
        val activeRate = if (SmallGallonHelper.isMaliitTagged(existingNotes)) {
            snapshot.smallGallonCommissionRate
        } else {
            snapshot.activeCommissionRate
        }
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

    fun dismissBulkSettlementConfirmation() {
        _bulkSettlementConfirmation.value = null
    }

    fun onRecordBulkUtangPayment(
        customerName: String,
        amount: Int,
        method: UtangMethod,
        notes: String,
    ) {
        val paymentDateKey = MinigeDateUtils.toKey(_selectedDate.value)
        val activeRate = uiState.value.activeCommissionRate
        viewModelScope.launch {
            val result = repository.recordBulkUtangPayment(
                userId = currentUserId,
                customerName = customerName,
                amount = amount,
                method = method,
                paymentDateKey = paymentDateKey,
                notes = notes,
                overrideCommissionRate = activeRate,
            )
            result.onSuccess { summary ->
                _bulkSettlementConfirmation.value = summary
                val settledDatesText = summary.settledDateKeys.distinct().joinToString(", ")
                val msg = when {
                    summary.settledDateKeys.isNotEmpty() && summary.partiallyPaidCount > 0 ->
                        "Lump-sum ₱${summary.totalAppliedAmount} (${summary.customerName}): Settled $settledDatesText + partial on newer balance"
                    summary.settledDateKeys.isNotEmpty() ->
                        "Lump-sum ₱${summary.totalAppliedAmount} (${summary.customerName}): Settled $settledDatesText ✓"
                    else ->
                        "Lump-sum ₱${summary.totalAppliedAmount} applied to oldest balance (₱${summary.remainingCustomerBalance} left)"
                }
                _events.tryEmit(MinigeEvent.ShowMessage(msg))
                scheduleAutoBackup()
            }.onFailure { err ->
                _events.tryEmit(
                    MinigeEvent.ShowMessage(err.localizedMessage ?: "Failed to record lump-sum payment.")
                )
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

    fun addBossReceivable(
        periodLabel: String,
        amount: Int,
        gallons: Int?,
        ratePerGallon: Double?,
        note: String,
    ) {
        val updated = BossReceivablesPrefs.addEntry(
            entries = _bossReceivables.value,
            periodLabel = periodLabel,
            amount = amount,
            gallons = gallons,
            ratePerGallon = ratePerGallon,
            note = note,
        )
        _bossReceivables.value = updated
        appContext?.let { BossReceivablesPrefs.saveEntries(it, currentUserId, updated) }
        _events.tryEmit(MinigeEvent.ShowMessage("Added unpaid sahod entry: ₱$amount ($periodLabel)"))
        scheduleAutoBackup()
    }

    fun markBossReceivablePaid(entryId: String) {
        val target = _bossReceivables.value.firstOrNull { it.id == entryId } ?: return
        val updated = BossReceivablesPrefs.markPaid(
            entries = _bossReceivables.value,
            entryId = entryId,
        )
        _bossReceivables.value = updated
        appContext?.let { BossReceivablesPrefs.saveEntries(it, currentUserId, updated) }
        _events.tryEmit(MinigeEvent.ShowMessage("Marked Nakuha Na: ₱${target.amount} (${target.periodLabel}) ✓"))
        scheduleAutoBackup()
    }

    fun deleteBossReceivable(entryId: String) {
        val updated = BossReceivablesPrefs.deleteEntry(
            entries = _bossReceivables.value,
            entryId = entryId,
        )
        _bossReceivables.value = updated
        appContext?.let { BossReceivablesPrefs.saveEntries(it, currentUserId, updated) }
        _events.tryEmit(MinigeEvent.ShowMessage("Removed unpaid sahod entry"))
        scheduleAutoBackup()
    }

    fun scheduleAutoBackup() {
        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch(Dispatchers.IO) {
            delay(5_000L) // 5 seconds debounce
            performDriveBackup(isSilent = true)
        }
    }

    private fun emitDriveConsentEventIfNeeded(
        consentError: DriveConsentRequiredException,
        pendingOperation: PendingDriveOperation,
        showPrompt: Boolean,
    ) {
        _driveBackupStatus.value = DriveBackupStatus.NEEDS_PERMISSION
        if (!showPrompt) return
        when {
            consentError.intentSender != null -> {
                pendingDriveOperation = pendingOperation
                _events.tryEmit(MinigeEvent.LaunchDriveConsent(consentError.intentSender))
            }
            consentError.recoveryIntent != null -> {
                pendingDriveOperation = pendingOperation
                _events.tryEmit(MinigeEvent.LaunchDriveRecoveryConsent(consentError.recoveryIntent))
            }
            else -> {
                _events.tryEmit(
                    MinigeEvent.ShowMessage(
                        consentError.localizedMessage ?: "Google Drive permission kailangan i-verify ulit."
                    )
                )
            }
        }
    }

    fun performDriveBackup(
        isSilent: Boolean,
        overrideAccessToken: String? = null,
    ) {
        val context = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isDriveBackingUp.value = true
            try {
                // Always obtain a fresh OAuth access token right before executing Drive REST calls
                // unless a non-blank token was just granted directly from the consent flow.
                val freshToken = if (!overrideAccessToken.isNullOrBlank()) {
                    overrideAccessToken
                } else {
                    when (val auth = GoogleAuthManager.fetchFreshDriveAccessToken(context)) {
                        is DriveAuthResult.Authorized -> {
                            DriveBackupManager.saveCachedToken(context, currentUserId, auth.accessToken)
                            auth.accessToken
                        }
                        is DriveAuthResult.NeedsConsent -> {
                            _driveBackupStatus.value = DriveBackupStatus.NEEDS_PERMISSION
                            if (!isSilent) {
                                pendingDriveOperation = PendingDriveOperation.BACKUP
                                _events.tryEmit(MinigeEvent.LaunchDriveConsent(auth.intentSender))
                            }
                            return@launch
                        }
                        is DriveAuthResult.NeedsConsentIntent -> {
                            _driveBackupStatus.value = DriveBackupStatus.NEEDS_PERMISSION
                            if (!isSilent) {
                                pendingDriveOperation = PendingDriveOperation.BACKUP
                                _events.tryEmit(MinigeEvent.LaunchDriveRecoveryConsent(auth.intent))
                            }
                            return@launch
                        }
                        is DriveAuthResult.Failure -> {
                            _driveBackupStatus.value = DriveBackupStatus.ERROR
                            if (!isSilent) {
                                _events.tryEmit(MinigeEvent.ShowMessage(auth.message))
                            }
                            return@launch
                        }
                    }
                }

                _driveBackupStatus.value = DriveBackupStatus.BACKING_UP
                val res = repository.backupToDrive(freshToken, currentUserId, context)
                res.onSuccess { ts ->
                    pendingDriveOperation = null
                    _driveLastBackupTimestamp.value = ts
                    _driveBackupStatus.value = DriveBackupStatus.ACTIVE
                    if (!isSilent) {
                        _events.tryEmit(MinigeEvent.ShowMessage("Backed up to Google Drive (Minige Backup folder) ✓"))
                    }
                }.onFailure { err ->
                    if (err is DriveConsentRequiredException) {
                        emitDriveConsentEventIfNeeded(
                            consentError = err,
                            pendingOperation = PendingDriveOperation.BACKUP,
                            showPrompt = !isSilent,
                        )
                    } else {
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
                }
            } catch (e: CancellationException) {
                throw e
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
            when (val auth = GoogleAuthManager.fetchFreshDriveAccessToken(context)) {
                is DriveAuthResult.Authorized -> {
                    DriveBackupManager.saveCachedToken(context, currentUserId, auth.accessToken)
                    _driveBackupStatus.value = DriveBackupStatus.ACTIVE
                    _events.tryEmit(MinigeEvent.ShowMessage("Google Drive access verified ✓"))
                    performDriveBackup(isSilent = false, overrideAccessToken = auth.accessToken)
                }
                is DriveAuthResult.NeedsConsent -> {
                    pendingDriveOperation = PendingDriveOperation.BACKUP
                    _events.tryEmit(MinigeEvent.LaunchDriveConsent(auth.intentSender))
                }
                is DriveAuthResult.NeedsConsentIntent -> {
                    pendingDriveOperation = PendingDriveOperation.BACKUP
                    _events.tryEmit(MinigeEvent.LaunchDriveRecoveryConsent(auth.intent))
                }
                is DriveAuthResult.Failure -> {
                    _driveBackupStatus.value = DriveBackupStatus.ERROR
                    _events.tryEmit(MinigeEvent.ShowMessage(auth.message))
                }
            }
        }
    }

    fun onDriveConsentGranted(token: String? = null) {
        val cleanToken = token?.takeIf { it.isNotBlank() }
        appContext?.let { ctx ->
            if (cleanToken != null) {
                DriveBackupManager.saveCachedToken(ctx, currentUserId, cleanToken)
            } else {
                DriveBackupManager.clearCachedToken(ctx, currentUserId)
            }
        }
        _driveBackupStatus.value = DriveBackupStatus.ACTIVE
        _events.tryEmit(MinigeEvent.ShowMessage("Google Drive access granted ✓"))

        val nextOp = pendingDriveOperation ?: PendingDriveOperation.BACKUP
        pendingDriveOperation = null
        val context = appContext
        when (nextOp) {
            PendingDriveOperation.BACKUP -> performDriveBackup(
                isSilent = false,
                overrideAccessToken = cleanToken,
            )
            PendingDriveOperation.PREVIEW_RESTORE -> if (context != null) {
                previewDriveRestore(context = context, overrideAccessToken = cleanToken)
            }
            PendingDriveOperation.RESTORE -> if (context != null) {
                restoreFromDrive(context = context, overrideAccessToken = cleanToken)
            }
        }
    }

    fun setAutoBackupBeforeUpdate(enabled: Boolean) {
        _autoBackupBeforeUpdate.value = enabled
        appContext?.let { ctx ->
            DriveBackupManager.setAutoBackupBeforeUpdateEnabled(ctx, currentUserId, enabled)
        }
        _events.tryEmit(
            MinigeEvent.ShowMessage(
                if (enabled) "Auto-backup before app updates enabled"
                else "Auto-backup before app updates disabled"
            )
        )
    }

    suspend fun backupBeforeAppUpdate(context: android.content.Context): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            _isDriveBackingUp.value = true
            try {
                val localRes = repository.exportLocalBackup(currentUserId, context)
                localRes.onSuccess { path ->
                    _lastLocalBackupPath.value = path
                    _driveLastBackupTimestamp.value = DriveBackupManager.getLastBackupTimestamp(context, currentUserId)
                }

                var driveSynced = false
                if (GoogleAuthManager.hasDrivePermission(context)) {
                    val auth = GoogleAuthManager.fetchFreshDriveAccessToken(context)
                    if (auth is DriveAuthResult.Authorized) {
                        val driveRes = repository.backupToDrive(auth.accessToken, currentUserId, context)
                        driveRes.onSuccess { ts ->
                            driveSynced = true
                            _driveLastBackupTimestamp.value = ts
                            _driveBackupStatus.value = DriveBackupStatus.ACTIVE
                        }
                    }
                }

                when {
                    driveSynced && localRes.isSuccess -> "Pre-update backup saved to Drive & Downloads ✓"
                    localRes.isSuccess -> "Pre-update local backup saved to ${DriveBackupManager.LOCAL_BACKUP_DISPLAY_PATH} ✓"
                    else -> "Starting update..."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "Starting update..."
            } finally {
                _isDriveBackingUp.value = false
            }
        }

    fun manualBackupNow(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.exportLocalBackup(currentUserId, context).onSuccess { path ->
                _lastLocalBackupPath.value = path
                _driveLastBackupTimestamp.value = DriveBackupManager.getLastBackupTimestamp(context, currentUserId)
                _events.tryEmit(
                    MinigeEvent.ShowMessage("Saved local backup to $path ✓")
                )
            }
        }
        performDriveBackup(isSilent = false)
    }

    fun previewDriveRestore(
        context: android.content.Context,
        overrideAccessToken: String? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isDriveBackingUp.value = true
            try {
                val freshToken = if (!overrideAccessToken.isNullOrBlank()) {
                    overrideAccessToken
                } else {
                    when (val auth = GoogleAuthManager.fetchFreshDriveAccessToken(context)) {
                        is DriveAuthResult.Authorized -> {
                            DriveBackupManager.saveCachedToken(context, currentUserId, auth.accessToken)
                            auth.accessToken
                        }
                        is DriveAuthResult.NeedsConsent -> {
                            pendingDriveOperation = PendingDriveOperation.PREVIEW_RESTORE
                            _events.tryEmit(MinigeEvent.LaunchDriveConsent(auth.intentSender))
                            return@launch
                        }
                        is DriveAuthResult.NeedsConsentIntent -> {
                            pendingDriveOperation = PendingDriveOperation.PREVIEW_RESTORE
                            _events.tryEmit(MinigeEvent.LaunchDriveRecoveryConsent(auth.intent))
                            return@launch
                        }
                        is DriveAuthResult.Failure -> {
                            _events.tryEmit(MinigeEvent.ShowMessage("Drive auth failed: ${auth.message}"))
                            return@launch
                        }
                    }
                }

                val res = DriveBackupManager.fetchBackupPreview(
                    context = context,
                    userId = currentUserId,
                    accessToken = freshToken,
                )
                res.onSuccess { preview ->
                    pendingDriveOperation = null
                    _driveBackupPreview.value = preview
                    _showRestorePreviewDialog.value = true
                }.onFailure { err ->
                    if (err is DriveConsentRequiredException) {
                        emitDriveConsentEventIfNeeded(
                            consentError = err,
                            pendingOperation = PendingDriveOperation.PREVIEW_RESTORE,
                            showPrompt = true,
                        )
                    } else {
                        _events.tryEmit(MinigeEvent.ShowMessage("Failed to preview backup: ${err.localizedMessage ?: "Unknown error"}"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
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

    fun restoreFromDrive(
        context: android.content.Context,
        overrideAccessToken: String? = null,
    ) {
        dismissRestorePreview()
        viewModelScope.launch(Dispatchers.IO) {
            _isDriveBackingUp.value = true
            try {
                val freshToken = if (!overrideAccessToken.isNullOrBlank()) {
                    overrideAccessToken
                } else {
                    when (val auth = GoogleAuthManager.fetchFreshDriveAccessToken(context)) {
                        is DriveAuthResult.Authorized -> {
                            DriveBackupManager.saveCachedToken(context, currentUserId, auth.accessToken)
                            auth.accessToken
                        }
                        is DriveAuthResult.NeedsConsent -> {
                            pendingDriveOperation = PendingDriveOperation.RESTORE
                            _events.tryEmit(MinigeEvent.LaunchDriveConsent(auth.intentSender))
                            return@launch
                        }
                        is DriveAuthResult.NeedsConsentIntent -> {
                            pendingDriveOperation = PendingDriveOperation.RESTORE
                            _events.tryEmit(MinigeEvent.LaunchDriveRecoveryConsent(auth.intent))
                            return@launch
                        }
                        is DriveAuthResult.Failure -> {
                            _events.tryEmit(MinigeEvent.ShowMessage("Drive auth failed: ${auth.message}"))
                            return@launch
                        }
                    }
                }

                val res = repository.restoreFromDrive(freshToken, currentUserId, context)
                res.onSuccess { summary ->
                    pendingDriveOperation = null
                    _driveLastBackupTimestamp.value = summary.backupTimestamp
                    _driveBackupStatus.value = DriveBackupStatus.ACTIVE
                    summary.restoredQuota?.let { q -> _dailyQuotaTarget.value = q }
                    summary.restoredSundayMakeup?.let { sm -> _sundayMakeupEnabled.value = sm }
                    summary.restoredSmallGallonPrice?.let { sp -> _smallGallonPrice.value = sp }
                    summary.restoredSmallGallonCommissionRate?.let { sc -> _smallGallonCommissionRate.value = sc }
                    _commissionRateHistory.value = CommissionRateHistory.loadHistory(context, currentUserId)
                    _bossReceivables.value = BossReceivablesPrefs.loadEntries(context, currentUserId)
                    _events.tryEmit(
                        MinigeEvent.ShowMessage(
                            "Restored ${summary.deliveryCount} deliveries, ${summary.paymentCount} payments, preferences, at customer directory mula sa 'Minige Backup' folder ✓"
                        )
                    )
                    scheduleAutoBackup()
                }.onFailure { err ->
                    if (err is DriveConsentRequiredException) {
                        emitDriveConsentEventIfNeeded(
                            consentError = err,
                            pendingOperation = PendingDriveOperation.RESTORE,
                            showPrompt = true,
                        )
                    } else {
                        _events.tryEmit(MinigeEvent.ShowMessage("Restore failed: ${err.localizedMessage ?: "Unknown error"}"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
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

    fun runAppDiagnostics(context: android.content.Context) {
        if (_isRunningDiagnostics.value) return
        val snapshot = uiState.value
        val bundle = latestBundle
        viewModelScope.launch(Dispatchers.IO) {
            _isRunningDiagnostics.value = true
            try {
                val silentDriveOk = GoogleAuthManager.refreshDriveAuthorizationSilently(context)
                val effectiveDriveStatus = if (silentDriveOk && snapshot.driveBackupStatus == DriveBackupStatus.NEEDS_PERMISSION) {
                    _driveBackupStatus.value = DriveBackupStatus.ACTIVE
                    DriveBackupStatus.ACTIVE
                } else {
                    snapshot.driveBackupStatus
                }
                val report = AppDiagnosticsRunner.run(
                    context = context,
                    userId = currentUserId,
                    selectedDateKey = snapshot.selectedDateKey,
                    deliveries = bundle?.deliveries ?: snapshot.deliveriesForDay,
                    utangPayments = bundle?.payments ?: snapshot.utangPayments,
                    expenses = bundle?.expenses ?: snapshot.expensesForDay,
                    dailyLedgers = bundle?.ledgers ?: emptyList(),
                    customers = bundle?.customers ?: snapshot.customers,
                    defaultSukli = bundle?.settings?.defaultSukli ?: snapshot.settings.defaultSukli,
                    pendingSyncCount = bundle?.pendingCount ?: snapshot.pendingSyncCount,
                    driveBackupStatus = effectiveDriveStatus,
                    lastDriveBackupTimestamp = snapshot.driveLastBackupTimestamp,
                    backupMetadataSummary = snapshot.driveBackupSummary,
                    silentDriveRefreshSucceeded = silentDriveOk,
                    lastFirestoreSyncError = MinigeFirestoreDataSource.getLastSyncError(context, currentUserId),
                )
                _diagnosticReport.value = report
            } finally {
                _isRunningDiagnostics.value = false
            }
        }
    }

    private data class SyncFilterSnapshot(
        val syncing: Boolean,
        val bossReceivables: List<BossReceivableEntry>,
        val todaySearchQuery: String,
        val todayFilterChip: DeliveryFilterChip,
        val isRefreshingToday: Boolean,
        val refreshNonce: Long,
    )

    private data class UiExtrasSnapshot(
        val calSyncing: Boolean,
        val status: String?,
        val bulkSummary: BulkUtangSettlementSummary?,
        val diagnosticReport: AppDiagnosticReport?,
        val isRunningDiagnostics: Boolean,
    )

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
                val remote = MinigeFirestoreDataSource(
                    db = firestore,
                    appContext = app.applicationContext,
                )
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
