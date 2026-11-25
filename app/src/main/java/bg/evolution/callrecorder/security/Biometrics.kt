package bg.evolution.callrecorder.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import bg.evolution.callrecorder.core.Logx

/** The phone's own fingerprint / face unlock and screen lock, through the platform APIs. */
object Biometrics {
    /** True if a fingerprint (or face) is set up and can unlock the app. */
    fun canUseBiometric(context: Context): Boolean = runCatching {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                context.getSystemService(BiometricManager::class.java)
                    ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                @Suppress("DEPRECATION")
                context.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT == Build.VERSION_CODES.P ->
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
            else -> false
        }
    }.getOrDefault(false)

    /** True if the phone has a screen lock (PIN, pattern or password). */
    fun isDeviceSecure(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /** On Android 11+ the screen lock can be asked for in the same system prompt. */
    val deviceCredentialInPrompt: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Shows the system fingerprint prompt ([deviceCredential] = false) or asks for the phone's
     * screen lock ([deviceCredential] = true, Android 11+ only). [onResult] gets true on success.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun prompt(
        activity: Activity,
        title: String,
        subtitle: String?,
        negativeText: String,
        deviceCredential: Boolean,
        onResult: (Boolean) -> Unit,
    ): CancellationSignal? {
        // The negative button and the error callback may both fire; report only once.
        var done = false
        val report = { ok: Boolean -> if (!done) { done = true; onResult(ok) } }
        val executor = activity.mainExecutor
        val builder = BiometricPrompt.Builder(activity).setTitle(title)
        if (!subtitle.isNullOrBlank()) builder.setSubtitle(subtitle)
        if (deviceCredential && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            }
            builder.setNegativeButton(negativeText, executor) { _, _ -> report(false) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setConfirmationRequired(false)
        val cancel = CancellationSignal()
        return try {
            builder.build().authenticate(cancel, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = report(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = report(false)
            })
            cancel
        } catch (e: Exception) {
            Logx.w("Biometric prompt failed", e)
            report(false)
            null
        }
    }

    /** The "confirm your screen lock" screen for Android 8-10 (null if there is no screen lock). */
    fun confirmCredentialIntent(context: Context, title: String, description: String?): Intent? {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        return km.createConfirmDeviceCredentialIntent(title, description)
    }
}
