package com.melvin.minige.util

import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangPaymentRecord

data class SukiCustomerProfile(
    val id: String,
    val name: String,
    val classification: CustomerClassification?,
    val savedUnitPrice: Double?,
    val lastOrderDateKey: String?,
    val lastOrderGallons: Int?,
    val routeArea: String?,
    val unpaidUtangAmount: Int,
    val unpaidUtangGallons: Int,
    val orderCount: Int,
    val lastUpdatedEpochMs: Long,
)

object CustomerDirectoryHelper {

    private val areaTagRegex = Regex("\\[Area:([^\\]]+)]", RegexOption.IGNORE_CASE)

    fun extractRouteArea(notes: String): String? {
        val raw = areaTagRegex.find(notes)?.groupValues?.getOrNull(1)?.trim()
        return raw?.takeIf { it.isNotEmpty() }
    }

    fun stripRouteAreaTag(notes: String): String {
        return notes
            .replace(areaTagRegex, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun applyRouteAreaTag(notes: String, routeArea: String?): String {
        val cleaned = stripRouteAreaTag(notes)
        val area = routeArea?.trim()?.replace("[", "")?.replace("]", "")?.take(40).orEmpty()
        if (area.isEmpty()) return cleaned
        val tag = "[Area:$area]"
        return if (cleaned.isEmpty()) tag else "$tag $cleaned".take(300)
    }

    fun buildDirectory(
        pricingCustomers: List<CustomerRecord>,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
    ): List<SukiCustomerProfile> {
        val pricingById = pricingCustomers.associateBy { it.id }
        val deliveriesByCustomer = deliveries.groupBy {
            MinigeCustomerUtils.normalizeCustomerId(it.customerName)
        }
        val allIds = (pricingById.keys + deliveriesByCustomer.keys).distinct()

        return allIds.mapNotNull { cid ->
            val customerRecord = pricingById[cid]
            val customerDeliveries = deliveriesByCustomer[cid].orEmpty()
                .sortedWith(
                    compareByDescending<DeliveryRecord> { it.dateKey }
                        .thenByDescending { it.updatedAtEpochMs }
                )

            val displayName = customerRecord?.name?.takeIf { it.isNotBlank() }
                ?: customerDeliveries.firstOrNull()?.customerName
                ?: return@mapNotNull null

            val latestDelivery = customerDeliveries.firstOrNull()
            val classification = customerDeliveries.firstNotNullOfOrNull {
                DeliveryVoiceParser.extractClassification(it.notes)
            }
            val routeArea = customerDeliveries.firstNotNullOfOrNull {
                extractRouteArea(it.notes)
            }

            val savedPrice = customerRecord?.lastUnitPrice
                ?: customerDeliveries.firstOrNull { it.paymentType != PaymentType.PICK_UP }?.let { d ->
                    if (d.alkalineGallons > 0 && d.purifiedGallons == 0) d.alkalineUnitPrice.toDouble()
                    else d.purifiedUnitPrice.toDouble()
                }

            val unpaidList = customerDeliveries.filter {
                it.paymentType == PaymentType.PAUTANG && !it.isUtangFullyPaid
            }
            val unpaidAmount = unpaidList.sumOf { it.remainingBalance }
            val unpaidGallons = unpaidList.sumOf {
                PartialPaymentHelper.getStatus(it, utangPayments).remainingUnpaidGallons
            }

            val updatedAt = maxOf(
                customerRecord?.updatedAtEpochMs ?: 0L,
                latestDelivery?.updatedAtEpochMs ?: 0L,
            )

            SukiCustomerProfile(
                id = cid,
                name = displayName,
                classification = classification,
                savedUnitPrice = savedPrice,
                lastOrderDateKey = latestDelivery?.dateKey,
                lastOrderGallons = latestDelivery?.totalGallons,
                routeArea = routeArea,
                unpaidUtangAmount = unpaidAmount,
                unpaidUtangGallons = unpaidGallons,
                orderCount = customerDeliveries.size,
                lastUpdatedEpochMs = updatedAt,
            )
        }.sortedWith(
            compareByDescending<SukiCustomerProfile> { it.unpaidUtangGallons > 0 }
                .thenByDescending { it.lastUpdatedEpochMs }
                .thenBy { it.name.lowercase() }
        )
    }

    fun filterSuggestions(
        directory: List<SukiCustomerProfile>,
        query: String,
        limit: Int = 10,
    ): List<SukiCustomerProfile> {
        val q = query.trim()
        if (q.isEmpty()) {
            return directory.take(limit)
        }
        return directory.filter { profile ->
            (profile.name.contains(q, ignoreCase = true) ||
                (profile.routeArea?.contains(q, ignoreCase = true) == true)) &&
                !profile.name.equals(q, ignoreCase = true)
        }.take(limit)
    }
}
