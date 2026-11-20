package com.melvin.minige.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.MinigeSettings
import com.melvin.minige.domain.PaymentType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddDeliverySheet(
    settings: MinigeSettings,
    onDismiss: () -> Unit,
    onSave: (
        customerName: String,
        purifiedGallons: Int,
        alkalineGallons: Int,
        paymentType: PaymentType,
        notes: String,
    ) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var customerName by remember { mutableStateOf("") }
    var purifiedText by remember { mutableStateOf("0") }
    var alkalineText by remember { mutableStateOf("0") }
    var paymentType by remember { mutableStateOf(PaymentType.CASH) }
    var notes by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                customerName = spoken.trim().take(80)
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
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say customer name")
            }
            runCatching { voiceLauncher.launch(intent) }
        }
    }

    val purifiedQty = purifiedText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val alkalineQty = alkalineText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val totalGallons = purifiedQty + alkalineQty
    val billAmount = if (paymentType == PaymentType.PICK_UP) {
        0
    } else {
        (purifiedQty * settings.purifiedPrice) + (alkalineQty * settings.alkalinePrice)
    }
    val previewCommission = when (paymentType) {
        PaymentType.CASH, PaymentType.BANK_DEPOSIT -> totalGallons * settings.commissionRate
        PaymentType.PAUTANG, PaymentType.PICK_UP -> 0
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Log Delivery / Pick-up",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = customerName,
                onValueChange = {
                    customerName = it.take(80)
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
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Say customer name")
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
                            contentDescription = "Voice input customer name",
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("delivery_customer_input"),
            )

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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Payment / Entry Type",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PaymentType.entries.forEach { type ->
                        FilterChip(
                            selected = paymentType == type,
                            onClick = { paymentType = type },
                            label = { Text(type.label) },
                            modifier = Modifier.testTag("payment_type_${type.name}"),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it.take(300) },
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
                                PaymentType.PAUTANG -> "₱0 (Awarded at ₱0 balance)"
                                PaymentType.PICK_UP -> "₱0 (Pick-up)"
                                else -> "₱$previewCommission ($totalGallons × ₱${settings.commissionRate})"
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
                    when {
                        customerName.isBlank() -> formError = "Please enter the customer name."
                        totalGallons <= 0 -> formError = "Please enter at least 1 gallon."
                        else -> {
                            onSave(customerName.trim(), purifiedQty, alkalineQty, paymentType, notes)
                            onDismiss()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_delivery_button"),
            ) {
                Text("Save Entry", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
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
