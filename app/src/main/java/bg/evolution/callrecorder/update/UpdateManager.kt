package bg.evolution.callrecorder.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import bg.evolution.callrecorder.BuildConfig
import bg.evolution.callrecorder.core.Logx
import bg.evolution.callrecorder.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A version published on the GitHub Releases page. */
data class ReleaseInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val reason: Reason, val release: ReleaseInfo? = null) : UpdateState

    enum class Reason { NETWORK, NOT_FOUND, SIGNATURE, INSTALL, PERMISSION }
}

/**
 * Checks the GitHub Releases page for a newer version when the app starts, downloads the
 * APK by itself and installs it when the user taps "Install" (Android always asks the user
 * to confirm installing apps that don't come from an app store).
 *
 * Safety: only APKs with this app's package name and the same signing certificate are
 * installed, so a tampered download is rejected.
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    /** The last fully downloaded and verified update, kept for retries. */
    private var ready: UpdateState.ReadyToInstall? = null

    private fun setReady(release: ReleaseInfo, file: File) {
        val r = UpdateState.ReadyToInstall(release, file)
        ready = r
        _state.value = r
    }
    private val dir get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Updates only make sense for the signed release build. */
    val supported: Boolean get() = !BuildConfig.DEBUG && BuildConfig.UPDATE_REPO.isNotBlank()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Called when the app is opened: check and, if there's something new, download it. */
    fun checkOnStart() {
        if (!supported || !settings.current.autoUpdate) return
        check(userInitiated = false)
    }

    /** "Check for updates" button. */
    fun checkNow() {
        if (!supported) return
        check(userInitiated = true)
    }

    private fun check(userInitiated: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        if (s is UpdateState.ReadyToInstall && s.file.exists()) return
        if (s is UpdateState.Failed && s.reason == UpdateState.Reason.PERMISSION && ready?.file?.exists() == true) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = UpdateState.Checking
            val release = try {
                fetchLatest()
            } catch (e: Exception) {
                Logx.w("Update check failed: ${e.message}")
                _state.value = if (userInitiated) UpdateState.Failed(UpdateState.Reason.NETWORK) else UpdateState.Idle
                return@launch
            }
            if (release == null) {
                _state.value = if (userInitiated) UpdateState.Failed(UpdateState.Reason.NOT_FOUND) else UpdateState.Idle
                return@launch
            }
            if (!isNewer(release.version, currentVersion)) {
                cleanup(keep = null)
                _state.value = UpdateState.UpToDate
                return@launch
            }
            download(release)
        }
    }

    private fun fetchLatest(): ReleaseInfo? {
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CallRecorder-Android")
        }
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return null // no release yet, or the repository is private
                else -> throw IOException("HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseRelease(body)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(release: ReleaseInfo) {
        val target = File(dir, "update-${release.version}.apk")
        if (target.exists() && target.length() == release.apkSize && verify(target)) {
            setReady(release, target)
            return
        }
        cleanup(keep = null)
        _state.value = UpdateState.Downloading(release, 0f)
        val partial = File(dir, "update-${release.version}.part")
        try {
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CallRecorder-Android")
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                conn.inputStream.use { input ->
                    partial.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = 0f
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                            if (p - lastReported >= 0.01f) {
                                lastReported = p
                                _state.value = UpdateState.Downloading(release, p)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (release.apkSize > 0 && partial.length() != release.apkSize) throw IOException("Incomplete download")
            if (!partial.renameTo(target)) throw IOException("Rename failed")
        } catch (e: Exception) {
            Logx.w("Update download failed: ${e.message}")
            partial.delete()
            _state.value = UpdateState.Failed(UpdateState.Reason.NETWORK, release)
            return
        }
        if (!verify(target)) {
            target.delete()
            _state.value = UpdateState.Failed(UpdateState.Reason.SIGNATURE, release)
            return
        }
        setReady(release, target)
    }

    /** The APK must be this app, a newer build, and signed with the same key. */
    private fun verify(apk: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return false
        if (versionCode(archive) <= versionCode(installed)) return false
        val theirs = signatures(archive)
        val ours = signatures(installed)
        // Some Android versions don't report certificates for archives; the installer
        // still refuses a different signer in that case.
        return theirs.isEmpty() || theirs == ours
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun signatures(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { si -> if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION") info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install updates. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the downloaded APK to the system installer. */
    fun install() {
        val pending = ready?.takeIf { it.file.exists() } ?: return
        if (!canInstall()) {
            // The user first has to allow this app to install updates; they tap "Install" again afterwards.
            _state.value = UpdateState.Failed(UpdateState.Reason.PERMISSION, pending.release)
            runCatching { context.startActivity(installPermissionIntent()) }
            return
        }
        _state.value = UpdateState.Installing(pending.release)
        scope.launch(Dispatchers.IO) {
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(pending.file.length())
                    if (Build.VERSION.SDK_INT >= 31) {
                        // Lets Android 12+ update without an extra prompt once this app installed itself before.
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, pending.file.length()).use { out ->
                        pending.file.inputStream().use { it.copyTo(out, 64 * 1024) }
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val status = PendingIntent.getBroadcast(
                        context, sessionId, Intent(context, InstallStatusReceiver::class.java), flags,
                    )
                    session.commit(status.intentSender)
                }
            } catch (e: Exception) {
                Logx.e("Install failed", e)
                withContext(Dispatchers.Main) { onInstallFailed() }
            }
        }
    }

    /** Called by [InstallStatusReceiver] when the installer reports an error or the user cancels. */
    fun onInstallFailed(cancelled: Boolean = false) {
        val r = ready
        _state.value = when {
            cancelled && r != null && r.file.exists() -> r
            else -> UpdateState.Failed(UpdateState.Reason.INSTALL, r?.release)
        }
    }

    /** Deletes downloaded APKs (after a successful update or when they're outdated). */
    fun cleanup(keep: File?) {
        dir.listFiles()?.filter { it != keep }?.forEach { it.delete() }
    }

    companion object {
        /** Parses the GitHub "latest release" JSON. Returns null if the release has no APK. */
        fun parseRelease(json: String): ReleaseInfo? {
            val o = JSONObject(json)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://github.com/")) {
                    return ReleaseInfo(
                        version = tag.removePrefix("v").removePrefix("V"),
                        notes = o.optString("body"),
                        apkUrl = url,
                        apkSize = a.optLong("size"),
                        pageUrl = o.optString("html_url"),
                    )
                }
            }
            return null
        }

        /** "1.10.0" is newer than "1.9.2"; suffixes such as "-debug" are ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
