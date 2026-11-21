package com.melvin.minige.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melvin.minige.BuildConfig
import com.melvin.minige.ui.theme.DarkOnSurface
import com.melvin.minige.ui.theme.DarkOnSurfaceVariant
import com.melvin.minige.ui.theme.DeepSlate
import com.melvin.minige.ui.theme.ElectricTeal
import com.melvin.minige.ui.theme.MidnightNavy
import com.melvin.minige.ui.theme.SkyBlue
import com.melvin.minige.ui.theme.SurfaceElevated
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object DeveloperSignatureConfig {
    const val SHARE_URL = "https://github.com/melvinandres081223-spec/Minige/releases/latest"
    const val EASTER_EGG_TAPS = 5
    const val EASTER_EGG_MESSAGE = "100% Built from Scratch by Melvin 💧"

    fun buildShareMessage(versionName: String): String {
        return "Check out Minige (v$versionName) — our water delivery & commission tracker app!\n" +
            "Download the latest APK here: $SHARE_URL"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperSignatureCard(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var easterEggTapCount by remember { mutableIntStateOf(0) }

    val cardGradient = remember {
        Brush.linearGradient(
            colors = listOf(
                MidnightNavy,
                DeepSlate,
                SurfaceElevated.copy(alpha = 0.96f),
            )
        )
    }
    val borderBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                SkyBlue.copy(alpha = 0.72f),
                ElectricTeal.copy(alpha = 0.85f),
                SkyBlue.copy(alpha = 0.40f),
            )
        )
    }

    val shimmerTransition = rememberInfiniteTransition(label = "monogram_glow")
    val shimmerPhase by shimmerTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "monogram_phase",
    )
    val glowPulse by shimmerTransition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.52f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "monogram_pulse",
    )

    Surface(
        onClick = {
            val nextCount = easterEggTapCount + 1
            if (nextCount >= DeveloperSignatureConfig.EASTER_EGG_TAPS) {
                easterEggTapCount = 0
                Toast.makeText(
                    context,
                    DeveloperSignatureConfig.EASTER_EGG_MESSAGE,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                easterEggTapCount = nextCount
            }
        },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderBrush),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("developer_signature_card"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardGradient)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: Circular Monogram Badge ("M" + Water Droplet accent) with soft shimmer/glow
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .drawBehind {
                            val angleRad = shimmerPhase * 2f * PI.toFloat()
                            val centerOffset = Offset(
                                x = size.width * 0.5f + cos(angleRad) * (size.width * 0.14f),
                                y = size.height * 0.5f + sin(angleRad) * (size.height * 0.14f),
                            )
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        ElectricTeal.copy(alpha = glowPulse),
                                        SkyBlue.copy(alpha = glowPulse * 0.45f),
                                        MidnightNavy.copy(alpha = 0f),
                                    ),
                                    center = centerOffset,
                                    radius = size.minDimension * 0.68f,
                                ),
                                radius = size.minDimension * 0.68f,
                            )
                        }
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    ElectricTeal.copy(alpha = 0.22f),
                                    SkyBlue.copy(alpha = 0.16f),
                                )
                            )
                        )
                        .border(
                            width = 1.5.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(SkyBlue, ElectricTeal)
                            ),
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WaterDrop,
                        contentDescription = null,
                        tint = SkyBlue.copy(alpha = 0.24f),
                        modifier = Modifier
                            .size(34.dp)
                            .align(Alignment.Center),
                    )
                    Text(
                        text = "M",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp,
                        ),
                        color = ElectricTeal,
                    )
                }

                // Center: Typography hierarchy + Official Build pill
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "CRAFTED & ENGINEERED BY",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.4.sp,
                        ),
                        color = ElectricTeal.copy(alpha = 0.92f),
                        modifier = Modifier.padding(end = 44.dp),
                    )
                    Text(
                        text = "Melvin",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                        ),
                        color = DarkOnSurface,
                        modifier = Modifier.padding(end = 44.dp),
                    )
                    Text(
                        text = "Built for the Daily Route",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = FontStyle.Italic,
                        ),
                        color = DarkOnSurfaceVariant,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        Text(
                            text = "Minige • v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelMedium,
                            color = DarkOnSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        Surface(
                            shape = CircleShape,
                            color = ElectricTeal.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, ElectricTeal.copy(alpha = 0.40f)),
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = ElectricTeal,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "Official Build",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = ElectricTeal,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }

            // Top-right compact Share button
            IconButton(
                onClick = { shareAppRelease(context) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
                    .testTag("share_app_signature_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Share Minige App",
                    tint = SkyBlue,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun shareAppRelease(context: Context) {
    val shareText = DeveloperSignatureConfig.buildShareMessage(BuildConfig.VERSION_NAME)
    val intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Minige Water Delivery App")
            putExtra(Intent.EXTRA_TEXT, shareText)
        },
        "Share Minige App",
    )
    runCatching { context.startActivity(intent) }
}
