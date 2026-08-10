package com.hasan.v1

import android.content.Context

/**
 * Façade TTS — délègue à l'un des trois moteurs selon [SettingsManager.ttsProvider] :
 * [AndroidNativeTtsEngine] (hors ligne), [EdgeTtsEngine] (cloud, endpoint gratuit
 * non officiel de Microsoft Edge, sans clé) ou [GeminiTtsEngine] (API Google
 * officielle, clé requise, modèles en preview).
 *
 * L'instance [AndroidNativeTtsEngine] est unique et permanente (une seule init
 * TextToSpeech pour toute la durée de vie du manager) — elle sert à la fois de
 * provider "natif" normal et de secours de fallback, pour que les allers-retours
 * entre providers dans les Réglages ne recréent jamais TextToSpeech inutilement.
 * Les deux moteurs cloud sont créés/libérés dynamiquement : un seul existe à la
 * fois, celui du provider actif.
 *
 * Si un moteur cloud est sélectionné mais échoue au moment de parler (pas de
 * réseau, endpoint Microsoft changé, clé Gemini absente/invalide, quota dépassé),
 * [speak] bascule automatiquement sur le TTS natif pour cette phrase et notifie
 * l'appelant via [onFallback], sans changer le réglage persisté — au prochain
 * `speak()`, le moteur choisi est retenté.
 */
class HassanTtsManager(private val context: Context) : TtsEngine {

    private val settings = SettingsManager(context)

    private val nativeEngine = AndroidNativeTtsEngine(context)
    private var edgeEngine: EdgeTtsEngine? = null
    private var geminiEngine: GeminiTtsEngine? = null
    private var provider: String = settings.ttsProvider

    override var onSpeakingStart: (() -> Unit)? = null
        set(value) {
            field = value
            nativeEngine.onSpeakingStart = value
            edgeEngine?.onSpeakingStart = value
            geminiEngine?.onSpeakingStart = value
        }

    override var onAllSpeakingDone: (() -> Unit)? = null
        set(value) {
            field = value
            nativeEngine.onAllSpeakingDone = value
            edgeEngine?.onAllSpeakingDone = value
            geminiEngine?.onAllSpeakingDone = value
        }

    /** Notifié quand une synthèse Edge TTS échoue et bascule sur le TTS natif. */
    var onFallback: ((reason: String) -> Unit)? = null

    init {
        nativeEngine.onSpeakingStart = onSpeakingStart
        nativeEngine.onAllSpeakingDone = onAllSpeakingDone
        when (provider) {
            SettingsManager.TTS_PROVIDER_EDGE -> ensureEdgeEngine()
            SettingsManager.TTS_PROVIDER_GEMINI -> ensureGeminiEngine()
        }
    }

    private fun ensureEdgeEngine(): EdgeTtsEngine =
        edgeEngine ?: EdgeTtsEngine(context).also {
            it.setVoice(settings.ttsVoice.ifBlank { EdgeTtsEngine.DEFAULT_VOICE })
            it.onFallbackTriggered = ::handleFallback
            it.onSpeakingStart = onSpeakingStart
            it.onAllSpeakingDone = onAllSpeakingDone
            edgeEngine = it
        }

    /**
     * La clé est lue à chaque synthèse (lambda, pas valeur capturée) : l'utilisateur
     * peut la saisir dans les Réglages après la création du moteur, sans avoir à
     * rebasculer de provider pour que le moteur la voie.
     */
    private fun ensureGeminiEngine(): GeminiTtsEngine =
        geminiEngine ?: GeminiTtsEngine(context) { settings.geminiApiKey }.also {
            it.setVoice(settings.ttsVoice.takeIf { v ->
                v in SettingsManager.GEMINI_TTS_VOICES
            } ?: GeminiTtsEngine.DEFAULT_VOICE)
            it.onFallbackTriggered = ::handleFallback
            it.onSpeakingStart = onSpeakingStart
            it.onAllSpeakingDone = onAllSpeakingDone
            geminiEngine = it
        }

    private val activeEngine: TtsEngine
        get() = when (provider) {
            SettingsManager.TTS_PROVIDER_EDGE -> ensureEdgeEngine()
            SettingsManager.TTS_PROVIDER_GEMINI -> ensureGeminiEngine()
            else -> nativeEngine
        }

    private fun handleFallback(reason: String) {
        onFallback?.invoke(reason)
    }

    /**
     * Bascule vers un autre provider ("native" ou "edge"). L'instance native n'est
     * jamais recréée ; seul EdgeTtsEngine est construit à la demande et libéré quand
     * on repasse en natif, pour ne pas garder une connexion/cache inutilisés.
     */
    fun changeProvider(newProvider: String) {
        if (newProvider == provider) return
        provider = newProvider
        // Libère le moteur cloud devenu inutile (connexion, cache disque) et crée
        // seulement celui qui sert désormais — l'instance native, elle, reste.
        if (newProvider != SettingsManager.TTS_PROVIDER_EDGE) {
            edgeEngine?.release()
            edgeEngine = null
        }
        if (newProvider != SettingsManager.TTS_PROVIDER_GEMINI) {
            geminiEngine?.release()
            geminiEngine = null
        }
        when (newProvider) {
            SettingsManager.TTS_PROVIDER_EDGE -> ensureEdgeEngine()
            SettingsManager.TTS_PROVIDER_GEMINI -> ensureGeminiEngine()
        }
    }

    fun currentProvider(): String = provider

    /** true si le provider actif nécessite une connexion réseau — dépend du provider actif, réévalué à chaque lecture. */
    override val isOnline: Boolean get() = activeEngine.isOnline

    /**
     * Parle avec le moteur actif. Si Edge TTS est actif mais indisponible (pas de
     * réseau), la phrase part automatiquement sur le TTS natif en secours — le
     * réglage utilisateur n'est pas modifié, seul cet appel bascule ponctuellement.
     */
    override fun speak(text: String) {
        val engine = activeEngine
        if (engine.isOnline && !isNetworkAvailable()) {
            handleFallback("Pas de connexion réseau")
            nativeEngine.speak(text)
            return
        }
        // Gemini sans clé ne peut rien produire : bascule immédiate plutôt que
        // d'attendre l'échec réseau, pour ne pas laisser l'app muette après
        // sélection du provider mais avant saisie de la clé.
        if (engine is GeminiTtsEngine && settings.geminiApiKey.isBlank()) {
            handleFallback("Clé API Gemini non configurée")
            nativeEngine.speak(text)
            return
        }
        engine.speak(text)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun stop() {
        nativeEngine.stop()
        edgeEngine?.stop()
        geminiEngine?.stop()
    }

    override fun isSpeaking(): Boolean = nativeEngine.isSpeaking() ||
        (edgeEngine?.isSpeaking() == true) || (geminiEngine?.isSpeaking() == true)

    override fun setVolume(volume: Float) {
        nativeEngine.setVolume(volume)
        edgeEngine?.setVolume(volume)
        geminiEngine?.setVolume(volume)
    }

    override fun setSpeed(speed: Float) {
        nativeEngine.setSpeed(speed)
        edgeEngine?.setSpeed(speed)
        geminiEngine?.setSpeed(speed)
    }

    /** Change de voix — voix système si natif, nom de voix Edge/Gemini sinon. */
    fun setVoice(voiceName: String) {
        nativeEngine.setVoice(voiceName)
        edgeEngine?.setVoice(voiceName)
        geminiEngine?.setVoice(voiceName)
    }

    fun getAvailableVoices(): List<String> = when (provider) {
        SettingsManager.TTS_PROVIDER_EDGE -> SettingsManager.EDGE_TTS_VOICES
        SettingsManager.TTS_PROVIDER_GEMINI -> SettingsManager.GEMINI_TTS_VOICES
        else -> nativeEngine.getAvailableVoices().map { it.name }
    }

    /** Moteurs Android natifs installés (Google TTS, Samsung TTS, etc.). */
    fun getAvailableEngines() = nativeEngine.getAvailableEngines()

    fun getCurrentEngine(): String = nativeEngine.getCurrentEngine()

    fun changeEngine(enginePackage: String) {
        nativeEngine.changeEngine(enginePackage)
    }

    override fun release() {
        nativeEngine.release()
        edgeEngine?.release()
        edgeEngine = null
        geminiEngine?.release()
        geminiEngine = null
    }
}
