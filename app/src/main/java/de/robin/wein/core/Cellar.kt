package de.robin.wein.core

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.IdentityHashMap

/** Ein Wein = EAN + Jahrgang (dieselbe EAN wird oft über mehrere Jahrgänge benutzt). */
data class Wine(
    val ean: String,
    val vintage: Int?,              // null = ohne Jahrgang (NV)
    val name: String,
    val producer: String = "",
    val country: String = "",
    val region: String = "",
    val grape: String = "",
    val color: String = "",
    val note: String = "",
    val source: String = "",        // woher die Stammdaten kamen (Open Food Facts, manuell …)
    val imageUrl: String = "",      // Flaschenbild aus der Online-Suche (wird auf dem Gerät zwischengespeichert)
    /** Spalten, die jemand in Excel selbst ergänzt hat – werden unverändert mitgeschrieben. */
    val extra: Map<String, String> = emptyMap(),
) {
    val key: String get() = keyOf(ean, vintage)
    val vintageLabel: String get() = vintage?.toString() ?: "o. J."

    companion object {
        fun keyOf(ean: String, vintage: Int?) = "${ean.trim()}|${vintage ?: "NV"}"
    }
}

enum class MoveKind(val label: String) { BUY("Einkauf"), SELL("Verkauf") }

data class Movement(
    val date: LocalDate,
    val key: String,
    val kind: MoveKind,
    val quantity: Int,
    val price: Double?,             // pro Flasche
    val note: String = "",
)

/** Kennzahlen je Wein, berechnet aus den Bewegungen. */
data class Stats(
    val stock: Int,
    val bought: Int,
    val sold: Int,
    val avgBuy: Double?,
    val avgSell: Double?,
    val revenue: Double,
    val lastBuyPrice: Double?,
    val lastSellPrice: Double?,
    /** Realisierter Gewinn (FIFO) aller Verkäufe mit Preis; null, wenn nichts verkauft wurde. */
    val profit: Double? = null,
    /** Verkäufe, bei denen der Einkaufspreis fehlt – deren Gewinn ist nicht berechenbar. */
    val unknownCost: Int = 0,
)

/**
 * Ergebnis eines Verkaufs nach FIFO: die zuerst gekauften Flaschen gehen zuerst raus.
 * cost = Einkaufswert der verkauften Flaschen (null, wenn ein Einkaufspreis fehlt).
 * profit = Erlös − cost; null bei unbekanntem Einkauf oder Verkauf ohne Preis (Eigenverbrauch/Geschenk).
 */
data class Sale(val movement: Movement, val cost: Double?, val profit: Double?) {
    val revenue: Double get() = (movement.price ?: 0.0) * movement.quantity
    val ownUse: Boolean get() = movement.price == null
}

/** Summen für einen Monat oder ein Jahr. */
data class Period(
    val label: String,              // "2026-09" bzw. "2026"
    val spent: Double,              // Einkäufe
    val revenue: Double,            // Verkaufserlöse
    val profit: Double,             // realisierter Gewinn/Verlust (FIFO)
    val bottlesIn: Int,
    val bottlesOut: Int,
    val ownUseValue: Double,        // Warenwert Eigenverbrauch/Geschenke (Verkauf ohne Preis)
    val unknown: Int,               // Verkäufe ohne bekannten Einkaufspreis
    val sales: List<Sale>,
    val movements: List<Movement> = emptyList(),
) {
    /** Kassensicht: was rein- und rausging (Einkauf zählt negativ). */
    val cash: Double get() = revenue - spent
}

class Cellar(wines: List<Wine> = emptyList(), movements: List<Movement> = emptyList()) {
    val wines: List<Wine> = wines.sortedWith(compareBy({ it.name.lowercase() }, { it.vintage ?: 0 }))
    val movements: List<Movement> = movements.sortedBy { it.date }
    private val byKey = this.wines.associateBy { it.key }
    /** Alle Verkäufe mit FIFO-Gewinn, gleiche Reihenfolge wie movements. */
    val sales: List<Sale> = computeSales()
    // Identität statt equals: zwei gleiche Buchungen am selben Tag sind trotzdem zwei Buchungen
    private val saleOf: Map<Movement, Sale> = IdentityHashMap<Movement, Sale>().also { m -> sales.forEach { m[it.movement] = it } }
    private val stats: Map<String, Stats> = computeStats()

    fun sale(m: Movement): Sale? = saleOf[m]

    /** Gewinn/Verlust über die Zeit, nach Monat oder Jahr, optional nur in [from, to]. Neueste zuerst. */
    fun periods(byYear: Boolean, from: LocalDate? = null, to: LocalDate? = null): List<Period> =
        group(from, to) { d -> if (byYear) "${d.year}" else "%04d-%02d".format(d.year, d.monthValue) }

    /** Summen je Tag (für den Kalender), Schlüssel = Datum. */
    fun days(from: LocalDate, to: LocalDate): Map<LocalDate, Period> =
        group(from, to) { it.toString() }.associateBy { LocalDate.parse(it.label) }

    /** Eine Summe über den ganzen Zeitraum. */
    fun total(from: LocalDate? = null, to: LocalDate? = null): Period =
        group(from, to) { "gesamt" }.firstOrNull() ?: Period("gesamt", 0.0, 0.0, 0.0, 0, 0, 0.0, 0, emptyList())

    private fun group(from: LocalDate?, to: LocalDate?, label: (LocalDate) -> String): List<Period> {
        fun inRange(d: LocalDate) = (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to))
        val ms = movements.filter { inRange(it.date) }
        val ss = sales.filter { inRange(it.movement.date) }
        val msBy = ms.groupBy { label(it.date) }
        val ssBy = ss.groupBy { label(it.movement.date) }
        return msBy.keys.sortedDescending().map { l ->
            val pm = msBy[l].orEmpty()
            val ps = ssBy[l].orEmpty()
            Period(
                label = l,
                spent = pm.filter { it.kind == MoveKind.BUY }.sumOf { (it.price ?: 0.0) * it.quantity },
                revenue = ps.sumOf { it.revenue },
                profit = ps.sumOf { it.profit ?: 0.0 },
                bottlesIn = pm.filter { it.kind == MoveKind.BUY }.sumOf { it.quantity },
                bottlesOut = pm.filter { it.kind == MoveKind.SELL }.sumOf { it.quantity },
                ownUseValue = ps.filter { it.ownUse }.sumOf { it.cost ?: 0.0 },
                unknown = ps.count { !it.ownUse && it.profit == null },
                sales = ps,
                movements = pm,
            )
        }
    }

    val totalProfit: Double get() = sales.sumOf { it.profit ?: 0.0 }

    fun wine(key: String): Wine? = byKey[key]
    fun stats(key: String): Stats = stats[key] ?: EMPTY
    fun byEan(ean: String): List<Wine> = wines.filter { it.ean == ean.trim() }
    fun movementsOf(key: String): List<Movement> = movements.filter { it.key == key }.sortedByDescending { it.date }
    val totalStock: Int get() = stats.values.sumOf { it.stock }
    val stockValue: Double get() = stats.values.sumOf { s -> (s.avgBuy ?: 0.0) * s.stock.coerceAtLeast(0) }

    /** Wein anlegen oder Stammdaten ersetzen (nur was übergeben wird – nichts wird automatisch gelöscht). */
    fun upsert(w: Wine, replacingKey: String? = null): Cellar {
        val oldKey = replacingKey ?: w.key
        val ws = wines.filter { it.key != oldKey && it.key != w.key } + w
        // Jahrgang/EAN geändert → Bewegungen umhängen
        val ms = if (oldKey != w.key) movements.map { if (it.key == oldKey) it.copy(key = w.key) else it } else movements
        return Cellar(ws, ms)
    }

    fun add(m: Movement): Cellar = Cellar(wines, movements + m)
    fun remove(m: Movement): Cellar {
        val i = movements.indexOf(m)
        return if (i < 0) this else Cellar(wines, movements.filterIndexed { idx, _ -> idx != i })
    }

    /** Wein samt Bewegungen löschen – nur auf ausdrücklichen Wunsch. */
    fun delete(key: String): Cellar = Cellar(wines.filter { it.key != key }, movements.filter { it.key != key })

    private fun computeSales(): List<Sale> {
        val result = IdentityHashMap<Movement, Sale>()
        // pro Wein chronologisch; am selben Tag zuerst die Einkäufe
        for ((_, ms) in movements.withIndex().groupBy { it.value.key }) {
            val lots = ArrayDeque<Pair<Int, Double?>>()   // (Flaschen, Preis)
            val ordered = ms.sortedWith(compareBy({ it.value.date }, { it.value.kind != MoveKind.BUY }, { it.index }))
            for ((_, m) in ordered) {
                if (m.kind == MoveKind.BUY) { lots.addLast(m.quantity to m.price); continue }
                var need = m.quantity
                var cost = 0.0
                var known = true
                while (need > 0) {
                    val lot = lots.removeFirstOrNull()
                    if (lot == null) { known = false; break }   // mehr verkauft als eingekauft
                    val take = minOf(need, lot.first)
                    if (lot.second == null) known = false else cost += lot.second!! * take
                    if (lot.first > take) lots.addFirst((lot.first - take) to lot.second)
                    need -= take
                }
                val c = if (known) cost else null
                val profit = if (c != null && m.price != null) m.price * m.quantity - c else null
                result[m] = Sale(m, c, profit)
            }
        }
        return movements.mapNotNull { result[it] }
    }

    private fun computeStats(): Map<String, Stats> = movements.groupBy { it.key }.mapValues { (_, ms) ->
        val buys = ms.filter { it.kind == MoveKind.BUY }
        val sells = ms.filter { it.kind == MoveKind.SELL }
        fun avg(list: List<Movement>): Double? {
            val priced = list.filter { it.price != null }
            val n = priced.sumOf { it.quantity }
            return if (n == 0) null else priced.sumOf { it.price!! * it.quantity } / n
        }
        Stats(
            stock = buys.sumOf { it.quantity } - sells.sumOf { it.quantity },
            bought = buys.sumOf { it.quantity },
            sold = sells.sumOf { it.quantity },
            avgBuy = avg(buys),
            avgSell = avg(sells),
            revenue = sells.sumOf { (it.price ?: 0.0) * it.quantity },
            lastBuyPrice = buys.lastOrNull { it.price != null }?.price,
            lastSellPrice = sells.lastOrNull { it.price != null }?.price,
            profit = sells.mapNotNull { saleOf[it]?.profit }.takeIf { it.isNotEmpty() }?.sum(),
            unknownCost = sells.count { it.price != null && saleOf[it]?.profit == null },
        )
    }

    // ------------------------------------------------------------ Suche

    /** Reihenfolge egal, kleine Tippfehler erlaubt (wie in der Münz-App). */
    fun search(query: String): List<Wine> {
        val q = tokenize(query)
        if (q.isEmpty()) return wines
        return wines.mapNotNull { w ->
            val tokens = tokenize(listOf(w.name, w.producer, w.country, w.region, w.grape, w.color, w.vintageLabel, w.ean, w.note).joinToString(" "))
            var score = 0
            for (t in q) {
                val best = tokens.mapNotNull { score(t, it) }.minOrNull() ?: return@mapNotNull null
                score += best
            }
            w to score
        }.sortedBy { it.second }.map { it.first }
    }

    companion object {
        private val EMPTY = Stats(0, 0, 0, null, null, 0.0, null, null)

        // ------------------------------------------------------------ Excel

        const val SHEET_WINES = "Weine"
        const val SHEET_MOVES = "Bewegungen"
        private val WINE_COLS = listOf("Schlüssel", "EAN", "Jahrgang", "Name", "Weingut", "Land", "Region", "Rebsorte", "Farbe", "Notiz", "Quelle",
            "Bestand", "Ø Einkauf", "Verkauft", "Umsatz Verkauf", "Gewinn", "Bild")
        private val MOVE_COLS = listOf("Datum", "Schlüssel", "EAN", "Jahrgang", "Art", "Menge", "Preis/Flasche", "Summe", "Name", "Notiz", "Gewinn")
        const val SHEET_REPORT = "Auswertung"
        private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
        private val DE_DATE = DateTimeFormatter.ofPattern("d.M.yyyy")

        fun read(bytes: ByteArray): Cellar {
            val pkg = XlsxPackage.read(bytes)
            val paths = pkg.sheetPaths()
            val sst = pkg.sharedStrings()
            val winePath = paths[SHEET_WINES] ?: throw XlsxException("Blatt \"$SHEET_WINES\" fehlt")
            val movePath = paths[SHEET_MOVES] ?: throw XlsxException("Blatt \"$SHEET_MOVES\" fehlt")

            val wineRows = rowsAsText(pkg.text(winePath), sst)
            val header = wineRows[1].orEmpty()
            val col = header.entries.associate { (letter, title) -> title.trim() to letter }
            fun get(r: Map<String, String>, title: String) = col[title]?.let { r[it] }?.trim().orEmpty()
            val known = WINE_COLS.toSet()
            val extraCols = header.filter { it.value.isNotBlank() && it.value.trim() !in known }
            val wines = wineRows.filterKeys { it > 1 }.toSortedMap().values.mapNotNull { r ->
                val ean = get(r, "EAN")
                val name = get(r, "Name")
                if (ean.isEmpty() && name.isEmpty()) return@mapNotNull null
                Wine(
                    ean = ean, vintage = get(r, "Jahrgang").toDoubleOrNull()?.toInt(), name = name,
                    producer = get(r, "Weingut"), country = get(r, "Land"), region = get(r, "Region"),
                    grape = get(r, "Rebsorte"), color = get(r, "Farbe"), note = get(r, "Notiz"), source = get(r, "Quelle"),
                    imageUrl = get(r, "Bild"),
                    extra = extraCols.entries.associate { (letter, title) -> title.trim() to r[letter].orEmpty() },
                )
            }

            val moveRows = rowsAsText(pkg.text(movePath), sst)
            val mh = moveRows[1].orEmpty().entries.associate { (letter, title) -> title.trim() to letter }
            fun mget(r: Map<String, String>, title: String) = mh[title]?.let { r[it] }?.trim().orEmpty()
            val moves = moveRows.filterKeys { it > 1 }.toSortedMap().values.mapNotNull { r ->
                val kind = when (mget(r, "Art").lowercase()) {
                    "einkauf", "kauf", "zugang" -> MoveKind.BUY
                    "verkauf", "abgang", "verbrauch" -> MoveKind.SELL
                    else -> return@mapNotNull null
                }
                val key = mget(r, "Schlüssel").ifEmpty {
                    Wine.keyOf(mget(r, "EAN"), mget(r, "Jahrgang").toDoubleOrNull()?.toInt())
                }
                Movement(
                    date = parseDate(mget(r, "Datum")) ?: return@mapNotNull null,
                    key = key, kind = kind,
                    quantity = mget(r, "Menge").toDoubleOrNull()?.toInt() ?: 1,
                    price = mget(r, "Preis/Flasche").replace(',', '.').toDoubleOrNull(),
                    note = mget(r, "Notiz"),
                )
            }
            return Cellar(wines, moves)
        }

        private fun rowsAsText(xml: String, sst: List<String>): Map<Int, Map<String, String>> {
            val all = SheetParser.parse(xml, ('A'..'Z').map { it.toString() }.toSet())
            return all.mapValues { (_, cells) ->
                cells.mapValues { (_, c) ->
                    when {
                        c.raw == null -> ""
                        c.type == "s" -> c.raw.toIntOrNull()?.let { sst.getOrNull(it) }.orEmpty()
                        else -> c.raw
                    }
                }
            }
        }

        private fun parseDate(s: String): LocalDate? {
            if (s.isBlank()) return null
            s.toDoubleOrNull()?.let { return EXCEL_EPOCH.plusDays(it.toLong()) }
            return runCatching { LocalDate.parse(s) }.getOrNull()
                ?: runCatching { LocalDate.parse(s, DE_DATE) }.getOrNull()
        }

        private fun serial(d: LocalDate): Long = ChronoUnit.DAYS.between(EXCEL_EPOCH, d)

        /** Schreibt die komplette Datei neu (die App ist Herrin der Datei; eigene Zusatzspalten bleiben erhalten). */
        fun write(c: Cellar): ByteArray {
            val entries = LinkedHashMap<String, ByteArray>()
            fun put(name: String, text: String) { entries[name] = text.toByteArray(Charsets.UTF_8) }

            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="$SHEET_WINES" sheetId="1" r:id="rId1"/><sheet name="$SHEET_MOVES" sheetId="2" r:id="rId2"/><sheet name="$SHEET_REPORT" sheetId="3" r:id="rId4"/></sheets><calcPr fullCalcOnLoad="1"/></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/></Relationships>""")
            // 0 Standard, 1 Kopf fett, 2 Datum, 3 Euro, 4 Text (EAN mit führenden Nullen)
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00 &quot;€&quot;"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="14" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Standard" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")

            // --- Weine
            val extraTitles = c.wines.flatMap { it.extra.keys }.distinct()
            val titles = WINE_COLS + extraTitles
            val w = StringBuilder(header(titles))
            val m = "$SHEET_MOVES!"
            c.wines.forEachIndexed { i, x ->
                val r = i + 2
                w.append("<row r=\"$r\">")
                    .append(str("A$r", x.key)).append(str("B$r", x.ean, 4))
                x.vintage?.let { w.append(num("C$r", it.toString())) }
                w.append(str("D$r", x.name)).append(str("E$r", x.producer)).append(str("F$r", x.country))
                    .append(str("G$r", x.region)).append(str("H$r", x.grape)).append(str("I$r", x.color))
                    .append(str("J$r", x.note)).append(str("K$r", x.source))
                    .append(fx("L$r", "SUMIFS(${m}\$F:\$F,${m}\$B:\$B,\$A$r,${m}\$E:\$E,\"Einkauf\")-SUMIFS(${m}\$F:\$F,${m}\$B:\$B,\$A$r,${m}\$E:\$E,\"Verkauf\")"))
                    .append(fx("M$r", "IFERROR(SUMIFS(${m}\$H:\$H,${m}\$B:\$B,\$A$r,${m}\$E:\$E,\"Einkauf\")/SUMIFS(${m}\$F:\$F,${m}\$B:\$B,\$A$r,${m}\$E:\$E,\"Einkauf\"),\"\")", 3))
                    .append(fx("N$r", "SUMIFS(${m}\$F:\$F,${m}\$B:\$B,\$A$r,${m}\$E:\$E,\"Verkauf\")"))
                    .append(fx("O$r", "SUMIFS(${m}\$H:\$H,${m}\$B:\$B,\$A$r,${m}\$E:\$E,\"Verkauf\")", 3))
                c.stats(x.key).profit?.let { w.append(num("P$r", it.toString(), 3)) }
                w.append(str("Q$r", x.imageUrl))
                extraTitles.forEachIndexed { j, t -> x.extra[t]?.takeIf { it.isNotEmpty() }?.let { w.append(str("${colName(WINE_COLS.size + j)}$r", it)) } }
                w.append("</row>")
            }
            put("xl/worksheets/sheet1.xml", sheet(w, "A1:${colName(titles.size - 1)}${c.wines.size + 1}",
                listOf(22, 15, 9, 34, 22, 12, 16, 16, 9, 24, 16, 9, 11, 9, 14, 11, 30) + extraTitles.map { 16 }))

            // --- Bewegungen (älteste zuerst)
            val names = c.wines.associate { it.key to it.name }
            val mv = StringBuilder(header(MOVE_COLS))
            c.movements.forEachIndexed { i, x ->
                val r = i + 2
                val (ean, vin) = x.key.substringBefore('|') to x.key.substringAfter('|')
                mv.append("<row r=\"$r\">")
                    .append(num("A$r", serial(x.date).toString(), 2)).append(str("B$r", x.key)).append(str("C$r", ean, 4))
                if (vin != "NV") mv.append(num("D$r", vin))
                mv.append(str("E$r", x.kind.label)).append(num("F$r", x.quantity.toString()))
                x.price?.let { mv.append(num("G$r", it.toString(), 3)) }
                mv.append(fx("H$r", "IF(G$r=\"\",\"\",F$r*G$r)", 3))
                    .append(str("I$r", names[x.key].orEmpty())).append(str("J$r", x.note))
                c.sale(x)?.profit?.let { mv.append(num("K$r", it.toString(), 3)) }
                mv.append("</row>")
            }
            put("xl/worksheets/sheet2.xml", sheet(mv, "A1:K${c.movements.size + 1}", listOf(11, 22, 15, 9, 9, 7, 13, 12, 34, 24, 11)))

            // --- Auswertung je Monat (Werte, von der App berechnet – FIFO)
            val rep = StringBuilder(header(listOf("Monat", "Einkauf", "Verkauf", "Gewinn/Verlust", "Flaschen rein", "Flaschen raus", "Eigenverbrauch (Warenwert)", "Kasse (Verkauf − Einkauf)")))
            c.periods(byYear = false).forEachIndexed { i, p ->
                val r = i + 2
                rep.append("<row r=\"$r\">").append(str("A$r", p.label))
                    .append(num("B$r", p.spent.toString(), 3)).append(num("C$r", p.revenue.toString(), 3))
                    .append(num("D$r", p.profit.toString(), 3)).append(num("E$r", p.bottlesIn.toString()))
                    .append(num("F$r", p.bottlesOut.toString())).append(num("G$r", p.ownUseValue.toString(), 3))
                    .append(fx("H$r", "C$r-B$r", 3))
                    .append("</row>")
            }
            put("xl/worksheets/sheet3.xml", sheet(rep, "A1:H${c.periods(false).size + 1}", listOf(10, 12, 12, 15, 13, 13, 24, 22)))
            return XlsxPackage.of(entries).toBytes()
        }

        private fun colName(i: Int): String {
            var n = i + 1
            val sb = StringBuilder()
            while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
            return sb.toString()
        }

        private fun sheet(rows: CharSequence, ref: String, widths: List<Int>): String {
            val cols = widths.mapIndexed { i, wd -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$wd\" customWidth=\"1\"/>" }.joinToString("")
            return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="$ref"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="15"/><cols>$cols</cols><sheetData>$rows</sheetData><autoFilter ref="$ref"/></worksheet>"""
        }

        private fun header(titles: List<String>) = buildString {
            append("<row r=\"1\">")
            titles.forEachIndexed { i, t -> append(str("${colName(i)}1", t, 1)) }
            append("</row>")
        }

        private fun str(ref: String, v: String, style: Int = 0): String =
            if (v.isEmpty() && style == 0) ""
            else "<c r=\"$ref\"${if (style > 0) " s=\"$style\"" else ""} t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapeXml(v)}</t></is></c>"

        private fun num(ref: String, v: String, style: Int = 0) = "<c r=\"$ref\"${if (style > 0) " s=\"$style\"" else ""}><v>$v</v></c>"
        private fun fx(ref: String, f: String, style: Int = 0) = "<c r=\"$ref\"${if (style > 0) " s=\"$style\"" else ""}><f>${escapeXml(f)}</f></c>"

        // ------------------------------------------------------------ Fuzzy-Hilfen

        private val MARKS = Regex("\\p{Mn}+")
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        fun norm(s: String): String = Normalizer.normalize(s.lowercase().replace("ß", "ss"), Normalizer.Form.NFD).replace(MARKS, "")
        fun tokenize(s: String): List<String> = norm(s).split(NON_ALNUM).filter { it.isNotEmpty() }

        private fun score(q: String, t: String): Int? {
            if (q == t) return 0
            if (q.all { it.isDigit() }) return if (t.startsWith(q) && q.length >= 3) 1 else null
            if (t.startsWith(q)) return 1
            val maxD = when { q.length >= 7 -> 2; q.length >= 4 -> 1; else -> 0 }
            if (maxD == 0) return null
            val d = osa(q, if (t.length > q.length) t.substring(0, q.length) else t)
            return if (d <= maxD) 2 + d else null
        }

        private fun osa(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
            return d[a.length][b.length]
        }
    }
}
