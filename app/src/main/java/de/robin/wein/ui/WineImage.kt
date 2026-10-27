package de.robin.wein.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.robin.wein.core.Wine
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

    /** Eigenes Foto (Datei im Foto-Ordner), verkleinert auf maxPx. */
    suspend fun loadFile(f: File, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "file:${f.path}@$maxPx"
        memory.get(key)?.let { return@withContext it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { memory.put(key, it) }
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

/** Liefert zu einem Fotonamen die Datei (holt sie bei Bedarf vom NAS) – wird in App() gesetzt. */
val LocalPhotoFile = staticCompositionLocalOf<suspend (String) -> File?> { { null } }

/** Eigenes Foto aus dem Foto-Ordner. */
@Composable
fun PhotoImage(name: String, size: Dp, modifier: Modifier = Modifier, maxPx: Int = 400, contentScale: ContentScale = ContentScale.Crop) {
    val photoFile = LocalPhotoFile.current
    val img by produceState<ImageBitmap?>(null, name, maxPx) {
        value = photoFile(name)?.let { ImageCache.loadFile(it, maxPx) }?.asImageBitmap()
    }
    Box(modifier.size(size).clip(RoundedCornerShape(8)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        img?.let { Image(it, null, Modifier.size(size), contentScale = contentScale) }
            ?: CircularProgressIndicator(Modifier.size(size / 4), strokeWidth = 2.dp)
    }
}

/** Vorschaubild in Listen: eigenes Foto vor Online-Bild vor Farbpunkt. */
@Composable
fun WineThumb(w: Wine, size: Dp) {
    val photo = w.photos.firstOrNull()
    if (photo != null) PhotoImage(photo, size) else WineImage(w.imageUrl, w.color, size)
}

/**
 * Fotoleiste: antippen = groß ansehen. onRemove != null zeigt ein × zum Entfernen
 * (nimmt das Foto nur aus der Liste, die Datei bleibt liegen).
 */
@Composable
fun PhotoStrip(photos: List<String>, size: Dp, onRemove: ((String) -> Unit)? = null) {
    var big by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEach { p ->
            Box {
                PhotoImage(p, size, Modifier.clickable { big = p })
                if (onRemove != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)).clickable { onRemove(p) },
                        contentAlignment = Alignment.Center,
                    ) { Text("×", color = Color.White) }
                }
            }
        }
    }
    big?.let { p ->
        Dialog(onDismissRequest = { big = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { big = null }, contentAlignment = Alignment.Center) {
                PhotoImage(p, 2000.dp, Modifier.fillMaxSize(), maxPx = 1600, contentScale = ContentScale.Fit)
            }
        }
    }
}
