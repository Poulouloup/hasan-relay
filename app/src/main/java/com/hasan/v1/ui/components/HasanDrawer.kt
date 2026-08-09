package com.hasan.v1.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.ui.screens.CutCornerOutlineButton
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.IBMPlexMono
import com.hasan.v1.ui.theme.IBMPlexSans
import com.hasan.v1.utils.TimeFormat
import kotlinx.coroutines.launch

enum class HasanNavTab { CHAT, TASKS, KANBAN, MEMORY, TOOLS, SETTINGS }

data class HasanNavItem(val tab: HasanNavTab, val iconRes: Int, val label: String)

/**
 * Header minimal (hamburger + titre optionnel) pour les écrans qui n'ont pas de
 * HasanHeader complet (Tâches, Kanban, Mémoire, Tools, Paramètres...) — le drawer
 * doit rester accessible depuis tous les écrans, pas seulement Chat, sinon impasse
 * de navigation une fois sur un autre onglet.
 *
 * [title], quand fourni, est affiché à droite du hamburger sur la même ligne (même
 * emplacement que "HASAN" dans HasanHeader côté Chat) — préférence explicite de
 * l'utilisateur sur le placement, sauf conflit avec la découpe caméra (punch-hole,
 * centrée horizontalement à mi-écran) documenté dans
 * archive/2026-07-23-audit-boutons-masque-punch-hole-pixel10.md : un titre trop long
 * pour tenir dans l'espace hamburger→centre-écran doit rester sur sa propre ligne via
 * [ScreenTitle] à la place (cas réel : "Tools & Permissions").
 */
@Composable
fun HasanMinimalHeader(
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    trailingContent: @Composable () -> Unit = {}
) {
    // Fond BgHeader — le padding d'insets status bar est appliqué au niveau du ComposeView
    // racine (MainActivity), pas ici : voir HasanHeader.kt pour l'explication complète
    // (WindowInsets.statusBars ne traverse pas fiablement plusieurs ComposeView imbriqués).
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(HasanColors.BgHeader)
            // border-bottom du mockup (.app-header, ligne 260 : 1px solid border-subtle) —
            // voir HasanHeader.kt pour le même correctif côté Chat.
            .drawBehind {
                drawLine(
                    color = HasanColors.Border,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = HasanDimens.BorderWidth.toPx()
                )
            }
            .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HasanIconButton(
            iconRes = R.drawable.ic_menu_hamburger,
            contentDescription = "Menu",
            onClick = onMenuClick
        )
        if (title != null) {
            Text(
                text = title,
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = HasanDimens.TextTitleMedium,
                modifier = Modifier.weight(1f).padding(start = HasanDimens.SpacingM)
            )
        }
        trailingContent()
    }
}

/** Session affichée dans le drawer — label déjà formaté par l'appelant ("01. Monte-Cristo"). */
data class DrawerSessionItem(
    val id: String,
    val label: String,
    val isActive: Boolean,
    /** HermesSession.updatedAt — dernière activité, pour l'affichage "-1h"/"+3d"/... à droite du label. */
    val lastMessageAt: Long
)

data class DrawerUiState(
    val navItems: List<HasanNavItem>,
    val selectedTab: HasanNavTab,
    val sessions: List<DrawerSessionItem>
)

class DrawerCallbacks(
    val onNavItemClick: (HasanNavTab) -> Unit,
    val onSessionClick: (String) -> Unit,
    val onSessionRename: (String) -> Unit,
    val onSessionDelete: (String) -> Unit,
    val onNewSession: () -> Unit,
    val onQuit: () -> Unit,
    val onClose: () -> Unit
)

/**
 * Englobe tout [content] dans un [ModalNavigationDrawer] Material3 — ouverture par
 * swipe ou via [drawerState] piloté par l'appelant (MainActivity.openDrawer()).
 *
 * Le geste de swipe natif de [ModalNavigationDrawer] réagit sur toute la largeur de
 * l'écran, ce qui entre en conflit avec le scroll horizontal du contenu (ex: liste de
 * messages) côté droit lorsqu'il est FERMÉ — remplacé dans ce cas par un détecteur de
 * drag manuel scopé à la moitié gauche de l'écran uniquement (zone où l'utilisateur ne
 * scrolle quasiment jamais), voir [leftHalfSwipeToOpen]. Une fois OUVERT, le geste natif
 * est réactivé (gesturesEnabled = drawerState.isOpen) : fermer en swipant vers la gauche
 * depuis le panneau lui-même ne présente aucun conflit avec un scroll de contenu (le
 * contenu de fond n'est plus interactif tant que le drawer est ouvert).
 *
 * Accessible depuis les écrans principaux (Chat via HasanHeader, Tools/Paramètres/etc.
 * via HasanMinimalHeader) — sinon impasse de navigation une fois sorti de Chat, aucun
 * moyen d'y revenir. Englobé au niveau MainActivity plutôt que par fragment car
 * "Drawer latéral géré par MainActivity, pas par les fragments" (.claude/rules/architecture.md).
 */
@Composable
fun HasanDrawerScaffold(
    state: DrawerUiState,
    callbacks: DrawerCallbacks,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = HasanColors.BgBase) {
                HasanDrawerContent(state = state, callbacks = callbacks)
            }
        }
    ) {
        Box(modifier = Modifier.leftHalfSwipeToOpen(drawerState, scope)) {
            content()
        }
    }
}

/**
 * Ouvre le drawer sur un drag horizontal vers la droite démarré dans la moitié
 * gauche de l'écran — évite le conflit avec le scroll horizontal du contenu
 * (ex: bulles de code, liste de messages) qui se produit surtout côté droit.
 * Un drag qui démarre dans la moitié droite, ou qui va vers la gauche, est
 * ignoré (laissé au contenu en dessous, `awaitFirstDown(requireUnconsumed = false)`
 * ne bloque rien tant que le seuil horizontal n'est pas dépassé).
 */
private fun Modifier.leftHalfSwipeToOpen(drawerState: DrawerState, scope: kotlinx.coroutines.CoroutineScope): Modifier =
    this.pointerInput(drawerState) {
        val dragThreshold = 24.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (drawerState.isOpen || down.position.x > size.width / 2f) return@awaitEachGesture

            var dragged = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val deltaX = change.position.x - down.position.x
                val deltaY = change.position.y - down.position.y
                if (!dragged) {
                    if (deltaX > dragThreshold && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.5f) {
                        dragged = true
                        change.consume()
                        scope.launch { drawerState.open() }
                    } else if (kotlin.math.abs(deltaY) > dragThreshold) {
                        break // scroll vertical — on laisse la main au contenu
                    }
                } else {
                    change.consume()
                }
            }
        }
    }

@Composable
fun HasanDrawerContent(
    state: DrawerUiState,
    callbacks: DrawerCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HasanColors.BgBase)
    ) {
        Column(modifier = Modifier.padding(top = HasanDimens.SpacingXl, bottom = HasanDimens.SpacingM)) {
            DrawerHeader(onClose = callbacks.onClose)
        }
        // .drawer-header { border-bottom } du mockup (ligne 471) — séparateur absent avant
        // cette correction entre le header et la section NAVIGATION.
        Box(modifier = Modifier.fillMaxWidth().height(HasanDimens.BorderWidth).background(HasanColors.Border))

        Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
        DrawerSectionTitle("NAVIGATION")
        Column {
            state.navItems.forEach { item ->
                DrawerNavRow(
                    item = item,
                    isActive = item.tab == state.selectedTab,
                    onClick = { callbacks.onNavItemClick(item.tab) }
                )
            }
        }

        Spacer(modifier = Modifier.height(HasanDimens.SpacingXxl))
        DrawerSectionTitle("SESSIONS ACTIVES")
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.sessions, key = { it.id }) { session ->
                DrawerSessionRow(
                    session = session,
                    onClick = { callbacks.onSessionClick(session.id) },
                    onRename = { callbacks.onSessionRename(session.id) },
                    onDelete = { callbacks.onSessionDelete(session.id) }
                )
            }
        }

        Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
        Box(modifier = Modifier.fillMaxWidth().height(HasanDimens.BorderWidth).background(HasanColors.Border).padding(horizontal = HasanDimens.SpacingXl))
        Spacer(modifier = Modifier.height(HasanDimens.SpacingM))

        CutCornerOutlineButton(
            text = "+ Nouvelle Session",
            onClick = callbacks.onNewSession,
            modifier = Modifier.padding(horizontal = HasanDimens.SpacingXl)
        )
        Spacer(modifier = Modifier.height(HasanDimens.SpacingS))
        DrawerQuitRow(onClick = callbacks.onQuit)
        Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
    }
}

@Composable
private fun DrawerHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HasanDimens.SpacingXl),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // logo-badge + "HASAN" du mockup (drawer-header, ligne 1137-1138) — logo absent avant
        // cette correction (seul le wordmark texte était affiché).
        // Logo + wordmark ~20% plus grands que le header Chat (HasanHeader.kt) — demande
        // explicite utilisateur, spécifique au drawer (BrandMark/TextDisplaySmall par défaut
        // restent inchangés ailleurs, pas de régression sur l'écran Chat déjà validé).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)) {
            BrandMark(size = 38.dp)
            Text(
                text = "HASAN",
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                letterSpacing = 2.sp
            )
        }
        HasanIconButton(
            iconRes = R.drawable.ic_close,
            contentDescription = "Fermer",
            onClick = onClose,
            tint = HasanColors.TextMutedA11y
        )
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    // .nav-group-label du mockup (ligne 158) — font-size:11px, letter-spacing:.09em (~1sp à 11px).
    Text(
        text = text,
        color = HasanColors.TextMutedA11y,
        fontFamily = IBMPlexMono,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingS)
    )
}

@Composable
private fun DrawerNavRow(item: HasanNavItem, isActive: Boolean, onClick: () -> Unit) {
    val contentColor = if (isActive) HasanColors.Accent else HasanColors.TextPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // heightIn(min = TouchTarget) avant clickable/padding : zone tactile mesurée
            // à ~40dp (16dp icône + 2×12dp padding vertical), sous le seuil 48dp — voir
            // archive/2026-07-23-audit-boutons-masque-punch-hole-pixel10.md.
            .heightIn(min = HasanDimens.TouchTarget)
            .background(if (isActive) HasanColors.AccentDim else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(HasanDimens.IconMedium)
                .background(if (isActive) HasanColors.Accent else androidx.compose.ui.graphics.Color.Transparent)
        )
        Spacer(modifier = Modifier.width(HasanDimens.SpacingM))
        // .icon universel du mockup (ligne 138) — 20px, pas 16dp (IconSmall trop petit).
        Image(
            painter = painterResource(item.iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(contentColor),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(HasanDimens.SpacingM))
        // .drawer-nav-item du mockup (ligne 474-479) — pas de font-family propre, hérite du
        // body (--font-body: Inter == IBMPlexSans ici), PAS IBMPlexMono ; font-size:14.5px ;
        // .active { font-weight:600 } (sinon 500 par défaut sur .nav-btn, mais drawer-nav-item
        // n'a pas de weight par défaut explicite — Normal convient hors état actif).
        Text(
            text = item.label,
            color = contentColor,
            fontFamily = IBMPlexSans,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 14.5.sp
        )
    }
}

/** Appui long ouvre un menu Renommer/Supprimer — même geste que l'ancien SessionRow de SettingsScreen.kt. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    session: DrawerSessionItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(session.id) { mutableStateOf(false) }
    val contentColor = if (session.isActive) HasanColors.Accent else HasanColors.TextPrimary
    Box {
        // session-row du mockup (ligne 1463-1465) — point (sdot) AVANT le nom, pas après à
        // droite ; pas de point du tout si la session n'est pas active (auparavant affiché
        // systématiquement à droite pour l'état actif, position et emplacement inversés).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingS),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (session.isActive) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(HasanColors.Accent)
                    )
                    Spacer(modifier = Modifier.width(HasanDimens.SpacingS))
                }
                // .session-row .sname du mockup (ligne 488) — font-size:13px, pas TextBodyMedium (12sp).
                Text(
                    text = session.label,
                    color = contentColor,
                    fontFamily = IBMPlexMono,
                    fontSize = 13.sp
                )
            }
            // .session-row .sage du mockup (ligne 490) — font-size:11px == TextCaption, inchangé.
            Text(
                text = TimeFormat.formatRelativeSessionAge(session.lastMessageAt),
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextCaption,
                modifier = Modifier.padding(start = HasanDimens.SpacingS)
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Renommer") },
                onClick = { menuExpanded = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text("Supprimer") },
                onClick = { menuExpanded = false; onDelete() }
            )
        }
    }
}

// "Quitter l'app" du mockup (ligne 1157) — bouton pleine largeur SANS icône (juste le texte,
// contrairement à la version précédente qui affichait une icône logout à gauche).
@Composable
private fun DrawerQuitRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HasanDimens.TouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = HasanDimens.SpacingXl, vertical = HasanDimens.SpacingM),
        contentAlignment = Alignment.Center
    ) {
        // .btn du mockup (ligne 1157) — même règle universelle que CutCornerOutlineButton :
        // Chakra Petch, capitales, letter-spacing (pas IBM Plex Mono en casse normale).
        Text(
            text = "QUITTER L'APP",
            color = HasanColors.TextMutedA11y,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = HasanDimens.TextSubtitle,
            letterSpacing = 0.8.sp
        )
    }
}
