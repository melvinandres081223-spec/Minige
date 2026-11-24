package com.melvin.minige.util

import androidx.compose.runtime.Immutable
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord

enum class PaidChannel(
    val label: String,
    val storedPaymentType: PaymentType,
    val storedUtangMethod: UtangMethod,
    val noteTag: String?,
) {
    CASH(
        label = "Cash",
        storedPaymentType = PaymentType.CASH,
        storedUtangMethod = UtangMethod.CASH,
        noteTag = null,
    ),
    BANK_ONLINE(
        label = "Bank / Online",
        storedPaymentType = PaymentType.BANK_DEPOSIT,
        storedUtangMethod = UtangMethod.BANK_DEPOSIT,
        noteTag = "[Bank / Online]",
    ),
}

@Immutable
data class PaymentChannelBreakdown(
    val cashPaidGallons: Int,
    val bankTransferGallons: Int,
    val bankDepositGallons: Int,
    val cashCollected: Int,
    val cashExpenses: Int,
    val cashToRemit: Int,
    val bankTransferAmount: Int,
    val bankDepositAmount: Int,
    val bankPaidTotal: Int,
    val commissionFromCash: Int,
    val commissionFromBank: Int,
    val totalPaidCommission: Int,
    val bankOnlineGallons: Int = bankTransferGallons + bankDepositGallons,
    val paidWalkInGallons: Int = 0,
    val commissionFromPaidWalkIn: Int = 0,
    val cashAdvanceTotal: Int = 0,
    val pautangTurnedOverToRefilling: Int = 0,
)

object PaymentBreakdownHelper {

    private const val TAG_BANK_ONLINE = "[Bank / Online]"
    private const val TAG_BANK_TRANSFER = "[Bank Transfer]"
    private const val TAG_BANK_DEPOSIT = "[Bank Deposit]"
    const val TAG_PAID_WALK_IN = "[Paid Walk-in]"
    const val TAG_NO_COMMISSION = "[No Commission]"
    const val TAG_CASH_ADVANCE = "[Cash Advance]"
    const val TAG_TURNED_OVER_REFILLING = "[Turned Over to Refilling]"

    fun resolveDeliveryChannel(delivery: DeliveryRecord): PaidChannel? {
        return when (delivery.paymentType) {
            PaymentType.CASH -> PaidChannel.CASH
            PaymentType.BANK_DEPOSIT -> PaidChannel.BANK_ONLINE
            PaymentType.PAID_WALK_IN, PaymentType.PAUTANG, PaymentType.PICK_UP -> null
        }
    }

    fun resolveUtangPaymentChannel(payment: UtangPaymentRecord): PaidChannel {
        return when (payment.method) {
            UtangMethod.CASH -> PaidChannel.CASH
            UtangMethod.BANK_DEPOSIT -> PaidChannel.BANK_ONLINE
        }
    }

    fun stripChannelTags(notes: String): String {
        return notes
            .replace(TAG_BANK_ONLINE, "", ignoreCase = true)
            .replace(TAG_BANK_TRANSFER, "", ignoreCase = true)
            .replace(TAG_BANK_DEPOSIT, "", ignoreCase = true)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun applyChannelTag(notes: String, channel: PaidChannel): String {
        val cleaned = stripChannelTags(notes)
        val tag = channel.noteTag ?: return cleaned
        return if (cleaned.isEmpty()) tag else "$tag $cleaned".take(300)
    }

    fun hasNoCommissionTag(notes: String): Boolean =
        notes.contains(TAG_NO_COMMISSION, ignoreCase = true)

    fun isPaidWalkInTagged(notes: String): Boolean =
        notes.contains(TAG_PAID_WALK_IN, ignoreCase = true)

    fun stripPaidWalkInTags(notes: String): String {
        return notes
            .replace(TAG_PAID_WALK_IN, "", ignoreCase = true)
            .replace(TAG_NO_COMMISSION, "", ignoreCase = true)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun applyPaidWalkInTags(
        notes: String,
        hasCommission: Boolean,
        includeTypeTagForCloud: Boolean = false,
    ): String {
        val cleaned = stripPaidWalkInTags(stripChannelTags(notes))
        val tags = buildList {
            if (includeTypeTagForCloud) add(TAG_PAID_WALK_IN)
            if (!hasCommission) add(TAG_NO_COMMISSION)
        }
        if (tags.isEmpty()) return cleaned.take(300)
        val prefix = tags.joinToString(" ")
        return if (cleaned.isEmpty()) prefix else "$prefix $cleaned".take(300)
    }

    fun resolvePaidWalkInHasCommission(delivery: DeliveryRecord): Boolean {
        if (delivery.paymentType != PaymentType.PAID_WALK_IN) return true
        return !hasNoCommissionTag(delivery.notes) && delivery.commissionEarned > 0
    }

    fun isCashAdvanceTagged(notes: String): Boolean =
        notes.contains(TAG_CASH_ADVANCE, ignoreCase = true)

    fun stripCashAdvanceTag(notes: String): String {
        return notes
            .replace(TAG_CASH_ADVANCE, "", ignoreCase = true)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun applyCashAdvanceTag(notes: String): String {
        val cleaned = stripCashAdvanceTag(notes)
        return if (cleaned.isEmpty()) TAG_CASH_ADVANCE else "$TAG_CASH_ADVANCE $cleaned".take(300)
    }

    fun isTurnedOverToRefilling(notes: String): Boolean =
        notes.contains(TAG_TURNED_OVER_REFILLING, ignoreCase = true)

    fun stripTurnedOverToRefillingTag(notes: String): String {
        return notes
            .replace(TAG_TURNED_OVER_REFILLING, "", ignoreCase = true)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun applyTurnedOverToRefillingTag(notes: String, turnedOver: Boolean): String {
        val cleaned = stripTurnedOverToRefillingTag(notes)
        if (!turnedOver) return cleaned.take(300)
        val maxRemaining = (300 - TAG_TURNED_OVER_REFILLING.length - 1).coerceAtLeast(0)
        val trimmed = cleaned.take(maxRemaining).trimEnd()
        return if (trimmed.isEmpty()) TAG_TURNED_OVER_REFILLING else "$trimmed $TAG_TURNED_OVER_REFILLING"
    }

    fun displayMethodLabel(delivery: DeliveryRecord): String {
        return when (delivery.paymentType) {
            PaymentType.BANK_DEPOSIT -> PaidChannel.BANK_ONLINE.label
            PaymentType.PAID_WALK_IN -> PaymentType.PAID_WALK_IN.label
            PaymentType.CASH -> PaidChannel.CASH.label
            PaymentType.PAUTANG -> PaymentType.PAUTANG.label
            PaymentType.PICK_UP -> PaymentType.PICK_UP.label
        }
    }

    fun calculateBreakdown(
        dateKey: String,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
        expenses: List<ExpenseRecord>,
    ): PaymentChannelBreakdown {
        val deliveriesForDate = deliveries.filter { it.dateKey == dateKey }
        val paymentsForDate = utangPayments.filter { it.dateKey == dateKey }
        val expenseEntriesForDate = expenses.filter { it.dateKey == dateKey }
        val routeExpensesTotal = expenseEntriesForDate
            .filter { !it.category.isCashAdvance }
            .sumOf { it.amount }
        val cashAdvanceTotal = expenseEntriesForDate
            .filter { it.category.isCashAdvance }
            .sumOf { it.amount }

        val directCash = deliveriesForDate.filter { resolveDeliveryChannel(it) == PaidChannel.CASH }
        val directBankOnline = deliveriesForDate.filter { resolveDeliveryChannel(it) == PaidChannel.BANK_ONLINE }
        val directPaidWalkIn = deliveriesForDate.filter { it.paymentType == PaymentType.PAID_WALK_IN }

        val settledUtangToday = deliveries.filter {
            it.paymentType == PaymentType.PAUTANG &&
                it.isUtangFullyPaid &&
                it.settledDateKey == dateKey
        }
        val settledByDeliveryId = paymentsForDate.groupBy { it.deliveryId }

        var utangCashGals = 0
        var utangBankGals = 0
        var utangCashComm = 0
        var utangBankComm = 0

        paymentsForDate.forEach { payment ->
            val chunkGals = PartialPaymentHelper.extractPaidGallons(payment)
            if (chunkGals > 0) {
                val chunkComm = PartialPaymentHelper.extractCommissionEarned(payment)
                when (resolveUtangPaymentChannel(payment)) {
                    PaidChannel.CASH -> {
                        utangCashGals += chunkGals
                        utangCashComm += chunkComm
                    }
                    PaidChannel.BANK_ONLINE -> {
                        utangBankGals += chunkGals
                        utangBankComm += chunkComm
                    }
                }
            }
        }

        settledUtangToday.forEach { utang ->
            val remGals = PartialPaymentHelper.untaggedSettledRemainderGallons(utang, utangPayments)
            val remComm = PartialPaymentHelper.untaggedSettledRemainderCommission(utang, utangPayments)
            if (remGals > 0 || remComm > 0) {
                val lastChannel = settledByDeliveryId[utang.id]
                    ?.lastOrNull()
                    ?.let { resolveUtangPaymentChannel(it) }
                    ?: PaidChannel.CASH
                when (lastChannel) {
                    PaidChannel.CASH -> {
                        utangCashGals += remGals
                        utangCashComm += remComm
                    }
                    PaidChannel.BANK_ONLINE -> {
                        utangBankGals += remGals
                        utangBankComm += remComm
                    }
                }
            }
        }

        // Paid Walk-in and Pautang payments turned over directly to refilling never contribute to cashCollected or bankPaidTotal
        val pautangTurnedOverToRefilling = paymentsForDate
            .filter { it.isTurnedOverToRefilling }
            .sumOf { it.amount }
        val cashCollected = directCash.sumOf { it.amountPaid } +
            paymentsForDate
                .filter { resolveUtangPaymentChannel(it) == PaidChannel.CASH && !it.isTurnedOverToRefilling }
                .sumOf { it.amount }
        val bankPaidTotal = directBankOnline.sumOf { it.amountPaid } +
            paymentsForDate
                .filter { resolveUtangPaymentChannel(it) == PaidChannel.BANK_ONLINE && !it.isTurnedOverToRefilling }
                .sumOf { it.amount }

        val bankOnlineGallons = directBankOnline.sumOf { it.totalGallons } + utangBankGals
        val paidWalkInGallons = directPaidWalkIn.sumOf { it.totalGallons }
        val commissionFromCash = directCash.sumOf { it.commissionEarned } + utangCashComm
        val commissionFromBank = directBankOnline.sumOf { it.commissionEarned } + utangBankComm
        val commissionFromPaidWalkIn = directPaidWalkIn.sumOf { it.commissionEarned }

        return PaymentChannelBreakdown(
            cashPaidGallons = directCash.sumOf { it.totalGallons } + utangCashGals,
            bankTransferGallons = 0,
            bankDepositGallons = bankOnlineGallons,
            cashCollected = cashCollected,
            cashExpenses = routeExpensesTotal,
            cashToRemit = cashCollected - routeExpensesTotal - cashAdvanceTotal,
            bankTransferAmount = 0,
            bankDepositAmount = bankPaidTotal,
            bankPaidTotal = bankPaidTotal,
            commissionFromCash = commissionFromCash,
            commissionFromBank = commissionFromBank,
            totalPaidCommission = commissionFromCash + commissionFromBank + commissionFromPaidWalkIn,
            bankOnlineGallons = bankOnlineGallons,
            paidWalkInGallons = paidWalkInGallons,
            commissionFromPaidWalkIn = commissionFromPaidWalkIn,
            cashAdvanceTotal = cashAdvanceTotal,
            pautangTurnedOverToRefilling = pautangTurnedOverToRefilling,
        )
    }
}
