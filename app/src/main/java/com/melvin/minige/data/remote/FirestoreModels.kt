package com.melvin.minige.data.remote

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.melvin.minige.data.local.AppSettingsEntity
import com.melvin.minige.data.local.DailyLedgerEntity
import com.melvin.minige.data.local.DeliveryEntity
import com.melvin.minige.data.local.ExpenseEntity
import com.melvin.minige.data.local.UtangPaymentEntity

@Keep
@IgnoreExtraProperties
data class FirestoreDelivery(
    @DocumentId val id: String = "",
    val userId: String = "",
    val dateKey: String = "",
    val customerName: String = "",
    val purifiedGallons: Long = 0L,
    val alkalineGallons: Long = 0L,
    val purifiedUnitPrice: Long = 30L,
    val alkalineUnitPrice: Long = 40L,
    val totalAmount: Long = 0L,
    val amountPaid: Long = 0L,
    val paymentType: String = "CASH",
    val commissionRate: Long = 8L,
    val commissionEarned: Long = 0L,
    val settledDateKey: String? = null,
    val notes: String = "",
    val calendarEventId: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toEntity(pendingSync: Boolean = false): DeliveryEntity = DeliveryEntity(
        id = id,
        userId = userId,
        dateKey = dateKey,
        customerName = customerName,
        purifiedGallons = purifiedGallons.toInt(),
        alkalineGallons = alkalineGallons.toInt(),
        purifiedUnitPrice = purifiedUnitPrice.toInt(),
        alkalineUnitPrice = alkalineUnitPrice.toInt(),
        totalAmount = totalAmount.toInt(),
        amountPaid = amountPaid.toInt(),
        paymentType = paymentType,
        commissionRate = commissionRate.toInt(),
        commissionEarned = commissionEarned.toInt(),
        settledDateKey = settledDateKey,
        notes = notes,
        calendarEventId = calendarEventId,
        pendingSync = pendingSync,
        isDeleted = false,
        createdAtEpochMs = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
        updatedAtEpochMs = updatedAt?.toDate()?.time ?: System.currentTimeMillis(),
    )
}

@Keep
@IgnoreExtraProperties
data class FirestoreUtangPayment(
    @DocumentId val id: String = "",
    val userId: String = "",
    val deliveryId: String = "",
    val dateKey: String = "",
    val amount: Long = 0L,
    val method: String = "CASH",
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toEntity(pendingSync: Boolean = false): UtangPaymentEntity = UtangPaymentEntity(
        id = id,
        deliveryId = deliveryId,
        userId = userId,
        dateKey = dateKey,
        amount = amount.toInt(),
        method = method,
        notes = notes,
        pendingSync = pendingSync,
        isDeleted = false,
        createdAtEpochMs = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
    )
}

@Keep
@IgnoreExtraProperties
data class FirestoreExpense(
    @DocumentId val id: String = "",
    val userId: String = "",
    val dateKey: String = "",
    val category: String = "FOOD",
    val amount: Long = 0L,
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toEntity(pendingSync: Boolean = false): ExpenseEntity = ExpenseEntity(
        id = id,
        userId = userId,
        dateKey = dateKey,
        category = category,
        amount = amount.toInt(),
        notes = notes,
        pendingSync = pendingSync,
        isDeleted = false,
        createdAtEpochMs = createdAt?.toDate()?.time ?: System.currentTimeMillis(),
        updatedAtEpochMs = updatedAt?.toDate()?.time ?: System.currentTimeMillis(),
    )
}

@Keep
@IgnoreExtraProperties
data class FirestoreDailyLedger(
    @DocumentId val id: String = "",
    val userId: String = "",
    val dateKey: String = "",
    val startingSukli: Long = 0L,
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toEntity(pendingSync: Boolean = false): DailyLedgerEntity = DailyLedgerEntity(
        dateKey = dateKey.ifBlank { id },
        userId = userId,
        startingSukli = startingSukli.toInt(),
        notes = notes,
        pendingSync = pendingSync,
        updatedAtEpochMs = updatedAt?.toDate()?.time ?: System.currentTimeMillis(),
    )
}

@Keep
@IgnoreExtraProperties
data class FirestoreUserSettings(
    @DocumentId val id: String = "",
    val userId: String = "",
    val purifiedPrice: Long = 30L,
    val alkalinePrice: Long = 40L,
    val commissionRate: Long = 8L,
    val defaultSukli: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toEntity(pendingSync: Boolean = false): AppSettingsEntity = AppSettingsEntity(
        id = "default",
        userId = userId,
        purifiedPrice = purifiedPrice.toInt(),
        alkalinePrice = alkalinePrice.toInt(),
        commissionRate = commissionRate.toInt(),
        defaultSukli = defaultSukli.toInt(),
        pendingSync = pendingSync,
        updatedAtEpochMs = updatedAt?.toDate()?.time ?: System.currentTimeMillis(),
    )
}

internal fun DeliveryEntity.toFirestoreCreateMap(uid: String): Map<String, Any> = buildMap {
    put("userId", uid)
    put("dateKey", dateKey)
    put("customerName", customerName.take(80))
    put("purifiedGallons", purifiedGallons.toLong())
    put("alkalineGallons", alkalineGallons.toLong())
    put("purifiedUnitPrice", purifiedUnitPrice.toLong())
    put("alkalineUnitPrice", alkalineUnitPrice.toLong())
    put("totalAmount", totalAmount.toLong())
    put("amountPaid", amountPaid.toLong())
    put("paymentType", paymentType)
    put("commissionRate", commissionRate.toLong())
    put("commissionEarned", commissionEarned.toLong())
    settledDateKey?.let { put("settledDateKey", it) }
    put("notes", notes.take(300))
    calendarEventId?.let { put("calendarEventId", it) }
    put("createdAt", FieldValue.serverTimestamp())
    put("updatedAt", FieldValue.serverTimestamp())
}

internal fun DeliveryEntity.toFirestoreUpdateMap(): Map<String, Any> = buildMap {
    put("dateKey", dateKey)
    put("customerName", customerName.take(80))
    put("purifiedGallons", purifiedGallons.toLong())
    put("alkalineGallons", alkalineGallons.toLong())
    put("purifiedUnitPrice", purifiedUnitPrice.toLong())
    put("alkalineUnitPrice", alkalineUnitPrice.toLong())
    put("totalAmount", totalAmount.toLong())
    put("amountPaid", amountPaid.toLong())
    put("paymentType", paymentType)
    put("commissionRate", commissionRate.toLong())
    put("commissionEarned", commissionEarned.toLong())
    settledDateKey?.let { put("settledDateKey", it) }
    put("notes", notes.take(300))
    calendarEventId?.let { put("calendarEventId", it) }
    put("updatedAt", FieldValue.serverTimestamp())
}

internal fun UtangPaymentEntity.toFirestoreCreateMap(uid: String): Map<String, Any> = mapOf(
    "userId" to uid,
    "deliveryId" to deliveryId,
    "dateKey" to dateKey,
    "amount" to amount.toLong(),
    "method" to method,
    "notes" to notes.take(300),
    "createdAt" to FieldValue.serverTimestamp(),
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun ExpenseEntity.toFirestoreCreateMap(uid: String): Map<String, Any> = mapOf(
    "userId" to uid,
    "dateKey" to dateKey,
    "category" to category,
    "amount" to amount.toLong(),
    "notes" to notes.take(300),
    "createdAt" to FieldValue.serverTimestamp(),
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun DailyLedgerEntity.toFirestoreCreateMap(uid: String): Map<String, Any> = mapOf(
    "userId" to uid,
    "dateKey" to dateKey,
    "startingSukli" to startingSukli.toLong(),
    "notes" to notes.take(300),
    "createdAt" to FieldValue.serverTimestamp(),
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun DailyLedgerEntity.toFirestoreUpdateMap(): Map<String, Any> = mapOf(
    "startingSukli" to startingSukli.toLong(),
    "notes" to notes.take(300),
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun AppSettingsEntity.toFirestoreCreateMap(uid: String): Map<String, Any> = mapOf(
    "userId" to uid,
    "purifiedPrice" to purifiedPrice.toLong(),
    "alkalinePrice" to alkalinePrice.toLong(),
    "commissionRate" to commissionRate.toLong(),
    "defaultSukli" to defaultSukli.toLong(),
    "createdAt" to FieldValue.serverTimestamp(),
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun AppSettingsEntity.toFirestoreUpdateMap(): Map<String, Any> = mapOf(
    "purifiedPrice" to purifiedPrice.toLong(),
    "alkalinePrice" to alkalinePrice.toLong(),
    "commissionRate" to commissionRate.toLong(),
    "defaultSukli" to defaultSukli.toLong(),
    "updatedAt" to FieldValue.serverTimestamp(),
)
