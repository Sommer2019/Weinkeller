package de.robin.wein.core

/**
 * Filter für den Bestand: Weingut, Region/Appellation, Jahrgang (null = alle).
 * Verglichen wird ohne Groß-/Kleinschreibung und Akzente („Château“ = „chateau“).
 */
data class StockFilter(
    val producer: String? = null,
    val region: String? = null,
    val vintage: String? = null,
) {
    enum class Field(val label: String) { PRODUCER("Weingut"), REGION("Region"), VINTAGE("Jahrgang") }

    val active: Boolean get() = producer != null || region != null || vintage != null

    operator fun get(f: Field): String? = when (f) {
        Field.PRODUCER -> producer
        Field.REGION -> region
        Field.VINTAGE -> vintage
    }

    fun with(f: Field, value: String?): StockFilter = when (f) {
        Field.PRODUCER -> copy(producer = value)
        Field.REGION -> copy(region = value)
        Field.VINTAGE -> copy(vintage = value)
    }

    fun matches(w: Wine): Boolean = Field.entries.all { f -> this[f]?.let { same(value(w, f), it) } ?: true }

    /**
     * Wählbare Werte für ein Feld mit Anzahl Einträge – berücksichtigt die übrigen Filter,
     * damit nur Kombinationen angeboten werden, die auch etwas finden.
     */
    fun options(wines: List<Wine>, f: Field): List<Pair<String, Int>> {
        val others = with(f, null)
        val values = wines.filter { others.matches(it) }.map { value(it, f) }.filter { it.isNotBlank() }
        val grouped = values.groupBy { key(it) }.values.map { it.first() to it.size }
        return if (f == Field.VINTAGE) grouped.sortedByDescending { it.first.toIntOrNull() ?: Int.MIN_VALUE }
        else grouped.sortedBy { key(it.first) }
    }

    companion object {
        fun value(w: Wine, f: Field): String = when (f) {
            Field.PRODUCER -> w.producer.trim()
            Field.REGION -> w.region.trim()
            Field.VINTAGE -> w.vintageLabel
        }

        private fun key(s: String) = Cellar.norm(s.trim())
        private fun same(a: String, b: String) = key(a) == key(b)
    }
}
