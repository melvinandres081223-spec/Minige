package com.melvin.minige.util

import android.content.Context
import androidx.compose.runtime.Immutable
import com.melvin.minige.domain.MinigeDateUtils
import java.time.LocalDate
import kotlin.math.roundToInt

@Immutable
data class CommissionRateEntry(
    val effectiveDateKey: String,
    val ratePerGallon: Double,
    val updatedAtEpochMs: Long = 0L,
)

object CommissionRateHistory {

    const val DEFAULT_RATE: Double = 8.0
    private const val PREFS_NAME = "minige_commission_rate_history"
    private const val KEY_PREFIX = "rates_"

    fun addOrUpdateEntry(
        history: List<CommissionRateEntry>,
        effectiveDate: LocalDate,
        ratePerGallon: Double,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<CommissionRateEntry> {
        val dateKey = MinigeDateUtils.toKey(effectiveDate)
        val clampedRate = ratePerGallon.coerceIn(0.0, 1000.0)
        val next = history
            .filterNot { it.effectiveDateKey == dateKey }
            .plus(
                CommissionRateEntry(
                    effectiveDateKey = dateKey,
                    ratePerGallon = clampedRate,
                    updatedAtEpochMs = nowEpochMs,
                )
            )
        return next.sortedBy { it.effectiveDateKey }
    }

    /**
     * Resolves the applicable per-gallon commission rate for a specific delivery/settlement date.
     * Chronological entries whose effectiveDateKey <= targetDate apply; earlier historical dates
     * remain unaffected when a future effective date is added.
     */
    fun effectiveRateForDate(
        history: List<CommissionRateEntry>,
        targetDate: LocalDate,
        fallbackRate: Double = DEFAULT_RATE,
    ): Double {
        val targetKey = MinigeDateUtils.toKey(targetDate)
        val matching = history
            .filter { it.effectiveDateKey <= targetKey }
            .maxByOrNull { it.effectiveDateKey }
        return matching?.ratePerGallon ?: fallbackRate
    }

    fun calculateCommissionAmount(paidGallons: Int, ratePerGallon: Double): Int {
        if (paidGallons <= 0 || ratePerGallon <= 0.0) return 0
        return (paidGallons * ratePerGallon).roundToInt()
    }

    fun formatRate(rate: Double): String {
        return String.format(java.util.Locale.ENGLISH, "%.2f", rate)
    }

    fun serialize(entries: List<CommissionRateEntry>): String {
        return entries
            .sortedBy { it.effectiveDateKey }
            .joinToString("\n") { entry ->
                "${entry.effectiveDateKey}|${entry.ratePerGallon}|${entry.updatedAtEpochMs}"
            }
    }

    fun deserialize(raw: String): List<CommissionRateEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size < 2) return@mapNotNull null
                val dateKey = parts[0].trim()
                val rate = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                val updatedAt = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                if (!dateKey.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) return@mapNotNull null
                CommissionRateEntry(
                    effectiveDateKey = dateKey,
                    ratePerGallon = rate,
                    updatedAtEpochMs = updatedAt,
                )
            }
            .sortedBy { it.effectiveDateKey }
            .toList()
    }

    fun loadHistory(context: Context, userId: String): List<CommissionRateEntry> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("$KEY_PREFIX$userId", "") ?: ""
        return deserialize(raw)
    }

    fun saveHistory(context: Context, userId: String, entries: List<CommissionRateEntry>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("$KEY_PREFIX$userId", serialize(entries))
            .apply()
    }
}
