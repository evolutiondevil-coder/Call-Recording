package bg.evolution.callrecorder.detect

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.telecom.TelecomManager

/** A messenger whose calls we know how to detect. */
data class VoipApp(val packageName: String, val label: String, val color: Long)

object VoipApps {
    val known: List<VoipApp> = listOf(
        VoipApp("com.viber.voip", "Viber", 0xFF7360F2),
        VoipApp("com.whatsapp", "WhatsApp", 0xFF25D366),
        VoipApp("com.whatsapp.w4b", "WhatsApp Business", 0xFF128C7E),
        VoipApp("com.facebook.orca", "Messenger", 0xFF0084FF),
        VoipApp("com.facebook.mlite", "Messenger Lite", 0xFF0084FF),
        VoipApp("com.facebook.katana", "Facebook", 0xFF1877F2),
        VoipApp("com.facebook.lite", "Facebook Lite", 0xFF1877F2),
        VoipApp("com.instagram.android", "Instagram", 0xFFE1306C),
        VoipApp("org.telegram.messenger", "Telegram", 0xFF29A9EB),
        VoipApp("org.telegram.messenger.web", "Telegram", 0xFF29A9EB),
        VoipApp("org.thunderdog.challegram", "Telegram X", 0xFF29A9EB),
        VoipApp("org.thoughtcrime.securesms", "Signal", 0xFF3A76F0),
        VoipApp("com.skype.raider", "Skype", 0xFF00AFF0),
        VoipApp("com.microsoft.teams", "Microsoft Teams", 0xFF6264A7),
        VoipApp("com.google.android.apps.tachyon", "Google Meet", 0xFF00897B),
        VoipApp("us.zoom.videomeetings", "Zoom", 0xFF2D8CFF),
        VoipApp("com.discord", "Discord", 0xFF5865F2),
        VoipApp("com.snapchat.android", "Snapchat", 0xFFFFC400),
        VoipApp("jp.naver.line.android", "LINE", 0xFF06C755),
        VoipApp("com.tencent.mm", "WeChat", 0xFF07C160),
        VoipApp("com.imo.android.imoim", "imo", 0xFF009DFF),
        VoipApp("im.thebot.messenger", "BOTIM", 0xFF0A7CFF),
        VoipApp("com.kakao.talk", "KakaoTalk", 0xFFFEE500),
        VoipApp("com.Slack", "Slack", 0xFF4A154B),
        VoipApp("ch.threema.app", "Threema", 0xFF05A63F),
        VoipApp("im.vector.app", "Element", 0xFF0DBD8B),
        VoipApp("com.wire", "Wire", 0xFF1D1D1D),
        VoipApp("com.vkontakte.android", "VK", 0xFF0077FF),
        VoipApp("com.google.android.apps.dynamite", "Google Chat", 0xFF00AC47),
    )

    private val byPackage = known.associateBy { it.packageName }

    fun find(pkg: String?): VoipApp? = pkg?.let(byPackage::get)
    fun isKnown(pkg: String?): Boolean = pkg != null && pkg in byPackage

    /** Packages that show the regular phone call UI; their call notifications are telephony, not VoIP. */
    private val dialerLike = setOf(
        "com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.android.phone",
        "com.android.server.telecom", "com.samsung.android.incallui", "com.samsung.android.dialer",
        "com.android.contacts", "com.miui.voip", "com.android.systemui", "com.huawei.contacts",
        "com.coloros.phone", "com.oplus.phone", "com.oneplus.dialer", "com.asus.contacts",
        "com.motorola.dialer", "com.sonymobile.android.dialer", "com.google.android.apps.messaging",
    )

    fun isDialer(context: Context, pkg: String): Boolean {
        if (pkg in dialerLike || pkg == context.packageName) return true
        val defaultDialer = runCatching {
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        }.getOrNull()
        return pkg == defaultDialer
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(pkg, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Installed known messengers, de-duplicated by label (e.g. two Telegram builds). */
    fun installed(context: Context): List<VoipApp> = known.filter { isInstalled(context, it.packageName) }

    fun label(context: Context, pkg: String?): String? {
        if (pkg == null) return null
        find(pkg)?.let { return it.label }
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrNull() ?: pkg
    }

    fun icon(context: Context, pkg: String): Drawable? =
        runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
}
