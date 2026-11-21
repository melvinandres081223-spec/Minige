package com.melvin.minige.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class PaymentType(val label: String) {
    CASH("Cash"),
    BANK_DEPOSIT("Bank Deposit"),
    PAUTANG("Pautang"),
    PICK_UP("Na-pick up");

    companion object {
        fun fromRaw(raw: String): PaymentType =
            entries.firstOrNull { it.name == raw } ?: CASH
    }
}

enum class UtangMethod(val label: String) {
    CASH("Cash"),
    BANK_DEPOSIT("Bank Deposit");

    companion object {
        fun fromRaw(raw: String): UtangMethod =
            entries.firstOrNull { it.name == raw } ?: CASH
    }
}

enum class ExpenseCategory(val label: String) {
    FOOD("Food"),
    GAS("Gas"),
    VULCANIZING("Vulcanizing"),
    MOTOR_REPAIR("Motor Repair");

    companion object {
        fun fromRaw(raw: String): ExpenseCategory =
            entries.firstOrNull { it.name == raw } ?: FOOD
    }
}

data class DeliveryRecord(
    val id: String,
    val userId: String,
    val dateKey: String,
    val customerName: String,
    val purifiedGallons: Int,
    val alkalineGallons: Int,
    val purifiedUnitPrice: Int,
    val alkalineUnitPrice: Int,
    val totalAmount: Int,
    val amountPaid: Int,
    val paymentType: PaymentType,
    val commissionRate: Int,
    val commissionEarned: Int,
    val settledDateKey: String?,
    val notes: String,
    val calendarEventId: String?,
    val pendingSync: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    val totalGallons: Int
        get() = purifiedGallons + alkalineGallons

    val remainingBalance: Int
        get() = if (paymentType == PaymentType.PAUTANG) {
            (totalAmount - amountPaid).coerceAtLeast(0)
        } else {
            0
        }

    val isUtangFullyPaid: Boolean
        get() = paymentType == PaymentType.PAUTANG && remainingBalance == 0
}

data class UtangPaymentRecord(
    val id: String,
    val deliveryId: String,
    val userId: String,
    val dateKey: String,
    val amount: Int,
    val method: UtangMethod,
    val notes: String,
    val pendingSync: Boolean,
    val createdAtEpochMs: Long,
)

data class ExpenseRecord(
    val id: String,
    val userId: String,
    val dateKey: String,
    val category: ExpenseCategory,
    val amount: Int,
    val notes: String,
    val pendingSync: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class CustomerRecord(
    val id: String,
    val userId: String,
    val name: String,
    val lastUnitPrice: Double? = null,
    val pendingSync: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
)

object MinigeCustomerUtils {
    fun normalizeCustomerId(rawName: String): String {
        val cleaned = rawName.trim()
            .lowercase(Locale.ENGLISH)
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .take(120)
        return cleaned.ifEmpty {
            val hash = kotlin.math.abs(rawName.trim().lowercase(Locale.ENGLISH).hashCode())
            "cust_$hash"
        }
    }

    fun formatPrice(price: Double): String {
        val rounded = kotlin.math.round(price * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
    }
}

data class DailyLedgerRecord(
    val dateKey: String,
    val userId: String,
    val startingSukli: Int,
    val notes: String,
    val pendingSync: Boolean,
    val updatedAtEpochMs: Long,
)

data class MinigeSettings(
    val purifiedPrice: Int = 30,
    val alkalinePrice: Int = 40,
    val commissionRate: Int = 8,
    val defaultSukli: Int = 0,
)

data class DailySummary(
    val dateKey: String,
    val directPaidGallons: Int,
    val settledUtangGallons: Int,
    val totalPaidGallons: Int,
    val dailyCommission: Int,
    val pickupGallons: Int,
    val unpaidUtangGallons: Int,
    val cashCollected: Int,
    val bankDepositTotal: Int,
    val expensesTotal: Int,
    val startingSukli: Int,
    val netCashRemit: Int,
    val cashOnHand: Int,
    val effectiveRateDisplay: Int?,
)

data class WeeklyDaySummary(
    val date: LocalDate,
    val dateKey: String,
    val paidGallons: Int,
    val commission: Int,
    val cashCollected: Int,
    val bankDeposit: Int,
    val expenses: Int,
    val pickupGallons: Int,
)

data class WeeklySummary(
    val monday: LocalDate,
    val saturday: LocalDate,
    val totalPaidGallons: Int,
    val totalCommission: Int,
    val totalCashCollected: Int,
    val totalBankDeposit: Int,
    val totalExpenses: Int,
    val days: List<WeeklyDaySummary>,
)

object MinigeDateUtils {
    private val keyFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val displayFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy (EEEE)", Locale.ENGLISH)
    private val shortFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)

    fun toKey(date: LocalDate): String = date.format(keyFormatter)

    fun parseKey(dateKey: String): LocalDate =
        runCatching { LocalDate.parse(dateKey, keyFormatter) }.getOrElse { LocalDate.now() }

    fun formatDisplay(date: LocalDate): String = date.format(displayFormatter)

    fun formatShort(date: LocalDate): String = date.format(shortFormatter)

    fun weekMonToSat(anchorDate: LocalDate): List<LocalDate> {
        val monday = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L..5L).map { monday.plusDays(it) }
    }
}

object MinigeCalculator {

    fun calculateDailySummary(
        dateKey: String,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
        expenses: List<ExpenseRecord>,
        startingSukli: Int,
    ): DailySummary {
        val deliveriesForDate = deliveries.filter { it.dateKey == dateKey }

        val directPaidDeliveries = deliveriesForDate.filter {
            it.paymentType == PaymentType.CASH || it.paymentType == PaymentType.BANK_DEPOSIT
        }
        val directPaidGallons = directPaidDeliveries.sumOf { it.totalGallons }
        val directCommission = directPaidDeliveries.sumOf { it.commissionEarned }

        // Utang commission is ONLY awarded on the exact date remaining balance reached 0
        val settledUtangToday = deliveries.filter {
            it.paymentType == PaymentType.PAUTANG &&
                it.isUtangFullyPaid &&
                it.settledDateKey == dateKey
        }
        val settledUtangGallons = settledUtangToday.sumOf { it.totalGallons }
        val settledUtangCommission = settledUtangToday.sumOf { it.commissionEarned }

        val totalPaidGallons = directPaidGallons + settledUtangGallons
        val dailyCommission = directCommission + settledUtangCommission

        val pickupGallons = deliveriesForDate
            .filter { it.paymentType == PaymentType.PICK_UP }
            .sumOf { it.totalGallons }

        val unpaidUtangGallons = deliveriesForDate
            .filter { it.paymentType == PaymentType.PAUTANG && !it.isUtangFullyPaid }
            .sumOf { it.totalGallons }

        val directCash = deliveriesForDate
            .filter { it.paymentType == PaymentType.CASH }
            .sumOf { it.amountPaid }
        val utangCash = utangPayments
            .filter { it.dateKey == dateKey && it.method == UtangMethod.CASH }
            .sumOf { it.amount }
        val cashCollected = directCash + utangCash

        val directBank = deliveriesForDate
            .filter { it.paymentType == PaymentType.BANK_DEPOSIT }
            .sumOf { it.amountPaid }
        val utangBank = utangPayments
            .filter { it.dateKey == dateKey && it.method == UtangMethod.BANK_DEPOSIT }
            .sumOf { it.amount }
        val bankDepositTotal = directBank + utangBank

        val expensesTotal = expenses
            .filter { it.dateKey == dateKey }
            .sumOf { it.amount }

        val netCashRemit = cashCollected - expensesTotal
        val cashOnHand = startingSukli + netCashRemit

        val distinctRates = (directPaidDeliveries + settledUtangToday)
            .map { it.commissionRate }
            .distinct()
        val effectiveRate = if (distinctRates.size == 1) distinctRates.first() else null

        return DailySummary(
            dateKey = dateKey,
            directPaidGallons = directPaidGallons,
            settledUtangGallons = settledUtangGallons,
            totalPaidGallons = totalPaidGallons,
            dailyCommission = dailyCommission,
            pickupGallons = pickupGallons,
            unpaidUtangGallons = unpaidUtangGallons,
            cashCollected = cashCollected,
            bankDepositTotal = bankDepositTotal,
            expensesTotal = expensesTotal,
            startingSukli = startingSukli,
            netCashRemit = netCashRemit,
            cashOnHand = cashOnHand,
            effectiveRateDisplay = effectiveRate,
        )
    }

    fun calculateWeeklySummary(
        anchorDate: LocalDate,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
        expenses: List<ExpenseRecord>,
    ): WeeklySummary {
        val weekDates = MinigeDateUtils.weekMonToSat(anchorDate)
        val daySummaries = weekDates.map { date ->
            val key = MinigeDateUtils.toKey(date)
            val daily = calculateDailySummary(
                dateKey = key,
                deliveries = deliveries,
                utangPayments = utangPayments,
                expenses = expenses,
                startingSukli = 0,
            )
            WeeklyDaySummary(
                date = date,
                dateKey = key,
                paidGallons = daily.totalPaidGallons,
                commission = daily.dailyCommission,
                cashCollected = daily.cashCollected,
                bankDeposit = daily.bankDepositTotal,
                expenses = daily.expensesTotal,
                pickupGallons = daily.pickupGallons,
            )
        }
        return WeeklySummary(
            monday = weekDates.first(),
            saturday = weekDates.last(),
            totalPaidGallons = daySummaries.sumOf { it.paidGallons },
            totalCommission = daySummaries.sumOf { it.commission },
            totalCashCollected = daySummaries.sumOf { it.cashCollected },
            totalBankDeposit = daySummaries.sumOf { it.bankDeposit },
            totalExpenses = daySummaries.sumOf { it.expenses },
            days = daySummaries,
        )
    }
}

object BossPickupFormatter {
    private val headerDateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy (EEEE)", Locale.ENGLISH)

    /**
     * Generates the strict Boss-facing pick-up message containing ONLY Na-pick up na Gallon entries.
     * Never includes commission, cash remit, expenses, utang, or bank deposit.
     */
    fun format(date: LocalDate, pickups: List<DeliveryRecord>): String {
        val onlyPickups = pickups.filter { it.paymentType == PaymentType.PICK_UP && it.totalGallons > 0 }
        val totalGals = onlyPickups.sumOf { it.totalGallons }
        val nameColWidth = (onlyPickups.maxOfOrNull { it.customerName.trim().length } ?: 0).coerceAtLeast(9)
        val countColWidth = maxOf(
            totalGals.toString().length,
            onlyPickups.maxOfOrNull { it.totalGallons.toString().length } ?: 1,
        ).coerceAtLeast(2)
        val separator = " — "
        val totalPrefix = "TOTAL:"
        val totalNumberPad = (nameColWidth + separator.length + countColWidth - totalPrefix.length)
            .coerceAtLeast(countColWidth + 1)

        val sb = StringBuilder()
        sb.appendLine("📦 NA-PICK UP NA GALLON")
        sb.appendLine("📅 Petsa: ${date.format(headerDateFormatter)}")
        sb.appendLine("━━━━━━━━━━━━━━━")

        if (onlyPickups.isEmpty()) {
            sb.appendLine("(Walang pick-up ngayon)")
        } else {
            onlyPickups.forEach { entry ->
                val name = entry.customerName.trim().padEnd(nameColWidth, ' ')
                val count = entry.totalGallons
                val unit = if (count == 1) "gal" else "gals"
                val countStr = count.toString().padStart(countColWidth, ' ')
                sb.appendLine("$name$separator$countStr $unit")
            }
        }

        sb.appendLine("━━━━━━━━━━━━━━━")
        val totalUnit = if (totalGals == 1) "gal" else "gals"
        sb.append("$totalPrefix${totalGals.toString().padStart(totalNumberPad, ' ')} $totalUnit")
        return sb.toString()
    }
}
