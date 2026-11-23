package com.melvin.minige.util

import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.PaymentType
import java.util.Locale

enum class CustomerClassification(
    val label: String,
    val noteTag: String,
) {
    OUTLET("Outlet", "[Outlet]"),
    RESIDENTIAL("Residential", "[Residential]"),
}

data class ParsedVoiceDelivery(
    val gallons: Int? = null,
    val isAlkaline: Boolean = false,
    val classification: CustomerClassification? = null,
    val paymentType: PaymentType? = null,
    val paidChannel: PaidChannel? = null,
    val customerName: String = "",
    val matchedCustomer: CustomerRecord? = null,
    val rememberedUnitPrice: Double? = null,
)

object DeliveryVoiceParser {

    private const val TAG_OUTLET = "[Outlet]"
    private const val TAG_RESIDENTIAL = "[Residential]"

    private val numberWords = mapOf(
        "one" to 1, "isa" to 1, "isang" to 1,
        "two" to 2, "dalawa" to 2, "dalawang" to 2,
        "three" to 3, "tatlo" to 3, "tatlong" to 3,
        "four" to 4, "apat" to 4, "apat na" to 4,
        "five" to 5, "lima" to 5, "limang" to 5,
        "six" to 6, "anim" to 6,
        "seven" to 7, "pito" to 7,
        "eight" to 8, "walo" to 8,
        "nine" to 9, "siyam" to 9,
        "ten" to 10, "sampu" to 10,
        "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
    )

    fun extractClassification(notes: String): CustomerClassification? = when {
        notes.contains(TAG_OUTLET, ignoreCase = true) -> CustomerClassification.OUTLET
        notes.contains(TAG_RESIDENTIAL, ignoreCase = true) -> CustomerClassification.RESIDENTIAL
        else -> null
    }

    fun applyClassificationTag(notes: String, classification: CustomerClassification?): String {
        val cleaned = notes
            .replace(TAG_OUTLET, "", ignoreCase = true)
            .replace(TAG_RESIDENTIAL, "", ignoreCase = true)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        val tag = classification?.noteTag ?: return cleaned
        return if (cleaned.isEmpty()) tag else "$tag $cleaned".take(300)
    }

    fun stripClassificationTag(notes: String): String = applyClassificationTag(notes, null)

    fun parse(
        spokenText: String,
        customers: List<CustomerRecord> = emptyList(),
    ): ParsedVoiceDelivery {
        val raw = spokenText.trim().replace(Regex("\\s+"), " ")
        if (raw.isEmpty()) return ParsedVoiceDelivery()

        var working = raw

        // 1. Extract leading or standalone gallon count (digits or spoken word)
        var gallons: Int? = null
        val digitMatch = Regex("(?:^|\\s)(\\d{1,4})(?:\\s*(?:gal|gals|gallon|gallons))?(?=\\s|$)", RegexOption.IGNORE_CASE)
            .find(working)
        if (digitMatch != null) {
            gallons = digitMatch.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
            if (gallons != null) {
                working = working.removeRange(digitMatch.range).trim()
            }
        } else {
            val tokens = working.split(' ')
            val firstLower = tokens.firstOrNull()?.lowercase(Locale.ENGLISH)
            val wordQty = firstLower?.let { numberWords[it] }
            if (wordQty != null) {
                gallons = wordQty
                working = tokens.drop(1).joinToString(" ")
            }
        }

        // Remove filler unit words like "gallons", "gals", "gal" left behind
        working = working.replace(Regex("\\b(?:gallons?|gals?)\\b", RegexOption.IGNORE_CASE), " ")

        // 2. Extract Alkaline vs Purified hint
        val isAlkaline = Regex("\\balkaline\\b", RegexOption.IGNORE_CASE).containsMatchIn(working)
        working = working
            .replace(Regex("\\b(?:alkaline|purified)\\b", RegexOption.IGNORE_CASE), " ")

        // 3. Extract Customer Classification (Outlet vs Residential)
        val classification = when {
            Regex("\\b(?:outlet|store|station|refill)\\b", RegexOption.IGNORE_CASE).containsMatchIn(working) -> {
                working = working.replace(
                    Regex("\\b(?:outlet|store|station|refill)\\b", RegexOption.IGNORE_CASE),
                    " ",
                )
                CustomerClassification.OUTLET
            }
            Regex("\\b(?:residential|house|bahay|home)\\b", RegexOption.IGNORE_CASE).containsMatchIn(working) -> {
                working = working.replace(
                    Regex("\\b(?:residential|house|bahay|home)\\b", RegexOption.IGNORE_CASE),
                    " ",
                )
                CustomerClassification.RESIDENTIAL
            }
            else -> null
        }

        // 4. Extract Transaction / Payment Type (Pick up, Pautang, Paid Walk-in, Bank / Online, Cash)
        var paymentType: PaymentType? = null
        var paidChannel: PaidChannel? = null

        when {
            Regex("\\b(?:na[-\\s]?pick\\s*up|pick\\s*-?\\s*up|pickup|hakot)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(working) -> {
                paymentType = PaymentType.PICK_UP
                working = working.replace(
                    Regex("\\b(?:na[-\\s]?pick\\s*up|pick\\s*-?\\s*up|pickup|hakot)\\b", RegexOption.IGNORE_CASE),
                    " ",
                )
            }
            Regex("\\b(?:paid\\s+walk[-\\s]?in|walk[-\\s]?in)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(working) -> {
                paymentType = PaymentType.PAID_WALK_IN
                working = working.replace(
                    Regex("\\b(?:paid\\s+walk[-\\s]?in|walk[-\\s]?in)\\b", RegexOption.IGNORE_CASE),
                    " ",
                )
            }
            Regex("\\b(?:pautang|utang|credit)\\b", RegexOption.IGNORE_CASE).containsMatchIn(working) -> {
                paymentType = PaymentType.PAUTANG
                working = working.replace(Regex("\\b(?:pautang|utang|credit)\\b", RegexOption.IGNORE_CASE), " ")
            }
            Regex("\\b(?:bank\\s*/\\s*online|bank\\s+transfer|bank\\s+deposit|bank\\s+online|online|transfer|deposit|gcash)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(working) -> {
                paymentType = PaymentType.BANK_DEPOSIT
                paidChannel = PaidChannel.BANK_ONLINE
                working = working.replace(
                    Regex("\\b(?:bank\\s*/\\s*online|bank\\s+transfer|bank\\s+deposit|bank\\s+online|online|transfer|deposit|gcash)\\b", RegexOption.IGNORE_CASE),
                    " ",
                )
            }
            Regex("\\bcash\\b", RegexOption.IGNORE_CASE).containsMatchIn(working) -> {
                paymentType = PaymentType.CASH
                paidChannel = PaidChannel.CASH
                working = working.replace(Regex("\\bcash\\b", RegexOption.IGNORE_CASE), " ")
            }
        }

        // Remove common spoken connectors ("para kay", "kay", "for", "ni", "sa") at the start
        val cleanedName = working
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("^(?:para\\s+kay|para\\s+sa|kay|for|to|sa|ni)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
            .take(80)

        val matchedCustomer = if (cleanedName.isNotBlank() && customers.isNotEmpty()) {
            val normalizedId = MinigeCustomerUtils.normalizeCustomerId(cleanedName)
            customers.firstOrNull { it.id == normalizedId }
                ?: customers.firstOrNull { it.name.equals(cleanedName, ignoreCase = true) }
        } else {
            null
        }

        return ParsedVoiceDelivery(
            gallons = gallons,
            isAlkaline = isAlkaline,
            classification = classification,
            paymentType = paymentType,
            paidChannel = paidChannel,
            customerName = matchedCustomer?.name ?: cleanedName,
            matchedCustomer = matchedCustomer,
            rememberedUnitPrice = matchedCustomer?.lastUnitPrice,
        )
    }
}
