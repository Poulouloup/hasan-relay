package com.hasan.v1.network

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Consomme le canal `proactive` du relay (messages poussés par Hermes hors
 * d'un tour de conversation initié par l'utilisateur) et les affiche :
 *   - app au premier plan : rien à faire ici, le ViewModel collecte le
 *     même canal directement pour l'UI ;
 *   - app en arrière-plan (mais processus vivant, WS toujours actif) :
 *     notification Android via [ProactiveNotifier] (tap → ouvre l'app).
 *
 * Distinct de [HasanFirebaseMessagingService], qui couvre le cas où l'app
 * n'a PLUS de WS actif (fermée, Doze) — les deux appellent le même
 * [ProactiveNotifier.show] pour ne pas dupliquer la logique d'affichage.
 *
 * Pas d'action "Répondre" (RemoteInput) pour l'instant — nécessite un
 * point d'accès à [ConnectionManager] depuis un BroadcastReceiver, qui
 * sera clarifié à l'intégration dans MainViewModel (étape suivante du
 * portage) plutôt que d'introduire un singleton prématurément ici.
 *
 * L'inbox (historique des messages proactifs non lus) reste dans
 * [com.hasan.v1.db] via le ViewModel — ce handler ne fait qu'afficher,
 * il ne persiste rien lui-même.
 */
class ProactiveMessageHandler(
    private val context: Context,
    private val multiplexer: ChannelMultiplexer,
    private val isAppInForeground: () -> Boolean = { defaultIsAppInForeground(context) }
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            multiplexer.proactive.collect { envelope ->
                if (envelope.type != "message") return@collect
                val text = envelope.payload.optString("text").takeIf { it.isNotBlank() } ?: return@collect

                if (isAppInForeground()) {
                    // L'UI (ViewModel) collecte multiplexer.proactive elle-même pour l'affichage
                    // en direct — pas de notification à pousser par-dessus.
                    return@collect
                }
                ProactiveNotifier.show(context, text)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private fun defaultIsAppInForeground(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            return am.runningAppProcesses?.any {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    it.processName == context.packageName
            } == true
        }
    }
}
