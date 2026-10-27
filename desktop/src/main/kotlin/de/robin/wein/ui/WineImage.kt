package de.robin.wein.ui

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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.robin.wein.core.Wine
import de.robin.wein.dataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections

/**
 * Minimaler Bildlader ohne Zusatzbibliothek:
 * einmal geladene Bilder liegen im Einstellungsordner und sind danach auch offline da.
 */
object ImageCache {
    private val memory: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 60
        }
    )

    /** Bilddatei auf dem PC, falls schon geladen (z. B. für Anzeigen). */
    fun cachedFile(url: String): File? = if (url.isBlank()) null else file(url).takeIf { it.exists() }

    private fun file(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(dataDir, "bilder").apply { mkdirs() }, "$hash.img")
    }

    /** Eigenes Foto (Datei im Foto-Ordner), verkleinert auf maxPx. */
    suspend fun loadFile(f: File, maxPx: Int): ImageBitmap? {
        val key = "file:${f.path}@$maxPx"
        memory[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val src = ImageIO.read(f) ?: return@runCatching null
                val scale = minOf(1.0, maxPx.toDouble() / maxOf(src.width, src.height))
                if (scale >= 1.0) return@runCatching src.toComposeImageBitmap()
                val w = (src.width * scale).toInt().coerceAtLeast(1)
                val h = (src.height * scale).toInt().coerceAtLeast(1)
                BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
                    createGraphics().apply {
                        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        drawImage(src, 0, 0, w, h, null)
                        dispose()
                    }
                }.toComposeImageBitmap()
            }.getOrNull()?.also { memory[key] = it }
        }
    }

    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        memory[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(url)
            if (!f.exists()) {
                runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 8000; c.readTimeout = 10000
                        c.setRequestProperty("User-Agent", "Weinkeller-App/1.0 (privat, Windows)")
                        if (c.responseCode == 200) {
                            val tmp = File(f.parentFile, f.name + ".tmp")
                            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                            tmp.renameTo(f)
                        }
                    } finally { c.disconnect() }
                }
            }
            if (!f.exists()) return@withContext null
            runCatching { SkiaImage.makeFromEncoded(f.readBytes()).toComposeImageBitmap() }.getOrNull()
                ?.also { memory[url] = it }
        }
    }
}

/** Flaschenbild, sonst der Farbpunkt. */
@Composable
fun WineImage(url: String, color: String, size: Dp, modifier: Modifier = Modifier) {
    val img by produceState<ImageBitmap?>(null, url) { value = ImageCache.load(url) }
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
    val img by produceState<ImageBitmap?>(null, name, maxPx) { value = photoFile(name)?.let { ImageCache.loadFile(it, maxPx) } }
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
 * Fotoleiste: anklicken = groß ansehen. onRemove != null zeigt ein × zum Entfernen
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
