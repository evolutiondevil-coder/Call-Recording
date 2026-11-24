package bg.evolution.callrecorder.core

import bg.evolution.callrecorder.data.settings.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UtilsTest {

    @Test
    fun normalizeKeepsDigitsAndLeadingPlus() {
        assertThat(PhoneNumbers.normalize("+359 (88) 123-45-67")).isEqualTo("+359881234567")
        assertThat(PhoneNumbers.normalize(" 0888 123 456 ")).isEqualTo("0888123456")
        assertThat(PhoneNumbers.normalize("12+34")).isEqualTo("1234")
        assertThat(PhoneNumbers.normalize("")).isNull()
        assertThat(PhoneNumbers.normalize(null)).isNull()
        assertThat(PhoneNumbers.normalize("abc")).isNull()
    }

    @Test
    fun numbersMatchAcrossFormats() {
        assertThat(PhoneNumbers.matches("+359881234567", "0881234567")).isTrue()
        assertThat(PhoneNumbers.matches("00359881234567", "+359 88 123 4567")).isTrue()
        assertThat(PhoneNumbers.matches("0881234567", "0881234568")).isFalse()
        assertThat(PhoneNumbers.matches("112", "112")).isTrue()
        assertThat(PhoneNumbers.matches("112", "911")).isFalse()
        assertThat(PhoneNumbers.matches(null, "0881234567")).isFalse()
    }

    @Test
    fun durationFormatting() {
        assertThat(Formatters.duration(0)).isEqualTo("0:00")
        assertThat(Formatters.duration(75_000)).isEqualTo("1:15")
        assertThat(Formatters.duration(3_725_000)).isEqualTo("1:02:05")
        assertThat(Formatters.position(59_999)).isEqualTo("0:59")
        assertThat(Formatters.duration(-5)).isEqualTo("0:00")
    }

    @Test
    fun fileNamesAreSafe() {
        val name = FileNames.forRecording(0, "Ivan/Petrov: \"boss\"", "Viber", "m4a")
        assertThat(name).endsWith(" (Viber).m4a")
        assertThat(name).doesNotContainMatch("[\\\\/:*?\"<>|]")
        assertThat(FileNames.sanitize("   ")).isEqualTo("recording")
        assertThat(FileNames.sanitize("a".repeat(500)).length).isEqualTo(60)
    }

    @Test
    fun excludedNumbersAndApps() {
        val s = AppSettings(excludedNumbers = setOf("+359881234567"), disabledApps = setOf("com.viber.voip"))
        assertThat(s.isNumberExcluded("0881234567")).isTrue()
        assertThat(s.isNumberExcluded("0881111111")).isFalse()
        assertThat(s.isNumberExcluded(null)).isFalse()
        assertThat(s.isAppEnabled("com.viber.voip")).isFalse()
        assertThat(s.isAppEnabled("com.whatsapp")).isTrue()
    }

    @Test
    fun formatsHaveSensibleParameters() {
        for (f in AudioFormat.entries) for (q in Quality.entries) {
            assertThat(f.sampleRate(q)).isIn(8_000..48_000)
            assertThat(f.bitRate(q)).isGreaterThan(0)
        }
        assertThat(AudioFormat.AMR_NB.sampleRate(Quality.HIGH)).isEqualTo(8_000)
        assertThat(AudioFormat.AMR_WB.sampleRate(Quality.LOW)).isEqualTo(16_000)
        // MP3 only supports the MPEG sample rates.
        val mpeg = setOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
        Quality.entries.forEach { assertThat(AudioFormat.MP3.sampleRate(it)).isIn(mpeg) }
    }
}
