package com.melvin.minige.data

import com.melvin.minige.data.local.CustomerEntity
import com.melvin.minige.data.local.MinigeDao
import com.melvin.minige.data.remote.MinigeFirestoreDataSource
import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.MinigeCustomerUtils
import com.melvin.minige.domain.PaymentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PricingRepository(
    private val dao: MinigeDao,
    private val remote: MinigeFirestoreDataSource,
) {
    fun observeCustomers(userId: String): Flow<List<CustomerRecord>> =
        dao.observeCustomers(userId).map { list -> list.map { it.toDomain() } }

    suspend fun getSavedUnitPrice(customerName: String): Double? {
        if (customerName.isBlank()) return null
        val customerId = MinigeCustomerUtils.normalizeCustomerId(customerName)
        return dao.getCustomerById(customerId)?.lastUnitPrice
    }

    suspend fun rememberCustomerPrice(
        userId: String,
        customerName: String,
        unitPriceToSave: Double?,
        now: Long = System.currentTimeMillis(),
    ) {
        val cleanName = customerName.trim().take(80)
        if (cleanName.isEmpty()) return

        val customerId = MinigeCustomerUtils.normalizeCustomerId(cleanName)
        val existingCustomer = dao.getCustomerById(customerId)
        val resolvedPrice = unitPriceToSave ?: existingCustomer?.lastUnitPrice
        val customerEntity = CustomerEntity(
            id = customerId,
            userId = userId,
            name = cleanName,
            lastUnitPrice = resolvedPrice,
            pendingSync = true,
            updatedAtEpochMs = now,
        )
        dao.upsertCustomer(customerEntity)
        val custSync = remote.upsertCustomer(userId, customerEntity)
        if (custSync.isSuccess) {
            dao.markCustomerSynced(customerId)
        }
    }

    suspend fun applyRemoteCustomers(userId: String, remoteCustomers: List<CustomerEntity>) {
        val pendingIds = dao.getPendingCustomers(userId).map { it.id }.toSet()
        val filtered = remoteCustomers.filterNot { it.id in pendingIds }
        if (filtered.isNotEmpty()) {
            dao.upsertCustomers(filtered)
        }
    }

    suspend fun syncPendingCustomers(userId: String): Pair<Int, Throwable?> {
        var synced = 0
        var lastError: Throwable? = null
        dao.getPendingCustomers(userId).forEach { customer ->
            val result = remote.upsertCustomer(userId, customer)
            if (result.isSuccess) {
                dao.markCustomerSynced(customer.id)
                synced++
            } else {
                lastError = result.exceptionOrNull()
            }
        }
        return synced to lastError
    }

    fun mergeWithHistoricalDeliveries(
        savedCustomers: List<CustomerRecord>,
        deliveries: List<DeliveryRecord>,
    ): List<CustomerRecord> {
        val customerById = savedCustomers.associateBy { it.id }.toMutableMap()
        deliveries.forEach { d ->
            val cid = MinigeCustomerUtils.normalizeCustomerId(d.customerName)
            if (cid !in customerById) {
                val fallbackPrice = if (d.paymentType != PaymentType.PICK_UP) {
                    (if (d.alkalineGallons > 0 && d.purifiedGallons == 0) d.alkalineUnitPrice else d.purifiedUnitPrice).toDouble()
                } else {
                    null
                }
                customerById[cid] = CustomerRecord(
                    id = cid,
                    userId = d.userId,
                    name = d.customerName,
                    lastUnitPrice = fallbackPrice,
                    updatedAtEpochMs = d.updatedAtEpochMs,
                )
            }
        }
        return customerById.values.sortedByDescending { it.updatedAtEpochMs }
    }
}
