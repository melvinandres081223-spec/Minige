package com.melvin.minige.util

import androidx.compose.runtime.Immutable
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.PaymentType
import java.util.Locale

@Immutable
data class RouteAreaGroup(
    val areaName: String,
    val deliveries: List<DeliveryRecord>,
    val totalGallons: Int,
    val paidGallons: Int,
    val unvisitedSuki: List<SukiCustomerProfile>,
)

@Immutable
data class RouteGroupingResult(
    val groups: List<RouteAreaGroup>,
    val totalUnvisitedCount: Int,
)

object RouteGroupingHelper {

    const val UNASSIGNED_AREA = "Unassigned Area"

    @Volatile
    private var lastDeliveriesRef: List<DeliveryRecord>? = null
    @Volatile
    private var lastDirectoryRef: List<SukiCustomerProfile>? = null
    @Volatile
    private var lastGroupingResult: RouteGroupingResult? = null
    private val cacheLock = Any()

    fun groupDeliveriesByArea(
        deliveriesForDay: List<DeliveryRecord>,
        sukiDirectory: List<SukiCustomerProfile>,
    ): RouteGroupingResult {
        if (deliveriesForDay.isEmpty()) {
            return RouteGroupingResult(groups = emptyList(), totalUnvisitedCount = 0)
        }

        lastGroupingResult?.let { cached ->
            if (lastDeliveriesRef === deliveriesForDay && lastDirectoryRef === sukiDirectory) {
                return cached
            }
        }

        val deliveredIds = deliveriesForDay
            .mapTo(HashSet(deliveriesForDay.size)) {
                MinigeCustomerUtils.normalizeCustomerId(it.customerName)
            }

        val sukiByAreaKey = HashMap<String, MutableList<SukiCustomerProfile>>()
        for (profile in sukiDirectory) {
            val areaKey = profile.routeArea?.trim()?.lowercase(Locale.ENGLISH)
            if (!areaKey.isNullOrEmpty() && profile.id !in deliveredIds) {
                sukiByAreaKey.getOrPut(areaKey) { mutableListOf() }.add(profile)
            }
        }

        val displayNameByAreaKey = linkedMapOf<String, String>()
        val deliveriesByAreaKey = linkedMapOf<String, MutableList<DeliveryRecord>>()

        deliveriesForDay.forEach { delivery ->
            val rawArea = CustomerDirectoryHelper.extractRouteArea(delivery.notes)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: UNASSIGNED_AREA
            val key = rawArea.lowercase(Locale.ENGLISH)
            if (!displayNameByAreaKey.containsKey(key)) {
                displayNameByAreaKey[key] = rawArea
            }
            deliveriesByAreaKey.getOrPut(key) { mutableListOf() }.add(delivery)
        }

        val groups = deliveriesByAreaKey.entries.map { (areaKey, entries) ->
            val areaLabel = displayNameByAreaKey[areaKey] ?: UNASSIGNED_AREA
            val totalGallons = entries.sumOf { it.totalGallons }
            val paidGallons = entries
                .filter { it.paymentType == PaymentType.CASH || it.paymentType == PaymentType.BANK_DEPOSIT }
                .sumOf { it.totalGallons }

            val unvisited = if (areaLabel == UNASSIGNED_AREA) {
                emptyList()
            } else {
                sukiByAreaKey[areaKey].orEmpty()
            }

            RouteAreaGroup(
                areaName = areaLabel,
                deliveries = entries,
                totalGallons = totalGallons,
                paidGallons = paidGallons,
                unvisitedSuki = unvisited,
            )
        }.sortedWith(
            compareBy<RouteAreaGroup> { it.areaName == UNASSIGNED_AREA }
                .thenBy { it.areaName.lowercase(Locale.ENGLISH) }
        )

        val result = RouteGroupingResult(
            groups = groups,
            totalUnvisitedCount = groups.sumOf { it.unvisitedSuki.size },
        )

        synchronized(cacheLock) {
            lastDeliveriesRef = deliveriesForDay
            lastDirectoryRef = sukiDirectory
            lastGroupingResult = result
        }

        return result
    }
}
