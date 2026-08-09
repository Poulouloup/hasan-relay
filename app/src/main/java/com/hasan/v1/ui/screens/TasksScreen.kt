package com.hasan.v1.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.webui.models.CronJob
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.HasanIconButton
import com.hasan.v1.ui.components.HasanMinimalHeader
import com.hasan.v1.ui.components.HasanToggle
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono
import com.hasan.v1.ui.theme.IBMPlexSans

/** État affiché par l'écran Tasks — reflète TasksViewModel.uiState. */
data class TasksScreenUiState(
    val jobs: List<CronJob>,
    val loading: Boolean,
    val errorMessage: String?,
    val runningJobIds: Set<String>
)

/** Callbacks délégués au Fragment — aucune logique métier dans les composables. */
class TasksCallbacks(
    val onMenuClick: () -> Unit,
    val onRefresh: () -> Unit,
    val onNewTask: () -> Unit,
    val onEditTask: (CronJob) -> Unit,
    val onToggleEnabled: (CronJob) -> Unit,
    val onRunNow: (CronJob) -> Unit,
    val onShowHistory: (CronJob) -> Unit,
    val onDeleteTask: (CronJob) -> Unit,
    val onDismissError: () -> Unit
)

@Composable
fun TasksScreen(state: TasksScreenUiState, callbacks: TasksCallbacks) {
    Column(modifier = Modifier.fillMaxSize()) {
        HasanMinimalHeader(callbacks.onMenuClick, title = "Tâches")
        TasksHeader(jobCount = state.jobs.size, loading = state.loading, onRefresh = callbacks.onRefresh, onNewTask = callbacks.onNewTask)

        state.errorMessage?.let { message ->
            TasksErrorBanner(message = message, onDismiss = callbacks.onDismissError)
        }

        when {
            state.loading && state.jobs.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HasanColors.Accent)
                }
            }
            state.jobs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(HasanDimens.SpacingXxl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aucune tâche programmée",
                        color = HasanColors.TextMutedA11y,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = HasanDimens.SpacingL),
                    verticalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)
                ) {
                    items(state.jobs, key = { it.id }) { job ->
                        TaskCard(
                            job = job,
                            running = job.id in state.runningJobIds,
                            callbacks = callbacks
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksHeader(jobCount: Int, loading: Boolean, onRefresh: () -> Unit, onNewTask: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingL),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = jobCount.toString(),
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = HasanDimens.TextDisplay
            )
            Text(
                text = "tâches programmées",
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextCaption
            )
        }
        // .icon-btn du mockup (ligne 777-780) — transparent, pas de fond/bordure coloré
        // (AccentIconButton avait un fond AccentGlowBg + bordure AccentDim, absents ici).
        Row(horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)) {
            com.hasan.v1.ui.components.RefreshIconButton(loading = loading, onClick = onRefresh)
            com.hasan.v1.ui.components.HasanIconButton(
                iconRes = com.hasan.v1.R.drawable.ic_plus,
                contentDescription = "Créer une tâche",
                onClick = onNewTask
            )
        }
    }
}

@Composable
private fun TasksErrorBanner(message: String, onDismiss: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HasanDimens.SpacingL, vertical = HasanDimens.SpacingXs),
        backgroundColor = HasanColors.BgSurface,
        borderColor = HasanColors.Accent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = HasanColors.Accent,
                fontSize = HasanDimens.TextBodyMedium,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_close),
                contentDescription = "Fermer",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HasanColors.TextMutedA11y),
                modifier = Modifier
                    .size(HasanDimens.IconSmall)
                    .padding(start = HasanDimens.SpacingS)
                    .clickableTextPadding(onDismiss)
            )
        }
    }
}

@Composable
private fun TaskCard(job: CronJob, running: Boolean, callbacks: TasksCallbacks) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = HasanShapes.panel()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(HasanDimens.SpacingM)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = job.name,
                    color = HasanColors.TextPrimary,
                    fontSize = HasanDimens.TextBody,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TaskStatusPill(job = job, running = running)
            }

            // .mono-chip du mockup (ligne 313-316, utilisé ligne 1399 pour l'expression cron)
            // — fond bg-surface-2, bordure border-subtle, texte accent-strong mono 12px.
            Box(
                modifier = Modifier
                    .padding(top = HasanDimens.SpacingS)
                    .background(HasanColors.BgSurface2)
                    .border(HasanDimens.BorderWidth, HasanColors.Border)
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = job.scheduleDisplay,
                    color = HasanColors.AccentStrong,
                    fontFamily = IBMPlexMono,
                    fontSize = 12.sp
                )
            }

            job.lastError?.let { error ->
                Text(
                    text = error,
                    color = HasanColors.Accent,
                    fontSize = HasanDimens.TextCaption,
                    modifier = Modifier.padding(top = HasanDimens.SpacingXs)
                )
            }

            // .switch-row du mockup (ligne 1401) — label "Actif" directement à gauche du
            // switch (pas de weight/espace vide entre les deux), bouton play séparé à droite.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HasanDimens.SpacingM),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingS)) {
                    Text(text = "Actif", color = HasanColors.TextPrimary, fontFamily = IBMPlexSans, fontSize = 14.5.sp)
                    HasanToggle(checked = job.enabled, onCheckedChange = { callbacks.onToggleEnabled(job) })
                }
                // .icon-btn.cut-sm du mockup (ligne 1402) — fond accent-deep plein, icône
                // blanche, pas transparent comme AccentIconButton (fond translucide + bordure).
                Box(
                    modifier = Modifier
                        .size(HasanDimens.TouchTarget)
                        .clip(HasanShapes.panelSmall())
                        .background(HasanColors.Accent)
                        .clickable(onClick = { callbacks.onRunNow(job) }),
                    contentAlignment = Alignment.Center
                ) {
                    if (running) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(com.hasan.v1.R.drawable.ic_play),
                            contentDescription = "Exécuter maintenant",
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // border-top du mockup (ligne 1404) — séparateur au-dessus de la rangée d'actions,
            // absent avant cette correction.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = HasanDimens.SpacingM)
                    .height(HasanDimens.BorderWidth)
                    .background(HasanColors.Border)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HasanDimens.SpacingXs),
                horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingL)
            ) {
                Text(
                    text = "Historique",
                    color = HasanColors.TextSecondary,
                    fontFamily = IBMPlexSans,
                    fontSize = 13.5.sp,
                    modifier = Modifier.clickableTextPadding { callbacks.onShowHistory(job) }
                )
                Text(
                    text = "Modifier",
                    color = HasanColors.TextSecondary,
                    fontFamily = IBMPlexSans,
                    fontSize = 13.5.sp,
                    modifier = Modifier.clickableTextPadding { callbacks.onEditTask(job) }
                )
                Text(
                    text = "Supprimer",
                    color = HasanColors.AccentStrong,
                    fontFamily = IBMPlexSans,
                    fontSize = 13.5.sp,
                    modifier = Modifier.clickableTextPadding { callbacks.onDeleteTask(job) }
                )
            }
        }
    }
}

// .badge/.badge-success du mockup (ligne 377-378, 1397) — rectangle net avec bordure
// teintée, pas TagPill (coins arrondis, sans bordure) : écart visuel avec le mockup
// sur ce composant partagé, corrigé localement ici plutôt que globalement (TagPill
// reste utilisé ailleurs — Kanban/Tools/Skills — hors périmètre de cette passe).
private data class StatusPillStyle(val label: String, val bg: Color, val fg: Color, val borderTinted: Boolean)

@Composable
private fun TaskStatusPill(job: CronJob, running: Boolean) {
    val style = when {
        running -> StatusPillStyle("EN COURS", HasanColors.AccentDim, HasanColors.Accent, borderTinted = true)
        !job.enabled -> StatusPillStyle("PAUSE", HasanColors.BgSurface3, HasanColors.TextMutedA11y, borderTinted = false)
        job.lastStatus == "error" -> StatusPillStyle("ERREUR", HasanColors.AccentDim, HasanColors.Accent, borderTinted = true)
        else -> StatusPillStyle("ACTIF", HasanColors.SuccessSoft, HasanColors.Success, borderTinted = true)
    }
    val borderColor = if (style.borderTinted) style.fg.copy(alpha = 0.35f) else HasanColors.Border
    Box(
        modifier = Modifier
            .background(style.bg)
            .border(HasanDimens.BorderWidth, borderColor)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text = style.label, color = style.fg, fontFamily = IBMPlexMono, fontSize = 11.sp, letterSpacing = 0.3.sp)
    }
}

private fun Modifier.clickableTextPadding(onClick: () -> Unit): Modifier =
    this.padding(HasanDimens.SpacingXs).clickable(onClick = onClick)
