package bg.evolution.callrecorder.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.AudioSourceOption
import bg.evolution.callrecorder.core.PhoneNumbers
import bg.evolution.callrecorder.core.Quality
import bg.evolution.callrecorder.core.RecordFilterMode
import bg.evolution.callrecorder.core.RecordMode
import bg.evolution.callrecorder.core.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

data class AppSettings(
    val autoRecord: Boolean = true,
    val recordMode: RecordMode = RecordMode.ALWAYS,
    val recordPhoneCalls: Boolean = true,
    val recordVoip: Boolean = true,
    /** Messenger packages the user switched off (new apps are recorded by default). */
    val disabledApps: Set<String> = emptySet(),
    /** Record calls of apps that aren't in the built-in list but post call notifications. */
    val recordOtherApps: Boolean = true,
    val format: AudioFormat = AudioFormat.M4A,
    val quality: Quality = Quality.STANDARD,
    val audioSource: AudioSourceOption = AudioSourceOption.AUTO,
    val gainDb: Int = 6,
    val filterMode: RecordFilterMode = RecordFilterMode.ALL,
    val excludedNumbers: Set<String> = emptySet(),
    val minDurationSec: Int = 3,
    val autoDeleteDays: Int = 0,
    val storageTreeUri: String? = null,
    val notifyAfterCall: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val onboardingDone: Boolean = false,
    /** Check GitHub for a new version on start and download it automatically. */
    val autoUpdate: Boolean = true,
) {
    fun isAppEnabled(pkg: String): Boolean = pkg !in disabledApps

    fun isNumberExcluded(number: String?): Boolean =
        number != null && excludedNumbers.any { PhoneNumbers.matches(it, number) }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val autoRecord = booleanPreferencesKey("auto_record")
        val recordMode = stringPreferencesKey("record_mode")
        val recordPhone = booleanPreferencesKey("record_phone")
        val recordVoip = booleanPreferencesKey("record_voip")
        val disabledApps = stringSetPreferencesKey("disabled_apps")
        val recordOtherApps = booleanPreferencesKey("record_other_apps")
        val format = stringPreferencesKey("format")
        val quality = stringPreferencesKey("quality")
        val audioSource = stringPreferencesKey("audio_source")
        val gainDb = intPreferencesKey("gain_db")
        val filterMode = stringPreferencesKey("filter_mode")
        val excluded = stringSetPreferencesKey("excluded_numbers")
        val minDuration = intPreferencesKey("min_duration")
        val autoDelete = intPreferencesKey("auto_delete_days")
        val treeUri = stringPreferencesKey("tree_uri")
        val notifyAfter = booleanPreferencesKey("notify_after_call")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val autoUpdate = booleanPreferencesKey("auto_update")
    }

    private val store = context.dataStore

    /** Always up-to-date settings; the first value is read synchronously so callers never see defaults by mistake. */
    val settings: StateFlow<AppSettings> = store.data
        .map(::fromPrefs)
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { fromPrefs(store.data.first()) })

    val current: AppSettings get() = settings.value

    private fun fromPrefs(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            autoRecord = p[K.autoRecord] ?: d.autoRecord,
            recordMode = enumOr(p[K.recordMode], d.recordMode),
            recordPhoneCalls = p[K.recordPhone] ?: d.recordPhoneCalls,
            recordVoip = p[K.recordVoip] ?: d.recordVoip,
            disabledApps = p[K.disabledApps] ?: d.disabledApps,
            recordOtherApps = p[K.recordOtherApps] ?: d.recordOtherApps,
            format = enumOr(p[K.format], d.format),
            quality = enumOr(p[K.quality], d.quality),
            audioSource = enumOr(p[K.audioSource], d.audioSource),
            gainDb = (p[K.gainDb] ?: d.gainDb).coerceIn(0, 18),
            filterMode = enumOr(p[K.filterMode], d.filterMode),
            excludedNumbers = p[K.excluded] ?: d.excludedNumbers,
            minDurationSec = p[K.minDuration] ?: d.minDurationSec,
            autoDeleteDays = p[K.autoDelete] ?: d.autoDeleteDays,
            storageTreeUri = p[K.treeUri],
            notifyAfterCall = p[K.notifyAfter] ?: d.notifyAfterCall,
            themeMode = enumOr(p[K.theme], d.themeMode),
            dynamicColor = p[K.dynamicColor] ?: d.dynamicColor,
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            autoUpdate = p[K.autoUpdate] ?: d.autoUpdate,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    suspend fun setAutoRecord(v: Boolean) = edit { it[K.autoRecord] = v }
    suspend fun setRecordMode(v: RecordMode) = edit { it[K.recordMode] = v.name }
    suspend fun setRecordPhone(v: Boolean) = edit { it[K.recordPhone] = v }
    suspend fun setRecordVoip(v: Boolean) = edit { it[K.recordVoip] = v }
    suspend fun setRecordOtherApps(v: Boolean) = edit { it[K.recordOtherApps] = v }
    suspend fun setAppEnabled(pkg: String, enabled: Boolean) = edit {
        val set = (it[K.disabledApps] ?: emptySet()).toMutableSet()
        if (enabled) set.remove(pkg) else set.add(pkg)
        it[K.disabledApps] = set
    }
    suspend fun setFormat(v: AudioFormat) = edit { it[K.format] = v.name }
    suspend fun setQuality(v: Quality) = edit { it[K.quality] = v.name }
    suspend fun setAudioSource(v: AudioSourceOption) = edit { it[K.audioSource] = v.name }
    suspend fun setGainDb(v: Int) = edit { it[K.gainDb] = v.coerceIn(0, 18) }
    suspend fun setFilterMode(v: RecordFilterMode) = edit { it[K.filterMode] = v.name }
    suspend fun addExcludedNumber(number: String) {
        val n = PhoneNumbers.normalize(number) ?: return
        edit { it[K.excluded] = (it[K.excluded] ?: emptySet()) + n }
    }
    suspend fun removeExcludedNumber(number: String) = edit {
        it[K.excluded] = (it[K.excluded] ?: emptySet()) - number
    }
    suspend fun setMinDuration(v: Int) = edit { it[K.minDuration] = v }
    suspend fun setAutoDeleteDays(v: Int) = edit { it[K.autoDelete] = v }
    suspend fun setStorageTreeUri(v: String?) = edit {
        if (v == null) it.remove(K.treeUri) else it[K.treeUri] = v
    }
    suspend fun setNotifyAfterCall(v: Boolean) = edit { it[K.notifyAfter] = v }
    suspend fun setThemeMode(v: ThemeMode) = edit { it[K.theme] = v.name }
    suspend fun setDynamicColor(v: Boolean) = edit { it[K.dynamicColor] = v }
    suspend fun setOnboardingDone(v: Boolean) = edit { it[K.onboardingDone] = v }
    suspend fun setAutoUpdate(v: Boolean) = edit { it[K.autoUpdate] = v }

    /** Restores every setting to its default value. */
    suspend fun resetAll() = edit { it.clear() }
}
