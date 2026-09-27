package de.robin.wein.core

/** Heuristiken, um aus Produkttexten Jahrgang und Farbe zu lesen. */
object Guess {
    private val YEAR = Regex("(?<!\\d)(19[5-9]\\d|20[0-4]\\d)(?!\\d)")

    /** Jahrgang nur, wenn genau ein plausibles Jahr im Text steht – sonst manuell. */
    fun vintage(vararg texts: String?, maxYear: Int): Int? {
        val found = texts.filterNotNull().flatMap { t -> YEAR.findAll(t).map { it.value.toInt() }.toList() }
            .filter { it <= maxYear }.distinct()
        return found.singleOrNull()
    }

    fun color(vararg texts: String?): String {
        val t = Cellar.norm(texts.filterNotNull().joinToString(" "))
        return when {
            listOf("rose", "rosado", "rosato", "blush").any { it in t } -> "Rosé"
            listOf("sekt", "champagne", "champagner", "cava", "prosecco", "cremant", "sparkling", "schaumwein", "spumante").any { it in t } -> "Schaumwein"
            listOf("red-wine", "red wine", "rotwein", "vin rouge", "vino rosso", "vino tinto", "rouge", "rosso", "tinto", "rot").any { it in t } -> "Rot"
            listOf("white-wine", "white wine", "weisswein", "vin blanc", "vino bianco", "vino blanco", "blanc", "bianco", "blanco", "weiss").any { it in t } -> "Weiß"
            else -> ""
        }
    }
}
