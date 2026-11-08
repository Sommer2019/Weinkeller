package de.robin.wein.lookup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import de.robin.wein.core.Guess
import de.robin.wein.core.Market
import de.robin.wein.core.Offer
import de.robin.wein.core.Wine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * „KI-Recherche“ über die Gemini-API (kostenloser Zugang mit eigenem Schlüssel aus Google AI Studio).
 * Gemini Flash darf im Gratis-Zugang die Google-Suche nutzen – findet also Händler- und Winzerseiten,
 * auf denen die EAN steht. Optional wird das Etikett-Foto mitgeschickt.
 * Hinweis: Im Gratis-Zugang darf Google die Anfragen zur Verbesserung seiner Produkte verwenden
 * (geschickt werden nur EAN und ggf. das Etikett-Foto).
 */
object GeminiLookup {
    // Feste Modellversion statt Alias „gemini-flash-latest“: Google rät dazu, das Modell direkt aufzurufen,
    // damit ein Wechsel hinter dem Alias die Recherche nicht unterbricht
    private const val MODEL = "gemini-3.6-flash"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    class GeminiException(msg: String) : Exception(msg)

    /**
     * Erst mit Google-Suche; ist die im Kontingent des Schlüssels nicht (mehr) drin (429),
     * noch einmal ohne Suche – dann nur Modellwissen bzw. das Etikett-Foto.
     */
    suspend fun ask(ctx: Context, key: String, ean: String?, labels: List<Uri>, page: QrPage? = null): LookupResult? = withContext(Dispatchers.IO) {
        val images = labels.mapNotNull { jpegBase64(ctx, it) }
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
            throw GeminiException("Kontingent der Gemini-Websuche erreicht – morgen wieder")
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

    private class QuotaException : Exception()

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
            return when (c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                400, 401, 403 -> throw GeminiException("Gemini lehnt den Schlüssel oder die Anfrage ab – Schlüssel prüfen")
                404 -> throw GeminiException("Gemini-Modell nicht verfügbar – App aktualisieren")
                429 -> if (search) throw QuotaException() else throw GeminiException("Tageskontingent von Gemini erreicht – morgen wieder")
                else -> throw GeminiException("Gemini-Fehler ${c.responseCode}")
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

    private fun jpegBase64(ctx: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
