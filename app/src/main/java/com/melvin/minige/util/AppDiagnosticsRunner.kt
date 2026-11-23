package com.melvin.minige.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.api.services.calendar.CalendarScopes
import com.melvin.minige.BuildConfig
import com.melvin.minige.auth.GoogleAuthManager
import com.melvin.minige.data.remote.MinigeFirestoreDataSource
import com.melvin.minige.domain.CustomerRecord
import com.melvin.minige.domain.DailyLedgerRecord
import com.melvin.minige.domain.DeliveryRecord
import com.melvin.minige.domain.ExpenseRecord
import com.melvin.minige.domain.MinigeCalculator
import com.melvin.minige.domain.PaymentType
import com.melvin.minige.domain.UtangPaymentRecord
import com.melvin.minige.sync.BackupMetadataSummary
import com.melvin.minige.sync.DriveBackupManager
import com.melvin.minige.sync.DriveBackupStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DiagnosticStatus(val badgeLabel: String) {
    PASS("PASS"),
    WARN("WARN"),
    FAIL("FAIL"),
}

@Immutable
data class DiagnosticCheckItem(
    val id: String,
    val title: String,
    val status: DiagnosticStatus,
    val summary: String,
    val detail: String = "",
)

@Immutable
data class AppDiagnosticReport(
    val generatedAtEpochMs: Long,
    val generatedAtDisplay: String,
    val overallStatus: DiagnosticStatus,
    val items: List<DiagnosticCheckItem>,
    val formattedReportText: String,
)

@Immutable
data class DiagnosticEnvironmentSnapshot(
    val isFirebaseSignedIn: Boolean,
    val accountLabelMasked: String,
    val pendingSyncCount: Int,
    val hasDrivePermission: Boolean,
    val hasDriveCachedToken: Boolean,
    val driveBackupStatus: DriveBackupStatus,
    val lastDriveBackupTimestamp: Long,
    val backupMetadataSummary: BackupMetadataSummary?,
    val hasMicPermission: Boolean,
    val hasCalendarPermission: Boolean,
    val canRequestPackageInstalls: Boolean,
    val versionName: String,
    val versionCode: Int,
    val lastFirestoreSyncError: String? = null,
    val silentDriveRefreshSucceeded: Boolean = false,
)

/**
 * Read-only health check & diagnostic report generator for Minige.
 * Never reads or logs private vault internals, OAuth tokens, or user passwords.
 */
object AppDiagnosticsRunner {

    fun run(
        context: Context,
        userId: String,
        selectedDateKey: String,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
        expenses: List<ExpenseRecord>,
        dailyLedgers: List<DailyLedgerRecord>,
        customers: List<CustomerRecord>,
        defaultSukli: Int,
        pendingSyncCount: Int,
        driveBackupStatus: DriveBackupStatus,
        lastDriveBackupTimestamp: Long,
        backupMetadataSummary: BackupMetadataSummary?,
        silentDriveRefreshSucceeded: Boolean = false,
        lastFirestoreSyncError: String? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AppDiagnosticReport {
        val appContext = context.applicationContext
        val firebaseUser = runCatching { GoogleAuthManager.currentUser() }.getOrNull()
        val hasMic = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        val signedInAccount = runCatching { GoogleSignIn.getLastSignedInAccount(appContext) }.getOrNull()
        val hasPersistedDriveAuth = runCatching {
            GoogleAuthManager.hasDrivePermission(appContext) ||
                DriveBackupManager.getLastSuccessfulDriveAuthEpochMs(appContext, userId) > 0L
        }.getOrDefault(false)
        val hasDrive = hasPersistedDriveAuth || silentDriveRefreshSucceeded
        val hasCachedDriveToken = runCatching {
            !DriveBackupManager.getCachedToken(appContext, userId).isNullOrBlank()
        }.getOrDefault(false)
        val hasCalendar = runCatching {
            signedInAccount != null &&
                GoogleSignIn.hasPermissions(signedInAccount, Scope(CalendarScopes.CALENDAR_EVENTS))
        }.getOrDefault(false)
        val canInstallPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }
        val recordedSyncError = lastFirestoreSyncError
            ?: runCatching { MinigeFirestoreDataSource.getLastSyncError(appContext, userId) }.getOrNull()

        val env = DiagnosticEnvironmentSnapshot(
            isFirebaseSignedIn = firebaseUser != null,
            accountLabelMasked = maskEmail(firebaseUser?.email),
            pendingSyncCount = pendingSyncCount,
            hasDrivePermission = hasDrive,
            hasDriveCachedToken = hasCachedDriveToken,
            driveBackupStatus = if (silentDriveRefreshSucceeded && driveBackupStatus == DriveBackupStatus.NEEDS_PERMISSION) {
                DriveBackupStatus.ACTIVE
            } else {
                driveBackupStatus
            },
            lastDriveBackupTimestamp = lastDriveBackupTimestamp,
            backupMetadataSummary = backupMetadataSummary,
            hasMicPermission = hasMic,
            hasCalendarPermission = hasCalendar,
            canRequestPackageInstalls = canInstallPackages,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            lastFirestoreSyncError = recordedSyncError,
            silentDriveRefreshSucceeded = silentDriveRefreshSucceeded,
        )

        return evaluate(
            env = env,
            selectedDateKey = selectedDateKey,
            deliveries = deliveries,
            utangPayments = utangPayments,
            expenses = expenses,
            dailyLedgers = dailyLedgers,
            customers = customers,
            defaultSukli = defaultSukli,
            nowEpochMs = nowEpochMs,
        )
    }

    fun evaluate(
        env: DiagnosticEnvironmentSnapshot,
        selectedDateKey: String,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
        expenses: List<ExpenseRecord>,
        dailyLedgers: List<DailyLedgerRecord>,
        customers: List<CustomerRecord>,
        defaultSukli: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AppDiagnosticReport {
        val items = listOf(
            checkFirebaseAuthAndSync(env),
            checkDriveBackup(env),
            checkLedgerMathIntegrity(
                selectedDateKey = selectedDateKey,
                deliveries = deliveries,
                utangPayments = utangPayments,
                expenses = expenses,
                dailyLedgers = dailyLedgers,
                defaultSukli = defaultSukli,
            ),
            checkPermissions(env),
            checkVersionAndRecordCounts(
                env = env,
                deliveries = deliveries,
                utangPayments = utangPayments,
                expenses = expenses,
                dailyLedgers = dailyLedgers,
                customers = customers,
            ),
        ).map { item ->
            item.copy(
                summary = redactSensitiveInfo(item.summary),
                detail = redactSensitiveInfo(item.detail),
            )
        }

        val overall = when {
            items.any { it.status == DiagnosticStatus.FAIL } -> DiagnosticStatus.FAIL
            items.any { it.status == DiagnosticStatus.WARN } -> DiagnosticStatus.WARN
            else -> DiagnosticStatus.PASS
        }

        val timestampDisplay = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.ENGLISH)
            .format(Date(nowEpochMs))

        val rawReport = buildString {
            appendLine("=== MINIGE APP DIAGNOSTICS ===")
            appendLine("Generated: $timestampDisplay")
            appendLine("Overall Health: ${overall.badgeLabel}")
            appendLine("App Version: v${env.versionName} (code ${env.versionCode})")
            appendLine()
            items.forEachIndexed { idx, item ->
                appendLine("${idx + 1}. [${item.status.badgeLabel}] ${item.title}")
                appendLine("   Summary: ${item.summary}")
                if (item.detail.isNotBlank()) {
                    appendLine("   Detail: ${item.detail}")
                }
            }
        }.trimEnd()

        return AppDiagnosticReport(
            generatedAtEpochMs = nowEpochMs,
            generatedAtDisplay = timestampDisplay,
            overallStatus = overall,
            items = items,
            formattedReportText = redactSensitiveInfo(rawReport),
        )
    }

    private fun checkFirebaseAuthAndSync(env: DiagnosticEnvironmentSnapshot): DiagnosticCheckItem {
        val lastError = env.lastFirestoreSyncError?.trim()?.takeIf { it.isNotEmpty() }
        val status = when {
            !env.isFirebaseSignedIn -> DiagnosticStatus.FAIL
            env.pendingSyncCount > 0 -> DiagnosticStatus.WARN
            else -> DiagnosticStatus.PASS
        }
        val summary = when {
            !env.isFirebaseSignedIn -> "Firebase Auth is not signed in."
            env.pendingSyncCount > 0 && lastError != null ->
                "Signed in (${env.accountLabelMasked}) • ${env.pendingSyncCount} item(s) waiting for cloud sync ($lastError)"
            env.pendingSyncCount > 0 ->
                "Signed in (${env.accountLabelMasked}) • ${env.pendingSyncCount} item(s) waiting for cloud sync."
            else -> "Signed in (${env.accountLabelMasked}) • 0 pending sync items."
        }
        val detail = if (lastError != null) {
            "Offline-first Room queue pending count: ${env.pendingSyncCount} | Last sync error: $lastError"
        } else {
            "Offline-first Room queue pending count: ${env.pendingSyncCount}"
        }
        return DiagnosticCheckItem(
            id = "firebase_auth_sync",
            title = "Firebase Auth & Sync Queue",
            status = status,
            summary = summary,
            detail = detail,
        )
    }

    private fun checkDriveBackup(env: DiagnosticEnvironmentSnapshot): DiagnosticCheckItem {
        val driveAuthorized = env.hasDrivePermission || env.silentDriveRefreshSucceeded
        val needsConsent = !driveAuthorized ||
            (!env.silentDriveRefreshSucceeded && env.driveBackupStatus == DriveBackupStatus.NEEDS_PERMISSION)

        val status = when {
            env.driveBackupStatus == DriveBackupStatus.ERROR -> DiagnosticStatus.FAIL
            needsConsent -> DiagnosticStatus.WARN
            else -> DiagnosticStatus.PASS
        }
        val backupTimeText = if (env.lastDriveBackupTimestamp > 0L) {
            DriveBackupManager.formatBackupTimestamp(env.lastDriveBackupTimestamp)
        } else {
            "Ready (no manual backup yet)"
        }
        val tokenPresence = when {
            env.silentDriveRefreshSucceeded -> "verified (redacted)"
            env.hasDriveCachedToken -> "cached (redacted)"
            else -> "on-demand"
        }
        val summary = when (status) {
            DiagnosticStatus.FAIL -> "Google Drive backup status is ERROR; auto-retry will run."
            DiagnosticStatus.WARN -> "Google Drive drive.file permission is not granted yet."
            DiagnosticStatus.PASS -> "Drive drive.file active (token: $tokenPresence) • Last backup: $backupTimeText"
        }
        val summaryMeta = env.backupMetadataSummary?.let {
            "Backup file metadata: v${it.appVersion} (${it.totalDeliveries} deliveries, ${it.totalPayments} payments)"
        } ?: "Backup file: Minige Backup/${DriveBackupManager.BACKUP_FILE_NAME}"

        return DiagnosticCheckItem(
            id = "google_drive_backup",
            title = "Google Drive Backup (drive.file)",
            status = status,
            summary = summary,
            detail = summaryMeta,
        )
    }

    private fun checkLedgerMathIntegrity(
        selectedDateKey: String,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
        expenses: List<ExpenseRecord>,
        dailyLedgers: List<DailyLedgerRecord>,
        defaultSukli: Int,
    ): DiagnosticCheckItem {
        val issues = mutableListOf<String>()

        // 1. Validate individual record invariants
        deliveries.forEach { d ->
            if (d.purifiedGallons < 0 || d.alkalineGallons < 0) {
                issues += "Negative gallon count in delivery '${d.customerName}' (${d.dateKey})."
            }
            if (d.totalAmount < 0 || d.amountPaid < 0 || d.remainingBalance < 0) {
                issues += "Negative monetary balance in delivery '${d.customerName}' (${d.dateKey})."
            }
            if (d.commissionEarned < 0 || d.commissionRate < 0) {
                issues += "Negative commission in delivery '${d.customerName}' (${d.dateKey})."
            }
            if (d.paymentType == PaymentType.PICK_UP && (d.commissionEarned != 0 || d.totalAmount != 0)) {
                issues += "PICK_UP entry '${d.customerName}' (${d.dateKey}) must earn ₱0 commission and ₱0 bill."
            }
        }
        expenses.forEach { e ->
            if (e.amount < 0) {
                issues += "Negative expense amount (${e.category.label}) on ${e.dateKey}."
            }
        }
        utangPayments.forEach { p ->
            if (p.amount < 0) {
                issues += "Negative pautang payment amount on ${p.dateKey}."
            }
        }

        // 2. Validate daily Cash-to-Remit identity across all dates present
        val ledgerByDate = dailyLedgers.associateBy { it.dateKey }
        val allDates = buildSet {
            add(selectedDateKey)
            deliveries.mapTo(this) { it.dateKey }
            expenses.mapTo(this) { it.dateKey }
            utangPayments.mapTo(this) { it.dateKey }
            dailyLedgers.mapTo(this) { it.dateKey }
        }

        allDates.forEach { dateKey ->
            val sukli = ledgerByDate[dateKey]?.startingSukli ?: defaultSukli
            val daily = MinigeCalculator.calculateDailySummary(
                dateKey = dateKey,
                deliveries = deliveries,
                utangPayments = utangPayments,
                expenses = expenses,
                startingSukli = sukli,
            )
            val breakdown = PaymentBreakdownHelper.calculateBreakdown(
                dateKey = dateKey,
                deliveries = deliveries,
                utangPayments = utangPayments,
                expenses = expenses,
            )
            val expectedNetRemit = daily.cashCollected - daily.expensesTotal - daily.cashAdvanceTotal
            if (daily.netCashRemit != expectedNetRemit || breakdown.cashToRemit != expectedNetRemit) {
                issues += "Remittance mismatch on $dateKey: summary=${daily.netCashRemit}, breakdown=${breakdown.cashToRemit}, expected=$expectedNetRemit."
            }
            if (daily.dailyCommission < 0) {
                issues += "Negative daily commission (${daily.dailyCommission}) on $dateKey."
            }
        }

        return if (issues.isEmpty()) {
            DiagnosticCheckItem(
                id = "ledger_math_integrity",
                title = "Ledger & Remittance Math Integrity",
                status = DiagnosticStatus.PASS,
                summary = "All ${allDates.size} ledger day(s) pass remittance, commission, and balance checks.",
                detail = "Verified: Cash to Remit == Cash Collected - Route Expenses - Cash Advance across ${deliveries.size} deliveries.",
            )
        } else {
            DiagnosticCheckItem(
                id = "ledger_math_integrity",
                title = "Ledger & Remittance Math Integrity",
                status = DiagnosticStatus.FAIL,
                summary = "${issues.size} integrity issue(s) detected: ${issues.first()}",
                detail = issues.take(5).joinToString(" | "),
            )
        }
    }

    private fun checkPermissions(env: DiagnosticEnvironmentSnapshot): DiagnosticCheckItem {
        val missingRequiredForFeatures = buildList {
            if (!env.hasMicPermission) add("Microphone")
            if (!env.canRequestPackageInstalls) add("Install Unknown Apps")
        }
        // Google Calendar uses on-demand OAuth scope rather than a runtime permission,
        // so Mic: Granted + Installer: Allowed + Calendar: On-demand is healthy [PASS].
        val status = if (missingRequiredForFeatures.isEmpty()) {
            DiagnosticStatus.PASS
        } else {
            DiagnosticStatus.WARN
        }
        val micLabel = if (env.hasMicPermission) "Granted" else "Missing"
        val calendarLabel = if (env.hasCalendarPermission) "Granted" else "On-demand"
        val installLabel = if (env.canRequestPackageInstalls) "Allowed" else "Blocked"

        val summary = if (missingRequiredForFeatures.isEmpty()) {
            "Mic: $micLabel • Installer: $installLabel • Calendar: $calendarLabel"
        } else {
            "Attention needed for: ${missingRequiredForFeatures.joinToString(", ")}"
        }
        return DiagnosticCheckItem(
            id = "device_permissions",
            title = "App & System Permissions",
            status = status,
            summary = summary,
            detail = "Microphone: $micLabel | Google Calendar: $calendarLabel | Unknown Apps Installer: $installLabel",
        )
    }

    private fun checkVersionAndRecordCounts(
        env: DiagnosticEnvironmentSnapshot,
        deliveries: List<DeliveryRecord>,
        utangPayments: List<UtangPaymentRecord>,
        expenses: List<ExpenseRecord>,
        dailyLedgers: List<DailyLedgerRecord>,
        customers: List<CustomerRecord>,
    ): DiagnosticCheckItem {
        return DiagnosticCheckItem(
            id = "version_record_counts",
            title = "App Version & Record Counts",
            status = DiagnosticStatus.PASS,
            summary = "v${env.versionName} (code ${env.versionCode}) • ${deliveries.size} deliveries, ${utangPayments.size} payments, ${expenses.size} expenses, ${customers.size} suki",
            detail = "Daily ledgers stored: ${dailyLedgers.size}",
        )
    }

    internal fun maskEmail(rawEmail: String?): String {
        val trimmed = rawEmail?.trim().orEmpty()
        if (trimmed.isEmpty()) return "Not signed in"
        val atIdx = trimmed.indexOf('@')
        if (atIdx <= 1) return "***@${trimmed.substringAfter('@', "account")}"
        val visible = trimmed.take(2)
        val domain = trimmed.substring(atIdx)
        return "$visible***$domain"
    }

    /**
     * Strictly redacts any OAuth tokens, passwords, secrets, or private vault / internal file paths
     * before rendering or copying diagnostic output.
     */
    fun redactSensitiveInfo(raw: String): String {
        if (raw.isEmpty()) return raw
        return raw
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED]")
            .replace(Regex("ya29\\.[A-Za-z0-9._~+/=-]+"), "[REDACTED]")
            .replace(
                Regex("(?i)\\b(oauth_token|access_token|refresh_token|id_token|password|passwd|secret|api_key|apikey|vault_pin|pin_code)\\b\\s*[:=]\\s*[^\\s,;]+"),
                "$1=[REDACTED]",
            )
            .replace(
                Regex("(?i)com\\.melvin\\.minige\\.vault[^\\s]*"),
                "[REDACTED_VAULT]",
            )
            .replace(
                Regex("(?i)/(data/user/0|data/data|storage/emulated/0)/[^\\s]*(vault|token|secret|auth)[^\\s]*"),
                "[REDACTED_PATH]",
            )
            .replace(
                Regex("(?i)/[A-Za-z0-9_./-]*(vault|private_vault|hidden_vault)[A-Za-z0-9_./-]*"),
                "[REDACTED_PATH]",
            )
    }
}
