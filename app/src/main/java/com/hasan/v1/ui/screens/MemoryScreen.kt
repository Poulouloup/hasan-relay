package com.hasan.v1.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.MemoryFile
import com.hasan.v1.MemoryTab
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanMinimalHeader
import com.hasan.v1.ui.components.MarkdownText
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono
import com.hasan.v1.webui.models.DailyInsight
import com.hasan.v1.webui.models.DayActivity
import com.hasan.v1.webui.models.HermesMemory
import com.hasan.v1.webui.models.HourActivity
import com.hasan.v1.webui.models.InsightsSummary
import com.hasan.v1.webui.models.ModelInsight
import java.util.Locale

/** État affiché par l'écran Memory & Insights — reflète MemoryViewModel.uiState. Lecture seule. */
data class MemoryScreenUiState(
    val selectedTab: MemoryTab,
    val selectedFile: MemoryFile?,
    val memory: HermesMemory?,
    val insights: InsightsSummary?,
    val loading: Boolean,
    val errorMessage: String?
)

class MemoryCallbacks(
    val onMenuClick: () -> Unit,
    val onSelectTab: (MemoryTab) -> Unit,
    val onOpenFile: (MemoryFile) -> Unit,
    val onCloseFile: () -> Unit,
    val onRefresh: () -> Unit,
    val onDismissError: () -> Unit
)

/**
 * Onglet Skills fusionné dans Memory (ex-onglet drawer séparé) — allège la
 * sidebar en regroupant deux écrans lecture-seule conceptuellement proches
 * (skills installées / mémoire & stats), voir prospective de l'audit
 * 4-volets. [skillsContent] reste géré par SkillsFragment/SkillsViewModel
 * (état, détail, refresh) — MemoryScreen ne fait qu'afficher le slot quand
 * l'onglet SKILLS est sélectionné.
 */
@Composable
fun MemoryScreen(
    state: MemoryScreenUiState,
    callbacks: MemoryCallbacks,
    skillsContent: @Composable () -> Unit
) {
    if (state.selectedFile != null) {
        MemoryFileDetailScreen(
            file = state.selectedFile,
            content = contentFor(state.memory, state.selectedFile),
            onClose = callbacks.onCloseFile
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HasanMinimalHeader(callbacks.onMenuClick, title = "Mémoire")
        MemoryTabSwitcher(selectedTab = state.selectedTab, onSelectTab = callbacks.onSelectTab)

        if (state.selectedTab == MemoryTab.SKILLS) {
            skillsContent()
            return@Column
        }

        state.errorMessage?.let { message ->
            MemoryErrorBanner(message = message, onDismiss = callbacks.onDismissError)
        }

        when {
            state.loading && state.memory == null && state.insights == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HasanColors.Accent)
                }
            }
            state.selectedTab == MemoryTab.MEMORY -> MemoryTabContent(state.memory, callbacks.onOpenFile)
            else -> InsightsTabContent(state.insights)
        }
    }
}

private fun contentFor(memory: HermesMemory?, file: MemoryFile): String? = when (file) {
    MemoryFile.MEMORY -> memory?.memory
    MemoryFile.USER -> memory?.user
    MemoryFile.SOUL -> memory?.soul
}

private fun titleFor(file: MemoryFile): String = when (file) {
    MemoryFile.MEMORY -> "MEMORY.md"
    MemoryFile.USER -> "USER.md"
    MemoryFile.SOUL -> "SOUL.md"
}

// header du mockup (ligne 936-940) — flèche retour à gauche (ic-arrow-l, pas une croix
// close), titre en font-mono (pas ChakraPetch), bouton copier à droite ; ordre et icônes
// différents de la version précédente (croix + titre à gauche + copier).
@Composable
private fun MemoryFileDetailScreen(file: MemoryFile, content: String?, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = Modifier.fillMaxSize().background(HasanColors.BgBase)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = HasanDimens.SpacingS, vertical = HasanDimens.SpacingS),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_arrow_l),
                contentDescription = "Retour à la mémoire",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextPrimary),
                modifier = Modifier
                    .size(HasanDimens.TouchTarget)
                    .clickable(onClick = onClose)
                    .padding(HasanDimens.SpacingM)
            )
            Text(
                text = titleFor(file),
                color = HasanColors.TextPrimary,
                fontFamily = IBMPlexMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = HasanDimens.TextHeading,
                modifier = Modifier.weight(1f).padding(start = HasanDimens.SpacingS)
            )
            if (!content.isNullOrBlank()) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_copy),
                    contentDescription = "Copier le contenu",
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextPrimary),
                    modifier = Modifier
                        .size(HasanDimens.TouchTarget)
                        .clickable {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(titleFor(file), content))
                        }
                        .padding(HasanDimens.SpacingM)
                )
            }
        }

        if (content.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxSize().padding(HasanDimens.SpacingXxl), contentAlignment = Alignment.Center) {
                Text(text = "Aucun contenu", color = HasanColors.TextMutedA11y, textAlign = TextAlign.Center)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = HasanDimens.SpacingL)) {
                MarkdownText(text = content, selectable = true, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun MemoryErrorBanner(message: String, onDismiss: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingXs),
        backgroundColor = HasanColors.BgSurface,
        borderColor = HasanColors.Accent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = HasanColors.Accent,
                fontSize = HasanDimens.TextBodyMedium,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_close),
                contentDescription = "Fermer",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextMutedA11y),
                modifier = Modifier
                    .size(HasanDimens.IconSmall)
                    .padding(start = HasanDimens.SpacingS)
                    .clickable(onClick = onDismiss)
            )
        }
    }
}

@Composable
private fun MemoryTabSwitcher(selectedTab: MemoryTab, onSelectTab: (MemoryTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingS),
        horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)
    ) {
        MemoryTabPill(
            label = "MEMORY",
            selected = selectedTab == MemoryTab.MEMORY,
            onClick = { onSelectTab(MemoryTab.MEMORY) },
            modifier = Modifier.weight(1f)
        )
        MemoryTabPill(
            label = "SKILLS",
            selected = selectedTab == MemoryTab.SKILLS,
            onClick = { onSelectTab(MemoryTab.SKILLS) },
            modifier = Modifier.weight(1f)
        )
        MemoryTabPill(
            label = "INSIGHTS",
            selected = selectedTab == MemoryTab.INSIGHTS,
            onClick = { onSelectTab(MemoryTab.INSIGHTS) },
            modifier = Modifier.weight(1f)
        )
    }
}

// .tab du mockup (ligne 385-391) — rectangle net, PAS de cut-corner ; actif = fond
// accent-soft (AccentDim), bordure accent-deep (Accent), texte accent-strong
// (AccentStrong, pas Accent) ; police Chakra Petch capitales (font-display),
// pas IBMPlexMono.
@Composable
private fun MemoryTabPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = HasanDimens.TouchTarget)
            .background(if (selected) HasanColors.AccentDim else HasanColors.BgSurface)
            .border(HasanDimens.BorderWidth, if (selected) HasanColors.Accent else HasanColors.Border)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) HasanColors.AccentStrong else HasanColors.TextSecondary,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center
        )
    }
}

// .section-label du mockup (ligne 394-399) — préfixe "// " en text-disabled, couleur
// accent (AccentLight), 11.5px, bordure pointillée au-dessus (sauf premier élément) —
// même correction que SectionTitle dans SettingsScreen.kt.
@Composable
private fun MemorySectionTitle(text: String, isFirst: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isFirst) Modifier
                else Modifier
                    .drawBehind {
                        drawLine(
                            color = HasanColors.BorderStrong,
                            start = androidx.compose.ui.geometry.Offset(0f, 0f),
                            end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
                        )
                    }
                    .padding(top = 14.dp)
            )
            .padding(bottom = HasanDimens.SpacingS)
    ) {
        Text(text = "// ", color = HasanColors.TextMuted, fontFamily = IBMPlexMono, fontSize = 11.5.sp, letterSpacing = 1.sp)
        Text(text = text, color = HasanColors.AccentLight, fontFamily = IBMPlexMono, fontSize = 11.5.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun MemoryTabContent(memory: HermesMemory?, onOpenFile: (MemoryFile) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = HasanDimens.SpacingL),
        verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingM)
    ) {
        item { MemoryFileRow(title = "MEMORY.md", content = memory?.memory, onClick = { onOpenFile(MemoryFile.MEMORY) }) }
        item { MemoryFileRow(title = "USER.md", content = memory?.user, onClick = { onOpenFile(MemoryFile.USER) }) }
        item { MemoryFileRow(title = "SOUL.md", content = memory?.soul, onClick = { onOpenFile(MemoryFile.SOUL) }) }
    }
}

// .link-row du mockup (ligne 903-905, --notch:8px) — titre en mono 13.5px gras (pas
// ChakraPetch), sous-titre 12px, flèche SVG ic-arrow-r (pas texte "→"), notch 8dp
// (pas le panel() par défaut à 10dp).
@Composable
private fun MemoryFileRow(title: String, content: String?, onClick: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = HasanShapes.panel(cut = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = HasanColors.TextPrimary,
                    fontFamily = IBMPlexMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp
                )
                Text(
                    text = if (content.isNullOrBlank()) "Aucun contenu" else "Toucher pour afficher",
                    color = HasanColors.TextMutedA11y,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_arrow_r),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextPrimary),
                modifier = Modifier.size(HasanDimens.IconSmall)
            )
        }
    }
}

@Composable
private fun InsightsTabContent(insights: InsightsSummary?) {
    if (insights == null) {
        Box(modifier = Modifier.fillMaxSize().padding(HasanDimens.SpacingXxl), contentAlignment = Alignment.Center) {
            Text(
                text = "Statistiques indisponibles",
                color = HasanColors.TextMutedA11y,
                textAlign = TextAlign.Center
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = HasanDimens.SpacingL),
        verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingM)
    ) {
        item { InsightsSummaryPanel(insights) }
        if (insights.models.isNotEmpty()) {
            item { MemorySectionTitle("PAR MODÈLE") }
            items(insights.models, key = { it.model }) { model -> ModelInsightRow(model) }
        }
        if (insights.dailyTokens.isNotEmpty()) {
            item { MemorySectionTitle("TOKENS PAR JOUR") }
            items(insights.dailyTokens, key = { it.date }) { daily -> DailyInsightRow(daily) }
        }
        if (insights.activityByDay.isNotEmpty()) {
            item { MemorySectionTitle("ACTIVITÉ PAR JOUR DE SEMAINE") }
            item { DayActivityPanel(insights.activityByDay) }
        }
        if (insights.activityByHour.isNotEmpty()) {
            item { MemorySectionTitle("ACTIVITÉ PAR HEURE") }
            item { HourActivityPanel(insights.activityByHour) }
        }
    }
}

@Composable
private fun InsightsSummaryPanel(insights: InsightsSummary) {
    CutCornerPanel(modifier = Modifier.fillMaxWidth(), shape = HasanShapes.panel()) {
        Column(modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM)) {
            MemorySectionTitle("${insights.periodDays} DERNIERS JOURS", isFirst = true)
            InsightStatRow("Sessions", formatThousands(insights.totalSessions))
            InsightStatRow("Messages", formatThousands(insights.totalMessages))
            InsightStatRow("Tokens entrée", formatThousands(insights.totalInputTokens))
            InsightStatRow("Tokens sortie", formatThousands(insights.totalOutputTokens))
            InsightStatRow("Tokens total", formatThousands(insights.totalTokens))
            // .stat-row .v du mockup (ligne 922) — Cache hit en vert (--success), seul stat
            // avec une couleur override (les autres restent text-primary).
            insights.totalCacheHitPercent?.let { InsightStatRow("Cache hit", "$it%", valueColor = HasanColors.Success) }
            InsightStatRow("Coût", formatCost(insights.totalCost))
        }
    }
}

@Composable
private fun InsightStatRow(label: String, value: String, valueColor: Color = HasanColors.TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = HasanColors.TextMutedA11y, fontFamily = IBMPlexMono, fontSize = HasanDimens.TextBodyMedium)
        Text(text = value, color = valueColor, fontFamily = IBMPlexMono, fontSize = HasanDimens.TextBodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** "195109374" -> "195 109 374" — .stat-row .v du mockup (ligne 918-921) sépare les milliers par un espace. */
private fun formatThousands(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(" ").reversed()

@Composable
private fun ModelInsightRow(model: ModelInsight) {
    CutCornerPanel(modifier = Modifier.fillMaxWidth(), shape = HasanShapes.panel()) {
        Column(modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model.model,
                    color = HasanColors.TextPrimary,
                    fontFamily = ChakraPetch,
                    fontSize = HasanDimens.TextSubtitle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(text = "${model.costShare}%", color = HasanColors.Accent, fontFamily = IBMPlexMono, fontSize = HasanDimens.TextCaption)
            }
            Text(
                text = "${model.sessions} sessions · ${model.totalTokens} tokens · ${formatCost(model.cost)}",
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextCaption,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun DailyInsightRow(daily: DailyInsight) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = daily.date, color = HasanColors.TextSecondary, fontFamily = IBMPlexMono, fontSize = HasanDimens.TextCaption)
        Text(
            text = "${daily.sessions} sess. · ${daily.inputTokens + daily.outputTokens} tok · ${formatCost(daily.cost)}",
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextCaption
        )
    }
}

@Composable
private fun DayActivityPanel(days: List<DayActivity>) {
    CutCornerPanel(modifier = Modifier.fillMaxWidth(), shape = HasanShapes.panel()) {
        Column(modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM)) {
            days.forEach { day -> InsightStatRow(day.day, "${day.sessions} sessions") }
        }
    }
}

@Composable
private fun HourActivityPanel(hours: List<HourActivity>) {
    CutCornerPanel(modifier = Modifier.fillMaxWidth(), shape = HasanShapes.panel()) {
        Column(modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM)) {
            hours.forEach { hour ->
                InsightStatRow(String.format(Locale.getDefault(), "%02dh", hour.hour), "${hour.sessions} sessions")
            }
        }
    }
}

private fun formatCost(cost: Double): String = String.format(Locale.getDefault(), "$%.2f", cost)
