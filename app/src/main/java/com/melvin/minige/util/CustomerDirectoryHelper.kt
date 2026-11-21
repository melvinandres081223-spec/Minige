package com.melvin.minige.util

import androidx.compose.runtime.Immutable
import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangPaymentRecord
import java.util.Locale

@Immutable
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

@Immutable
data class IndexedSukiDirectory(
    val profiles: List<SukiCustomerProfile>,
    val byNormalizedId: Map<String, SukiCustomerProfile>,
    val searchableEntries: List<SearchableSukiEntry>,
)

@Immutable
data class SearchableSukiEntry(
    val profile: SukiCustomerProfile,
    val normalizedNameLower: String,
    val normalizedAreaLower: String,
)

object CustomerDirectoryHelper {

    private val areaTagRegex = Regex("\\[Area:([^\\]]+)]", RegexOption.IGNORE_CASE)

    @Volatile
    private var cachedPricingRef: List<CustomerRecord>? = null
    @Volatile
    private var cachedDeliveriesRef: List<DeliveryRecord>? = null
    @Volatile
    private var cachedPaymentsRef: List<UtangPaymentRecord>? = null
    @Volatile
    private var cachedIndexedDirectory: IndexedSukiDirectory? = null
    private val cacheLock = Any()

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

    fun buildIndexedDirectory(
        pricingCustomers: List<CustomerRecord>,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
    ): IndexedSukiDirectory {
        cachedIndexedDirectory?.let { existing ->
            if (cachedPricingRef === pricingCustomers &&
                cachedDeliveriesRef === deliveries &&
                cachedPaymentsRef === utangPayments
            ) {
                return existing
            }
        }

        val pricingById = pricingCustomers.associateBy { it.id }
        val deliveriesByCustomer = deliveries.groupBy {
            MinigeCustomerUtils.normalizeCustomerId(it.customerName)
        }
        val unpaidPaymentsByDeliveryId = utangPayments.groupBy { it.deliveryId }
        val allIds = (pricingById.keys + deliveriesByCustomer.keys).distinct()

        val profiles = allIds.mapNotNull { cid ->
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
            val unpaidGallons = unpaidList.sumOf { utangDelivery ->
                PartialPaymentHelper.getStatus(
                    delivery = utangDelivery,
                    allUtangPayments = unpaidPaymentsByDeliveryId[utangDelivery.id].orEmpty(),
                ).remainingUnpaidGallons
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
                .thenBy { it.name.lowercase(Locale.ENGLISH) }
        )

        val indexed = IndexedSukiDirectory(
            profiles = profiles,
            byNormalizedId = profiles.associateBy { it.id },
            searchableEntries = profiles.map { profile ->
                SearchableSukiEntry(
                    profile = profile,
                    normalizedNameLower = profile.name.lowercase(Locale.ENGLISH),
                    normalizedAreaLower = profile.routeArea?.lowercase(Locale.ENGLISH).orEmpty(),
                )
            },
        )

        synchronized(cacheLock) {
            cachedPricingRef = pricingCustomers
            cachedDeliveriesRef = deliveries
            cachedPaymentsRef = utangPayments
            cachedIndexedDirectory = indexed
        }

        return indexed
    }

    fun buildDirectory(
        pricingCustomers: List<CustomerRecord>,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
    ): List<SukiCustomerProfile> {
        return buildIndexedDirectory(
            pricingCustomers = pricingCustomers,
            deliveries = deliveries,
            utangPayments = utangPayments,
        ).profiles
    }

    fun filterSuggestions(
        directory: List<SukiCustomerProfile>,
        query: String,
        limit: Int = 10,
    ): List<SukiCustomerProfile> {
        val q = query.trim().lowercase(Locale.ENGLISH)
        if (q.isEmpty()) {
            return directory.take(limit)
        }
        val activeIndexed = cachedIndexedDirectory
        if (activeIndexed != null && activeIndexed.profiles === directory) {
            val matches = ArrayList<SukiCustomerProfile>(limit)
            for (entry in activeIndexed.searchableEntries) {
                if ((entry.normalizedNameLower.contains(q) || entry.normalizedAreaLower.contains(q)) &&
                    entry.normalizedNameLower != q
                ) {
                    matches.add(entry.profile)
                    if (matches.size >= limit) break
                }
            }
            return matches
        }
        val matches = ArrayList<SukiCustomerProfile>(limit)
        for (profile in directory) {
            val nameLower = profile.name.lowercase(Locale.ENGLISH)
            val areaLower = profile.routeArea?.lowercase(Locale.ENGLISH).orEmpty()
            if ((nameLower.contains(q) || areaLower.contains(q)) && nameLower != q) {
                matches.add(profile)
                if (matches.size >= limit) break
            }
        }
        return matches
    }
}
