package de.robin.wein.ui

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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import de.robin.wein.dataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
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
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 200
        }
    )

    /** Bilddatei auf dem PC, falls schon geladen (z. B. für Anzeigen). */
    fun cachedFile(url: String): File? = if (url.isBlank()) null else file(url).takeIf { it.exists() }

    private fun file(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(dataDir, "bilder").apply { mkdirs() }, "$hash.img")
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
