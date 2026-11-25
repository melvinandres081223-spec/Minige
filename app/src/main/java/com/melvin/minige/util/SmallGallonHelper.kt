package com.melvin.minige.util

import android.content.Context

object SmallGallonPrefs {
    private const val PREFS_NAME = "minige_small_gallon_prefs"
    private const val KEY_PRICE = "small_gallon_price_"
    private const val KEY_COMMISSION_RATE = "small_gallon_commission_rate_"

    fun loadPrice(context: Context, userId: String): Double {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getFloat("$KEY_PRICE$userId", SmallGallonHelper.DEFAULT_PRICE.toFloat()).toDouble()
        return if (raw.isNaN() || raw < 0.0) SmallGallonHelper.DEFAULT_PRICE else raw
    }

    fun savePrice(context: Context, userId: String, price: Double): Double {
        val clamped = if (price.isNaN() || price < 0.0) SmallGallonHelper.DEFAULT_PRICE else price
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat("$KEY_PRICE$userId", clamped.toFloat()).apply()
        return clamped
    }

    fun loadCommissionRate(context: Context, userId: String): Double {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getFloat(
            "$KEY_COMMISSION_RATE$userId",
            SmallGallonHelper.DEFAULT_COMMISSION_RATE.toFloat(),
        ).toDouble()
        return if (raw.isNaN() || raw < 0.0) SmallGallonHelper.DEFAULT_COMMISSION_RATE else raw
    }

    fun saveCommissionRate(context: Context, userId: String, rate: Double): Double {
        val clamped = if (rate.isNaN() || rate < 0.0) SmallGallonHelper.DEFAULT_COMMISSION_RATE else rate
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat("$KEY_COMMISSION_RATE$userId", clamped.toFloat()).apply()
        return clamped
    }
}

object SmallGallonHelper {
    const val DEFAULT_PRICE = 25.0
    const val DEFAULT_COMMISSION_RATE = 8.0
    const val TAG_MALIIT = "[Maliit]"

    fun isMaliitTagged(notes: String): Boolean =
        notes.contains(TAG_MALIIT, ignoreCase = true)

    fun stripMaliitTag(notes: String): String {
        return notes
            .replace(TAG_MALIIT, "", ignoreCase = true)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun applyMaliitTag(notes: String, isMaliit: Boolean): String {
        val cleaned = stripMaliitTag(notes)
        if (!isMaliit) return cleaned.take(300)
        return if (cleaned.isEmpty()) TAG_MALIIT else "$TAG_MALIIT $cleaned".take(300)
    }
}
