package com.melvin.minige.util

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
    BANK_TRANSFER(
        label = "Bank Transfer",
        storedPaymentType = PaymentType.BANK_DEPOSIT,
        storedUtangMethod = UtangMethod.BANK_DEPOSIT,
        noteTag = "[Bank Transfer]",
    ),
    BANK_DEPOSIT(
        label = "Bank Deposit",
        storedPaymentType = PaymentType.BANK_DEPOSIT,
        storedUtangMethod = UtangMethod.BANK_DEPOSIT,
        noteTag = "[Bank Deposit]",
    ),
}

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
)

object PaymentBreakdownHelper {

    private const val TAG_BANK_TRANSFER = "[Bank Transfer]"
    private const val TAG_BANK_DEPOSIT = "[Bank Deposit]"

    fun resolveDeliveryChannel(delivery: DeliveryRecord): PaidChannel? {
        return when (delivery.paymentType) {
            PaymentType.CASH -> PaidChannel.CASH
            PaymentType.BANK_DEPOSIT -> {
                if (delivery.notes.contains(TAG_BANK_TRANSFER, ignoreCase = true)) {
                    PaidChannel.BANK_TRANSFER
                } else {
                    PaidChannel.BANK_DEPOSIT
                }
            }
            PaymentType.PAUTANG, PaymentType.PICK_UP -> null
        }
    }

    fun resolveUtangPaymentChannel(payment: UtangPaymentRecord): PaidChannel {
        return when (payment.method) {
            UtangMethod.CASH -> PaidChannel.CASH
            UtangMethod.BANK_DEPOSIT -> {
                if (payment.notes.contains(TAG_BANK_TRANSFER, ignoreCase = true)) {
                    PaidChannel.BANK_TRANSFER
                } else {
                    PaidChannel.BANK_DEPOSIT
                }
            }
        }
    }

    fun stripChannelTags(notes: String): String {
        return notes
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

    fun displayMethodLabel(delivery: DeliveryRecord): String {
        return resolveDeliveryChannel(delivery)?.label ?: delivery.paymentType.label
    }

    fun calculateBreakdown(
        dateKey: String,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
        expenses: List<ExpenseRecord>,
    ): PaymentChannelBreakdown {
        val deliveriesForDate = deliveries.filter { it.dateKey == dateKey }
        val paymentsForDate = utangPayments.filter { it.dateKey == dateKey }
        val expensesTotal = expenses.filter { it.dateKey == dateKey }.sumOf { it.amount }

        val directCash = deliveriesForDate.filter { resolveDeliveryChannel(it) == PaidChannel.CASH }
        val directBankTransfer = deliveriesForDate.filter { resolveDeliveryChannel(it) == PaidChannel.BANK_TRANSFER }
        val directBankDeposit = deliveriesForDate.filter { resolveDeliveryChannel(it) == PaidChannel.BANK_DEPOSIT }

        val settledUtangToday = deliveries.filter {
            it.paymentType == PaymentType.PAUTANG &&
                it.isUtangFullyPaid &&
                it.settledDateKey == dateKey
        }
        val settledByDeliveryId = paymentsForDate.groupBy { it.deliveryId }

        var utangCashGals = 0
        var utangTransferGals = 0
        var utangDepositGals = 0
        var utangCashComm = 0
        var utangBankComm = 0

        settledUtangToday.forEach { utang ->
            val lastChannel = settledByDeliveryId[utang.id]
                ?.lastOrNull()
                ?.let { resolveUtangPaymentChannel(it) }
                ?: PaidChannel.CASH
            when (lastChannel) {
                PaidChannel.CASH -> {
                    utangCashGals += utang.totalGallons
                    utangCashComm += utang.commissionEarned
                }
                PaidChannel.BANK_TRANSFER -> {
                    utangTransferGals += utang.totalGallons
                    utangBankComm += utang.commissionEarned
                }
                PaidChannel.BANK_DEPOSIT -> {
                    utangDepositGals += utang.totalGallons
                    utangBankComm += utang.commissionEarned
                }
            }
        }

        val cashCollected = directCash.sumOf { it.amountPaid } +
            paymentsForDate.filter { resolveUtangPaymentChannel(it) == PaidChannel.CASH }.sumOf { it.amount }
        val bankTransferAmount = directBankTransfer.sumOf { it.amountPaid } +
            paymentsForDate.filter { resolveUtangPaymentChannel(it) == PaidChannel.BANK_TRANSFER }.sumOf { it.amount }
        val bankDepositAmount = directBankDeposit.sumOf { it.amountPaid } +
            paymentsForDate.filter { resolveUtangPaymentChannel(it) == PaidChannel.BANK_DEPOSIT }.sumOf { it.amount }

        val commissionFromCash = directCash.sumOf { it.commissionEarned } + utangCashComm
        val commissionFromBank = directBankTransfer.sumOf { it.commissionEarned } +
            directBankDeposit.sumOf { it.commissionEarned } +
            utangBankComm

        return PaymentChannelBreakdown(
            cashPaidGallons = directCash.sumOf { it.totalGallons } + utangCashGals,
            bankTransferGallons = directBankTransfer.sumOf { it.totalGallons } + utangTransferGals,
            bankDepositGallons = directBankDeposit.sumOf { it.totalGallons } + utangDepositGals,
            cashCollected = cashCollected,
            cashExpenses = expensesTotal,
            cashToRemit = cashCollected - expensesTotal,
            bankTransferAmount = bankTransferAmount,
            bankDepositAmount = bankDepositAmount,
            bankPaidTotal = bankTransferAmount + bankDepositAmount,
            commissionFromCash = commissionFromCash,
            commissionFromBank = commissionFromBank,
            totalPaidCommission = commissionFromCash + commissionFromBank,
        )
    }
}
