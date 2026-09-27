package de.robin.wein.core

/** Eine erkannte Textzeile vom Etikett; height = Schrifthöhe in Pixeln (groß = wichtig). */
data class LabelLine(val text: String, val height: Int)

data class LabelResult(
    val name: String,
    val producer: String,
    val region: String,
    val country: String,
    val grape: String,
    val color: String,
    val years: List<Int>,          // alle plausiblen Jahrgänge auf dem Etikett
    val raw: String,
) {
    /** Nur eindeutig, wenn genau ein Jahr gefunden wurde. */
    val vintage: Int? get() = years.singleOrNull()
}

/**
 * Heuristik fürs Etikett: größte Schrift = Name, Zeile mit „Weingut/Domaine/…“ = Erzeuger,
 * bekannte Rebsorten/Regionen per Wortliste, Jahrgang 1950…heute.
 */
object LabelParser {
    private val PRODUCER = listOf("weingut", "winzer", "domaine", "chateau", "château", "tenuta", "bodega", "bodegas", "cantina",
        "cantine", "schloss", "kellerei", "weinhaus", "gut ", "estate", "winery", "maison", "castello", "fattoria", "quinta", "clos ")
    private val NOISE = listOf("% vol", "%vol", "vol.", "0,75", "0.75", "75 cl", "75cl", "750 ml", "liter", "enthält", "contains",
        "sulfite", "sulphite", "abfüllung", "abfueller", "abgefüllt", "a.p.nr", "a. p. nr", "ap-nr", "qualitätswein", "produce of",
        "product of", "mis en bouteille", "imbottigliato", "embotellado", "allergen", "www.", ".de", ".com", "tel.", "℮")
    private val PRAEDIKAT = listOf("kabinett", "spätlese", "auslese", "beerenauslese", "eiswein", "trocken", "halbtrocken",
        "feinherb", "lieblich", "grosses gewächs", "großes gewächs", "reserva", "gran reserva", "crianza", "riserva", "superiore", "classico")
    private val GRAPES = listOf("Riesling", "Spätburgunder", "Pinot Noir", "Grauburgunder", "Pinot Grigio", "Pinot Gris", "Weißburgunder",
        "Weissburgunder", "Pinot Blanc", "Silvaner", "Müller-Thurgau", "Rivaner", "Dornfelder", "Lemberger", "Blaufränkisch", "Trollinger",
        "Portugieser", "Regent", "Scheurebe", "Kerner", "Gewürztraminer", "Chardonnay", "Sauvignon Blanc", "Sauvignon", "Chenin Blanc",
        "Merlot", "Cabernet Sauvignon", "Cabernet Franc", "Cabernet", "Syrah", "Shiraz", "Grenache", "Garnacha", "Tempranillo", "Sangiovese",
        "Nebbiolo", "Barbera", "Primitivo", "Zinfandel", "Montepulciano", "Nero d'Avola", "Malbec", "Carménère", "Pinotage",
        "Grüner Veltliner", "Zweigelt", "Verdejo", "Albariño", "Vermentino", "Viognier", "Muskateller", "Moscato", "Gamay", "Mourvèdre")
    private val REGIONS = mapOf(
        "Mosel" to "Deutschland", "Rheingau" to "Deutschland", "Rheinhessen" to "Deutschland", "Pfalz" to "Deutschland",
        "Nahe" to "Deutschland", "Ahr" to "Deutschland", "Baden" to "Deutschland", "Württemberg" to "Deutschland",
        "Franken" to "Deutschland", "Mittelrhein" to "Deutschland", "Saale-Unstrut" to "Deutschland", "Sachsen" to "Deutschland",
        "Hessische Bergstraße" to "Deutschland",
        "Bordeaux" to "Frankreich", "Bourgogne" to "Frankreich", "Burgund" to "Frankreich", "Champagne" to "Frankreich",
        "Alsace" to "Frankreich", "Elsass" to "Frankreich", "Côtes du Rhône" to "Frankreich", "Rhône" to "Frankreich",
        "Languedoc" to "Frankreich", "Loire" to "Frankreich", "Provence" to "Frankreich", "Beaujolais" to "Frankreich",
        "Chianti" to "Italien", "Toscana" to "Italien", "Toskana" to "Italien", "Piemonte" to "Italien", "Piemont" to "Italien",
        "Veneto" to "Italien", "Puglia" to "Italien", "Apulien" to "Italien", "Sicilia" to "Italien", "Barolo" to "Italien",
        "Valpolicella" to "Italien", "Montalcino" to "Italien", "Abruzzo" to "Italien",
        "Rioja" to "Spanien", "Ribera del Duero" to "Spanien", "Priorat" to "Spanien", "Navarra" to "Spanien", "La Mancha" to "Spanien",
        "Rueda" to "Spanien", "Douro" to "Portugal", "Alentejo" to "Portugal", "Vinho Verde" to "Portugal",
        "Wachau" to "Österreich", "Kamptal" to "Österreich", "Burgenland" to "Österreich", "Steiermark" to "Österreich",
        "Mendoza" to "Argentinien", "Napa" to "USA", "Sonoma" to "USA", "Barossa" to "Australien", "Stellenbosch" to "Südafrika",
    )
    private val YEAR = Regex("(?<!\\d)(19[5-9]\\d|20[0-4]\\d)(?!\\d)")

    fun parse(lines: List<LabelLine>, maxYear: Int): LabelResult {
        val all = lines.joinToString("\n") { it.text }
        val lower = all.lowercase()
        val years = YEAR.findAll(all).map { it.value.toInt() }.filter { it <= maxYear }.distinct().sortedDescending().toList()

        fun isNoise(t: String): Boolean {
            val l = t.lowercase().trim()
            if (l.length < 3) return true
            if (NOISE.any { it in l }) return true
            if (YEAR.matches(l)) return true
            val letters = l.count { it.isLetter() }
            return letters < l.length * 0.6
        }
        val candidates = lines.filter { !isNoise(it.text) }.sortedByDescending { it.height }
        val producerLine = candidates.firstOrNull { c -> PRODUCER.any { it in c.text.lowercase() + " " } }
        val nameLine = candidates.firstOrNull { it != producerLine }
        // „Riesling“ + „Spätlese trocken“ → „Riesling Spätlese trocken“
        val extra = candidates.filter { it != nameLine && it != producerLine && PRAEDIKAT.any { p -> p in it.text.lowercase() } }
            .filter { it.text.length <= 30 }.take(1)
        val name = (listOfNotNull(nameLine) + extra).joinToString(" ") { cleanup(it.text) }
        val producer = producerLine?.text ?: candidates.getOrNull(1)?.takeIf { it.text != name }?.text.orEmpty()

        val grape = GRAPES.firstOrNull { it.lowercase() in lower }.orEmpty()
        val region = REGIONS.keys.firstOrNull { Regex("\\b${Regex.escape(it.lowercase())}\\b").containsMatchIn(lower) }.orEmpty()
        return LabelResult(
            name = name,
            producer = cleanup(producer),
            region = region,
            country = REGIONS[region].orEmpty(),
            grape = grape,
            color = Guess.color(all, grape.let { g -> RED_GRAPES.firstOrNull { it.equals(g, true) }?.let { "rotwein" } ?: "" }),
            years = years,
            raw = lines.joinToString(" · ") { it.text }.take(400),
        )
    }

    private val RED_GRAPES = listOf("Spätburgunder", "Pinot Noir", "Dornfelder", "Lemberger", "Blaufränkisch", "Trollinger", "Portugieser",
        "Regent", "Merlot", "Cabernet Sauvignon", "Cabernet Franc", "Cabernet", "Syrah", "Shiraz", "Grenache", "Garnacha", "Tempranillo",
        "Sangiovese", "Nebbiolo", "Barbera", "Primitivo", "Zinfandel", "Montepulciano", "Nero d'Avola", "Malbec", "Carménère",
        "Pinotage", "Zweigelt", "Gamay", "Mourvèdre")

    /** GROSSBUCHSTABEN vom Etikett lesbar machen. */
    private fun cleanup(s: String): String {
        val t = s.trim().trim('-', '·', ',', '.')
        if (t.any { it.isLowerCase() }) return t
        return t.lowercase().split(' ').joinToString(" ") { w ->
            w.split('-').joinToString("-") { p -> p.replaceFirstChar { it.titlecase() } }
        }
    }
}
