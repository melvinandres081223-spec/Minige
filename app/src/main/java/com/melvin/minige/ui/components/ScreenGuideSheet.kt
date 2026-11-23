package com.melvin.minige.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class GuideScreenTab(val label: String) {
    TODAY("Today"),
    PAUTANG("Pautang"),
    WEEKLY("Weekly"),
    SETTINGS("Settings"),
}

data class GuideItem(
    val badgeOrButton: String,
    val taglishExplanation: String,
    val example: String,
)

data class ScreenGuideContent(
    val screenTitle: String,
    val introTaglish: String,
    val items: List<GuideItem>,
)

object ScreenGuideCatalog {

    fun forTab(tab: GuideScreenTab): ScreenGuideContent = when (tab) {
        GuideScreenTab.TODAY -> ScreenGuideContent(
            screenTitle = "Today Screen Guide",
            introTaglish = "Mabilisang gabay sa lahat ng button at numbers sa Today screen.",
            items = listOf(
                GuideItem(
                    badgeOrButton = "Hakot Quota Bar",
                    taglishExplanation = "Makikita sa itaas ang progreso ng galon ngayong araw at kabuuang commission. I-tap ito kung gusto mong baguhin ang daily target.",
                    example = "Hal: \"Hakot: 35 / 50 gal • ₱280 comm\"",
                ),
                GuideItem(
                    badgeOrButton = "Closing",
                    taglishExplanation = "Pribadong end-of-day summary ng commission, sukli, bank payments, gastos, at eksaktong Cash to Remit kay Boss. Hindi ito nasasama sa Messenger Hakot.",
                    example = "Hal: Cash ₱950 − Gas ₱120 = ₱830 remit",
                ),
                GuideItem(
                    badgeOrButton = "Bank / Online & Paid Walk-in",
                    taglishExplanation = "Pinagsama na ang Bank Transfer at Bank Deposit sa 'Bank / Online'. Gamitin ang 'Paid Walk-in' kapag nagbayad na sa station/kay Boss ang suki pero ikaw ang nag-deliver (may switch kung may ₱8/gal commission o ₱0).",
                    example = "Hal: Paid Walk-in • Hindi kasama sa Cash to Remit, pero pasok sa Daily Hakot",
                ),
                GuideItem(
                    badgeOrButton = "+ Cash Advance",
                    taglishExplanation = "I-log dito ang binawas mong Cash Advance. Awtomatiko itong ibinabawas sa Net Cash to Remit ngayong araw at sa Net Remaining Commission mo sa Weekly tab.",
                    example = "Hal: + Cash Advance ₱500 → bawas agad sa Weekly Commission",
                ),
                GuideItem(
                    badgeOrButton = "Daily Hakot",
                    taglishExplanation = "1-Tap send kay Boss sa Messenger. Laman nito ang bayad na deliveries ngayong araw (Cash, Bank / Online, Paid Walk-in, at bayad na Pautang) pati bagong Pautang ngayong araw—walang presyo (₱), commission, o gastos.",
                    example = "Hal: DELIVERED PAID TOTAL: 16 gals • PAUTANG TOTAL: 4 gals",
                ),
                GuideItem(
                    badgeOrButton = "By Area",
                    taglishExplanation = "I-grupo ang mga delivery ayon sa street o lugar at makita agad kung sinong suki sa area na iyon ang hindi pa nadadaanan.",
                    example = "Hal: \"Poblacion • 8 gal | Next: Kuya Jun\"",
                ),
                GuideItem(
                    badgeOrButton = "Sync Status Dot",
                    taglishExplanation = "Green dot = naka-save na sa cloud. Amber dot = naka-save sa phone pero pending sync dahil mahina ang signal; i-tap para tingnan.",
                    example = "Hal: Amber dot → tap Sync Now pag may data na",
                ),
                GuideItem(
                    badgeOrButton = "+ Button & Voice",
                    taglishExplanation = "1-tap para mag-log ng delivery, pick-up, o mabilisang Cash Advance. Pwede ring gamitin ang mic button para magdikta.",
                    example = "Hal: Sabihin \"5 outlet pick up Ate Maria\"",
                ),
            ),
        )

        GuideScreenTab.PAUTANG -> ScreenGuideContent(
            screenTitle = "Pautang Screen Guide",
            introTaglish = "Paano mag-track at maningil ng utang nang tama ang commission.",
            items = listOf(
                GuideItem(
                    badgeOrButton = "Overdue Alert",
                    taglishExplanation = "Lumalabas sa itaas kapag may utang na 3 araw o higit pang hindi bayad para madaling mauna sa singilan.",
                    example = "Hal: \"2 accounts • ₱180\" with \"Overdue (3d)\"",
                ),
                GuideItem(
                    badgeOrButton = "Partial Pay",
                    taglishExplanation = "Gamitin kapag ilang galon pa lang ang binabayaran (Cash o Bank / Online). Agad papasok ang commission para sa mga galong binayaran ngayon.",
                    example = "Hal: 5 gal utang → 2 gal Partial Pay = +₱16 comm",
                ),
                GuideItem(
                    badgeOrButton = "Collect",
                    taglishExplanation = "Gamitin kapag buo o cash/bank payment na ang ibabayad. Pag umabot sa ₱0 ang balanse, papasok ang natitirang commission sa araw ng bayad.",
                    example = "Hal: Bal ₱90 → Collect ₱90 = Settled",
                ),
                GuideItem(
                    badgeOrButton = "Settle Lump-Sum",
                    taglishExplanation = "Kapag may 2 o higit pang open utang ang isang customer at nagbayad ng isang bagsakan, awtomatikong ibabawas ito mula pinakamatandang petsa (FIFO) bago ang mas bago.",
                    example = "Hal: ₱150 bayad → bayad muna ang Nov 18 bago Nov 20",
                ),
                GuideItem(
                    badgeOrButton = "5s Undo Banner",
                    taglishExplanation = "Kapag nagkamali ng pindot sa Collect, Partial Pay, o edit/delete, may lalabas na 'Undo' sa ibaba sa loob ng 5 segundo.",
                    example = "Hal: Napindot Paid → tap Undo agad sa ibaba",
                ),
            ),
        )

        GuideScreenTab.WEEKLY -> ScreenGuideContent(
            screenTitle = "Weekly Screen Guide",
            introTaglish = "Kabuuang lingguhang kita mula Lunes hanggang Sabado (o Linggo).",
            items = listOf(
                GuideItem(
                    badgeOrButton = "Net Remaining Commission",
                    taglishExplanation = "Kinakaltas sa Weekly Total Commission ang kabuuang Cash Advance mo sa linggong ito para kita agad ang natitirang komisyon.",
                    example = "Hal: ₱2,000 Comm − ₱500 Cash Advance = ₱1,500 Net",
                ),
                GuideItem(
                    badgeOrButton = "Outlet vs. Residential",
                    taglishExplanation = "Pinapakita kung ilang galon at commission ang galing sa mga canteen/store (Outlet) kumpara sa bahay (Residential), pati Top Weekly Suki.",
                    example = "Hal: Outlet 45g (₱360) vs Residential 20g (₱160)",
                ),
                GuideItem(
                    badgeOrButton = "Sunday Makeup",
                    taglishExplanation = "I-on ito kapag nag-brownout ng Sabado at bumawi ng delivery ng Linggo para maisama sa weekly summary.",
                    example = "Hal: Mon–Sat + Sun = Mon–Sun total",
                ),
                GuideItem(
                    badgeOrButton = "Calendar Sync",
                    taglishExplanation = "I-save ang delivery report ng napiling araw sa Google Calendar bilang backup.",
                    example = "Hal: Tap Calendar Sync → event saved",
                ),
            ),
        )

        GuideScreenTab.SETTINGS -> ScreenGuideContent(
            screenTitle = "Settings Screen Guide",
            introTaglish = "Dito naka-set ang presyo, commission rate, sync, at app updates.",
            items = listOf(
                GuideItem(
                    badgeOrButton = "Pricing & Commission",
                    taglishExplanation = "Default na presyo ng Purified/Alkaline, starting sukli, at rate per gallon. Hindi nagagalaw ang mga nakaraang araw na commission.",
                    example = "Hal: Purified ₱30 • Alkaline ₱40 • ₱8/gal",
                ),
                GuideItem(
                    badgeOrButton = "Rate History",
                    taglishExplanation = "Mag-schedule ng bagong commission rate na magsisimula sa piniling petsa.",
                    example = "Hal: ₱10/gal effective sa susunod na Lunes",
                ),
                GuideItem(
                    badgeOrButton = "Sync & App Updates",
                    taglishExplanation = "I-push ang offline changes sa Firebase at mag-check kung may bagong Minige APK sa GitHub.",
                    example = "Hal: Tap \"Check for App Updates\"",
                ),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinigeTooltipWrapper(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(text = label)
            }
        },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenGuideSheet(
    initialTab: GuideScreenTab,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    val guide = remember(selectedTab) { ScreenGuideCatalog.forTab(selectedTab) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .testTag("screen_guide_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = guide.screenTitle,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = guide.introTaglish,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Close")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GuideScreenTab.entries.forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = { Text(tab.label, maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("guide_tab_${tab.name.lowercase()}"),
                    )
                }
            }

            guide.items.forEach { item ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = item.badgeOrButton,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        Text(
                            text = item.taglishExplanation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = item.example,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
