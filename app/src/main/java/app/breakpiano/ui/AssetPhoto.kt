package app.breakpiano.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Card photos live in assets/img/inst/<instrument id>.webp and assets/img/beat/<style id>.webp
 * (jpg/png also accepted). Missing files simply fall back to the built-in illustration.
 */
object AssetPhotos {
    private var index: Map<String, String>? = null
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private fun index(ctx: Context): Map<String, String> = index ?: synchronized(this) {
        index ?: buildMap {
            for (kind in listOf("inst", "beat")) {
                val files = runCatching { ctx.assets.list("img/$kind") }.getOrNull().orEmpty()
                for (f in files) {
                    val ext = f.substringAfterLast('.', "").lowercase()
                    if (ext in setOf("webp", "jpg", "jpeg", "png")) put("$kind/${f.substringBeforeLast('.')}", "img/$kind/$f")
                }
            }
        }.also { index = it }
    }

    fun has(ctx: Context, kind: String, id: String) = index(ctx).containsKey("$kind/$id")

    suspend fun load(ctx: Context, kind: String, id: String, targetWidth: Int = 640): ImageBitmap? {
        val path = index(ctx)["$kind/$id"] ?: return null
        cache.get(path)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
            }.getOrNull()?.also { cache.put(path, it) }
        }
    }
}

/** Shows the photo for [kind]/[id] if the app ships one, otherwise [fallback]. */
@Composable
fun AssetPhoto(kind: String, id: String, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    val ctx = LocalContext.current
    if (!AssetPhotos.has(ctx, kind, id)) { fallback(); return }
    val bmp by produceState<ImageBitmap?>(null, kind, id) { value = AssetPhotos.load(ctx, kind, id) }
    val b = bmp
    if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}
