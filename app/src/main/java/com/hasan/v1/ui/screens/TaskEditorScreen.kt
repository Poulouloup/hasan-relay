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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hasan.v1.R
import com.hasan.v1.webui.models.CronJob
import com.hasan.v1.webui.models.DeliveryOption
import com.hasan.v1.webui.models.EveryXUnit
import com.hasan.v1.webui.models.ScheduleControls
import com.hasan.v1.webui.models.ScheduleFrequency
import com.hasan.v1.webui.models.isoDayOfWeekNow
import com.hasan.v1.webui.models.parseScheduleToControls
import com.hasan.v1.webui.models.toScheduleString
import com.hasan.v1.ui.components.CutCornerPanel
import com.hasan.v1.ui.components.DayChipRow
import com.hasan.v1.ui.components.FreqTabOption
import com.hasan.v1.ui.components.FreqTabRow
import com.hasan.v1.ui.components.HasanIconButton
import com.hasan.v1.ui.theme.ChakraPetch
import com.hasan.v1.ui.theme.HasanColors
import com.hasan.v1.ui.theme.HasanDimens
import com.hasan.v1.ui.theme.HasanShapes
import com.hasan.v1.ui.theme.IBMPlexMono
import java.util.Calendar

/**
 * Formulaire plein écran de création/édition d'une tâche cron. [initialJob]
 * non-null = mode édition (champs pré-remplis), null = mode création.
 *
 * Sélecteur de fréquence simplifié par défaut (Une fois / Toutes les X /
 * Quotidien / Hebdo — section 5 de next_update/PROMPT_CLAUDE_CODE.md),
 * générant l'expression schedule réelle via
 * [com.hasan.v1.webui.models.toScheduleString]. Un disclosure replié
 * "Expression cron (avancé)" révèle le champ texte brut pour les formats non
 * couverts par les contrôles simplifiés (voir
 * [com.hasan.v1.webui.models.parseScheduleToControls] — retombe en mode
 * avancé si le schedule existant ne correspond à aucune des 3 grammaires
 * simplifiées). En mode avancé, le champ texte devient la source de vérité
 * envoyée telle quelle (les contrôles simplifiés sont alors ignorés).
 */
@Composable
fun TaskEditorScreen(
    initialJob: CronJob?,
    deliveryOptions: List<DeliveryOption>,
    errorMessage: String?,
    onSave: (prompt: String, schedule: String, name: String?, deliver: String?) -> Unit,
    onCancel: () -> Unit,
    onPickDelivery: (List<DeliveryOption>, (DeliveryOption) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf(initialJob?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(initialJob?.prompt.orEmpty()) }
    var deliver by remember { mutableStateOf(initialJob?.deliver ?: "local") }

    // scheduleDisplay est la forme lisible ("every 30m"), pas la valeur brute
    // d'origine (non renvoyée par le serveur) — réutilisée comme point de
    // départ pour tenter la reconnaissance en contrôles simplifiés. Si elle
    // ne correspond à aucune des 3 grammaires reconnues, on retombe sur le
    // mode avancé avec le texte brut pré-rempli (le serveur re-parse de
    // toute façon la chaîne soumise, POST /api/crons/update accepte une
    // nouvelle chaîne schedule quel que soit son format d'origine).
    val initialRawSchedule = initialJob?.scheduleDisplay.orEmpty()
    val recognizedControls = remember(initialRawSchedule) { parseScheduleToControls(initialRawSchedule) }

    var advancedMode by remember { mutableStateOf(initialJob != null && recognizedControls == null) }
    var rawSchedule by remember { mutableStateOf(initialRawSchedule) }
    var controls by remember {
        mutableStateOf(
            recognizedControls ?: ScheduleControls(
                frequency = ScheduleFrequency.DAILY,
                weeklyDayOfWeek = isoDayOfWeekNow()
            )
        )
    }

    val effectiveSchedule = if (advancedMode) rawSchedule.trim() else controls.toScheduleString()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HasanColors.BgBase)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HasanDimens.SpacingS, vertical = HasanDimens.SpacingXs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HasanIconButton(
                iconRes = R.drawable.ic_close,
                contentDescription = "Retour",
                onClick = onCancel
            )
            Text(
                text = if (initialJob == null) "Nouvelle tâche" else "Modifier la tâche",
                color = HasanColors.TextPrimary,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = HasanDimens.TextTitleMedium,
                modifier = Modifier.padding(start = HasanDimens.SpacingS)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(HasanDimens.SpacingL)
        ) {

            errorMessage?.let {
                Text(
                    text = it,
                    color = HasanColors.AccentLight,
                    fontSize = HasanDimens.TextBodyMedium,
                    modifier = Modifier.padding(bottom = HasanDimens.SpacingM)
                )
            }

            EditorField(label = "Nom (optionnel)", value = name, onValueChange = { name = it }, singleLine = true)

            EditorField(
                label = "Prompt",
                value = prompt,
                onValueChange = { prompt = it },
                singleLine = false,
                minLines = 3
            )

            SectionLabel("Planification")

            FreqTabRow(
                options = listOf(
                    FreqTabOption(ScheduleFrequency.ONCE, "Une fois"),
                    FreqTabOption(ScheduleFrequency.EVERY_X, "Toutes les X"),
                    FreqTabOption(ScheduleFrequency.DAILY, "Quotidien"),
                    FreqTabOption(ScheduleFrequency.WEEKLY, "Hebdo")
                ),
                selected = controls.frequency,
                onSelect = { freq -> controls = controls.copy(frequency = freq) },
                modifier = Modifier.fillMaxWidth().padding(top = HasanDimens.SpacingXs)
            )

            Spacer(HasanDimens.SpacingM)

            if (!advancedMode) {
                when (controls.frequency) {
                    ScheduleFrequency.ONCE -> OnceControls(
                        epochMillis = controls.onceEpochMillis,
                        onChange = { millis -> controls = controls.copy(onceEpochMillis = millis) }
                    )
                    ScheduleFrequency.EVERY_X -> EveryXControls(
                        amount = controls.everyXAmount,
                        unit = controls.everyXUnit,
                        onAmountChange = { amount -> controls = controls.copy(everyXAmount = amount) },
                        onUnitChange = { unit -> controls = controls.copy(everyXUnit = unit) }
                    )
                    ScheduleFrequency.DAILY -> TimeControls(
                        hour = controls.hour,
                        minute = controls.minute,
                        onChange = { h, m -> controls = controls.copy(hour = h, minute = m) }
                    )
                    ScheduleFrequency.WEEKLY -> WeeklyControls(
                        hour = controls.hour,
                        minute = controls.minute,
                        selectedDay = controls.weeklyDayOfWeek,
                        onTimeChange = { h, m -> controls = controls.copy(hour = h, minute = m) },
                        onDaySelect = { day -> controls = controls.copy(weeklyDayOfWeek = day) }
                    )
                }

                Spacer(HasanDimens.SpacingM)

                Text(
                    text = "Expression générée : $effectiveSchedule",
                    color = HasanColors.TextMutedA11y,
                    fontFamily = IBMPlexMono,
                    fontSize = HasanDimens.TextLabelMedium
                )
            }

            Spacer(HasanDimens.SpacingM)

            AdvancedCronDisclosure(
                expanded = advancedMode,
                onToggle = {
                    if (!advancedMode) {
                        // Bascule vers avancé : pré-remplit avec l'expression générée par les contrôles actuels.
                        rawSchedule = effectiveSchedule
                    }
                    advancedMode = !advancedMode
                },
                rawValue = rawSchedule,
                onRawChange = { rawSchedule = it }
            )

            SectionLabel("Livraison")
            CutCornerPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onPickDelivery(deliveryOptions) { picked -> deliver = picked.value }
                    },
                shape = HasanShapes.panel()
            ) {
                Text(
                    text = deliveryOptions.firstOrNull { it.value == deliver }?.label ?: deliver,
                    color = HasanColors.TextPrimary,
                    fontSize = HasanDimens.TextSubtitle,
                    modifier = Modifier.padding(HasanDimens.SpacingM)
                )
            }

            // .btn-row du mockup (ligne 863-866) — .btn-ghost/.btn-primary, pas des
            // CutCornerPanel ad-hoc en casse normale (ces boutons partagent la règle
            // universelle .btn : Chakra Petch capitales, voir CutCornerOutlineButton).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HasanDimens.SpacingXxl),
                horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingM)
            ) {
                CutCornerOutlineButton(
                    text = "Annuler",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                )
                CutCornerFilledButton(
                    text = "Enregistrer",
                    onClick = { onSave(prompt.trim(), effectiveSchedule, name.trim().ifBlank { null }, deliver) },
                    modifier = Modifier.weight(1f),
                    enabled = prompt.isNotBlank() && effectiveSchedule.isNotBlank()
                )
            }
        }
    }
}

@Composable
private fun Spacer(height: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.height(height))
}

// .field label du mockup (ligne 551-552, 807 pour "Planification") — même règle que
// EditorField : "$ " en text-disabled, capitales, mono 11.5px.
@Composable
private fun SectionLabel(text: String) {
    Row(modifier = Modifier.padding(top = HasanDimens.SpacingM, bottom = HasanDimens.SpacingXs)) {
        Text(text = "$ ", color = HasanColors.TextMuted, fontFamily = IBMPlexMono, fontSize = 11.5.sp, letterSpacing = 0.7.sp)
        Text(
            text = text.uppercase(),
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = 11.5.sp,
            letterSpacing = 0.7.sp
        )
    }
}

// ─────────────────────────── Contrôles par fréquence ───────────────────────

@Composable
private fun OnceControls(epochMillis: Long?, onChange: (Long) -> Unit) {
    val calendar = remember(epochMillis) {
        Calendar.getInstance().apply { epochMillis?.let { timeInMillis = it } }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val dateLabel = remember(epochMillis) {
        if (epochMillis == null) "Choisir date et heure"
        else java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMillis))
    }
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                android.app.DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        calendar.set(year, month, day)
                        android.app.TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                calendar.set(Calendar.HOUR_OF_DAY, hour)
                                calendar.set(Calendar.MINUTE, minute)
                                calendar.set(Calendar.SECOND, 0)
                                onChange(calendar.timeInMillis)
                            },
                            calendar.get(Calendar.HOUR_OF_DAY),
                            calendar.get(Calendar.MINUTE),
                            true
                        ).show()
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
        shape = HasanShapes.panel()
    ) {
        Text(
            text = dateLabel,
            color = if (epochMillis == null) HasanColors.TextMutedA11y else HasanColors.TextPrimary,
            fontFamily = IBMPlexMono,
            fontSize = HasanDimens.TextSubtitle,
            modifier = Modifier.padding(HasanDimens.SpacingM)
        )
    }
}

@Composable
private fun EveryXControls(
    amount: Int,
    unit: EveryXUnit,
    onAmountChange: (Int) -> Unit,
    onUnitChange: (EveryXUnit) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HasanDimens.SpacingM)) {
        TextField(
            value = amount.toString(),
            onValueChange = { text -> text.filter { it.isDigit() }.toIntOrNull()?.let { onAmountChange(it.coerceIn(1, 999)) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.width(90.dp),
            colors = editorFieldColors()
        )
        FreqTabRow(
            options = listOf(
                FreqTabOption(EveryXUnit.MINUTES, "Minutes"),
                FreqTabOption(EveryXUnit.HOURS, "Heures")
            ),
            selected = unit,
            onSelect = onUnitChange,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
// .field label "Heure" du mockup (ligne 826, 840) — absent avant cette correction sur
// les deux usages (Quotidien/Hebdo).
private fun TimeControls(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val timeLabel = "%02d:%02d".format(hour, minute)
    Column {
        SectionLabel("Heure")
        CutCornerPanel(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    android.app.TimePickerDialog(context, { _, h, m -> onChange(h, m) }, hour, minute, true).show()
                },
            shape = HasanShapes.panel()
        ) {
            Text(
                text = timeLabel,
                color = HasanColors.TextPrimary,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextSubtitle,
                modifier = Modifier.padding(HasanDimens.SpacingM)
            )
        }
    }
}

// .freq-panel du mockup (ligne 606, 815-827) — fond bg-surface-2 + bordure border-subtle,
// label "Jour" (.rl-sub) au-dessus de DayChipRow, absents avant cette correction.
@Composable
private fun WeeklyControls(
    hour: Int,
    minute: Int,
    selectedDay: Int?,
    onTimeChange: (Int, Int) -> Unit,
    onDaySelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HasanColors.BgSurface2)
            .border(HasanDimens.BorderWidth, HasanColors.Border)
            .padding(HasanDimens.SpacingM)
    ) {
        Text(
            text = "Jour",
            color = HasanColors.TextMutedA11y,
            fontFamily = IBMPlexMono,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = HasanDimens.SpacingS)
        )
        DayChipRow(
            selectedIndex = selectedDay,
            onSelect = onDaySelect,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(HasanDimens.SpacingM)
        TimeControls(hour = hour, minute = minute, onChange = onTimeChange)
    }
}

// ─────────────────────────── Disclosure mode avancé ─────────────────────────

@Composable
private fun AdvancedCronDisclosure(
    expanded: Boolean,
    onToggle: () -> Unit,
    rawValue: String,
    onRawChange: (String) -> Unit
) {
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "cron-disclosure-chevron"
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = HasanDimens.SpacingS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "▶",
                color = HasanColors.TextMutedA11y,
                fontSize = HasanDimens.TextLabelSmall,
                modifier = Modifier.rotate(rotation).padding(end = 6.dp)
            )
            Text(
                text = "EXPRESSION CRON (AVANCÉ)",
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = HasanDimens.TextLabelSmall,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded) {
            EditorField(
                label = "Planification (brut)",
                value = rawValue,
                onValueChange = onRawChange,
                singleLine = true,
                helpText = "Formats acceptés : \"every 30m\" / \"every 2h\" · expression cron " +
                    "5 champs (\"0 9 * * *\") · horodatage ISO (\"2026-08-01T09:00\") · " +
                    "durée simple (\"30m\", \"2h\")"
            )
        }
    }
}

@Composable
private fun editorFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = HasanColors.BgSurface,
    unfocusedContainerColor = HasanColors.BgSurface,
    focusedIndicatorColor = HasanColors.Accent,
    unfocusedIndicatorColor = HasanColors.Border,
    focusedTextColor = HasanColors.TextPrimary,
    unfocusedTextColor = HasanColors.TextPrimary,
    cursorColor = HasanColors.Accent
)

@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    minLines: Int = 1,
    helpText: String? = null
) {
    // .field label du mockup (ligne 551-552) — "$ " en text-disabled généré par ::before
    // (couleur distincte du reste du label, donc deux Text), capitales, 11.5px, letter-spacing.
    // .field input (ligne 553-554) — border-left:2.5px accent, absente du TextField Material3
    // par défaut (juste un indicator en bas) : accent-bar ajoutée via drawBehind.
    Column(modifier = Modifier.fillMaxWidth().padding(top = HasanDimens.SpacingM)) {
        Row {
            Text(text = "$ ", color = HasanColors.TextMuted, fontFamily = IBMPlexMono, fontSize = 11.5.sp, letterSpacing = 0.7.sp)
            Text(
                text = label.uppercase(),
                color = HasanColors.TextMutedA11y,
                fontFamily = IBMPlexMono,
                fontSize = 11.5.sp,
                letterSpacing = 0.7.sp
            )
        }
        Spacer(HasanDimens.SpacingXs)
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(color = HasanColors.BorderAccent, size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height))
                },
            keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
            colors = editorFieldColors()
        )
        helpText?.let {
            Text(
                text = it,
                color = HasanColors.TextMutedA11y,
                fontSize = HasanDimens.TextLabelMedium,
                modifier = Modifier.padding(top = HasanDimens.SpacingXs)
            )
        }
    }
}
