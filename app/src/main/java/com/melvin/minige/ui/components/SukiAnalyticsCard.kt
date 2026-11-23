package com.melvin.minige.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.MinigeDateUtils
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.util.CustomerClassification
import com.melvin.minige.util.DeliveryVoiceParser
import com.melvin.minige.util.PartialPaymentHelper
import java.time.LocalDate

@Immutable
data class WeeklyCustomerTally(
    val customerId: String,
    val customerName: String,
    val classification: CustomerClassification?,
    val totalGallons: Int,
    val paidGallons: Int,
    val commissionEarned: Int,
    val orderCount: Int,
)

@Immutable
data class WeeklySukiAnalytics(
    val outletGallons: Int = 0,
    val outletPaidCommission: Int = 0,
    val residentialGallons: Int = 0,
    val residentialPaidCommission: Int = 0,
    val unclassifiedGallons: Int = 0,
    val unclassifiedPaidCommission: Int = 0,
    val topSuki: List<WeeklyCustomerTally> = emptyList(),
)

object SukiAnalyticsHelper {

    fun compute(
        weekDates: List<LocalDate>,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
        topLimit: Int = 5,
    ): WeeklySukiAnalytics {
        if (weekDates.isEmpty()) return WeeklySukiAnalytics()
        val weekKeys = weekDates.map { MinigeDateUtils.toKey(it) }.toSet()
        val deliveriesById = deliveries.associateBy { it.id }

        var outletGallons = 0
        var outletComm = 0
        var residentialGallons = 0
        var residentialComm = 0
        var unclassifiedGallons = 0
        var unclassifiedComm = 0

        data class MutableTally(
            val id: String,
            var name: String,
            var classification: CustomerClassification?,
            var totalGallons: Int = 0,
            var paidGallons: Int = 0,
            var commissionEarned: Int = 0,
            var orderCount: Int = 0,
        )

        val tallies = linkedMapOf<String, MutableTally>()

        fun getOrCreateTally(
            customerName: String,
            classification: CustomerClassification?,
        ): MutableTally {
            val cid = MinigeCustomerUtils.normalizeCustomerId(customerName)
            val existing = tallies[cid]
            return if (existing != null) {
                if (existing.classification == null && classification != null) {
                    existing.classification = classification
                }
                existing
            } else {
                MutableTally(
                    id = cid,
                    name = customerName,
                    classification = classification,
                ).also { tallies[cid] = it }
            }
        }

        deliveries.filter { it.dateKey in weekKeys }.forEach { delivery ->
            val cls = DeliveryVoiceParser.extractClassification(delivery.notes)
            val gals = delivery.totalGallons
            when (cls) {
                CustomerClassification.OUTLET -> outletGallons += gals
                CustomerClassification.RESIDENTIAL -> residentialGallons += gals
                null -> unclassifiedGallons += gals
            }

            val tally = getOrCreateTally(delivery.customerName, cls)
            tally.totalGallons += gals
            tally.orderCount += 1

            if (delivery.paymentType == PaymentType.CASH ||
                delivery.paymentType == PaymentType.BANK_DEPOSIT ||
                delivery.paymentType == PaymentType.PAID_WALK_IN
            ) {
                tally.paidGallons += gals
                tally.commissionEarned += delivery.commissionEarned
                when (cls) {
                    CustomerClassification.OUTLET -> outletComm += delivery.commissionEarned
                    CustomerClassification.RESIDENTIAL -> residentialComm += delivery.commissionEarned
                    null -> unclassifiedComm += delivery.commissionEarned
                }
            }
        }

        // Credit partial utang payments made inside weekKeys
        utangPayments.filter { it.dateKey in weekKeys }.forEach { payment ->
            val chunkGals = PartialPaymentHelper.extractPaidGallons(payment)
            val chunkComm = PartialPaymentHelper.extractCommissionEarned(payment)
            if (chunkGals > 0 || chunkComm > 0) {
                val parent = deliveriesById[payment.deliveryId] ?: return@forEach
                val cls = DeliveryVoiceParser.extractClassification(parent.notes)
                when (cls) {
                    CustomerClassification.OUTLET -> outletComm += chunkComm
                    CustomerClassification.RESIDENTIAL -> residentialComm += chunkComm
                    null -> unclassifiedComm += chunkComm
                }
                val tally = getOrCreateTally(parent.customerName, cls)
                tally.paidGallons += chunkGals
                tally.commissionEarned += chunkComm
            }
        }

        // Credit untagged settled utang remainders settled inside weekKeys
        deliveries.filter {
            it.paymentType == PaymentType.PAUTANG &&
                it.isUtangFullyPaid &&
                it.settledDateKey in weekKeys
        }.forEach { settled ->
            val remGals = PartialPaymentHelper.untaggedSettledRemainderGallons(settled, utangPayments)
            val remComm = PartialPaymentHelper.untaggedSettledRemainderCommission(settled, utangPayments)
            if (remGals > 0 || remComm > 0) {
                val cls = DeliveryVoiceParser.extractClassification(settled.notes)
                when (cls) {
                    CustomerClassification.OUTLET -> outletComm += remComm
                    CustomerClassification.RESIDENTIAL -> residentialComm += remComm
                    null -> unclassifiedComm += remComm
                }
                val tally = getOrCreateTally(settled.customerName, cls)
                tally.paidGallons += remGals
                tally.commissionEarned += remComm
            }
        }

        val rankedTop = tallies.values
            .filter { it.totalGallons > 0 }
            .sortedWith(
                compareByDescending<MutableTally> { it.totalGallons }
                    .thenByDescending { it.commissionEarned }
                    .thenBy { it.name.lowercase() }
            )
            .take(topLimit)
            .map {
                WeeklyCustomerTally(
                    customerId = it.id,
                    customerName = it.name,
                    classification = it.classification,
                    totalGallons = it.totalGallons,
                    paidGallons = it.paidGallons,
                    commissionEarned = it.commissionEarned,
                    orderCount = it.orderCount,
                )
            }

        return WeeklySukiAnalytics(
            outletGallons = outletGallons,
            outletPaidCommission = outletComm,
            residentialGallons = residentialGallons,
            residentialPaidCommission = residentialComm,
            unclassifiedGallons = unclassifiedGallons,
            unclassifiedPaidCommission = unclassifiedComm,
            topSuki = rankedTop,
        )
    }
}

@Composable
fun SukiAnalyticsCard(
    analytics: WeeklySukiAnalytics,
    modifier: Modifier = Modifier,
) {
    val classifiedTotal = (analytics.outletGallons + analytics.residentialGallons).coerceAtLeast(1)
    val outletFraction = (analytics.outletGallons.toFloat() / classifiedTotal.toFloat()).coerceIn(0f, 1f)
    val residentialFraction = (1f - outletFraction).coerceIn(0f, 1f)

    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("weekly_suki_analytics_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Outlet vs. Residential Breakdown",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SegmentSummaryTile(
                    icon = Icons.Rounded.Storefront,
                    title = "Outlet (Canteen)",
                    gallons = analytics.outletGallons,
                    commission = analytics.outletPaidCommission,
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                SegmentSummaryTile(
                    icon = Icons.Rounded.Home,
                    title = "Residential",
                    gallons = analytics.residentialGallons,
                    commission = analytics.residentialPaidCommission,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }

            if (analytics.outletGallons + analytics.residentialGallons > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (outletFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(outletFraction.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    if (residentialFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(residentialFraction.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                    }
                }
            }

            if (analytics.unclassifiedGallons > 0) {
                Text(
                    text = "Untagged entries: ${analytics.unclassifiedGallons} gal (₱${analytics.unclassifiedPaidCommission} comm)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.WorkspacePremium,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Top Weekly Suki",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            if (analytics.topSuki.isEmpty()) {
                Text(
                    text = "No customer deliveries recorded for this week yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                analytics.topSuki.forEachIndexed { index, suki ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                            Text(
                                text = suki.customerName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            suki.classification?.let { cls ->
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                ) {
                                    Text(
                                        text = cls.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${suki.totalGallons}g • ₱${suki.commissionEarned}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentSummaryTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    gallons: Int,
    commission: Int,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$gallons gal",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = contentColor,
            )
            Text(
                text = "₱$commission comm",
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.85f),
            )
        }
    }
}
