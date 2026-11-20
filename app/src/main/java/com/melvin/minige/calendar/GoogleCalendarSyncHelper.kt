package com.melvin.minige.calendar

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.melvin.minige.domain.DailySummary
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.MinigeDateUtils
import com.melvin.minige.domain.PaymentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface CalendarAuthResult {
    data class Authorized(val accessToken: String) : CalendarAuthResult
    data class NeedsConsent(val intentSender: IntentSender) : CalendarAuthResult
    data class Failure(val message: String) : CalendarAuthResult
}

object GoogleCalendarSyncHelper {

    private val calendarScope = Scope(CalendarScopes.CALENDAR_EVENTS)

    suspend fun requestCalendarAuthorization(context: Context): CalendarAuthResult {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(calendarScope))
                .build()
            val result = Identity.getAuthorizationClient(context)
                .authorize(request)
                .await()

            val pendingIntent = result.pendingIntent
            val token = result.accessToken
            when {
                result.hasResolution() && pendingIntent != null -> {
                    CalendarAuthResult.NeedsConsent(pendingIntent.intentSender)
                }
                !token.isNullOrBlank() -> {
                    CalendarAuthResult.Authorized(token)
                }
                else -> {
                    CalendarAuthResult.Failure("No Google Calendar access token returned.")
                }
            }
        } catch (e: Exception) {
            CalendarAuthResult.Failure(e.localizedMessage ?: "Failed to authorize Google Calendar.")
        }
    }

    fun extractTokenFromIntent(context: Context, data: Intent?): String? {
        return try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            result.accessToken
        } catch (_: Exception) {
            null
        }
    }

    suspend fun syncDailyDeliveriesToCalendar(
        accessToken: String,
        dateKey: String,
        deliveries: List<DeliveryRecord>,
        summary: DailySummary,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val transport = NetHttpTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()
            val initializer = HttpRequestInitializer { request ->
                request.headers.authorization = "Bearer $accessToken"
            }

            val service = Calendar.Builder(transport, jsonFactory, initializer)
                .setApplicationName("Minige")
                .build()

            val date = MinigeDateUtils.parseKey(dateKey)
            val nextDateKey = MinigeDateUtils.toKey(date.plusDays(1))

            val existingEventId = deliveries.firstNotNullOfOrNull { it.calendarEventId }
            val paidLines = deliveries.filter { it.paymentType != PaymentType.PICK_UP }
            val pickupLines = deliveries.filter { it.paymentType == PaymentType.PICK_UP }

            val descriptionBuilder = StringBuilder().apply {
                appendLine("Minige Daily Delivery Log (${MinigeDateUtils.formatDisplay(date)})")
                appendLine("Paid Gallons: ${summary.totalPaidGallons} (Direct: ${summary.directPaidGallons}, Utang Settled: ${summary.settledUtangGallons})")
                appendLine("Na-pick up na Gallon: ${summary.pickupGallons} gals")
                appendLine()
                if (paidLines.isNotEmpty()) {
                    appendLine("Deliveries:")
                    paidLines.forEach { d ->
                        appendLine("• ${d.customerName}: ${d.totalGallons} gals (${d.paymentType.label}) - ₱${d.totalAmount}")
                    }
                    appendLine()
                }
                if (pickupLines.isNotEmpty()) {
                    appendLine("Pick-ups:")
                    pickupLines.forEach { d ->
                        appendLine("• ${d.customerName}: ${d.totalGallons} gals")
                    }
                }
            }

            val event = Event()
                .setSummary("Minige: ${summary.totalPaidGallons} Paid / ${summary.pickupGallons} Pick-up Gals")
                .setDescription(descriptionBuilder.toString().trim())
                .setStart(EventDateTime().setDate(DateTime(dateKey)))
                .setEnd(EventDateTime().setDate(DateTime(nextDateKey)))

            val savedEvent = if (!existingEventId.isNullOrBlank()) {
                runCatching {
                    service.events().update("primary", existingEventId, event).execute()
                }.getOrElse {
                    service.events().insert("primary", event).execute()
                }
            } else {
                service.events().insert("primary", event).execute()
            }

            Result.success(savedEvent.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
