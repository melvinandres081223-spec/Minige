package com.melvin.minige.util

import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangPaymentRecord
import kotlin.math.roundToInt

data class PartialGallonStatus(
    val totalGallons: Int,
    val paidGallonsSoFar: Int,
    val remainingUnpaidGallons: Int,
    val remainingBalance: Int,
)

object PartialPaymentHelper {

    private val gallonTagRegex = Regex("\\[Gallons:(\\d+)\\s+Comm:(\\d+)]", RegexOption.IGNORE_CASE)

    fun extractPaidGallons(payment: UtangPaymentRecord): Int {
        val match = gallonTagRegex.find(payment.notes) ?: return 0
        return match.groupValues[1].toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    fun extractCommissionEarned(payment: UtangPaymentRecord): Int {
        val match = gallonTagRegex.find(payment.notes) ?: return 0
        return match.groupValues[2].toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    fun stripPartialGallonTag(notes: String): String {
        return notes
            .replace(gallonTagRegex, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun applyPartialGallonTag(
        notes: String,
        paidGallons: Int,
        commissionEarned: Int,
        channel: PaidChannel,
        turnedOverToRefilling: Boolean = false,
    ): String {
        val hasTurnedOver = turnedOverToRefilling || PaymentBreakdownHelper.isTurnedOverToRefilling(notes)
        val base = PaymentBreakdownHelper.stripTurnedOverToRefillingTag(
            PaymentBreakdownHelper.stripChannelTags(stripPartialGallonTag(notes))
        )
        val gallonTag = "[Gallons:${paidGallons.coerceAtLeast(1)} Comm:${commissionEarned.coerceAtLeast(0)}]"
        val withGallons = if (base.isEmpty()) gallonTag else "$gallonTag $base"
        val withChannel = PaymentBreakdownHelper.applyChannelTag(withGallons, channel)
        return PaymentBreakdownHelper.applyTurnedOverToRefillingTag(withChannel, hasTurnedOver)
    }

    fun getStatus(
        delivery: DeliveryRecord,
        allUtangPayments: List<UtangPaymentRecord>,
    ): PartialGallonStatus {
        val totalGals = delivery.totalGallons.coerceAtLeast(0)
        val remainingBal = delivery.remainingBalance.coerceAtLeast(0)
        if (delivery.paymentType != PaymentType.PAUTANG || remainingBal == 0) {
            return PartialGallonStatus(
                totalGallons = totalGals,
                paidGallonsSoFar = totalGals,
                remainingUnpaidGallons = 0,
                remainingBalance = 0,
            )
        }

        val paymentsForDelivery = allUtangPayments.filter { it.deliveryId == delivery.id }
        val taggedPaidGals = paymentsForDelivery.sumOf { extractPaidGallons(it) }
        val inferredPaidGals = if (taggedPaidGals > 0) {
            taggedPaidGals.coerceIn(0, totalGals)
        } else if (delivery.amountPaid > 0 && delivery.totalAmount > 0 && totalGals > 0) {
            val unitRate = delivery.totalAmount.toDouble() / totalGals.toDouble()
            val est = if (unitRate > 0.0) kotlin.math.floor(delivery.amountPaid / unitRate).toInt() else 0
            est.coerceIn(0, (totalGals - 1).coerceAtLeast(0))
        } else {
            0
        }

        val remainingGals = (totalGals - inferredPaidGals).coerceAtLeast(1)
        return PartialGallonStatus(
            totalGallons = totalGals,
            paidGallonsSoFar = inferredPaidGals,
            remainingUnpaidGallons = remainingGals,
            remainingBalance = remainingBal,
        )
    }

    fun calculateAmountForGallons(
        status: PartialGallonStatus,
        gallonsToPay: Int,
    ): Int {
        if (status.remainingBalance <= 0 || status.remainingUnpaidGallons <= 0 || gallonsToPay <= 0) {
            return 0
        }
        val clampedGallons = gallonsToPay.coerceIn(1, status.remainingUnpaidGallons)
        if (clampedGallons >= status.remainingUnpaidGallons) {
            return status.remainingBalance
        }
        val unitRate = status.remainingBalance.toDouble() / status.remainingUnpaidGallons.toDouble()
        return (unitRate * clampedGallons).roundToInt().coerceIn(1, status.remainingBalance)
    }

    fun taggedPaidGallonsForDate(
        dateKey: String,
        utangPayments: List<UtangPaymentRecord>,
    ): Int = utangPayments
        .filter { it.dateKey == dateKey }
        .sumOf { extractPaidGallons(it) }

    fun taggedCommissionForDate(
        dateKey: String,
        utangPayments: List<UtangPaymentRecord>,
    ): Int = utangPayments
        .filter { it.dateKey == dateKey }
        .sumOf { extractCommissionEarned(it) }

    fun untaggedSettledRemainderGallons(
        delivery: DeliveryRecord,
        allUtangPayments: List<UtangPaymentRecord>,
    ): Int {
        val taggedSum = allUtangPayments
            .filter { it.deliveryId == delivery.id }
            .sumOf { extractPaidGallons(it) }
        return (delivery.totalGallons - taggedSum).coerceAtLeast(0)
    }

    fun untaggedSettledRemainderCommission(
        delivery: DeliveryRecord,
        allUtangPayments: List<UtangPaymentRecord>,
    ): Int {
        val taggedComm = allUtangPayments
            .filter { it.deliveryId == delivery.id }
            .sumOf { extractCommissionEarned(it) }
        return (delivery.commissionEarned - taggedComm).coerceAtLeast(0)
    }
}
