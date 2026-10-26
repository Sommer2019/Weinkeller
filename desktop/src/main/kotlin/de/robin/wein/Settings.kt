package de.robin.wein

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Ordner für Einstellungen, Arbeitskopie und Bilder: %APPDATA%\Weinkeller (sonst ~/.weinkeller). */
val dataDir: File by lazy {
    val appData = System.getenv("APPDATA")
    (if (!appData.isNullOrBlank()) File(appData, "Weinkeller") else File(System.getProperty("user.home"), ".weinkeller"))
        .apply { mkdirs() }
}

private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/** Gegenstück zu Androids SharedPreferences: eine Properties-Datei, nach jeder Änderung gespeichert. */
class Prefs(private val file: File = File(dataDir, "einstellungen.properties")) {
    private val p = Properties().apply { if (file.exists()) runCatching { file.inputStream().use { load(it) } } }

    fun getString(key: String, def: String? = null): String? = p.getProperty(key) ?: def
    fun getInt(key: String, def: Int): Int = p.getProperty(key)?.toIntOrNull() ?: def
    fun getLong(key: String, def: Long): Long = p.getProperty(key)?.toLongOrNull() ?: def

    fun edit(block: Editor.() -> Unit) {
        synchronized(p) {
            Editor().block()
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.outputStream().use { p.store(it, "Weinkeller") }
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    inner class Editor {
        fun putString(key: String, v: String?) { if (v == null) p.remove(key) else p.setProperty(key, v) }
        fun putInt(key: String, v: Int) { p.setProperty(key, v.toString()) }
        fun putLong(key: String, v: Long) { p.setProperty(key, v.toString()) }
        fun remove(key: String) { p.remove(key) }
    }
}

/**
 * Passwörter/Schlüssel verschlüsselt ablegen. Unter Windows mit der Datenschutz-API (DPAPI):
 * entschlüsseln kann nur dasselbe Windows-Benutzerkonto. Sonst (Linux/macOS zum Entwickeln)
 * AES mit einem Schlüssel in einer Datei im Einstellungsordner.
 */
object SecretStore {
    private const val WIN = "dpapi:"

    fun encrypt(plain: String): String =
        if (isWindows) WIN + Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(plain.toByteArray()))
        else {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, fileKey(), GCMParameterSpec(128, iv))
            Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
        }

    fun decrypt(s: String): String? = runCatching {
        if (s.startsWith(WIN)) String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(s.removePrefix(WIN))))
        else {
            val all = Base64.getDecoder().decode(s)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, fileKey(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12))
        }
    }.getOrNull()

    private fun fileKey(): SecretKeySpec {
        val f = File(dataDir, "schluessel.bin")
        if (!f.exists()) {
            f.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
            f.setReadable(false, false); f.setReadable(true, true)
        }
        return SecretKeySpec(f.readBytes(), "AES")
    }
}
