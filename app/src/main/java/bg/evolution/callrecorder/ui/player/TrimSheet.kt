package bg.evolution.callrecorder.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.automirrored.rounded.LastPage
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.evolution.callrecorder.R
import bg.evolution.callrecorder.audio.Trimmer
import bg.evolution.callrecorder.audio.WaveformBuilder
import bg.evolution.callrecorder.core.Formatters
import bg.evolution.callrecorder.data.db.Recording

/** Choose the part of a recording to keep, listen to it, and save it. */
@Composable
fun TrimSheet(vm: PlayerViewModel, r: Recording, onDismiss: () -> Unit) {
    val state by vm.trimState.collectAsStateWithLifecycle()
    val working = state is PlayerViewModel.TrimState.Working
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // Saving can't be interrupted by swiping the sheet away.
        confirmValueChange = { it != SheetValue.Hidden || vm.trimState.value !is PlayerViewModel.TrimState.Working },
    )
    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() }, sheetState = sheetState) {
        TrimContent(vm, r)
    }
}

@Composable
fun TrimContent(vm: PlayerViewModel, r: Recording) {
    val ps by vm.player.state.collectAsStateWithLifecycle()
    val state by vm.trimState.collectAsStateWithLifecycle()
    val working = state is PlayerViewModel.TrimState.Working
    val total = (ps.durationMs.takeIf { it > 0 } ?: r.durationMs).coerceAtLeast(1)
    val minFraction = (Trimmer.MIN_LENGTH_MS.toFloat() / total).coerceAtMost(1f)

    var start by rememberSaveable { mutableFloatStateOf(0f) }
    var end by rememberSaveable { mutableFloatStateOf(1f) }
    var confirmReplace by remember { mutableStateOf(false) }
    val startMs = (start * total).toLong()
    val endMs = (end * total).toLong()

    // The preview stops at the end of the selection.
    LaunchedEffect(ps.playing, ps.positionMs >= endMs) {
        if (ps.playing && ps.positionMs >= endMs) {
            vm.player.pause()
            vm.player.seekTo(startMs)
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Text(stringResource(R.string.trim_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.trim_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        TrimWaveform(
            waveform = r.waveform,
            start = start,
            end = end,
            progress = ps.positionMs.toFloat() / total,
            onSeek = { f -> if (!working) vm.player.seekTo((f * total).toLong()) },
            modifier = Modifier.fillMaxWidth().height(96.dp),
        )
        RangeSlider(
            value = start..end,
            onValueChange = { v ->
                if (v.endInclusive - v.start >= minFraction) {
                    start = v.start
                    end = v.endInclusive
                }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        )
        // Start and end times with the preview button between them.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TimeLabel(stringResource(R.string.trim_start), Formatters.position(startMs), Alignment.Start, Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = {
                    if (ps.playing) {
                        vm.player.pause()
                    } else {
                        if (ps.positionMs < startMs || ps.positionMs >= endMs - 200) vm.player.seekTo(startMs)
                        vm.player.play()
                    }
                },
                enabled = !working && !ps.error,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    if (ps.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (ps.playing) R.string.action_pause else R.string.trim_preview),
                    modifier = Modifier.size(28.dp),
                )
            }
            TimeLabel(stringResource(R.string.trim_end), Formatters.position(endMs), Alignment.End, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.trim_kept, Formatters.position(endMs - startMs)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        // Put an end at the place being heard: two equal buttons, side by side.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val icons = maxWidth >= 300.dp
            val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { start = (ps.positionMs.toFloat() / total).coerceIn(0f, end - minFraction) },
                    enabled = !working,
                    contentPadding = padding,
                    modifier = Modifier.weight(1f),
                ) {
                    if (icons) {
                        Icon(Icons.Rounded.FirstPage, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.trim_set_start), maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = { end = (ps.positionMs.toFloat() / total).coerceIn(start + minFraction, 1f) },
                    enabled = !working,
                    contentPadding = padding,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.trim_set_end), maxLines = 1, softWrap = false)
                    if (icons) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.LastPage, null, Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        val unchanged = start <= 0f && end >= 1f
        Button(
            onClick = { vm.trim(startMs, endMs, replace = false) },
            enabled = !working && !unchanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            if ((state as? PlayerViewModel.TrimState.Working)?.replace == false) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.SaveAs, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.trim_save_new))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { confirmReplace = true },
            enabled = !working && !unchanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            if ((state as? PlayerViewModel.TrimState.Working)?.replace == true) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.ContentCut, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.trim_replace))
        }
        if (state is PlayerViewModel.TrimState.Failed) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.trim_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            icon = { Icon(Icons.Rounded.ContentCut, null) },
            title = { Text(stringResource(R.string.trim_replace_title)) },
            text = { Text(stringResource(R.string.trim_replace_text)) },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; vm.trim(startMs, endMs, replace = true) }) {
                    Text(stringResource(R.string.trim_replace))
                }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun TimeLabel(label: String, time: String, align: Alignment.Horizontal, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = align) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

/** The waveform with the kept part in colour, the rest faded, and the place being heard. */
@Composable
private fun TrimWaveform(
    waveform: ByteArray?,
    start: Float,
    end: Float,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kept = MaterialTheme.colorScheme.primary
    val cut = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val selection = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val playhead = MaterialTheme.colorScheme.tertiary
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
                .pointerInput(Unit) { detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) } },
        ) {
            val step = size.width / count
            val bw = (step - gapPx).coerceAtLeast(1f)
            val x0 = size.width * start
            val x1 = size.width * end
            drawRoundRect(selection, Offset(x0, 0f), Size(x1 - x0, size.height), CornerRadius(8.dp.toPx()))
            bars.forEachIndexed { i, v ->
                val h = (size.height * 0.86f * v).coerceAtLeast(bw)
                val x = i * step
                val center = x + bw / 2
                drawRoundRect(
                    color = if (center in x0..x1) kept else cut,
                    topLeft = Offset(x, (size.height - h) / 2f),
                    size = Size(bw, h),
                    cornerRadius = CornerRadius(bw / 2f),
                )
            }
            val edge = 3.dp.toPx()
            listOf(x0, x1).forEach { x ->
                drawRoundRect(
                    kept,
                    Offset((x - edge / 2).coerceIn(0f, size.width - edge), 0f),
                    Size(edge, size.height),
                    CornerRadius(edge / 2),
                )
            }
            val px = size.width * progress.coerceIn(0f, 1f)
            drawRoundRect(playhead, Offset((px - 1f).coerceIn(0f, size.width - 2f), 0f), Size(2f, size.height), CornerRadius(1f))
        }
    }
}
