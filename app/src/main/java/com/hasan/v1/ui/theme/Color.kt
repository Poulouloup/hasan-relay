package com.hasan.v1.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens couleur — extraits AU PIXEL PRÈS de update/hasan-rework-mockup.html
 * (:root, lignes 20-53), valeurs re-vérifiées contre des captures Chrome headless du
 * mockup rendu (pas juste lues dans le CSS — le rendu réel fait foi). Ne pas
 * réutiliser les couleurs XML de res/values/colors.xml : cette palette diverge
 * volontairement et les deux systèmes cohabitent le temps de la migration Compose.
 */
object HasanColors {
    val BgBase = Color(0xFF0A0A0D)          // --bg-canvas
    val BgApp = Color(0xFF0D0D10)           // --bg-app
    val BgHeader = Color(0xFF101013)        // --bg-header (panneau opaque, jamais transparent)
    val BgSurface = Color(0xFF16161A)       // --bg-surface
    val BgSurface2 = Color(0xFF1D1D22)      // --bg-surface-2
    val BgSurface3 = Color(0xFF24242B)      // --bg-surface-3
    val Border = Color(0xFF2C2C34)          // --border-subtle
    val BorderStrong = Color(0xFF46464F)    // --border-strong
    val BorderAccent = Color(0xFF7A2530)    // --border-accent

    /** Rouge profond — aplats/boutons pleins. Ne passe PAS le contraste AA en texte fin, voir AccentLight. */
    val Accent = Color(0xFFCC2936)          // --accent-deep

    /** Rouge clair — texte/icônes actifs sur fond sombre (Accent seul est sous le seuil WCAG AA en texte). */
    val AccentLight = Color(0xFFFF4D5E)     // --accent
    val AccentStrong = Color(0xFFFF6B79)    // --accent-strong (hover/focus)
    val AccentDeep2 = Color(0xFFA61F2B)     // --accent-deep-2 (pression / bulle utilisateur)

    /** --accent-soft (rgba(255,77,94,.12)) — opaque, alpha déjà mélangé sur fond header/app pour rester compatible avec les usages existants en fond plein (badges, boutons ghost accent). */
    val AccentDim = Color(0xFF241419)

    /** --accent-soft-2 (rgba(255,77,94,.22)) — idem, alpha déjà mélangé. */
    val AccentGlowBg = Color(0xFF33191E)
    val TextPrimary = Color(0xFFF4F3F0)     // --text-primary (17.8:1)
    val TextSecondary = Color(0xFFB7B6BE)   // --text-secondary (8.9:1)

    /** À utiliser pour tout texte lisible (timestamps, labels mono, hints) — 4.6:1 sur bg-app, conforme WCAG AA. */
    val TextMutedA11y = Color(0xFF85848D)   // --text-tertiary

    /** Décoratif uniquement (bordures fines, séparateurs) — usage large/décoratif, pas de garantie de contraste. */
    val TextMuted = Color(0xFF55545C)       // --text-disabled

    /** Alias explicite du palier "tertiaire" (3 paliers de gris, section 1 du brief) — même valeur que TextMutedA11y. */
    val TextTertiary = TextMutedA11y

    // ─────────────────────────── États sémantiques ──────────────────────────
    // Jamais la seule info transmise — toujours doublés d'un texte/icône (section 13).
    val Success = Color(0xFF3ED98A)
    val SuccessSoft = Color(0x243ED98A)
    val Warning = Color(0xFFFFB648)
    val WarningSoft = Color(0x24FFB648)
    val Info = Color(0xFF5FB9FF)

    /**
     * Couleurs fixes par colonne Kanban (mapping en dur côté app — les
     * colonnes serveur (BOARD_COLUMNS) n'ont ni nom ni couleur personnalisable,
     * voir docs/ARCHITECTURE.md#kanban). Issues du mockup fourni par l'utilisateur
     * (hasan-kanban-mockup-v2-grouped.html) ; KanbanGreen/KanbanPurple du mockup
     * non repris faute de 7e colonne côté serveur à leur associer.
     */
    val KanbanGray = Color(0xFF5C5652)
    val KanbanBlue = Color(0xFF5B8AB0)
    val KanbanGold = Color(0xFFB8862E)
    val KanbanRed = Color(0xFF7A3A3A)
    val KanbanMuted = Color(0xFF3A3632)
}
