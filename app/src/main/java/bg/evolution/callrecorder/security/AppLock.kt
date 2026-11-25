package bg.evolution.callrecorder.security

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Locks the app behind a PIN (and, if wanted, the fingerprint or face unlock of the phone).
 *
 * Only a salted PBKDF2 hash of the PIN is kept, in the app's private storage. The state lives
 * in its own SharedPreferences, so it is known synchronously when the app starts and nothing
 * from the recordings is drawn before the lock screen.
 */
@android.annotation.SuppressLint("ApplySharedPref") // The PIN and failed attempts must be on disk at once.
class AppLock(
    context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    data class Config(
        val enabled: Boolean = false,
        val biometric: Boolean = false,
        /** How long the app may stay in the background before it locks again (0 = immediately). */
        val timeoutMs: Long = 0,
        val pinLength: Int = 0,
    )

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val attemptsLeft: Int) : Result
        data class LockedOut(val remainingMs: Long) : Result
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    val config: StateFlow<Config> = _config.asStateFlow()

    private val _locked = MutableStateFlow(_config.value.enabled)
    /** True while the lock screen must cover the app. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var hiddenAt = 0L
    private var hiddenForOwnScreen = false
    private var ownScreenAt = 0L
    @Volatile private var promptShowing = false

    private fun read() = Config(
        enabled = prefs.getString(KEY_HASH, null) != null,
        biometric = prefs.getBoolean(KEY_BIOMETRIC, false),
        timeoutMs = prefs.getLong(KEY_TIMEOUT, 0L),
        pinLength = prefs.getInt(KEY_LENGTH, 0),
    )

    /** Turns the lock on (or changes the PIN). The app stays unlocked. */
    fun setPin(pin: String, biometric: Boolean = _config.value.biometric) {
        require(isValidPin(pin)) { "PIN must have $MIN_LENGTH-$MAX_LENGTH digits" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        prefs.edit()
            .putString(KEY_HASH, encode(hash(pin, salt, ITERATIONS)))
            .putString(KEY_SALT, encode(salt))
            .putInt(KEY_ITERATIONS, ITERATIONS)
            .putInt(KEY_LENGTH, pin.length)
            .putBoolean(KEY_BIOMETRIC, biometric)
            .remove(KEY_FAILS)
            .remove(KEY_LOCKOUT_UNTIL)
            .commit()
        _config.value = read()
        _locked.value = false
    }

    /** Turns the lock off and forgets the PIN. */
    fun disable() {
        prefs.edit().clear().commit()
        _config.value = read()
        _locked.value = false
    }

    fun setBiometric(on: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC, on).apply()
        _config.value = _config.value.copy(biometric = on)
    }

    fun setTimeout(ms: Long) {
        prefs.edit().putLong(KEY_TIMEOUT, ms.coerceAtLeast(0)).apply()
        _config.value = _config.value.copy(timeoutMs = ms.coerceAtLeast(0))
    }

    /**
     * Checks a PIN. Slow on purpose (PBKDF2), so call it off the main thread. After every
     * [MAX_ATTEMPTS] wrong PINs the next try has to wait, longer each time.
     */
    fun verify(pin: String): Result {
        lockoutRemainingMs().takeIf { it > 0 }?.let { return Result.LockedOut(it) }
        val stored = prefs.getString(KEY_HASH, null)?.let(::decode) ?: return Result.Ok
        val salt = prefs.getString(KEY_SALT, null)?.let(::decode) ?: ByteArray(0)
        val iterations = prefs.getInt(KEY_ITERATIONS, ITERATIONS)
        if (MessageDigest.isEqual(stored, hash(pin, salt, iterations))) {
            resetFailures()
            return Result.Ok
        }
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        val edit = prefs.edit().putInt(KEY_FAILS, fails)
        val result = if (fails % MAX_ATTEMPTS == 0) {
            val wait = lockoutDuration(fails / MAX_ATTEMPTS)
            edit.putLong(KEY_LOCKOUT_UNTIL, wallClock() + wait)
            Result.LockedOut(wait)
        } else {
            Result.Wrong(MAX_ATTEMPTS - fails % MAX_ATTEMPTS)
        }
        edit.commit()
        return result
    }

    /** Time left before another PIN may be tried; 0 if there is no wait. */
    fun lockoutRemainingMs(): Long {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (until == 0L) return 0
        val left = until - wallClock()
        // If the clock was turned back, never make the wait longer than the longest one.
        return left.coerceIn(0, MAX_LOCKOUT_MS)
    }

    /** Called after a correct PIN, fingerprint or screen lock: hides the lock screen. */
    fun unlock() {
        resetFailures()
        _locked.value = false
    }

    private fun resetFailures() {
        if (prefs.contains(KEY_FAILS) || prefs.contains(KEY_LOCKOUT_UNTIL)) {
            prefs.edit().remove(KEY_FAILS).remove(KEY_LOCKOUT_UNTIL).apply()
        }
    }

    // ---- when to lock ---------------------------------------------------------------------------

    /**
     * The app is about to open a screen of its own choosing (contact picker, "save as", a system
     * settings page...). Leaving for it doesn't lock the app straight away.
     */
    fun onOwnScreenLaunch() {
        ownScreenAt = clock()
    }

    /** A fingerprint / screen lock prompt is showing; it may cover the app for a moment. */
    fun onPromptShowing(showing: Boolean) {
        promptShowing = showing
    }

    /** The app went to the background (Activity.onStop, but not for a rotation). */
    fun onHidden() {
        hiddenAt = clock()
        hiddenForOwnScreen = promptShowing || (ownScreenAt != 0L && hiddenAt - ownScreenAt < OWN_SCREEN_WINDOW_MS)
        ownScreenAt = 0L
    }

    /** The app is visible again (Activity.onStart). Locks it if it was away for too long. */
    fun onShown() {
        val at = hiddenAt
        hiddenAt = 0L
        val c = _config.value
        if (!c.enabled || at == 0L || _locked.value) return
        val away = clock() - at
        val limit = if (hiddenForOwnScreen) maxOf(c.timeoutMs, OWN_SCREEN_GRACE_MS) else c.timeoutMs
        if (away >= limit) _locked.value = true
    }

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 8
        const val MAX_ATTEMPTS = 5
        val TIMEOUT_OPTIONS = listOf(0L, 60_000L, 5 * 60_000L, 30 * 60_000L)

        private const val PREFS = "app_lock"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_ITERATIONS = "pin_iterations"
        private const val KEY_LENGTH = "pin_length"
        private const val KEY_BIOMETRIC = "biometric"
        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_FAILS = "fails"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val ITERATIONS = 40_000

        /** A screen the app opened itself may stay on top this long without locking the app. */
        private const val OWN_SCREEN_GRACE_MS = 5 * 60_000L
        private const val OWN_SCREEN_WINDOW_MS = 3_000L
        private const val MAX_LOCKOUT_MS = 15 * 60_000L

        fun isValidPin(pin: String) = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

        /** 30 s after the first 5 wrong PINs, then 1, 2, 4, 8 and at most 15 minutes. */
        fun lockoutDuration(round: Int): Long =
            (30_000L shl (round - 1).coerceIn(0, 5)).coerceAtMost(MAX_LOCKOUT_MS)

        private fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    }
}
