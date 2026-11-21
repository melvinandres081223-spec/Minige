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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreditScore
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.melvin.minige.sync.CalendarSyncManager
import com.melvin.minige.ui.MinigeEvent
import com.melvin.minige.ui.MinigeViewModel
import com.melvin.minige.ui.screens.AddDeliverySheet
import com.melvin.minige.ui.screens.AddExpenseSheet
import com.melvin.minige.ui.screens.AuthScreen
import com.melvin.minige.ui.screens.EditSukliDialog
import com.melvin.minige.ui.screens.PautangScreen
import com.melvin.minige.ui.screens.SettingsScreen
import com.melvin.minige.ui.screens.TodayScreen
import com.melvin.minige.ui.screens.WeeklyScreen
import com.melvin.minige.ui.theme.MinigeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MinigeTheme {
                MinigeRootApp()
            }
        }
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
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by rememberSaveable { mutableStateOf(MinigeTab.TODAY) }
    var editingDelivery by remember { mutableStateOf<DeliveryRecord?>(null) }
    var showAddDeliverySheet by remember { mutableStateOf(false) }
    var showAddExpenseSheet by remember { mutableStateOf(false) }
    var showEditSukliDialog by remember { mutableStateOf(false) }

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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MinigeEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                is MinigeEvent.LaunchCalendarConsent -> {
                    calendarConsentLauncher.launch(
                        IntentSenderRequest.Builder(event.intentSender).build()
                    )
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
                        label = { Text(tab.label) },
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == MinigeTab.TODAY) {
                FloatingActionButton(
                    onClick = {
                        editingDelivery = null
                        showAddDeliverySheet = true
                    },
                    modifier = Modifier.testTag("fab_add_entry"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Log delivery or pick-up")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                MinigeTab.TODAY -> TodayScreen(
                    uiState = uiState,
                    onShiftDate = viewModel::shiftDate,
                    onJumpToday = viewModel::jumpToToday,
                    onEditSukliClick = { showEditSukliDialog = true },
                    onEditDelivery = { delivery ->
                        editingDelivery = delivery
                        showAddDeliverySheet = true
                    },
                    onDeleteDelivery = viewModel::deleteDelivery,
                    onAddExpenseClick = { showAddExpenseSheet = true },
                    onDeleteExpense = viewModel::deleteExpense,
                    onSyncCalendarClick = { viewModel.triggerCalendarSync(context) },
                    onBossSendTriggered = { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    },
                    onUpdateQuotaTarget = viewModel::updateDailyQuotaTarget,
                )
                MinigeTab.PAUTANG -> PautangScreen(
                    uiState = uiState,
                    onRecordPayment = viewModel::recordUtangPayment,
                    onRecordPartialGallonPayment = viewModel::recordPartialGallonPayment,
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
                )
                MinigeTab.SETTINGS -> SettingsScreen(
                    uiState = uiState,
                    userEmail = userEmail,
                    onSaveSettings = viewModel::updateSettings,
                    onScheduleCommissionRate = viewModel::scheduleCommissionRate,
                    onSyncNow = viewModel::syncNow,
                    onSignOut = {
                        GoogleAuthManager.signOut(
                            credentialManager = credentialManager,
                            scope = scope,
                        )
                    },
                )
            }
        }
    }

    if (showAddDeliverySheet) {
        AddDeliverySheet(
            settings = uiState.settings,
            customers = uiState.customers,
            sukiDirectory = uiState.sukiDirectory,
            activeCommissionRate = uiState.activeCommissionRate,
            initialDelivery = editingDelivery,
            onDismiss = {
                showAddDeliverySheet = false
                editingDelivery = null
            },
            onSave = { customerName, purifiedGallons, alkalineGallons, paymentType, notes, customUnitPrice ->
                val editing = editingDelivery
                if (editing == null) {
                    viewModel.addDelivery(
                        customerName = customerName,
                        purifiedGallons = purifiedGallons,
                        alkalineGallons = alkalineGallons,
                        paymentType = paymentType,
                        notes = notes,
                        customUnitPrice = customUnitPrice,
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
                    )
                }
            },
        )
    }

    if (showAddExpenseSheet) {
        AddExpenseSheet(
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
}
