package com.hasan.v1.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.ui.screens.CutCornerOutlineButton
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono
import com.hasan.v1.ui.theme.IBMPlexSans

/**
 * .dialog-card du mockup (update/hasan-rework-mockup.html ligne 440-452, overlay
 * ov-certificates ligne 1186-1194) — style dédié distinct de [HasanConfirmOverlay] :
 * card ancrée en BAS de l'écran (pas centrée), notch 16px (--notch-lg), technique
 * cadre+remplissage (bordure border-strong + fond bg-surface-2), titre Chakra Petch.
 *
 * Le mockup ne montre qu'un seul certificat sans action de suppression individuelle
 * visible — la liste dynamique + révocation par tap-sur-ligne + état vide sont
 * conservés du comportement existant (showTrustedCertsDialog, migré ici), le mockup
 * n'illustrant qu'un exemple à un seul item plutôt qu'une limitation fonctionnelle.
 *
 * [pendingRevoke] pilote la confirmation en cascade (HasanConfirmOverlay empilé
 * par-dessus, pas de remplacement de contenu) — géré par l'appelant (SettingsFragment)
 * pour garder cet overlay uniquement responsable de l'affichage de la liste.
 */
@Composable
fun CertificatesOverlay(
    certs: Map<String, String>,
    onRevokeRequest: (key: String, fingerprint: String) -> Unit,
    onClearAllRequest: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(enabled = true, onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = HasanDimens.SpacingXl, vertical = 26.dp)
                .fillMaxWidth()
                .clip(HasanShapes.panel(cut = 16.dp))
                .background(HasanColors.BorderStrong)
                .padding(1.5.dp)
                .clip(HasanShapes.panel(cut = 16.dp))
                .background(HasanColors.BgSurface2)
                .clickable(enabled = false) {} // absorbe le clic pour ne pas fermer via le scrim
                .padding(20.dp)
        ) {
            Text(
                text = "Certificats de confiance (${certs.size})",
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(HasanDimens.SpacingM))

            if (certs.isEmpty()) {
                Text(
                    text = "Aucun certificat enregistré.\n\nLes certificats sont ajoutés automatiquement lors du premier test de connexion.",
                    color = HasanColors.TextSecondary,
                    fontFamily = IBMPlexSans,
                    fontSize = HasanDimens.TextBodyMedium
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(certs.entries.toList()) { (key, fingerprint) ->
                        Text(
                            text = truncateFingerprint(fingerprint),
                            color = HasanColors.TextSecondary,
                            fontFamily = IBMPlexMono,
                            fontSize = 13.5.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRevokeRequest(key, fingerprint) }
                                .padding(vertical = HasanDimens.SpacingM)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(HasanDimens.SpacingM))
                CutCornerOutlineButton(
                    text = "Tout effacer",
                    onClick = onClearAllRequest,
                    borderColor = HasanColors.Accent,
                    contentColor = HasanColors.Accent,
                    backgroundColor = HasanColors.BgSurface2
                )
            }
        }
    }
}

/** "58:41:76:...:2F:17:4B" — même format que l'ancien showTrustedCertsDialog(). */
private fun truncateFingerprint(fingerprint: String): String {
    val parts = fingerprint.split(":")
    return if (parts.size > 8) "${parts.take(3).joinToString(":")}:…:${parts.takeLast(3).joinToString(":")}"
    else fingerprint
}
