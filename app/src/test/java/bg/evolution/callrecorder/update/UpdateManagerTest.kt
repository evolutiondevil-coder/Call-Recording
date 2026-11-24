package bg.evolution.callrecorder.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) // org.json is part of the Android framework
@Config(sdk = [35])
class UpdateManagerTest {

    @Test
    fun versionComparison() {
        assertThat(UpdateManager.isNewer("1.1.0", "1.0.0")).isTrue()
        assertThat(UpdateManager.isNewer("1.10.0", "1.9.9")).isTrue()
        assertThat(UpdateManager.isNewer("2.0", "1.9.9")).isTrue()
        assertThat(UpdateManager.isNewer("1.0.0", "1.0.0")).isFalse()
        assertThat(UpdateManager.isNewer("1.0.0", "1.0.1")).isFalse()
        assertThat(UpdateManager.isNewer("1.0.1", "1.0.0-debug")).isTrue()
        assertThat(UpdateManager.isNewer("1.0", "1.0.0")).isFalse()
    }

    private val sample = """
        {
          "tag_name": "v1.2.0",
          "html_url": "https://github.com/o/r/releases/tag/v1.2.0",
          "body": "Нова версия",
          "draft": false,
          "prerelease": false,
          "assets": [
            {"name": "notes.txt", "size": 10, "browser_download_url": "https://github.com/o/r/releases/download/v1.2.0/notes.txt"},
            {"name": "Zapis-na-razgovori-1.2.0.apk", "size": 2000000, "browser_download_url": "https://github.com/o/r/releases/download/v1.2.0/Zapis-na-razgovori-1.2.0.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesLatestRelease() {
        val r = UpdateManager.parseRelease(sample)!!
        assertThat(r.version).isEqualTo("1.2.0")
        assertThat(r.apkUrl).endsWith("Zapis-na-razgovori-1.2.0.apk")
        assertThat(r.apkSize).isEqualTo(2_000_000)
        assertThat(r.notes).isEqualTo("Нова версия")
    }

    @Test
    fun ignoresReleasesWithoutApkOrFromElsewhere() {
        assertThat(UpdateManager.parseRelease("""{"tag_name":"v1","assets":[]}""")).isNull()
        assertThat(
            UpdateManager.parseRelease(
                """{"tag_name":"v9","assets":[{"name":"x.apk","size":1,"browser_download_url":"https://evil.example/x.apk"}]}""",
            ),
        ).isNull()
        assertThat(UpdateManager.parseRelease(sample.replace("\"draft\": false", "\"draft\": true"))).isNull()
    }
}
