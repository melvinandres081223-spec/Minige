package com.melvin.minige.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melvin.minige.domain.InactiveSeverity
import com.melvin.minige.domain.InactiveSukiAlert
import com.melvin.minige.domain.MinigeCustomerUtils

object InactiveSukiAlertPrefs {
    const val DEFAULT_ENABLED = true
    const val DEFAULT_THRESHOLD_DAYS = 3
    const val MIN_THRESHOLD_DAYS = 1
    const val MAX_THRESHOLD_DAYS = 30
    private const val PREFS_NAME = "minige_inactive_suki_prefs"
    private const val KEY_ENABLED_PREFIX = "enabled_"
    private const val KEY_THRESHOLD_PREFIX = "threshold_days_"

    fun isEnabled(context: Context, userId: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("$KEY_ENABLED_PREFIX$userId", DEFAULT_ENABLED)
    }

    fun setEnabled(context: Context, userId: String, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("$KEY_ENABLED_PREFIX$userId", enabled).apply()
    }

    fun loadThresholdDays(context: Context, userId: String): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("$KEY_THRESHOLD_PREFIX$userId", DEFAULT_THRESHOLD_DAYS)
            .coerceIn(MIN_THRESHOLD_DAYS, MAX_THRESHOLD_DAYS)
    }

    fun saveThresholdDays(context: Context, userId: String, days: Int): Int {
        val clamped = days.coerceIn(MIN_THRESHOLD_DAYS, MAX_THRESHOLD_DAYS)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("$KEY_THRESHOLD_PREFIX$userId", clamped).apply()
        return clamped
    }
}

@Composable
fun InactiveSukiHeaderBadge(
    count: Int,
    hasCritical: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return

    val containerColor = if (hasCritical) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val borderColor = if (hasCritical) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
    }
    val accentColor = if (hasCritical) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    val badgeTextColor = if (hasCritical) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onTertiary
    }

    MinigeTooltipWrapper(label = "$count suki 3+ days walang karga") {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = containerColor,
            border = BorderStroke(1.dp, borderColor),
            tonalElevation = 2.dp,
            modifier = modifier
                .minimumInteractiveComponentSize()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("inactive_suki_header_badge"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (hasCritical) Icons.Rounded.NotificationsActive else Icons.Rounded.WarningAmber,
                    contentDescription = "$count inactive suki alert",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
                Surface(
                    shape = CircleShape,
                    color = accentColor,
                ) {
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = badgeTextColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun InactiveSukiWarningChip(
    alert: InactiveSukiAlert,
    modifier: Modifier = Modifier,
) {
    val isCritical = alert.severity == InactiveSeverity.RED_CRITICAL
    val containerColor = if (isCritical) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isCritical) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val borderColor = if (isCritical) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
    }
    val badgeText = if (isCritical) {
        "🚨 5d+"
    } else {
        "⚠️ 3d+"
    }

    Surface(
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.testTag("inactive_warning_chip_${alert.customerId}"),
    ) {
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InactiveSukiAlertCard(
    alerts: List<InactiveSukiAlert>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onReorderClick: (InactiveSukiAlert) -> Unit,
    modifier: Modifier = Modifier,
    thresholdDays: Int = InactiveSukiAlertPrefs.DEFAULT_THRESHOLD_DAYS,
) {
    if (alerts.isEmpty()) return

    val criticalCount = alerts.count { it.severity == InactiveSeverity.RED_CRITICAL }
    val longestDays = alerts.maxOf { it.daysSinceLastDelivery }
    val borderColor = if (criticalCount > 0) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.38f),
        border = BorderStroke(1.5.dp, borderColor),
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("inactive_suki_alert_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (criticalCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = "${thresholdDays}+ days walang karga warning",
                            tint = if (criticalCount > 0) {
                                MaterialTheme.colorScheme.onError
                            } else {
                                MaterialTheme.colorScheme.onTertiary
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "⚠️ ${thresholdDays}+ DAYS WALANG KARGA",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sukiWord = if (alerts.size == 1) "suki" else "suki"
                    Text(
                        text = "${alerts.size} $sukiWord overdue • Pinakamatagal: ${longestDays}d",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("toggle_inactive_suki_card_button"),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse inactive suki list" else "Expand inactive suki list",
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = borderColor.copy(alpha = 0.45f))
                    alerts.forEach { alert ->
                        InactiveSukiAlertRow(
                            alert = alert,
                            onReorderClick = { onReorderClick(alert) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InactiveSukiAlertRow(
    alert: InactiveSukiAlert,
    onReorderClick: () -> Unit,
) {
    val isCritical = alert.severity == InactiveSeverity.RED_CRITICAL
    val pillContainerColor = if (isCritical) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val pillTextColor = if (isCritical) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val pillBorderColor = if (isCritical) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
    }
    val elapsedBadgeLabel = if (isCritical) {
        "🚨 5+ araw na (${alert.daysSinceLastDelivery}d)"
    } else {
        "⏰ ${alert.daysSinceLastDelivery} araw na"
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, pillBorderColor.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inactive_suki_row_${alert.customerId}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = alert.customerName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    Surface(
                        shape = CircleShape,
                        color = pillContainerColor,
                        border = BorderStroke(1.dp, pillBorderColor),
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Text(
                            text = elapsedBadgeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = pillTextColor,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }

                val priceSuffix = alert.lastUnitPrice
                    ?.takeIf { it > 0.0 }
                    ?.let { " • ₱${MinigeCustomerUtils.formatPrice(it)}/gal" }
                    .orEmpty()
                Text(
                    text = "Last order: ${alert.lastOrderGallons} gal(s)$priceSuffix • ${alert.lastDeliveryDateKey}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FilledTonalButton(
                onClick = onReorderClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("reorder_inactive_${alert.customerId}"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Replay,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Re-order",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
