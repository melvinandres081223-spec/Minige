package com.melvin.minige.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.InactiveSukiAlert
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.MinigeSettings
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.ui.components.CustomerSuggestionBar
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.CustomerClassification
import com.melvin.minige.util.CustomerDirectoryHelper
import com.melvin.minige.util.DeliveryVoiceParser
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PaymentBreakdownHelper
import com.melvin.minige.util.SukiCustomerProfile
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddDeliverySheet(
    settings: MinigeSettings,
    customers: List<CustomerRecord>,
    sukiDirectory: List<SukiCustomerProfile> = emptyList(),
    inactiveAlertsByCustomerId: Map<String, InactiveSukiAlert> = emptyMap(),
    activeCommissionRate: Double = settings.commissionRate.toDouble(),
    initialDelivery: DeliveryRecord? = null,
    reorderPrefill: InactiveSukiAlert? = null,
    onDismiss: () -> Unit,
    onSave: (
        customerName: String,
        purifiedGallons: Int,
        alkalineGallons: Int,
        paymentType: PaymentType,
        notes: String,
        customUnitPrice: Double?,
        hasCommission: Boolean,
    ) -> Unit,
    onSaveCashAdvance: ((amount: Int, notes: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val customerFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val formKey = Triple(initialDelivery?.id, reorderPrefill?.customerId, reorderPrefill?.lastDeliveryDateKey)

    val initialPriceText = remember(formKey, settings) {
        if (initialDelivery != null &&
            initialDelivery.paymentType != PaymentType.PICK_UP &&
            initialDelivery.paymentType != PaymentType.PAID_WALK_IN &&
            initialDelivery.totalGallons > 0
        ) {
            val expectedSettingsSplit = (initialDelivery.purifiedGallons * initialDelivery.purifiedUnitPrice) +
                (initialDelivery.alkalineGallons * initialDelivery.alkalineUnitPrice)
            val derivedPrice = if (initialDelivery.totalAmount != expectedSettingsSplit && initialDelivery.totalAmount > 0) {
                initialDelivery.totalAmount.toDouble() / initialDelivery.totalGallons
            } else if (initialDelivery.alkalineGallons > 0 && initialDelivery.purifiedGallons == 0) {
                initialDelivery.alkalineUnitPrice.toDouble()
            } else {
                initialDelivery.purifiedUnitPrice.toDouble()
            }
            MinigeCustomerUtils.formatPrice(derivedPrice)
        } else if (reorderPrefill?.lastUnitPrice != null && reorderPrefill.lastUnitPrice >= 0.0) {
            MinigeCustomerUtils.formatPrice(reorderPrefill.lastUnitPrice)
        } else {
            settings.purifiedPrice.toString()
        }
    }

    var isQuickCashAdvanceMode by remember(formKey) { mutableStateOf(false) }
    var cashAdvanceAmountText by remember(formKey) { mutableStateOf("") }
    var cashAdvanceNotes by remember(formKey) { mutableStateOf("") }

    var customerName by remember(formKey) {
        mutableStateOf(initialDelivery?.customerName ?: reorderPrefill?.customerName ?: "")
    }
    var purifiedText by remember(formKey) {
        mutableStateOf((initialDelivery?.purifiedGallons ?: reorderPrefill?.lastPurifiedGallons ?: 0).toString())
    }
    var alkalineText by remember(formKey) {
        mutableStateOf((initialDelivery?.alkalineGallons ?: reorderPrefill?.lastAlkalineGallons ?: 0).toString())
    }
    var paymentType by remember(formKey) {
        mutableStateOf(initialDelivery?.paymentType ?: PaymentType.CASH)
    }
    var hasPaidWalkInCommission by remember(formKey) {
        mutableStateOf(
            if (initialDelivery?.paymentType == PaymentType.PAID_WALK_IN) {
                PaymentBreakdownHelper.resolvePaidWalkInHasCommission(initialDelivery)
            } else {
                true
            }
        )
    }
    var selectedPaidChannel by remember(formKey) {
        mutableStateOf(
            if (initialDelivery != null) PaymentBreakdownHelper.resolveDeliveryChannel(initialDelivery)
            else PaidChannel.CASH
        )
    }
    var classification by remember(formKey) {
        val sourceNotes = initialDelivery?.notes ?: reorderPrefill?.lastNotes
        mutableStateOf(sourceNotes?.let { DeliveryVoiceParser.extractClassification(it) })
    }
    var routeArea by remember(formKey) {
        val sourceNotes = initialDelivery?.notes ?: reorderPrefill?.lastNotes
        mutableStateOf(sourceNotes?.let { CustomerDirectoryHelper.extractRouteArea(it) } ?: "")
    }
    val initialCleanNotes = remember(formKey) {
        initialDelivery?.notes?.let {
            CustomerDirectoryHelper.stripRouteAreaTag(
                PaymentBreakdownHelper.stripPaidWalkInTags(
                    PaymentBreakdownHelper.stripChannelTags(DeliveryVoiceParser.stripClassificationTag(it))
                )
            )
        } ?: ""
    }
    var notes by remember(formKey) { mutableStateOf(initialCleanNotes) }
    var unitPriceText by remember(formKey) { mutableStateOf(initialPriceText) }
    var hasManualPriceOverride by remember(formKey) {
        mutableStateOf(initialDelivery != null || reorderPrefill?.lastUnitPrice != null)
    }
    var formError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(formKey) {
        if (initialDelivery == null && reorderPrefill == null) {
            delay(80)
            runCatching { customerFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    val purifiedQty = purifiedText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val alkalineQty = alkalineText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val totalGallons = purifiedQty + alkalineQty

    var debouncedCustomerQuery by remember(formKey) { mutableStateOf(customerName) }
    LaunchedEffect(customerName) {
        if (customerName.isBlank()) {
            debouncedCustomerQuery = ""
        } else {
            delay(120)
            debouncedCustomerQuery = customerName
        }
    }

    val normalizedTypedId = remember(customerName) {
        if (customerName.isBlank()) "" else MinigeCustomerUtils.normalizeCustomerId(customerName)
    }
    val matchedCustomer = remember(normalizedTypedId, customers) {
        if (normalizedTypedId.isEmpty()) null
        else customers.firstOrNull { it.id == normalizedTypedId }
    }

    val sukiSuggestions by remember(sukiDirectory) {
        derivedStateOf {
            CustomerDirectoryHelper.filterSuggestions(sukiDirectory, debouncedCustomerQuery, limit = 10)
        }
    }

    val defaultUnitPriceForSelection = remember(purifiedQty, alkalineQty, settings) {
        if (alkalineQty > 0 && purifiedQty == 0) settings.alkalinePrice else settings.purifiedPrice
    }

    LaunchedEffect(matchedCustomer?.id, matchedCustomer?.lastUnitPrice, defaultUnitPriceForSelection, hasManualPriceOverride) {
        if (!hasManualPriceOverride) {
            val remembered = matchedCustomer?.lastUnitPrice
            unitPriceText = if (remembered != null && remembered >= 0.0) {
                MinigeCustomerUtils.formatPrice(remembered)
            } else {
                defaultUnitPriceForSelection.toString()
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                val parsed = DeliveryVoiceParser.parse(spoken, customers)
                if (parsed.customerName.isNotBlank()) {
                    customerName = parsed.customerName
                }
                if (parsed.gallons != null) {
                    if (parsed.isAlkaline) {
                        alkalineText = parsed.gallons.toString()
                        if (initialDelivery == null) purifiedText = "0"
                    } else {
                        purifiedText = parsed.gallons.toString()
                        if (initialDelivery == null) alkalineText = "0"
                    }
                }
                if (parsed.classification != null) {
                    classification = parsed.classification
                }
                if (parsed.paymentType != null) {
                    paymentType = parsed.paymentType
                    selectedPaidChannel = parsed.paidChannel
                }
                val sukiMatch = sukiDirectory.firstOrNull {
                    it.id == MinigeCustomerUtils.normalizeCustomerId(parsed.customerName)
                }
                if (sukiMatch != null) {
                    if (parsed.classification == null && sukiMatch.classification != null) {
                        classification = sukiMatch.classification
                    }
                    if (!sukiMatch.routeArea.isNullOrBlank()) {
                        routeArea = sukiMatch.routeArea
                    }
                }
                hasManualPriceOverride = false
                if (parsed.rememberedUnitPrice != null && parsed.rememberedUnitPrice >= 0.0) {
                    unitPriceText = MinigeCustomerUtils.formatPrice(parsed.rememberedUnitPrice)
                }
                formError = null
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say e.g. '5 outlet pick up Ate Maria'")
            }
            runCatching { voiceLauncher.launch(intent) }
        }
    }

    val isMixedDefaultPrice = purifiedQty > 0 &&
        alkalineQty > 0 &&
        matchedCustomer?.lastUnitPrice == null &&
        !hasManualPriceOverride
    val parsedUnitPrice = unitPriceText.toDoubleOrNull()?.takeIf { it >= 0.0 }
        ?: defaultUnitPriceForSelection.toDouble()

    val billAmount = when {
        paymentType == PaymentType.PICK_UP || paymentType == PaymentType.PAID_WALK_IN -> 0
        isMixedDefaultPrice -> (purifiedQty * settings.purifiedPrice) + (alkalineQty * settings.alkalinePrice)
        else -> (totalGallons * parsedUnitPrice).roundToInt()
    }
    val rateFormatted = CommissionRateHistory.formatRate(activeCommissionRate)
    val previewCommission = when (paymentType) {
        PaymentType.CASH, PaymentType.BANK_DEPOSIT ->
            CommissionRateHistory.calculateCommissionAmount(totalGallons, activeCommissionRate)
        PaymentType.PAID_WALK_IN -> if (hasPaidWalkInCommission) {
            CommissionRateHistory.calculateCommissionAmount(totalGallons, activeCommissionRate)
        } else {
            0
        }
        PaymentType.PAUTANG, PaymentType.PICK_UP -> 0
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = when {
                    initialDelivery != null -> "Edit Delivery / Pick-up"
                    isQuickCashAdvanceMode -> "Log Cash Advance"
                    else -> "Log Delivery / Pick-up"
                },
                style = MaterialTheme.typography.titleLarge,
            )

            if (initialDelivery == null && onSaveCashAdvance != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = !isQuickCashAdvanceMode,
                        onClick = {
                            isQuickCashAdvanceMode = false
                            formError = null
                        },
                        label = { Text("Delivery / Pick-up") },
                        modifier = Modifier.testTag("entry_mode_delivery_chip"),
                    )
                    FilterChip(
                        selected = isQuickCashAdvanceMode,
                        onClick = {
                            isQuickCashAdvanceMode = true
                            formError = null
                        },
                        label = { Text("Cash Advance") },
                        modifier = Modifier.testTag("entry_mode_cash_advance_chip"),
                    )
                }
            }

            if (isQuickCashAdvanceMode && onSaveCashAdvance != null) {
                OutlinedTextField(
                    value = cashAdvanceAmountText,
                    onValueChange = {
                        cashAdvanceAmountText = it.filter(Char::isDigit).take(7)
                        formError = null
                    },
                    label = { Text("Cash Advance Amount (₱)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_cash_advance_amount_input"),
                )
                OutlinedTextField(
                    value = cashAdvanceNotes,
                    onValueChange = { cashAdvanceNotes = it.take(300) },
                    label = { Text("Notes (optional)") },
                    placeholder = { Text("e.g. Advance mula sa cash remittance") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Ibebawas ito sa Net Cash to Remit to Boss ngayong araw at sa Net Remaining Commission mo sa Weekly Summary.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                if (formError != null) {
                    Text(
                        text = formError ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        val amt = cashAdvanceAmountText.toIntOrNull() ?: 0
                        if (amt <= 0) {
                            formError = "Enter a Cash Advance amount greater than ₱0."
                        } else {
                            onSaveCashAdvance(amt, cashAdvanceNotes)
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("quick_save_cash_advance_button"),
                ) {
                    Text("Save Cash Advance", style = MaterialTheme.typography.labelLarge)
                }
                return@ModalBottomSheet
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customerName,
                    onValueChange = {
                        customerName = it.take(80)
                        hasManualPriceOverride = false
                        formError = null
                    },
                    label = { Text("Customer Name") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                        )
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say e.g. '5 outlet pick up Ate Maria'")
                                    }
                                    runCatching { voiceLauncher.launch(intent) }
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.testTag("voice_customer_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = "Voice input delivery details",
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(customerFocusRequester)
                        .testTag("delivery_customer_input"),
                )

                CustomerSuggestionBar(
                    suggestions = sukiSuggestions,
                    inactiveAlertsByCustomerId = inactiveAlertsByCustomerId,
                    onSelectCustomer = { profile ->
                        customerName = profile.name
                        if (profile.classification != null) {
                            classification = profile.classification
                        }
                        if (!profile.routeArea.isNullOrBlank()) {
                            routeArea = profile.routeArea
                        }
                        hasManualPriceOverride = false
                        unitPriceText = profile.savedUnitPrice
                            ?.takeIf { it >= 0.0 }
                            ?.let { MinigeCustomerUtils.formatPrice(it) }
                            ?: defaultUnitPriceForSelection.toString()
                        formError = null
                    },
                )
            }

            GallonStepperRow(
                label = "Purified (₱${settings.purifiedPrice}/gal)",
                valueText = purifiedText,
                onValueChange = {
                    purifiedText = it.filter(Char::isDigit).take(4)
                    formError = null
                },
                onDelta = { delta ->
                    val next = (purifiedQty + delta).coerceIn(0, 9999)
                    purifiedText = next.toString()
                    formError = null
                },
                testTagPrefix = "purified",
            )

            GallonStepperRow(
                label = "Alkaline (₱${settings.alkalinePrice}/gal)",
                valueText = alkalineText,
                onValueChange = {
                    alkalineText = it.filter(Char::isDigit).take(4)
                    formError = null
                },
                onDelta = { delta ->
                    val next = (alkalineQty + delta).coerceIn(0, 9999)
                    alkalineText = next.toString()
                    formError = null
                },
                testTagPrefix = "alkaline",
            )

            OutlinedTextField(
                value = unitPriceText,
                onValueChange = { raw ->
                    unitPriceText = sanitizeDecimalPrice(raw)
                    hasManualPriceOverride = true
                    formError = null
                },
                enabled = paymentType != PaymentType.PICK_UP && paymentType != PaymentType.PAID_WALK_IN,
                label = { Text("Unit Price (₱/gal)") },
                supportingText = {
                    val savedPrice = matchedCustomer?.lastUnitPrice
                    val hint = when {
                        paymentType == PaymentType.PICK_UP -> "Pick-up entries earn ₱0 remittance & commission"
                        paymentType == PaymentType.PAID_WALK_IN -> "Paid at station — ₱0 to collect/remit"
                        savedPrice != null && !hasManualPriceOverride ->
                            "Auto-filled from ${matchedCustomer.name}'s saved rate (₱${MinigeCustomerUtils.formatPrice(savedPrice)}/gal)"
                        isMixedDefaultPrice ->
                            "Using Settings rates (₱${settings.purifiedPrice} Purified / ₱${settings.alkalinePrice} Alkaline)"
                        else -> "Edit to override & remember this customer's rate"
                    }
                    Text(hint)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("delivery_unit_price_input"),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Customer Type (Optional)",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CustomerClassification.entries.forEach { cls ->
                        FilterChip(
                            selected = classification == cls,
                            onClick = {
                                classification = if (classification == cls) null else cls
                            },
                            label = { Text(cls.label) },
                            modifier = Modifier.testTag("classification_${cls.name}"),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Payment / Entry Type",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PaidChannel.entries.forEach { channel ->
                        val isSelected = paymentType == channel.storedPaymentType &&
                            (channel == PaidChannel.CASH || selectedPaidChannel == channel)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                paymentType = channel.storedPaymentType
                                selectedPaidChannel = channel
                            },
                            label = { Text(channel.label) },
                            modifier = Modifier.testTag("payment_channel_${channel.name}"),
                        )
                    }
                    FilterChip(
                        selected = paymentType == PaymentType.PAID_WALK_IN,
                        onClick = {
                            paymentType = PaymentType.PAID_WALK_IN
                            selectedPaidChannel = null
                        },
                        label = { Text(PaymentType.PAID_WALK_IN.label) },
                        modifier = Modifier.testTag("payment_type_PAID_WALK_IN"),
                    )
                    FilterChip(
                        selected = paymentType == PaymentType.PAUTANG,
                        onClick = {
                            paymentType = PaymentType.PAUTANG
                            selectedPaidChannel = null
                        },
                        label = { Text(PaymentType.PAUTANG.label) },
                        modifier = Modifier.testTag("payment_type_PAUTANG"),
                    )
                    FilterChip(
                        selected = paymentType == PaymentType.PICK_UP,
                        onClick = {
                            paymentType = PaymentType.PICK_UP
                            selectedPaidChannel = null
                        },
                        label = { Text(PaymentType.PICK_UP.label) },
                        modifier = Modifier.testTag("payment_type_PICK_UP"),
                    )
                }

                if (paymentType == PaymentType.PAID_WALK_IN) {
                    OutlinedCard(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("paid_walk_in_commission_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Bayad na sa station (hindi kasama sa Cash to Remit), pero kasama sa Daily Hakot dahil ikaw ang nag-deliver.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "May Commission (₱$rateFormatted/gal)",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                    Text(
                                        text = if (hasPaidWalkInCommission) {
                                            "May ₱$rateFormatted/gal commission ka sa delivery na ito."
                                        } else {
                                            "Walang commission (₱0) — delivery lang ng paid walk-in order."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (hasPaidWalkInCommission) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Switch(
                                    checked = hasPaidWalkInCommission,
                                    onCheckedChange = { hasPaidWalkInCommission = it },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("paid_walk_in_commission_switch"),
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = routeArea,
                onValueChange = { routeArea = it.take(40) },
                label = { Text("Route / Area Tag (optional)") },
                placeholder = { Text("e.g. Poblacion, Market") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("delivery_route_area_input"),
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it.take(240) },
                label = { Text("Notes (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Total Gallons", style = MaterialTheme.typography.bodyMedium)
                        Text("$totalGallons gal(s)", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = when (paymentType) {
                                PaymentType.PICK_UP -> "Remittance Bill"
                                PaymentType.PAID_WALK_IN -> "Cash to Collect / Remit"
                                else -> "Total Bill"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("₱$billAmount", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Commission Earned Today", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = when (paymentType) {
                                PaymentType.PAUTANG -> "₱0 (Awarded on paid gallons)"
                                PaymentType.PICK_UP -> "₱0 (Pick-up)"
                                PaymentType.PAID_WALK_IN -> if (hasPaidWalkInCommission) {
                                    "₱$previewCommission ($totalGallons × ₱$rateFormatted)"
                                } else {
                                    "₱0 (Paid Walk-in • No Commission)"
                                }
                                else -> "₱$previewCommission ($totalGallons × ₱$rateFormatted)"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (formError != null) {
                Text(
                    text = formError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    val isZeroBillMode = paymentType == PaymentType.PICK_UP || paymentType == PaymentType.PAID_WALK_IN
                    val priceToSave = when {
                        isZeroBillMode -> null
                        isMixedDefaultPrice -> null
                        else -> unitPriceText.toDoubleOrNull()?.takeIf { it >= 0.0 }
                    }
                    when {
                        customerName.isBlank() -> formError = "Please enter the customer name."
                        totalGallons <= 0 -> formError = "Please enter at least 1 gallon."
                        !isZeroBillMode && !isMixedDefaultPrice && priceToSave == null ->
                            formError = "Please enter a valid unit price."
                        else -> {
                            val withChannel = if (paymentType == PaymentType.CASH || paymentType == PaymentType.BANK_DEPOSIT) {
                                PaymentBreakdownHelper.applyChannelTag(
                                    notes = notes,
                                    channel = selectedPaidChannel ?: PaidChannel.CASH,
                                )
                            } else {
                                PaymentBreakdownHelper.stripChannelTags(notes)
                            }
                            val withWalkIn = if (paymentType == PaymentType.PAID_WALK_IN) {
                                PaymentBreakdownHelper.applyPaidWalkInTags(
                                    notes = withChannel,
                                    hasCommission = hasPaidWalkInCommission,
                                )
                            } else {
                                PaymentBreakdownHelper.stripPaidWalkInTags(withChannel)
                            }
                            val withClassification = DeliveryVoiceParser.applyClassificationTag(
                                notes = withWalkIn,
                                classification = classification,
                            )
                            val finalNotes = CustomerDirectoryHelper.applyRouteAreaTag(
                                notes = withClassification,
                                routeArea = routeArea,
                            )
                            onSave(
                                customerName.trim(),
                                purifiedQty,
                                alkalineQty,
                                paymentType,
                                finalNotes,
                                priceToSave,
                                if (paymentType == PaymentType.PAID_WALK_IN) hasPaidWalkInCommission else true,
                            )
                            onDismiss()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_delivery_button"),
            ) {
                Text(
                    text = if (initialDelivery == null) "Save Entry" else "Update Entry",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun sanitizeDecimalPrice(raw: String): String {
    val sb = StringBuilder()
    var hasDot = false
    for (ch in raw) {
        if (ch.isDigit()) {
            sb.append(ch)
        } else if (ch == '.' && !hasDot) {
            if (sb.isEmpty()) sb.append('0')
            sb.append('.')
            hasDot = true
        }
        if (sb.length >= 7) break
    }
    return sb.toString()
}

@Composable
private fun GallonStepperRow(
    label: String,
    valueText: String,
    onValueChange: (String) -> Unit,
    onDelta: (Int) -> Unit,
    testTagPrefix: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(
                onClick = { onDelta(-1) },
                modifier = Modifier.size(48.dp).testTag("${testTagPrefix}_minus"),
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "Decrease $label")
            }
            OutlinedTextField(
                value = valueText,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .width(74.dp)
                    .testTag("${testTagPrefix}_input"),
            )
            IconButton(
                onClick = { onDelta(1) },
                modifier = Modifier.size(48.dp).testTag("${testTagPrefix}_plus"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Increase $label")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddExpenseSheet(
    initialCategory: ExpenseCategory = ExpenseCategory.FOOD,
    onDismiss: () -> Unit,
    onSave: (ExpenseCategory, Int, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    var amountText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (category.isCashAdvance) "Log Cash Advance" else "Log Route Expense",
                style = MaterialTheme.typography.titleLarge,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ExpenseCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(cat.label) },
                        modifier = Modifier.testTag("expense_cat_${cat.name}"),
                    )
                }
            }

            if (category.isCashAdvance) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Ibebawas ang Cash Advance sa Net Cash to Remit ngayong araw at sa Net Remaining Commission sa Weekly Summary.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = {
                    amountText = it.filter(Char::isDigit).take(7)
                    error = null
                },
                label = { Text("Amount (₱)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("expense_amount_input"),
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it.take(300) },
                label = { Text("Notes (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(
                    text = error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    val amt = amountText.toIntOrNull() ?: 0
                    if (amt <= 0) {
                        error = "Enter an amount greater than ₱0."
                    } else {
                        onSave(category, amt, notes)
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_expense_button"),
            ) {
                Text(
                    text = if (category.isCashAdvance) "Save Cash Advance" else "Save Expense",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
fun EditSukliDialog(
    currentSukli: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var sukliText by remember(currentSukli) { mutableStateOf(currentSukli.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Starting Retained Change (Sukli)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Set your starting change (sukli) for this date to calculate exact cash on hand.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = sukliText,
                    onValueChange = { sukliText = it.filter(Char::isDigit).take(7) },
                    label = { Text("Starting Sukli (₱)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sukli_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(sukliText.toIntOrNull() ?: 0)
                    onDismiss()
                },
                modifier = Modifier.testTag("save_sukli_button"),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
