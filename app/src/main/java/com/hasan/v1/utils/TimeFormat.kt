package com.hasan.v1.utils

/**
 * Formatage du temps écoulé depuis la dernière activité d'une session Hermes,
 * affiché dans le drawer à droite du nom de session (voir HasanDrawer.kt,
 * DrawerSessionRow).
 *
 * Échelle de granularité volontairement grossière (paliers fixes, pas de
 * pluralisation ni de calendrier calendaire) — cohérent avec les utilitaires
 * "relative time" déjà présents dans le projet (ActivityScreen.kt,
 * KanbanScreen.kt) mais avec des paliers plus larges car une session peut
 * rester inactive des semaines/mois contrairement à une tâche ou un log
 * d'activité récent.
 */
object TimeFormat {

    private const val MS_PER_HOUR = 3_600_000L
    private const val MS_PER_DAY = 24 * MS_PER_HOUR
    private const val MS_PER_WEEK = 7 * MS_PER_DAY
    private const val MS_PER_MONTH = 30 * MS_PER_DAY
    private const val MS_PER_YEAR = 365 * MS_PER_DAY

    /**
     * Âge relatif d'une session depuis [lastMessageAt] (HermesSession.updatedAt,
     * mis à jour à chaque message — voir SessionDao.touchSession) jusqu'à [now].
     *
     * Paliers :
     *  - < 1h                  -> "-1h"
     *  - 1h .. 23h              -> "+1h".."+23h"
     *  - 1j .. 6j               -> "+1d".."+6d"
     *  - 1sem .. 4sem           -> "+1w".."+4w"
     *  - 1mois .. 11mois        -> "+1m".."+11m"
     *  - >= 1an                 -> "+1y", "+2y", ... (pas de plafond, simple division entière)
     *
     * Approximation simple : mois = 30 jours, année = 365 jours (pas de
     * java.time calendaire) — suffisant pour un affichage indicatif dans le drawer.
     */
    fun formatRelativeSessionAge(lastMessageAt: Long, now: Long = System.currentTimeMillis()): String {
        val deltaMs = (now - lastMessageAt).coerceAtLeast(0L)

        return when {
            deltaMs < MS_PER_HOUR -> "-1h"
            deltaMs < MS_PER_DAY -> "+${deltaMs / MS_PER_HOUR}h"
            deltaMs < MS_PER_WEEK -> "+${deltaMs / MS_PER_DAY}d"
            deltaMs < MS_PER_MONTH -> "+${deltaMs / MS_PER_WEEK}w"
            deltaMs < MS_PER_YEAR -> "+${deltaMs / MS_PER_MONTH}m"
            else -> "+${deltaMs / MS_PER_YEAR}y"
        }
    }
}
