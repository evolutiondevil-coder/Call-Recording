package bg.evolution.callrecorder.ui.home

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VoiceOverOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.evolution.callrecorder.R
import bg.evolution.callrecorder.core.CallDirection
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.core.Formatters
import bg.evolution.callrecorder.core.RecordMode
import bg.evolution.callrecorder.data.db.Recording
import bg.evolution.callrecorder.data.settings.AppSettings
import bg.evolution.callrecorder.detect.VoipApps
import bg.evolution.callrecorder.service.RecorderState
import bg.evolution.callrecorder.ui.components.CallerAvatar
import bg.evolution.callrecorder.ui.components.EmptyState
import bg.evolution.callrecorder.ui.components.LevelBars
import bg.evolution.callrecorder.ui.components.UpdateCard
import bg.evolution.callrecorder.ui.components.isWorthShowing
import bg.evolution.callrecorder.ui.components.rememberAppIcon
import bg.evolution.callrecorder.ui.components.segmentShape
import bg.evolution.callrecorder.ui.setup.Permissions
import bg.evolution.callrecorder.ui.setup.SetupStatus
import bg.evolution.callrecorder.ui.theme.LocalExtraColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenRecording: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rec by vm.recorderState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val update by vm.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var setup by remember { mutableStateOf(Permissions.status(context)) }
    LifecycleResumeEffect(Unit) {
        setup = Permissions.status(context)
        onPauseOrDispose { }
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val selecting = state.selection.isNotEmpty()
    BackHandler(selecting) { vm.clearSelection() }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startManual()
    }
    val startManual = {
        if (Permissions.granted(context, Manifest.permission.RECORD_AUDIO)) vm.startManual()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val deletedMsg = stringResource(R.string.snack_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    fun delete(ids: Set<Long>) {
        val batch = vm.requestDelete(ids)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = String.format(Locale.getDefault(), deletedMsg, batch.size),
                actionLabel = undoLabel,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoDelete(batch) else vm.commitDelete(batch)
        }
    }

    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(selecting, label = "topbar") { sel ->
                if (sel) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
                        },
                        title = { Text(pluralStringResource(R.plurals.selected_count, state.selection.size, state.selection.size)) },
                        actions = {
                            IconButton(onClick = vm::selectAll) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.action_select_all)) }
                            IconButton(onClick = {
                                vm.shareIntent(vm.selectedRecordings())?.let { runCatching { context.startActivity(it) } }
                            }) { Icon(Icons.Rounded.Share, stringResource(R.string.action_share)) }
                            IconButton(onClick = { delete(state.selection) }) {
                                Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                } else {
                    LargeTopAppBar(
                        title = { Text(stringResource(R.string.home_title)) },
                        actions = {
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title))
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(!selecting, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                RecordFab(rec, fabExpanded, onStart = startManual, onStop = vm::stopRecording)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 104.dp),
        ) {
            if (update.isWorthShowing()) {
                item(key = "update") {
                    UpdateCard(update, onInstall = vm.updates::install, onRetry = vm.updates::checkNow, modifier = Modifier.animateItem())
                }
            }
            item(key = "status") {
                StatusCard(
                    rec = rec,
                    level = vm.level,
                    recordedMs = vm::recordedMs,
                    settings = settings,
                    onToggleAuto = vm::setAutoRecord,
                    onStop = vm::stopRecording,
                    onPause = vm::pauseRecording,
                    onResume = vm::resumeRecording,
                    onConfirm = vm::confirmRecording,
                    onDecline = vm::declineRecording,
                )
            }
            if (!setup.allDone) {
                item(key = "setup") { SetupCard(setup, onOpenSetup) }
            }
            if (state.hasAnyRecording) {
                item(key = "stats") { StatsRow(state) }
                item(key = "search") { SearchField(state.query, vm::setQuery) }
                if (state.filters.size > 1) {
                    item(key = "filters") { FilterRow(state.filters, state.filter, vm::setFilter) }
                }
            }
            if (!state.loading && state.sections.isEmpty()) {
                item(key = "empty") {
                    if (state.hasAnyRecording) {
                        EmptyState(Icons.Rounded.Search, stringResource(R.string.empty_search_title), stringResource(R.string.empty_search_text))
                    } else {
                        EmptyState(Icons.Rounded.GraphicEq, stringResource(R.string.empty_title), stringResource(R.string.empty_text))
                    }
                }
            }
            state.sections.forEach { section ->
                item(key = "day-${section.day}") { DayHeader(section.day, Modifier.animateItem()) }
                itemsIndexed(section.items, key = { _, r -> r.id }) { index, r ->
                    RecordingRow(
                        r = r,
                        index = index,
                        count = section.items.size,
                        selected = r.id in state.selection,
                        selectionMode = selecting,
                        onClick = { if (selecting) vm.toggleSelection(r.id) else onOpenRecording(r.id) },
                        onLongClick = { vm.toggleSelection(r.id) },
                        onToggleFavorite = { vm.toggleFavorite(r) },
                        onDelete = { delete(setOf(r.id)) },
                        modifier = Modifier.animateItem(fadeOutSpec = null),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordFab(rec: RecorderState, expanded: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val extra = LocalExtraColors.current
    if (rec is RecorderState.Active) {
        ExtendedFloatingActionButton(
            onClick = onStop,
            expanded = expanded,
            icon = { Icon(Icons.Rounded.Stop, null) },
            text = { Text(stringResource(R.string.action_stop)) },
            containerColor = extra.record,
            contentColor = extra.onRecord,
        )
    } else {
        ExtendedFloatingActionButton(
            onClick = onStart,
            expanded = expanded,
            icon = { Icon(Icons.Rounded.Mic, null) },
            text = { Text(stringResource(R.string.action_record_now)) },
        )
    }
}

// -------------------------------------------------------------------------------------------
// Status hero card
// -------------------------------------------------------------------------------------------

@Composable
private fun StatusCard(
    rec: RecorderState,
    level: StateFlow<Float>,
    recordedMs: () -> Long,
    settings: AppSettings,
    onToggleAuto: (Boolean) -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    val extra = LocalExtraColors.current
    val onHero = extra.onHero
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(listOf(extra.heroStart, extra.heroEnd)))
            .drawBehind {
                // Soft decorative circles for depth.
                drawCircle(onHero.copy(alpha = 0.07f), radius = size.height * 0.9f, center = Offset(size.width * 0.95f, -size.height * 0.15f))
                drawCircle(onHero.copy(alpha = 0.05f), radius = size.height * 0.6f, center = Offset(size.width * 0.05f, size.height * 1.1f))
            }
            .padding(20.dp),
    ) {
        AnimatedContent(
            targetState = rec as? RecorderState.Active,
            contentKey = { it != null },
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            label = "status",
        ) { active ->
            if (active != null) {
                ActiveStatus(active, level, recordedMs, onHero, onStop, onPause, onResume, onConfirm, onDecline)
            } else {
                IdleStatus(settings, onHero, onToggleAuto)
            }
        }
    }
}

@Composable
private fun IdleStatus(settings: AppSettings, onHero: Color, onToggleAuto: (Boolean) -> Unit) {
    val context = LocalContext.current
    val phoneLabel = stringResource(R.string.source_phone)
    val summary = remember(settings, phoneLabel) {
        buildList {
            if (settings.recordPhoneCalls) add(phoneLabel)
            if (settings.recordVoip) {
                VoipApps.installed(context).filter { settings.isAppEnabled(it.packageName) }
                    .map { it.label }.distinct().forEach(::add)
            }
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(onHero.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (settings.autoRecord) Icons.Rounded.Shield else Icons.Rounded.VoiceOverOff,
                    null, tint = onHero, modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (settings.autoRecord) R.string.status_auto_on else R.string.status_auto_off),
                    style = MaterialTheme.typography.titleMedium, color = onHero,
                )
                Text(
                    stringResource(if (settings.autoRecord) R.string.status_auto_on_hint else R.string.status_auto_off_hint),
                    style = MaterialTheme.typography.bodySmall, color = onHero.copy(alpha = 0.8f),
                )
            }
            Switch(
                checked = settings.autoRecord,
                onCheckedChange = onToggleAuto,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LocalExtraColors.current.heroStart,
                    checkedTrackColor = onHero,
                    checkedBorderColor = onHero,
                    uncheckedThumbColor = onHero,
                    uncheckedTrackColor = onHero.copy(alpha = 0.2f),
                    uncheckedBorderColor = onHero.copy(alpha = 0.6f),
                ),
            )
        }
        if (settings.autoRecord && summary.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            val shown = summary.take(4)
            val more = summary.size - shown.size
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { HeroChip(it, onHero) }
                if (more > 0) HeroChip("+$more", onHero)
            }
            Spacer(Modifier.height(8.dp))
            HeroChip(
                stringResource(if (settings.recordMode == RecordMode.ASK) R.string.mode_ask_short else R.string.mode_always_short),
                onHero,
            )
        }
    }
}

@Composable
private fun HeroChip(text: String, onHero: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = onHero,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(onHero.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun ActiveStatus(
    active: RecorderState.Active,
    level: StateFlow<Float>,
    recordedMs: () -> Long,
    onHero: Color,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    val extra = LocalExtraColors.current
    val history = remember { mutableStateListOf<Float>().apply { repeat(40) { add(0f) } } }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active.sessionId) {
        var smoothed = 0f
        while (true) {
            smoothed = maxOf(level.value, smoothed * 0.6f)
            history.removeAt(0)
            history.add(if (active.paused) 0f else smoothed)
            elapsed = recordedMs()
            delay(70)
        }
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val dot by pulse.animateFloat(0.55f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot")

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .scale(if (active.paused) 1f else dot)
                    .clip(CircleShape)
                    .background(if (active.paused) onHero.copy(alpha = 0.5f) else extra.record),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(
                    when {
                        active.awaitingConfirmation -> R.string.status_asking
                        active.paused -> R.string.status_paused
                        else -> R.string.status_recording
                    },
                ),
                style = MaterialTheme.typography.labelLarge, color = onHero,
            )
            Spacer(Modifier.weight(1f))
            Text(
                Formatters.position(elapsed),
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                color = onHero,
            )
        }
        Spacer(Modifier.height(6.dp))
        val who = active.title ?: stringResource(
            when (active.info.source) {
                CallSource.MANUAL -> R.string.source_manual
                else -> R.string.unknown_caller
            },
        )
        Text(
            listOfNotNull(who, active.appLabel).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
            color = onHero.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        LevelBars(history, onHero, Modifier.fillMaxWidth().height(44.dp))
        Spacer(Modifier.height(16.dp))
        if (active.awaitingConfirmation) {
            Text(
                stringResource(R.string.notif_ask_hint),
                style = MaterialTheme.typography.bodySmall,
                color = onHero.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onDecline,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = onHero.copy(alpha = 0.18f), contentColor = onHero),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Close, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_dont_record))
                }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = extra.record, contentColor = extra.onRecord),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.FiberManualRecord, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_record))
                }
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = if (active.paused) onResume else onPause,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = onHero.copy(alpha = 0.18f), contentColor = onHero),
                modifier = Modifier.weight(1f),
            ) {
                Icon(if (active.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (active.paused) R.string.action_resume else R.string.action_pause))
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = extra.record, contentColor = extra.onRecord),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Stop, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_stop_save))
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Setup reminder, stats, search, filters
// -------------------------------------------------------------------------------------------

@Composable
private fun SetupCard(setup: SetupStatus, onOpenSetup: () -> Unit) {
    val essential = setup.essentialMissing > 0
    val bg = if (essential) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (essential) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        onClick = onOpenSetup,
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (essential) R.string.setup_card_essential else R.string.setup_card_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    pluralStringResource(R.plurals.setup_steps_left, setup.missingCount, setup.missingCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(R.string.action_fix), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatsRow(state: HomeUiState) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile(Icons.Rounded.GraphicEq, state.totalCount.toString(), pluralStringResource(R.plurals.recordings_count_label, state.totalCount), Modifier.weight(1f))
        StatTile(Icons.Rounded.Schedule, totalDuration(state.totalDurationMs), stringResource(R.string.stat_duration), Modifier.weight(1f))
        StatTile(Icons.Rounded.SdStorage, Formatters.fileSize(state.totalSize), stringResource(R.string.stat_storage), Modifier.weight(1f))
    }
}

/** "45 сек", "21 мин", "3 ч 05 мин" — a total, not a clock time. */
@Composable
private fun totalDuration(ms: Long): String {
    val totalMin = ms / 60_000
    return when {
        totalMin >= 60 -> stringResource(R.string.duration_hm, totalMin / 60, totalMin % 60)
        totalMin >= 1 -> stringResource(R.string.duration_m, totalMin)
        else -> stringResource(R.string.duration_s, ms / 1000)
    }
}

@Composable
private fun StatTile(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(52.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_clear)) }
            }
        }
    }
}

@Composable
private fun FilterRow(filters: List<HomeFilter>, selected: HomeFilter, onSelect: (HomeFilter) -> Unit) {
    val context = LocalContext.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        items(filters, key = { it.toString() }) { f ->
            val label = when (f) {
                HomeFilter.All -> stringResource(R.string.filter_all)
                HomeFilter.Favorites -> stringResource(R.string.filter_favorites)
                HomeFilter.Phone -> stringResource(R.string.source_phone)
                HomeFilter.Manual -> stringResource(R.string.filter_manual)
                is HomeFilter.App -> VoipApps.label(context, f.packageName) ?: f.packageName
            }
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text(label) },
                shape = CircleShape,
                leadingIcon = {
                    when (f) {
                        HomeFilter.All -> if (f == selected) Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize))
                        HomeFilter.Favorites -> Icon(Icons.Rounded.Star, null, Modifier.size(FilterChipDefaults.IconSize))
                        HomeFilter.Phone -> Icon(Icons.Rounded.Call, null, Modifier.size(FilterChipDefaults.IconSize))
                        HomeFilter.Manual -> Icon(Icons.Rounded.Mic, null, Modifier.size(FilterChipDefaults.IconSize))
                        is HomeFilter.App -> rememberAppIcon(f.packageName)?.let {
                            Image(it, null, Modifier.size(FilterChipDefaults.IconSize).clip(CircleShape))
                        }
                    }
                },
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// List
// -------------------------------------------------------------------------------------------

@Composable
private fun DayHeader(day: LocalDate, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> {
            val pattern = if (day.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"
            day.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun rememberTimeFormatter(): DateTimeFormatter {
    val context = LocalContext.current
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    return remember(is24h) { DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault()) }
}

fun Recording.displayTitle(unknown: String, manual: String): String =
    title ?: if (source == CallSource.MANUAL) manual else unknown

@Composable
private fun RecordingRow(
    r: Recording,
    index: Int,
    count: Int,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val timeFormatter = rememberTimeFormatter()
    val shape = segmentShape(index, count)
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            // The swipe state is saved per list key; reset it so that "Undo" brings the row back intact.
            dismiss.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    val containerColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "rowColor",
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !selectionMode,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 1.dp),
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Surface(
            color = containerColor,
            shape = shape,
            modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    CallerAvatar(r.contactName, r.source, r.appPackage)
                    androidx.compose.animation.AnimatedVisibility(selected, enter = scaleIn(), exit = scaleOut()) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.displayTitle(stringResource(R.string.unknown_caller), stringResource(R.string.source_manual)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (!r.listened) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!r.listened) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DirectionIcon(r.direction, r.source)
                        val time = java.time.Instant.ofEpochMilli(r.startedAt).atZone(java.time.ZoneId.systemDefault()).format(timeFormatter)
                        val parts = listOfNotNull(
                            time,
                            Formatters.duration(r.durationMs),
                            if (r.source == CallSource.VOIP) VoipApps.label(context, r.appPackage) else null,
                        )
                        Text(
                            parts.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (r.silent) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.WarningAmber, stringResource(R.string.silent_warning), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                IconButton(onClick = onToggleFavorite, enabled = !selectionMode) {
                    Icon(
                        if (r.favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        stringResource(if (r.favorite) R.string.action_unfavorite else R.string.action_favorite),
                        tint = if (r.favorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
fun DirectionIcon(direction: CallDirection, source: CallSource, size: androidx.compose.ui.unit.Dp = 16.dp) {
    val extra = LocalExtraColors.current
    val (icon, tint) = when {
        source == CallSource.MANUAL -> Icons.Rounded.Mic to MaterialTheme.colorScheme.onSurfaceVariant
        direction == CallDirection.INCOMING -> Icons.AutoMirrored.Rounded.CallReceived to extra.incoming
        direction == CallDirection.OUTGOING -> Icons.AutoMirrored.Rounded.CallMade to extra.outgoing
        else -> Icons.Rounded.Call to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, null, tint = tint, modifier = Modifier.size(size))
    Spacer(Modifier.width(6.dp))
}
