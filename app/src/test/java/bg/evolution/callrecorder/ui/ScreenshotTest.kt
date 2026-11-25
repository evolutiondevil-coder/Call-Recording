package bg.evolution.callrecorder.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.CallDirection
import bg.evolution.callrecorder.core.CallInfo
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.core.RecordMode
import bg.evolution.callrecorder.core.ThemeMode
import bg.evolution.callrecorder.data.db.Recording
import bg.evolution.callrecorder.data.settings.AppSettings
import bg.evolution.callrecorder.service.RecorderState
import bg.evolution.callrecorder.ui.components.UpdateCard
import bg.evolution.callrecorder.ui.home.HomeScreen
import bg.evolution.callrecorder.ui.home.HomeViewModel
import bg.evolution.callrecorder.ui.home.StatusCard
import bg.evolution.callrecorder.ui.lock.PinCheckScreen
import bg.evolution.callrecorder.ui.lock.PinSetupScreen
import bg.evolution.callrecorder.ui.player.PlayerScreen
import bg.evolution.callrecorder.ui.player.PlayerViewModel
import bg.evolution.callrecorder.ui.player.TrimContent
import bg.evolution.callrecorder.ui.settings.AppsScreen
import bg.evolution.callrecorder.ui.settings.SettingsScreen
import bg.evolution.callrecorder.ui.settings.SettingsViewModel
import bg.evolution.callrecorder.ui.setup.SetupScreen
import bg.evolution.callrecorder.ui.theme.CallRecorderTheme
import bg.evolution.callrecorder.update.ReleaseInfo
import bg.evolution.callrecorder.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

private const val NARROW = "bg-w360dp-h800dp-xxhdpi"
private const val WIDE = "bg-w411dp-h891dp-xxhdpi"
private const val LANDSCAPE = "bg-w800dp-h360dp-land-xxhdpi"
private const val TALL = "bg-w411dp-h2600dp-xxhdpi"

/**
 * Renders every screen and state with sample data into app/build/screenshots, on a narrow
 * (360 dp) and a common (411 dp) phone width, so layout problems such as cut-off labels,
 * awkward line breaks or overlapping buttons can be reviewed without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = WIDE)
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val graph by lazy { AppGraph.get(ApplicationProvider.getApplicationContext()) }
    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** The messengers the user really has installed, so the status card shows the same chips. */
    @Before
    fun installMessengers() {
        val pm = shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().packageManager)
        listOf("com.viber.voip", "com.facebook.orca", "com.facebook.katana", "com.whatsapp", "org.telegram.messenger").forEach { pkg ->
            pm.installPackage(
                PackageInfo().apply {
                    packageName = pkg
                    applicationInfo = ApplicationInfo().apply { packageName = pkg; name = pkg }
                },
            )
        }
        runBlocking { graph.settings.resetAll(); graph.settings.setOnboardingDone(true) }
    }

    private fun wave(seed: Int) = ByteArray(600) { i ->
        val speech = abs(sin(i / 7.0 + seed)) * abs(sin(i / 31.0 + seed * 2))
        val pause = if ((i / 45 + seed) % 5 == 0) 0.15 else 1.0
        (40 + 200 * speech * pause).toInt().coerceIn(0, 255).toByte()
    }

    /** The app graph is a process-wide singleton, so start every test from an empty database. */
    private fun clear() = runBlocking(Dispatchers.IO) { graph.database.clearAllTables() }

    private fun seed(): Long = runBlocking {
        clear()
        val now = System.currentTimeMillis()
        val day = 24 * 3600 * 1000L
        val samples = listOf(
            Recording(uri = "file:///none/1.m4a", fileName = "1.m4a", format = AudioFormat.M4A, sizeBytes = 2_400_000, startedAt = now - 40 * 60_000, durationMs = 312_000, source = CallSource.VOIP, appPackage = "com.viber.voip", direction = CallDirection.INCOMING, contactName = "Мария Иванова", waveform = wave(1)),
            Recording(uri = "file:///none/2.m4a", fileName = "2.m4a", format = AudioFormat.M4A, sizeBytes = 900_000, startedAt = now - 3 * 3600_000, durationMs = 95_000, source = CallSource.PHONE, direction = CallDirection.OUTGOING, phoneNumber = "+359888123456", contactName = "Георги Петров", favorite = true, listened = true, waveform = wave(2)),
            Recording(uri = "file:///none/3.mp3", fileName = "3.mp3", format = AudioFormat.MP3, sizeBytes = 1_300_000, startedAt = now - 5 * 3600_000, durationMs = 161_000, source = CallSource.VOIP, appPackage = "com.whatsapp", direction = CallDirection.OUTGOING, contactName = "Александра Константинова-Христова", listened = true, waveform = wave(3)),
            Recording(uri = "file:///none/4.amr", fileName = "4.amr", format = AudioFormat.AMR_WB, sizeBytes = 120_000, startedAt = now - day - 2 * 3600_000, durationMs = 41_000, source = CallSource.PHONE, direction = CallDirection.INCOMING, phoneNumber = "+359877000111", listened = true, waveform = wave(4)),
            Recording(uri = "file:///none/5.m4a", fileName = "5.m4a", format = AudioFormat.M4A, sizeBytes = 31_000_000, startedAt = now - day - 6 * 3600_000, durationMs = 2 * 3600_000L + 5 * 60_000, source = CallSource.VOIP, appPackage = "com.facebook.orca", direction = CallDirection.INCOMING, contactName = "Николай Димитров", note = "Уговорка за срещата в петък", listened = true, waveform = wave(5)),
            Recording(uri = "file:///none/6.m4a", fileName = "6.m4a", format = AudioFormat.M4A, sizeBytes = 500_000, startedAt = now - 3 * day, durationMs = 62_000, source = CallSource.VOIP, appPackage = "org.telegram.messenger", direction = CallDirection.OUTGOING, contactName = "Ана", listened = true, waveform = wave(6)),
        )
        var first = 0L
        samples.forEach { r -> graph.repository.insert(r).also { if (first == 0L) first = it } }
        first
    }

    private fun shot(name: String, dark: Boolean = false, animated: Boolean = false, content: @Composable () -> Unit) {
        if (animated) compose.mainClock.autoAdvance = false
        compose.setContent {
            CallRecorderTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        if (!animated) compose.waitForIdle()
        // Draw the window directly (Compose's captureToImage waits for a hardware frame that never comes here).
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- home --------------------------------------------------------------------------------

    @Test fun home() { seed(); val vm = HomeViewModel(graph); shot("01-home") { HomeScreen(vm, {}, {}, {}) } }

    @Test @Config(qualifiers = NARROW)
    fun homeNarrow() { seed(); val vm = HomeViewModel(graph); shot("01-home-narrow") { HomeScreen(vm, {}, {}, {}) } }

    @Test fun homeDark() { seed(); val vm = HomeViewModel(graph); shot("02-home-dark", dark = true) { HomeScreen(vm, {}, {}, {}) } }

    @Test fun homeEmpty() { clear(); val vm = HomeViewModel(graph); shot("03-home-empty", dark = true) { HomeScreen(vm, {}, {}, {}) } }

    @Test @Config(qualifiers = NARROW)
    fun homeEmptyNarrow() {
        clear()
        runBlocking { graph.settings.setRecordMode(RecordMode.ASK) }
        val vm = HomeViewModel(graph)
        shot("03-home-empty-narrow") { HomeScreen(vm, {}, {}, {}) }
    }

    private fun active(awaiting: Boolean, paused: Boolean = false) = RecorderState.Active(
        sessionId = 1,
        info = CallInfo(CallSource.VOIP, appPackage = "com.viber.voip", displayName = "Александра Константинова", direction = CallDirection.INCOMING),
        startedElapsed = SystemClock.elapsedRealtime(),
        paused = paused,
        appLabel = "Viber",
        awaitingConfirmation = awaiting,
    )

    @Composable
    private fun Cards(state: RecorderState, update: UpdateState = UpdateState.Idle) {
        Column(Modifier.statusBarsPadding().padding(top = 16.dp)) {
            if (update != UpdateState.Idle) UpdateCard(update, {}, {})
            StatusCard(
                rec = state, level = MutableStateFlow(0.6f), recordedMs = { 754_000 },
                settings = AppSettings(), onToggleAuto = {}, onStop = {}, onPause = {}, onResume = {},
                onConfirm = {}, onDecline = {},
            )
            StatusCard(
                rec = RecorderState.Idle, level = MutableStateFlow(0f), recordedMs = { 0 },
                settings = AppSettings(autoRecord = false), onToggleAuto = {}, onStop = {}, onPause = {}, onResume = {},
                onConfirm = {}, onDecline = {},
            )
        }
    }

    @Test @Config(qualifiers = NARROW)
    fun recordingNarrow() { shot("04-recording-narrow", animated = true) { Cards(active(awaiting = false)) } }

    @Test @Config(qualifiers = NARROW)
    fun askingNarrow() {
        val release = ReleaseInfo("1.2.0", "", "https://github.com/x.apk", 2_000_000, "")
        shot("05-asking-narrow", dark = true, animated = true) { Cards(active(awaiting = true), UpdateState.Downloading(release, 0.42f)) }
    }

    @Test @Config(qualifiers = NARROW)
    fun updateReadyNarrow() {
        val release = ReleaseInfo("1.2.0", "", "https://github.com/x.apk", 2_000_000, "")
        shot("06-update-ready-narrow", animated = true) {
            Cards(active(awaiting = false, paused = true), UpdateState.ReadyToInstall(release, File("x")))
        }
    }

    // ---- other screens ----------------------------------------------------------------------

    @Test fun player() { val id = seed(); val vm = PlayerViewModel(graph, id); shot("07-player") { PlayerScreen(vm) {} } }

    @Test @Config(qualifiers = NARROW)
    fun playerNarrow() { val id = seed(); val vm = PlayerViewModel(graph, id); shot("07-player-narrow", dark = true) { PlayerScreen(vm) {} } }

    @Test fun settings() { val vm = SettingsViewModel(graph); shot("08-settings") { SettingsScreen(vm, {}, {}, {}, {}) } }

    @Test @Config(qualifiers = NARROW)
    fun appsNarrow() { val vm = SettingsViewModel(graph); shot("09-apps-narrow") { AppsScreen(vm) {} } }

    @Test @Config(qualifiers = NARROW)
    fun setupNarrow() {
        shot("10-setup-narrow") { SetupScreen(onboarding = true, recordMode = RecordMode.ASK, onRecordMode = {}, onDone = {}, onBack = {}) }
    }

    // ---- app lock ----------------------------------------------------------------------------

    private fun withLock(block: () -> Unit) {
        graph.lock.setPin("2580")
        try {
            block()
        } finally {
            graph.lock.disable()
        }
    }

    @Test @Config(qualifiers = NARROW)
    fun lockNarrow() = withLock {
        shot("11-lock-narrow") { PinCheckScreen(graph.lock, "Въведете ПИН", onSuccess = {}, onForgotConfirmed = {}) }
    }

    @Test fun lockDark() = withLock {
        shot("12-lock-dark", dark = true) { PinCheckScreen(graph.lock, "Въведете ПИН", onSuccess = {}, onForgotConfirmed = {}) }
    }

    @Test @Config(qualifiers = LANDSCAPE)
    fun lockLandscape() = withLock {
        shot("13-lock-landscape") { PinCheckScreen(graph.lock, "Въведете сегашния ПИН", onSuccess = {}, onForgotConfirmed = {}, onCancel = {}) }
    }

    @Test @Config(qualifiers = NARROW)
    fun pinSetupNarrow() { shot("14-pin-setup-narrow") { PinSetupScreen(onDone = {}, onCancel = {}) } }

    @Test @Config(qualifiers = TALL)
    fun settingsWithLock() = withLock {
        val vm = SettingsViewModel(graph)
        shot("15-settings-lock-tall") { SettingsScreen(vm, {}, {}, {}, {}) }
    }

    // ---- trimming ----------------------------------------------------------------------------

    @Test @Config(qualifiers = NARROW)
    fun trimNarrow() {
        val id = seed()
        val vm = PlayerViewModel(graph, id)
        val r = runBlocking { graph.repository.get(id)!! }
        shot("16-trim-narrow") {
            Column(Modifier.statusBarsPadding().padding(top = 24.dp)) { TrimContent(vm, r) }
        }
    }

    @Test fun trimDark() {
        val id = seed()
        val vm = PlayerViewModel(graph, id)
        val r = runBlocking { graph.repository.get(id)!! }
        shot("17-trim-dark", dark = true) {
            Column(Modifier.statusBarsPadding().padding(top = 24.dp)) { TrimContent(vm, r) }
        }
    }
}
