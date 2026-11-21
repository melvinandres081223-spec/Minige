package com.melvin.minige

import com.melvin.minige.domain.BossPickupFormatter
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.MinigeCalculator
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.util.PautangOverdueHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
