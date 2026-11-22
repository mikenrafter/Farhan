package ly.com.tahaben.notification_filter_presentation.apppriority

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt

internal val appIconSize = 40.dp

@Composable
internal fun rememberAppIconLoader(): AppIconLoader<ImageBitmap> {
    val packages = LocalContext.current.applicationContext.packageManager
    return remember(packages) { AppIconLoader(AppIconSource { name, pixels -> packages.readIcon(name, pixels) }) }
}

@Composable
internal fun PackageAppIcon(
    packageName: String,
    letter: String,
    loader: AppIconLoader<ImageBitmap>,
) {
    val pixels = with(LocalDensity.current) { appIconSize.roundToPx().coerceIn(1, 160) }
    var icon by remember(packageName, pixels, loader) { mutableStateOf<AppIcon<ImageBitmap>>(loader.peek(packageName, pixels) ?: AppIcon.LetterTile) }
    LaunchedEffect(packageName, pixels, loader) {
        if (icon !is AppIcon.Loaded) icon = loader.load(packageName, pixels)
    }
    val loaded = icon
    // A real icon is drawn on its own, clipped to one rounded square. Only the letter fallback gets a tile,
    // so there is no second shape behind an icon that already has its own.
    Surface(
        modifier = Modifier.size(appIconSize),
        shape = RoundedCornerShape(10.dp),
        color = if (loaded is AppIcon.Loaded) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (loaded) {
                is AppIcon.Loaded -> Image(loaded.icon, contentDescription = null, modifier = Modifier.fillMaxSize())
                AppIcon.LetterTile -> Text(letter, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.readIcon(packageName: String, pixels: Int): ImageBitmap? = try {
    val application = getApplicationInfo(packageName, 0)
    if (application.icon == 0) null else getApplicationIcon(application).displayBitmap(pixels)
} catch (_: PackageManager.NameNotFoundException) {
    null
} catch (_: SecurityException) {
    null
}

private fun Drawable.displayBitmap(pixels: Int): ImageBitmap {
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    val scale = pixels.toFloat() / maxOf(width, height)
    return toBitmap(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    ).asImageBitmap()
}
