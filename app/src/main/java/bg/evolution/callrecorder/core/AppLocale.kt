package bg.evolution.callrecorder.core

import android.app.Activity
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
 * The app applies the language itself, to every activity, service and the application
 * context (see [wrap]), on all Android versions. It doesn't rely on Android 13's per-app
 * language alone: that is applied asynchronously and could leave the first screen in the
 * phone's language. The choice is still mirrored to Android 13+, so the system's
 * "App languages" screen shows it, and a change made there is picked up.
 */
object AppLocale {
    const val BULGARIAN = "bg"
    const val ENGLISH = "en"
    const val SYSTEM = "system"

    val options = listOf(BULGARIAN, ENGLISH, SYSTEM)

    private const val PREFS = "app_prefs"
    private const val KEY = "language"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saved(context: Context): String =
        prefs(context).getString(KEY, BULGARIAN)?.takeIf { it in options } ?: BULGARIAN

    /** Mirrors the saved choice to Android 13+ on first start. Call from Application.onCreate. */
    fun initialize(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY)) p.edit().putString(KEY, BULGARIAN).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            // Adopt a language picked in Android's own "App languages" screen.
            val system = systemChoice(context)
            if (system != null && system != saved(context)) {
                p.edit().putString(KEY, system).apply()
            } else if (system == null && saved(context) != SYSTEM) {
                setSystemLocales(context, saved(context))
            }
        }
    }

    /** The current choice: [BULGARIAN], [ENGLISH] or [SYSTEM]. */
    fun current(context: Context): String = saved(context)

    /**
     * Saves and applies a new language. The given activity is recreated right away so the
     * change is visible immediately.
     */
    fun set(context: Context, language: String) {
        prefs(context).edit().putString(KEY, language).commit()
        if (Build.VERSION.SDK_INT >= 33) setSystemLocales(context, language)
        // Notifications and services use the application context: refresh it too.
        val app = context.applicationContext
        val locale = localeFor(language) ?: systemLocale()
        Locale.setDefault(locale)
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList(locale))
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
        (context as? Activity)?.recreate()
    }

    /** Language chosen in Android 13+'s per-app settings, or null if none. */
    private fun systemChoice(context: Context): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        if (locales.isEmpty) return null
        return locales[0].language.takeIf { it == BULGARIAN || it == ENGLISH }
    }

    @RequiresApi(33)
    private fun setSystemLocales(context: Context, language: String) {
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        val wanted = tagFor(language)?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        if (lm.applicationLocales != wanted) lm.applicationLocales = wanted
    }

    /**
     * Full language + country tags: a bare "bg" in a single-locale configuration is replaced
     * by the phone's own locale when resources are resolved, while "bg-BG" is kept.
     */
    private fun tagFor(language: String): String? = when (language) {
        BULGARIAN -> "bg-BG"
        ENGLISH -> "en-US"
        else -> null
    }

    private fun localeFor(language: String): Locale? = tagFor(language)?.let(Locale::forLanguageTag)

    private fun systemLocale(): Locale = LocaleList.getDefault().get(0) ?: Locale.getDefault()

    /** Wraps a base context with the chosen language. Use in every attachBaseContext. */
    fun wrap(base: Context): Context {
        val config = configurationFor(base.resources.configuration, saved(base)) ?: return base
        Locale.setDefault(config.locales[0])
        return base.createConfigurationContext(config)
    }

    /** The configuration to use for [language], or null to keep the phone's own. */
    fun configurationFor(base: Configuration, language: String): Configuration? {
        val locale = localeFor(language) ?: return null
        return Configuration(base).apply { setLocales(LocaleList(locale)) }
    }

    /** Language names are always shown in their own language. */
    fun label(language: String, systemLabel: String): String = when (language) {
        BULGARIAN -> "Български"
        ENGLISH -> "English"
        else -> systemLabel
    }
}
