package com.hasan.v1.ui.screens

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono

/**
 * Mode mains libres — overlay plein écran (mockup #freehand-overlay). Transpose la
 * logique déjà validée de LightModeFragment (mute/unmute avec coupure TTS, rendu
 * d'état vocal, vibrations) sans la modifier — voir FreeHandUiState pour le contrat
 * exact attendu par ConversationFragment.
 */
data class FreeHandUiState(
    val statusText: String,
    val lastMessage: String,
    val isListening: Boolean,
    val isMuted: Boolean
)

/** Nombre max de lignes du transcript vocal affichées en pleine opacité (préférence utilisateur). */
private const val TRANSCRIPT_MAX_LINES = 12

/**
 * Estompe le bas du composable (alpha 1 → 0) — signale qu'un contenu déborde sans le
 * couper brutalement.
 *
 * [fadeHeightPx] doit correspondre à la marge basse vide réservée dans le TextView
 * (TRANSCRIPT_BOTTOM_FADE) : le dégradé court alors exactement sur ce vide et laisse
 * intacte la dernière ligne de texte. Deux approches antérieures échouaient là-dessus —
 * une fraction de la hauteur du bloc (zone estompée proportionnelle au texte, donc de
 * plus en plus mordante), puis un multiple de hauteur de ligne supposée (le rendu
 * markdown ne garantit pas des lignes d'égale hauteur : un titre est plus haut).
 *
 * Nécessite CompositingStrategy.Offscreen : le BlendMode.DstIn s'applique à la couche
 * déjà composée (le TextView interop inclus), pas au fond de l'écran derrière.
 */
private fun Modifier.fadeOutBottom(fadeHeightPx: Float): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                // coerceAtLeast(0f) : sur un bloc plus court que la zone de fondu, sans
                // garde startY passerait au-dessus du composable et le dégradé
                // effacerait du texte visible.
                startY = (size.height - fadeHeightPx).coerceAtLeast(0f),
                endY = size.height,
                colors = listOf(Color.Black, Color.Transparent)
            ),
            blendMode = BlendMode.DstIn
        )
    }

/**
 * Largeur max du transcript — calibrée pour ~40 caractères par ligne à 20sp en IBM Plex
 * Sans : la largeur moyenne d'un glyphe y vaut ≈ 0,5 em, soit ≈ 10sp, d'où 40 × 10 = 400dp
 * théoriques. Plafonné à 340dp car l'écran fait 360-410dp de large moins les marges
 * latérales (SpacingXl × 2) : au-delà, c'est la largeur d'écran qui contraindrait le retour
 * à la ligne, pas cette valeur, et le rendu varierait d'un device à l'autre.
 */
private val TRANSCRIPT_MAX_WIDTH = 340.dp

/**
 * Marge basse vide réservée SOUS la dernière ligne du transcript, à l'intérieur du
 * TextView, pour que le dégradé d'estompage y retombe entièrement (voir [fadeOutBottom])
 * au lieu de recouvrir du texte lisible.
 *
 * Première approche essayée puis abandonnée : ajouter une ligne vide à la fin du texte
 * markdown. Markwon la rendait à hauteur réduite (~40px mesurés sur device au lieu d'une
 * ligne pleine), le dégradé mordait donc toujours sur la dernière ligne réelle. Une marge
 * en pixels est déterministe : elle ne dépend ni du rendu markdown, ni de la hauteur de
 * la dernière ligne (un titre est plus haut qu'une ligne de corps de texte).
 */
private val TRANSCRIPT_BOTTOM_FADE = 44.dp

@Composable
fun FreeHandScreen(
    state: FreeHandUiState,
    onExit: () -> Unit,
    onToggleMute: () -> Unit,
    onMicClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HasanColors.BgBase)
            .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingXxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Transcript AU-DESSUS du micro (et non en dessous), micro poussé vers le bas de
        // l'écran : préférence utilisateur — le bouton doit rester atteignable au pouce
        // lors d'un usage à une main. Le weight(2f)/weight(1f) place le bloc micro dans le
        // tiers bas plutôt qu'au centre vertical.
        Box(
            modifier = Modifier.weight(2f).fillMaxWidth(),
            // Center : le texte est centré dans l'espace disponible au-dessus du micro,
            // le vide restant se répartissant également au-dessus et en dessous du bloc.
            // Étapes précédentes : BottomCenter collait le texte au micro (tout le vide
            // en haut), TopCenter le collait en haut (tout le vide juste au-dessus du
            // micro) — les deux déséquilibrés. Le décalage vertical selon la longueur du
            // message qu'on cherchait à éviter avec TopCenter est devenu négligeable
            // depuis le passage de 6 à 12 lignes : le bloc remplit l'essentiel de la zone,
            // il reste peu de marge à répartir.
            contentAlignment = Alignment.Center
        ) {
            if (state.lastMessage.isNotBlank()) {
                // .voice-transcript du mockup (ligne 658) — font-size:20px, text-secondary,
                // centré. Rendu en markdown (même moteur Markwon que les bulles de chat)
                // plutôt qu'en texte brut : les réponses d'Hermes contiennent du gras, des
                // listes, du code inline qui s'affichaient jusqu'ici avec leurs marqueurs
                // bruts (**, `, -) en plein écran vocal.
                //
                // Débordement : au lieu de couper net, on applique un dégradé d'alpha sur
                // la marge basse réservée (TRANSCRIPT_BOTTOM_FADE) — signal visuel qu'il
                // reste du texte, sans le "…" sec d'une troncature classique et sans
                // rendre illisible la dernière ligne affichée.
                val fadeHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                    TRANSCRIPT_BOTTOM_FADE.toPx()
                }
                com.hasan.v1.ui.components.MarkdownText(
                    text = state.lastMessage,
                    selectable = false,
                    textSizeSp = 20f,
                    textColor = HasanColors.TextSecondary,
                    centered = true,
                    maxLines = TRANSCRIPT_MAX_LINES,
                    ellipsize = false,
                    bottomPaddingPx = fadeHeightPx.toInt(),
                    modifier = Modifier
                        .widthIn(max = TRANSCRIPT_MAX_WIDTH)
                        .fadeOutBottom(fadeHeightPx)
                )
            }
        }

        // Bloc micro volontairement aligné en haut de son weight(1f) (pas centré) : le
        // mou de cette zone reste donc sous le texte de statut, ce qui maintient le micro
        // haut dans le tiers bas. Le centrer descendait le bouton d'environ 70px, contre
        // l'exigence d'accessibilité au pouce en usage à une main.
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FreeHandMic(
                isListening = state.isListening,
                isMuted = state.isMuted,
                onClick = onMicClick
            )
            Text(
                text = state.statusText,
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextCaption,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = HasanDimens.SpacingL)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Couper le son à gauche, Quitter à droite — section 11 du brief
            // next_update/PROMPT_CLAUDE_CODE.md (ordre inversé par rapport à avant).
            FreeHandTextButton(
                label = if (state.isMuted) {
                    stringResource(R.string.light_mode_muted)
                } else {
                    stringResource(R.string.light_mode_mute)
                },
                muted = state.isMuted,
                onClick = onToggleMute
            )
            FreeHandTextButton(
                label = stringResource(R.string.light_mode_exit_short),
                onClick = onExit
            )
        }
    }
}

// .btn.btn-ghost du mockup (ligne 1116-1117, règle universelle .btn ligne 321-324) —
// Chakra Petch capitales, letter-spacing, min-height 44px, largeur intrinsèque (PAS
// pleine largeur : les deux boutons cohabitent dans une Row SpaceBetween). Remplace
// l'ancien FreeHandTextButton (chip IBM Plex Mono minuscule, hors DA — c'était un
// résidu de l'ancien mockup jamais migré).
@Composable
private fun FreeHandTextButton(label: String, muted: Boolean = false, onClick: () -> Unit) {
    val backgroundColor = if (muted) HasanColors.AccentGlowBg else HasanColors.BgBase
    val borderColor = if (muted) HasanColors.AccentDim else HasanColors.BorderStrong
    val contentColor = if (muted) HasanColors.Accent else HasanColors.TextPrimary
    val shape = HasanShapes.panelSmall()

    Box(
        modifier = Modifier
            .heightIn(min = HasanDimens.TouchTarget)
            .clip(shape)
            .background(backgroundColor)
            .border(1.5.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingM),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.uppercase(),
            color = contentColor,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = HasanDimens.TextSubtitle,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun FreeHandMic(isListening: Boolean, isMuted: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "fh-mic-ring")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "fh-mic-ring-scale"
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "fh-mic-ring-alpha"
    )

    val micColor = if (isMuted) HasanColors.BgSurface2 else HasanColors.Accent
    // Coin coupé HasanShapes.panel (DA actuelle du rework, mockup ligne 121-126 .cut) —
    // remplace diagonalLarge (polygon asymétrique 20%, résidu de l'ancien mockup
    // hasan-mockup-v2.html jamais migré ici). Le cercle plein plat du mockup rework
    // (96dp sans anneau animé) est volontairement ignoré par préférence utilisateur —
    // seule la forme (cut-corner) est reprise, pas la taille/le style du bouton lui-même.
    // Taille FIXE du conteneur (= celle de l'anneau au repos, le plus grand des deux
    // enfants) plutôt qu'un wrap_content : sinon la Box se mesurait à 128dp hors écoute
    // et à 148dp en écoute, et le micro se décalait vers le bas au moment où isListening
    // passait à true — soit ~1s après le tap, le temps que le moteur STT démarre
    // réellement. L'anneau reste toujours composé (alpha 0 quand inactif) pour la même
    // raison : le retirer/remettre dans l'arbre relance une passe de layout.
    Box(
        modifier = Modifier.size(148.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .scale(ringScale)
                // L'anneau déborde volontairement de son conteneur quand il grossit
                // (scale jusqu'à 1.35) : ce dépassement est purement visuel et ne
                // participe pas à la mesure, la position du micro reste donc figée.
                .alpha(if (!isMuted && isListening) ringAlpha else 0f)
                .clip(HasanShapes.panel(28.dp))
                .background(HasanColors.Accent)
        )
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(HasanShapes.panel(24.dp))
                .background(micColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_mic),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (isMuted) HasanColors.TextMutedA11y else HasanColors.BgBase),
                // 56dp dans un bouton de 128dp (≈44%) : à 34dp l'icône n'occupait qu'un
                // quart du bouton et paraissait perdue au milieu de l'aplat rouge.
                modifier = Modifier.size(56.dp)
            )
        }
    }
}
