package com.hasan.v1.ui.components

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.Color
import com.hasan.v1.ui.theme.HasanColors
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Rendu Markdown partagé (Markwon) — extrait de ChatScreen.kt pour être
 * réutilisable par tout écran affichant du contenu markdown côté serveur
 * (bulles de chat, contenu SKILL.md brut dans l'écran Skills, etc.).
 */
private var sharedMarkwon: Markwon? = null

private fun getMarkwon(context: Context): Markwon =
    sharedMarkwon ?: Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(LinkifyPlugin.create())
        .build()
        .also { sharedMarkwon = it }

/**
 * [textSizeSp], [textColor], [centered], [maxLines] et [bottomPaddingPx] permettent aux écrans qui ne
 * rendent pas une bulle de chat d'ajuster le rendu sans dupliquer la configuration
 * Markwon — cas réel : le transcript du mode mains libres (.voice-transcript du
 * mockup, ligne 658 : font-size:20px, text-secondary, centré, tronqué à N lignes).
 * Les valeurs par défaut reproduisent exactement le rendu des bulles de chat, donc
 * les appels existants sont inchangés.
 */
@Composable
fun MarkdownText(
    text: String,
    selectable: Boolean,
    modifier: Modifier = Modifier,
    alphaValue: Float = 1f,
    onLongPress: (() -> Unit)? = null,
    textSizeSp: Float = 14.5f,
    textColor: Color = HasanColors.TextPrimary,
    centered: Boolean = false,
    maxLines: Int? = null,
    ellipsize: Boolean = true,
    bottomPaddingPx: Int = 0
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                // bubble-content du mockup (ligne 507) : font-size:14.5px, identique à la bulle
                // utilisateur (ChatScreen.UserBubble) — avant : 15f ici vs 13sp côté user,
                // écart visible donnant l'impression (à tort) de deux polices différentes.
                typeface = ResourcesCompat.getFont(ctx, com.hasan.v1.R.font.ibm_plex_sans_regular)
            }
        },
        update = { tv ->
            tv.setTextColor(textColor.toArgbInt())
            tv.textSize = textSizeSp
            // Marge basse VIDE, à l'intérieur du TextView : permet à un appelant qui
            // applique un dégradé d'estompage de le faire retomber dans du vide plutôt
            // que sur la dernière ligne de texte. La faire porter par le TextView (et non
            // par un Modifier.padding côté Compose) est indispensable : le dégradé est
            // dessiné sur la couche du composable, une marge Compose extérieure ne serait
            // donc pas incluse dans la zone estompée.
            tv.setPadding(0, 0, 0, bottomPaddingPx)
            tv.gravity = if (centered) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.NO_GRAVITY
            if (maxLines != null) {
                tv.maxLines = maxLines
                // ellipsize END tronque la dernière ligne visible avec "…". À désactiver
                // quand l'appelant signale déjà le débordement autrement (ex: dégradé
                // d'estompage du transcript mains-libres) — sinon le "…" apparaît au milieu
                // du fondu, ce qui donne deux marqueurs concurrents pour la même info.
                tv.ellipsize = if (ellipsize) android.text.TextUtils.TruncateAt.END else null
            } else {
                tv.maxLines = Integer.MAX_VALUE
                tv.ellipsize = null
            }
            tv.setTextIsSelectable(selectable)
            // TextView.setTextIsSelectable(true) réinitialise movementMethod en interne à
            // chaque appel (documented Android behavior) — l'écrasant silencieusement même
            // s'il a été posé dans factory. Comme update() se réexécute à chaque recomposition
            // (streaming token par token en particulier), le lien redevenait non cliquable dès
            // la première recomposition qui suit le montage initial. Le réappliquer ici, après
            // setTextIsSelectable, garde à la fois la sélection de texte ET les liens cliquables.
            tv.movementMethod = LinkMovementMethod.getInstance()
            tv.alpha = alphaValue
            // setOnLongClickListener natif (pas un Modifier Compose sur un parent) : coexiste
            // avec LinkMovementMethod sans intercepter les taps courts destinés aux liens —
            // un pointerInput/combinedClickable posé plus haut dans l'arbre Compose consomme
            // le down avant que ce TextView interop ne le voie (confirmé sur device réel).
            tv.setOnLongClickListener {
                onLongPress?.invoke()
                onLongPress != null
            }
            getMarkwon(context).setMarkdown(tv, text)
        }
    )
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)
