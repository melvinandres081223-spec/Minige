package com.melvin.minige

import com.melvin.minige.domain.BossPickupFormatter
import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.MinigeCalculator
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.ui.components.DailyClosingHelper
import com.melvin.minige.ui.components.DailyQuotaPrefs
import com.melvin.minige.ui.components.DeliveryFilterChip
import com.melvin.minige.ui.components.DeliveryFilterHelper
import com.melvin.minige.ui.components.GuideScreenTab
import com.melvin.minige.ui.components.ScreenGuideCatalog
import com.melvin.minige.ui.components.SukiAnalyticsHelper
import com.melvin.minige.ui.components.SundayMakeupHelper
import com.melvin.minige.util.CommissionRateHistory
import com.melvin.minige.util.CustomerClassification
import com.melvin.minige.util.CustomerDirectoryHelper
import com.melvin.minige.util.DeliveryVoiceParser
import com.melvin.minige.util.PaidChannel
import com.melvin.minige.util.PartialPaymentHelper
import com.melvin.minige.util.PautangOverdueHelper
import com.melvin.minige.util.PaymentBreakdownHelper
import com.melvin.minige.util.RouteGroupingHelper
import com.melvin.minige.util.UndoStateHelper
import com.melvin.minige.util.UndoableAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExampleUnitTest {

    private fun delivery(
        id: String,
        dateKey: String,
        name: String,
        purified: Int,
        alkaline: Int,
        paymentType: PaymentType,
        amountPaid: Int,
        commissionRate: Int = 8,
        commissionEarned: Int = 0,
        settledDateKey: String? = null,
    ): DeliveryRecord {
        val totalBill = if (paymentType == PaymentType.PICK_UP) 0 else purified * 30 + alkaline * 40
        return DeliveryRecord(
            id = id,
            userId = "u1",
            dateKey = dateKey,
            customerName = name,
            purifiedGallons = purified,
            alkalineGallons = alkaline,
            purifiedUnitPrice = 30,
            alkalineUnitPrice = 40,
            totalAmount = totalBill,
            amountPaid = amountPaid,
            paymentType = paymentType,
            commissionRate = commissionRate,
            commissionEarned = commissionEarned,
            settledDateKey = settledDateKey,
            notes = "",
            calendarEventId = null,
            pendingSync = false,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        )
    }

    @Test
    fun `strict commission calculator handles direct paid, partial vs settled utang, pickup, and remittance`() {
        val today = "2026-11-20"
        val yesterday = "2026-11-19"

        val deliveries = listOf(
            // 30 Cash + 20 Bank = 50 direct paid gals -> ₱400
            delivery("d1", today, "Customer A", 30, 0, PaymentType.CASH, 900, 8, 240, today),
            delivery("d2", today, "Customer B", 0, 20, PaymentType.BANK_DEPOSIT, 800, 8, 160, today),
            // Partial Utang today -> ₱0 commission, excluded from paid gallons
            delivery("d3", today, "Partial Customer", 5, 0, PaymentType.PAUTANG, 50, 8, 0, null),
            // Yesterday's Utang settled in full today -> +2 gals, +₱16 commission today
            delivery("d4", yesterday, "Settled Customer", 2, 0, PaymentType.PAUTANG, 60, 8, 16, today),
            // Na-pick up today -> ₱0 commission, excluded from paid gallons & remittance
            delivery("d5", today, "Nene", 45, 0, PaymentType.PICK_UP, 0, 8, 0, null),
        )

        val payments = listOf(
            UtangPaymentRecord("p1", "d3", "u1", today, 50, UtangMethod.CASH, "", false, 0L),
            UtangPaymentRecord("p2", "d4", "u1", today, 60, UtangMethod.BANK_DEPOSIT, "", false, 0L),
        )

        val expenses = listOf(
            ExpenseRecord("e1", "u1", today, ExpenseCategory.GAS, 120, "", false, 0L, 0L),
            ExpenseRecord("e2", "u1", today, ExpenseCategory.FOOD, 80, "", false, 0L, 0L),
        )

        val summary = MinigeCalculator.calculateDailySummary(
            dateKey = today,
            deliveries = deliveries,
            utangPayments = payments,
            expenses = expenses,
            startingSukli = 300,
        )

        assertEquals(50, summary.directPaidGallons)
        assertEquals(2, summary.settledUtangGallons)
        assertEquals(52, summary.totalPaidGallons)
        assertEquals(416, summary.dailyCommission)
        assertEquals(45, summary.pickupGallons)
        assertEquals(5, summary.unpaidUtangGallons)
        // Cash: 900 direct + 50 partial utang = 950; Bank: 800 direct + 60 settled utang = 860
        assertEquals(950, summary.cashCollected)
        assertEquals(860, summary.bankDepositTotal)
        assertEquals(200, summary.expensesTotal)
        assertEquals(750, summary.netCashRemit)
        assertEquals(1050, summary.cashOnHand)
    }

    @Test
    fun `boss pickup formatter produces exact monospace format without sensitive totals`() {
        val date = LocalDate.of(2026, 11, 20)
        val entries = listOf(
            delivery("p1", "2026-11-20", "Nene", 45, 0, PaymentType.PICK_UP, 0),
            delivery("p2", "2026-11-20", "Jhon", 1, 0, PaymentType.PICK_UP, 0),
            delivery("p3", "2026-11-20", "Melvin", 2, 0, PaymentType.PICK_UP, 0),
            // Should be ignored completely:
            delivery("c1", "2026-11-20", "Secret Cash", 10, 0, PaymentType.CASH, 300, 8, 80, "2026-11-20"),
        )

        val formatted = BossPickupFormatter.format(date, entries)
        val expected = """
            📦 NA-PICK UP NA GALLON
            📅 Petsa: Nov 20, 2026 (Friday)
            ━━━━━━━━━━━━━━━
            Nene      — 45 gals
            Jhon      —  1 gal
            Melvin    —  2 gals
            ━━━━━━━━━━━━━━━
            TOTAL:      48 gals
        """.trimIndent()

        assertEquals(expected, formatted)
        val lines = formatted.lines()
        // Verify numbers end at the exact same character index in monospace
        assertEquals(lines[3].indexOf(" gals"), lines[5].indexOf(" gals"))
        assertEquals(lines[3].indexOf(" gals"), lines[7].indexOf(" gals"))
        assertEquals(lines[3].indexOf(" gals"), lines[4].indexOf(" gal"))
        assertFalse(formatted.contains("Secret Cash"))
        assertFalse(formatted.contains("₱"))
        assertFalse(formatted.contains("Commission", ignoreCase = true))
    }

    @Test
    fun `customer utils normalize ids and format prices cleanly`() {
        assertEquals("aling_nena_s_store", MinigeCustomerUtils.normalizeCustomerId("  Aling Nena's Store! "))
        assertEquals("aling_nena_s_store", MinigeCustomerUtils.normalizeCustomerId("ALING NENA'S STORE"))
        assertEquals("25", MinigeCustomerUtils.formatPrice(25.0))
        assertEquals("27.5", MinigeCustomerUtils.formatPrice(27.5))
    }

    @Test
    fun `pautang overdue helper flags 3 or more days unpaid and clears when fully paid`() {
        val today = LocalDate.of(2026, 11, 21)

        // 2 days old -> NOT overdue
        val recentUtang = delivery("u2d", "2026-11-19", "Recent Customer", 3, 0, PaymentType.PAUTANG, 0)
        // Exactly 3 days old (partial payment, ₱60 left of ₱90) -> OVERDUE
        val exact3DaysUtang = delivery("u3d", "2026-11-18", "3-Day Customer", 3, 0, PaymentType.PAUTANG, 30)
        // 5 days old unpaid -> OVERDUE
        val old5DaysUtang = delivery("u5d", "2026-11-16", "5-Day Customer", 4, 0, PaymentType.PAUTANG, 0)
        // 7 days old but fully paid -> NOT overdue
        val paidOldUtang = delivery("upaid", "2026-11-14", "Paid Customer", 2, 0, PaymentType.PAUTANG, 60, 8, 16, "2026-11-21")
        // Old direct cash -> NOT overdue
        val oldCash = delivery("ucash", "2026-11-10", "Cash Customer", 5, 0, PaymentType.CASH, 150, 8, 40, "2026-11-10")

        val summary = PautangOverdueHelper.summarizeOverdue(
            deliveries = listOf(recentUtang, exact3DaysUtang, old5DaysUtang, paidOldUtang, oldCash),
            referenceDate = today,
        )

        assertFalse(PautangOverdueHelper.isOverdue(recentUtang, today))
        assertTrue(PautangOverdueHelper.isOverdue(exact3DaysUtang, today))
        assertTrue(PautangOverdueHelper.isOverdue(old5DaysUtang, today))
        assertFalse(PautangOverdueHelper.isOverdue(paidOldUtang, today))
        assertFalse(PautangOverdueHelper.isOverdue(oldCash, today))

        assertEquals(2, summary.overdueCount)
        assertEquals(180, summary.totalOverdueAmount) // 60 + 120
        assertEquals(setOf("u3d", "u5d"), summary.overdueIds)
        assertEquals(3L, summary.daysElapsedByDeliveryId["u3d"])
        assertEquals(5L, summary.daysElapsedByDeliveryId["u5d"])

        // When u3d and u5d are marked paid, overdue alert clears completely
        val clearedSummary = PautangOverdueHelper.summarizeOverdue(
            deliveries = listOf(
                recentUtang,
                exact3DaysUtang.copy(amountPaid = 90, settledDateKey = "2026-11-21"),
                old5DaysUtang.copy(amountPaid = 120, settledDateKey = "2026-11-21"),
                paidOldUtang,
            ),
            referenceDate = today,
        )
        assertEquals(0, clearedSummary.overdueCount)
        assertEquals(0, clearedSummary.totalOverdueAmount)
    }

    @Test
    fun `payment breakdown helper separates cash to remit from bank transfer and deposit while keeping commission`() {
        val today = "2026-11-21"
        val cashDelivery = delivery("c1", today, "Cash Store", 10, 0, PaymentType.CASH, 300, 8, 80, today)
        val bankTransferDelivery = delivery(
            "b1", today, "Ate Maria", 5, 0, PaymentType.BANK_DEPOSIT, 150, 8, 40, today
        ).copy(notes = PaymentBreakdownHelper.applyChannelTag("", PaidChannel.BANK_TRANSFER))
        val bankDepositDelivery = delivery(
            "b2", today, "Kuya Jun", 4, 0, PaymentType.BANK_DEPOSIT, 120, 8, 32, today
        ).copy(notes = PaymentBreakdownHelper.applyChannelTag("", PaidChannel.BANK_DEPOSIT))
        val settledUtangTransfer = delivery(
            "u1", "2026-11-18", "Settled Transfer", 2, 0, PaymentType.PAUTANG, 60, 8, 16, today
        )

        val payments = listOf(
            UtangPaymentRecord(
                id = "p1",
                deliveryId = "u1",
                userId = "u1",
                dateKey = today,
                amount = 60,
                method = UtangMethod.BANK_DEPOSIT,
                notes = PaymentBreakdownHelper.applyChannelTag("GCash transfer", PaidChannel.BANK_TRANSFER),
                pendingSync = false,
                createdAtEpochMs = 0L,
            )
        )
        val expenses = listOf(
            ExpenseRecord("e1", "u1", today, ExpenseCategory.GAS, 100, "", false, 0L, 0L)
        )

        val breakdown = PaymentBreakdownHelper.calculateBreakdown(
            dateKey = today,
            deliveries = listOf(cashDelivery, bankTransferDelivery, bankDepositDelivery, settledUtangTransfer),
            utangPayments = payments,
            expenses = expenses,
        )

        assertEquals(10, breakdown.cashPaidGallons)
        assertEquals(7, breakdown.bankTransferGallons) // 5 direct + 2 settled utang
        assertEquals(4, breakdown.bankDepositGallons)
        assertEquals(300, breakdown.cashCollected)
        assertEquals(100, breakdown.cashExpenses)
        assertEquals(200, breakdown.cashToRemit) // 300 - 100
        assertEquals(210, breakdown.bankTransferAmount) // 150 + 60
        assertEquals(120, breakdown.bankDepositAmount)
        assertEquals(330, breakdown.bankPaidTotal)
        assertEquals(80, breakdown.commissionFromCash)
        assertEquals(88, breakdown.commissionFromBank) // 40 + 32 + 16
        assertEquals(168, breakdown.totalPaidCommission)
        assertEquals("Bank Transfer", PaymentBreakdownHelper.displayMethodLabel(bankTransferDelivery))
        assertEquals("Bank Deposit", PaymentBreakdownHelper.displayMethodLabel(bankDepositDelivery))
    }

    @Test
    fun `delivery voice parser extracts gallons classification payment mode and saved price`() {
        val customers = listOf(
            CustomerRecord(id = "ate_maria", userId = "u1", name = "Ate Maria", lastUnitPrice = 25.0),
            CustomerRecord(id = "kuya_jun", userId = "u1", name = "Kuya Jun", lastUnitPrice = 28.0),
        )

        val pickupParsed = DeliveryVoiceParser.parse("5 outlet pick up Ate Maria", customers)
        assertEquals(5, pickupParsed.gallons)
        assertEquals(CustomerClassification.OUTLET, pickupParsed.classification)
        assertEquals(PaymentType.PICK_UP, pickupParsed.paymentType)
        assertEquals("Ate Maria", pickupParsed.customerName)
        assertEquals(25.0, pickupParsed.rememberedUnitPrice ?: 0.0, 0.001)

        val utangParsed = DeliveryVoiceParser.parse("3 residential pautang Kuya Jun", customers)
        assertEquals(3, utangParsed.gallons)
        assertEquals(CustomerClassification.RESIDENTIAL, utangParsed.classification)
        assertEquals(PaymentType.PAUTANG, utangParsed.paymentType)
        assertEquals("Kuya Jun", utangParsed.customerName)
        assertEquals(28.0, utangParsed.rememberedUnitPrice ?: 0.0, 0.001)

        val transferParsed = DeliveryVoiceParser.parse("10 alkaline bank transfer Aling Nena", customers)
        assertEquals(10, transferParsed.gallons)
        assertTrue(transferParsed.isAlkaline)
        assertNull(transferParsed.classification)
        assertEquals(PaymentType.BANK_DEPOSIT, transferParsed.paymentType)
        assertEquals(PaidChannel.BANK_TRANSFER, transferParsed.paidChannel)
        assertEquals("Aling Nena", transferParsed.customerName)
        assertNull(transferParsed.rememberedUnitPrice)
    }

    @Test
    fun `sunday brownout makeup helper keeps Mon-Sat standard and appends Sunday only when enabled`() {
        val monday = LocalDate.of(2026, 11, 16)
        val friday = "2026-11-20"
        val sunday = "2026-11-22"

        val deliveries = listOf(
            delivery("d_fri", friday, "Friday Customer", 10, 0, PaymentType.CASH, 300, 8, 80, friday),
            delivery("d_sun", sunday, "Sunday Makeup", 8, 0, PaymentType.CASH, 240, 8, 64, sunday),
        )

        val baseWeekly = MinigeCalculator.calculateWeeklySummary(
            anchorDate = monday,
            deliveries = deliveries,
            utangPayments = emptyList(),
            expenses = emptyList(),
        )
        val sundayDay = SundayMakeupHelper.calculateSundayDaySummary(
            monday = baseWeekly.monday,
            deliveries = deliveries,
            utangPayments = emptyList(),
            expenses = emptyList(),
        )

        // Standard Mon-Sat mode excludes Sunday
        val standardResult = SundayMakeupHelper.applySundayMakeup(baseWeekly, sundayDay, enabled = false)
        assertEquals(6, standardResult.days.size)
        assertEquals(10, standardResult.totalPaidGallons)
        assertEquals(80, standardResult.totalCommission)

        // Sunday Makeup mode includes Sunday totals and day row
        val makeupResult = SundayMakeupHelper.applySundayMakeup(baseWeekly, sundayDay, enabled = true)
        assertEquals(7, makeupResult.days.size)
        assertEquals(18, makeupResult.totalPaidGallons)
        assertEquals(144, makeupResult.totalCommission)
        assertEquals(540, makeupResult.totalCashCollected)
        assertEquals(LocalDate.of(2026, 11, 22), makeupResult.days.last().date)
    }

    @Test
    fun `customer directory helper indexes suki history area classification and unpaid gallons`() {
        val customers = listOf(
            CustomerRecord(id = "ate_maria", userId = "u1", name = "Ate Maria", lastUnitPrice = 25.0, updatedAtEpochMs = 200L),
        )
        val utangNotes = CustomerDirectoryHelper.applyRouteAreaTag(
            DeliveryVoiceParser.applyClassificationTag("", CustomerClassification.OUTLET),
            "Poblacion",
        )
        val deliveries = listOf(
            delivery("d1", "2026-11-20", "Ate Maria", 5, 0, PaymentType.PAUTANG, 60, 8, 0, null)
                .copy(notes = utangNotes, updatedAtEpochMs = 200L),
            delivery("d2", "2026-11-19", "Kuya Jun", 3, 0, PaymentType.CASH, 90, 8, 24, "2026-11-19")
                .copy(notes = "[Residential] [Area:Market]", updatedAtEpochMs = 150L),
        )
        val payments = listOf(
            UtangPaymentRecord(
                id = "p1",
                deliveryId = "d1",
                userId = "u1",
                dateKey = "2026-11-21",
                amount = 60,
                method = UtangMethod.CASH,
                notes = PartialPaymentHelper.applyPartialGallonTag("", paidGallons = 2, commissionEarned = 16, channel = PaidChannel.CASH),
                pendingSync = false,
                createdAtEpochMs = 210L,
            )
        )

        val directory = CustomerDirectoryHelper.buildDirectory(customers, deliveries, payments)
        val maria = directory.first { it.id == "ate_maria" }
        assertEquals(CustomerClassification.OUTLET, maria.classification)
        assertEquals("Poblacion", maria.routeArea)
        assertEquals(25.0, maria.savedUnitPrice ?: 0.0, 0.001)
        assertEquals(5, maria.lastOrderGallons)
        assertEquals(3, maria.unpaidUtangGallons) // 5 total - 2 paid = 3 unpaid
        assertEquals(90, maria.unpaidUtangAmount) // 150 - 60 = 90

        val filtered = CustomerDirectoryHelper.filterSuggestions(directory, "Poblacion")
        assertEquals(1, filtered.size)
        assertEquals("Ate Maria", filtered.first().name)
    }

    @Test
    fun `partial payment helper deducts paid gallons and awards immediate commission without double counting`() {
        val day1 = "2026-11-21"
        val day2 = "2026-11-22"

        // Day 0: 5 gallons on pautang (₱150 total). Day 1: Partial Pay 2 gallons in Cash (₱60, +₱16 comm).
        val pautangDelivery = delivery("d1", "2026-11-20", "Suki Store", 5, 0, PaymentType.PAUTANG, 60, 8, 0, null)
        val day1Payment = UtangPaymentRecord(
            id = "p1",
            deliveryId = "d1",
            userId = "u1",
            dateKey = day1,
            amount = 60,
            method = UtangMethod.CASH,
            notes = PartialPaymentHelper.applyPartialGallonTag("", paidGallons = 2, commissionEarned = 16, channel = PaidChannel.CASH),
            pendingSync = false,
            createdAtEpochMs = 1L,
        )

        val statusBeforeDay2 = PartialPaymentHelper.getStatus(pautangDelivery, listOf(day1Payment))
        assertEquals(2, statusBeforeDay2.paidGallonsSoFar)
        assertEquals(3, statusBeforeDay2.remainingUnpaidGallons)
        assertEquals(90, PartialPaymentHelper.calculateAmountForGallons(statusBeforeDay2, 3))
        assertEquals(30, PartialPaymentHelper.calculateAmountForGallons(statusBeforeDay2, 1))

        val day1Summary = MinigeCalculator.calculateDailySummary(
            dateKey = day1,
            deliveries = listOf(pautangDelivery),
            utangPayments = listOf(day1Payment),
            expenses = emptyList(),
            startingSukli = 0,
        )
        assertEquals(2, day1Summary.settledUtangGallons)
        assertEquals(16, day1Summary.dailyCommission)
        assertEquals(60, day1Summary.cashCollected)

        // Day 2: Remaining 3 gallons paid via Bank Transfer (₱90, +₱24 comm)
        val day2Payment = UtangPaymentRecord(
            id = "p2",
            deliveryId = "d1",
            userId = "u1",
            dateKey = day2,
            amount = 90,
            method = UtangMethod.BANK_DEPOSIT,
            notes = PartialPaymentHelper.applyPartialGallonTag("", paidGallons = 3, commissionEarned = 24, channel = PaidChannel.BANK_TRANSFER),
            pendingSync = false,
            createdAtEpochMs = 2L,
        )
        val settledDelivery = pautangDelivery.copy(amountPaid = 150, commissionEarned = 40, settledDateKey = day2)

        val day2Summary = MinigeCalculator.calculateDailySummary(
            dateKey = day2,
            deliveries = listOf(settledDelivery),
            utangPayments = listOf(day1Payment, day2Payment),
            expenses = emptyList(),
            startingSukli = 0,
        )
        // Must count ONLY the 3 remaining gallons (₱24) on day2, never double counting Day 1's 2 gallons!
        assertEquals(3, day2Summary.settledUtangGallons)
        assertEquals(24, day2Summary.dailyCommission)
        assertEquals(90, day2Summary.bankDepositTotal)

        val day2Breakdown = PaymentBreakdownHelper.calculateBreakdown(
            dateKey = day2,
            deliveries = listOf(settledDelivery),
            utangPayments = listOf(day1Payment, day2Payment),
            expenses = emptyList(),
        )
        assertEquals(3, day2Breakdown.bankTransferGallons)
        assertEquals(24, day2Breakdown.commissionFromBank)
    }

    @Test
    fun `commission rate history preserves historical dates and applies effective future rates chronologically`() {
        val hist0 = emptyList<com.melvin.minige.util.CommissionRateEntry>()
        val hist1 = CommissionRateHistory.addOrUpdateEntry(hist0, LocalDate.of(2026, 11, 25), 10.0)
        val hist2 = CommissionRateHistory.addOrUpdateEntry(hist1, LocalDate.of(2026, 12, 1), 12.0)

        assertEquals(8.0, CommissionRateHistory.effectiveRateForDate(hist2, LocalDate.of(2026, 11, 21)), 0.001)
        assertEquals(8.0, CommissionRateHistory.effectiveRateForDate(hist2, LocalDate.of(2026, 11, 24)), 0.001)
        assertEquals(10.0, CommissionRateHistory.effectiveRateForDate(hist2, LocalDate.of(2026, 11, 25)), 0.001)
        assertEquals(10.0, CommissionRateHistory.effectiveRateForDate(hist2, LocalDate.of(2026, 11, 30)), 0.001)
        assertEquals(12.0, CommissionRateHistory.effectiveRateForDate(hist2, LocalDate.of(2026, 12, 1)), 0.001)

        val roundTrip = CommissionRateHistory.deserialize(CommissionRateHistory.serialize(hist2))
        assertEquals(2, roundTrip.size)
        assertEquals(10.0, roundTrip[0].ratePerGallon, 0.001)
        assertEquals(12.0, roundTrip[1].ratePerGallon, 0.001)
    }

    @Test
    fun `delivery filter helper filters by search text classification paid pautang and bank chips`() {
        val outletCash = delivery("1", "2026-11-21", "Outlet A", 5, 0, PaymentType.CASH, 150)
            .copy(notes = "[Outlet] [Area:North]")
        val residentialBank = delivery("2", "2026-11-21", "Home B", 3, 0, PaymentType.BANK_DEPOSIT, 90)
            .copy(notes = "[Residential] [Bank Transfer] [Area:South]")
        val unpaidPautang = delivery("3", "2026-11-21", "Credit C", 4, 0, PaymentType.PAUTANG, 0)
            .copy(notes = "[Outlet] [Area:Market]")

        val all = listOf(outletCash, residentialBank, unpaidPautang)
        assertEquals(3, DeliveryFilterHelper.filterDeliveries(all, "", DeliveryFilterChip.ALL).size)
        assertEquals(listOf(outletCash, unpaidPautang), DeliveryFilterHelper.filterDeliveries(all, "", DeliveryFilterChip.OUTLET))
        assertEquals(listOf(residentialBank), DeliveryFilterHelper.filterDeliveries(all, "", DeliveryFilterChip.RESIDENTIAL))
        assertEquals(listOf(outletCash, residentialBank), DeliveryFilterHelper.filterDeliveries(all, "", DeliveryFilterChip.PAID))
        assertEquals(listOf(unpaidPautang), DeliveryFilterHelper.filterDeliveries(all, "", DeliveryFilterChip.PAUTANG))
        assertEquals(listOf(residentialBank), DeliveryFilterHelper.filterDeliveries(all, "", DeliveryFilterChip.BANK))
        assertEquals(listOf(unpaidPautang), DeliveryFilterHelper.filterDeliveries(all, "Market", DeliveryFilterChip.ALL))
    }

    @Test
    fun `daily closing helper computes private remittance metrics separate from boss pickup format`() {
        val today = "2026-11-21"
        val cash = delivery("c1", today, "Ate Maria", 10, 0, PaymentType.CASH, 300, 8, 80, today)
        val bank = delivery("b1", today, "Canteen", 5, 0, PaymentType.BANK_DEPOSIT, 150, 8, 40, today)
            .copy(notes = PaymentBreakdownHelper.applyChannelTag("", PaidChannel.BANK_TRANSFER))
        val pautang = delivery("u1", today, "Kuya Jun", 3, 0, PaymentType.PAUTANG, 0, 8, 0, null)
        val pickup = delivery("p1", today, "Nene", 12, 0, PaymentType.PICK_UP, 0, 8, 0, null)
        val expenses = listOf(ExpenseRecord("e1", "u1", today, ExpenseCategory.GAS, 80, "", false, 0L, 0L))

        val deliveries = listOf(cash, bank, pautang, pickup)
        val summary = MinigeCalculator.calculateDailySummary(today, deliveries, emptyList(), expenses, startingSukli = 300)
        val breakdown = PaymentBreakdownHelper.calculateBreakdown(today, deliveries, emptyList(), expenses)
        val closing = DailyClosingHelper.compute(
            dateKey = today,
            summary = summary,
            breakdown = breakdown,
            activeCommissionRate = 8.0,
            defaultStartingSukli = 250,
        )

        assertEquals(12, closing.totalPickedUpGallons)
        assertEquals(15, closing.totalPaidGallons)
        assertEquals(3, closing.pautangUnpaidGallons)
        assertEquals(120, closing.totalCommissionEarned)
        assertEquals(150, closing.bankTransferAmount)
        assertEquals(300, closing.retainedSukli)
        assertEquals(250, closing.defaultStartingSukli)
        assertEquals(220, closing.netCashToRemitToBoss)

        // Boss pickup message still contains ONLY pick-up data
        val bossMsg = BossPickupFormatter.format(LocalDate.of(2026, 11, 21), listOf(pickup))
        assertFalse(bossMsg.contains("₱"))
        assertFalse(bossMsg.contains("Remit", ignoreCase = true))
        assertTrue(DailyClosingHelper.formatPrivateClosingText(closing).contains("Net Cash to Remit to Boss: ₱220"))
    }

    @Test
    fun `route grouping helper groups by area tag and detects unvisited suki stops`() {
        val today = "2026-11-21"
        val maria = delivery("d1", today, "Ate Maria", 6, 0, PaymentType.CASH, 180, 8, 48, today)
            .copy(notes = "[Area:Poblacion]")
        val untagged = delivery("d2", today, "Walkin", 2, 0, PaymentType.PICK_UP, 0)
        val sukiDirectory = CustomerDirectoryHelper.buildDirectory(
            pricingCustomers = emptyList(),
            deliveries = listOf(
                maria,
                delivery("d0", "2026-11-19", "Kuya Jun", 4, 0, PaymentType.CASH, 120, 8, 32, "2026-11-19")
                    .copy(notes = "[Area:Poblacion]"),
            ),
            utangPayments = emptyList(),
        )

        val result = RouteGroupingHelper.groupDeliveriesByArea(
            deliveriesForDay = listOf(maria, untagged),
            sukiDirectory = sukiDirectory,
        )

        assertEquals(2, result.groups.size)
        assertEquals("Poblacion", result.groups[0].areaName)
        assertEquals(6, result.groups[0].totalGallons)
        assertEquals(listOf("Kuya Jun"), result.groups[0].unvisitedSuki.map { it.name })
        assertEquals(RouteGroupingHelper.UNASSIGNED_AREA, result.groups[1].areaName)
        assertEquals(1, result.totalUnvisitedCount)
    }

    @Test
    fun `suki analytics helper computes weekly outlet vs residential totals and top suki`() {
        val monday = LocalDate.of(2026, 11, 16)
        val weekDates = com.melvin.minige.domain.MinigeDateUtils.weekMonToSat(monday)
        val canteen = delivery("1", "2026-11-17", "Canteen Alpha", 20, 0, PaymentType.CASH, 600, 8, 160, "2026-11-17")
            .copy(notes = "[Outlet]")
        val home = delivery("2", "2026-11-18", "House Beta", 5, 0, PaymentType.CASH, 150, 8, 40, "2026-11-18")
            .copy(notes = "[Residential]")
        val partialUtang = delivery("3", "2026-11-19", "Canteen Alpha", 10, 0, PaymentType.PAUTANG, 60, 8, 0, null)
            .copy(notes = "[Outlet]")
        val partialPay = UtangPaymentRecord(
            id = "p1",
            deliveryId = "3",
            userId = "u1",
            dateKey = "2026-11-20",
            amount = 60,
            method = UtangMethod.CASH,
            notes = PartialPaymentHelper.applyPartialGallonTag("", paidGallons = 2, commissionEarned = 16, channel = PaidChannel.CASH),
            pendingSync = false,
            createdAtEpochMs = 1L,
        )

        val analytics = SukiAnalyticsHelper.compute(
            weekDates = weekDates,
            deliveries = listOf(canteen, home, partialUtang),
            utangPayments = listOf(partialPay),
        )

        assertEquals(30, analytics.outletGallons)
        assertEquals(176, analytics.outletPaidCommission) // 160 direct + 16 partial pay
        assertEquals(5, analytics.residentialGallons)
        assertEquals(40, analytics.residentialPaidCommission)
        assertEquals("Canteen Alpha", analytics.topSuki.first().customerName)
        assertEquals(30, analytics.topSuki.first().totalGallons)
        assertEquals(176, analytics.topSuki.first().commissionEarned)
    }

    @Test
    fun `daily quota progress computes handled gallons and clamps fraction`() {
        val today = "2026-11-21"
        val summary = MinigeCalculator.calculateDailySummary(
            dateKey = today,
            deliveries = listOf(
                delivery("c1", today, "A", 20, 0, PaymentType.CASH, 600, 8, 160, today),
                delivery("u1", today, "B", 5, 0, PaymentType.PAUTANG, 0, 8, 0, null),
                delivery("p1", today, "C", 10, 0, PaymentType.PICK_UP, 0, 8, 0, null),
            ),
            utangPayments = emptyList(),
            expenses = emptyList(),
            startingSukli = 0,
        )
        val progress = DailyQuotaPrefs.computeProgress(summary, targetGallons = 50)
        assertEquals(35, progress.currentGallons)
        assertEquals(50, progress.targetGallons)
        assertEquals(160, progress.commissionEarned)
        assertEquals(0.7f, progress.fraction, 0.001f)
    }

    @Test
    fun `undo state helper enforces 5 second auto dismiss window`() {
        val snapshot = delivery("d1", "2026-11-21", "Ate Maria", 5, 0, PaymentType.PAUTANG, 0)
        val action = UndoableAction.UtangPaymentRecorded(
            previousDelivery = snapshot,
            createdPaymentId = "pay_1",
            message = "Marked Ate Maria paid",
            createdAtEpochMs = 10_000L,
        )

        assertFalse(UndoStateHelper.shouldAutoDismiss(action, nowEpochMs = 12_500L))
        assertEquals(3, UndoStateHelper.remainingSeconds(action, nowEpochMs = 12_500L))
        assertTrue(UndoStateHelper.shouldAutoDismiss(action, nowEpochMs = 15_000L))
        assertEquals(0, UndoStateHelper.remainingSeconds(action, nowEpochMs = 15_000L))
        assertFalse(UndoStateHelper.shouldAutoDismiss(null, nowEpochMs = 20_000L))
    }

    @Test
    fun `screen guide catalog provides taglish explanations and examples for all tabs`() {
        val todayGuide = ScreenGuideCatalog.forTab(GuideScreenTab.TODAY)
        assertTrue(todayGuide.items.any { it.badgeOrButton == "Closing" })
        assertTrue(todayGuide.items.any { it.badgeOrButton == "By Area" })
        assertTrue(todayGuide.items.any { it.badgeOrButton == "Daily Hakot" })
        assertTrue(todayGuide.items.any { it.badgeOrButton == "Sync Status Dot" })

        val pautangGuide = ScreenGuideCatalog.forTab(GuideScreenTab.PAUTANG)
        assertTrue(pautangGuide.items.any { it.badgeOrButton == "Partial Pay" })

        GuideScreenTab.entries.forEach { tab ->
            val guide = ScreenGuideCatalog.forTab(tab)
            assertTrue(guide.items.isNotEmpty())
            assertTrue(guide.items.all { it.taglishExplanation.isNotBlank() && it.example.isNotBlank() })
        }
    }
}
