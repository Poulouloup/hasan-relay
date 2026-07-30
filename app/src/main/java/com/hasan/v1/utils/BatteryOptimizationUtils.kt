package com.hasan.v1.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Exemption de l'optimisation batterie — sans elle, les gestionnaires de batterie
 * agressifs des OEM (MIUI, EMUI, ColorOS, OneUI, OxygenOS) tuent le service wake
 * word en arrière-plan en quelques minutes à quelques heures, malgré START_STICKY
 * et la notification foreground. C'est la cause la plus fréquente de "le wake word
 * s'arrête tout seul" en usage réel.
 */
object BatteryOptimizationUtils {

    /** true si Hasan est déjà exempté (le wake word peut tourner sans être tué par l'OS). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent système demandant l'exemption directement (dialog natif Android,
     * pas de navigation manuelle requise sur AOSP/Pixel). Sur certains OEM
     * (MIUI notamment), ce dialog seul ne suffit pas toujours — l'utilisateur
     * peut aussi devoir activer "Autostart" manuellement, non couvert par une
     * API standard.
     */
    fun buildRequestExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}
