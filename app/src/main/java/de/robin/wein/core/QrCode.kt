package de.robin.wein.core

/**
 * QR-Codes auf Weinflaschen: seit Dez. 2023 meist das E-Label (Zutaten, Nährwerte) als Link.
 * Manche nutzen einen GS1-Digital-Link – darin steckt die GTIN (= EAN), z. B.
 * https://id.gs1.org/01/04006542011112/10/LOT1 oder https://weingut.de/01/4006542011112.
 */
object QrCode {
    private val DIGITAL_LINK = Regex("""/01/(\d{8,14})(?:[/?#]|$)""")
    private val GTIN_ONLY = Regex("""\d{8,14}""")

    /** GTIN aus dem QR-Inhalt, auf EAN-13 gekürzt (führende Nullen der GTIN-14 weg); null, wenn keine drinsteckt. */
    fun gtin(raw: String): String? {
        val s = raw.trim()
        val digits = if (GTIN_ONLY.matches(s)) s else DIGITAL_LINK.find(s)?.groupValues?.get(1) ?: return null
        return if (digits.length == 14 && digits.startsWith("0")) digits.drop(1) else digits
    }

    /** Link aus dem QR-Inhalt (für E-Label-Seiten); null, wenn es kein Web-Link ist. */
    fun url(raw: String): String? = raw.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    /** Vergleich unabhängig von http/https, Groß-/Kleinschreibung der Domain und abschließendem „/“. */
    fun same(a: String, b: String): Boolean = norm(a) == norm(b)

    private val SCHEME = Regex("""^(?i)https?://""")

    private fun norm(s: String) = SCHEME.replace(s.trim(), "").trimEnd('/')
        .let { it.substringBefore('/').lowercase() + it.drop(it.substringBefore('/').length) }

    private val DROP = Regex("""(?is)<(script|style|noscript|svg)[^>]*>.*?</\1>""")
    private val TAG = Regex("""(?s)<[^>]+>""")
    private val SPACE = Regex("""\s+""")
    private val TITLE = Regex("""(?is)<title[^>]*>(.*?)</title>""")
    private val OG_TITLE = Regex("""(?is)<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']*)["']""")

    /** Seitentitel (og:title vor <title>) einer E-Label-Seite. */
    fun pageTitle(html: String): String =
        (OG_TITLE.find(html) ?: TITLE.find(html))?.groupValues?.get(1)?.let(::unescapeXml)?.let { SPACE.replace(it, " ").trim() }.orEmpty()

    /** Sichtbarer Text einer E-Label-Seite, gekürzt – für die KI-Recherche. */
    fun pageText(html: String, max: Int = 4000): String =
        unescapeXml(SPACE.replace(TAG.replace(DROP.replace(html, " "), " "), " ")).replace("&nbsp;", " ").trim().take(max)
}
