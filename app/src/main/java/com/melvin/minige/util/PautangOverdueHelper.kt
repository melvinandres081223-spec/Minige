package com.melvin.minige.util

import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.PaymentType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class OverduePautangSummary(
    val overdueCount: Int = 0,
    val totalOverdueAmount: Int = 0,
    val overdueIds: Set<String> = emptySet(),
    val daysElapsedByDeliveryId: Map<String, Long> = emptyMap(),
)

object PautangOverdueHelper {

    const val OVERDUE_THRESHOLD_DAYS: Long = 3L

    fun calculateDaysElapsed(
        delivery: DeliveryRecord,
        referenceDate: LocalDate = LocalDate.now(),
    ): Long {
        val deliveryDate = runCatching { LocalDate.parse(delivery.dateKey) }
            .getOrElse {
                if (delivery.createdAtEpochMs > 0L) {
                    Instant.ofEpochMilli(delivery.createdAtEpochMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                } else {
                    referenceDate
                }
            }
        return ChronoUnit.DAYS.between(deliveryDate, referenceDate).coerceAtLeast(0L)
    }

    fun isOverdue(
        delivery: DeliveryRecord,
        referenceDate: LocalDate = LocalDate.now(),
    ): Boolean {
        if (delivery.paymentType != PaymentType.PAUTANG) return false
        if (delivery.isUtangFullyPaid || delivery.remainingBalance <= 0) return false
        return calculateDaysElapsed(delivery, referenceDate) >= OVERDUE_THRESHOLD_DAYS
    }

    fun summarizeOverdue(
        deliveries: List<DeliveryRecord>,
        referenceDate: LocalDate = LocalDate.now(),
    ): OverduePautangSummary {
        val unpaidPautang = deliveries.filter {
            it.paymentType == PaymentType.PAUTANG && !it.isUtangFullyPaid && it.remainingBalance > 0
        }
        if (unpaidPautang.isEmpty()) return OverduePautangSummary()

        val daysMap = mutableMapOf<String, Long>()
        val overdueEntries = mutableListOf<DeliveryRecord>()

        for (entry in unpaidPautang) {
            val days = calculateDaysElapsed(entry, referenceDate)
            daysMap[entry.id] = days
            if (days >= OVERDUE_THRESHOLD_DAYS) {
                overdueEntries.add(entry)
            }
        }

        return OverduePautangSummary(
            overdueCount = overdueEntries.size,
            totalOverdueAmount = overdueEntries.sumOf { it.remainingBalance },
            overdueIds = overdueEntries.map { it.id }.toSet(),
            daysElapsedByDeliveryId = daysMap,
        )
    }
}
