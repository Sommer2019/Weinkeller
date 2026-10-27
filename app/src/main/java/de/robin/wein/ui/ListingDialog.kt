package de.robin.wein.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.robin.wein.core.Listing
import de.robin.wein.core.Stats
import de.robin.wein.core.Wine
import java.util.Locale

private const val PKG_KLEINANZEIGEN = "com.ebay.kleinanzeigen"
private const val PKG_EBAY = "com.ebay.mobile"
private const val URL_KLEINANZEIGEN = "https://www.kleinanzeigen.de/p-anzeige-aufgeben.html"
private const val URL_EBAY = "https://www.ebay.de/sl/sell"

/**
 * Anzeige vorbereiten: Text wird aus den Stammdaten erzeugt und ist frei änderbar.
 * Kleinanzeigen und eBay bieten keine Schnittstelle zum Vorausfüllen – die App kopiert
 * deshalb Titel + Text in die Zwischenablage, legt das Flaschenbild in die Galerie
 * und öffnet die jeweilige App (oder Webseite). Dort nur noch einfügen.
 */
@Composable
fun ListingDialog(wine: Wine, stats: Stats, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
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

    fun prepare(): Boolean {
        val full = buildString {
            append(title).append("\n\n")
            priceNum?.let { append("Preis: ").append(String.format(Locale.GERMANY, "%.2f €", it)).append(if (qty > 1) " je Flasche\n\n" else "\n\n") }
            append(text)
        }
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Anzeige", full))
        return saveImageToGallery(ctx, wine)
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
                Text("Titel, Preis und Text landen in der Zwischenablage, die Fotos in der Galerie (Ordner „Weinkeller“). " +
                    "In der App dann nur noch einfügen und das Bild auswählen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { val img = prepare(); open(ctx, PKG_KLEINANZEIGEN, URL_KLEINANZEIGEN, img) }) { Text("Kleinanzeigen") }
                    Button(onClick = { val img = prepare(); open(ctx, PKG_EBAY, URL_EBAY, img) }) { Text("eBay") }
                }
                OutlinedButton(onClick = { share(ctx, wine, title, text) }, modifier = Modifier.padding(top = 8.dp)) { Text("Teilen …") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}

private fun open(ctx: Context, pkg: String, url: String, imageSaved: Boolean) {
    Toast.makeText(ctx, if (imageSaved) "Text kopiert, Bild in der Galerie – einfach einfügen" else "Text kopiert – einfach einfügen",
        Toast.LENGTH_LONG).show()
    val app = ctx.packageManager.getLaunchIntentForPackage(pkg)
    val intent = app ?: Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Bilder für die Anzeige: eigene Fotos (sofern auf dem Gerät), sonst das Online-Bild. */
private fun listingImages(ctx: Context, wine: Wine): List<java.io.File> =
    wine.photos.map { java.io.File(ctx.filesDir, "fotos/$it") }.filter { it.exists() }.take(6)
        .ifEmpty { listOfNotNull(ImageCache.cachedFile(ctx, wine.imageUrl)) }

/** Bilder in die Galerie legen, damit sie im Foto-Auswahldialog der Verkaufs-App auftauchen. */
private fun saveImageToGallery(ctx: Context, wine: Wine): Boolean {
    val files = listingImages(ctx, wine)
    if (files.isEmpty()) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false   // ältere Androids bräuchten Speicher-Berechtigung
    return runCatching {
        files.forEachIndexed { i, f ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, ("${wine.name} ${wine.vintageLabel}" + (if (i > 0) " ${i + 1}" else "") + ".jpg").replace('/', '-'))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Weinkeller")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        }
        true
    }.getOrDefault(false)
}

private fun share(ctx: Context, wine: Wine, title: String, text: String) {
    val uris = ArrayList(listingImages(ctx, wine).map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
    val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, "$title\n\n$text")
        when {
            uris.size > 1 -> { type = "image/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            uris.size == 1 -> { type = "image/*"; putExtra(Intent.EXTRA_STREAM, uris[0]) }
            else -> type = "text/plain"
        }
        uris.firstOrNull()?.let { first ->
            clipData = ClipData.newRawUri("Bild", first).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    ctx.startActivity(Intent.createChooser(intent, "Anzeige teilen"))
}
