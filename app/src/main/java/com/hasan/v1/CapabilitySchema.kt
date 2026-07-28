package com.hasan.v1

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Type attendu d'un paramètre de capability — reflète les types JSON Schema annoncés au relay (voir capabilitiesAnnouncementJson) et récupérés dynamiquement par plugin/hasan_delivery/tools.py. */
enum class ParamType { STRING, INT, FLOAT, BOOLEAN }

/** État d'affichage du badge de permission Android d'une capability (écran Tools & Permissions). */
enum class CapabilityPermissionState { GRANTED, REQUIRED, NOT_APPLICABLE }

/** Déclaration d'un paramètre attendu par une capability, façon JSON Schema function-calling. */
data class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String = ""
)

/**
 * Résultat de validation des paramètres reçus contre un [ParamSpec] — permet à
 * [CapabilityExecutor] de rejeter un appel malformé avec un message uniforme
 * avant de dispatcher, au lieu du parsing manuel optString/optInt sans garde-fou.
 */
sealed class ParamValidationResult {
    object Valid : ParamValidationResult()
    data class Invalid(val message: String) : ParamValidationResult()
}

/** Valide `params` contre `schema` — vérifie présence des champs requis et type de chaque champ fourni. */
fun validateParams(schema: List<ParamSpec>, params: JSONObject): ParamValidationResult {
    for (spec in schema) {
        val has = params.has(spec.name) && !params.isNull(spec.name)
        if (spec.required && !has) {
            return ParamValidationResult.Invalid("Paramètre '${spec.name}' manquant")
        }
        if (!has) continue

        val typeOk = when (spec.type) {
            ParamType.STRING -> params.opt(spec.name) is String
            ParamType.INT -> params.opt(spec.name).let { it is Int || it is Long }
            ParamType.FLOAT -> params.opt(spec.name).let { it is Int || it is Long || it is Double || it is Float }
            ParamType.BOOLEAN -> params.opt(spec.name) is Boolean
        }
        if (!typeOk) {
            return ParamValidationResult.Invalid("Paramètre '${spec.name}' invalide (type ${spec.type.name.lowercase()} attendu)")
        }
    }
    return ParamValidationResult.Valid
}

/** Sérialise un schéma de paramètres au format JSON Schema minimal, envoyé au serveur via register()/updateCapabilities(). */
fun schemaToJson(schema: List<ParamSpec>): JSONObject {
    val properties = JSONObject()
    val required = org.json.JSONArray()
    schema.forEach { spec ->
        properties.put(spec.name, JSONObject().apply {
            put("type", when (spec.type) {
                ParamType.STRING -> "string"
                ParamType.INT -> "integer"
                ParamType.FLOAT -> "number"
                ParamType.BOOLEAN -> "boolean"
            })
            if (spec.description.isNotBlank()) put("description", spec.description)
        })
        if (spec.required) required.put(spec.name)
    }
    return JSONObject().apply {
        put("type", "object")
        put("properties", properties)
        put("required", required)
    }
}

/**
 * Sérialise les capabilities activées par l'utilisateur ET dont la permission Android
 * est accordée, au format annoncé au relay via l'envelope `system/capabilities` (voir
 * ConnectionManager.kt) — c'est cette liste que `plugin/hasan_delivery/tools.py` récupère
 * dynamiquement via `GET /capabilities`, pour ne plus dupliquer les schémas côté Python à
 * chaque ajout de capability. N'annonce que ce qui est réellement exécutable maintenant :
 * une capability désactivée ou sans permission serait un tool que Hermes croirait
 * disponible alors que le téléphone la refuserait systématiquement (voir
 * BridgeCommandHandler.kt, mêmes deux checks avant confirmation/exécution).
 */
fun capabilitiesAnnouncementJson(context: Context, settings: SettingsManager): JSONArray {
    val array = JSONArray()
    for (capability in ALL_CAPABILITIES) {
        if (!settings.isCapabilityEnabled(capability.name)) continue
        val permission = capability.permission
        if (permission != null &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        ) continue

        array.put(JSONObject().apply {
            put("name", capability.name)
            put("description", context.getString(capability.descriptionRes))
            put("parameters", schemaToJson(capability.parameters))
        })
    }
    return array
}
