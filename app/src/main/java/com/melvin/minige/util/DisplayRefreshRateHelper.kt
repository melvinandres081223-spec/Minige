package com.melvin.minige.util

import android.app.Activity
import android.os.Build
import android.view.Display

data class DisplayModeSpec(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float,
)

object DisplayRefreshRateHelper {

    fun selectHighestRefreshRateMode(
        modes: List<DisplayModeSpec>,
        activeWidth: Int? = null,
        activeHeight: Int? = null,
    ): DisplayModeSpec? {
        if (modes.isEmpty()) return null

        val matchingResolution = if (activeWidth != null && activeHeight != null && activeWidth > 0 && activeHeight > 0) {
            modes.filter { it.physicalWidth == activeWidth && it.physicalHeight == activeHeight }
        } else {
            emptyList()
        }

        val candidates = matchingResolution.ifEmpty { modes }
        return candidates.maxWithOrNull(
            compareBy<DisplayModeSpec> { it.refreshRate }
                .thenBy { it.physicalWidth.toLong() * it.physicalHeight.toLong() }
        )
    }

    fun applyMaxRefreshRate(activity: Activity) {
        runCatching {
            val window = activity.window ?: return
            val display: Display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager?.defaultDisplay
            } ?: return

            val activeMode = display.mode
            val supportedModes = display.supportedModes?.map { mode ->
                DisplayModeSpec(
                    modeId = mode.modeId,
                    physicalWidth = mode.physicalWidth,
                    physicalHeight = mode.physicalHeight,
                    refreshRate = mode.refreshRate,
                )
            }.orEmpty()

            val optimal = selectHighestRefreshRateMode(
                modes = supportedModes,
                activeWidth = activeMode?.physicalWidth,
                activeHeight = activeMode?.physicalHeight,
            )

            val params = window.attributes
            var updated = false

            if (optimal != null && params.preferredDisplayModeId != optimal.modeId) {
                params.preferredDisplayModeId = optimal.modeId
                updated = true
            }

            val bestRefreshRate = optimal?.refreshRate ?: activeMode?.refreshRate ?: 0f
            if (bestRefreshRate > 0f && params.preferredRefreshRate != bestRefreshRate) {
                params.preferredRefreshRate = bestRefreshRate
                updated = true
            }

            if (updated) {
                window.attributes = params
            }
        }
    }
}
