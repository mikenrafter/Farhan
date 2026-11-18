package ly.com.tahaben.notification_filter_presentation.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import ly.com.tahaben.core_ui.LocalSpacing
import ly.com.tahaben.core_ui.mirror
import ly.com.tahaben.notification_filter_domain.schedule.EditorNotice
import ly.com.tahaben.notification_filter_domain.schedule.EditorSelection
import ly.com.tahaben.notification_filter_domain.schedule.EditorState
import java.time.Instant
import java.time.ZoneOffset

/** The full-day schedule editor: timeline, exact controls, Apply and Cancel. Reached from the notification dashboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(
    onNavigateUp: () -> Unit,
    viewModel: ScheduleEditorViewModel = hiltViewModel(),
) {
    val spacing = LocalSpacing.current
    val screen = viewModel.screen
    val state = viewModel.editorState
    val editor = viewModel.editor
    var confirmDiscard by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(screen.applied) { if (screen.applied) onNavigateUp() }
    LaunchedEffect(state?.isFineDrag) { if (state?.isFineDrag == true) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    BackHandler(enabled = state?.isDirty == true) { confirmDiscard = true }

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        TopAppBar(
            title = { Text("Notification schedule") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = {
                IconButton(onClick = { if (state?.isDirty == true) confirmDiscard = true else onNavigateUp() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.mirror())
                }
            },
        )
        when {
            screen.isLoading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            screen.loadError != null || state == null -> Text(
                screen.loadError ?: "The schedule could not be shown.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(spacing.spaceMedium),
            )
            else -> {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = spacing.spaceMedium),
                    verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall),
                ) {
                    TargetHeader(screen, state.isDirty || state.isDragging, onEveryDay = { viewModel.chooseTarget(ScheduleTarget.Repeating) },
                        onPickDate = { pickingDate = true })
                    ScheduleTimeline(state, editor)
                    ZoomRow(state, onZoomIn = editor::zoomIn, onZoomOut = editor::zoomOut, onReset = editor::resetZoom,
                        onPanLeft = { editor.pan(-state.view.span / 4) }, onPanRight = { editor.pan(state.view.span / 4) })
                    SelectionPanel(state, viewModel, onPickTime = { pickingTime = true })
                    HorizontalDivider()
                    Text("Windows", style = MaterialTheme.typography.titleSmall)
                    state.schedule.forEachIndexed { index, period ->
                        val selected = state.selection == EditorSelection.Zone(index)
                        Surface(
                            tonalElevation = if (selected) 4.dp else 0.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().clickable { editor.selectZone(index) }
                                .semantics { role = Role.Button; this.selected = selected
                                    contentDescription = "Window ${clockLabel(period.startMinuteOfDay)} to ${clockLabel(period.endMinuteOfDay)}, ${period.budgetPoints} points" },
                        ) {
                            Row(Modifier.padding(spacing.spaceSmall), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${clockLabel(period.startMinuteOfDay)} – ${clockLabel(period.endMinuteOfDay)}",
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                Text(if (period.budgetPoints == 0) "Silent" else "${period.budgetPoints} pts",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(spacing.spaceMedium),
                    horizontalArrangement = Arrangement.spacedBy(spacing.spaceSmall, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = editor::undo, enabled = state.canUndo) { Text("Undo") }
                    OutlinedButton(onClick = viewModel::cancel, enabled = state.isDirty && !state.isDragging) { Text("Cancel") }
                    Button(onClick = viewModel::apply, enabled = state.isDirty && !state.isDragging && !screen.isApplying) { Text("Apply") }
                }

                state.notice?.let { notice ->
                    AlertDialog(
                        onDismissRequest = editor::dismissNotice,
                        title = { Text(if (notice == EditorNotice.SPLIT_NEEDS_MORE_TIME) "Not enough room to split" else "Budgets too large to merge") },
                        text = { Text(if (notice == EditorNotice.SPLIT_NEEDS_MORE_TIME)
                            "A split needs two hours. This window is shorter than that, and the windows next to it have no spare time to lend. Make this window or a window next to it longer, then split again."
                            else "These two budgets add up to more than the app can store. Lower one of them first, then remove the boundary.") },
                        confirmButton = { TextButton(onClick = editor::dismissNotice) { Text("OK") } },
                    )
                }
                if (screen.confirmReplace) {
                    AlertDialog(
                        onDismissRequest = viewModel::dismissConfirmReplace,
                        title = { Text("Saved schedule changed") },
                        text = { Text("This schedule was changed somewhere else (for example by automation) after you opened the editor. Replace it with what you see here? Points already spent are kept. Or reload and lose your edits.") },
                        confirmButton = { TextButton(onClick = viewModel::confirmReplace) { Text("Replace") } },
                        dismissButton = { TextButton(onClick = viewModel::reloadFromStorage) { Text("Reload") } },
                    )
                }
                screen.applyError?.let { message ->
                    AlertDialog(
                        onDismissRequest = viewModel::dismissApplyError,
                        title = { Text("Not saved") },
                        text = { Text(message) },
                        confirmButton = { TextButton(onClick = viewModel::dismissApplyError) { Text("OK") } },
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits have not been saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.cancel(); onNavigateUp() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (pickingDate) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        viewModel.chooseTarget(ScheduleTarget.Dated(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()))
                    }
                    pickingDate = false
                }) { Text("Edit this date") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
    val boundary = state?.selection as? EditorSelection.Boundary
    if (pickingTime && boundary != null && state != null) {
        val current = state.schedule[boundary.index].endMinuteOfDay
        val pickerState = rememberTimePickerState(initialHour = (current / 60).coerceAtMost(23), initialMinute = current % 60, is24Hour = true)
        Dialog(onDismissRequest = { pickingTime = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(spacing.spaceLarge)) {
                    Text("Exact time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = spacing.spaceSmall))
                    TimePicker(state = pickerState)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { pickingTime = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            editor.setBoundaryMinute(boundary.index, pickerState.hour * 60 + pickerState.minute)
                            pickingTime = false
                        }) { Text("OK") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetHeader(screen: ScheduleScreenState, locked: Boolean, onEveryDay: () -> Unit, onPickDate: () -> Unit) {
    val dated = screen.target as? ScheduleTarget.Dated
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = dated == null, onClick = onEveryDay, enabled = !locked, label = { Text("Every day") })
            FilterChip(selected = dated != null, onClick = onPickDate, enabled = !locked,
                label = { Text(dated?.let { "Only ${it.date}" } ?: "One date…") })
        }
        Text(
            when {
                dated != null -> "You are editing ${dated.date} only. Other dates use the every-day schedule." +
                    if (screen.targetIsNew) " This date has no schedule of its own yet, so you start from the every-day one." else ""
                screen.todayHasOverride -> "You are editing the every-day schedule. Today uses a one-date schedule, and Apply replaces it with this one."
                else -> "You are editing the every-day schedule. It applies to every date without a schedule of its own."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (locked) Text("Apply or Cancel your edits before choosing another schedule.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ZoomRow(state: EditorState, onZoomIn: () -> Unit, onZoomOut: () -> Unit, onReset: () -> Unit, onPanLeft: () -> Unit, onPanRight: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 3) {
        OutlinedButton(onClick = onZoomIn, enabled = state.view.span > 30f) { Text("Zoom in") }
        OutlinedButton(onClick = onZoomOut, enabled = !state.view.isFullDay) { Text("Zoom out") }
        TextButton(onClick = onReset, enabled = !state.view.isFullDay) { Text("Full day") }
        if (!state.view.isFullDay) {
            TextButton(onClick = onPanLeft, enabled = state.view.startMinute > 0f) { Text("◀ Earlier") }
            TextButton(onClick = onPanRight, enabled = state.view.endMinute < 1440f) { Text("Later ▶") }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SelectionPanel(state: EditorState, viewModel: ScheduleEditorViewModel, onPickTime: () -> Unit) {
    val editor = viewModel.editor
    val spacing = LocalSpacing.current
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.spaceMedium), verticalArrangement = Arrangement.spacedBy(spacing.spaceSmall)) {
            when (val selection = state.selection) {
                EditorSelection.None -> Text(
                    "Tap a window to set its budget, or tap a boundary to move it. Only the selected boundary can be dragged.",
                    style = MaterialTheme.typography.bodyMedium)
                is EditorSelection.Zone -> {
                    val period = state.schedule[selection.index]
                    Text("${clockLabel(period.startMinuteOfDay)} – ${clockLabel(period.endMinuteOfDay)}", style = MaterialTheme.typography.titleMedium)
                    BudgetField(selection.index, period.budgetPoints, onCommit = { editor.setBudget(selection.index, it) })
                    Text(budgetSummary(period.budgetPoints), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { editor.splitSelectedZone() }) { Text("Split this window") }
                }
                is EditorSelection.Boundary -> {
                    val before = state.schedule[selection.index]
                    val after = state.schedule[selection.index + 1]
                    Text(clockLabel(before.endMinuteOfDay), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        when {
                            state.isFineDrag -> "Moving one minute at a time. Lift your finger to stop."
                            state.isDragging -> "Moving in five-minute steps. Hold still for one-minute steps."
                            else -> "Between ${clockLabel(before.startMinuteOfDay)} – ${clockLabel(before.endMinuteOfDay)} and ${clockLabel(after.startMinuteOfDay)} – ${clockLabel(after.endMinuteOfDay)}. Drag it on the timeline, or use the buttons."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 2) {
                        listOf(-5 to "−5 min", -1 to "−1 min", 1 to "+1 min", 5 to "+5 min").forEach { (delta, label) ->
                            OutlinedButton(onClick = { editor.nudgeSelectedBoundary(delta) }, enabled = !state.isDragging) { Text(label) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPickTime, enabled = !state.isDragging) { Text("Set exact time") }
                        OutlinedButton(onClick = { editor.mergeSelectedBoundary() }, enabled = !state.isDragging) { Text("Remove boundary") }
                    }
                    Text("Every window stays at least one hour. Removing a boundary merges the two windows and adds their budgets.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BudgetField(zoneIndex: Int, points: Int, onCommit: (Int) -> Unit) {
    var text by remember(zoneIndex, points) { mutableStateOf(points.toString()) }
    fun commit() { text.toIntOrNull()?.takeIf { it != points }?.let(onCommit) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { onCommit((points - 1).coerceAtLeast(0)) }, enabled = points > 0) { Text("−") }
        OutlinedTextField(
            value = text,
            onValueChange = {
                if (it.length <= 9 && it.all(Char::isDigit)) {
                    text = it
                    it.toIntOrNull()?.let(onCommit)
                }
            },
            label = { Text("Points") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) commit() },
        )
        OutlinedButton(onClick = { onCommit(points + 1) }, enabled = points < 999_999_999) { Text("+") }
    }
}

/** HIGH, MEDIUM and LOW cost 2, 4 and 8 points; CRITICAL is free. */
internal fun budgetSummary(points: Int): String =
    if (points == 0) "Silent: nothing is delivered in this window."
    else "Enough for about ${points / 2} high, ${points / 4} medium or ${points / 8} low notifications."

