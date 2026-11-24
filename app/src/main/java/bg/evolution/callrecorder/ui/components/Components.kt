package bg.evolution.callrecorder.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import bg.evolution.callrecorder.core.CallSource
import bg.evolution.callrecorder.detect.VoipApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val avatarPalette = listOf(
    Color(0xFF6C5CE7) to Color(0xFFB9B0FF),
    Color(0xFF00897B) to Color(0xFF7FD8CC),
    Color(0xFFE17055) to Color(0xFFFFB59F),
    Color(0xFF0984E3) to Color(0xFF9CCBFF),
    Color(0xFFD63384) to Color(0xFFFFA8D1),
    Color(0xFF2E9E5B) to Color(0xFF93DDB0),
    Color(0xFFB8860B) to Color(0xFFF2CF7A),
    Color(0xFF5F6B7A) to Color(0xFFC2CBD6),
)

fun initialsOf(name: String?): String? {
    if (name.isNullOrBlank()) return null
    val words = name.trim().split(Regex("\\s+")).filter { w -> w.firstOrNull()?.isLetter() == true }
    if (words.isEmpty()) return null
    return words.take(2).joinToString("") { it.first().uppercase() }
}

/** Round avatar with initials (or a person icon) and a small badge showing where the call came from. */
@Composable
fun CallerAvatar(
    name: String?,
    source: CallSource,
    appPackage: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    showBadge: Boolean = true,
) {
    val dark = isSystemInDarkTheme()
    val key = name ?: appPackage ?: source.name
    val (strong, soft) = avatarPalette[abs(key.hashCode()) % avatarPalette.size]
    val bg = Brush.linearGradient(
        if (dark) listOf(strong.copy(alpha = 0.55f), strong.copy(alpha = 0.35f)) else listOf(soft.copy(alpha = 0.9f), soft.copy(alpha = 0.55f)),
    )
    val fg = if (dark) soft else strong
    val initials = initialsOf(name)
    Box(modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            if (initials != null) {
                Text(
                    initials,
                    color = fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.36f).sp,
                    maxLines = 1,
                )
            } else {
                Icon(Icons.Rounded.Person, null, tint = fg, modifier = Modifier.size(size * 0.55f))
            }
        }
        if (showBadge) {
            val badge = size * 0.42f
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp),
                contentAlignment = Alignment.Center,
            ) {
                SourceBadge(source, appPackage, Modifier.size(badge - 3.dp))
            }
        }
    }
}

@Composable
fun SourceBadge(source: CallSource, appPackage: String?, modifier: Modifier = Modifier) {
    val icon = if (source == CallSource.VOIP && appPackage != null) rememberAppIcon(appPackage) else null
    if (icon != null) {
        Image(icon, null, modifier.clip(CircleShape))
    } else {
        val vector: ImageVector = when (source) {
            CallSource.PHONE -> Icons.Rounded.Call
            CallSource.VOIP -> Icons.Rounded.Wifi
            CallSource.MANUAL -> Icons.Rounded.Mic
        }
        val color = VoipApps.find(appPackage)?.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
        Box(modifier.clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(vector, null, tint = Color.White, modifier = Modifier.padding(2.5.dp))
        }
    }
}

private object IconCache {
    val cache = LruCache<String, ImageBitmap>(48)
}

/** Loads (and caches) the launcher icon of [pkg]. Returns null while loading or if it doesn't exist. */
@Composable
fun rememberAppIcon(pkg: String, sizePx: Int = 96): ImageBitmap? {
    val context = LocalContext.current
    val value by produceState(IconCache.cache.get(pkg), pkg) {
        if (value == null) value = withContext(Dispatchers.IO) { loadIcon(context, pkg, sizePx) }
    }
    return value
}

private fun loadIcon(context: Context, pkg: String, sizePx: Int): ImageBitmap? = runCatching {
    val d = VoipApps.icon(context, pkg) ?: return null
    d.toBitmap(sizePx, sizePx).asImageBitmap().also { IconCache.cache.put(pkg, it) }
}.getOrNull()

/** Simple bar level meter, newest value on the right. */
@Composable
fun LevelBars(levels: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (levels.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val barW = ((size.width - gap * (levels.size - 1)) / levels.size).coerceAtLeast(1f)
        levels.forEachIndexed { i, l ->
            val h = (size.height * (0.08f + 0.92f * l.coerceIn(0f, 1f))).coerceAtLeast(barW)
            drawRoundRect(
                color = color.copy(alpha = 0.45f + 0.55f * l.coerceIn(0f, 1f)),
                topLeft = Offset(i * (barW + gap), (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Shape for an item inside a visually grouped list ("segmented" cards). */
fun segmentShape(index: Int, count: Int, outer: Dp = 24.dp, inner: Dp = 6.dp): RoundedCornerShape = when {
    count <= 1 -> RoundedCornerShape(outer)
    index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
    index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
    else -> RoundedCornerShape(inner)
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.secondaryContainer, contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    androidx.compose.foundation.layout.Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor, maxLines = 1)
    }
}

@Composable
fun OutlinedCircle(modifier: Modifier = Modifier, color: Color, content: @Composable () -> Unit) {
    Box(
        modifier.clip(CircleShape).border(1.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}
