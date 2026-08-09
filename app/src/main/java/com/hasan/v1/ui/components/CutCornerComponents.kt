package com.hasan.v1.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono

/**
 * Panel générique à coin coupé — équivalent Compose de .clip-panel / .settings-panel /
 * .activity-row du mockup (docs/design/hasan-mockup-v2.html).
 */
@Composable
fun CutCornerPanel(
    modifier: Modifier = Modifier,
    shape: Shape = HasanShapes.panel(),
    backgroundColor: Color = HasanColors.BgSurface,
    borderColor: Color = HasanColors.Border,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(HasanDimens.BorderWidth, borderColor, shape)
    ) {
        content()
    }
}

/** Icon-btn / fh-btn — bouton carré ou compact à coin coupé, fond neutre. */
@Composable
fun CutCornerIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = HasanColors.BgSurface,
    borderColor: Color = HasanColors.Border,
    contentColor: Color = HasanColors.TextSecondary,
    content: @Composable () -> Unit
) {
    val shape = HasanShapes.panelSmall()
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(HasanDimens.BorderWidth, borderColor, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
            content = content
        )
    }
}

/** icon-btn.send — même forme, palette accent (fond accent-glow-bg, bordure accent-dim, icône accent). */
@Composable
fun AccentIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    CutCornerIconButton(
        onClick = onClick,
        modifier = modifier,
        backgroundColor = HasanColors.AccentGlowBg,
        borderColor = HasanColors.AccentDim,
        contentColor = HasanColors.Accent,
        content = content
    )
}

/** tag-pill — CRON/PUSH/AUTH badge coloré, texte mono. */
@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = HasanColors.AccentDim,
    contentColor: Color = HasanColors.Accent
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextLabelSmall,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Toggle custom — rail sombre + knob glissant, accent rouge actif. Ne réutilise pas
 * Switch Material par défaut (le mockup a un style rail/knob non standard, voir .toggle
 * / .toggle-knob dans hasan-mockup-v2.html).
 */
@Composable
fun HasanToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // .switch du mockup (ligne 359-368) — 48×26px, bordure 1.5px, AUCUN border-radius
    // (rectangles nets, pas arrondis), thumb 19×19px, offset 2px→21px.
    val railColor = if (checked) HasanColors.AccentDim else HasanColors.BgSurface3
    val borderColor = if (checked) HasanColors.Accent else HasanColors.BorderStrong
    val knobColor = if (checked) HasanColors.Accent else HasanColors.TextMutedA11y
    // .switch::after du mockup : left:2px (position de base, état off) + .switch.on::after
    // { transform:translateX(21px) } — le translateX s'AJOUTE au left:2px existant, la
    // position finale en "on" est donc 2+21=23px depuis le padding-box, pas 21px absolu
    // (bug précédent : utiliser 21.dp seul cassait la symétrie des marges gauche/droite
    // entre les deux états). +1.5dp supplémentaire car left/top sont mesurés depuis
    // l'intérieur de la bordure du rail (border-box), pas depuis le bord du Box Compose.
    val knobOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) 2.dp + 21.dp + 1.5.dp else 2.dp + 1.5.dp,
        label = "toggle-knob-offset"
    )

    Box(
        modifier = modifier
            .size(width = 48.dp, height = 26.dp)
            .background(railColor)
            .border(1.5.dp, borderColor)
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .padding(start = knobOffset, top = 2.dp + 1.5.dp)
                .size(19.dp)
                .background(knobColor)
        )
    }
}

/**
 * 7 puces coin-coupé (L M M J V S D) — sélection simple, un seul jour actif à
 * la fois (voir section 5 : "actuellement le mockup ne permet qu'un seul jour
 * sélectionné" — pas de multi-jour pour cette version). [selectedIndex] est
 * un index 0=lundi..6=dimanche (ISO-8601, cohérent avec Calendar.DAY_OF_WEEK
 * converti par l'appelant, PAS l'index Calendar brut qui démarre à dimanche=1).
 */
@Composable
fun DayChipRow(
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val labels = listOf("L", "M", "M", "J", "V", "S", "D")
    val fullNames = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingXs)
    ) {
        // .day-chip du mockup (ligne 608-622) — PAS de bordure (technique cadre+remplissage :
        // fond border-subtle = cadre 1px, ::before bg-surface = remplissage), actif = fond
        // accent-deep PLEIN (pas translucide comme .freq-tab), texte blanc.
        labels.forEachIndexed { index, label ->
            val isSelected = selectedIndex == index
            val shape = HasanShapes.panelSmall(cut = 5.dp)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(shape)
                    .background(HasanColors.Border)
                    .padding(HasanDimens.BorderWidth)
                    .clip(shape)
                    .background(if (isSelected) HasanColors.Accent else HasanColors.BgSurface)
                    .semantics {
                        this.role = Role.RadioButton
                        this.selected = isSelected
                        contentDescription = fullNames[index]
                    }
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.White else HasanColors.TextSecondary,
                    fontFamily = IBMPlexMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** Un onglet de fréquence — libellé + valeur associée. */
data class FreqTabOption<T>(val value: T, val label: String)

/**
 * 4 onglets coin-coupé (Une fois / Toutes les X / Quotidien / Hebdo) —
 * sélecteur de fréquence de l'éditeur de tâche, section 5 du brief. Générique
 * sur T pour rester réutilisable sans dépendre du type d'énum de l'appelant.
 */
@Composable
fun <T> FreqTabRow(
    options: List<FreqTabOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingXs)
    ) {
        // .freq-tab du mockup (ligne 591-605) — PAS de bordure (cadre+remplissage : fond
        // border-subtle = cadre 1px, ::before bg-surface = remplissage), actif = fond
        // accent-soft translucide (AccentDim), texte accent-strong.
        options.forEach { option ->
            val isSelected = option.value == selected
            val shape = HasanShapes.panelSmall(cut = 6.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = HasanDimens.TouchTarget)
                    .clip(shape)
                    .background(HasanColors.Border)
                    .padding(HasanDimens.BorderWidth)
                    .clip(shape)
                    .background(if (isSelected) HasanColors.AccentDim else HasanColors.BgSurface)
                    .semantics {
                        this.role = Role.Tab
                        this.selected = isSelected
                    }
                    .clickable { onSelect(option.value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option.label,
                    color = if (isSelected) HasanColors.AccentStrong else HasanColors.TextSecondary,
                    fontFamily = IBMPlexMono,
                    fontSize = HasanDimens.TextLabelMedium,
                    letterSpacing = 0.5.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = HasanDimens.SpacingXs, vertical = HasanDimens.SpacingS)
                )
            }
        }
    }
}

/**
 * .icon-btn "rafraîchir" partagé par tous les écrans à liste (Tâches, Mémoire, Kanban,
 * Fichiers, Skills) — rotation tant que [loading] est vrai, feedback visuel de chargement
 * absent auparavant (le bouton restait statique pendant le refresh).
 *
 * PAS de rememberInfiniteTransition ici : couper une boucle infinie à un angle random dès
 * que [loading] repasse à false donnait un arrêt saccadé (pile au milieu d'un tour). Cet
 * Animatable tourne à vitesse constante pendant le chargement, mais quand [loading] devient
 * false, termine le tour en cours jusqu'au prochain multiple de 360° avec un easing de
 * décélération — l'icône revient toujours à sa position de repos, jamais figée à mi-course.
 */
@Composable
fun RefreshIconButton(
    loading: Boolean,
    onClick: () -> Unit,
    contentDescription: String = "Rafraîchir la liste",
    modifier: Modifier = Modifier,
    tint: Color = HasanColors.TextPrimary
) {
    val rotation = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(loading) {
        if (loading) {
            // Tour complet en 1.4s, vitesse constante — assez lent pour rester lisible/smooth
            // (800ms précédent donnait une impression de saccade), tant que le chargement dure.
            while (true) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing)
                )
            }
        } else {
            // Termine le tour en cours plutôt que de s'arrêter net à l'angle courant —
            // décélération douce jusqu'au prochain multiple de 360° (position de repos).
            val remaining = 360f - (rotation.value % 360f)
            if (remaining > 0f && remaining < 360f) {
                rotation.animateTo(
                    targetValue = rotation.value + remaining,
                    animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                )
            }
        }
    }
    Box(
        modifier = modifier
            .size(HasanDimens.TouchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_refresh),
            contentDescription = contentDescription,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = Modifier
                .size(HasanDimens.IconMedium)
                .rotate(rotation.value % 360f)
        )
    }
}
