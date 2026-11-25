package bg.evolution.callrecorder.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhoneNumbers {
    /** Keeps only digits and a leading '+', so "+359 (88) 123-45-67" -> "+359881234567". */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val sb = StringBuilder()
        raw.trim().forEachIndexed { i, c ->
            when {
                c.isDigit() -> sb.append(c)
                c == '+' && sb.isEmpty() && i <= 1 -> sb.append(c)
            }
        }
        return sb.toString().takeIf { it.count(Char::isDigit) >= 2 }
    }

    /**
     * Loose equality that tolerates country codes and trunk prefixes:
     * "+359881234567", "00359881234567" and "0881234567" all match.
     */
    fun matches(a: String?, b: String?): Boolean {
        val x = normalize(a)?.filter(Char::isDigit) ?: return false
        val y = normalize(b)?.filter(Char::isDigit) ?: return false
        if (x == y) return true
        val n = minOf(x.length, y.length, 9)
        if (n < 6) return false
        return x.takeLast(n) == y.takeLast(n)
    }
}

object Formatters {
    /** 75_000 -> "1:15", 3_725_000 -> "1:02:05". */
    fun duration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) + 500) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Duration used while a player is running, never rounds up past the real position. */
    fun position(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }
}

object FileNames {
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun sanitize(name: String, maxLength: Int = 60): String {
        val cleaned = name.replace(illegal, "_").replace(Regex("\\s+"), " ").trim().trim('.')
        return cleaned.take(maxLength).ifBlank { "recording" }
    }

    /** e.g. "2026-11-24 18-03-12 Ivan Petrov (Viber).m4a" */
    fun forRecording(startedAt: Long, title: String?, appLabel: String?, extension: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date(startedAt))
        val parts = buildString {
            append(stamp)
            if (!title.isNullOrBlank()) append(' ').append(title)
            if (!appLabel.isNullOrBlank()) append(" (").append(appLabel).append(')')
        }
        return sanitize(parts, maxLength = 100) + "." + extension
    }
}

/** Thin logging helper so that every class logs under the same tag prefix. */
object Logx {
    private const val TAG = "CallRecorder"
    fun d(msg: String) = Log.d(TAG, msg)
    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, msg, t)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}

/**
 * Set while the app's own player sends audio to the earpiece. That switches the phone into
 * communication mode, which call detection must not mistake for an internet call.
 */
object EarpiecePlayback {
    @Volatile var active: Boolean = false
}
