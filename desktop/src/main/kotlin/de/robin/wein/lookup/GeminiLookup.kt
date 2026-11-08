package de.robin.wein.lookup

import de.robin.wein.core.Guess
import de.robin.wein.core.Market
import de.robin.wein.core.Offer
import de.robin.wein.core.Wine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Calendar
import javax.imageio.ImageIO

/**
 * „KI-Recherche“ über die Gemini-API (kostenloser Zugang mit eigenem Schlüssel aus Google AI Studio).
 * Gemini Flash darf im Gratis-Zugang die Google-Suche nutzen – findet also Händler- und Winzerseiten,
 * auf denen die EAN steht. Optional wird das Etikett-Foto mitgeschickt.
 * Hinweis: Im Gratis-Zugang darf Google die Anfragen zur Verbesserung seiner Produkte verwenden
 * (geschickt werden nur EAN und ggf. das Etikett-Foto).
 */
object GeminiLookup {
    // Alias auf das jeweils aktuelle Flash-Modell – Google stellt es selbst um, die App muss nicht nachziehen
    private const val MODEL = "gemini-flash-latest"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    class GeminiException(msg: String) : Exception(msg)

    /**
     * Erst mit Google-Suche; ist die im Kontingent des Schlüssels nicht (mehr) drin (429),
     * noch einmal ohne Suche – dann nur Modellwissen bzw. das Etikett-Foto.
     */
    suspend fun ask(key: String, ean: String?, labels: List<File>, page: QrPage? = null): LookupResult? = withContext(Dispatchers.IO) {
        val images = labels.mapNotNull { jpegBase64(it) }
        val response = try {
            request(key, prompt(ean, images.isNotEmpty(), page, search = true), images, search = true)
        } catch (e: QuotaException) {
            request(key, prompt(ean, images.isNotEmpty(), page, search = false), images, search = false)
        }
        parse(response)
    }

    /**
     * Marktpreis: Gemini sucht per Google nach aktuellen Angeboten des Weins und listet sie einzeln auf.
     * Den Median bildet die App selbst (Market.evaluate) – die KI liefert nur die Rohdaten.
     * Ohne Websuche ergibt das keinen Sinn, daher bei erschöpftem Kontingent ein Fehler statt eines zweiten Versuchs.
     */
    suspend fun offers(key: String, w: Wine): List<Offer> = withContext(Dispatchers.IO) {
        val response = try {
            request(key, Market.prompt(w), emptyList(), search = true)
        } catch (e: QuotaException) {
            // Minutenlimit: Google nennt die Wartezeit – einmal abwarten und noch einmal versuchen
            val wait = e.retrySeconds?.takeIf { !e.daily && it <= 60 } ?: throw GeminiException(e.text())
            kotlinx.coroutines.delay((wait + 1) * 1000L)
            try {
                request(key, Market.prompt(w), emptyList(), search = true)
            } catch (e2: QuotaException) {
                throw GeminiException(e2.text())
            }
        }
        val o = answerJson(response) ?: return@withContext emptyList()
        val a = o.optJSONArray("angebote") ?: return@withContext emptyList()
        (0 until a.length()).mapNotNull { i ->
            val x = a.optJSONObject(i) ?: return@mapNotNull null
            val price = x.optDouble("preis", Double.NaN).takeIf { !it.isNaN() }
                ?: x.optString("preis").replace("€", "").replace(',', '.').trim().toDoubleOrNull()
                ?: return@mapNotNull null
            Offer(
                shop = x.optString("shop").trim(),
                price = price,
                liters = x.optDouble("liter", Double.NaN).takeIf { !it.isNaN() && it > 0 },
                vintage = x.optInt("jahrgang", 0).takeIf { it in 1900..2100 },
            )
        }
    }

    /**
     * HTTP 429 – welches Limit, steht in der Antwort von Google: Minuten- oder Tageskontingent,
     * oder „limit: 0“ (Modell bzw. Websuche im Gratis-Zugang dieses Schlüssels gar nicht freigegeben).
     */
    private class QuotaException(val googleMessage: String, val quotaIds: List<String>, val retrySeconds: Int?) : Exception(googleMessage) {
        val daily: Boolean get() = quotaIds.any { it.contains("PerDay", ignoreCase = true) }
        val perMinute: Boolean get() = quotaIds.any { it.contains("PerMinute", ignoreCase = true) }
        val none: Boolean get() = Regex("limit:\\s*0\\b").containsMatchIn(googleMessage)

        fun text(): String = when {
            none -> "Für diesen Gemini-Schlüssel ist die Websuche bzw. das Modell nicht freigegeben (Kontingent 0)"
            daily -> "Tageskontingent der Gemini-Websuche erreicht – morgen wieder"
            perMinute -> "Zu viele Gemini-Anfragen pro Minute – kurz warten und noch einmal versuchen"
            else -> "Gemini meldet ein erreichtes Limit"
        } + googleMessage.takeIf { it.isNotBlank() }?.let { " (Google: ${it.lineSequence().first().take(160)})" }.orEmpty()
    }

    /** Fehlermeldung von Google aus der Antwort lesen (error.message). */
    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("").trim()

    private fun quotaException(body: String): QuotaException {
        val details = runCatching { JSONObject(body).optJSONObject("error")?.optJSONArray("details") }.getOrNull()
        val ids = ArrayList<String>()
        var retry: Int? = null
        if (details != null) for (i in 0 until details.length()) {
            val d = details.optJSONObject(i) ?: continue
            d.optJSONArray("violations")?.let { v ->
                for (j in 0 until v.length()) v.optJSONObject(j)?.optString("quotaId")?.takeIf { it.isNotBlank() }?.let(ids::add)
            }
            d.optString("retryDelay").takeIf { it.isNotBlank() }?.let { retry = it.removeSuffix("s").toDoubleOrNull()?.toInt() }
        }
        return QuotaException(errorMessage(body), ids, retry)
    }

    private fun request(key: String, prompt: String, images: List<String>, search: Boolean): String {
        val parts = JSONArray()
        parts.put(JSONObject().put("text", prompt))
        images.forEach { b64 ->
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.1))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code == 200) return c.inputStream.bufferedReader().use { it.readText() }
            val err = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            val google = errorMessage(err).takeIf { it.isNotBlank() }?.let { " (Google: ${it.lineSequence().first().take(160)})" }.orEmpty()
            throw when (code) {
                400, 401, 403 -> GeminiException("Gemini lehnt den Schlüssel oder die Anfrage ab – Schlüssel prüfen$google")
                404 -> GeminiException("Gemini-Modell nicht verfügbar – App aktualisieren$google")
                429 -> quotaException(err).let { q -> if (search) q else GeminiException(q.text()) }
                else -> GeminiException("Gemini-Fehler $code$google")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun prompt(ean: String?, withImage: Boolean, page: QrPage?, search: Boolean) = buildString {
        append("Du bestimmst einen Wein für eine private Kellerliste.")
        if (page != null) {
            append(" Der QR-Code auf der Flasche (E-Label) führt zu ${page.url}.")
            if (page.text.isNotBlank()) append(" Text dieser Seite: «${page.text}».")
            append(" Lies daraus Name, Weingut, Jahrgang, Herkunft und Rebsorte ab" + if (search) " – notfalls öffne bzw. suche die Seite im Web." else ".")
        }
        if (ean == null && withImage && search) append(" Suche bei Bedarf im Web nach dem, was auf dem Etikett steht (Weingut, Lage, Cuvée).")
        if (ean != null) append(" Die Flasche hat die EAN/GTIN $ean." +
            if (search) " Suche im Web nach genau dieser Nummer (Händler, Weingüter, Shops)." else " Eine Websuche steht nicht zur Verfügung.")
        if (withImage) append(" Die Fotos zeigen die Flasche bzw. ihr Etikett (vorne/hinten) – lies Name, Weingut, Jahrgang, Rebsorte und Herkunft davon ab.")
        append(" Antworte NUR mit einem JSON-Objekt, ohne weiteren Text, in dieser Form: ")
        append("""{"gefunden":true,"name":"","weingut":"","jahrgang":null,"land":"","region":"","rebsorte":"","farbe":"","alkohol":"","sicher":"hoch|mittel|niedrig"}""")
        append(". farbe ist eine von: Rot, Weiß, Rosé, Schaumwein oder leer. name ohne Weingut und ohne Jahrgang, aber mit Prädikat/Stil (z. B. \"Riesling Spätlese trocken\").")
        append(" jahrgang nur als Zahl, wenn er eindeutig zu dieser EAN gehört oder auf dem Etikett steht – dieselbe EAN gilt oft für mehrere Jahrgänge, dann null.")
        append(" Erfinde nichts: Unbekanntes leer lassen; wenn du den Wein nicht sicher findest, gefunden=false.")
    }

    /** Das JSON-Objekt aus der Antwort des Modells (steht manchmal in Text oder ```-Blöcken). */
    private fun answerJson(response: String): JSONObject? {
        val cand = JSONObject(response).optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val text = cand.optJSONObject("content")?.optJSONArray("parts")?.let { ps ->
            (0 until ps.length()).joinToString("") { ps.optJSONObject(it)?.optString("text").orEmpty() }
        }.orEmpty()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun parse(response: String): LookupResult? {
        val cand = JSONObject(response).optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val o = answerJson(response) ?: return null
        if (!o.optBoolean("gefunden", true)) return null
        val name = o.optString("name").trim()
        val producer = o.optString("weingut").trim()
        if (name.isEmpty() && producer.isEmpty()) return null
        val year = o.optInt("jahrgang", 0).takeIf { it in 1950..(Calendar.getInstance().get(Calendar.YEAR) + 1) }
        // Quellen der Google-Suche anzeigen, damit man nachprüfen kann
        val sources = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("web")?.optString("title")?.takeIf { t -> t.isNotBlank() } }
        }.orEmpty().distinct().take(3)
        val sure = o.optString("sicher")
        return LookupResult(
            source = "KI-Recherche (Gemini)" + if (sure.isNotBlank()) ", Sicherheit $sure" else "",
            name = name,
            producer = producer,
            country = o.optString("land").trim(),
            region = o.optString("region").trim(),
            grape = o.optString("rebsorte").trim(),
            color = o.optString("farbe").trim().ifEmpty { Guess.color(name) },
            vintage = year,
            alcohol = o.optString("alkohol").trim(),
            raw = listOf(producer, name, year?.toString()).filter { !it.isNullOrBlank() }.joinToString(" ") +
                if (sources.isNotEmpty()) "  ·  Quellen: " + sources.joinToString(", ") else "",
        )
    }

    /** Foto verkleinern (max. 1280 px) und als JPEG verschicken. */
    private fun jpegBase64(file: File): String? = runCatching {
        val src = ImageIO.read(file) ?: return null
        val scale = minOf(1.0, 1280.0 / maxOf(src.width, src.height))
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        Base64.getEncoder().encodeToString(out.toByteArray())
    }.getOrNull()
}
