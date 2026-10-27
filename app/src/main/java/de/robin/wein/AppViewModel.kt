package de.robin.wein

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.robin.wein.core.Cellar
import de.robin.wein.core.Movement
import de.robin.wein.core.QrCode
import de.robin.wein.core.Wine
import de.robin.wein.core.LabelResult
import de.robin.wein.lookup.EanLookup
import de.robin.wein.lookup.LabelOcr
import de.robin.wein.lookup.LookupResult
import de.robin.wein.lookup.LookupConfig
import de.robin.wein.nas.SecretStore
import de.robin.wein.nas.Nas
import de.robin.wein.nas.NasConfig
import de.robin.wein.nas.NasConflictException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

sealed interface Phase {
    data object Loading : Phase
    data class NoFile(val error: String? = null) : Phase
    data object Ready : Phase
}

sealed interface Source {
    val id: String
    data class Local(val uri: Uri) : Source { override val id get() = "local:$uri" }
    data class Network(val cfg: NasConfig) : Source { override val id get() = cfg.id }
}

/** Ergebnis von „Automatisch einlesen“: Texterkennung der Fotos und/oder KI-Recherche. */
data class AutoRead(val label: LabelResult?, val ai: LookupResult?, val error: String?)

/** Was nach einem Scan angezeigt wird – per Barcode (ean) oder per Etikett-Foto (ean leer, label/photo gesetzt). */
data class ScanHit(
    val ean: String,
    val local: List<Wine>,
    val online: List<LookupResult> = emptyList(),
    val searching: Boolean = true,
    val error: String? = null,
    val label: LabelResult? = null,
    val photo: Uri? = null,
    /** Inhalt eines QR-Codes ohne EAN (meist der E-Label-Link). */
    val qr: String? = null,
) {
    val isLabel: Boolean get() = photo != null
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("wein", 0)
    private val resolver get() = getApplication<Application>().contentResolver
    private val workFile = File(app.filesDir, "arbeitskopie.xlsx")

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
        val local = prefs.getString(KEY_LOCAL, null)?.let(Uri::parse)
        when {
            prefs.getString(KEY_KIND, null) == KIND_NAS && nas != null -> loadNas(nas)
            local != null && hasPermission(local) -> loadLocal(local)
            else -> phase = Phase.NoFile()
        }
    }

    // ------------------------------------------------------------ Quellen

    /** Neue, leere Weinliste an einem Ort nach Wahl anlegen. */
    fun createLocal(uri: Uri) {
        viewModelScope.launch {
            try {
                takePermission(uri)
                val empty = Cellar()
                writeUri(uri, Cellar.write(empty))
                prefs.edit().putString(KEY_KIND, KIND_LOCAL).putString(KEY_LOCAL, uri.toString()).apply()
                clearWorkingCopy()
                setLoaded(Source.Local(uri), empty, displayName(uri) ?: "Weine.xlsx")
                _events.tryEmit("Neue Weinliste angelegt")
            } catch (e: Throwable) {
                phase = Phase.NoFile("Anlegen fehlgeschlagen: ${e.message ?: e}")
            }
        }
    }

    fun openLocal(uri: Uri) {
        takePermission(uri)
        prefs.edit().putString(KEY_KIND, KIND_LOCAL).putString(KEY_LOCAL, uri.toString()).apply()
        loadLocal(uri)
    }

    private fun loadLocal(uri: Uri) {
        phase = Phase.Loading
        viewModelScope.launch {
            try {
                val src = Source.Local(uri)
                val work = workingCopyFor(src)
                val bytes = work ?: withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Datei nicht lesbar")
                }
                val c = withContext(Dispatchers.Default) { Cellar.read(bytes) }
                setLoaded(src, c, displayName(uri) ?: "Weine.xlsx")
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
        prefs.edit().putString(KEY_KIND, KIND_NAS).apply()
        loadNas(cfg)
    }

    fun disconnectNas() {
        Nas.forget(prefs)
        prefs.edit().remove(KEY_KIND).apply()
        if (isNas) {
            source = null; phase = Phase.NoFile()
        }
    }

    fun nasConfig(): NasConfig? = Nas.load(prefs)

    private fun loadNas(cfg: NasConfig) {
        phase = Phase.Loading
        viewModelScope.launch {
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
                cacheFile().writeBytes(f.bytes)
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
            is Source.Local -> { clearWorkingCopy(); loadLocal(s.uri) }
            null -> Unit
        }
    }

    private fun setLoaded(src: Source, c: Cellar, name: String) {
        source = src
        cellar = c
        fileName = name
        unsaved = 0
        phase = Phase.Ready
        prefs.edit().putLong(KEY_BASE_WRITE, nasLastWrite ?: 0L).apply()
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

    // ------------------------------------------------------------ Scannen

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
        viewModelScope.launch {
            var aiError: String? = null
            val found = runCatching { EanLookup.lookupQr(getApplication(), url, activeConfig()) { aiError = it } }.getOrDefault(emptyList())
            scan = scan?.takeIf { it.qr == url }?.copy(online = found, searching = false,
                error = if (found.isEmpty()) "E-Label-Seite nicht auslesbar – bitte von Hand eintragen oder Fotos einlesen" +
                    (aiError?.let { " ($it)" } ?: "") else null)
        }
    }

    fun onBarcode(ean: String) {
        val code = ean.filter { it.isDigit() }
        if (code.isEmpty() || scan?.ean == code) return
        scan = ScanHit(code, cellar.byEan(code))
        viewModelScope.launch {
            var aiError: String? = null
            val found = try {
                EanLookup.lookup(getApplication(), code, activeConfig()) { aiError = it }
            } catch (e: Throwable) {
                scan = scan?.takeIf { it.ean == code }?.copy(searching = false, error = "Online-Suche fehlgeschlagen: ${e.message ?: e}")
                return@launch
            }
            scan = scan?.takeIf { it.ean == code }?.copy(online = found, searching = false,
                error = if (found.isEmpty()) "Online nichts gefunden – bitte von Hand eintragen" + (aiError?.let { " ($it)" } ?: "") else null)
        }
    }

    /** Etikett im Scan-Tab fotografiert → Texterkennung → passende Weine aus der Liste vorschlagen. */
    fun onLabelPhoto(uri: Uri) {
        scan = ScanHit(ean = "", local = emptyList(), photo = uri)
        viewModelScope.launch {
            val l = readLabel(uri)
            if (scan?.photo != uri) return@launch
            scan = if (l == null) scan?.copy(searching = false, error = "Kein Text erkannt – näher ran, Etikett gerade halten")
            else scan?.copy(label = l, local = matchLabel(l), searching = false)
        }
    }

    /** Weine, die zum Etikett passen könnten (Name, sonst Erzeuger); exakter Jahrgang zuerst. */
    private fun matchLabel(l: LabelResult): List<Wine> {
        val hits = listOf(l.name, l.producer).filter { it.isNotBlank() }
            .firstNotNullOfOrNull { q -> cellar.search(q).takeIf { it.isNotEmpty() } }.orEmpty()
        return hits.sortedBy { if (l.vintage != null && it.vintage == l.vintage) 0 else 1 }.take(5)
    }

    fun closeScan() { scan = null }

    // ------------------------------------------------------------ Online-Quellen & Etikett

    fun lookupConfig(): LookupConfig = LookupConfig(
        eanSearchToken = prefs.getString(KEY_EANSEARCH, null)?.let(SecretStore::decrypt).orEmpty(),
        geminiKey = prefs.getString(KEY_GEMINI, null)?.let(SecretStore::decrypt).orEmpty(),
    )

    /** Wie lookupConfig(), aber ohne eigenen Gemini-Schlüssel greift der beim Bauen eingebaute (GitHub-Secret). */
    private fun activeConfig(): LookupConfig = lookupConfig().let {
        if (it.geminiKey.isBlank()) it.copy(geminiKey = BuildConfig.GEMINI_API_KEY) else it
    }

    fun saveLookupConfig(c: LookupConfig) {
        prefs.edit()
            .putString(KEY_EANSEARCH, if (c.eanSearchToken.isBlank()) null else SecretStore.encrypt(c.eanSearchToken.trim()))
            .putString(KEY_GEMINI, if (c.geminiKey.isBlank()) null else SecretStore.encrypt(c.geminiKey.trim()))
            .apply()
        _events.tryEmit("Online-Quellen gespeichert")
    }

    val hasAi: Boolean get() = activeConfig().geminiKey.isNotBlank()

    /** KI-Recherche per Knopf: EAN und/oder Etikett-Foto. Fehlertext statt Exception. */
    suspend fun askAi(ean: String?, labels: List<Uri>): Pair<LookupResult?, String?> {
        val key = activeConfig().geminiKey
        if (key.isBlank()) return null to "Kein Gemini-Schlüssel eingetragen (Menü → Online-Quellen)"
        return try {
            val r = de.robin.wein.lookup.GeminiLookup.ask(getApplication(), key, ean?.takeIf { !it.startsWith("X") }, labels)
            r to (if (r == null) "KI hat den Wein nicht sicher gefunden" else null)
        } catch (e: Throwable) {
            null to (e.message ?: e.toString())
        }
    }

    /** Etikett-Foto lesen (offline). */
    suspend fun readLabel(uri: Uri): LabelResult? =
        runCatching { LabelOcr.read(getApplication(), uri) }.getOrNull()

    /**
     * „Automatisch einlesen“: Texterkennung auf allen Fotos (offline), dazu – falls eingerichtet – die KI-Recherche
     * mit EAN und Fotos. Füllt im Dialog nur leere Felder.
     */
    suspend fun autoRead(ean: String?, photos: List<String>): AutoRead {
        val files = photos.mapNotNull { photoFile(it) }
        val labels = files.mapNotNull { readLabel(Uri.fromFile(it)) }
        // Das Foto mit dem meisten erkannten Inhalt gewinnt; Jahreszahlen aller Fotos zur Auswahl
        val label = labels.maxByOrNull { l -> listOf(l.name, l.producer, l.grape, l.region).count { it.isNotBlank() } + (if (l.vintage != null) 1 else 0) }
            ?.let { best -> best.copy(years = labels.flatMap { it.years }.distinct().sortedDescending()) }
        val canAi = hasAi && (files.isNotEmpty() || !ean.isNullOrBlank())
        val (ai, aiError) = if (canAi) askAi(ean, files.take(3).map { Uri.fromFile(it) }) else null to null
        val error = when {
            label != null || ai != null -> null
            files.isEmpty() && !canAi -> "Erst ein Foto machen"
            else -> listOfNotNull("Nichts erkannt – Etikett gerade und formatfüllend fotografieren", aiError).joinToString(" · ")
        }
        return AutoRead(label, ai, error)
    }

    // ------------------------------------------------------------ Eigene Fotos

    /** Fotos liegen im App-Speicher; bei einer Weinliste auf dem NAS zusätzlich im Ordner „Weinkeller-Fotos“ daneben. */
    private val photoDir get() = File(getApplication<Application>().filesDir, "fotos").apply { mkdirs() }
    private val photoMissing = HashSet<String>()

    /** Foto übernehmen: gedreht wie aufgenommen, auf max. 1600 px verkleinert, als JPEG. Liefert den Dateinamen. */
    suspend fun importPhoto(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
            var bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (rotation != 0) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            }
            val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val name = newPhotoName()
            File(photoDir, name).writeBytes(out.toByteArray())
            name
        }.getOrNull()
    }

    /** Foto auf dem Gerät – fehlt es (am anderen Gerät aufgenommen), wird es vom NAS geholt. */
    suspend fun photoFile(name: String): File? {
        val f = File(photoDir, name)
        if (f.exists()) return f
        val cfg = (source as? Source.Network)?.cfg ?: return null
        if (name in photoMissing) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(photoDir, "$name.tmp")
                tmp.writeBytes(Nas.readPhoto(cfg, name))
                tmp.renameTo(f)
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

    private fun newPhotoName() =
        "foto-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
            "-" + java.util.UUID.randomUUID().toString().take(6) + ".jpg"

    fun webSearchIntent(query: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(EanLookup.webSearchUrl(query)))

    // ------------------------------------------------------------ Speichern

    fun save(force: Boolean = false) {
        val src = source ?: return
        busy = true
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { Cellar.write(cellar) }
                when (src) {
                    is Source.Local -> writeUri(src.uri, bytes)
                    is Source.Network -> {
                        val lw = Nas.write(src.cfg, bytes, expectedLastWrite = nasLastWrite, force = force)
                        nasLastWrite = lw
                        cacheFile().writeBytes(bytes)
                        prefs.edit().putLong(KEY_BASE_WRITE, lw).apply()
                        offline = false
                        uploadPhotos(src.cfg)
                    }
                }
                unsaved = 0
                clearWorkingCopy()
                nasConflict = false
                _events.tryEmit("Gespeichert")
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
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(workFile.parentFile, workFile.name + ".tmp")
                tmp.writeBytes(Cellar.write(snapshot))
                tmp.renameTo(workFile)
                prefs.edit().putString(KEY_WORK_FOR, src.id).putInt(KEY_UNSAVED, count).apply()
            }
        }
    }

    private fun workingCopyFor(src: Source): ByteArray? =
        if (prefs.getString(KEY_WORK_FOR, null) == src.id && workFile.exists()) workFile.readBytes() else null

    private fun clearWorkingCopy() {
        workFile.delete()
        prefs.edit().remove(KEY_WORK_FOR).remove(KEY_UNSAVED).apply()
    }

    private fun cacheFile() = File(getApplication<Application>().filesDir, "nas_kopie.xlsx")

    private suspend fun writeUri(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Datei nicht beschreibbar")
    }

    private fun takePermission(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { resolver.takePersistableUriPermission(uri, rw) } catch (_: SecurityException) {
            try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
        }
    }

    private fun hasPermission(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KIND_LOCAL = "local"
        private const val KIND_NAS = "nas"
        private const val KEY_LOCAL = "localUri"
        private const val KEY_WORK_FOR = "workFor"
        private const val KEY_UNSAVED = "unsaved"
        private const val KEY_BASE_WRITE = "baseWrite"
        private const val KEY_EANSEARCH = "eanSearchToken"
        private const val KEY_GEMINI = "geminiKey"
    }
}
