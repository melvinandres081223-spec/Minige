package com.melvin.minige.domain

import androidx.compose.runtime.Immutable
import com.melvin.minige.util.PartialPaymentHelper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class PaymentType(val label: String) {
    CASH("Cash"),
    BANK_DEPOSIT("Bank / Online"),
    PAID_WALK_IN("Paid Walk-in"),
    PAUTANG("Pautang"),
    PICK_UP("Na-pick up");

    companion object {
        fun fromRaw(raw: String): PaymentType =
            entries.firstOrNull { it.name == raw } ?: CASH

        fun fromEntityRaw(raw: String, notes: String): PaymentType {
            return if (raw == PAID_WALK_IN.name || notes.contains("[Paid Walk-in]", ignoreCase = true)) {
                PAID_WALK_IN
            } else {
                fromRaw(raw)
            }
        }
    }
}

enum class UtangMethod(val label: String) {
    CASH("Cash"),
    BANK_DEPOSIT("Bank / Online");

    companion object {
        fun fromRaw(raw: String): UtangMethod =
            entries.firstOrNull { it.name == raw } ?: CASH
    }
}

enum class ExpenseCategory(val label: String) {
    FOOD("Food"),
    GAS("Gas"),
    VULCANIZING("Vulcanizing"),
    MOTOR_REPAIR("Motor Repair"),
    CASH_ADVANCE("Cash Advance");

    val isCashAdvance: Boolean
        get() = this == CASH_ADVANCE

    companion object {
        fun fromRaw(raw: String): ExpenseCategory =
            entries.firstOrNull { it.name == raw } ?: FOOD

        fun fromEntityRaw(raw: String, notes: String): ExpenseCategory {
            return if (raw == CASH_ADVANCE.name || notes.contains("[Cash Advance]", ignoreCase = true)) {
                CASH_ADVANCE
            } else {
                fromRaw(raw)
            }
        }
    }
}

@Immutable
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

    val hasPaidWalkInCommission: Boolean
        get() = paymentType == PaymentType.PAID_WALK_IN &&
            commissionEarned > 0 &&
            !notes.contains("[No Commission]", ignoreCase = true)
}

@Immutable
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
) {
    val isTurnedOverToRefilling: Boolean
        get() = notes.contains("[Turned Over to Refilling]", ignoreCase = true)
}

@Immutable
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

@Immutable
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

@Immutable
data class DailyLedgerRecord(
    val dateKey: String,
    val userId: String,
    val startingSukli: Int,
    val notes: String,
    val pendingSync: Boolean,
    val updatedAtEpochMs: Long,
)

@Immutable
data class MinigeSettings(
    val purifiedPrice: Int = 30,
    val alkalinePrice: Int = 40,
    val commissionRate: Int = 8,
    val defaultSukli: Int = 0,
)

@Immutable
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
    val cashAdvanceTotal: Int = 0,
    val pautangTurnedOverToRefilling: Int = 0,
)

@Immutable
data class WeeklyDaySummary(
    val date: LocalDate,
    val dateKey: String,
    val paidGallons: Int,
    val commission: Int,
    val cashCollected: Int,
    val bankDeposit: Int,
    val expenses: Int,
    val pickupGallons: Int,
    val cashAdvance: Int = 0,
)

@Immutable
data class WeeklySummary(
    val monday: LocalDate,
    val saturday: LocalDate,
    val totalPaidGallons: Int,
    val totalCommission: Int,
    val totalCashCollected: Int,
    val totalBankDeposit: Int,
    val totalExpenses: Int,
    val days: List<WeeklyDaySummary>,
    val totalCashAdvance: Int = 0,
) {
    val netRemainingCommission: Int
        get() = totalCommission - totalCashAdvance
}

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
            it.paymentType == PaymentType.CASH ||
                it.paymentType == PaymentType.BANK_DEPOSIT ||
                it.paymentType == PaymentType.PAID_WALK_IN
        }
        val directPaidGallons = directPaidDeliveries.sumOf { it.totalGallons }
        val directCommission = directPaidDeliveries.sumOf { it.commissionEarned }

        // Partial-gallon payments count immediately on payment date; remainder on full settlement date
        val partialUtangGallonsToday = PartialPaymentHelper.taggedPaidGallonsForDate(dateKey, utangPayments)
        val partialUtangCommissionToday = PartialPaymentHelper.taggedCommissionForDate(dateKey, utangPayments)

        // Utang commission is ONLY awarded on the exact date remaining balance reached 0 (for untagged portion)
        val settledUtangToday = deliveries.filter {
            it.paymentType == PaymentType.PAUTANG &&
                it.isUtangFullyPaid &&
                it.settledDateKey == dateKey
        }
        val settledRemainderGallons = settledUtangToday.sumOf {
            PartialPaymentHelper.untaggedSettledRemainderGallons(it, utangPayments)
        }
        val settledRemainderCommission = settledUtangToday.sumOf {
            PartialPaymentHelper.untaggedSettledRemainderCommission(it, utangPayments)
        }

        val settledUtangGallons = partialUtangGallonsToday + settledRemainderGallons
        val settledUtangCommission = partialUtangCommissionToday + settledRemainderCommission

        val totalPaidGallons = directPaidGallons + settledUtangGallons
        val dailyCommission = directCommission + settledUtangCommission

        val pickupGallons = deliveriesForDate
            .filter { it.paymentType == PaymentType.PICK_UP }
            .sumOf { it.totalGallons }

        val unpaidUtangGallons = deliveriesForDate
            .filter { it.paymentType == PaymentType.PAUTANG && !it.isUtangFullyPaid }
            .sumOf { it.totalGallons }

        // Paid Walk-in and Pautang payments turned over directly to refilling NEVER add to cashCollected or bankDepositTotal
        val pautangTurnedOverToRefilling = utangPayments
            .filter { it.dateKey == dateKey && it.isTurnedOverToRefilling }
            .sumOf { it.amount }
        val directCash = deliveriesForDate
            .filter { it.paymentType == PaymentType.CASH }
            .sumOf { it.amountPaid }
        val utangCash = utangPayments
            .filter {
                it.dateKey == dateKey &&
                    it.method == UtangMethod.CASH &&
                    !it.isTurnedOverToRefilling
            }
            .sumOf { it.amount }
        val cashCollected = directCash + utangCash

        val directBank = deliveriesForDate
            .filter { it.paymentType == PaymentType.BANK_DEPOSIT }
            .sumOf { it.amountPaid }
        val utangBank = utangPayments
            .filter {
                it.dateKey == dateKey &&
                    it.method == UtangMethod.BANK_DEPOSIT &&
                    !it.isTurnedOverToRefilling
            }
            .sumOf { it.amount }
        val bankDepositTotal = directBank + utangBank

        val entriesForDate = expenses.filter { it.dateKey == dateKey }
        val expensesTotal = entriesForDate
            .filter { !it.category.isCashAdvance }
            .sumOf { it.amount }
        val cashAdvanceTotal = entriesForDate
            .filter { it.category.isCashAdvance }
            .sumOf { it.amount }

        val netCashRemit = cashCollected - expensesTotal - cashAdvanceTotal
        val cashOnHand = startingSukli + netCashRemit

        val hasZeroCommWalkIn = directPaidDeliveries.any {
            it.paymentType == PaymentType.PAID_WALK_IN && it.commissionEarned == 0 && it.totalGallons > 0
        }
        val distinctRates = (directPaidDeliveries + settledUtangToday)
            .filter { it.commissionEarned > 0 }
            .map { it.commissionRate }
            .distinct()
        val effectiveRate = if (!hasZeroCommWalkIn && distinctRates.size == 1) distinctRates.first() else null

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
            cashAdvanceTotal = cashAdvanceTotal,
            pautangTurnedOverToRefilling = pautangTurnedOverToRefilling,
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
                cashAdvance = daily.cashAdvanceTotal,
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
            totalCashAdvance = daySummaries.sumOf { it.cashAdvance },
        )
    }
}

object BossPickupFormatter {
    private val headerDateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH)
    private const val DIVIDER = "━━━━━━━━━━━━━━━━━━━━"

    @Immutable
    data class DailyHakotReport(
        val paidGallons: Int,
        val unpaidPautangGallons: Int,
        val grandTotalGallons: Int,
        val message: String,
    )

    fun tagalogDayName(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "Lunes"
        DayOfWeek.TUESDAY -> "Martes"
        DayOfWeek.WEDNESDAY -> "Miyerkules"
        DayOfWeek.THURSDAY -> "Huwebes"
        DayOfWeek.FRIDAY -> "Biyernes"
        DayOfWeek.SATURDAY -> "Sabado"
        DayOfWeek.SUNDAY -> "Linggo"
    }

    private data class HakotRow(
        val customerName: String,
        val gallons: Int,
        val sortTimestamp: Long,
    )

    /**
     * Generates the Boss-facing Daily Hakot report containing two clearly separated sections
     * for [date]:
     * 1) Paid / Delivered gallons (`CASH`, `BANK_DEPOSIT` / Bank / Online, `PAID_WALK_IN` whether
     *    commission is ON or OFF, partial-gallon `PAUTANG` payments collected on `dateKey`, and
     *    settled `PAUTANG` remainder gallons with `settledDateKey == dateKey`).
     * 2) Unpaid `PAUTANG` deliveries created/delivered on `dateKey` (`!isUtangFullyPaid`), showing
     *    remaining unpaid gallons if a partial-gallon payment already happened on `dateKey`.
     *
     * Strictly excludes `PICK_UP` entries, peso signs (`₱`), unit prices, commissions,
     * cash remittance, cash advances, and sukli.
     */
    fun format(
        date: LocalDate,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord> = emptyList(),
    ): String = buildReport(date, deliveries, utangPayments).message

    fun buildReport(
        date: LocalDate,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord> = emptyList(),
    ): DailyHakotReport {
        val dateKey = MinigeDateUtils.toKey(date)
        val deliveryById = deliveries.associateBy { it.id }

        val paidRows = mutableListOf<HakotRow>()

        // 1) Direct paid deliveries on selected date (Cash, Bank / Online, Paid Walk-in)
        deliveries
            .filter {
                it.dateKey == dateKey &&
                    (it.paymentType == PaymentType.CASH ||
                        it.paymentType == PaymentType.BANK_DEPOSIT ||
                        it.paymentType == PaymentType.PAID_WALK_IN) &&
                    it.totalGallons > 0
            }
            .forEach { entry ->
                paidRows += HakotRow(
                    customerName = entry.customerName.trim().ifEmpty { "Customer" },
                    gallons = entry.totalGallons,
                    sortTimestamp = entry.createdAtEpochMs,
                )
            }

        // 2) Gallon-tagged partial Pautang payments collected on selected date
        utangPayments
            .filter { it.dateKey == dateKey }
            .forEach { payment ->
                val paidGals = PartialPaymentHelper.extractPaidGallons(payment)
                if (paidGals > 0) {
                    val parent = deliveryById[payment.deliveryId]
                    if (parent == null || parent.paymentType == PaymentType.PAUTANG) {
                        val name = parent?.customerName?.trim()?.ifEmpty { "Customer" } ?: "Customer"
                        paidRows += HakotRow(
                            customerName = name,
                            gallons = paidGals,
                            sortTimestamp = payment.createdAtEpochMs,
                        )
                    }
                }
            }

        // 3) Settled Pautang untagged remainder gallons whose settlement date is selected date
        deliveries
            .filter {
                it.paymentType == PaymentType.PAUTANG &&
                    it.isUtangFullyPaid &&
                    it.settledDateKey == dateKey
            }
            .forEach { utang ->
                val remGallons = PartialPaymentHelper.untaggedSettledRemainderGallons(utang, utangPayments)
                if (remGallons > 0) {
                    val lastPaymentTs = utangPayments
                        .filter { it.deliveryId == utang.id && it.dateKey == dateKey }
                        .maxOfOrNull { it.createdAtEpochMs }
                        ?: utang.updatedAtEpochMs
                    paidRows += HakotRow(
                        customerName = utang.customerName.trim().ifEmpty { "Customer" },
                        gallons = remGallons,
                        sortTimestamp = lastPaymentTs,
                    )
                }
            }

        // 4) Unpaid Pautang deliveries created/delivered on selected date
        val pautangRows = mutableListOf<HakotRow>()
        deliveries
            .filter {
                it.dateKey == dateKey &&
                    it.paymentType == PaymentType.PAUTANG &&
                    !it.isUtangFullyPaid &&
                    it.totalGallons > 0
            }
            .forEach { utang ->
                val paidTaggedOnOrBeforeDate = utangPayments
                    .filter { it.deliveryId == utang.id && it.dateKey <= dateKey }
                    .sumOf { PartialPaymentHelper.extractPaidGallons(it) }
                val remainingUnpaidGals = (utang.totalGallons - paidTaggedOnOrBeforeDate).coerceAtLeast(0)
                if (remainingUnpaidGals > 0) {
                    pautangRows += HakotRow(
                        customerName = utang.customerName.trim().ifEmpty { "Customer" },
                        gallons = remainingUnpaidGals,
                        sortTimestamp = utang.createdAtEpochMs,
                    )
                }
            }

        val orderedPaidRows = paidRows.sortedBy { it.sortTimestamp }
        val orderedPautangRows = pautangRows.sortedBy { it.sortTimestamp }

        val paidTotalGals = orderedPaidRows.sumOf { it.gallons }
        val pautangTotalGals = orderedPautangRows.sumOf { it.gallons }
        val grandTotalGals = paidTotalGals + pautangTotalGals

        fun formatUnit(count: Int): String = if (count == 1) "gal" else "gals"

        val sb = StringBuilder()
        sb.appendLine("🚚 DAILY HAKOT (DELIVERED)")
        sb.appendLine("📅 Petsa: ${date.format(headerDateFormatter)} (${tagalogDayName(date.dayOfWeek)})")
        sb.appendLine(DIVIDER)

        if (orderedPaidRows.isEmpty()) {
            sb.appendLine("(Wala pang paid delivery ngayon)")
        } else {
            orderedPaidRows.forEach { row ->
                sb.appendLine("• ${row.customerName} — ${row.gallons} ${formatUnit(row.gallons)}")
            }
        }

        sb.appendLine(DIVIDER)
        sb.appendLine("DELIVERED PAID TOTAL: $paidTotalGals ${formatUnit(paidTotalGals)}")
        sb.appendLine()
        sb.appendLine("📋 PAUTANG DELIVERED TODAY")
        sb.appendLine(DIVIDER)

        if (orderedPautangRows.isEmpty()) {
            sb.appendLine("(Walang pautang delivered today)")
        } else {
            orderedPautangRows.forEach { row ->
                sb.appendLine("• ${row.customerName} — ${row.gallons} ${formatUnit(row.gallons)}")
            }
        }

        sb.appendLine(DIVIDER)
        sb.appendLine("PAUTANG TOTAL: $pautangTotalGals ${formatUnit(pautangTotalGals)}")
        sb.append("GRAND TOTAL: $grandTotalGals ${formatUnit(grandTotalGals)}")

        return DailyHakotReport(
            paidGallons = paidTotalGals,
            unpaidPautangGallons = pautangTotalGals,
            grandTotalGallons = grandTotalGals,
            message = sb.toString(),
        )
    }
}

enum class InactiveSeverity {
    AMBER_WARNING, // 3–4 days without an order
    RED_CRITICAL,  // 5+ days without an order
}

@Immutable
data class InactiveSukiAlert(
    val customerId: String,
    val customerName: String,
    val lastDeliveryDateKey: String,
    val daysSinceLastDelivery: Int,
    val severity: InactiveSeverity,
    val lastOrderGallons: Int,
    val lastPurifiedGallons: Int,
    val lastAlkalineGallons: Int,
    val lastUnitPrice: Double?,
    val lastNotes: String = "",
)

object InactiveSukiAlertHelper {
    const val INACTIVE_THRESHOLD_DAYS = 3L
    const val CRITICAL_THRESHOLD_DAYS = 5L

    fun findInactiveCustomers(
        customers: List<CustomerRecord>,
        deliveries: List<DeliveryRecord>,
        referenceDate: LocalDate = LocalDate.now(),
    ): List<InactiveSukiAlert> {
        val referenceKey = MinigeDateUtils.toKey(referenceDate)
        val customersById = customers.associateBy { customer ->
            MinigeCustomerUtils.normalizeCustomerId(customer.name)
                .ifEmpty { customer.id }
        }

        // Only actual water deliveries (excluding PaymentType.PICK_UP) on or before referenceDate count
        val deliveriesByCustomerId = deliveries
            .filter {
                it.paymentType != PaymentType.PICK_UP &&
                    it.totalGallons > 0 &&
                    it.dateKey <= referenceKey
            }
            .groupBy { MinigeCustomerUtils.normalizeCustomerId(it.customerName) }

        val allCustomerIds = (customersById.keys + deliveriesByCustomerId.keys).distinct()

        return allCustomerIds.mapNotNull { cid ->
            val customerRecord = customersById[cid]
            val latestDelivery = deliveriesByCustomerId[cid]
                ?.maxWithOrNull(
                    compareBy<DeliveryRecord> { it.dateKey }
                        .thenBy { it.updatedAtEpochMs }
                        .thenBy { it.createdAtEpochMs }
                )
                ?: return@mapNotNull null

            val lastDate = runCatching { LocalDate.parse(latestDelivery.dateKey) }.getOrNull()
                ?: return@mapNotNull null
            val daysElapsed = ChronoUnit.DAYS.between(lastDate, referenceDate)
            if (daysElapsed < INACTIVE_THRESHOLD_DAYS) return@mapNotNull null

            val displayName = customerRecord?.name?.takeIf { it.isNotBlank() }
                ?: latestDelivery.customerName.trim().ifEmpty { return@mapNotNull null }

            val derivedUnitPrice = customerRecord?.lastUnitPrice
                ?: deriveUnitPrice(latestDelivery)

            val severity = if (daysElapsed >= CRITICAL_THRESHOLD_DAYS) {
                InactiveSeverity.RED_CRITICAL
            } else {
                InactiveSeverity.AMBER_WARNING
            }

            InactiveSukiAlert(
                customerId = cid,
                customerName = displayName,
                lastDeliveryDateKey = latestDelivery.dateKey,
                daysSinceLastDelivery = daysElapsed.toInt(),
                severity = severity,
                lastOrderGallons = latestDelivery.totalGallons,
                lastPurifiedGallons = latestDelivery.purifiedGallons,
                lastAlkalineGallons = latestDelivery.alkalineGallons,
                lastUnitPrice = derivedUnitPrice,
                lastNotes = latestDelivery.notes,
            )
        }.sortedWith(
            compareByDescending<InactiveSukiAlert> { it.daysSinceLastDelivery }
                .thenByDescending { it.lastOrderGallons }
                .thenBy { it.customerName.lowercase(Locale.ENGLISH) }
        )
    }

    private fun deriveUnitPrice(delivery: DeliveryRecord): Double? {
        if (delivery.totalGallons <= 0) return null
        if (delivery.totalAmount > 0) {
            val defaultSplit = (delivery.purifiedGallons * delivery.purifiedUnitPrice) +
                (delivery.alkalineGallons * delivery.alkalineUnitPrice)
            return when {
                delivery.totalAmount != defaultSplit ->
                    delivery.totalAmount.toDouble() / delivery.totalGallons.toDouble()
                delivery.alkalineGallons > 0 && delivery.purifiedGallons == 0 ->
                    delivery.alkalineUnitPrice.toDouble()
                else -> delivery.purifiedUnitPrice.toDouble()
            }
        }
        return if (delivery.alkalineGallons > 0 && delivery.purifiedGallons == 0) {
            delivery.alkalineUnitPrice.toDouble()
        } else {
            delivery.purifiedUnitPrice.toDouble()
        }
    }
}
