package bg.evolution.callrecorder.ui.setup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import bg.evolution.callrecorder.core.RecordMode
import bg.evolution.callrecorder.ui.settings.recordModeDescription
import bg.evolution.callrecorder.ui.settings.recordModeLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import bg.evolution.callrecorder.R
import bg.evolution.callrecorder.ui.theme.LocalExtraColors

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun SetupScreen(
    onboarding: Boolean,
    recordMode: RecordMode,
    onRecordMode: (RecordMode) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(Permissions.status(context)) }
    LifecycleResumeEffect(Unit) {
        status = Permissions.status(context)
        onPauseOrDispose { }
    }
    var askedRuntime by rememberSaveable { mutableStateOf(false) }
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedRuntime = true
        status = Permissions.status(context)
    }
    val restrictedHint = Build.VERSION.SDK_INT >= 33

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        if (!onboarding) {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
            }
        } else {
            Spacer(Modifier.height(24.dp))
        }
        Hero(onboarding)
        Spacer(Modifier.height(24.dp))
        ModeCard(recordMode, onRecordMode)
        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StepCard(
                number = 1,
                icon = Icons.Rounded.Mic,
                title = stringResource(R.string.step_permissions_title),
                text = stringResource(R.string.step_permissions_text),
                done = status.runtimeGranted,
                required = true,
                action = stringResource(R.string.action_allow),
                onAction = {
                    val activity = context.findActivity()
                    val missing = Permissions.runtime.filter { !Permissions.granted(context, it) }
                    val blocked = askedRuntime && activity != null &&
                        missing.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
                    if (blocked) Permissions.launchFirst(context, listOf(Permissions.appDetailsIntent(context)))
                    else runtimeLauncher.launch(Permissions.runtime)
                },
            )
            StepCard(
                number = 2,
                icon = Icons.Rounded.AccessibilityNew,
                title = stringResource(R.string.step_accessibility_title),
                text = stringResource(
                    if (status.accessibilityRequired) R.string.step_accessibility_text else R.string.step_accessibility_text_old,
                ),
                done = status.accessibility,
                required = status.accessibilityRequired,
                action = stringResource(R.string.action_enable),
                onAction = { Permissions.launchFirst(context, Permissions.accessibilitySettingsIntents(context)) },
                hint = if (restrictedHint) stringResource(R.string.restricted_settings_hint) else null,
                onHint = { Permissions.launchFirst(context, listOf(Permissions.appDetailsIntent(context))) },
            )
            StepCard(
                number = 3,
                icon = Icons.Rounded.NotificationsActive,
                title = stringResource(R.string.step_notifications_title),
                text = stringResource(R.string.step_notifications_text),
                done = status.notificationAccess,
                required = false,
                action = stringResource(R.string.action_allow),
                onAction = { Permissions.launchFirst(context, Permissions.notificationAccessIntents(context)) },
                hint = if (restrictedHint) stringResource(R.string.restricted_settings_hint) else null,
                onHint = { Permissions.launchFirst(context, listOf(Permissions.appDetailsIntent(context))) },
            )
            StepCard(
                number = 4,
                icon = Icons.Rounded.BatteryChargingFull,
                title = stringResource(R.string.step_battery_title),
                text = stringResource(R.string.step_battery_text),
                done = status.batteryUnrestricted,
                required = false,
                action = stringResource(R.string.action_allow),
                onAction = { Permissions.launchFirst(context, Permissions.batteryIntents(context)) },
            )
        }

        Spacer(Modifier.height(16.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.padding(16.dp)) {
                Icon(Icons.Rounded.Gavel, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.legal_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(if (onboarding) R.string.setup_start else R.string.action_done))
        }
        if (onboarding && !status.allDone) {
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.setup_later))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Hero(onboarding: Boolean) {
    val extra = LocalExtraColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Brush.linearGradient(listOf(extra.heroStart, extra.heroEnd))),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, modifier = Modifier.size(96.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(if (onboarding) R.string.setup_welcome else R.string.setup_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ModeCard(mode: RecordMode, onSelect: (RecordMode) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(stringResource(R.string.set_record_mode), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            RecordMode.entries.forEach { m ->
                val selected = m == mode
                val border by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    label = "modeBorder",
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(20.dp))
                        .selectable(selected = selected, role = Role.RadioButton) { onSelect(m) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(recordModeLabel(m), style = MaterialTheme.typography.bodyLarge)
                        Text(recordModeDescription(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.mode_change_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    icon: ImageVector,
    title: String,
    text: String,
    done: Boolean,
    required: Boolean,
    action: String,
    onAction: () -> Unit,
    hint: String? = null,
    onHint: (() -> Unit)? = null,
) {
    val container by animateColorAsState(
        if (done) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "step",
    )
    Surface(color = container, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimary)
                    else Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (required) R.string.step_required else R.string.step_recommended, number),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (required && !done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
            AnimatedVisibility(!done) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = onAction) { Text(action) }
                        if (hint != null && onHint != null) {
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = onHint) { Text(stringResource(R.string.action_app_info)) }
                        }
                    }
                    if (hint != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (done) {
                Text(
                    stringResource(R.string.step_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 58.dp),
                )
            }
        }
    }
}
