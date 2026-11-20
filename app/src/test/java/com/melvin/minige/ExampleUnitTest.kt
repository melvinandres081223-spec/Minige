package com.melvin.minige

import com.melvin.minige.domain.BossPickupFormatter
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.MinigeCalculator
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
