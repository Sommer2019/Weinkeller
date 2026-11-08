package de.robin.wein.core

import java.time.LocalDate
import java.util.Locale

/** Ein Angebot aus der Websuche, wie es auf der Händlerseite steht. */
data class Offer(val shop: String, val price: Double, val liters: Double?, val vintage: Int?) {
    /** Preis auf eine 0,75-l-Flasche umgerechnet (Magnum, halbe Flasche …). */
    val perBottle: Double get() = liters?.takeIf { it in 0.1..6.0 }?.let { price * 0.75 / it } ?: price
}

/** Ergebnis: Median und Spanne der passenden Angebote. */
data class MarketPrice(val median: Double, val min: Double, val max: Double, val offers: List<Offer>) {
    val count: Int get() = offers.size
}

/** „Marktpreis im Netz“: Angebote auswerten – der Median ist robust gegen einzelne Ausreißer (Raritätenhändler, Lockpreise). */
object Market {

    /** Auftrag an die KI-Websuche. */
    fun prompt(w: Wine): String = buildString {
        append("Du ermittelst den aktuellen Marktpreis eines Weins für eine private Kellerliste. ")
        append("Wein: ").append(listOf(w.producer, w.name).filter { it.isNotBlank() }.joinToString(" – "))
        append(", Jahrgang ").append(w.vintage?.toString() ?: "ohne Jahrgang (NV)")
        val origin = listOf(w.region, w.country).filter { it.isNotBlank() }.joinToString(", ")
        if (origin.isNotBlank()) append(", Herkunft ").append(origin)
        if (w.grape.isNotBlank()) append(", Rebsorte ").append(w.grape)
        if (w.ean.isNotBlank() && !w.ean.startsWith("X")) append(", EAN ").append(w.ean)
        append(". Suche im Web nach aktuellen Kaufangeboten dieses Weins (Online-Händler, Weinshops, Shop des Weinguts; ")
        append("bevorzugt Deutschland und EU, Preise in Euro). Nimm jedes Angebot einzeln auf – bis zu 15. ")
        append("Keine Auktionen, keine Gebrauchtanzeigen, keine Durchschnittswerte von Preisvergleichsseiten. ")
        append("Gilt ein Preis für einen Karton, rechne ihn auf eine Flasche um. ")
        append("Gib bei jedem Angebot den Jahrgang an, der dort steht – auch wenn er vom gesuchten abweicht – sonst null. ")
        append("Antworte NUR mit einem JSON-Objekt, ohne weiteren Text, in dieser Form: ")
        append("""{"angebote":[{"shop":"","preis":0.0,"liter":0.75,"jahrgang":null}]}""")
        append(". preis = Euro je Flasche als Zahl, liter = Flaschengröße. ")
        append("Nur Angebote, die du tatsächlich auf einer Seite gefunden hast – erfinde nichts. Nichts gefunden: leere Liste.")
    }

    /**
     * Passende Angebote auswählen und den Median bilden.
     * Mit Jahrgang zählen nur Angebote genau dieses Jahrgangs; gibt es keine, die ohne Jahrgangsangabe.
     * Je Shop nur ein Angebot (das günstigste), damit ein Händler mit vielen Varianten nicht alles bestimmt.
     */
    fun evaluate(offers: List<Offer>, vintage: Int?): MarketPrice? {
        val valid = offers.filter { it.price in 1.0..100_000.0 }
        val matching = if (vintage == null) valid.filter { it.vintage == null }.ifEmpty { valid }
        else valid.filter { it.vintage == vintage }.ifEmpty { valid.filter { it.vintage == null } }
        val perShop = matching.groupBy { it.shop.trim().lowercase().ifEmpty { "?" + it.hashCode() } }
            .values.map { list -> list.minBy { it.perBottle } }
        if (perShop.isEmpty()) return null
        val prices = perShop.map { it.perBottle }
        return MarketPrice(median(prices), prices.min(), prices.max(), perShop.sortedBy { it.perBottle })
    }

    fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Ergebnis in den Wein übernehmen. */
    fun apply(w: Wine, p: MarketPrice, date: LocalDate): Wine {
        val shops = p.offers.map { it.shop.trim() }.filter { it.isNotEmpty() }.distinct()
        val range = if (p.count > 1) ", ${eur(p.min)}–${eur(p.max)}" else ""
        val info = "${p.count} ${if (p.count == 1) "Angebot" else "Angebote"}$range" +
            if (shops.isNotEmpty()) " (" + shops.take(4).joinToString(", ") + (if (shops.size > 4) " …" else "") + ")" else ""
        return w.copy(marketPrice = Math.round(p.median * 100) / 100.0, marketDate = date, marketInfo = info)
    }

    /** Selbst eingetragener Marktpreis (z. B. aus Wine-Searcher, Händlerliste); price = null entfernt ihn. */
    fun manual(w: Wine, price: Double?, info: String, date: LocalDate): Wine =
        if (price == null) w.copy(marketPrice = null, marketDate = null, marketInfo = "")
        else w.copy(marketPrice = Math.round(price * 100) / 100.0, marketDate = date, marketInfo = info.trim().ifEmpty { "selbst eingetragen" })

    private fun eur(v: Double) = String.format(Locale.GERMANY, "%.2f €", v)
}
