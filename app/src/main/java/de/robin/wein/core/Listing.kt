package de.robin.wein.core

import java.util.Locale

/** Anzeigentext für Kleinanzeigen/eBay aus den Stammdaten. Alles bleibt im Dialog editierbar. */
object Listing {
    fun title(w: Wine, qty: Int): String {
        val base = listOf(w.producer, w.name, w.vintage?.toString()).filter { !it.isNullOrBlank() }
            .joinToString(" ") { it!! }
            // Erzeuger nicht doppelt, wenn er schon im Namen steckt
            .let { if (w.producer.isNotBlank() && w.name.contains(w.producer, true)) listOf(w.name, w.vintage?.toString()).filterNotNull().joinToString(" ") else it }
        val suffix = if (qty > 1) " – $qty Flaschen" else ""
        return (base + suffix).take(80)
    }

    fun description(w: Wine, qty: Int, alcohol: String, volume: String, price: Double?): String = buildString {
        append("Biete ").append(if (qty == 1) "1 Flasche" else "$qty Flaschen").append(' ')
        append(w.name)
        w.vintage?.let { append(" (Jahrgang $it)") }
        if (w.producer.isNotBlank() && !w.name.contains(w.producer, true)) append(" von ").append(w.producer)
        append(".\n\n")
        val facts = listOfNotNull(
            w.grape.takeIf { it.isNotBlank() }?.let { "Rebsorte: $it" },
            listOf(w.region, w.country).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }?.let { "Herkunft: $it" },
            w.color.takeIf { it.isNotBlank() }?.let { "Farbe: $it" },
            w.rating.takeIf { it.isNotBlank() }?.let { "Bewertung: $it" },
            volume.takeIf { it.isNotBlank() }?.let { "Füllmenge: $it" },
            alcohol.takeIf { it.isNotBlank() }?.let { "Alkohol: $it % vol" },
        )
        facts.forEach { append(it).append('\n') }
        append("Enthält Sulfite.\n")
        if (w.note.isNotBlank()) append('\n').append(w.note).append('\n')
        append("\nFlaschen ungeöffnet, kühl und dunkel gelagert.\n")
        if (qty > 1 && price != null) append("Preis je Flasche ${String.format(Locale.GERMANY, "%.2f €", price)}, gerne auch einzeln.\n")
        append("Abholung oder Versand (Versandkosten extra).\n\n")
        append("Privatverkauf, daher keine Garantie oder Rücknahme. Abgabe nur an Personen ab 18 Jahren.")
    }
}
