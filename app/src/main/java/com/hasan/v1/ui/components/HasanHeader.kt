package com.hasan.v1.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono

/**
 * État d'un badge de connexion affiché dans le header. Deux instances
 * distinctes cohabitent — une pour hermes-webui (transport du chat, dérivée
 * de `serverConnected && webUiLoggedIn`), une pour le relay bridge (SMS,
 * localisation, dérivée de [com.hasan.v1.network.RelayConnectionStatus]) —
 * voir ConversationFragment.updateConnectionBadges(). Les deux systèmes sont
 * indépendants depuis la migration webui, d'où la séparation visuelle (cf.
 * WebUiConnectionSection/RelayBridgeSection dans SettingsScreen.kt).
 */
data class ConnectionBadgeState(
    val connected: Boolean,
    /** ex: "HERMES · CONNECTÉ" — déjà formaté par l'appelant, ce composant ne fait pas de logique métier. */
    val readout: String
)

/** Header — hamburger (ouvre le drawer), logo à coin diagonal, wordmark HASAN, badges de
 * connexion (hermes-webui + relay bridge) avec point pulsant. Voir .header dans hasan-mockup-v2.html. */
/**
 * [onFilesClick] : bouton "Fichiers" du header, quand non-null — dans le mockup
 * (update/hasan-rework-mockup.html ligne 751-758) il vit entre le titre "Hasan" et
 * les conn-pills, PAS en overlay flottant par-dessus le contenu (ancien
 * comportement de ChatScreen.kt avant le rework fidèle au mockup). Null sur les
 * écrans qui n'ont pas d'action fichiers associée (aucun aujourd'hui — HasanHeader
 * n'est utilisé que par le Chat — mais gardé optionnel par cohérence avec
 * HasanMinimalHeader qui accepte déjà un title optionnel).
 */
@Composable
fun HasanHeader(
    hermesState: ConnectionBadgeState,
    bridgeState: ConnectionBadgeState,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFilesClick: (() -> Unit)? = null
) {
    // Fond BgHeader — status bar système gardée visible (pas masquée, voir
    // MainActivity.onCreate()). Le padding d'insets status bar N'EST PAS appliqué ici :
    // ce composable vit dans un ComposeView imbriqué (Compose racine → AndroidView →
    // Fragment → ComposeView, voir ConversationFragment.setupComposeChat()) où
    // WindowInsets.statusBars ne se propage pas de façon fiable à travers plusieurs
    // frontières de vues. Le padding est appliqué UNE FOIS au niveau du ComposeView
    // racine (MainActivity.setupDrawerRoot()) à la place — voir HasanStatusBarSpacer.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(HasanColors.BgHeader)
            // border-bottom du mockup (.app-header, ligne 260 : 1px solid border-subtle) —
            // sépare visuellement le header du contenu, absent avant cette correction (bug
            // repéré sur plusieurs onglets, pas seulement Chat).
            .drawBehind {
                drawLine(
                    color = HasanColors.Border,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = HasanDimens.BorderWidth.toPx()
                )
            }
            // vertical = SpacingM/4 (3dp, pas 12dp) : l'espace vide entre le bas de la status
            // bar système et le haut de ce header était encore jugé trop grand après un premier
            // resserrement à 6dp — les insets système réservent déjà l'espace du punch-hole, ce
            // padding ne doit qu'aérer le header lui-même, pas ajouter de marge visible.
            .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingM / 4),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HasanIconButton(
            iconRes = R.drawable.ic_menu_hamburger,
            contentDescription = "Menu",
            onClick = onMenuClick
        )
        Spacer(modifier = Modifier.width(HasanDimens.SpacingS))
        BrandMark()
        // "Hasan" en casse mixte (mockup ligne 753 : <h1>Hasan</h1>), PAS "HASAN"
        // tout capitales — le tout-capitales espacé est réservé au wordmark du
        // drawer (DrawerHeader), un contexte différent dans le mockup.
        Text(
            text = "Hasan",
            color = HasanColors.TextPrimary,
            fontFamily = ChakraPetch,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            fontSize = 18.sp,
            modifier = Modifier.weight(1f).padding(start = HasanDimens.SpacingS)
        )
        if (onFilesClick != null) {
            HasanIconButton(
                iconRes = R.drawable.ic_folder,
                contentDescription = "Fichiers",
                onClick = onFilesClick
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingXxs)) {
            ConnectionBadge(hermesState)
            ConnectionBadge(bridgeState)
        }
    }
}

/**
 * Bouton icône à zone tactile 48dp (norme Material) — icône visuelle 24dp
 * centrée. Remplace les Image+clickable bruts dupliqués précédemment dans
 * HasanHeader (hamburger 22dp) et HasanDrawer (hamburger + close, 22dp/20dp),
 * qui n'offraient pas de marge tactile suffisante (zone cliquable = taille
 * exacte de l'icône, aucune marge d'erreur au toucher).
 */
@Composable
fun HasanIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = HasanColors.TextPrimary
) {
    Box(
        modifier = modifier
            .size(HasanDimens.TouchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(HasanDimens.IconMedium)
        )
    }
}

/**
 * Titre d'écran affiché sous [HasanMinimalHeader] — pattern partagé entre les
 * onglets qui n'avaient qu'un sous-header contextuel (compteur, board actif)
 * sans nom d'écran littéral (audit 4-volets finding #8, étendu à
 * Tâches/Kanban/Mémoire après retour utilisateur).
 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = HasanColors.TextPrimary,
        fontFamily = ChakraPetch,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = HasanDimens.TextTitleMedium,
        modifier = modifier.padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingXs)
    )
}

/**
 * logo-badge du mockup (ligne 661-671, .cut-sm = notch 6px) — cadre coin-coupé
 * fixe (PAS la forme diagonale asymétrique utilisée avant), cadre = Border
 * (gris, PAS l'accent rouge), remplissage = fond sombre + logo. La forme
 * arrondie visible sur la capture vient du PNG logo lui-même (cercle dessiné
 * dans l'asset), pas d'un clip Compose supplémentaire.
 */
@Composable
fun BrandMark(size: Dp = 32.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(HasanShapes.panelSmall())
            .background(HasanColors.Border)
            .padding(1.dp)
            .clip(HasanShapes.panelSmall())
            .background(Color(0xFF0A0A0C)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.hasan_logo_transparent),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(0.92f)
        )
    }
}

@Composable
private fun ConnectionBadge(state: ConnectionBadgeState) {
    val transition = rememberInfiniteTransition(label = "conn-dot-pulse")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = androidx.compose.animation.core.EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "conn-dot-alpha"
    )
    // Point vert (Success) quand connecté — mockup ligne 275 (.dot { background: var(--success) }),
    // PAS l'accent rouge de marque : le rouge est réservé aux commandes/actions, le vert aux
    // statuts positifs (cohérent avec la distinction sémantique du mockup, section 1 du brief).
    val dotColor = if (state.connected) HasanColors.Success else HasanColors.TextMutedA11y

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(5.dp)
                .alpha(if (state.connected) dotAlpha else 1f)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = state.readout,
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextLabelSmall,
            letterSpacing = 0.5.sp
        )
    }
}
