package com.hasan.v1.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.webui.models.SkillDetail
import com.hasan.v1.webui.models.SkillSummary
import com.hasan.v1.webui.models.SkillUsage
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanMinimalHeader
import com.hasan.v1.ui.components.MarkdownText
import com.hasan.v1.ui.components.TagPill
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono

/** Nom de la catégorie de repli pour les skills sans catégorie (category == null côté serveur). */
private const val UNCATEGORIZED_LABEL = "Autres"

/** État affiché par l'écran Skills — reflète SkillsViewModel.uiState. Lecture seule. */
data class SkillsScreenUiState(
    val skills: List<SkillSummary>,
    val usage: Map<String, SkillUsage>,
    val loading: Boolean,
    val errorMessage: String?
)

class SkillsCallbacks(
    val onMenuClick: () -> Unit,
    val onRefresh: () -> Unit,
    val onSkillClick: (SkillSummary) -> Unit,
    val onDismissError: () -> Unit
)

/**
 * [showMenuHeader] désactivé quand cet écran est hébergé comme onglet interne
 * de MemoryScreen (voir MemoryFragment) — le hamburger est déjà affiché par
 * le HasanMinimalHeader du parent, un second dupliqué créerait deux points
 * d'ouverture du drawer sur le même écran.
 *
 * [showRefresh] désactivé pour le même hébergement — .tab-panel[mem-skills] du mockup
 * (ligne 907-913) n'a pas de bouton refresh (juste big-stat + big-stat-label), le
 * rafraîchissement de cet onglet suit celui de l'écran Mémoire parent.
 */
@Composable
fun SkillsScreen(
    state: SkillsScreenUiState,
    callbacks: SkillsCallbacks,
    showMenuHeader: Boolean = true,
    showRefresh: Boolean = true
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (showMenuHeader) HasanMinimalHeader(callbacks.onMenuClick)

        // Groupé par catégorie — le serveur trie déjà (catégorie, nom),
        // "Autres" pour les skills non catégorisées (category == null).
        val grouped = remember(state.skills) {
            state.skills.groupBy { it.category ?: UNCATEGORIZED_LABEL }
        }
        val expandedState = remember {
            mutableStateMapOf<String, Boolean>().apply {
                grouped.keys.forEach { category -> put(category, false) }
            }
        }

        var query by rememberSaveable { mutableStateOf("") }
        // Recherche active : seules les catégories avec au moins un skill qui
        // matche restent visibles ; à l'intérieur d'une catégorie dépliée, seuls
        // les skills qui matchent le texte s'affichent. Les catégories restent
        // REPLIÉES par défaut (searching ne force PAS le dépli — la recherche
        // filtre la visibilité, pas l'état de dépli, qui reste piloté par
        // expandedState comme en dehors de la recherche).
        val searching = query.isNotBlank()
        val filteredGrouped = remember(grouped, query) {
            if (!searching) grouped
            else grouped.mapValues { (_, skills) -> skills.filter { it.matchesQuery(query) } }
                .filterValues { it.isNotEmpty() }
        }
        val matchCount = remember(filteredGrouped) { filteredGrouped.values.sumOf { it.size } }

        SkillsHeader(
            count = state.skills.size,
            matchCount = matchCount.takeIf { searching },
            loading = state.loading,
            showRefresh = showRefresh,
            onRefresh = callbacks.onRefresh
        )

        if (state.skills.isNotEmpty()) {
            SkillsSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingS)
            )
        }

        state.errorMessage?.let { message ->
            SkillsErrorBanner(message = message, onDismiss = callbacks.onDismissError)
        }

        when {
            state.loading && state.skills.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HasanColors.Accent)
                }
            }
            state.skills.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(HasanDimens.SpacingXxl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aucune skill installée",
                        color = HasanColors.TextMutedA11y,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                // Sections dépliantes, TOUTES repliées par défaut (y compris les
                // petites catégories réelles) — la liste à plat de 838 skills
                // était jugée trop dense pour rester lisible même en ne repliant
                // que le gros bloc "Autres" (761/838 skills), donc repli total
                // au premier chargement, chaque section restant un tap.
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = HasanDimens.SpacingL),
                    verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)
                ) {
                    if (searching && filteredGrouped.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingXxl), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Aucune skill ne correspond à « $query »",
                                    color = HasanColors.TextMutedA11y,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    filteredGrouped.forEach { (category, skillsInCategory) ->
                        val isExpanded = expandedState[category] ?: false
                        item(key = "header-$category") {
                            CategoryHeader(
                                category = category,
                                count = skillsInCategory.size,
                                expanded = isExpanded,
                                onToggle = { expandedState[category] = !(expandedState[category] ?: false) }
                            )
                        }
                        if (isExpanded) {
                            items(skillsInCategory, key = { it.name }) { skill ->
                                SkillCard(skill = skill, usage = state.usage[skill.name], onClick = { callbacks.onSkillClick(skill) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun SkillSummary.matchesQuery(query: String): Boolean =
    name.contains(query, ignoreCase = true) || description.contains(query, ignoreCase = true)

/**
 * Champ de recherche skills — même langage visuel que .field du mockup (EditorField dans
 * TaskEditorScreen.kt : fond bg-surface, bordure border-strong, accent-bar gauche 2.5dp,
 * police mono 14px) puisque le mockup n'a pas d'écran de recherche de référence direct.
 * Icône loupe à gauche du texte, croix pour vider quand une saisie est présente.
 */
@Composable
private fun SkillsSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = HasanDimens.TouchTarget)
            .background(HasanColors.BgSurface)
            .border(HasanDimens.BorderWidth, HasanColors.BorderStrong)
            .drawBehind {
                drawRect(color = HasanColors.BorderAccent, size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height))
            }
            .padding(horizontal = HasanDimens.SpacingM, vertical = HasanDimens.SpacingS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            colorFilter = ColorFilter.tint(HasanColors.TextMutedA11y),
            modifier = Modifier.size(HasanDimens.IconSmall)
        )
        Spacer(modifier = Modifier.width(HasanDimens.SpacingS))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Rechercher un skill…",
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = 14.sp
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = HasanColors.TextPrimary,
                    fontFamily = IBMPlexMono,
                    fontSize = 14.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(HasanColors.Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Image(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Effacer la recherche",
                colorFilter = ColorFilter.tint(HasanColors.TextMutedA11y),
                modifier = Modifier
                    .size(HasanDimens.IconSmall)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

@Composable
private fun SkillsErrorBanner(message: String, onDismiss: () -> Unit) {
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
private fun SkillsHeader(count: Int, matchCount: Int?, loading: Boolean, showRefresh: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingL),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = count.toString(),
                    color = HasanColors.TextPrimary,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = HasanDimens.TextDisplay
                )
                // Nombre de résultats filtrés par la recherche — badge accent à côté du
                // compteur total, même style que HasanBadge (Paramètres) : fond translucide
                // + bordure teintée, mono.
                if (matchCount != null) {
                    Box(
                        modifier = Modifier
                            .padding(start = HasanDimens.SpacingS)
                            .background(HasanColors.AccentDim)
                            .border(HasanDimens.BorderWidth, HasanColors.Accent.copy(alpha = 0.35f))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$matchCount résultat${if (matchCount > 1) "s" else ""}",
                            color = HasanColors.AccentStrong,
                            fontFamily = IBMPlexMono,
                            fontSize = 11.sp,
                            letterSpacing = 0.3.sp
                        )
                    }
                }
            }
            Text(
                text = "skills installées",
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextCaption
            )
        }
        if (showRefresh) {
            com.hasan.v1.ui.components.RefreshIconButton(loading = loading, onClick = onRefresh, tint = HasanColors.Accent)
        }
    }
}

@Composable
private fun CategoryHeader(category: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "category-chevron-rotation"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = HasanDimens.SpacingS, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "▶",
            color = HasanColors.TextMutedA11y,
            fontSize = HasanDimens.TextLabelSmall,
            modifier = Modifier.rotate(rotation).padding(end = 6.dp)
        )
        Text(
            text = category.uppercase(),
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextLabelMedium,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextLabelMedium
        )
    }
}

@Composable
private fun SkillCard(skill: SkillSummary, usage: SkillUsage?, onClick: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = HasanShapes.panel()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = skill.name,
                    color = HasanColors.TextPrimary,
                    fontSize = HasanDimens.TextBody,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (skill.disabled) {
                    TagPill(text = "DÉSACTIVÉE", backgroundColor = HasanColors.BgSurface3, contentColor = HasanColors.TextMutedA11y)
                } else if (usage != null && usage.useCount > 0) {
                    TagPill(text = "${usage.useCount}×", backgroundColor = HasanColors.AccentDim, contentColor = HasanColors.Accent)
                }
            }
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    color = HasanColors.TextMutedA11y,
                    fontSize = HasanDimens.TextBodyMedium,
                    maxLines = 2,
                    modifier = Modifier.padding(top = HasanDimens.SpacingXs)
                )
            }
        }
    }
}

class SkillDetailCallbacks(val onBack: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillDetailScreen(
    skillName: String,
    detail: SkillDetail?,
    loading: Boolean,
    callbacks: SkillDetailCallbacks
) {
    Column(modifier = Modifier.fillMaxSize().background(HasanColors.BgBase)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingL),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                color = HasanColors.TextSecondary,
                fontSize = HasanDimens.TextTitleMedium,
                modifier = Modifier.clickable(onClick = callbacks.onBack).padding(end = HasanDimens.SpacingM)
            )
            Text(
                text = skillName,
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = HasanDimens.TextHeading
            )
        }

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HasanColors.Accent)
                }
            }
            detail == null -> {
                Box(modifier = Modifier.fillMaxSize().padding(HasanDimens.SpacingXxl), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Skill introuvable ou indisponible sur cette plateforme",
                        color = HasanColors.TextMutedA11y,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = HasanDimens.SpacingL)) {
                    if (detail.tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = HasanDimens.SpacingM)
                        ) {
                            detail.tags.forEach { tag -> TagPill(text = tag) }
                        }
                    }
                    MarkdownText(
                        text = stripFrontmatter(detail.content),
                        selectable = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * GET /api/skills/content renvoie le SKILL.md complet, frontmatter YAML
 * inclus (voir api/routes.py `_skill_view_from_file` — "content": full raw
 * SKILL.md text). name/description/tags sont déjà extraits séparément par
 * le serveur (champs dédiés de la réponse) donc le bloc frontmatter en tête
 * de [content] est redondant pour l'affichage — Markwon le rendrait sinon
 * comme du texte brut continu (bug observé en conditions réelles).
 */
private fun stripFrontmatter(content: String): String {
    val trimmed = content.trimStart()
    if (!trimmed.startsWith("---")) return content
    val closingIndex = trimmed.indexOf("\n---", startIndex = 3)
    if (closingIndex == -1) return content
    val afterClosing = trimmed.indexOf('\n', closingIndex + 1)
    return if (afterClosing == -1) "" else trimmed.substring(afterClosing + 1).trimStart('\n')
}
