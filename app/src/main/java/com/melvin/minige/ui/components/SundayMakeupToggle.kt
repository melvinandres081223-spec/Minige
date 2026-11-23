package com.melvin.minige.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.MinigeCalculator
import com.melvin.minige.domain.MinigeDateUtils
import com.melvin.minige.domain.UtangPaymentRecord
import android.content.Context
import com.melvin.minige.domain.WeeklyDaySummary
import com.melvin.minige.domain.WeeklySummary
import java.time.LocalDate

object SundayMakeupPrefs {
    private const val PREFS_NAME = "minige_sunday_makeup_prefs"
    private const val KEY_ENABLED = "sunday_makeup_enabled_"

    fun isEnabled(context: Context, userId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("$KEY_ENABLED$userId", false)
    }

    fun setEnabled(context: Context, userId: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("$KEY_ENABLED$userId", enabled).apply()
    }
}

object SundayMakeupHelper {

    fun calculateSundayDaySummary(
        monday: LocalDate,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
        expenses: List<ExpenseRecord>,
    ): WeeklyDaySummary {
        val sunday = monday.plusDays(6)
        val key = MinigeDateUtils.toKey(sunday)
        val daily = MinigeCalculator.calculateDailySummary(
            dateKey = key,
            deliveries = deliveries,
            utangPayments = utangPayments,
            expenses = expenses,
            startingSukli = 0,
        )
        return WeeklyDaySummary(
            date = sunday,
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

    fun applySundayMakeup(
        baseWeekly: WeeklySummary,
        sundaySummary: WeeklyDaySummary,
        enabled: Boolean,
    ): WeeklySummary {
        if (!enabled) return baseWeekly
        val days = baseWeekly.days + sundaySummary
        return WeeklySummary(
            monday = baseWeekly.monday,
            saturday = sundaySummary.date,
            totalPaidGallons = days.sumOf { it.paidGallons },
            totalCommission = days.sumOf { it.commission },
            totalCashCollected = days.sumOf { it.cashCollected },
            totalBankDeposit = days.sumOf { it.bankDeposit },
            totalExpenses = days.sumOf { it.expenses },
            days = days,
            totalCashAdvance = days.sumOf { it.cashAdvance },
        )
    }
}

@Composable
fun SundayMakeupToggle(
    enabled: Boolean,
    sundaySummary: WeeklyDaySummary,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("sunday_makeup_card"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Sunday Brownout Makeup Mode",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                val subtitle = if (enabled) {
                    "Active (${MinigeDateUtils.formatShort(sundaySummary.date)}): +${sundaySummary.paidGallons} gals • ₱${sundaySummary.commission} included"
                } else {
                    "Standard schedule Mon–Sat. Enable if Saturday had a brownout."
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("sunday_makeup_switch"),
            )
        }
    }
}
