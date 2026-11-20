package com.melvin.minige.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.melvin.minige.domain.DailyLedgerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseCategory
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.MinigeSettings
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangMethod
import com.melvin.minige.domain.UtangPaymentRecord

@Entity(tableName = "deliveries")
data class DeliveryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val dateKey: String,
    val customerName: String,
    val purifiedGallons: Int,
    val alkalineGallons: Int,
    val purifiedUnitPrice: Int,
    val alkalineUnitPrice: Int,
    val totalAmount: Int,
    val amountPaid: Int,
    val paymentType: String,
    val commissionRate: Int,
    val commissionEarned: Int,
    val settledDateKey: String?,
    val notes: String,
    val calendarEventId: String?,
    val pendingSync: Boolean,
    val isDeleted: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    fun toDomain(): DeliveryRecord = DeliveryRecord(
        id = id,
        userId = userId,
        dateKey = dateKey,
        customerName = customerName,
        purifiedGallons = purifiedGallons,
        alkalineGallons = alkalineGallons,
        purifiedUnitPrice = purifiedUnitPrice,
        alkalineUnitPrice = alkalineUnitPrice,
        totalAmount = totalAmount,
        amountPaid = amountPaid,
        paymentType = PaymentType.fromRaw(paymentType),
        commissionRate = commissionRate,
        commissionEarned = commissionEarned,
        settledDateKey = settledDateKey,
        notes = notes,
        calendarEventId = calendarEventId,
        pendingSync = pendingSync,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}

@Entity(tableName = "utang_payments")
data class UtangPaymentEntity(
    @PrimaryKey val id: String,
    val deliveryId: String,
    val userId: String,
    val dateKey: String,
    val amount: Int,
    val method: String,
    val notes: String,
    val pendingSync: Boolean,
    val isDeleted: Boolean,
    val createdAtEpochMs: Long,
) {
    fun toDomain(): UtangPaymentRecord = UtangPaymentRecord(
        id = id,
        deliveryId = deliveryId,
        userId = userId,
        dateKey = dateKey,
        amount = amount,
        method = UtangMethod.fromRaw(method),
        notes = notes,
        pendingSync = pendingSync,
        createdAtEpochMs = createdAtEpochMs,
    )
}

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val dateKey: String,
    val category: String,
    val amount: Int,
    val notes: String,
    val pendingSync: Boolean,
    val isDeleted: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    fun toDomain(): ExpenseRecord = ExpenseRecord(
        id = id,
        userId = userId,
        dateKey = dateKey,
        category = ExpenseCategory.fromRaw(category),
        amount = amount,
        notes = notes,
        pendingSync = pendingSync,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}

@Entity(tableName = "daily_ledgers")
data class DailyLedgerEntity(
    @PrimaryKey val dateKey: String,
    val userId: String,
    val startingSukli: Int,
    val notes: String,
    val pendingSync: Boolean,
    val updatedAtEpochMs: Long,
) {
    fun toDomain(): DailyLedgerRecord = DailyLedgerRecord(
        dateKey = dateKey,
        userId = userId,
        startingSukli = startingSukli,
        notes = notes,
        pendingSync = pendingSync,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: String = "default",
    val userId: String = "",
    val purifiedPrice: Int = 30,
    val alkalinePrice: Int = 40,
    val commissionRate: Int = 8,
    val defaultSukli: Int = 0,
    val pendingSync: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
) {
    fun toDomain(): MinigeSettings = MinigeSettings(
        purifiedPrice = purifiedPrice,
        alkalinePrice = alkalinePrice,
        commissionRate = commissionRate,
        defaultSukli = defaultSukli,
    )
}
