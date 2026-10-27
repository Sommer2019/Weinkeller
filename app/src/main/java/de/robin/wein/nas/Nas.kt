package de.robin.wein.nas

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Verbindung zu einer Datei auf einer Samba-Freigabe.
 * address: smb://nas/freigabe/ordner/datei.xlsx  oder  \\nas\freigabe\ordner\datei.xlsm
 */
data class NasConfig(
    val address: String,
    val user: String,
    val password: String,
    val domain: String,
) {
    val host: String get() = parts().getOrElse(0) { "" }
    val share: String get() = parts().getOrElse(1) { "" }
    /** Pfad innerhalb der Freigabe, mit Backslashes. */
    val path: String get() = parts().drop(2).joinToString("\\")
    val fileName: String get() = parts().lastOrNull().orEmpty()
    val id: String get() = "nas://$host/$share/$path"
    /** Ordner für die eigenen Weinfotos, neben der Weinliste. */
    val photoDir: String get() = (parts().drop(2).dropLast(1) + PHOTO_DIR).joinToString("\\")

    fun isValid() = host.isNotBlank() && share.isNotBlank() && path.isNotBlank()

    private fun parts(): List<String> = address.trim()
        .removePrefix("smb://").removePrefix("SMB://")
        .replace('\\', '/')
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() }
}

const val PHOTO_DIR = "Weinkeller-Fotos"

class NasConflictException : Exception("Die Datei wurde inzwischen auf dem NAS geändert")

data class NasFile(val bytes: ByteArray, val lastWrite: Long)

object Nas {

    private val config: SmbConfig = SmbConfig.builder()
        .withTimeout(15, TimeUnit.SECONDS)
        .withSoTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun read(cfg: NasConfig): NasFile = withContext(Dispatchers.IO) {
        withShare(cfg) { disk ->
            val bytes = disk.openFile(
                cfg.path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null,
            ).use { f -> f.inputStream.use { it.readBytes() } }
            NasFile(bytes, lastWrite(disk, cfg.path))
        }
    }

    /** Gibt es die Datei schon? (Verbindung/Anmeldung müssen klappen, sonst Exception) */
    suspend fun exists(cfg: NasConfig): Boolean = withContext(Dispatchers.IO) {
        withShare(cfg) { disk -> disk.fileExists(cfg.path) }
    }

    /** Kurzer Verbindungstest: liefert eine lesbare Beschreibung der Datei. */
    suspend fun test(cfg: NasConfig): String = withContext(Dispatchers.IO) {
        withShare(cfg) { disk ->
            if (!disk.fileExists(cfg.path)) return@withShare "Verbunden – ${cfg.fileName} gibt es noch nicht, wird beim ersten Speichern angelegt"
            val info = disk.getFileInformation(cfg.path)
            val kb = info.standardInformation.endOfFile / 1024
            "Verbunden – ${cfg.fileName}, $kb KB"
        }
    }

    /**
     * Schreibt sicher zurück: erst .tmp, dann alte Datei → .bak, dann .tmp → Ziel.
     * Hat sich die Datei seit dem Laden geändert (anderer Zeitstempel), gibt es eine
     * NasConflictException – außer bei force.
     */
    suspend fun write(cfg: NasConfig, bytes: ByteArray, expectedLastWrite: Long?, force: Boolean): Long =
        withContext(Dispatchers.IO) {
            withShare(cfg) { disk ->
                val path = cfg.path
                if (!force && expectedLastWrite != null && disk.fileExists(path) && lastWrite(disk, path) != expectedLastWrite) {
                    throw NasConflictException()
                }
                val tmp = "$path.tmp"
                val bak = path.substringBeforeLast('.') + ".bak"
                disk.openFile(
                    tmp, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
                ).use { f -> f.outputStream.use { it.write(bytes) } }
                try {
                    if (disk.fileExists(path)) {
                        disk.openFile(
                            path, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN, null,
                        ).use { it.rename(bak, true) }
                    }
                    disk.openFile(
                        tmp, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN, null,
                    ).use { it.rename(path, true) }
                } catch (e: SMBApiException) {
                    // Umbenennen nicht erlaubt (z. B. Rechte) → direkt überschreiben
                    disk.openFile(
                        path, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
                    ).use { f -> f.outputStream.use { it.write(bytes) } }
                    runCatching { disk.rm(tmp) }
                }
                lastWrite(disk, path)
            }
        }

    // ---------- Fotos ----------

    /** Fotos, die schon im Foto-Ordner auf dem NAS liegen (leer, wenn es den Ordner noch nicht gibt). */
    suspend fun listPhotos(cfg: NasConfig): Set<String> = withContext(Dispatchers.IO) {
        withShare(cfg) { disk ->
            if (!disk.folderExists(cfg.photoDir)) emptySet()
            else disk.list(cfg.photoDir).map { it.fileName }.filter { it != "." && it != ".." }.toSet()
        }
    }

    suspend fun readPhoto(cfg: NasConfig, name: String): ByteArray = withContext(Dispatchers.IO) {
        withShare(cfg) { disk ->
            disk.openFile(
                "${cfg.photoDir}\\$name", EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null,
            ).use { f -> f.inputStream.use { it.readBytes() } }
        }
    }

    /** Lädt mehrere Fotos in einer Verbindung hoch; legt den Ordner bei Bedarf an. */
    suspend fun writePhotos(cfg: NasConfig, photos: Map<String, ByteArray>) = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext
        withShare(cfg) { disk ->
            if (!disk.folderExists(cfg.photoDir)) disk.mkdir(cfg.photoDir)
            photos.forEach { (name, bytes) ->
                disk.openFile(
                    "${cfg.photoDir}\\$name", EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
                ).use { f -> f.outputStream.use { it.write(bytes) } }
            }
        }
    }

    private fun lastWrite(disk: DiskShare, path: String): Long =
        disk.getFileInformation(path).basicInformation.lastWriteTime.toEpochMillis()

    private fun <T> withShare(cfg: NasConfig, block: (DiskShare) -> T): T {
        require(cfg.isValid()) { "Adresse unvollständig – Format: smb://nas/freigabe/ordner/weine.xlsx" }
        return SMBClient(config).use { client ->
            client.connect(cfg.host).use { conn ->
                val auth = if (cfg.user.isBlank()) AuthenticationContext.guest()
                else AuthenticationContext(cfg.user, cfg.password.toCharArray(), cfg.domain.ifBlank { null })
                conn.authenticate(auth).use { session ->
                    (session.connectShare(cfg.share) as DiskShare).use { disk -> block(disk) }
                }
            }
        }
    }

    // ---------- Einstellungen ----------

    fun load(prefs: SharedPreferences): NasConfig? {
        val address = prefs.getString("nas_address", null) ?: return null
        return NasConfig(
            address,
            prefs.getString("nas_user", "").orEmpty(),
            prefs.getString("nas_pw", null)?.let(SecretStore::decrypt).orEmpty(),
            prefs.getString("nas_domain", "").orEmpty(),
        )
    }

    fun store(prefs: SharedPreferences, cfg: NasConfig) {
        prefs.edit()
            .putString("nas_address", cfg.address.trim())
            .putString("nas_user", cfg.user.trim())
            .putString("nas_pw", SecretStore.encrypt(cfg.password))
            .putString("nas_domain", cfg.domain.trim())
            .apply()
    }

    fun forget(prefs: SharedPreferences) {
        prefs.edit().remove("nas_address").remove("nas_user").remove("nas_pw").remove("nas_domain").apply()
    }
}

/** Passwort mit einem Schlüssel aus dem Android-Keystore verschlüsseln (verlässt das Gerät nie). */
object SecretStore {
    private const val ALIAS = "wein_nas"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(s: String): String? = runCatching {
        val all = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12))
    }.getOrNull()
}
