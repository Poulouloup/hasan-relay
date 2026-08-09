package com.hasan.v1.webui.models

import java.util.Calendar
import java.util.Locale

/**
 * Conversion contrôles simplifiés ↔ expression schedule pour l'éditeur de
 * tâche (section 5 de next_update/PROMPT_CLAUDE_CODE.md). Le serveur
 * (~/.hermes/hermes-agent/cron/jobs.py `parse_schedule`) accepte 4
 * grammaires — seules 3 sont modélisées ici en contrôles simplifiés (ISO
 * timestamp exclu du sélecteur "Une fois", qui utilise un date+heure natif
 * générant directement l'ISO) :
 *  - "every <N><unit>" avec unit = m|h|d — TOUTES_LES_X
 *  - horodatage ISO "yyyy-MM-ddTHH:mm" — UNE_FOIS
 *  - expression cron 5 champs "min hour dom month dow" — QUOTIDIEN/HEBDO
 *
 * Le champ texte cron brut (mode avancé) reste la source de vérité en cas
 * d'édition d'une tâche existante dont le schedule ne correspond à aucun de
 * ces 4 patterns reconnus (ex. cron avec listes/plages complexes) — voir
 * [parseToControls], qui retombe sur le mode avancé si la reconnaissance
 * échoue plutôt que d'essayer de deviner.
 */
enum class ScheduleFrequency { ONCE, EVERY_X, DAILY, WEEKLY }

enum class EveryXUnit(val cronSuffix: String, val label: String) {
    MINUTES("m", "minutes"),
    HOURS("h", "heures")
}

/**
 * État des contrôles simplifiés — un seul de ces champs est pertinent à la
 * fois selon [frequency], les autres sont ignorés par [toScheduleString].
 * [weeklyDayOfWeek] est un index ISO 0=lundi..6=dimanche (cohérent avec
 * [DayChipRow][com.hasan.v1.ui.components.DayChipRow]), converti en cron
 * dow (0=dimanche..6=samedi) uniquement au moment de la génération.
 */
data class ScheduleControls(
    val frequency: ScheduleFrequency = ScheduleFrequency.DAILY,
    /** Timestamp epoch ms — pour ONCE. Null tant que l'utilisateur n'a pas choisi de date+heure. */
    val onceEpochMillis: Long? = null,
    val everyXAmount: Int = 30,
    val everyXUnit: EveryXUnit = EveryXUnit.MINUTES,
    /** Heure locale 0..23 pour DAILY et WEEKLY. */
    val hour: Int = 9,
    val minute: Int = 0,
    /** Index ISO 0=lundi..6=dimanche pour WEEKLY — null tant qu'aucun jour choisi. */
    val weeklyDayOfWeek: Int? = null
)

private val isoTimestampFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
private val everyPattern = Regex("""^every\s+(\d+)\s*(m|min|minutes?|h|hours?|d|days?)$""", RegexOption.IGNORE_CASE)
private val isoPattern = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$""")
private val cronPattern = Regex("""^(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)$""")

/** Génère l'expression schedule réelle envoyée au serveur depuis les contrôles simplifiés. */
fun ScheduleControls.toScheduleString(): String = when (frequency) {
    ScheduleFrequency.ONCE -> {
        val millis = onceEpochMillis ?: System.currentTimeMillis()
        isoTimestampFormat.format(java.util.Date(millis))
    }
    ScheduleFrequency.EVERY_X -> "every $everyXAmount${everyXUnit.cronSuffix}"
    ScheduleFrequency.DAILY -> "$minute $hour * * *"
    ScheduleFrequency.WEEKLY -> {
        // cron dow : 0=dimanche..6=samedi. weeklyDayOfWeek (ISO) : 0=lundi..6=dimanche.
        val cronDow = weeklyDayOfWeek?.let { (it + 1) % 7 } ?: 1
        "$minute $hour * * $cronDow"
    }
}

/**
 * Reconnaît une expression schedule existante (venant du serveur, via
 * [CronJob.scheduleDisplay] ou un champ schedule brut saisi précédemment) et
 * la convertit en contrôles pré-remplis. Retourne null si le format n'est
 * reconnu par aucune des 3 grammaires simplifiées ci-dessus — l'appelant
 * doit alors garder le mode avancé (champ texte brut) plutôt que d'afficher
 * des contrôles simplifiés qui ne représenteraient pas fidèlement la valeur
 * réelle (ex. cron avec listes "1,15" ou plages "9-17").
 */
fun parseScheduleToControls(raw: String?): ScheduleControls? {
    val schedule = raw?.trim().orEmpty()
    if (schedule.isEmpty()) return null

    everyPattern.find(schedule)?.let { match ->
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unitToken = match.groupValues[2].lowercase(Locale.US)
        val unit = when {
            unitToken.startsWith("m") -> EveryXUnit.MINUTES
            unitToken.startsWith("h") -> EveryXUnit.HOURS
            else -> return null // "d"/"day(s)" pas représentable par EveryXUnit — reste en mode avancé
        }
        return ScheduleControls(frequency = ScheduleFrequency.EVERY_X, everyXAmount = amount, everyXUnit = unit)
    }

    if (isoPattern.matches(schedule)) {
        return try {
            val parsed = isoTimestampFormat.parse(schedule.take(16))
            ScheduleControls(frequency = ScheduleFrequency.ONCE, onceEpochMillis = parsed?.time)
        } catch (_: Exception) {
            null
        }
    }

    cronPattern.find(schedule)?.let { match ->
        val (minStr, hourStr, dom, month, dow) = match.destructured
        val minute = minStr.toIntOrNull() ?: return null
        val hour = hourStr.toIntOrNull() ?: return null
        if (dom != "*" || month != "*") return null // motif non couvert par DAILY/WEEKLY — mode avancé
        return when {
            dow == "*" -> ScheduleControls(frequency = ScheduleFrequency.DAILY, hour = hour, minute = minute)
            else -> {
                val cronDow = dow.toIntOrNull() ?: return null
                val isoDow = (cronDow + 6) % 7 // cron 0=dimanche..6=samedi -> ISO 0=lundi..6=dimanche
                ScheduleControls(frequency = ScheduleFrequency.WEEKLY, hour = hour, minute = minute, weeklyDayOfWeek = isoDow)
            }
        }
    }

    return null
}

/** ISO day-of-week (0=lundi) du Calendar.DAY_OF_WEEK courant (1=dimanche..7=samedi côté java.util.Calendar). */
fun isoDayOfWeekNow(): Int {
    val cal = Calendar.getInstance()
    val javaDow = cal.get(Calendar.DAY_OF_WEEK) // 1=dimanche..7=samedi
    return (javaDow + 5) % 7 // -> 0=lundi..6=dimanche
}
