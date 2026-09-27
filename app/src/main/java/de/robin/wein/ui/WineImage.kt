package de.robin.wein.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Minimaler Bildlader ohne Zusatzbibliothek:
 * einmal geladene Bilder liegen im App-Speicher und sind danach auch offline da.
 */
object ImageCache {
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Bilddatei auf dem Gerät, falls schon geladen (z. B. zum Teilen). */
    fun cachedFile(ctx: Context, url: String): File? = if (url.isBlank()) null else file(ctx, url).takeIf { it.exists() }

    private fun file(ctx: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.filesDir, "bilder").apply { mkdirs() }, "$hash.img")
    }

    suspend fun load(ctx: Context, url: String, maxPx: Int = 600): Bitmap? {
        if (url.isBlank()) return null
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(ctx, url)
            if (!f.exists()) {
                runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 8000; c.readTimeout = 10000
                        c.setRequestProperty("User-Agent", "Weinkeller-App/1.0 (privat, Android)")
                        if (c.responseCode == 200) {
                            val tmp = File(f.parentFile, f.name + ".tmp")
                            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                            tmp.renameTo(f)
                        }
                    } finally { c.disconnect() }
                }
            }
            if (!f.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.also { memory.put(url, it) }
        }
    }
}

/** Flaschenbild, sonst der Farbpunkt. */
@Composable
fun WineImage(url: String, color: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, url) {
        value = ImageCache.load(ctx.applicationContext, url)?.asImageBitmap()
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val b = img
        if (b != null) {
            Image(b, null, Modifier.size(size).clip(RoundedCornerShape(8)), contentScale = ContentScale.Fit)
        } else {
            WineDot(color)
        }
    }
}
