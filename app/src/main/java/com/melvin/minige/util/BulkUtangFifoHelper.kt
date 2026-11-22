package com.melvin.minige.util

import androidx.compose.runtime.Immutable
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.PaymentType
import java.util.Locale

@Immutable
data class BulkUtangSettlementSummary(
    val customerName: String,
    val requestedAmount: Int,
    val totalAppliedAmount: Int,
    val excessAmount: Int,
    val remainingCustomerBalance: Int,
    val affectedCount: Int,
    val fullySettledCount: Int,
    val partiallyPaidCount: Int,
    val settledDateKeys: List<String>,
    val partiallyPaidDateKeys: List<String>,
    val pendingCloudSync: Boolean,
)

@Immutable
data class BulkFifoAllocationStep(
    val deliveryId: String,
    val dateKey: String,
    val totalGallons: Int,
    val remainingBefore: Int,
    val allocatedAmount: Int,
    val remainingAfter: Int,
    val willFullySettle: Boolean,
)

@Immutable
data class BulkFifoPreview(
    val customerName: String,
    val totalOpenBalance: Int,
    val enteredAmount: Int,
    val totalAppliedAmount: Int,
    val excessAmount: Int,
    val remainingCustomerBalanceAfter: Int,
    val steps: List<BulkFifoAllocationStep>,
)

@Immutable
data class CustomerOpenUtangGroup(
    val normalizedKey: String,
    val displayName: String,
    val entriesFifo: List<DeliveryRecord>,
    val combinedBalance: Int,
    val totalGallons: Int,
)

object BulkUtangFifoHelper {

    fun normalizeCustomerKey(customerName: String): String =
        customerName.trim().lowercase(Locale.ENGLISH)

    fun sortFifoOpenUtang(
        deliveries: List<DeliveryRecord>,
        customerName: String,
    ): List<DeliveryRecord> {
        val targetKey = normalizeCustomerKey(customerName)
        if (targetKey.isEmpty()) return emptyList()
        return deliveries
            .filter {
                it.paymentType == PaymentType.PAUTANG &&
                    it.remainingBalance > 0 &&
                    normalizeCustomerKey(it.customerName) == targetKey
            }
            .sortedWith(
                compareBy<DeliveryRecord> { it.dateKey }
                    .thenBy { it.createdAtEpochMs }
                    .thenBy { it.id }
            )
    }

    fun buildMultiEntryCustomerGroups(
        activeUtangList: List<DeliveryRecord>,
    ): Map<String, CustomerOpenUtangGroup> {
        return activeUtangList
            .filter { it.paymentType == PaymentType.PAUTANG && it.remainingBalance > 0 }
            .groupBy { normalizeCustomerKey(it.customerName) }
            .filter { (key, list) -> key.isNotEmpty() && list.size >= 2 }
            .mapValues { (key, list) ->
                val sortedFifo = list.sortedWith(
                    compareBy<DeliveryRecord> { it.dateKey }
                        .thenBy { it.createdAtEpochMs }
                        .thenBy { it.id }
                )
                CustomerOpenUtangGroup(
                    normalizedKey = key,
                    displayName = sortedFifo.first().customerName.trim(),
                    entriesFifo = sortedFifo,
                    combinedBalance = sortedFifo.sumOf { it.remainingBalance },
                    totalGallons = sortedFifo.sumOf { it.totalGallons },
                )
            }
    }

    fun previewFifoAllocation(
        openDeliveriesForCustomer: List<DeliveryRecord>,
        enteredAmount: Int,
    ): BulkFifoPreview {
        val cleanAmount = enteredAmount.coerceAtLeast(0)
        val sorted = openDeliveriesForCustomer
            .filter { it.paymentType == PaymentType.PAUTANG && it.remainingBalance > 0 }
            .sortedWith(
                compareBy<DeliveryRecord> { it.dateKey }
                    .thenBy { it.createdAtEpochMs }
                    .thenBy { it.id }
            )
        val displayName = sorted.firstOrNull()?.customerName?.trim().orEmpty()
        val totalOpenBalance = sorted.sumOf { it.remainingBalance }
        var remainingToApply = cleanAmount
        var appliedSum = 0

        val steps = sorted.map { entry ->
            val before = entry.remainingBalance
            val allocated = minOf(remainingToApply, before).coerceAtLeast(0)
            remainingToApply -= allocated
            appliedSum += allocated
            val after = (before - allocated).coerceAtLeast(0)
            BulkFifoAllocationStep(
                deliveryId = entry.id,
                dateKey = entry.dateKey,
                totalGallons = entry.totalGallons,
                remainingBefore = before,
                allocatedAmount = allocated,
                remainingAfter = after,
                willFullySettle = allocated > 0 && after == 0,
            )
        }

        return BulkFifoPreview(
            customerName = displayName,
            totalOpenBalance = totalOpenBalance,
            enteredAmount = cleanAmount,
            totalAppliedAmount = appliedSum,
            excessAmount = (cleanAmount - appliedSum).coerceAtLeast(0),
            remainingCustomerBalanceAfter = (totalOpenBalance - appliedSum).coerceAtLeast(0),
            steps = steps,
        )
    }
}
