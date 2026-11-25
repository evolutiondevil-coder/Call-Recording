package bg.evolution.callrecorder.ui.settings

import android.os.Build
import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.Password
import androidx.lifecycle.compose.LifecycleResumeEffect
import bg.evolution.callrecorder.security.AppLock
import bg.evolution.callrecorder.security.Biometrics
import bg.evolution.callrecorder.ui.lock.PinFlow
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material3.FilledTonalButton
import bg.evolution.callrecorder.core.PhoneNumbers
import bg.evolution.callrecorder.core.SelectedContact
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.ui.platform.LocalContext
import bg.evolution.callrecorder.core.AppLocale
import bg.evolution.callrecorder.ui.components.AppLogo
import bg.evolution.callrecorder.ui.components.UpdateCard
import bg.evolution.callrecorder.ui.components.isWorthShowing
import bg.evolution.callrecorder.ui.components.message
import bg.evolution.callrecorder.update.UpdateState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.FiberSmartRecord
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.evolution.callrecorder.BuildConfig
import bg.evolution.callrecorder.R
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.AudioSourceOption
import bg.evolution.callrecorder.core.Formatters
import bg.evolution.callrecorder.core.Quality
import bg.evolution.callrecorder.core.RecordFilterMode
import bg.evolution.callrecorder.core.RecordMode
import bg.evolution.callrecorder.core.ThemeMode

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenPin: (PinFlow) -> Unit,
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val lock by vm.lock.config.collectAsStateWithLifecycle()
    val formats by vm.formats.collectAsStateWithLifecycle()
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    val totalSize by vm.totalSize.collectAsStateWithLifecycle()
    val update by vm.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLocale.current(context)) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.data?.let(vm::addSelectedFromPicker)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setStorageFolder(uri)
    }
    val voipSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    // A fingerprint may have been added in the phone's settings meanwhile.
    var biometricAvailable by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        biometricAvailable = Biometrics.canUseBiometric(context)
        onPauseOrDispose {}
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Group(stringResource(R.string.group_recording)) {
                    SwitchItem(Icons.Rounded.PowerSettingsNew, stringResource(R.string.set_auto_record), stringResource(R.string.set_auto_record_sub), s.autoRecord, onChange = vm::setAutoRecord)
                    Divider()
                    ClickItem(Icons.Rounded.FiberSmartRecord, stringResource(R.string.set_record_mode), recordModeLabel(s.recordMode), enabled = s.autoRecord) { dialog = "mode" }
                    Divider()
                    SwitchItem(Icons.Rounded.Call, stringResource(R.string.set_phone_calls), stringResource(R.string.set_phone_calls_sub), s.recordPhoneCalls, enabled = s.autoRecord, onChange = vm::setRecordPhone)
                    Divider()
                    SwitchItem(
                        Icons.Rounded.Wifi, stringResource(R.string.set_voip_calls),
                        stringResource(if (voipSupported) R.string.set_voip_calls_sub else R.string.set_voip_unsupported),
                        s.recordVoip && voipSupported, enabled = s.autoRecord && voipSupported, onChange = vm::setRecordVoip,
                    )
                    Divider()
                    val enabledCount = apps.count { s.isAppEnabled(it.packageName) }
                    ClickItem(
                        Icons.Rounded.Apps, stringResource(R.string.set_apps),
                        if (apps.isEmpty()) stringResource(R.string.set_apps_none) else stringResource(R.string.set_apps_sub, enabledCount, apps.size),
                        enabled = s.recordVoip && voipSupported, onClick = onOpenApps,
                    )
                }
            }
            item {
                Group(stringResource(R.string.group_filters)) {
                    ClickItem(Icons.Rounded.People, stringResource(R.string.set_filter_mode), filterLabel(s.filterMode)) { dialog = "filter" }
                    Divider()
                    if (s.filterMode == RecordFilterMode.SELECTED_ONLY) {
                        val count = s.selected.size
                        ClickItem(
                            Icons.Rounded.HowToReg, stringResource(R.string.set_selected),
                            if (count == 0) stringResource(R.string.selected_none_warning)
                            else pluralStringResource(R.plurals.contacts_count, count, count),
                        ) { dialog = "selected" }
                        Divider()
                    }
                    ClickItem(
                        Icons.Rounded.Block, stringResource(R.string.set_excluded),
                        if (s.excludedNumbers.isEmpty()) stringResource(R.string.set_excluded_none)
                        else pluralStringResource(R.plurals.numbers_count, s.excludedNumbers.size, s.excludedNumbers.size),
                    ) { dialog = "excluded" }
                    Divider()
                    ClickItem(Icons.Rounded.Timer, stringResource(R.string.set_min_duration), minDurationLabel(s.minDurationSec)) { dialog = "min" }
                }
            }
            item {
                Group(stringResource(R.string.group_audio)) {
                    ClickItem(Icons.Rounded.GraphicEq, stringResource(R.string.set_format), s.format.label) { dialog = "format" }
                    Divider()
                    ClickItem(
                        Icons.Rounded.HighQuality, stringResource(R.string.set_quality),
                        if (s.format.supportsQuality) qualityLabel(s.quality) + " · " + qualityDetails(s.format, s.quality)
                        else qualityDetails(s.format, s.quality),
                        enabled = s.format.supportsQuality,
                    ) { dialog = "quality" }
                    Divider()
                    ClickItem(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.set_gain), gainLabel(s.gainDb)) { dialog = "gain" }
                    Divider()
                    ClickItem(Icons.Rounded.Mic, stringResource(R.string.set_source), sourceLabel(s.audioSource)) { dialog = "source" }
                }
            }
            item {
                Group(stringResource(R.string.group_storage)) {
                    val folderValid = vm.storageFolderValid(s.storageTreeUri)
                    val folder = if (s.storageTreeUri != null && folderValid) vm.storageFolderName(s.storageTreeUri) else null
                    ClickItem(
                        Icons.Rounded.Folder, stringResource(R.string.set_folder),
                        folder ?: stringResource(R.string.set_folder_internal),
                    ) { if (s.storageTreeUri == null) folderPicker.launch(null) else dialog = "folder" }
                    Divider()
                    ClickItem(Icons.Rounded.AutoDelete, stringResource(R.string.set_auto_delete), autoDeleteLabel(s.autoDeleteDays)) { dialog = "autodelete" }
                    Divider()
                    InfoItem(Icons.Rounded.SdStorage, stringResource(R.string.set_used_space), Formatters.fileSize(totalSize))
                }
            }
            item {
                Group(stringResource(R.string.group_security)) {
                    SwitchItem(
                        Icons.Rounded.Lock, stringResource(R.string.set_app_lock), stringResource(R.string.set_app_lock_sub),
                        lock.enabled, onChange = { on -> onOpenPin(if (on) PinFlow.ENABLE else PinFlow.DISABLE) },
                    )
                    if (lock.enabled) {
                        Divider()
                        SwitchItem(
                            Icons.Rounded.Fingerprint, stringResource(R.string.set_biometric),
                            stringResource(if (biometricAvailable) R.string.set_biometric_sub else R.string.set_biometric_unavailable),
                            lock.biometric && biometricAvailable, enabled = biometricAvailable, onChange = vm.lock::setBiometric,
                        )
                        Divider()
                        ClickItem(Icons.Rounded.LockClock, stringResource(R.string.set_lock_after), lockAfterLabel(lock.timeoutMs)) { dialog = "lockafter" }
                        Divider()
                        ClickItem(Icons.Rounded.Password, stringResource(R.string.set_change_pin), null) { onOpenPin(PinFlow.CHANGE) }
                    }
                }
            }
            item {
                Group(stringResource(R.string.group_notifications)) {
                    SwitchItem(Icons.Rounded.NotificationsActive, stringResource(R.string.set_notify_after), stringResource(R.string.set_notify_after_sub), s.notifyAfterCall, onChange = vm::setNotifyAfterCall)
                }
            }
            item {
                Group(stringResource(R.string.group_appearance)) {
                    ClickItem(Icons.Rounded.Language, stringResource(R.string.set_language), AppLocale.label(language, stringResource(R.string.language_system))) { dialog = "language" }
                    Divider()
                    ClickItem(Icons.Rounded.DarkMode, stringResource(R.string.set_theme), themeLabel(s.themeMode)) { dialog = "theme" }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Divider()
                        SwitchItem(Icons.Rounded.ColorLens, stringResource(R.string.set_dynamic_color), stringResource(R.string.set_dynamic_color_sub), s.dynamicColor, onChange = vm::setDynamicColor)
                    }
                }
            }
            if (vm.updates.supported) {
                item {
                    Group(stringResource(R.string.group_updates)) {
                        SwitchItem(Icons.Rounded.SystemUpdate, stringResource(R.string.set_auto_update), stringResource(R.string.set_auto_update_sub), s.autoUpdate, onChange = vm::setAutoUpdate)
                        Divider()
                        ClickItem(
                            Icons.Rounded.Refresh,
                            stringResource(R.string.set_check_update),
                            stringResource(R.string.set_version, vm.updates.currentVersion) + " · " + updateStatusText(update),
                            enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading,
                        ) { vm.updates.checkNow() }
                    }
                }
                if (update.isWorthShowing()) {
                    item { UpdateCard(update, onInstall = vm.updates::install, onRetry = vm.updates::checkNow, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            item {
                Group(stringResource(R.string.group_other)) {
                    ClickItem(Icons.Rounded.Tune, stringResource(R.string.set_permissions), stringResource(R.string.set_permissions_sub), onClick = onOpenSetup)
                    Divider()
                    ClickItem(Icons.Rounded.Info, stringResource(R.string.set_about), stringResource(R.string.set_about_sub, BuildConfig.VERSION_NAME)) { dialog = "about" }
                }
            }
        }
    }

    when (dialog) {
        "mode" -> ChoiceDialog(
            stringResource(R.string.set_record_mode), RecordMode.entries, s.recordMode, { recordModeLabel(it) },
            description = { recordModeDescription(it) },
            onSelect = vm::setRecordMode, onDismiss = { dialog = null },
        )
        "filter" -> ChoiceDialog(
            stringResource(R.string.set_filter_mode), RecordFilterMode.entries, s.filterMode, { filterLabel(it) },
            description = { if (it == RecordFilterMode.SELECTED_ONLY) stringResource(R.string.filter_mode_selected_desc) else null },
            onSelect = { m -> vm.setFilterMode(m); if (m == RecordFilterMode.SELECTED_ONLY && s.selected.isEmpty()) dialog = "selected" },
            onDismiss = { if (dialog == "filter") dialog = null },
        )
        "min" -> ChoiceDialog(
            stringResource(R.string.set_min_duration), listOf(0, 3, 5, 10, 30, 60), s.minDurationSec, { minDurationLabel(it) },
            onSelect = vm::setMinDuration, onDismiss = { dialog = null },
        )
        "format" -> ChoiceDialog(
            stringResource(R.string.set_format), formats, s.format, { it.label }, description = { formatDescription(it) },
            onSelect = vm::setFormat, onDismiss = { dialog = null },
        )
        "quality" -> ChoiceDialog(
            stringResource(R.string.set_quality), Quality.entries, s.quality, { qualityLabel(it) }, description = { qualityDetails(s.format, it) },
            onSelect = vm::setQuality, onDismiss = { dialog = null },
        )
        "source" -> ChoiceDialog(
            stringResource(R.string.set_source), AudioSourceOption.entries, s.audioSource, { sourceLabel(it) }, description = { sourceDescription(it) },
            onSelect = vm::setAudioSource, onDismiss = { dialog = null },
        )
        "autodelete" -> ChoiceDialog(
            stringResource(R.string.set_auto_delete), listOf(0, 7, 30, 90, 180, 365), s.autoDeleteDays, { autoDeleteLabel(it) },
            onSelect = vm::setAutoDelete, onDismiss = { dialog = null },
        )
        "language" -> ChoiceDialog(
            stringResource(R.string.set_language), AppLocale.options, language,
            { AppLocale.label(it, stringResource(R.string.language_system)) },
            onSelect = { lang ->
                if (lang != language) {
                    language = lang
                    // Saves the choice and recreates the screen so it changes immediately.
                    AppLocale.set(context, lang)
                }
            },
            onDismiss = { dialog = null },
        )
        "theme" -> ChoiceDialog(
            stringResource(R.string.set_theme), ThemeMode.entries, s.themeMode, { themeLabel(it) },
            onSelect = vm::setTheme, onDismiss = { dialog = null },
        )
        "lockafter" -> ChoiceDialog(
            stringResource(R.string.set_lock_after), AppLock.TIMEOUT_OPTIONS, lock.timeoutMs, { lockAfterLabel(it) },
            header = stringResource(R.string.lock_after_desc),
            onSelect = vm.lock::setTimeout, onDismiss = { dialog = null },
        )
        "gain" -> GainDialog(s.gainDb, onSave = vm::setGain, onDismiss = { dialog = null })
        "selected" -> SelectedDialog(
            s.selected.sortedBy { (it.name ?: it.number ?: "").lowercase() },
            onPick = { contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
            onAddNumber = { vm.addSelected(SelectedContact(PhoneNumbers.normalize(it), null)) },
            onRemove = vm::removeSelected,
            onDismiss = { dialog = null },
        )
        "excluded" -> ExcludedDialog(s.excludedNumbers.sorted(), onAdd = vm::addExcluded, onRemove = vm::removeExcluded, onDismiss = { dialog = null })
        "folder" -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Rounded.Folder, null) },
            title = { Text(stringResource(R.string.set_folder)) },
            text = { Text(stringResource(R.string.folder_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { dialog = null; folderPicker.launch(null) }) { Text(stringResource(R.string.folder_choose)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null; vm.setStorageFolder(null) }) { Text(stringResource(R.string.folder_internal)) }
            },
        )
        "about" -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { AppLogo(88.dp) },
            title = { Text(stringResource(R.string.app_name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.legal_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

// ---- labels ---------------------------------------------------------------------------------

@Composable
private fun updateStatusText(state: UpdateState): String = when (state) {
    is UpdateState.Checking -> stringResource(R.string.update_checking)
    is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateState.Downloading -> stringResource(R.string.update_downloading, (state.progress * 100).toInt())
    is UpdateState.ReadyToInstall -> stringResource(R.string.update_available, state.release.version)
    is UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.Failed -> stringResource(state.reason.message())
    UpdateState.Idle -> ""
}.ifEmpty { stringResource(R.string.set_check_update) }

@Composable
fun recordModeLabel(m: RecordMode) = stringResource(
    when (m) {
        RecordMode.ALWAYS -> R.string.mode_always
        RecordMode.ASK -> R.string.mode_ask
    },
)

@Composable
fun recordModeDescription(m: RecordMode) = stringResource(
    when (m) {
        RecordMode.ALWAYS -> R.string.mode_always_desc
        RecordMode.ASK -> R.string.mode_ask_desc
    },
)

@Composable
private fun filterLabel(m: RecordFilterMode) = stringResource(
    when (m) {
        RecordFilterMode.ALL -> R.string.filter_mode_all
        RecordFilterMode.CONTACTS_ONLY -> R.string.filter_mode_contacts
        RecordFilterMode.UNKNOWN_ONLY -> R.string.filter_mode_unknown
        RecordFilterMode.SELECTED_ONLY -> R.string.filter_mode_selected
    },
)

@Composable
private fun lockAfterLabel(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return if (minutes <= 0) stringResource(R.string.lock_after_immediately)
    else pluralStringResource(R.plurals.lock_after_minutes, minutes, minutes)
}

@Composable
private fun minDurationLabel(sec: Int) =
    if (sec == 0) stringResource(R.string.min_duration_none) else stringResource(R.string.seconds_short, sec)

@Composable
private fun autoDeleteLabel(days: Int) =
    if (days == 0) stringResource(R.string.auto_delete_never) else pluralStringResource(R.plurals.older_than_days, days, days)

@Composable
private fun qualityLabel(q: Quality) = stringResource(
    when (q) {
        Quality.LOW -> R.string.quality_low
        Quality.STANDARD -> R.string.quality_standard
        Quality.HIGH -> R.string.quality_high
    },
)

private fun qualityDetails(f: AudioFormat, q: Quality): String {
    val rate = f.sampleRate(q) / 1000.0
    val rateText = if (rate == rate.toInt().toDouble()) "${rate.toInt()} kHz" else "$rate kHz"
    val kbps = f.bitRate(q) / 1000.0
    val brText = if (kbps == kbps.toInt().toDouble()) "${kbps.toInt()} kbps" else "%.2f kbps".format(kbps)
    return "$rateText · $brText · ~${Formatters.fileSize(f.bytesPerMinute(q))}/min"
}

@Composable
private fun formatDescription(f: AudioFormat) = stringResource(
    when (f) {
        AudioFormat.M4A -> R.string.format_m4a_desc
        AudioFormat.MP3 -> R.string.format_mp3_desc
        AudioFormat.AMR_WB -> R.string.format_amrwb_desc
        AudioFormat.AMR_NB -> R.string.format_amrnb_desc
        AudioFormat.WAV -> R.string.format_wav_desc
    },
)

@Composable
private fun gainLabel(db: Int) = if (db == 0) stringResource(R.string.gain_off) else "+$db dB"

@Composable
private fun sourceLabel(s: AudioSourceOption) = stringResource(
    when (s) {
        AudioSourceOption.AUTO -> R.string.source_auto
        AudioSourceOption.VOICE_RECOGNITION -> R.string.source_voice_recognition
        AudioSourceOption.MIC -> R.string.source_mic
        AudioSourceOption.VOICE_COMMUNICATION -> R.string.source_voice_communication
        AudioSourceOption.VOICE_CALL -> R.string.source_voice_call
        AudioSourceOption.CAMCORDER -> R.string.source_camcorder
    },
)

@Composable
private fun sourceDescription(s: AudioSourceOption): String? = when (s) {
    AudioSourceOption.AUTO -> stringResource(R.string.source_auto_desc)
    AudioSourceOption.VOICE_CALL -> stringResource(R.string.source_voice_call_desc)
    else -> null
}

@Composable
private fun themeLabel(t: ThemeMode) = stringResource(
    when (t) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

// ---- building blocks --------------------------------------------------------------------------

@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 8.dp),
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(26.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), content = content)
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(start = 72.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun LeadingIcon(icon: ImageVector, enabled: Boolean) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 1f else 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingIcon(icon, enabled)
        Spacer(Modifier.width(16.dp))
        Texts(title, subtitle, enabled, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun ClickItem(icon: ImageVector, title: String, subtitle: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingIcon(icon, enabled)
        Spacer(Modifier.width(16.dp))
        Texts(title, subtitle, enabled, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else 0.3f))
    }
}

@Composable
private fun InfoItem(icon: ImageVector, title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingIcon(icon, true)
        Spacer(Modifier.width(16.dp))
        Texts(title, value, true, Modifier.weight(1f))
    }
}

@Composable
private fun Texts(title: String, subtitle: String?, enabled: Boolean, modifier: Modifier) {
    val alpha = if (enabled) 1f else 0.45f
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
    }
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    description: (@Composable (T) -> String?)? = null,
    header: String? = null,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (header != null) {
                    Text(header, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                options.forEach { o ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .selectable(selected = o == selected, role = Role.RadioButton) { onSelect(o); onDismiss() }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(label(o), style = MaterialTheme.typography.bodyLarge)
                            description?.invoke(o)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun GainDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(current.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) },
        title = { Text(stringResource(R.string.set_gain)) },
        text = {
            Column {
                Text(stringResource(R.string.gain_desc), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Text(gainLabel(value.toInt()), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..18f, steps = 5)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.toInt()); onDismiss() }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SelectedDialog(
    contacts: List<SelectedContact>,
    onPick: () -> Unit,
    onAddNumber: (String) -> Unit,
    onRemove: (SelectedContact) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.HowToReg, null) },
        title = { Text(stringResource(R.string.set_selected)) },
        text = {
            Column {
                Text(stringResource(R.string.selected_desc), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Contacts, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_pick_contact), maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.excluded_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onAddNumber(input); input = "" },
                        enabled = input.count(Char::isDigit) >= 3,
                    ) { Text(stringResource(R.string.action_add)) }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    contacts.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name ?: c.number.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                                if (c.name != null && c.number != null) {
                                    Text(c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { onRemove(c) }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

@Composable
private fun ExcludedDialog(numbers: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Block, null) },
        title = { Text(stringResource(R.string.set_excluded)) },
        text = {
            Column {
                Text(stringResource(R.string.excluded_desc), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.excluded_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onAdd(input); input = "" },
                        enabled = input.count(Char::isDigit) >= 3,
                    ) { Text(stringResource(R.string.action_add)) }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    numbers.forEach { n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemove(n) }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}
