package bg.evolution.callrecorder.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import bg.evolution.callrecorder.AppGraph
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.CallDirection
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.core.RecordMode
import bg.evolution.callrecorder.core.ThemeMode
import bg.evolution.callrecorder.data.db.Recording
import bg.evolution.callrecorder.ui.home.HomeScreen
import bg.evolution.callrecorder.ui.home.HomeViewModel
import bg.evolution.callrecorder.ui.player.PlayerScreen
import bg.evolution.callrecorder.ui.player.PlayerViewModel
import bg.evolution.callrecorder.ui.settings.SettingsScreen
import bg.evolution.callrecorder.ui.settings.SettingsViewModel
import bg.evolution.callrecorder.ui.setup.SetupScreen
import bg.evolution.callrecorder.ui.theme.CallRecorderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * Renders the main screens with sample data into app/build/screenshots so the UI can be
 * reviewed without a device. Runs on the JVM with Robolectric's native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "bg-w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val graph by lazy { AppGraph.get(ApplicationProvider.getApplicationContext()) }
    private val outDir = File("build/screenshots").apply { mkdirs() }

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
            Recording(uri = "file:///none/3.mp3", fileName = "3.mp3", format = AudioFormat.MP3, sizeBytes = 1_300_000, startedAt = now - 5 * 3600_000, durationMs = 161_000, source = CallSource.VOIP, appPackage = "com.whatsapp", direction = CallDirection.OUTGOING, contactName = "Екип Офис", listened = true, waveform = wave(3)),
            Recording(uri = "file:///none/4.amr", fileName = "4.amr", format = AudioFormat.AMR_WB, sizeBytes = 120_000, startedAt = now - day - 2 * 3600_000, durationMs = 41_000, source = CallSource.PHONE, direction = CallDirection.INCOMING, phoneNumber = "+359877000111", listened = true, waveform = wave(4)),
            Recording(uri = "file:///none/5.m4a", fileName = "5.m4a", format = AudioFormat.M4A, sizeBytes = 3_100_000, startedAt = now - day - 6 * 3600_000, durationMs = 604_000, source = CallSource.VOIP, appPackage = "com.facebook.orca", direction = CallDirection.INCOMING, contactName = "Николай Димитров", note = "Уговорка за срещата в петък", listened = true, waveform = wave(5)),
            Recording(uri = "file:///none/6.m4a", fileName = "6.m4a", format = AudioFormat.M4A, sizeBytes = 500_000, startedAt = now - 3 * day, durationMs = 62_000, source = CallSource.VOIP, appPackage = "org.telegram.messenger", direction = CallDirection.OUTGOING, contactName = "Ана", listened = true, waveform = wave(6)),
        )
        var first = 0L
        samples.forEach { r -> graph.repository.insert(r).also { if (first == 0L) first = it } }
        graph.settings.setOnboardingDone(true)
        first
    }

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            CallRecorderTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        // Draw the window directly (Compose's captureToImage waits for a hardware frame that never comes here).
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home() {
        seed()
        val vm = HomeViewModel(graph)
        shot("01-home") { HomeScreen(vm, {}, {}, {}) }
    }

    @Test
    fun homeDark() {
        seed()
        val vm = HomeViewModel(graph)
        shot("02-home-dark", dark = true) { HomeScreen(vm, {}, {}, {}) }
    }

    @Test
    fun homeEmpty() {
        clear()
        val vm = HomeViewModel(graph)
        shot("03-home-empty") { HomeScreen(vm, {}, {}, {}) }
    }

    @Test
    fun player() {
        val id = seed()
        val vm = PlayerViewModel(graph, id)
        shot("04-player") { PlayerScreen(vm) {} }
    }

    @Test
    fun settings() {
        val vm = SettingsViewModel(graph)
        shot("05-settings") { SettingsScreen(vm, {}, {}, {}) }
    }

    @Test
    fun setup() {
        shot("06-setup") { SetupScreen(onboarding = true, recordMode = RecordMode.ASK, onRecordMode = {}, onDone = {}, onBack = {}) }
    }
}
