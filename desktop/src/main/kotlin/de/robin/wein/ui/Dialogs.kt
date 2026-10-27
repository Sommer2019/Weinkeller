package de.robin.wein.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import de.robin.wein.AutoRead
import de.robin.wein.core.MoveKind
import de.robin.wein.core.Movement
import de.robin.wein.core.Stats
import de.robin.wein.core.Wine
import de.robin.wein.lookup.LookupResult
import de.robin.wein.nas.NasConfig
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val DATE_IN: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")
private val COLORS = listOf("Rot", "Weiß", "Rosé", "Schaumwein")

fun euro(v: Double?): String = v?.let { String.format(Locale.GERMANY, "%.2f €", it) } ?: "–"

// ---------------------------------------------------------------- Wein anlegen / bearbeiten

/**
 * initial = vorhandener Wein (bearbeiten) oder null (neu).
 * Online-Vorschläge überschreiben nie, was schon eingetragen ist – „Übernehmen“ füllt nur leere Felder.
 * Ohne Barcode: Fotos hinzufügen, Daten selbst eintragen oder per „Automatisch einlesen“ aus den Fotos holen.
 * Am PC gibt es keine Texterkennung auf dem Gerät – das Einlesen macht die KI-Recherche.
 */
@Composable
fun WineDialog(
    initial: Wine?,
    ean: String,
    online: List<LookupResult>,
    onImportPhoto: suspend (File) -> String?,
    onAutoRead: suspend (String?, List<String>) -> AutoRead,
    canAi: Boolean,
    onSave: (Wine) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = online.firstOrNull()
    val isNew = initial == null
    var eanText by remember { mutableStateOf(initial?.ean ?: ean) }
    var name by remember { mutableStateOf(initial?.name ?: first?.name.orEmpty()) }
    var producer by remember { mutableStateOf(initial?.producer ?: first?.producer.orEmpty()) }
    var country by remember { mutableStateOf(initial?.country ?: first?.country.orEmpty()) }
    var region by remember { mutableStateOf(initial?.region ?: first?.region.orEmpty()) }
    var grape by remember { mutableStateOf(initial?.grape ?: first?.grape.orEmpty()) }
    var color by remember { mutableStateOf(initial?.color ?: first?.color.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var rating by remember { mutableStateOf(initial?.rating.orEmpty()) }
    var vintage by remember { mutableStateOf(initial?.vintage?.toString() ?: if (isNew) first?.vintage?.toString().orEmpty() else "") }
    var noVintage by remember { mutableStateOf(initial != null && initial.vintage == null) }
    var source by remember { mutableStateOf(initial?.source ?: first?.source ?: "manuell") }
    var imageUrl by remember { mutableStateOf(initial?.imageUrl?.ifBlank { null } ?: first?.imageUrl.orEmpty()) }

    // --- Fotos: hinzufügen, auf Knopfdruck per KI einlesen → nur leere Felder füllen
    val scope = rememberCoroutineScope()
    val photos = remember { mutableStateListOf<String>().apply { addAll(initial?.photos.orEmpty()) } }
    var importing by remember { mutableStateOf(false) }
    var ai by remember { mutableStateOf<LookupResult?>(null) }
    var reading by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    fun applyOnline(r: LookupResult) {
        if (name.isBlank()) name = r.name
        if (producer.isBlank()) producer = r.producer
        if (country.isBlank()) country = r.country
        if (region.isBlank()) region = r.region
        if (grape.isBlank()) grape = r.grape
        if (color.isBlank()) color = r.color
        if (vintage.isBlank() && !noVintage) r.vintage?.let { vintage = it.toString() }
        if (imageUrl.isBlank()) imageUrl = r.imageUrl
    }

    val vintageNum = vintage.toIntOrNull()
    val vintageOk = noVintage || (vintageNum != null && vintageNum in 1900..(LocalDate.now().year + 1))
    val valid = name.isNotBlank() && vintageOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Wein anlegen" else "Wein bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (imageUrl.isNotBlank()) {
                    WineImage(imageUrl, color, 120.dp, Modifier.align(Alignment.CenterHorizontally))
                    TextButton(onClick = { imageUrl = "" }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Bild entfernen") }
                }
                online.forEach { r ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Online (${r.source})", style = MaterialTheme.typography.labelMedium)
                            Text(r.raw.ifBlank { r.name }, style = MaterialTheme.typography.bodyMedium)
                            if (!isNew) TextButton(onClick = {
                                if (name.isBlank()) name = r.name
                                if (producer.isBlank()) producer = r.producer
                                if (country.isBlank()) country = r.country
                                if (color.isBlank()) color = r.color
                                if (vintage.isBlank() && !noVintage) r.vintage?.let { vintage = it.toString() }
                                if (source.isBlank() || source == "manuell") source = r.source
                                if (imageUrl.isBlank()) imageUrl = r.imageUrl
                            }) { Text("Leere Felder übernehmen") }
                        }
                    }
                }
                Text("Fotos", style = MaterialTheme.typography.labelLarge)
                if (photos.isNotEmpty()) PhotoStrip(photos, 88.dp, onRemove = { photos.remove(it) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !importing, onClick = {
                        val files = chooseImages()
                        if (files.isNotEmpty()) {
                            importing = true
                            scope.launch {
                                files.forEach { f -> onImportPhoto(f)?.let { photos += it } }
                                importing = false
                            }
                        }
                    }) { Text("Fotos hinzufügen …") }
                    FilledTonalButton(
                        enabled = !reading && !importing && canAi && (photos.isNotEmpty() || eanText.isNotBlank()),
                        onClick = {
                            reading = true; readError = null
                            scope.launch {
                                val r = onAutoRead(eanText.ifBlank { null }, photos.toList())
                                reading = false
                                readError = r.error
                                r.ai?.let {
                                    ai = it; applyOnline(it)
                                    if (source == "manuell") source = it.source
                                }
                            }
                        },
                    ) { Text("Automatisch einlesen") }
                    if (importing || reading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(
                    if (canAi) "Die KI-Recherche liest die Fotos (und die EAN, falls vorhanden) – füllt nur leere Felder."
                    else "Automatisch einlesen braucht am PC einen Gemini-Schlüssel (Menü → Online-Quellen). Daten sonst einfach selbst eintragen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                readError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                ai?.let { r ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r.source, style = MaterialTheme.typography.labelMedium)
                            Text(r.raw, style = MaterialTheme.typography.bodySmall)
                            if (r.name.isNotBlank() && r.name != name) TextButton(onClick = { name = r.name }) { Text("Name übernehmen: ${r.name}") }
                            if (r.vintage != null && r.vintage.toString() != vintage) TextButton(onClick = { noVintage = false; vintage = r.vintage.toString() }) {
                                Text("Jahrgang übernehmen: ${r.vintage}")
                            }
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        if (noVintage) "" else vintage, { v -> vintage = v.filter { it.isDigit() }.take(4) },
                        Modifier.weight(1f), label = { Text("Jahrgang") }, singleLine = true, enabled = !noVintage,
                        isError = !vintageOk,
                        supportingText = { if (!vintageOk) Text("Nicht erkennbar – bitte eintragen") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Checkbox(noVintage, { noVintage = it })
                    Text("ohne", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(producer, { producer = it }, Modifier.fillMaxWidth(), label = { Text("Weingut / Marke") }, singleLine = true)
                Row {
                    OutlinedTextField(country, { country = it }, Modifier.weight(1f), label = { Text("Land") }, singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(region, { region = it }, Modifier.weight(1f), label = { Text("Region / Appellation") }, singleLine = true)
                }
                OutlinedTextField(grape, { grape = it }, Modifier.fillMaxWidth(), label = { Text("Rebsorte") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    COLORS.forEach { c -> FilterChip(selected = color == c, onClick = { color = if (color == c) "" else c }, label = { Text(c) }) }
                }
                OutlinedTextField(rating, { rating = it }, Modifier.fillMaxWidth(), label = { Text("Bewertung") },
                    placeholder = { Text("z. B. Parker 95, eigene ★★★★") }, singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Notiz") })
                OutlinedTextField(
                    eanText, { v -> eanText = v.filter { it.isDigit() } }, Modifier.fillMaxWidth(),
                    label = { Text("EAN (leer = ohne Barcode)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val base = initial ?: Wine(ean = "", vintage = null, name = "")
                val finalEan = eanText.ifBlank { initial?.ean?.takeIf { it.startsWith("X") } ?: "X" + System.currentTimeMillis().toString(36).uppercase() }
                onSave(base.copy(
                    ean = finalEan, vintage = if (noVintage) null else vintageNum, name = name.trim(),
                    producer = producer.trim(), country = country.trim(), region = region.trim(), grape = grape.trim(),
                    color = color, note = note.trim(), rating = rating.trim(), source = source, imageUrl = imageUrl, photos = photos.toList(),
                ))
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ---------------------------------------------------------------- Ein-/Verkauf buchen

@Composable
fun MovementDialog(
    wine: Wine,
    stats: Stats,
    initialKind: MoveKind,
    onSave: (Movement) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(initialKind) }
    var qty by remember { mutableStateOf(1) }
    fun defaultPrice(k: MoveKind) = (if (k == MoveKind.BUY) stats.lastBuyPrice else stats.lastSellPrice)
        ?.let { String.format(Locale.GERMANY, "%.2f", it) }.orEmpty()
    var price by remember { mutableStateOf(defaultPrice(initialKind)) }
    var date by remember { mutableStateOf(LocalDate.now().format(DATE_FMT)) }
    var note by remember { mutableStateOf("") }

    val parsedDate = runCatching { LocalDate.parse(date.trim(), DATE_IN) }.getOrNull()
    val parsedPrice = price.replace(',', '.').trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val priceOk = price.isBlank() || parsedPrice != null
    val tooMany = kind == MoveKind.SELL && qty > stats.stock

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${wine.name} ${wine.vintageLabel}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoveKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = {
                            if (kind != k) { kind = k; price = defaultPrice(k) }
                        }, label = { Text(k.label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Flaschen", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { if (qty > 1) qty-- }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text("$qty", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                    FilledTonalIconButton(onClick = { qty++ }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
                if (tooMany) {
                    Text("Laut Liste sind nur ${stats.stock} da – wird trotzdem gebucht.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    price, { price = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (kind == MoveKind.BUY) "Einkaufspreis je Flasche (€)" else "Verkaufspreis je Flasche (€)") },
                    singleLine = true, isError = !priceOk,
                    supportingText = { parsedPrice?.let { Text("Summe ${euro(it * qty)}") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    date, { date = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (kind == MoveKind.BUY) "Kaufdatum" else "Verkaufsdatum") },
                    singleLine = true, isError = parsedDate == null,
                    supportingText = { if (parsedDate == null) Text("Format TT.MM.JJJJ") },
                )
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (kind == MoveKind.BUY) "Notiz (z. B. Händler)" else "Notiz (z. B. an wen)") })
            }
        },
        confirmButton = {
            TextButton(enabled = parsedDate != null && priceOk, onClick = {
                onSave(Movement(parsedDate!!, wine.key, kind, qty, parsedPrice, note.trim()))
                onDismiss()
            }) { Text("Buchen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ---------------------------------------------------------------- NAS

@Composable
fun NasDialog(
    initial: NasConfig?,
    onTest: suspend (NasConfig) -> String,
    onConnect: (NasConfig) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    var address by remember { mutableStateOf(initial?.address ?: "smb://") }
    var user by remember { mutableStateOf(initial?.user.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var domain by remember { mutableStateOf(initial?.domain.orEmpty()) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cfg = NasConfig(address, user, password, domain)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NAS-Laufwerk") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Die Weinliste wird bei jedem Start vom NAS geholt und beim Speichern zurückgeschrieben (alte Version als .bak). " +
                        "Gibt es die Datei noch nicht, wird sie beim ersten Speichern angelegt.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(address, { address = it; result = null }, Modifier.fillMaxWidth(), label = { Text("Adresse") },
                    placeholder = { Text("smb://192.168.1.10/daten/Wein/Weine.xlsx") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(user, { user = it; result = null }, Modifier.fillMaxWidth(), label = { Text("Benutzer (leer = Gast)") }, singleLine = true)
                OutlinedTextField(password, { password = it; result = null }, Modifier.fillMaxWidth(), label = { Text("Passwort") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(domain, { domain = it }, Modifier.fillMaxWidth(), label = { Text("Domäne (optional)") }, singleLine = true)
                result?.let {
                    Text(it, color = if (it.startsWith("Fehler")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !testing && cfg.isValid(), onClick = {
                        testing = true
                        scope.launch { result = onTest(cfg); testing = false }
                    }) { Text(if (testing) "Teste …" else "Verbindung testen") }
                }
                if (initial != null) TextButton(onClick = { onDisconnect(); onDismiss() }) {
                    Text("NAS trennen", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(enabled = cfg.isValid(), onClick = { onConnect(cfg); onDismiss() }) { Text("Verbinden & laden") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ---------------------------------------------------------------- Online-Quellen

@Composable
fun SourcesDialog(initial: de.robin.wein.lookup.LookupConfig, onSave: (de.robin.wein.lookup.LookupConfig) -> Unit, onDismiss: () -> Unit) {
    var gemini by remember { mutableStateOf(initial.geminiKey) }
    var token by remember { mutableStateOf(initial.eanSearchToken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Online-Quellen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Gesucht wird der Reihe nach: Open Food Facts, UPCitemdb, dann – falls eingetragen – die KI-Recherche und EAN-Search.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    gemini, { gemini = it.trim() }, Modifier.fillMaxWidth(),
                    label = { Text("Gemini API-Schlüssel (kostenlos)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Text("Unter aistudio.google.com/apikey mit einem Google-Konto erzeugen, ohne Kreditkarte. " +
                            "Die KI sucht per Google nach der EAN und liest auf Wunsch das Etikett-Foto. " +
                            "Im Gratis-Zugang darf Google die Anfragen (EAN, Foto) zur Produktverbesserung nutzen." +
                            if (de.robin.wein.BuildConfig.GEMINI_API_KEY.isNotBlank()) " Leer lassen = eingebauter Schlüssel." else "")
                    },
                )
                OutlinedTextField(
                    token, { token = it.trim() }, Modifier.fillMaxWidth(),
                    label = { Text("EAN-Search Token (optional, kostenpflichtig)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Findet sie die Flasche nicht, schlägt sie über die ersten Ziffern wenigstens den Hersteller vor.") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(de.robin.wein.lookup.LookupConfig(token, gemini)); onDismiss() }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
