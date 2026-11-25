package com.melvin.minige.ui.components

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.MinigeSettings
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.CustomerDirectoryHelper
import com.melvin.minige.util.DeliveryVoiceParser
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PaymentBreakdownHelper
import com.melvin.minige.util.SmallGallonHelper
import kotlin.math.roundToInt

@Immutable
data class PickupDialogInitialPrices(
    val isMixedOrder: Boolean,
    val isPurifiedOnly: Boolean,
    val isAlkalineOnly: Boolean,
    val purifiedPrice: Double,
    val alkalinePrice: Double,
    val singleUnitPrice: Double,
)

object PickupChecklistHelper {
    fun filterPendingPickupsForDay(
        deliveriesForDay: List<DeliveryRecord>,
        searchQuery: String = "",
        selectedChip: DeliveryFilterChip = DeliveryFilterChip.ALL,
    ): List<DeliveryRecord> {
        return DeliveryFilterHelper.filterDeliveries(
            deliveries = deliveriesForDay,
            searchQuery = searchQuery,
            selectedChip = selectedChip,
        ).filter { it.paymentType == PaymentType.PICK_UP }
    }

    fun resolveInitialPrices(
        delivery: DeliveryRecord,
        settings: MinigeSettings,
        matchedCustomer: CustomerRecord?,
        isSmallGallon: Boolean,
        smallGallonPrice: Double = SmallGallonHelper.DEFAULT_PRICE,
    ): PickupDialogInitialPrices {
        val isMixed = delivery.purifiedGallons > 0 && delivery.alkalineGallons > 0
        val isAlkalineOnly = delivery.alkalineGallons > 0 && delivery.purifiedGallons == 0
        val isPurifiedOnly = delivery.purifiedGallons > 0 && delivery.alkalineGallons == 0
        if (isSmallGallon) {
            return PickupDialogInitialPrices(
                isMixedOrder = isMixed,
                isPurifiedOnly = isPurifiedOnly,
                isAlkalineOnly = isAlkalineOnly,
                purifiedPrice = smallGallonPrice,
                alkalinePrice = smallGallonPrice,
                singleUnitPrice = smallGallonPrice,
            )
        }
        val savedCustomerPurified = matchedCustomer?.lastUnitPrice?.takeIf { it >= 0.0 }
        val resolvedPurified = savedCustomerPurified
            ?: delivery.purifiedUnitPrice.takeIf { it > 0 }?.toDouble()
            ?: settings.purifiedPrice.toDouble()
        val resolvedAlkaline = delivery.alkalineUnitPrice.takeIf { it > 0 }?.toDouble()
            ?: settings.alkalinePrice.toDouble()
        val resolvedSingle = if (isAlkalineOnly) resolvedAlkaline else resolvedPurified
        return PickupDialogInitialPrices(
            isMixedOrder = isMixed,
            isPurifiedOnly = isPurifiedOnly,
            isAlkalineOnly = isAlkalineOnly,
            purifiedPrice = resolvedPurified,
            alkalinePrice = resolvedAlkaline,
            singleUnitPrice = resolvedSingle,
        )
    }

    fun computePreviewBill(
        delivery: DeliveryRecord,
        paymentType: PaymentType,
        purifiedUnitPrice: Double,
        alkalineUnitPrice: Double,
    ): Int {
        if (paymentType == PaymentType.PAID_WALK_IN || paymentType == PaymentType.PICK_UP) return 0
        val safePurified = purifiedUnitPrice.coerceAtLeast(0.0)
        val safeAlkaline = alkalineUnitPrice.coerceAtLeast(0.0)
        return ((delivery.purifiedGallons * safePurified) + (delivery.alkalineGallons * safeAlkaline)).roundToInt()
    }

    fun computePreviewCommission(
        delivery: DeliveryRecord,
        paymentType: PaymentType,
        commissionRate: Double,
        hasPaidWalkInCommission: Boolean,
    ): Int {
        val totalGals = delivery.totalGallons
        return when (paymentType) {
            PaymentType.CASH, PaymentType.BANK_DEPOSIT ->
                CommissionRateHistory.calculateCommissionAmount(totalGals, commissionRate)
            PaymentType.PAID_WALK_IN -> if (hasPaidWalkInCommission) {
                CommissionRateHistory.calculateCommissionAmount(totalGals, commissionRate)
            } else {
                0
            }
            PaymentType.PAUTANG, PaymentType.PICK_UP -> 0
        }
    }

    fun buildConvertedDeliveryNotes(
        existingNotes: String,
        targetPaymentType: PaymentType,
        hasCommission: Boolean,
        isSmallGallon: Boolean? = null,
    ): String {
        val withChannel = when (targetPaymentType) {
            PaymentType.CASH -> PaymentBreakdownHelper.applyChannelTag(existingNotes, PaidChannel.CASH)
            PaymentType.BANK_DEPOSIT -> PaymentBreakdownHelper.applyChannelTag(existingNotes, PaidChannel.BANK_ONLINE)
            else -> PaymentBreakdownHelper.stripChannelTags(existingNotes)
        }
        val withWalkIn = if (targetPaymentType == PaymentType.PAID_WALK_IN) {
            PaymentBreakdownHelper.applyPaidWalkInTags(withChannel, hasCommission = hasCommission)
        } else {
            PaymentBreakdownHelper.stripPaidWalkInTags(withChannel)
        }
        return if (isSmallGallon != null) {
            SmallGallonHelper.applyMaliitTag(withWalkIn, isSmallGallon)
        } else {
            withWalkIn
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodayPickupChecklistSheet(
    pickups: List<DeliveryRecord>,
    onCheckOffPickup: (DeliveryRecord) -> Unit,
    onNewDirectDelivery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val amberAccent = MaterialTheme.colorScheme.tertiary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("pickup_checklist_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "I-check Kung Na-deliver Na",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "I-tap ang checkbox kapag nai-deliver na sa suki para pumasok sa Delivered & Commission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        text = "${pickups.size} pending",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            if (pickups.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocalShipping,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Walang pending na Pick-Up sa araw na ito. Gamitin ang button sa ibaba para sa bagong direct delivery.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = pickups,
                        key = { "checklist_pickup_${it.id}" },
                        contentType = { "checklist_pickup_row" },
                    ) { delivery ->
                        val area = CustomerDirectoryHelper.extractRouteArea(delivery.notes)
                        OutlinedCard(
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCheckOffPickup(delivery) }
                                .testTag("checklist_pickup_card_${delivery.id}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .drawBehind {
                                        drawRect(
                                            color = amberAccent,
                                            size = Size(width = 5.dp.toPx(), height = size.height),
                                        )
                                    }
                                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = delivery.customerName,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                        ) {
                                            Text(
                                                text = "PICKED UP",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            )
                                        }
                                        if (!area.isNullOrBlank()) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                            ) {
                                                Text(
                                                    text = area,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = formatGallonSummary(delivery),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Checkbox(
                                    checked = false,
                                    onCheckedChange = { onCheckOffPickup(delivery) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("checklist_pickup_checkbox_${delivery.id}"),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Button(
                onClick = onNewDirectDelivery,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("new_direct_delivery_button"),
            ) {
                Text(
                    text = "+ Bagong Direct Delivery",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmPickupDeliveredDialog(
    delivery: DeliveryRecord,
    settings: MinigeSettings,
    customers: List<CustomerRecord>,
    activeCommissionRate: Double,
    smallGallonPrice: Double = SmallGallonHelper.DEFAULT_PRICE,
    smallGallonCommissionRate: Double = SmallGallonHelper.DEFAULT_COMMISSION_RATE,
    onDismiss: () -> Unit,
    onConfirm: (
        paymentType: PaymentType,
        notes: String,
        customUnitPrice: Double?,
        customPurifiedUnitPrice: Double?,
        customAlkalineUnitPrice: Double?,
        hasCommission: Boolean,
    ) -> Unit,
) {
    val customerId = remember(delivery.customerName) {
        MinigeCustomerUtils.normalizeCustomerId(delivery.customerName)
    }
    val matchedCustomer = remember(customerId, customers) {
        if (customerId.isEmpty()) null else customers.firstOrNull { it.id == customerId }
    }
    var isSmallGallon by remember(delivery.id) {
        mutableStateOf(SmallGallonHelper.isMaliitTagged(delivery.notes))
    }
    val initialPrices = remember(
        delivery.id,
        matchedCustomer?.lastUnitPrice,
        delivery.purifiedUnitPrice,
        delivery.alkalineUnitPrice,
        settings,
        smallGallonPrice,
    ) {
        PickupChecklistHelper.resolveInitialPrices(
            delivery = delivery,
            settings = settings,
            matchedCustomer = matchedCustomer,
            isSmallGallon = SmallGallonHelper.isMaliitTagged(delivery.notes),
            smallGallonPrice = smallGallonPrice,
        )
    }

    var selectedPaymentType by remember(delivery.id) { mutableStateOf(PaymentType.CASH) }
    var hasPaidWalkInCommission by remember(delivery.id) { mutableStateOf(true) }
    var singlePriceText by remember(delivery.id, initialPrices.singleUnitPrice) {
        mutableStateOf(MinigeCustomerUtils.formatPrice(initialPrices.singleUnitPrice))
    }
    var purifiedPriceText by remember(delivery.id, initialPrices.purifiedPrice) {
        mutableStateOf(MinigeCustomerUtils.formatPrice(initialPrices.purifiedPrice))
    }
    var alkalinePriceText by remember(delivery.id, initialPrices.alkalinePrice) {
        mutableStateOf(MinigeCustomerUtils.formatPrice(initialPrices.alkalinePrice))
    }
    var formError by remember(delivery.id) { mutableStateOf<String?>(null) }

    val parsedPurified = if (initialPrices.isMixedOrder) {
        purifiedPriceText.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: initialPrices.purifiedPrice
    } else {
        singlePriceText.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: initialPrices.singleUnitPrice
    }
    val parsedAlkaline = if (initialPrices.isMixedOrder) {
        alkalinePriceText.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: initialPrices.alkalinePrice
    } else {
        singlePriceText.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: initialPrices.singleUnitPrice
    }
    val previewBill = PickupChecklistHelper.computePreviewBill(
        delivery = delivery,
        paymentType = selectedPaymentType,
        purifiedUnitPrice = parsedPurified,
        alkalineUnitPrice = parsedAlkaline,
    )
    val effectiveRate = if (isSmallGallon) smallGallonCommissionRate else activeCommissionRate
    val previewCommission = PickupChecklistHelper.computePreviewCommission(
        delivery = delivery,
        paymentType = selectedPaymentType,
        commissionRate = effectiveRate,
        hasPaidWalkInCommission = hasPaidWalkInCommission,
    )
    val rateFormatted = CommissionRateHistory.formatRate(effectiveRate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Na-deliver Na?",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${delivery.customerName} • ${formatGallonSummary(delivery)}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    text = "Piliin ang bayad ng suki:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = selectedPaymentType == PaymentType.CASH,
                        onClick = {
                            selectedPaymentType = PaymentType.CASH
                            formError = null
                        },
                        label = { Text("Cash") },
                        modifier = Modifier.testTag("confirm_pickup_type_CASH"),
                    )
                    FilterChip(
                        selected = selectedPaymentType == PaymentType.BANK_DEPOSIT,
                        onClick = {
                            selectedPaymentType = PaymentType.BANK_DEPOSIT
                            formError = null
                        },
                        label = { Text("Bank / Online") },
                        modifier = Modifier.testTag("confirm_pickup_type_BANK"),
                    )
                    FilterChip(
                        selected = selectedPaymentType == PaymentType.PAID_WALK_IN,
                        onClick = {
                            selectedPaymentType = PaymentType.PAID_WALK_IN
                            formError = null
                        },
                        label = { Text(PaymentType.PAID_WALK_IN.label) },
                        modifier = Modifier.testTag("confirm_pickup_type_PAID_WALK_IN"),
                    )
                    FilterChip(
                        selected = selectedPaymentType == PaymentType.PAUTANG,
                        onClick = {
                            selectedPaymentType = PaymentType.PAUTANG
                            formError = null
                        },
                        label = { Text(PaymentType.PAUTANG.label) },
                        modifier = Modifier.testTag("confirm_pickup_type_PAUTANG"),
                    )
                    FilterChip(
                        selected = isSmallGallon,
                        onClick = {
                            val next = !isSmallGallon
                            isSmallGallon = next
                            val updatedPrices = PickupChecklistHelper.resolveInitialPrices(
                                delivery = delivery,
                                settings = settings,
                                matchedCustomer = matchedCustomer,
                                isSmallGallon = next,
                                smallGallonPrice = smallGallonPrice,
                            )
                            singlePriceText = MinigeCustomerUtils.formatPrice(updatedPrices.singleUnitPrice)
                            purifiedPriceText = MinigeCustomerUtils.formatPrice(updatedPrices.purifiedPrice)
                            alkalinePriceText = MinigeCustomerUtils.formatPrice(updatedPrices.alkalinePrice)
                            formError = null
                        },
                        label = { Text("Maliit (₱${MinigeCustomerUtils.formatPrice(smallGallonPrice)})") },
                        modifier = Modifier.testTag("confirm_pickup_maliit_chip"),
                    )
                }

                if (selectedPaymentType == PaymentType.PAID_WALK_IN) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        ) {
                            Text(
                                text = "May Commission (₱$rateFormatted/gal)",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = if (hasPaidWalkInCommission) "May commission sa delivery" else "₱0 commission",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = hasPaidWalkInCommission,
                            onCheckedChange = { hasPaidWalkInCommission = it },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("confirm_pickup_walkin_comm_switch"),
                        )
                    }
                } else if (initialPrices.isMixedOrder) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = purifiedPriceText,
                            onValueChange = { raw ->
                                purifiedPriceText = sanitizePickupPrice(raw)
                                formError = null
                            },
                            label = { Text("Purified Price (₱)") },
                            supportingText = { Text("${delivery.purifiedGallons} gal") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("confirm_pickup_purified_price_input"),
                        )
                        OutlinedTextField(
                            value = alkalinePriceText,
                            onValueChange = { raw ->
                                alkalinePriceText = sanitizePickupPrice(raw)
                                formError = null
                            },
                            label = { Text("Alkaline Price (₱)") },
                            supportingText = { Text("${delivery.alkalineGallons} gal") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("confirm_pickup_alkaline_price_input"),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = singlePriceText,
                        onValueChange = { raw ->
                            singlePriceText = sanitizePickupPrice(raw)
                            formError = null
                        },
                        label = { Text("Unit Price (₱/gal)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("confirm_pickup_unit_price_input"),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = when (selectedPaymentType) {
                                PaymentType.PAID_WALK_IN -> "Cash to Remit: ₱0 (Paid at station)"
                                PaymentType.BANK_DEPOSIT -> "Bank / Online Bill: ₱$previewBill (₱0 Cash to Remit)"
                                PaymentType.PAUTANG -> "Pautang Balance: ₱$previewBill"
                                else -> "Total Bill: ₱$previewBill"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = when (selectedPaymentType) {
                                PaymentType.PAUTANG -> "Commission: ₱0 (Awarded on paid gallons)"
                                else -> "Commission Today: ₱$previewCommission"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (formError != null) {
                    Text(
                        text = formError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val singleToSave: Double?
                    val purifiedToSave: Double?
                    val alkalineToSave: Double?
                    if (selectedPaymentType == PaymentType.PAID_WALK_IN) {
                        singleToSave = null
                        purifiedToSave = null
                        alkalineToSave = null
                    } else if (initialPrices.isMixedOrder) {
                        val validPurified = purifiedPriceText.toDoubleOrNull()?.takeIf { it >= 0.0 }
                        val validAlkaline = alkalinePriceText.toDoubleOrNull()?.takeIf { it >= 0.0 }
                        if (validPurified == null || validAlkaline == null) {
                            formError = "Please enter valid Purified and Alkaline unit prices."
                            return@Button
                        }
                        singleToSave = null
                        purifiedToSave = validPurified
                        alkalineToSave = validAlkaline
                    } else {
                        val validSingle = singlePriceText.toDoubleOrNull()?.takeIf { it >= 0.0 }
                        if (validSingle == null) {
                            formError = "Please enter a valid unit price."
                            return@Button
                        }
                        singleToSave = validSingle
                        purifiedToSave = null
                        alkalineToSave = null
                    }
                    val finalNotes = PickupChecklistHelper.buildConvertedDeliveryNotes(
                        existingNotes = delivery.notes,
                        targetPaymentType = selectedPaymentType,
                        hasCommission = if (selectedPaymentType == PaymentType.PAID_WALK_IN) hasPaidWalkInCommission else true,
                        isSmallGallon = isSmallGallon,
                    )
                    onConfirm(
                        selectedPaymentType,
                        finalNotes,
                        singleToSave,
                        purifiedToSave,
                        alkalineToSave,
                        if (selectedPaymentType == PaymentType.PAID_WALK_IN) hasPaidWalkInCommission else true,
                    )
                },
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("confirm_pickup_delivered_button"),
            ) {
                Text("Confirm Delivered")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinnedPickupDeliveryCard(
    delivery: DeliveryRecord,
    onMarkDelivered: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val classification = DeliveryVoiceParser.extractClassification(delivery.notes)
    val routeArea = CustomerDirectoryHelper.extractRouteArea(delivery.notes)
    val amberAccent = MaterialTheme.colorScheme.tertiary

    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .testTag("delivery_card_${delivery.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = amberAccent,
                        size = Size(width = 5.dp.toPx(), height = size.height),
                    )
                }
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = delivery.customerName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Text(
                            text = "PICKED UP",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (SmallGallonHelper.isMaliitTagged(delivery.notes)) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Text(
                                text = "Maliit",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (classification != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Text(
                                text = classification.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (!routeArea.isNullOrBlank()) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Text(
                                text = routeArea,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Icon(
                        imageVector = if (delivery.pendingSync) Icons.Rounded.CloudUpload else Icons.Rounded.CloudDone,
                        contentDescription = if (delivery.pendingSync) "Pending cloud sync" else "Synced",
                        tint = if (delivery.pendingSync) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.CenterVertically),
                    )
                }

                Text(
                    text = formatGallonSummary(delivery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = "Pick-up only • I-check kapag na-deliver na",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("edit_delivery_${delivery.id}"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Edit ${delivery.customerName}",
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete ${delivery.customerName}",
                )
            }

            Checkbox(
                checked = false,
                onCheckedChange = { onMarkDelivered() },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("pinned_pickup_checkbox_${delivery.id}"),
            )
        }
    }
}

@Composable
fun DeliveredTodayDrawerHeader(
    customerCount: Int,
    totalDeliveredGals: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val galUnit = if (totalDeliveredGals == 1) "gal" else "gals"
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(onClick = onToggleExpanded)
            .testTag("delivered_today_drawer_toggle"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "✅ Delivered Today ($customerCount suki • $totalDeliveredGals $galUnit)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (expanded) "I-tap para itago ang listahan" else "I-tap para makita ang mga nai-deliver na",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse delivered today" else "Expand delivered today",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatGallonSummary(delivery: DeliveryRecord): String = buildString {
    if (delivery.purifiedGallons > 0) append("${delivery.purifiedGallons} Purified")
    if (delivery.purifiedGallons > 0 && delivery.alkalineGallons > 0) append(" + ")
    if (delivery.alkalineGallons > 0) append("${delivery.alkalineGallons} Alkaline")
    append(" (${delivery.totalGallons} gal")
    if (delivery.totalGallons != 1) append("s")
    append(")")
}

private fun sanitizePickupPrice(raw: String): String {
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
