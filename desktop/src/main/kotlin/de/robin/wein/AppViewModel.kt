package de.robin.wein

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.robin.wein.core.Cellar
import de.robin.wein.core.LabelResult
import de.robin.wein.core.Movement
import de.robin.wein.core.QrCode
import de.robin.wein.core.Wine
import de.robin.wein.lookup.EanLookup
import de.robin.wein.lookup.GeminiLookup
import de.robin.wein.lookup.LookupConfig
import de.robin.wein.lookup.LookupResult
import de.robin.wein.nas.Nas
import de.robin.wein.nas.NasConfig
import de.robin.wein.nas.NasConflictException
import de.robin.wein.nas.PHOTO_DIR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.imageio.ImageIO

sealed interface Phase {
    data object Loading : Phase
    data class NoFile(val error: String? = null) : Phase
    data object Ready : Phase
}

sealed interface Source {
    val id: String
    data class Local(val file: File) : Source { override val id get() = "local:${file.absolutePath}" }
    data class Network(val cfg: NasConfig) : Source { override val id get() = cfg.id }
}

/** Ergebnis von „Automatisch einlesen“ – am PC nur KI-Recherche (keine Texterkennung auf dem Gerät). */
data class AutoRead(val label: LabelResult?, val ai: LookupResult?, val error: String?)

/** Was nach einem Scan angezeigt wird. */
data class ScanHit(
    val ean: String,
    val local: List<Wine>,
    val online: List<LookupResult> = emptyList(),
    val searching: Boolean = true,
    val error: String? = null,
    /** Inhalt eines QR-Codes ohne EAN (meist der E-Label-Link). */
    val qr: String? = null,
)

/** Desktop-Gegenstück zum AppViewModel der Android-App – Dateien statt Android-URIs, Einstellungen in %APPDATA%. */
class AppViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = Prefs()
    private val workFile = File(dataDir, "arbeitskopie.xlsx")

    var phase by mutableStateOf<Phase>(Phase.Loading)
        private set
    var cellar by mutableStateOf(Cellar())
        private set
    var fileName by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    /** Anzahl Änderungen seit dem letzten Speichern. */
    var unsaved by mutableIntStateOf(0)
        private set
    var offline by mutableStateOf(false)
        private set
    var nasConflict by mutableStateOf(false)
        private set
    var source by mutableStateOf<Source?>(null)
        private set
    private var nasLastWrite: Long? = null

    // Bestand
    var query by mutableStateOf("")
    var showAll by mutableStateOf(false)

    // Scannen
    var scan by mutableStateOf<ScanHit?>(null)
        private set

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    val isNas: Boolean get() = source is Source.Network

    init {
        val nas = Nas.load(prefs)
        val local = prefs.getString(KEY_LOCAL)?.let(::File)
        when {
            prefs.getString(KEY_KIND) == KIND_NAS && nas != null -> loadNas(nas)
            local != null && local.exists() -> loadLocal(local)
            else -> phase = Phase.NoFile()
        }
    }

    // ------------------------------------------------------------ Quellen

    /** Neue, leere Weinliste an einem Ort nach Wahl anlegen. */
    fun createLocal(file: File) {
        scope.launch {
            try {
                val empty = Cellar()
                writeFile(file, Cellar.write(empty))
                prefs.edit { putString(KEY_KIND, KIND_LOCAL); putString(KEY_LOCAL, file.absolutePath) }
                clearWorkingCopy()
                setLoaded(Source.Local(file), empty, file.name)
                _events.tryEmit("Neue Weinliste angelegt")
            } catch (e: Throwable) {
                phase = Phase.NoFile("Anlegen fehlgeschlagen: ${e.message ?: e}")
            }
        }
    }

    fun openLocal(file: File) {
        prefs.edit { putString(KEY_KIND, KIND_LOCAL); putString(KEY_LOCAL, file.absolutePath) }
        loadLocal(file)
    }

    private fun loadLocal(file: File) {
        phase = Phase.Loading
        scope.launch {
            try {
                val src = Source.Local(file)
                val work = workingCopyFor(src)
                val bytes = work ?: withContext(Dispatchers.IO) { file.readBytes() }
                val c = withContext(Dispatchers.Default) { Cellar.read(bytes) }
                setLoaded(src, c, file.name)
                if (work != null) unsaved = prefs.getInt(KEY_UNSAVED, 1).coerceAtLeast(1)
            } catch (e: Throwable) {
                phase = Phase.NoFile("Laden fehlgeschlagen: ${e.message ?: e}")
            }
        }
    }

    suspend fun testNas(cfg: NasConfig): String = try {
        Nas.test(cfg)
    } catch (e: Throwable) {
        "Fehler: ${e.message ?: e}"
    }

    fun connectNas(cfg: NasConfig) {
        Nas.store(prefs, cfg)
        prefs.edit { putString(KEY_KIND, KIND_NAS) }
        loadNas(cfg)
    }

    fun disconnectNas() {
        Nas.forget(prefs)
        prefs.edit { remove(KEY_KIND) }
        if (isNas) {
            source = null; phase = Phase.NoFile()
        }
    }

    fun nasConfig(): NasConfig? = Nas.load(prefs)

    private fun loadNas(cfg: NasConfig) {
        phase = Phase.Loading
        scope.launch {
            val src = Source.Network(cfg)
            val work = workingCopyFor(src)
            try {
                if (!Nas.exists(cfg)) {
                    // Datei gibt es noch nicht → leere Liste, wird beim ersten Speichern angelegt
                    nasLastWrite = null
                    val c = work?.let { withContext(Dispatchers.Default) { Cellar.read(it) } } ?: Cellar()
                    setLoaded(src, c, cfg.fileName)
                    unsaved = if (work != null) prefs.getInt(KEY_UNSAVED, 1) else 1
                    offline = false
                    return@launch
                }
                val f = Nas.read(cfg)
                nasLastWrite = if (work != null) prefs.getLong(KEY_BASE_WRITE, f.lastWrite) else f.lastWrite
                val c = withContext(Dispatchers.Default) { Cellar.read(work ?: f.bytes) }
                withContext(Dispatchers.IO) { cacheFile().writeBytes(f.bytes) }
                setLoaded(src, c, cfg.fileName)
                offline = false
                if (work != null) unsaved = prefs.getInt(KEY_UNSAVED, 1).coerceAtLeast(1)
            } catch (e: Throwable) {
                // Offline: Arbeitskopie oder letzte Kopie vom NAS
                val fallback = work ?: cacheFile().takeIf { it.exists() }?.readBytes()
                if (fallback != null) {
                    val c = withContext(Dispatchers.Default) { Cellar.read(fallback) }
                    nasLastWrite = prefs.getLong(KEY_BASE_WRITE, 0L).takeIf { it > 0 }
                    setLoaded(src, c, cfg.fileName)
                    offline = true
                    if (work != null) unsaved = prefs.getInt(KEY_UNSAVED, 1).coerceAtLeast(1)
                    _events.tryEmit("NAS nicht erreichbar – arbeite mit der letzten Kopie")
                } else {
                    phase = Phase.NoFile("NAS nicht erreichbar: ${e.message ?: e}")
                }
            }
        }
    }

    fun reload() {
        when (val s = source) {
            is Source.Network -> { clearWorkingCopy(); loadNas(s.cfg) }
            is Source.Local -> { clearWorkingCopy(); loadLocal(s.file) }
            null -> Unit
        }
    }

    private fun setLoaded(src: Source, c: Cellar, name: String) {
        source = src
        cellar = c
        fileName = name
        unsaved = 0
        phase = Phase.Ready
        prefs.edit { putLong(KEY_BASE_WRITE, nasLastWrite ?: 0L) }
    }

    // ------------------------------------------------------------ Änderungen

    private fun change(c: Cellar, message: String? = null) {
        cellar = c
        unsaved++
        message?.let { _events.tryEmit(it) }
        persistWorkingCopy()
    }

    fun saveWine(w: Wine, replacingKey: String? = null) {
        val clash = cellar.wine(w.key)
        if (clash != null && w.key != replacingKey) {
            _events.tryEmit("${w.name} ${w.vintageLabel} gibt es schon – Stammdaten aktualisiert")
        }
        change(cellar.upsert(w, replacingKey))
    }

    fun addMovement(m: Movement) {
        val name = cellar.wine(m.key)?.name.orEmpty()
        change(cellar.add(m), "${m.kind.label}: ${m.quantity}× $name")
    }

    fun removeMovement(m: Movement) = change(cellar.remove(m), "Buchung gelöscht")

    fun deleteWine(key: String) {
        val name = cellar.wine(key)?.name.orEmpty()
        change(cellar.delete(key), "$name gelöscht")
    }

    // ------------------------------------------------------------ Scannen (USB-Scanner oder Eintippen)

    /**
     * Alles, was der Scanner liefert: EAN, QR-Code mit GTIN (GS1-Link) → wie ein Barcode;
     * sonst QR-Code mit Link (E-Label) → Wein über den gespeicherten Link wiederfinden und die Seite auslesen.
     */
    fun onCode(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        QrCode.gtin(text)?.let { return onBarcode(it) }
        if (scan?.qr == text) return
        val url = QrCode.url(text)
        if (url == null) {
            scan = ScanHit(ean = "", local = cellar.byQr(text), qr = text, searching = false,
                error = "QR-Code ohne Link und ohne EAN – Inhalt wird beim Anlegen gespeichert")
            return
        }
        scan = ScanHit(ean = "", local = cellar.byQr(url), qr = url)
        scope.launch {
            var aiError: String? = null
            val found = runCatching { EanLookup.lookupQr(url, activeConfig()) { aiError = it } }.getOrDefault(emptyList())
            scan = scan?.takeIf { it.qr == url }?.copy(online = found, searching = false,
                error = if (found.isEmpty()) "E-Label-Seite nicht auslesbar – bitte von Hand eintragen oder Fotos einlesen" +
                    (aiError?.let { " ($it)" } ?: "") else null)
        }
    }

    fun onBarcode(ean: String) {
        val code = ean.filter { it.isDigit() }
        if (code.isEmpty() || scan?.ean == code) return
        scan = ScanHit(code, cellar.byEan(code))
        scope.launch {
            var aiError: String? = null
            val found = try {
                EanLookup.lookup(code, activeConfig()) { aiError = it }
            } catch (e: Throwable) {
                scan = scan?.takeIf { it.ean == code }?.copy(searching = false, error = "Online-Suche fehlgeschlagen: ${e.message ?: e}")
                return@launch
            }
            scan = scan?.takeIf { it.ean == code }?.copy(online = found, searching = false,
                error = if (found.isEmpty()) "Online nichts gefunden – bitte von Hand eintragen" + (aiError?.let { " ($it)" } ?: "") else null)
        }
    }

    fun closeScan() { scan = null }

    // ------------------------------------------------------------ Online-Quellen & Etikett

    fun lookupConfig(): LookupConfig = LookupConfig(
        eanSearchToken = prefs.getString(KEY_EANSEARCH)?.let(SecretStore::decrypt).orEmpty(),
        geminiKey = prefs.getString(KEY_GEMINI)?.let(SecretStore::decrypt).orEmpty(),
    )

    /** Wie lookupConfig(), aber ohne eigenen Gemini-Schlüssel greift der beim Bauen eingebaute (GitHub-Secret). */
    private fun activeConfig(): LookupConfig = lookupConfig().let {
        if (it.geminiKey.isBlank()) it.copy(geminiKey = BuildConfig.GEMINI_API_KEY) else it
    }

    fun saveLookupConfig(c: LookupConfig) {
        prefs.edit {
            putString(KEY_EANSEARCH, if (c.eanSearchToken.isBlank()) null else SecretStore.encrypt(c.eanSearchToken.trim()))
            putString(KEY_GEMINI, if (c.geminiKey.isBlank()) null else SecretStore.encrypt(c.geminiKey.trim()))
        }
        _events.tryEmit("Online-Quellen gespeichert")
    }

    val hasAi: Boolean get() = activeConfig().geminiKey.isNotBlank()

    /** KI-Recherche per Knopf: EAN und/oder Etikett-Foto. Fehlertext statt Exception. */
    suspend fun askAi(ean: String?, labels: List<File>): Pair<LookupResult?, String?> {
        val key = activeConfig().geminiKey
        if (key.isBlank()) return null to "Kein Gemini-Schlüssel eingetragen (Menü → Online-Quellen)"
        return try {
            val r = GeminiLookup.ask(key, ean?.takeIf { !it.startsWith("X") }, labels)
            r to (if (r == null) "KI hat den Wein nicht sicher gefunden" else null)
        } catch (e: Throwable) {
            null to (e.message ?: e.toString())
        }
    }

    /** „Automatisch einlesen“: KI-Recherche mit EAN und bis zu drei Fotos. Füllt im Dialog nur leere Felder. */
    suspend fun autoRead(ean: String?, photos: List<String>): AutoRead {
        val files = photos.mapNotNull { photoFile(it) }
        if (!hasAi) return AutoRead(null, null, "Automatisch einlesen braucht am PC die KI-Recherche – Gemini-Schlüssel unter Menü → Online-Quellen")
        if (files.isEmpty() && ean.isNullOrBlank()) return AutoRead(null, null, "Erst ein Foto hinzufügen")
        val (ai, err) = askAi(ean, files.take(3))
        return AutoRead(null, ai, err)
    }

    fun openWebSearch(ean: String) = openUrl(EanLookup.webSearchUrl(ean))

    // ------------------------------------------------------------ Eigene Fotos

    /**
     * Weinliste als Datei: Fotos im Ordner „Weinkeller-Fotos“ daneben (sieht man im Explorer, wandert mit der Datei).
     * Weinliste auf dem NAS: Kopie in %APPDATA%\Weinkeller\fotos, das Original im Ordner neben der Liste auf dem NAS.
     */
    private val photoDir: File
        get() = when (val s = source) {
            is Source.Local -> File(s.file.absoluteFile.parentFile, PHOTO_DIR)
            else -> File(dataDir, "fotos")
        }.apply { mkdirs() }
    private val photoMissing = HashSet<String>()

    /** Foto übernehmen: gedreht wie aufgenommen, auf max. 1600 px verkleinert, als JPEG. Liefert den Dateinamen. */
    suspend fun importPhoto(file: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = file.readBytes()
            var img = ImageIO.read(bytes.inputStream()) ?: return@runCatching null
            img = rotate(img, jpegOrientation(bytes))
            val scale = minOf(1.0, 1600.0 / maxOf(img.width, img.height))
            val w = (img.width * scale).toInt().coerceAtLeast(1)
            val h = (img.height * scale).toInt().coerceAtLeast(1)
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(img, 0, 0, w, h, null)
                dispose()
            }
            val name = "foto-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
                "-" + UUID.randomUUID().toString().take(6) + ".jpg"
            ImageIO.write(out, "jpg", File(photoDir, name))
            name
        }.getOrNull()
    }

    /** Foto auf dem PC – fehlt es (am Handy aufgenommen), wird es vom NAS geholt. */
    suspend fun photoFile(name: String): File? {
        val f = File(photoDir, name)
        if (f.exists()) return f
        val cfg = (source as? Source.Network)?.cfg ?: return null
        if (name in photoMissing) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(f.parentFile, "$name.tmp")
                tmp.writeBytes(Nas.readPhoto(cfg, name))
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
                f
            }.getOrElse { photoMissing += name; null }
        }
    }

    /** Fotos, die das NAS noch nicht hat, hochladen. Fehler stören das Speichern der Liste nicht. */
    private suspend fun uploadPhotos(cfg: NasConfig) {
        val wanted = cellar.wines.flatMap { it.photos }.toSet()
        if (wanted.isEmpty()) return
        runCatching {
            val there = Nas.listPhotos(cfg)
            val missing = wanted.filter { it !in there }.mapNotNull { n -> File(photoDir, n).takeIf { it.exists() }?.let { n to it.readBytes() } }
            Nas.writePhotos(cfg, missing.toMap())
        }.onFailure { _events.tryEmit("Fotos nicht aufs NAS geladen: ${it.message ?: it} – nächstes Speichern versucht es erneut") }
    }

    // ------------------------------------------------------------ Speichern

    fun save(force: Boolean = false, then: () -> Unit = {}) {
        val src = source ?: return
        busy = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { Cellar.write(cellar) }
                when (src) {
                    is Source.Local -> writeFile(src.file, bytes)
                    is Source.Network -> {
                        val lw = Nas.write(src.cfg, bytes, expectedLastWrite = nasLastWrite, force = force)
                        nasLastWrite = lw
                        withContext(Dispatchers.IO) { cacheFile().writeBytes(bytes) }
                        prefs.edit { putLong(KEY_BASE_WRITE, lw) }
                        offline = false
                        uploadPhotos(src.cfg)
                    }
                }
                unsaved = 0
                clearWorkingCopy()
                nasConflict = false
                _events.tryEmit("Gespeichert")
                then()
            } catch (e: NasConflictException) {
                nasConflict = true
            } catch (e: Throwable) {
                if (src is Source.Network) offline = true
                _events.tryEmit("Speichern fehlgeschlagen: ${e.message ?: e}")
            } finally {
                busy = false
            }
        }
    }

    fun dismissConflict() { nasConflict = false }

    // ------------------------------------------------------------ Hilfen

    private fun persistWorkingCopy() {
        val src = source ?: return
        val snapshot = cellar
        val count = unsaved
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(workFile.parentFile, workFile.name + ".tmp")
                tmp.writeBytes(Cellar.write(snapshot))
                if (!tmp.renameTo(workFile)) { workFile.delete(); tmp.renameTo(workFile) }
                prefs.edit { putString(KEY_WORK_FOR, src.id); putInt(KEY_UNSAVED, count) }
            }
        }
    }

    private fun workingCopyFor(src: Source): ByteArray? =
        if (prefs.getString(KEY_WORK_FOR) == src.id && workFile.exists()) workFile.readBytes() else null

    private fun clearWorkingCopy() {
        workFile.delete()
        prefs.edit { remove(KEY_WORK_FOR); remove(KEY_UNSAVED) }
    }

    private fun cacheFile() = File(dataDir, "nas_kopie.xlsx")

    /** Wie auf dem NAS: erst .tmp schreiben, dann ersetzen – eine halb geschriebene Datei gibt es so nie. */
    private suspend fun writeFile(file: File, bytes: ByteArray) = withContext(Dispatchers.IO) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("${file.name} ist gesperrt – in Excel geöffnet?", e)
        }
    }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KIND_LOCAL = "local"
        private const val KIND_NAS = "nas"
        private const val KEY_LOCAL = "localFile"
        private const val KEY_WORK_FOR = "workFor"
        private const val KEY_UNSAVED = "unsaved"
        private const val KEY_BASE_WRITE = "baseWrite"
        private const val KEY_EANSEARCH = "eanSearchToken"
        private const val KEY_GEMINI = "geminiKey"
    }
}

fun openUrl(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

/** EXIF-Ausrichtung eines JPEG (1 = normal); Handyfotos sind oft nur per EXIF „gedreht“. */
private fun jpegOrientation(b: ByteArray): Int {
    fun u16(i: Int, le: Boolean) = if (le) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        else ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    fun u32(i: Int, le: Boolean) = if (le) u16(i, true) or (u16(i + 2, true) shl 16) else (u16(i, false) shl 16) or u16(i + 2, false)
    return runCatching {
        if (u16(0, false) != 0xFFD8) return 1
        var i = 2
        while (i + 4 < b.size) {
            val marker = u16(i, false)
            val len = u16(i + 2, false)
            if (marker == 0xFFE1 && String(b, i + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                val tiff = i + 10
                val le = b[tiff] == 'I'.code.toByte()
                val ifd = tiff + u32(tiff + 4, le)
                val n = u16(ifd, le)
                for (k in 0 until n) {
                    val e = ifd + 2 + k * 12
                    if (u16(e, le) == 0x0112) return u16(e + 8, le)
                }
                return 1
            }
            if (marker == 0xFFDA) return 1
            i += 2 + len
        }
        1
    }.getOrDefault(1)
}

private fun rotate(img: BufferedImage, orientation: Int): BufferedImage {
    val deg = when (orientation) { 3 -> 180; 6 -> 90; 8 -> 270; else -> return img }
    val swap = deg != 180
    val w = if (swap) img.height else img.width
    val h = if (swap) img.width else img.height
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    out.createGraphics().apply {
        transform(AffineTransform().apply {
            translate(w / 2.0, h / 2.0)
            rotate(Math.toRadians(deg.toDouble()))
            translate(-img.width / 2.0, -img.height / 2.0)
        })
        drawImage(img, 0, 0, null)
        dispose()
    }
    return out
}
