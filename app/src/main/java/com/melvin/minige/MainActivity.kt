package com.melvin.minige

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreditScore
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melvin.minige.auth.GoogleAuthManager
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.InactiveSukiAlert
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.sync.CalendarSyncManager
import com.melvin.minige.ui.MinigeEvent
import com.melvin.minige.ui.MinigeViewModel
import com.melvin.minige.ui.components.ConfirmPickupDeliveredDialog
import com.melvin.minige.ui.components.GuideScreenTab
import com.melvin.minige.ui.components.MinigeTooltipWrapper
import com.melvin.minige.ui.components.ScreenGuideSheet
import com.melvin.minige.ui.components.SyncStatusPill
import com.melvin.minige.ui.components.TodayPickupChecklistSheet
import com.melvin.minige.ui.components.UndoActionBanner
import com.melvin.minige.ui.screens.AddDeliverySheet
import com.melvin.minige.ui.screens.AddExpenseSheet
import com.melvin.minige.ui.screens.AuthScreen
import com.melvin.minige.ui.screens.EditSukliDialog
import com.melvin.minige.ui.screens.PautangScreen
import com.melvin.minige.ui.screens.SettingsScreen
import com.melvin.minige.ui.screens.TodayScreen
import com.melvin.minige.ui.screens.WeeklyScreen
import com.melvin.minige.ui.theme.MinigeTheme
import com.melvin.minige.util.DisplayRefreshRateHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DisplayRefreshRateHelper.applyMaxRefreshRate(this)
        setContent {
            MinigeTheme {
                MinigeRootApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DisplayRefreshRateHelper.applyMaxRefreshRate(this)
    }
}

private enum class MinigeTab(
    val label: String,
    val icon: ImageVector,
) {
    TODAY("Today", Icons.Rounded.Today),
    PAUTANG("Pautang", Icons.Rounded.CreditScore),
    WEEKLY("Weekly", Icons.Rounded.DateRange),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
fun MinigeRootApp() {
    val currentUser by GoogleAuthManager.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = GoogleAuthManager.currentUser())
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            MinigeMainShell(
                currentUserId = user.uid,
                userEmail = user.email,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinigeMainShell(
    currentUserId: String,
    userEmail: String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: MinigeViewModel = viewModel(
        key = currentUserId,
        factory = MinigeViewModel.factory(currentUserId),
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val undoableAction by viewModel.undoableAction.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by rememberSaveable { mutableStateOf(MinigeTab.TODAY) }
    var editingDelivery by remember { mutableStateOf<DeliveryRecord?>(null) }
    var reorderPrefill by remember { mutableStateOf<InactiveSukiAlert?>(null) }
    var initialPaymentTypeForSheet by remember { mutableStateOf<PaymentType?>(null) }
    var showAddDeliverySheet by remember { mutableStateOf(false) }
    var showPickupChecklistSheet by remember { mutableStateOf(false) }
    var confirmingPickupDelivery by remember { mutableStateOf<DeliveryRecord?>(null) }
    var showAddExpenseSheet by remember { mutableStateOf(false) }
    var initialExpenseCategory by remember { mutableStateOf(ExpenseCategory.FOOD) }
    var showEditSukliDialog by remember { mutableStateOf(false) }
    var showScreenGuideSheet by remember { mutableStateOf(false) }

    val calendarConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val token = CalendarSyncManager.extractTokenFromIntent(context, result.data)
            if (!token.isNullOrBlank()) {
                viewModel.syncDayWithCalendarToken(token)
            } else {
                scope.launch { snackbarHostState.showSnackbar("Calendar authorization completed without token.") }
            }
        }
    }

    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val token = GoogleAuthManager.extractDriveTokenFromIntent(context, result.data)
            viewModel.onDriveConsentGranted(token)
        }
    }

    val driveRecoveryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onDriveConsentGranted(token = null)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MinigeEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                is MinigeEvent.LaunchCalendarConsent -> {
                    calendarConsentLauncher.launch(
                        IntentSenderRequest.Builder(event.intentSender).build()
                    )
                }
                is MinigeEvent.LaunchDriveConsent -> {
                    driveConsentLauncher.launch(
                        IntentSenderRequest.Builder(event.intentSender).build()
                    )
                }
                is MinigeEvent.LaunchDriveRecoveryConsent -> {
                    driveRecoveryLauncher.launch(event.intent)
                }
            }
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    if (selectedTab == MinigeTab.TODAY) {
                        MinigeTooltipWrapper(label = "Refresh today's deliveries") {
                            IconButton(
                                onClick = viewModel::refreshTodayData,
                                enabled = !uiState.isRefreshingToday,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("top_bar_refresh_button"),
                            ) {
                                if (uiState.isRefreshingToday) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = "Refresh today deliveries",
                                    )
                                }
                            }
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_minige_icon_fg_1795177580809),
                            contentDescription = stringResource(R.string.minige_cd_brand_emblem),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                actions = {
                    SyncStatusPill(
                        pendingSyncCount = uiState.pendingSyncCount,
                        isSyncing = uiState.isSyncing,
                        onSyncNow = viewModel::syncNow,
                    )
                    MinigeTooltipWrapper(label = "Screen guide (Taglish)") {
                        IconButton(
                            onClick = { showScreenGuideSheet = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("screen_guide_help_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                                contentDescription = "Open screen guide",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            NavigationBar(windowInsets = WindowInsets.navigationBars) {
                MinigeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == MinigeTab.TODAY) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MinigeTooltipWrapper(label = "Log Pick-Up (For Delivery)") {
                        ExtendedFloatingActionButton(
                            onClick = {
                                editingDelivery = null
                                reorderPrefill = null
                                initialPaymentTypeForSheet = PaymentType.PICK_UP
                                showAddDeliverySheet = true
                            },
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.LocalShipping,
                                    contentDescription = "Log pick-up",
                                )
                            },
                            text = {
                                Text(
                                    text = "Pick Up",
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("fab_pick_up"),
                        )
                    }

                    MinigeTooltipWrapper(label = "Check off Pick-Up or add direct delivery") {
                        BadgedBox(
                            badge = {
                                val pendingPickups = uiState.pickupsForDay.size
                                if (pendingPickups > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                        contentColor = MaterialTheme.colorScheme.onTertiary,
                                        modifier = Modifier.testTag("fab_pickup_badge"),
                                    ) {
                                        Text(pendingPickups.toString())
                                    }
                                }
                            },
                        ) {
                            FloatingActionButton(
                                onClick = { showPickupChecklistSheet = true },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.testTag("fab_add_entry"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Open pick-up checklist or log direct delivery",
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                MinigeTab.TODAY -> TodayScreen(
                    uiState = uiState,
                    onShiftDate = viewModel::shiftDate,
                    onJumpToday = viewModel::jumpToToday,
                    onEditSukliClick = { showEditSukliDialog = true },
                    onEditDelivery = { delivery ->
                        reorderPrefill = null
                        initialPaymentTypeForSheet = null
                        editingDelivery = delivery
                        showAddDeliverySheet = true
                    },
                    onDeleteDelivery = viewModel::deleteDelivery,
                    onAddExpenseClick = {
                        initialExpenseCategory = ExpenseCategory.FOOD
                        showAddExpenseSheet = true
                    },
                    onAddCashAdvanceClick = {
                        initialExpenseCategory = ExpenseCategory.CASH_ADVANCE
                        showAddExpenseSheet = true
                    },
                    onDeleteExpense = viewModel::deleteExpense,
                    onSyncCalendarClick = { viewModel.triggerCalendarSync(context) },
                    onBossSendTriggered = { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    },
                    onUpdateQuotaTarget = viewModel::updateDailyQuotaTarget,
                    onMarkPickupDelivered = { pickup ->
                        confirmingPickupDelivery = pickup
                    },
                    onSearchQueryChange = viewModel::setTodaySearchQuery,
                    onSelectFilterChip = viewModel::setTodayFilterChip,
                    onRefreshToday = viewModel::refreshTodayData,
                )
                MinigeTab.PAUTANG -> PautangScreen(
                    uiState = uiState,
                    onRecordPayment = viewModel::recordUtangPayment,
                    onRecordPartialGallonPayment = viewModel::recordPartialGallonPayment,
                    onRecordBulkPayment = viewModel::onRecordBulkUtangPayment,
                    onDismissBulkSettlementConfirmation = viewModel::dismissBulkSettlementConfirmation,
                    onUndoSettledUtang = viewModel::undoSettledUtang,
                )
                MinigeTab.WEEKLY -> WeeklyScreen(
                    uiState = uiState,
                    onSelectDate = { date ->
                        viewModel.selectDate(date)
                        selectedTab = MinigeTab.TODAY
                    },
                    onSyncCalendarClick = { viewModel.triggerCalendarSync(context) },
                    onToggleSundayMakeup = viewModel::setSundayMakeupMode,
                    onSyncSundayMakeupClick = { sundayDate ->
                        viewModel.triggerCalendarSync(context, targetDate = sundayDate)
                    },
                    onAddBossReceivable = viewModel::addBossReceivable,
                    onMarkBossReceivablePaid = viewModel::markBossReceivablePaid,
                    onDeleteBossReceivable = viewModel::deleteBossReceivable,
                )
                MinigeTab.SETTINGS -> SettingsScreen(
                    uiState = uiState,
                    userEmail = userEmail,
                    onSaveSettings = viewModel::updateSettings,
                    onSaveSmallGallonSettings = viewModel::updateSmallGallonSettings,
                    onScheduleCommissionRate = viewModel::scheduleCommissionRate,
                    onUpdateQuotaTarget = viewModel::updateDailyQuotaTarget,
                    onToggleSundayMakeup = viewModel::setSundayMakeupMode,
                    onToggleInactiveAlerts = viewModel::setInactiveAlertsEnabled,
                    onUpdateInactiveThresholdDays = viewModel::setInactiveThresholdDays,
                    onReorderInactiveSuki = { alert ->
                        editingDelivery = null
                        initialPaymentTypeForSheet = null
                        reorderPrefill = alert
                        selectedTab = MinigeTab.TODAY
                        showAddDeliverySheet = true
                    },
                    onSyncNow = viewModel::syncNow,
                    onGrantDriveAccess = { viewModel.triggerDriveConsent(context) },
                    onBackupToDriveNow = { viewModel.manualBackupNow(context) },
                    onPreviewRestoreFromDrive = { viewModel.previewDriveRestore(context) },
                    onConfirmRestoreFromDrive = { viewModel.restoreFromDrive(context) },
                    onDismissRestorePreview = viewModel::dismissRestorePreview,
                    onToggleAutoBackupBeforeUpdate = viewModel::setAutoBackupBeforeUpdate,
                    onBackupBeforeUpdate = { viewModel.backupBeforeAppUpdate(context) },
                    onRunDiagnostics = { viewModel.runAppDiagnostics(context) },
                    onSignOut = {
                        GoogleAuthManager.signOut(
                            credentialManager = credentialManager,
                            scope = scope,
                            context = context,
                        )
                    },
                )
            }
            }

            UndoActionBanner(
                action = undoableAction,
                onUndoClick = viewModel::executeUndo,
                onDismiss = viewModel::dismissUndoAction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = if (selectedTab == MinigeTab.TODAY) 88.dp else 16.dp,
                        bottom = 14.dp,
                    ),
            )
        }
    }

    if (showPickupChecklistSheet) {
        TodayPickupChecklistSheet(
            pickups = uiState.pickupsForDay,
            onCheckOffPickup = { pickup ->
                showPickupChecklistSheet = false
                confirmingPickupDelivery = pickup
            },
            onNewDirectDelivery = {
                showPickupChecklistSheet = false
                editingDelivery = null
                reorderPrefill = null
                initialPaymentTypeForSheet = PaymentType.CASH
                showAddDeliverySheet = true
            },
            onDismiss = { showPickupChecklistSheet = false },
        )
    }

    val pickupToConfirm = confirmingPickupDelivery
    if (pickupToConfirm != null) {
        ConfirmPickupDeliveredDialog(
            delivery = pickupToConfirm,
            settings = uiState.settings,
            customers = uiState.customers,
            activeCommissionRate = uiState.activeCommissionRate,
            smallGallonPrice = uiState.smallGallonPrice,
            smallGallonCommissionRate = uiState.smallGallonCommissionRate,
            onDismiss = { confirmingPickupDelivery = null },
            onConfirm = { paymentType, notes, customUnitPrice, customPurifiedUnitPrice, customAlkalineUnitPrice, hasCommission ->
                viewModel.updateDelivery(
                    deliveryId = pickupToConfirm.id,
                    customerName = pickupToConfirm.customerName,
                    purifiedGallons = pickupToConfirm.purifiedGallons,
                    alkalineGallons = pickupToConfirm.alkalineGallons,
                    paymentType = paymentType,
                    notes = notes,
                    customUnitPrice = customUnitPrice,
                    hasCommission = hasCommission,
                    customPurifiedUnitPrice = customPurifiedUnitPrice,
                    customAlkalineUnitPrice = customAlkalineUnitPrice,
                )
                confirmingPickupDelivery = null
            },
        )
    }

    if (showAddDeliverySheet) {
        AddDeliverySheet(
            settings = uiState.settings,
            customers = uiState.customers,
            sukiDirectory = uiState.sukiDirectory,
            inactiveAlertsByCustomerId = uiState.inactiveAlertsByCustomerId,
            activeCommissionRate = uiState.activeCommissionRate,
            smallGallonPrice = uiState.smallGallonPrice,
            smallGallonCommissionRate = uiState.smallGallonCommissionRate,
            initialDelivery = editingDelivery,
            reorderPrefill = reorderPrefill,
            initialPaymentType = initialPaymentTypeForSheet,
            onDismiss = {
                showAddDeliverySheet = false
                editingDelivery = null
                reorderPrefill = null
                initialPaymentTypeForSheet = null
            },
            onSave = { customerName, purifiedGallons, alkalineGallons, paymentType, notes, customUnitPrice, hasCommission ->
                val editing = editingDelivery
                if (editing == null) {
                    viewModel.addDelivery(
                        customerName = customerName,
                        purifiedGallons = purifiedGallons,
                        alkalineGallons = alkalineGallons,
                        paymentType = paymentType,
                        notes = notes,
                        customUnitPrice = customUnitPrice,
                        hasCommission = hasCommission,
                    )
                } else {
                    viewModel.updateDelivery(
                        deliveryId = editing.id,
                        customerName = customerName,
                        purifiedGallons = purifiedGallons,
                        alkalineGallons = alkalineGallons,
                        paymentType = paymentType,
                        notes = notes,
                        customUnitPrice = customUnitPrice,
                        hasCommission = hasCommission,
                    )
                }
            },
            onSaveCashAdvance = { amount, advNotes ->
                viewModel.addExpense(
                    category = ExpenseCategory.CASH_ADVANCE,
                    amount = amount,
                    notes = advNotes,
                )
            },
        )
    }

    if (showAddExpenseSheet) {
        AddExpenseSheet(
            initialCategory = initialExpenseCategory,
            onDismiss = { showAddExpenseSheet = false },
            onSave = viewModel::addExpense,
        )
    }

    if (showEditSukliDialog) {
        EditSukliDialog(
            currentSukli = uiState.dailySummary.startingSukli,
            onDismiss = { showEditSukliDialog = false },
            onSave = viewModel::updateStartingSukli,
        )
    }

    if (showScreenGuideSheet) {
        val guideTab = when (selectedTab) {
            MinigeTab.TODAY -> GuideScreenTab.TODAY
            MinigeTab.PAUTANG -> GuideScreenTab.PAUTANG
            MinigeTab.WEEKLY -> GuideScreenTab.WEEKLY
            MinigeTab.SETTINGS -> GuideScreenTab.SETTINGS
        }
        ScreenGuideSheet(
            initialTab = guideTab,
            onDismiss = { showScreenGuideSheet = false },
        )
    }
}
