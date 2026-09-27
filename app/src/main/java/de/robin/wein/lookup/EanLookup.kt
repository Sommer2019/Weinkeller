package de.robin.wein.lookup

import de.robin.wein.core.Guess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

/** Was online über eine EAN gefunden wurde. Leere Felder = unbekannt. */
data class LookupResult(
    val source: String,
    val name: String,
    val producer: String = "",
    val country: String = "",
    val color: String = "",
    val vintage: Int? = null,
    val raw: String = "",           // Originaltext, falls der Nutzer selbst nachlesen will
    val imageUrl: String = "",      // Produktfoto, falls die Quelle eins hat
    val region: String = "",
    val grape: String = "",
    val alcohol: String = "",       // z. B. "12,5"
)

/** Zugangsdaten für die optionalen Quellen. */
data class LookupConfig(
    val eanSearchToken: String = "",              // ean-search.org (kostenpflichtig) – leer = aus
    val geminiKey: String = "",                   // Google AI Studio (kostenlos) – KI-Recherche mit Google-Suche
)

/**
 * Freie Quellen ohne API-Schlüssel:
 * 1. Open Food Facts (gut bei Supermarkt-Weinen in Europa)
 * 2. UPCitemdb, Testzugang (100 Abfragen/Tag)
 * Findet keine etwas, bleibt die Websuche im Browser und die Eingabe von Hand.
 */
object EanLookup {
    private const val UA = "Weinkeller-App/1.0 (privat, Android)"

    /**
     * Reihenfolge: Open Food Facts → UPCitemdb → (optional) KI-Recherche mit Gemini → (optional) EAN-Search.
     * Die erste Quelle mit Treffer gewinnt; ein Foto wird notfalls aus einer anderen Quelle ergänzt.
     * Findet niemand die Flasche, liefert EAN-Search per Präfix wenigstens den Hersteller.
     */
    suspend fun lookup(
        ctx: android.content.Context, ean: String, cfg: LookupConfig = LookupConfig(),
        onAiError: (String) -> Unit = {},
    ): List<LookupResult> = withContext(Dispatchers.IO) {
        val sources = listOf<suspend () -> LookupResult?>(
            { openFoodFacts(ean) },
            { upcItemDb(ean) },
            {
                if (cfg.geminiKey.isBlank()) null
                // Fehler der KI (Schlüssel, Kontingent …) melden statt still zu schlucken
                else runCatching { GeminiLookup.ask(ctx, cfg.geminiKey, ean, null) }
                    .onFailure { onAiError((it as? GeminiLookup.GeminiException)?.message ?: "KI-Recherche fehlgeschlagen") }
                    .getOrNull()
            },
            { if (cfg.eanSearchToken.isBlank()) null else eanSearch(ean, cfg.eanSearchToken) },
        )
        var hit: LookupResult? = null
        for (src in sources) {
            hit = runCatching { src() }.getOrNull()
            if (hit != null) break
        }
        if (hit == null && cfg.eanSearchToken.isNotBlank()) {
            hit = runCatching { eanSearchVendor(ean, cfg.eanSearchToken) }.getOrNull()
        }
        listOfNotNull(hit)
    }

    /** ean-search.org – kostenpflichtig, nur mit eigenem Token. */
    private fun eanSearch(ean: String, token: String): LookupResult? {
        val json = get("https://api.ean-search.org/api?token=${enc(token)}&op=barcode-lookup&format=json&ean=$ean") ?: return null
        val arr = org.json.JSONArray(json)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        if (o.has("error")) return null
        val name = o.optString("name").trim().ifEmpty { return null }
        return LookupResult(
            source = "EAN-Search",
            name = name,
            color = Guess.color(name, o.optString("categoryName")),
            vintage = Guess.vintage(name, maxYear = year()),
            raw = listOf(name, o.optString("categoryName")).filter { it.isNotBlank() }.joinToString(" · "),
        )
    }

    /**
     * Die ersten Ziffern einer EAN gehören dem Hersteller. Kennt EAN-Search andere Produkte mit diesem Präfix,
     * zeigen wir sie als Hinweis – Name und Jahrgang bleiben leer, nur der Erzeuger wird vorgeschlagen.
     */
    private fun eanSearchVendor(ean: String, token: String): LookupResult? {
        for (len in intArrayOf(9, 7)) {
            if (ean.length <= len) continue
            val json = get("https://api.ean-search.org/api?token=${enc(token)}&op=barcode-prefix-search&format=json&prefix=${ean.take(len)}") ?: continue
            val arr = org.json.JSONArray(json)
            val names = (0 until arr.length()).map { arr.getJSONObject(it) }.filterNot { it.has("error") }
                .map { it.optString("name") }.filter { it.isNotBlank() }
            if (names.isEmpty()) continue
            return LookupResult(
                source = "EAN-Search (gleicher Hersteller)",
                name = "",
                producer = commonPrefix(names),
                raw = "Andere Produkte dieses Herstellers: " + names.take(4).joinToString("; "),
            )
        }
        return null
    }

    /** Gemeinsamer Wortanfang mehrerer Produktnamen – oft der Erzeuger („Weingut X Riesling …“). */
    private fun commonPrefix(names: List<String>): String {
        if (names.size < 2) return ""
        val words = names.map { it.split(' ') }
        val common = ArrayList<String>()
        for (i in words.first().indices) {
            val w = words.first()[i]
            if (words.all { it.getOrNull(i).equals(w, ignoreCase = true) }) common += w else break
        }
        return common.joinToString(" ").takeIf { it.length >= 3 }.orEmpty()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun webSearchUrl(ean: String): String =
        "https://www.google.com/search?q=" + URLEncoder.encode("$ean Wein", "UTF-8")

    private fun openFoodFacts(ean: String): LookupResult? {
        val json = get("https://world.openfoodfacts.org/api/v2/product/$ean.json?fields=product_name,product_name_de,generic_name,brands,countries,origins,categories_tags,quantity,labels,image_front_url,image_url") ?: return null
        val o = JSONObject(json)
        if (o.optInt("status") != 1) return null
        val p = o.optJSONObject("product") ?: return null
        val name = p.optString("product_name_de").ifBlank { p.optString("product_name") }.trim()
        if (name.isEmpty()) return null
        val generic = p.optString("generic_name")
        val cats = p.optJSONArray("categories_tags")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } }.orEmpty()
        val country = (p.optString("origins").ifBlank { p.optString("countries") }).split(',').firstOrNull().orEmpty()
            .substringAfter(':').trim().replaceFirstChar { it.uppercase() }
        return LookupResult(
            source = "Open Food Facts",
            name = name,
            producer = p.optString("brands").split(',').firstOrNull().orEmpty().trim(),
            country = country,
            color = Guess.color(name, generic, cats),
            vintage = Guess.vintage(name, generic, p.optString("labels"), maxYear = year()),
            raw = listOf(name, generic, p.optString("quantity")).filter { it.isNotBlank() }.joinToString(" · "),
            imageUrl = p.optString("image_front_url").ifBlank { p.optString("image_url") },
        )
    }

    private fun upcItemDb(ean: String): LookupResult? {
        val json = get("https://api.upcitemdb.com/prod/trial/lookup?upc=$ean") ?: return null
        val items = JSONObject(json).optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val it = items.getJSONObject(0)
        val title = it.optString("title").trim()
        if (title.isEmpty()) return null
        val desc = it.optString("description")
        return LookupResult(
            source = "UPCitemdb",
            name = title,
            producer = it.optString("brand").trim(),
            color = Guess.color(title, desc, it.optString("category")),
            vintage = Guess.vintage(title, desc, maxYear = year()),
            raw = listOf(title, desc).filter { s -> s.isNotBlank() }.joinToString(" · ").take(300),
            imageUrl = it.optJSONArray("images")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } }
                ?.firstOrNull { u -> u.startsWith("https://") }.orEmpty(),
        )
    }

    private fun year() = Calendar.getInstance().get(Calendar.YEAR)

    private fun get(url: String): String? = getBytes(url)?.toString(Charsets.UTF_8)

    private fun getBytes(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
