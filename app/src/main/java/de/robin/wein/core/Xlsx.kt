package de.robin.wein.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Minimaler XLSX/XLSM-Zugriff ohne Apache POI.
 * Lesen: Zip entpacken, Sheet-XML per Regex durchgehen.
 * Schreiben: nur einzelne Zellwerte im Sheet-XML ersetzen, alle anderen Teile
 * (Makros, Formatierung, Datenschnitte, bedingte Formatierung) bleiben byte-gleich.
 */
class XlsxPackage private constructor(val entries: LinkedHashMap<String, ByteArray>) {

    fun has(name: String): Boolean = entries.containsKey(name)

    /** Neues Paket mit ersetzten/entfernten Teilen (Reihenfolge bleibt erhalten). */
    fun with(replaced: Map<String, ByteArray>, removed: Set<String> = emptySet()): XlsxPackage {
        val m = LinkedHashMap<String, ByteArray>()
        for ((k, v) in entries) if (k !in removed) m[k] = replaced[k] ?: v
        for ((k, v) in replaced) if (k !in m && k !in removed) m[k] = v
        return XlsxPackage(m)
    }

    fun text(name: String): String =
        entries[name]?.toString(Charsets.UTF_8) ?: throw XlsxException("Teil fehlt: $name")

    fun toBytes(replaced: Map<String, ByteArray> = emptyMap()): ByteArray {
        val bos = ByteArrayOutputStream(1 shl 20)
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(replaced[name] ?: data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Pfad des Arbeitsblatts (z. B. xl/worksheets/sheet1.xml) anhand des Blattnamens. */
    fun sheetPaths(): Map<String, String> {
        val wb = text("xl/workbook.xml")
        val rels = text("xl/_rels/workbook.xml.rels")
        val relTargets = HashMap<String, String>()
        Regex("""<Relationship\b[^>]*>""").findAll(rels).forEach { m ->
            val id = attr(m.value, "Id") ?: return@forEach
            val target = attr(m.value, "Target") ?: return@forEach
            relTargets[id] = when {
                target.startsWith("/") -> target.removePrefix("/")
                else -> "xl/$target"
            }
        }
        val result = LinkedHashMap<String, String>()
        Regex("""<sheet\b[^>]*>""").findAll(wb).forEach { m ->
            val name = attr(m.value, "name")?.let(::unescapeXml) ?: return@forEach
            val rid = attr(m.value, "r:id") ?: return@forEach
            relTargets[rid]?.let { result[name] = it }
        }
        return result
    }

    fun sharedStrings(): List<String> {
        val xml = entries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8) ?: return emptyList()
        val out = ArrayList<String>()
        SI.findAll(xml).forEach { si ->
            val sb = StringBuilder()
            T.findAll(si.groupValues[1]).forEach { sb.append(unescapeXml(it.groupValues[1])) }
            out.add(sb.toString())
        }
        return out
    }

    companion object {
        fun of(entries: LinkedHashMap<String, ByteArray>) = XlsxPackage(entries)

        private val SI = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
        private val T = Regex("""<t(?:\s[^>]*)?>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)

        fun read(bytes: ByteArray): XlsxPackage {
            val map = LinkedHashMap<String, ByteArray>()
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    while (true) {
                        val e = zis.nextEntry ?: break
                        if (!e.isDirectory) map[e.name] = zis.readBytes()
                        zis.closeEntry()
                    }
                }
            } catch (e: Exception) {
                throw XlsxException("Datei ist keine gültige Excel-Datei (${e.message})")
            }
            if (!map.containsKey("xl/workbook.xml")) throw XlsxException("Datei ist keine Excel-Arbeitsmappe")
            return XlsxPackage(map)
        }
    }
}

class XlsxException(msg: String) : Exception(msg)

/** Eine Zelle, so wie sie im XML steht. */
class Cell(
    val type: String?,      // t-Attribut: s, str, inlineStr, b, e oder null (Zahl)
    val raw: String?,       // Inhalt von <v> bzw. Inline-Text
    val formula: String?,   // Formeltext, falls vorhanden
    val sharedIndex: Int?,  // si bei geteilten Formeln
    val style: String? = null, // s-Attribut
)

object SheetParser {

    /**
     * Läuft einmal linear mit indexOf durch das Sheet-XML (kein Regex –
     * Regex über 7 MB ist auf Android extrem langsam).
     * onCell bekommt Start/Ende der Zelle, Spalte, Zeile, Attribut- und Inhaltsbereich.
     */
    private inline fun scanCells(
        xml: String,
        onCell: (start: Int, end: Int, col: String, row: Int, attrStart: Int, attrEnd: Int, bodyStart: Int, bodyEnd: Int) -> Unit,
    ) {
        var i = 0
        while (true) {
            val s = xml.indexOf("<c ", i)
            if (s < 0) return
            val tagEnd = xml.indexOf('>', s)
            if (tagEnd < 0) return
            val selfClosing = xml[tagEnd - 1] == '/'
            val attrStart = s + 3
            val attrEnd = if (selfClosing) tagEnd - 1 else tagEnd
            val bodyStart = tagEnd + 1
            val bodyEnd: Int
            val end: Int
            if (selfClosing) {
                bodyEnd = bodyStart; end = tagEnd + 1
            } else {
                bodyEnd = xml.indexOf("</c>", tagEnd).takeIf { it >= 0 } ?: return
                end = bodyEnd + 4
            }
            val ref = attrIn(xml, attrStart, attrEnd, "r")
            if (ref != null) {
                var k = 0
                while (k < ref.length && ref[k].isLetter()) k++
                val row = ref.substring(k).toIntOrNull()
                if (row != null) onCell(s, end, ref.substring(0, k), row, attrStart, attrEnd, bodyStart, bodyEnd)
            }
            i = end
        }
    }

    /** Liefert Zeile -> (Spalte -> Zelle). Nur die angegebenen Spalten werden behalten. */
    fun parse(xml: String, columns: Set<String>): Map<Int, Map<String, Cell>> {
        val rows = HashMap<Int, HashMap<String, Cell>>()
        scanCells(xml) { _, _, col, row, aS, aE, bS, bE ->
            if (col !in columns) return@scanCells
            val type = attrIn(xml, aS, aE, "t")
            var raw: String? = between(xml, bS, bE, "<v>", "</v>")?.let(::unescapeXml)
            if (type == "inlineStr") {
                raw = between(xml, bS, bE, "<t", "</t>")?.let { it.substring(it.indexOf('>') + 1) }?.let(::unescapeXml)
            }
            var formula: String? = null
            var si: Int? = null
            val f = xml.indexOf("<f", bS).takeIf { it in bS until bE }
            if (f != null) {
                val fTagEnd = xml.indexOf('>', f)
                val selfClosing = xml[fTagEnd - 1] == '/'
                si = attrIn(xml, f + 2, if (selfClosing) fTagEnd - 1 else fTagEnd, "si")?.toIntOrNull()
                if (!selfClosing) {
                    val fEnd = xml.indexOf("</f>", fTagEnd)
                    if (fEnd in 0 until bE) formula = unescapeXml(xml.substring(fTagEnd + 1, fEnd))
                }
            }
            rows.getOrPut(row) { HashMap() }[col] = Cell(type, raw, formula, si, attrIn(xml, aS, aE, "s"))
        }
        return rows
    }

    /**
     * Ersetzt die Werte der angegebenen Zellen (Referenz wie "F123") durch Zahlen.
     * Stil (s=) bleibt erhalten, Typ wird auf Zahl zurückgesetzt.
     * Gibt das neue XML und die Liste nicht gefundener Zellen zurück.
     */
    fun patchNumbers(xml: String, values: Map<String, Long>): Pair<String, Set<String>> {
        val missing = values.keys.toMutableSet()
        val sb = StringBuilder(xml.length + values.size * 8)
        var copied = 0
        scanCells(xml) { start, end, col, row, aS, aE, _, _ ->
            val ref = col + row
            val newVal = values[ref] ?: return@scanCells
            missing.remove(ref)
            val attrs = xml.substring(aS, aE).replace(TYPE_ATTR, "").trimEnd()
            sb.append(xml, copied, start)
            sb.append("<c ").append(attrs).append("><v>").append(newVal).append("</v></c>")
            copied = end
        }
        sb.append(xml, copied, xml.length)
        return sb.toString() to missing
    }

    private val TYPE_ATTR = Regex("""\s*\bt="[^"]*"""")

    private fun between(xml: String, from: Int, to: Int, open: String, close: String): String? {
        val a = xml.indexOf(open, from)
        if (a < 0 || a >= to) return null
        val b = xml.indexOf(close, a + open.length)
        if (b < 0 || b > to) return null
        return xml.substring(a + open.length, b)
    }
}

/** Attribut name="…" im Bereich [from, to) suchen, ohne Regex. */
internal fun attrIn(xml: String, from: Int, to: Int, name: String): String? {
    var i = from
    while (i < to) {
        val p = xml.indexOf(name, i)
        if (p < 0 || p + name.length + 1 >= to) return null
        val before = if (p == from) ' ' else xml[p - 1]
        if (before.isWhitespace() && xml[p + name.length] == '=' && xml[p + name.length + 1] == '"') {
            val vs = p + name.length + 2
            val ve = xml.indexOf('"', vs)
            return if (ve in vs..to) xml.substring(vs, ve) else null
        }
        i = p + name.length
    }
    return null
}

internal fun attr(tag: String, name: String): String? = attrIn(tag, 0, tag.length, name)

internal fun unescapeXml(s: String): String {
    if (s.indexOf('&') < 0) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '&') {
            val end = s.indexOf(';', i)
            if (end > i) {
                val ent = s.substring(i + 1, end)
                val rep: String? = when {
                    ent == "amp" -> "&"
                    ent == "lt" -> "<"
                    ent == "gt" -> ">"
                    ent == "quot" -> "\""
                    ent == "apos" -> "'"
                    ent.startsWith("#x") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                    ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                    else -> null
                }
                if (rep != null) {
                    sb.append(rep); i = end + 1; continue
                }
            }
        }
        sb.append(c); i++
    }
    return sb.toString()
}

internal fun escapeXml(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        else -> append(c)
    }
}
