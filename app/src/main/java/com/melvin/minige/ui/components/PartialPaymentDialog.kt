package com.melvin.minige.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PartialPaymentHelper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartialPaymentDialog(
    delivery: DeliveryRecord,
    allUtangPayments: List<UtangPaymentRecord>,
    paymentDateKey: String,
    activeCommissionRate: Double,
    onDismiss: () -> Unit,
    onConfirm: (paidGallons: Int, amount: Int, channel: PaidChannel, notes: String) -> Unit,
) {
    val status = remember(delivery, allUtangPayments) {
        PartialPaymentHelper.getStatus(delivery, allUtangPayments)
    }
    val maxGallons = status.remainingUnpaidGallons.coerceAtLeast(1)

    var gallonsText by remember(delivery.id, maxGallons) {
        mutableStateOf(1.coerceAtMost(maxGallons).toString())
    }
    var selectedChannel by remember(delivery.id) { mutableStateOf(PaidChannel.CASH) }
    var notes by remember(delivery.id) { mutableStateOf("") }

    val paidGallons = (gallonsText.toIntOrNull() ?: 0).coerceIn(0, maxGallons)
    val calculatedAmount = remember(status, paidGallons) {
        PartialPaymentHelper.calculateAmountForGallons(status, paidGallons)
    }
    val earnedCommission = remember(paidGallons, activeCommissionRate) {
        CommissionRateHistory.calculateCommissionAmount(paidGallons, activeCommissionRate)
    }
    val remainingGallonsAfter = (status.remainingUnpaidGallons - paidGallons).coerceAtLeast(0)
    val remainingPesosAfter = (status.remainingBalance - calculatedAmount).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partial Pay • ${delivery.customerName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Unpaid: ${status.remainingUnpaidGallons} gal(s) • Bal: ₱${status.remainingBalance} ($paymentDateKey)",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = "Paid Gallons Today (max $maxGallons)",
                    style = MaterialTheme.typography.labelLarge,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            val next = (paidGallons - 1).coerceIn(1, maxGallons)
                            gallonsText = next.toString()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("partial_pay_minus"),
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Decrease paid gallons")
                    }
                    OutlinedTextField(
                        value = gallonsText,
                        onValueChange = { raw ->
                            val digits = raw.filter(Char::isDigit).take(4)
                            val numeric = digits.toIntOrNull()
                            gallonsText = if (numeric != null && numeric > maxGallons) {
                                maxGallons.toString()
                            } else {
                                digits
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .width(86.dp)
                            .testTag("partial_pay_gallons_input"),
                    )
                    IconButton(
                        onClick = {
                            val next = (paidGallons + 1).coerceIn(1, maxGallons)
                            gallonsText = next.toString()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("partial_pay_plus"),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Increase paid gallons")
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaidChannel.entries.forEach { channel ->
                        FilterChip(
                            selected = selectedChannel == channel,
                            onClick = { selectedChannel = channel },
                            label = { Text(channel.label) },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("partial_pay_channel_${channel.name}"),
                        )
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(240) },
                    label = { Text("Notes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Amount to Deduct: ₱$calculatedAmount (${selectedChannel.label})",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = "Immediate Commission: +₱$earnedCommission ($paidGallons gal × ₱${CommissionRateHistory.formatRate(activeCommissionRate)})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = if (remainingGallonsAfter == 0) {
                                "Will settle account to ₱0 balance!"
                            } else {
                                "Remaining Unpaid: $remainingGallonsAfter gal(s) • ₱$remainingPesosAfter"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (paidGallons > 0 && calculatedAmount > 0) {
                        onConfirm(paidGallons, calculatedAmount, selectedChannel, notes)
                    }
                },
                enabled = paidGallons > 0 && calculatedAmount > 0,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("confirm_partial_pay_button"),
            ) {
                Text("Record Partial Pay")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Cancel")
            }
        },
    )
}
