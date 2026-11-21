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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
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
    activeCommissionRate: Double = settings.commissionRate.toDouble(),
    initialDelivery: DeliveryRecord? = null,
    onDismiss: () -> Unit,
    onSave: (
        customerName: String,
        purifiedGallons: Int,
        alkalineGallons: Int,
        paymentType: PaymentType,
        notes: String,
        customUnitPrice: Double?,
    ) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val customerFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val initialPriceText = remember(initialDelivery?.id, settings) {
        if (initialDelivery != null && initialDelivery.paymentType != PaymentType.PICK_UP && initialDelivery.totalGallons > 0) {
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
        } else {
            settings.purifiedPrice.toString()
        }
    }

    var customerName by remember(initialDelivery?.id) { mutableStateOf(initialDelivery?.customerName ?: "") }
    var purifiedText by remember(initialDelivery?.id) {
        mutableStateOf((initialDelivery?.purifiedGallons ?: 0).toString())
    }
    var alkalineText by remember(initialDelivery?.id) {
        mutableStateOf((initialDelivery?.alkalineGallons ?: 0).toString())
    }
    var paymentType by remember(initialDelivery?.id) {
        mutableStateOf(initialDelivery?.paymentType ?: PaymentType.CASH)
    }
    var selectedPaidChannel by remember(initialDelivery?.id) {
        mutableStateOf(
            if (initialDelivery != null) PaymentBreakdownHelper.resolveDeliveryChannel(initialDelivery)
            else PaidChannel.CASH
        )
    }
    var classification by remember(initialDelivery?.id) {
        mutableStateOf(initialDelivery?.let { DeliveryVoiceParser.extractClassification(it.notes) })
    }
    var routeArea by remember(initialDelivery?.id) {
        mutableStateOf(initialDelivery?.let { CustomerDirectoryHelper.extractRouteArea(it.notes) } ?: "")
    }
    val initialCleanNotes = remember(initialDelivery?.id) {
        initialDelivery?.notes?.let {
            CustomerDirectoryHelper.stripRouteAreaTag(
                PaymentBreakdownHelper.stripChannelTags(DeliveryVoiceParser.stripClassificationTag(it))
            )
        } ?: ""
    }
    var notes by remember(initialDelivery?.id) { mutableStateOf(initialCleanNotes) }
    var unitPriceText by remember(initialDelivery?.id) { mutableStateOf(initialPriceText) }
    var hasManualPriceOverride by remember(initialDelivery?.id) { mutableStateOf(initialDelivery != null) }
    var formError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialDelivery?.id) {
        if (initialDelivery == null) {
            delay(80)
            runCatching { customerFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    val purifiedQty = purifiedText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val alkalineQty = alkalineText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val totalGallons = purifiedQty + alkalineQty

    var debouncedCustomerQuery by remember(initialDelivery?.id) { mutableStateOf(customerName) }
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
        paymentType == PaymentType.PICK_UP -> 0
        isMixedDefaultPrice -> (purifiedQty * settings.purifiedPrice) + (alkalineQty * settings.alkalinePrice)
        else -> (totalGallons * parsedUnitPrice).roundToInt()
    }
    val previewCommission = when (paymentType) {
        PaymentType.CASH, PaymentType.BANK_DEPOSIT ->
            CommissionRateHistory.calculateCommissionAmount(totalGallons, activeCommissionRate)
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
                text = if (initialDelivery == null) "Log Delivery / Pick-up" else "Edit Delivery / Pick-up",
                style = MaterialTheme.typography.titleLarge,
            )

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
                enabled = paymentType != PaymentType.PICK_UP,
                label = { Text("Unit Price (₱/gal)") },
                supportingText = {
                    val savedPrice = matchedCustomer?.lastUnitPrice
                    val hint = when {
                        paymentType == PaymentType.PICK_UP -> "Pick-up entries earn ₱0 remittance & commission"
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
                            text = if (paymentType == PaymentType.PICK_UP) "Remittance Bill" else "Total Bill",
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
                                else -> "₱$previewCommission ($totalGallons × ₱${CommissionRateHistory.formatRate(activeCommissionRate)})"
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
                    val priceToSave = when {
                        paymentType == PaymentType.PICK_UP -> null
                        isMixedDefaultPrice -> null
                        else -> unitPriceText.toDoubleOrNull()?.takeIf { it >= 0.0 }
                    }
                    when {
                        customerName.isBlank() -> formError = "Please enter the customer name."
                        totalGallons <= 0 -> formError = "Please enter at least 1 gallon."
                        paymentType != PaymentType.PICK_UP && !isMixedDefaultPrice && priceToSave == null ->
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
                            val withClassification = DeliveryVoiceParser.applyClassificationTag(
                                notes = withChannel,
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
    onDismiss: () -> Unit,
    onSave: (ExpenseCategory, Int, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember { mutableStateOf(ExpenseCategory.FOOD) }
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
                text = "Log Route Expense",
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
                Text("Save Expense", style = MaterialTheme.typography.labelLarge)
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
