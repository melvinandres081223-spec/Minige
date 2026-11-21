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
import com.melvin.minige.sync.CalendarAuthResult
import com.melvin.minige.sync.CalendarSyncManager
import com.melvin.minige.ui.components.SundayMakeupHelper
import com.melvin.minige.util.PaymentBreakdownHelper
import com.melvin.minige.util.PaymentChannelBreakdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import java.time.LocalDate

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
    val pendingSyncCount: Int = 0,
    val isSyncing: Boolean = false,
    val isCalendarSyncing: Boolean = false,
    val statusMessage: String? = null,
)

sealed interface MinigeEvent {
    data class ShowMessage(val message: String) : MinigeEvent
    data class LaunchCalendarConsent(val intentSender: IntentSender) : MinigeEvent
}

class MinigeViewModel(
    private val repository: MinigeRepository,
    private val pricingRepository: PricingRepository,
    private val currentUserId: String,
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    private val _sundayMakeupEnabled = MutableStateFlow(false)
    private val _isSyncing = MutableStateFlow(false)
    private val _isCalendarSyncing = MutableStateFlow(false)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private var latestBundle: LocalBundle? = null
    private var pendingCalendarSyncDate: LocalDate? = null

    private val _events = MutableSharedFlow<MinigeEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MinigeEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                repository.startCloudSync(currentUserId) { error ->
                    Log.w(TAG, "Cloud sync stream error", error)
                    _statusMessage.value = "Offline mode: changes saved locally and queued for sync."
                }
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
        LocalBundle(deliveries, payments, expenses, ledgers, settings, customers)
    }

    val uiState: StateFlow<MinigeUiState> = combine(
        combine(_selectedDate, _sundayMakeupEnabled) { date, sundayEnabled -> date to sundayEnabled },
        localDataFlow,
        _isSyncing,
        _isCalendarSyncing,
        _statusMessage,
    ) { (date, sundayEnabled), bundle, syncing, calSyncing, status ->
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

        val pendingCount = bundle.deliveries.count { it.pendingSync } +
            bundle.payments.count { it.pendingSync } +
            bundle.expenses.count { it.pendingSync } +
            bundle.ledgers.count { it.pendingSync } +
            bundle.customers.count { it.pendingSync }

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
            pendingSyncCount = pendingCount,
            isSyncing = syncing,
            isCalendarSyncing = calSyncing,
            statusMessage = status,
        )
    }.stateIn(
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
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
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
            )
            val msg = if (result.isSuccess) {
                "Saved ${customerName.trim()} (${paymentType.label})"
            } else {
                "Saved locally — will sync to cloud when online."
            }
            _events.tryEmit(MinigeEvent.ShowMessage(msg))
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
            val msg = if (result.isSuccess) {
                "Updated ${customerName.trim()} (${paymentType.label})"
            } else {
                "Updated locally — will sync to cloud when online."
            }
            _events.tryEmit(MinigeEvent.ShowMessage(msg))
        }
    }

    fun deleteDelivery(deliveryId: String) {
        viewModelScope.launch {
            val result = repository.deleteDelivery(currentUserId, deliveryId)
            val msg = if (result.isSuccess) "Entry deleted" else "Deleted locally (pending cloud sync)"
            _events.tryEmit(MinigeEvent.ShowMessage(msg))
        }
    }

    fun recordUtangPayment(
        deliveryId: String,
        amount: Int,
        method: UtangMethod,
        notes: String,
    ) {
        val paymentDateKey = MinigeDateUtils.toKey(_selectedDate.value)
        viewModelScope.launch {
            val result = repository.recordUtangPayment(
                userId = currentUserId,
                deliveryId = deliveryId,
                amount = amount,
                method = method,
                paymentDateKey = paymentDateKey,
                notes = notes,
            )
            result.onSuccess { fullyPaid ->
                val msg = if (fullyPaid) {
                    "Utang fully paid! Commission awarded for $paymentDateKey."
                } else {
                    "Partial payment recorded (₱0 commission until fully paid)."
                }
                _events.tryEmit(MinigeEvent.ShowMessage(msg))
            }.onFailure { err ->
                _events.tryEmit(MinigeEvent.ShowMessage(err.localizedMessage ?: "Payment saved locally."))
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
        }
    }

    fun deleteExpense(expenseId: String) {
        viewModelScope.launch {
            repository.deleteExpense(currentUserId, expenseId)
            _events.tryEmit(MinigeEvent.ShowMessage("Expense removed"))
        }
    }

    fun updateStartingSukli(sukli: Int) {
        val dateKey = MinigeDateUtils.toKey(_selectedDate.value)
        viewModelScope.launch {
            repository.updateStartingSukli(currentUserId, dateKey, sukli)
            _events.tryEmit(MinigeEvent.ShowMessage("Starting sukli updated to ₱$sukli"))
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
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _isSyncing.value = true
            val res = repository.syncPendingToCloud(currentUserId)
            _isSyncing.value = false
            res.onSuccess { count ->
                _events.tryEmit(
                    MinigeEvent.ShowMessage(
                        if (count > 0) "Synced $count pending item(s) to Firestore"
                        else "Everything is up to date"
                    )
                )
            }.onFailure { err ->
                _events.tryEmit(MinigeEvent.ShowMessage("Sync failed: ${err.localizedMessage ?: "Offline"}"))
            }
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
    )

    companion object {
        private const val TAG = "MinigeVM"

        fun factory(currentUserId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(databaseId)
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
                )
            }
        }
    }
}
