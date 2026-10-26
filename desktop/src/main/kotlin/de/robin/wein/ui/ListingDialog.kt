package de.robin.wein.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.robin.wein.core.Listing
import de.robin.wein.core.Stats
import de.robin.wein.core.Wine
import de.robin.wein.openUrl
import java.io.File
import java.util.Locale

private const val URL_KLEINANZEIGEN = "https://www.kleinanzeigen.de/p-anzeige-aufgeben.html"
private const val URL_EBAY = "https://www.ebay.de/sl/sell"

/**
 * Anzeige vorbereiten: Text wird aus den Stammdaten erzeugt und ist frei änderbar.
 * Kleinanzeigen und eBay bieten keine Schnittstelle zum Vorausfüllen – das Programm kopiert
 * deshalb Titel + Text in die Zwischenablage, legt das Flaschenbild unter „Bilder\Weinkeller“ ab
 * und öffnet die jeweilige Webseite im Browser. Dort nur noch einfügen.
 */
@Composable
fun ListingDialog(wine: Wine, stats: Stats, onDismiss: () -> Unit) {
    var hint by remember { mutableStateOf<String?>(null) }
    var qty by remember { mutableIntStateOf(stats.stock.coerceAtLeast(1)) }
    var price by remember {
        mutableStateOf((stats.lastSellPrice ?: stats.avgSell)?.let { String.format(Locale.GERMANY, "%.2f", it) }.orEmpty())
    }
    var alcohol by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf("0,75 l") }
    var titleEdited by remember { mutableStateOf<String?>(null) }
    var textEdited by remember { mutableStateOf<String?>(null) }

    val priceNum = price.replace(',', '.').toDoubleOrNull()
    // Solange nicht von Hand geändert, wandern Menge/Preis/Alkohol automatisch in den Text
    val title = titleEdited ?: Listing.title(wine, qty)
    val text = textEdited ?: Listing.description(wine, qty, alcohol, volume, priceNum)

    fun prepare(url: String) {
        val full = buildString {
            append(title).append("\n\n")
            priceNum?.let { append("Preis: ").append(String.format(Locale.GERMANY, "%.2f €", it)).append(if (qty > 1) " je Flasche\n\n" else "\n\n") }
            append(text)
        }
        copyToClipboard(full)
        val img = saveImage(wine)
        hint = if (img != null) "Text kopiert, Bild liegt in ${img.parentFile.path} – im Browser einfügen" else "Text kopiert – im Browser einfügen"
        openUrl(url)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inserieren") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Flaschen", modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { if (qty > 1) qty-- }) { Text("−") }
                    Text("$qty", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
                    FilledTonalIconButton(onClick = { qty++ }) { Text("+") }
                }
                if (qty > stats.stock) Text("Laut Liste sind nur ${stats.stock} da.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Row {
                    OutlinedTextField(price, { price = it }, Modifier.weight(1f), label = { Text("Preis/Flasche €") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(alcohol, { alcohol = it }, Modifier.weight(0.8f), label = { Text("% vol") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(volume, { volume = it }, Modifier.weight(0.8f), label = { Text("Menge") }, singleLine = true)
                }
                stats.avgBuy?.let {
                    Text("Ø Einkauf ${euro(it)} – bei ${price.ifBlank { "?" }} € wären das ${priceNum?.let { p -> signedEuro((p - it) * qty) } ?: "?"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(title, { titleEdited = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Titel (${title.length}/80)") })
                OutlinedTextField(text, { textEdited = it }, Modifier.fillMaxWidth().height(220.dp), label = { Text("Beschreibung") })
                if (titleEdited != null || textEdited != null) {
                    TextButton(onClick = { titleEdited = null; textEdited = null }) { Text("Text neu erzeugen") }
                }
                Text("Titel, Preis und Text landen in der Zwischenablage, das Flaschenbild im Ordner „Bilder\\Weinkeller“. " +
                    "Auf der Webseite dann nur noch einfügen und das Bild hochladen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { prepare(URL_KLEINANZEIGEN) }) { Text("Kleinanzeigen") }
                    Button(onClick = { prepare(URL_EBAY) }) { Text("eBay") }
                }
                OutlinedButton(onClick = { copyToClipboard("$title\n\n$text"); hint = "Text kopiert" }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Nur Text kopieren")
                }
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}

/** Flaschenbild nach „Bilder\Weinkeller“ kopieren, damit es sich im Upload-Dialog der Webseite leicht findet. */
private fun saveImage(wine: Wine): File? {
    val f = ImageCache.cachedFile(wine.imageUrl) ?: return null
    return runCatching {
        val dir = File(System.getProperty("user.home"), "Pictures/Weinkeller").apply { mkdirs() }
        val name = "${wine.name} ${wine.vintageLabel}".replace(Regex("""[\\/:*?"<>|]"""), "-").trim()
        val ext = if (f.readBytes().take(4) == listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) "png" else "jpg"
        f.copyTo(File(dir, "$name.$ext"), overwrite = true)
    }.getOrNull()
}
