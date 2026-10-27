package de.robin.wein.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.robin.wein.AppViewModel
import de.robin.wein.ScanHit
import de.robin.wein.core.MoveKind
import de.robin.wein.core.QrCode
import de.robin.wein.openUrl
import de.robin.wein.core.Wine

/** Was der Scan-Tab als Nächstes öffnen möchte. */
sealed interface ScanAction {
    data class NewWine(val hit: ScanHit) : ScanAction
    data class Book(val wine: Wine, val kind: MoveKind) : ScanAction
    /** Ohne Barcode: alles von Hand eintragen. */
    data object Manual : ScanAction
}

/**
 * Am PC gibt es keine Kamera-Erkennung: ein USB-/Bluetooth-Barcodescanner „tippt“ die EAN
 * samt Enter ins Feld – oder man gibt sie von Hand ein. Das Feld hat immer den Fokus.
 */
@Composable
fun ScanTab(vm: AppViewModel, onAction: (ScanAction) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            EanInput { vm.onCode(it) }
            Spacer(Modifier.height(16.dp))
            val hit = vm.scan
            if (hit == null) {
                Text(
                    "Barcode oder QR-Code mit dem Handscanner einlesen (für QR-Codes braucht es einen 2D-Scanner) – " +
                        "oder die Nummer unter dem Strichcode eintippen und Enter drücken.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
                TextButton(onClick = { onAction(ScanAction.Manual) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Kein Barcode? Mit Fotos anlegen")
                }
            } else {
                ScanSheet(
                    hit = hit,
                    stockOf = { vm.cellar.stats(it.key).stock },
                    onBook = { w, k -> onAction(ScanAction.Book(w, k)) },
                    onNew = { onAction(ScanAction.NewWine(hit)) },
                    onWeb = { hit.qr?.let(QrCode::url)?.let(::openUrl) ?: vm.openWebSearch(hit.ean) },
                    onClose = { vm.closeScan() },
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun EanInput(onEan: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() { if (text.trim().length >= 8) { onEan(text.trim()); text = "" } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            // 2D-Handscanner tippen beim QR-Code den ganzen Link
            text, { v -> text = v.take(500) },
            Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { submit(); true } else false
            },
            label = { Text("EAN oder QR-Code scannen bzw. eintippen") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        )
        Spacer(Modifier.width(8.dp))
        Button(enabled = text.trim().length >= 8, onClick = { submit() }) { Text("Suchen") }
    }
}

@Composable
private fun ScanSheet(
    hit: ScanHit,
    stockOf: (Wine) -> Int,
    onBook: (Wine, MoveKind) -> Unit,
    onNew: () -> Unit,
    onWeb: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text(if (hit.qr != null) "QR-Code · ${hit.qr}" else "EAN ${hit.ean}", maxLines = 2, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            when {
                hit.searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (hit.qr != null) "Lese E-Label-Seite …" else "Suche online …")
                }
                hit.online.isNotEmpty() -> hit.online.first().let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (r.imageUrl.isNotBlank()) {
                            WineImage(r.imageUrl, r.color, 64.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(r.name.ifBlank { r.producer.ifBlank { "Flasche unbekannt" } }, style = MaterialTheme.typography.titleMedium)
                            if (r.name.isBlank()) Text(r.raw, style = MaterialTheme.typography.bodySmall)
                            val meta = listOf(r.producer, r.country, r.color, r.vintage?.let { "Jahrgang $it" } ?: "Jahrgang unklar")
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            Text("$meta  (${r.source})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                hit.error != null -> Text(hit.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (hit.local.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Schon in der Liste", style = MaterialTheme.typography.labelLarge)
                hit.local.forEach { w ->
                    val stock = stockOf(w)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        WineThumb(w, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${w.name} ${w.vintageLabel}", style = MaterialTheme.typography.bodyLarge)
                            Text("$stock Fl. auf Lager", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onBook(w, MoveKind.BUY) }) { Text("+ Kauf") }
                        TextButton(onClick = { onBook(w, MoveKind.SELL) }, enabled = stock > 0) { Text("− Verkauf") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNew) { Text(if (hit.local.isEmpty()) "Anlegen" else "Anderer Jahrgang") }
                OutlinedButton(onClick = onWeb) { Text(if (hit.qr?.startsWith("http", true) == true) "Seite öffnen" else "Im Web suchen") }
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Schließen") }
        }
    }
}

@Composable
fun WineDot(color: String, size: Int = 14) {
    Canvas(Modifier.size(size.dp)) { drawCircle(WineColors.of(color)) }
}
