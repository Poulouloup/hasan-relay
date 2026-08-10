package com.hasan.v1

import android.content.Context
import android.util.Base64
import android.util.Log
import com.hasan.v1.audio.VoicePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synthèse vocale via l'API Gemini (`generativelanguage.googleapis.com`,
 * modèles `*-tts`). Contrairement à [EdgeTtsEngine] — gratuit et sans clé mais
 * adossé à un endpoint non documenté — Gemini est une API officielle, mais
 * exige une **clé API** ([SettingsManager.geminiApiKey], stockée chiffrée) et
 * ses modèles TTS sont en preview : quotas ajustés sans préavis côté Google.
 *
 * Même architecture que [EdgeTtsEngine], délibérément : pipeline de chunks
 * (la synthèse du chunk N+1 démarre pendant que N est en file de lecture),
 * lecture gapless via [VoicePlayer], cache LRU des 3 derniers audios, et
 * fallback vers le TTS natif signalé par [onFallbackTriggered] — un quota
 * dépassé (429) ou une clé invalide (400/403) ne doit jamais rendre l'app
 * muette.
 *
 * Spécificité Gemini : la réponse n'est pas un fichier audio prêt à jouer mais
 * du **PCM brut en base64** (signé 16 bits, little-endian, 24 kHz, mono). Il
 * faut donc lui fabriquer un en-tête WAV avant de le passer à ExoPlayer, qui
 * ne sait pas lire du PCM nu (voir [writeWavFile]).
 */
class GeminiTtsEngine(
    private val context: Context,
    private val apiKeyProvider: () -> String
) : TtsEngine {

    companion object {
        private const val TAG = "GeminiTtsEngine"

        /** Modèle TTS le moins cher/le plus rapide — le seul du free tier en pratique. */
        const val DEFAULT_MODEL = "gemini-2.5-flash-preview-tts"
        const val DEFAULT_VOICE = "Kore"

        private const val CACHE_CAPACITY = 3
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        // Format imposé par l'API (documenté) — sert à construire l'en-tête WAV.
        private const val SAMPLE_RATE = 24_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    override val isOnline = true

    override var onSpeakingStart: (() -> Unit)? = null
    override var onAllSpeakingDone: (() -> Unit)? = null

    /** Notifié quand Gemini échoue (clé absente/invalide, quota, réseau) et qu'un fallback est requis. */
    var onFallbackTriggered: ((reason: String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        // La synthèse d'un chunk long peut dépasser le défaut de 10 s d'OkHttp.
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val pendingChunks = Channel<String>(Channel.UNLIMITED)
    private var pipelineJob: Job? = null

    private val voicePlayer = VoicePlayer(context).apply {
        setOnAllPlaybackDone { onAllSpeakingDone?.invoke() }
        setOnError { error ->
            Log.e(TAG, "Erreur de lecture VoicePlayer", error)
            onFallbackTriggered?.invoke("Erreur de lecture : ${error.message}")
        }
    }

    private var voiceName = DEFAULT_VOICE
    private val pendingUtterances = AtomicInteger(0)

    private val audioCache = object : LinkedHashMap<String, File>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File>): Boolean {
            if (size > CACHE_CAPACITY) {
                eldest.value.delete()
                return true
            }
            return false
        }
    }
    private val cacheLock = Any()

    init {
        startPipeline()
    }

    private fun cacheKey(voice: String, text: String): String = "$voice $text"

    private fun startPipeline() {
        pipelineJob = scope.launch {
            for (text in pendingChunks) {
                val audioResult = runCatching { requestSpeech(text) }
                val audioFile = audioResult.getOrNull()
                if (audioFile == null) {
                    val error = audioResult.exceptionOrNull()
                    Log.e(TAG, "Échec de synthèse Gemini TTS", error)
                    onFallbackTriggered?.invoke("Erreur Gemini TTS : ${error?.message}")
                    onUtteranceFinished()
                    continue
                }
                if (pendingUtterances.get() > 0) onSpeakingStart?.invoke()
                voicePlayer.enqueue(audioFile)
                onUtteranceFinished()
            }
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        val clean = com.hasan.v1.utils.MarkdownUtils.stripMarkdown(text)
        if (clean.isBlank()) return
        pendingUtterances.incrementAndGet()
        pendingChunks.trySend(clean)
    }

    private fun requestSpeech(text: String): File {
        val key = cacheKey(voiceName, text)
        synchronized(cacheLock) {
            audioCache[key]?.let { cached ->
                if (cached.exists()) return cached
                audioCache.remove(key)
            }
        }

        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) throw IllegalStateException("Clé API Gemini non configurée")

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))
            ))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put(
                    "voiceConfig",
                    JSONObject().put(
                        "prebuiltVoiceConfig",
                        JSONObject().put("voiceName", voiceName)
                    )
                ))
            })
        }

        // Clé passée en header et non en query string : elle ne se retrouve ainsi
        // ni dans les logs d'accès ni dans un éventuel proxy intermédiaire.
        val request = Request.Builder()
            .url("$BASE_URL/$DEFAULT_MODEL:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Le corps d'erreur Gemini porte un message lisible ({"error":{"message":…}})
                // bien plus utile que le seul code HTTP pour l'utilisateur (quota
                // dépassé vs clé invalide vs modèle retiré du free tier).
                val detail = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw java.io.IOException(
                    "HTTP ${response.code}${if (detail.isNotBlank()) " — $detail" else ""}"
                )
            }

            val base64Pcm = JSONObject(body)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)
                ?.optJSONObject("inlineData")
                ?.optString("data")
                ?.takeIf { it.isNotBlank() }
                ?: throw java.io.IOException("Réponse Gemini sans données audio")

            val pcm = Base64.decode(base64Pcm, Base64.DEFAULT)
            val out = File(context.cacheDir, "gemini_tts_${System.nanoTime()}.wav")
            writeWavFile(out, pcm)
            synchronized(cacheLock) { audioCache[key] = out }
            return out
        }
    }

    /**
     * Emballe du PCM brut dans un conteneur WAV (en-tête RIFF de 44 octets).
     * ExoPlayer ne lit pas de PCM nu : sans cet en-tête, [VoicePlayer] échoue sur
     * un format inconnu. Les paramètres sont ceux imposés par l'API Gemini
     * (24 kHz, mono, 16 bits signés little-endian).
     */
    private fun writeWavFile(target: File, pcm: ByteArray) {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val header = java.nio.ByteBuffer.allocate(44)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + pcm.size)            // taille du fichier - 8
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)                       // taille du bloc fmt (PCM)
                putShort(1)                      // format PCM non compressé
                putShort(CHANNELS.toShort())
                putInt(SAMPLE_RATE)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(BITS_PER_SAMPLE.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcm.size)
            }
        target.outputStream().use { stream ->
            stream.write(header.array())
            stream.write(pcm)
        }
    }

    private fun onUtteranceFinished() {
        if (pendingUtterances.decrementAndGet() < 0) pendingUtterances.set(0)
    }

    override fun stop() {
        pendingUtterances.set(0)
        while (pendingChunks.tryReceive().isSuccess) { /* vide la file en attente */ }
        voicePlayer.stop()
    }

    override fun isSpeaking(): Boolean = voicePlayer.isSpeaking()

    override fun setVolume(volume: Float) {
        voicePlayer.setVolume(volume)
    }

    /**
     * L'API Gemini n'expose aucun paramètre de vitesse — le réglage est appliqué
     * à la lecture par [VoicePlayer] (ExoPlayer), comme pour Edge TTS.
     */
    override fun setSpeed(speed: Float) {
        voicePlayer.setSpeed(speed.coerceIn(0.5f, 2.0f))
    }

    fun setVoice(name: String) {
        voiceName = name
    }

    fun getCurrentVoice(): String = voiceName

    override fun release() {
        stop()
        pipelineJob?.cancel()
        pendingChunks.close()
        voicePlayer.release()
        synchronized(cacheLock) {
            audioCache.values.forEach { it.delete() }
            audioCache.clear()
        }
    }
}
