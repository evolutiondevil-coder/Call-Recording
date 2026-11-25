package bg.evolution.callrecorder.ui.player

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.evolution.callrecorder.R
import bg.evolution.callrecorder.audio.WaveformBuilder
import bg.evolution.callrecorder.core.CallDirection
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.core.Formatters
import bg.evolution.callrecorder.data.db.Recording
import bg.evolution.callrecorder.detect.VoipApps
import bg.evolution.callrecorder.ui.components.CallerAvatar
import bg.evolution.callrecorder.ui.components.Pill
import bg.evolution.callrecorder.ui.home.displayTitle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

@Composable
fun PlayerScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val load by vm.load.collectAsStateWithLifecycle()
    var closed by remember { mutableStateOf(false) }
    LaunchedEffect(load) {
        if (load is PlayerViewModel.Load.Missing && !closed) {
            closed = true
            onBack()
        }
    }
    val r = (load as? PlayerViewModel.Load.Found)?.recording
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    PlayerContent(vm, r, onBack)
}

@Composable
private fun PlayerContent(vm: PlayerViewModel, r: Recording, onBack: () -> Unit) {
    val context = LocalContext.current
    val ps by vm.player.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var excluding by remember { mutableStateOf(false) }

    val exportOk = stringResource(R.string.export_done)
    val exportFail = stringResource(R.string.export_failed)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(r.format.mimeType)) { uri ->
        if (uri != null) vm.export(uri) { ok -> Toast.makeText(context, if (ok) exportOk else exportFail, Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                title = {},
                actions = {
                    IconButton(onClick = vm::toggleFavorite) {
                        Icon(
                            if (r.favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                            stringResource(if (r.favorite) R.string.action_unfavorite else R.string.action_favorite),
                            tint = if (r.favorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.shareIntent()?.let { runCatching { context.startActivity(it) } } }) {
                        Icon(Icons.Rounded.Share, stringResource(R.string.action_share))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rename)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { menu = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_save_as)) },
                                leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) },
                                onClick = { menu = false; exportLauncher.launch(r.fileName) },
                            )
                            if (r.phoneNumber != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_call_back)) },
                                    leadingIcon = { Icon(Icons.Rounded.Call, null) },
                                    onClick = {
                                        menu = false
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + r.phoneNumber)))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_never_record)) },
                                    leadingIcon = { Icon(Icons.Rounded.Block, null) },
                                    onClick = { menu = false; excluding = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(r)
            Spacer(Modifier.height(28.dp))

            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    val progress = if (ps.durationMs > 0) ps.positionMs.toFloat() / ps.durationMs else 0f
                    WaveformSeekBar(
                        waveform = r.waveform,
                        progress = progress,
                        onSeek = { f -> vm.player.seekTo((f * ps.durationMs).toLong()) },
                        modifier = Modifier.fillMaxWidth().height(88.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(Formatters.position(ps.positionMs), style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace))
                        Spacer(Modifier.weight(1f))
                        // Playback speed sits between the times, so the controls below have room on small phones.
                        TextButton(
                            onClick = {
                                val i = speeds.indexOf(ps.speed)
                                vm.player.setSpeed(speeds[(i + 1) % speeds.size])
                            },
                            enabled = !ps.error,
                        ) {
                            Icon(Icons.Rounded.Speed, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(formatSpeed(ps.speed), maxLines = 1, softWrap = false)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            Formatters.position(ps.durationMs),
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Controls(
                        playing = ps.playing,
                        enabled = !ps.error,
                        onToggle = vm.player::toggle,
                        onBack10 = { vm.player.skip(-10_000) },
                        onFwd10 = { vm.player.skip(10_000) },
                    )
                    Spacer(Modifier.height(12.dp))
                    FilterChip(
                        selected = ps.earpiece,
                        onClick = { vm.player.setEarpiece(!ps.earpiece) },
                        enabled = !ps.error,
                        label = { Text(stringResource(R.string.player_earpiece), maxLines = 1, softWrap = false) },
                        leadingIcon = {
                            Icon(
                                if (ps.earpiece) Icons.Rounded.PhoneInTalk else Icons.AutoMirrored.Rounded.VolumeUp,
                                null, Modifier.size(FilterChipDefaults.IconSize),
                            )
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    if (ps.error) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.player_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (r.silent) {
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.problem_silent_text), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            NoteCard(r.note, vm::saveNote)
            Spacer(Modifier.height(16.dp))
            InfoCard(r)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (excluding) {
        AlertDialog(
            onDismissRequest = { excluding = false },
            icon = { Icon(Icons.Rounded.Block, null) },
            title = { Text(stringResource(R.string.exclude_title)) },
            text = { Text(stringResource(R.string.exclude_text, r.phoneNumber ?: "")) },
            confirmButton = {
                TextButton(onClick = { excluding = false; vm.excludeNumber() }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { excluding = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (renaming) {
        var name by rememberSaveable { mutableStateOf(r.contactName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            icon = { Icon(Icons.Rounded.Edit, null) },
            title = { Text(stringResource(R.string.rename_title)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.rename_label)) },
                )
            },
            confirmButton = {
                TextButton(onClick = { renaming = false; vm.rename(name) }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Header(r: Recording) {
    val context = LocalContext.current
    Spacer(Modifier.height(8.dp))
    CallerAvatar(r.contactName, r.source, r.appPackage, size = 104.dp)
    Spacer(Modifier.height(16.dp))
    Text(
        r.displayTitle(stringResource(R.string.unknown_caller), stringResource(R.string.source_manual)),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (r.contactName != null && r.phoneNumber != null) {
        Text(r.phoneNumber, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(12.dp))
    val directionText = when {
        r.source == CallSource.MANUAL -> stringResource(R.string.source_manual)
        r.direction == CallDirection.INCOMING -> stringResource(R.string.direction_incoming)
        r.direction == CallDirection.OUTGOING -> stringResource(R.string.direction_outgoing)
        else -> stringResource(R.string.direction_unknown)
    }
    val sourceText = when (r.source) {
        CallSource.PHONE -> stringResource(R.string.source_phone)
        CallSource.VOIP -> VoipApps.label(context, r.appPackage) ?: stringResource(R.string.source_voip)
        CallSource.MANUAL -> null
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(directionText)
        if (sourceText != null) Pill(sourceText)
    }
    Spacer(Modifier.height(8.dp))
    val dt = Instant.ofEpochMilli(r.startedAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).withLocale(Locale.getDefault()))
    Text(dt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Controls(
    playing: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onBack10: () -> Unit,
    onFwd10: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onBack10, enabled = enabled, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Rounded.Replay10, stringResource(R.string.action_back10))
        }
        FilledIconButton(
            onClick = onToggle,
            enabled = enabled,
            modifier = Modifier.size(76.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            AnimatedContent(playing, transitionSpec = { scaleIn() togetherWith scaleOut() }, label = "play") { p ->
                Icon(
                    if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (p) R.string.action_pause else R.string.action_play),
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        FilledTonalIconButton(onClick = onFwd10, enabled = enabled, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Rounded.Forward10, stringResource(R.string.action_fwd10))
        }
    }
}

private fun formatSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}×" else String.format(Locale.ROOT, "%.2f", s).trimEnd('0') + "×"

@Composable
fun WaveformSeekBar(waveform: ByteArray?, progress: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val shown = if (dragFraction >= 0f) dragFraction else progress
    val animated by animateFloatAsState(shown.coerceIn(0f, 1f), label = "progress")
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val barWidthPx = with(density) { 3.dp.toPx() }
        val gapPx = with(density) { 2.dp.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val count = ((widthPx + gapPx) / (barWidthPx + gapPx)).toInt().coerceAtLeast(10)
        val bars = remember(waveform, count) { WaveformBuilder.bars(waveform, count) }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            if (dragFraction >= 0f) onSeek(dragFraction)
                            dragFraction = -1f
                        },
                        onDragCancel = { dragFraction = -1f },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            val step = size.width / count
            val bw = (step - gapPx).coerceAtLeast(1f)
            val playedX = size.width * (if (dragFraction >= 0f) shown else animated)
            bars.forEachIndexed { i, v ->
                val h = (size.height * v).coerceAtLeast(bw)
                val x = i * step
                drawRoundRect(
                    color = if (x + bw / 2 <= playedX) played else rest,
                    topLeft = Offset(x, (size.height - h) / 2f),
                    size = Size(bw, h),
                    cornerRadius = CornerRadius(bw / 2f),
                )
            }
            // Playhead
            drawRoundRect(
                color = played,
                topLeft = Offset((playedX - 1.5f).coerceIn(0f, size.width - 3f), 0f),
                size = Size(3f, size.height),
                cornerRadius = CornerRadius(1.5f),
            )
        }
    }
}

@Composable
private fun NoteCard(note: String?, onSave: (String) -> Unit) {
    var text by rememberSaveable(note) { mutableStateOf(note.orEmpty()) }
    val changed = text.trim() != note.orEmpty()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.Notes, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.note_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (changed) {
                    TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.note_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}

@Composable
private fun InfoCard(r: Recording) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(stringResource(R.string.info_format), r.format.label)
            InfoRow(stringResource(R.string.info_duration), Formatters.duration(r.durationMs))
            InfoRow(stringResource(R.string.info_size), Formatters.fileSize(r.sizeBytes))
            InfoRow(stringResource(R.string.info_file), r.fileName)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
