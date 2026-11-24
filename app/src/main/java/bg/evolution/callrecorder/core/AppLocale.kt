package bg.evolution.callrecorder.core

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * The app's own language setting. Bulgarian is the default even on phones set to
 * English; the user can switch to English or follow the system language.
 *
 * Android 13+ has per-app languages built in (LocaleManager), which applies the choice to
 * every part of the app, including notifications. Older versions get the language by
 * wrapping each component's base context.
 */
object AppLocale {
    const val BULGARIAN = "bg"
    const val ENGLISH = "en"
    const val SYSTEM = "system"

    val options = listOf(BULGARIAN, ENGLISH, SYSTEM)

    private const val PREFS = "app_prefs"
    private const val KEY = "language"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saved(context: Context): String = prefs(context).getString(KEY, BULGARIAN) ?: BULGARIAN

    /** Applies the default (Bulgarian) on the very first start. Call from Application.onCreate. */
    fun initialize(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY)) {
            p.edit().putString(KEY, BULGARIAN).apply()
            if (Build.VERSION.SDK_INT >= 33) setSystemLocales(context, BULGARIAN)
        }
    }

    /** The current choice: [BULGARIAN], [ENGLISH] or [SYSTEM]. */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales != null) return if (locales.isEmpty) SYSTEM else locales[0].language
        }
        return saved(context)
    }

    /** Saves and applies a new language. On Android 12 and older the caller should recreate the activity. */
    fun set(context: Context, language: String) {
        prefs(context).edit().putString(KEY, language).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            setSystemLocales(context, language)
        } else {
            // Also refresh the application context, which notifications use.
            val app = context.applicationContext
            val config = Configuration(app.resources.configuration)
            val locale = localeFor(language) ?: Locale.getDefault()
            Locale.setDefault(locale)
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(config, app.resources.displayMetrics)
        }
    }

    @RequiresApi(33)
    private fun setSystemLocales(context: Context, language: String) {
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        val wanted = if (language == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
        if (lm.applicationLocales != wanted) lm.applicationLocales = wanted
    }

    private fun localeFor(language: String): Locale? =
        if (language == SYSTEM) null else Locale.forLanguageTag(language)

    /** Wraps a base context with the chosen language (Android 12 and older only). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val locale = localeFor(saved(base)) ?: return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** Language names are always shown in their own language. */
    fun label(language: String, systemLabel: String): String = when (language) {
        BULGARIAN -> "Български"
        ENGLISH -> "English"
        else -> systemLabel
    }
}
