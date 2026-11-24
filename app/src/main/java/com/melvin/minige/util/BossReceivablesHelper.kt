package com.melvin.minige.util

import android.content.Context
import androidx.compose.runtime.Immutable
import com.melvin.minige.domain.MinigeDateUtils
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

@Immutable
data class BossReceivableEntry(
    val id: String,
    val periodLabel: String,
    val amount: Int,
    val gallons: Int? = null,
    val ratePerGallon: Double? = null,
    val note: String = "",
    val isPaid: Boolean = false,
    val paidDateKey: String? = null,
    val createdAtEpochMs: Long = 0L,
    val paidAtEpochMs: Long? = null,
)

object BossReceivablesPrefs {

    private const val PREFS_NAME = "minige_boss_receivables_prefs"
    private const val KEY_ENTRIES = "boss_receivables_"

    fun totalUnpaidBalance(entries: List<BossReceivableEntry>): Int =
        entries.filter { !it.isPaid }.sumOf { it.amount.coerceAtLeast(0) }

    fun totalPaidHistory(entries: List<BossReceivableEntry>): Int =
        entries.filter { it.isPaid }.sumOf { it.amount.coerceAtLeast(0) }

    fun calculateGallonsAmount(gallons: Int, ratePerGallon: Double): Int {
        if (gallons <= 0 || ratePerGallon <= 0.0) return 0
        return (gallons * ratePerGallon).roundToInt().coerceAtLeast(0)
    }

    fun addEntry(
        entries: List<BossReceivableEntry>,
        periodLabel: String,
        amount: Int,
        gallons: Int? = null,
        ratePerGallon: Double? = null,
        note: String = "",
        id: String = UUID.randomUUID().toString(),
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<BossReceivableEntry> {
        val cleanPeriod = periodLabel.trim().take(80).ifEmpty {
            MinigeDateUtils.formatShort(LocalDate.now())
        }
        val cleanAmount = amount.coerceAtLeast(0)
        if (cleanAmount <= 0) return entries
        val cleanGallons = gallons?.takeIf { it > 0 }
        val cleanRate = ratePerGallon?.takeIf { it > 0.0 }
        val newEntry = BossReceivableEntry(
            id = id,
            periodLabel = cleanPeriod,
            amount = cleanAmount,
            gallons = cleanGallons,
            ratePerGallon = cleanRate,
            note = note.trim().take(240),
            isPaid = false,
            paidDateKey = null,
            createdAtEpochMs = nowEpochMs,
            paidAtEpochMs = null,
        )
        return listOf(newEntry) + entries.filterNot { it.id == id }
    }

    fun markPaid(
        entries: List<BossReceivableEntry>,
        entryId: String,
        paidDateKey: String = MinigeDateUtils.toKey(LocalDate.now()),
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<BossReceivableEntry> {
        return entries.map { entry ->
            if (entry.id == entryId) {
                entry.copy(
                    isPaid = true,
                    paidDateKey = paidDateKey,
                    paidAtEpochMs = nowEpochMs,
                )
            } else {
                entry
            }
        }
    }

    fun deleteEntry(
        entries: List<BossReceivableEntry>,
        entryId: String,
    ): List<BossReceivableEntry> = entries.filterNot { it.id == entryId }

    fun mergeRestoredEntries(
        existing: List<BossReceivableEntry>,
        restored: List<BossReceivableEntry>,
    ): List<BossReceivableEntry> {
        if (restored.isEmpty()) return existing
        val merged = linkedMapOf<String, BossReceivableEntry>()
        restored.forEach { merged[it.id] = it }
        existing.forEach { entry ->
            if (!merged.containsKey(entry.id)) {
                merged[entry.id] = entry
            }
        }
        return merged.values.sortedByDescending { it.createdAtEpochMs }
    }

    fun serializeToJsonArray(entries: List<BossReceivableEntry>): JSONArray {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(JSONObject().apply {
                put("id", entry.id)
                put("periodLabel", entry.periodLabel)
                put("amount", entry.amount)
                entry.gallons?.let { put("gallons", it) }
                entry.ratePerGallon?.let { put("ratePerGallon", it) }
                put("note", entry.note)
                put("isPaid", entry.isPaid)
                entry.paidDateKey?.let { put("paidDateKey", it) }
                put("createdAtEpochMs", entry.createdAtEpochMs)
                entry.paidAtEpochMs?.let { put("paidAtEpochMs", it) }
            })
        }
        return arr
    }

    fun deserializeFromJsonArray(arr: JSONArray?): List<BossReceivableEntry> {
        if (arr == null) return emptyList()
        val result = mutableListOf<BossReceivableEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: continue
            val amount = obj.optInt("amount", 0)
            if (amount <= 0) continue
            val gallons = if (obj.has("gallons") && !obj.isNull("gallons")) {
                obj.optInt("gallons", 0).takeIf { it > 0 }
            } else null
            val rate = if (obj.has("ratePerGallon") && !obj.isNull("ratePerGallon")) {
                obj.optDouble("ratePerGallon", 0.0).takeIf { !it.isNaN() && it > 0.0 }
            } else null
            val paidDateKey = if (obj.has("paidDateKey") && !obj.isNull("paidDateKey")) {
                obj.optString("paidDateKey", "").takeIf { it.isNotBlank() }
            } else null
            val paidAt = if (obj.has("paidAtEpochMs") && !obj.isNull("paidAtEpochMs")) {
                obj.optLong("paidAtEpochMs", 0L).takeIf { it > 0L }
            } else null

            result += BossReceivableEntry(
                id = id,
                periodLabel = obj.optString("periodLabel", "").ifBlank { "Past Unpaid" },
                amount = amount,
                gallons = gallons,
                ratePerGallon = rate,
                note = obj.optString("note", ""),
                isPaid = obj.optBoolean("isPaid", false),
                paidDateKey = paidDateKey,
                createdAtEpochMs = obj.optLong("createdAtEpochMs", 0L),
                paidAtEpochMs = paidAt,
            )
        }
        return result.sortedByDescending { it.createdAtEpochMs }
    }

    fun serializeToJsonString(entries: List<BossReceivableEntry>): String =
        serializeToJsonArray(entries).toString()

    fun deserializeFromJsonString(raw: String?): List<BossReceivableEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            deserializeFromJsonArray(JSONArray(raw))
        }.getOrElse { emptyList() }
    }

    fun loadEntries(context: Context, userId: String): List<BossReceivableEntry> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("$KEY_ENTRIES$userId", null)
        return deserializeFromJsonString(raw)
    }

    fun saveEntries(context: Context, userId: String, entries: List<BossReceivableEntry>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("$KEY_ENTRIES$userId", serializeToJsonString(entries)).apply()
    }
}
