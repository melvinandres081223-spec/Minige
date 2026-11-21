package com.melvin.minige.util

import androidx.compose.runtime.Immutable
import com.melvin.minige.domain.DeliveryRecord
import kotlin.math.ceil

@Immutable
sealed interface UndoableAction {
    val message: String
    val createdAtEpochMs: Long

    data class DeliveryDeleted(
        val snapshot: DeliveryRecord,
        override val message: String = "Deleted ${snapshot.customerName}",
        override val createdAtEpochMs: Long = System.currentTimeMillis(),
    ) : UndoableAction

    data class DeliveryUpdated(
        val previousSnapshot: DeliveryRecord,
        override val message: String = "Updated ${previousSnapshot.customerName}",
        override val createdAtEpochMs: Long = System.currentTimeMillis(),
    ) : UndoableAction

    data class UtangPaymentRecorded(
        val previousDelivery: DeliveryRecord,
        val createdPaymentId: String,
        override val message: String,
        override val createdAtEpochMs: Long = System.currentTimeMillis(),
    ) : UndoableAction
}

object UndoStateHelper {
    const val UNDO_DURATION_MS: Long = 5_000L

    fun shouldAutoDismiss(
        action: UndoableAction?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (action == null) return false
        return (nowEpochMs - action.createdAtEpochMs) >= UNDO_DURATION_MS
    }

    fun remainingSeconds(
        action: UndoableAction?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Int {
        if (action == null) return 0
        val remainingMs = (UNDO_DURATION_MS - (nowEpochMs - action.createdAtEpochMs)).coerceAtLeast(0L)
        return ceil(remainingMs / 1000.0).toInt()
    }
}
