package bg.evolution.callrecorder.ui.lock

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import bg.evolution.callrecorder.R
import bg.evolution.callrecorder.core.Formatters
import bg.evolution.callrecorder.security.AppLock
import bg.evolution.callrecorder.security.Biometrics
import bg.evolution.callrecorder.ui.components.AppLogo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What a PIN screen opened from the settings is for. */
enum class PinFlow {
    /** Choose a PIN and turn the lock on. */
    ENABLE,
    /** Enter the current PIN, then choose a new one. */
    CHANGE,
    /** Enter the current PIN, then turn the lock off. */
    DISABLE,
    /** The PIN was forgotten and the phone's screen lock was confirmed: choose a new one. */
    RESET,
}

/**
 * Covers the app with the lock screen while [AppLock.locked]. The app's screens leave the
 * composition meanwhile (nothing of them can be seen, read aloud or tapped) and come back in
 * the same state after unlocking.
 */
@Composable
fun AppLockGate(lock: AppLock, onPinForgotten: () -> Unit, content: @Composable () -> Unit) {
    val locked by lock.locked.collectAsState()
    val holder = rememberSaveableStateHolder()
    val activity = LocalActivity.current
    Box(Modifier.fillMaxSize()) {
        if (!locked) holder.SaveableStateProvider("app") { content() }
        AnimatedVisibility(visible = locked, enter = EnterTransition.None, exit = fadeOut(tween(220))) {
            PinCheckScreen(
                lock = lock,
                title = stringResource(R.string.lock_title),
                onSuccess = lock::unlock,
                onForgotConfirmed = {
                    lock.unlock()
                    onPinForgotten()
                },
            )
        }
    }
    // "Back" on the lock screen leaves the app instead of reaching the screens behind it.
    BackHandler(enabled = locked) { activity?.moveTaskToBack(true) }
}

/** A PIN screen opened from the settings: turns the lock on or off, or changes the PIN. */
@Composable
fun PinFlowScreen(flow: PinFlow, lock: AppLock, onFinish: () -> Unit) {
    val context = LocalContext.current
    // Choosing a PIN over an existing one always needs the current PIN first (RESET comes only
    // right after the phone's screen lock was confirmed).
    var verified by rememberSaveable {
        mutableStateOf(flow == PinFlow.RESET || (flow == PinFlow.ENABLE && !lock.config.value.enabled))
    }
    fun afterVerified() {
        if (flow == PinFlow.DISABLE) {
            lock.disable()
            onFinish()
        } else {
            verified = true
        }
    }
    if (!verified) {
        PinCheckScreen(
            lock = lock,
            title = stringResource(R.string.pin_verify_title),
            onSuccess = ::afterVerified,
            onForgotConfirmed = ::afterVerified,
            onCancel = onFinish,
        )
    } else {
        PinSetupScreen(
            onDone = { pin ->
                // A fingerprint set up on the phone is used from the start; it can be turned off.
                val biometric = if (flow == PinFlow.ENABLE) Biometrics.canUseBiometric(context) else lock.config.value.biometric
                lock.setPin(pin, biometric)
                onFinish()
            },
            onCancel = onFinish,
        )
    }
}

private sealed interface PinProblem {
    data class Wrong(val attemptsLeft: Int) : PinProblem
    data object Mismatch : PinProblem
}

/** Asks for the PIN (or the fingerprint) and calls [onSuccess] when it is right. */
@Composable
fun PinCheckScreen(
    lock: AppLock,
    title: String,
    onSuccess: () -> Unit,
    onForgotConfirmed: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val config by lock.config.collectAsState()
    val length = config.pinLength.coerceIn(AppLock.MIN_LENGTH, AppLock.MAX_LENGTH)

    var entered by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<PinProblem?>(null) }
    var checking by remember { mutableStateOf(false) }
    var lockoutMs by remember { mutableLongStateOf(lock.lockoutRemainingMs()) }
    var noScreenLock by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }

    LaunchedEffect(lockoutMs > 0) {
        while (lockoutMs > 0) {
            delay(250)
            lockoutMs = lock.lockoutRemainingMs()
        }
    }

    val biometric = config.biometric && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        remember { Biometrics.canUseBiometric(context) }
    val promptTitle = stringResource(R.string.lock_biometric_title)
    val appName = stringResource(R.string.app_name)
    val usePin = stringResource(R.string.lock_use_pin)
    val forgotTitle = stringResource(R.string.lock_forgot_title)
    val cancelText = stringResource(R.string.action_cancel)

    fun succeed() {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onSuccess()
    }

    fun askBiometric() {
        val a = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        lock.onPromptShowing(true)
        Biometrics.prompt(a, promptTitle, appName, usePin, deviceCredential = false) { ok ->
            lock.onPromptShowing(false)
            if (ok) succeed()
        }
    }

    val credentialLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        lock.onPromptShowing(false)
        if (r.resultCode == Activity.RESULT_OK) onForgotConfirmed()
    }

    fun forgot() {
        val a = activity
        if (a == null || !Biometrics.isDeviceSecure(context)) {
            noScreenLock = true
            return
        }
        if (Biometrics.deviceCredentialInPrompt) {
            lock.onPromptShowing(true)
            Biometrics.prompt(a, forgotTitle, appName, cancelText, deviceCredential = true) { ok ->
                lock.onPromptShowing(false)
                if (ok) onForgotConfirmed()
            }
        } else {
            val intent = Biometrics.confirmCredentialIntent(context, forgotTitle, appName)
            if (intent == null) noScreenLock = true else credentialLauncher.launch(intent)
        }
    }

    // Offer the fingerprint as soon as the screen is in front.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(biometric) {
        if (!biometric) return@LaunchedEffect
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        delay(250)
        askBiometric()
    }

    fun submit(pin: String) {
        checking = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { lock.verify(pin) }
            checking = false
            when (result) {
                AppLock.Result.Ok -> succeed()
                is AppLock.Result.Wrong -> {
                    problem = PinProblem.Wrong(result.attemptsLeft)
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    shake.shake()
                    entered = ""
                }
                is AppLock.Result.LockedOut -> {
                    problem = null
                    lockoutMs = result.remainingMs
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    shake.shake()
                    entered = ""
                }
            }
        }
    }

    val subtitle = when {
        lockoutMs > 0 -> stringResource(R.string.lock_locked_out, Formatters.duration(lockoutMs))
        problem is PinProblem.Wrong -> {
            val left = (problem as PinProblem.Wrong).attemptsLeft
            pluralStringResource(R.plurals.lock_attempts_left, left, left)
        }
        else -> stringResource(R.string.lock_subtitle)
    }
    val inputEnabled = !checking && lockoutMs <= 0

    PinLayout(
        title = title,
        subtitle = subtitle,
        isError = lockoutMs > 0 || problem != null,
        dots = length,
        filled = entered.length,
        shake = shake.value,
        inputEnabled = inputEnabled,
        onDigit = { d ->
            if (entered.length < length) {
                haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                entered += d
                if (entered.length == length) submit(entered)
            }
        },
        onBackspace = { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
        extraKey = if (biometric) {
            { PadIconKey(Icons.Rounded.Fingerprint, stringResource(R.string.lock_use_fingerprint), enabled = true, onClick = ::askBiometric) }
        } else {
            null
        },
        onCancel = onCancel,
        footer = {
            TextButton(onClick = ::forgot) {
                Icon(Icons.Rounded.LockReset, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lock_forgot))
            }
        },
    )

    if (noScreenLock) {
        AlertDialog(
            onDismissRequest = { noScreenLock = false },
            icon = { Icon(Icons.Rounded.LockReset, null) },
            title = { Text(stringResource(R.string.lock_forgot)) },
            text = { Text(stringResource(R.string.lock_forgot_unavailable)) },
            confirmButton = { TextButton(onClick = { noScreenLock = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

/** Choose a new PIN and type it a second time. */
@Composable
fun PinSetupScreen(onDone: (String) -> Unit, onCancel: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var first by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // "Back" while repeating the PIN returns to choosing it.
    BackHandler(enabled = first != null) {
        first = null
        entered = ""
    }

    fun next() {
        if (entered.length >= AppLock.MIN_LENGTH) {
            first = entered
            entered = ""
            mismatch = false
        }
    }

    val confirming = first != null
    PinLayout(
        title = stringResource(if (confirming) R.string.pin_confirm_title else R.string.pin_choose_title),
        subtitle = stringResource(
            when {
                confirming -> R.string.pin_confirm_sub
                mismatch -> R.string.pin_mismatch
                else -> R.string.pin_choose_sub
            },
        ),
        isError = mismatch && !confirming,
        dots = first?.length ?: maxOf(AppLock.MIN_LENGTH, entered.length),
        filled = entered.length,
        shake = shake.value,
        inputEnabled = true,
        onDigit = { d ->
            val target = first
            if (target == null) {
                if (entered.length < AppLock.MAX_LENGTH) {
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    entered += d
                    if (entered.length == AppLock.MAX_LENGTH) next()
                }
            } else if (entered.length < target.length) {
                haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                entered += d
                if (entered.length == target.length) {
                    if (entered == target) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onDone(entered)
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        mismatch = true
                        first = null
                        entered = ""
                        scope.launch { shake.shake() }
                    }
                }
            }
        },
        onBackspace = { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
        extraKey = if (!confirming) {
            {
                PadIconKey(
                    Icons.Rounded.Check, stringResource(R.string.action_continue),
                    enabled = entered.length >= AppLock.MIN_LENGTH, onClick = ::next,
                )
            }
        } else {
            null
        },
        onCancel = onCancel,
        footer = null,
    )
}

private suspend fun Animatable<Float, *>.shake() {
    animateTo(0f, keyframes {
        durationMillis = 420
        -18f at 60
        16f at 130
        -12f at 200
        8f at 270
        -4f at 340
    })
}

// ---- layout ---------------------------------------------------------------------------------

@Composable
private fun PinLayout(
    title: String,
    subtitle: String,
    isError: Boolean,
    dots: Int,
    filled: Int,
    shake: Float,
    inputEnabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    extraKey: (@Composable () -> Unit)?,
    onCancel: (() -> Unit)?,
    footer: (@Composable () -> Unit)?,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            // Digits and backspace from a hardware keyboard work too.
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !inputEnabled) return@onKeyEvent false
                val c = e.utf16CodePoint.toChar()
                when {
                    c in '0'..'9' -> { onDigit(c); true }
                    e.key == Key.Backspace -> { onBackspace(); true }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val landscape = maxWidth > maxHeight && maxHeight < 520.dp
            val keySize = if (landscape) {
                min(64.dp, (maxHeight - 40.dp) / 4.7f)
            } else {
                min(76.dp, min((maxWidth - 64.dp) / 3.6f, (maxHeight - 330.dp) / 4.9f))
            }.coerceAtLeast(44.dp)

            val header: @Composable ColumnScope.() -> Unit = {
                AppLogo(if (landscape) 52.dp else 68.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    modifier = Modifier
                        .widthIn(max = 340.dp)
                        .padding(horizontal = 24.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(20.dp))
                PinDots(
                    count = dots,
                    filled = filled,
                    error = isError && filled == 0,
                    modifier = Modifier.offset { IntOffset((shake * density).roundToInt(), 0) },
                )
            }
            val pad: @Composable () -> Unit = {
                PinPad(keySize, inputEnabled, onDigit, onBackspace, canBackspace = filled > 0, extraKey = extraKey)
            }

            if (landscape) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        header()
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.heightIn(min = 48.dp)) { footer?.invoke() }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { pad() }
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(1f))
                    header()
                    Spacer(Modifier.weight(1f))
                    pad()
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { footer?.invoke() }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (onCancel != null) {
                IconButton(onClick = onCancel, modifier = Modifier.padding(4.dp).align(Alignment.TopStart)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

@Composable
private fun PinDots(count: Int, filled: Int, error: Boolean, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.pin_entered, filled)
    Row(
        modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val on = i < filled
            val outline = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
            val fill by animateColorAsState(
                if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                tween(120),
                label = "dot",
            )
            Box(
                Modifier
                    .size(14.dp)
                    .background(fill, CircleShape)
                    .border(1.5.dp, if (on) MaterialTheme.colorScheme.primary else outline, CircleShape),
            )
        }
    }
}

@Composable
private fun PinPad(
    keySize: Dp,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    canBackspace: Boolean,
    extraKey: (@Composable () -> Unit)?,
) {
    val gap = keySize * 0.3f
    val rowGap = keySize * 0.18f
    Column(verticalArrangement = Arrangement.spacedBy(rowGap), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { d -> DigitKey(d, keySize, enabled) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(keySize), contentAlignment = Alignment.Center) { extraKey?.invoke() }
            DigitKey('0', keySize, enabled) { onDigit('0') }
            Box(Modifier.size(keySize), contentAlignment = Alignment.Center) {
                PadIconKey(
                    Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.action_backspace),
                    enabled = enabled && canBackspace, onClick = onBackspace,
                )
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                digit.toString(),
                style = if (size >= 64.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun PadIconKey(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(icon, description, modifier = Modifier.size(28.dp))
    }
}
