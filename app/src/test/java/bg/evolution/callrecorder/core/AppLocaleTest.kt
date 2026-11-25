package bg.evolution.callrecorder.core

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import bg.evolution.callrecorder.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The app shows Bulgarian by default, even on a phone set to English, and follows the chosen language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLocaleTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private val englishPhone = Configuration().apply { setLocales(LocaleList(Locale.US)) }

    private fun choose(language: String?) {
        val p = app.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
        if (language == null) p.clear() else p.putString("language", language)
        p.commit()
    }

    /** Resolves a string for [config] directly against the APK's resource table. */
    @Suppress("DEPRECATION")
    private fun title(config: Configuration) =
        Resources(app.assets, app.resources.displayMetrics, config).getString(R.string.home_title)

    private fun applied(language: String) = AppLocale.configurationFor(englishPhone, language) ?: englishPhone

    @Test
    fun bulgarianIsTheDefaultChoice() {
        choose(null)
        assertThat(AppLocale.current(app)).isEqualTo(AppLocale.BULGARIAN)
        choose("garbage")
        assertThat(AppLocale.current(app)).isEqualTo(AppLocale.BULGARIAN)
    }

    @Test
    fun bulgarianOnAnEnglishPhone() {
        assertThat(title(englishPhone)).isEqualTo("Recordings") // the phone itself is in English
        assertThat(applied(AppLocale.BULGARIAN).locales[0].language).isEqualTo("bg")
        assertThat(title(applied(AppLocale.BULGARIAN))).isEqualTo("Записи")
    }

    @Test
    fun englishAndSystemChoices() {
        assertThat(title(applied(AppLocale.ENGLISH))).isEqualTo("Recordings")
        assertThat(AppLocale.configurationFor(englishPhone, AppLocale.SYSTEM)).isNull()
    }

    @Test
    fun perAppLanguageListResolves() {
        // Android 13+ puts the app's language first and the phone's after it.
        val cfg = Configuration().apply { setLocales(LocaleList(Locale.forLanguageTag("bg"), Locale.US)) }
        assertThat(title(cfg)).isEqualTo("Записи")
        val other = Configuration().apply { setLocales(LocaleList(Locale.GERMANY)) }
        assertThat(title(other)).isEqualTo("Записи")
    }
}
