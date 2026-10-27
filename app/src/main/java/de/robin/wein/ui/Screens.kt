package de.robin.wein.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.robin.wein.AppViewModel
import de.robin.wein.Phase
import de.robin.wein.ScanHit
import de.robin.wein.XLSX_MIME
import de.robin.wein.core.MoveKind
import de.robin.wein.core.Wine
import kotlinx.coroutines.launch

/** Welcher Dialog gerade offen ist. */
private sealed interface Dlg {
    data object None : Dlg
    data object Nas : Dlg
    data object Sources : Dlg
    data class EditWine(val wine: Wine?, val hit: ScanHit?) : Dlg
    data class Book(val key: String, val kind: MoveKind) : Dlg
}

@Composable
fun App(vm: AppViewModel) {
    CompositionLocalProvider(LocalPhotoFile provides vm::photoFile) { AppBody(vm) }
}

@Composable
private fun AppBody(vm: AppViewModel) {
    var dlg by remember { mutableStateOf<Dlg>(Dlg.None) }
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        if (uri != null) vm.createLocal(uri)
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openLocal(uri)
    }
    val actions = Actions(
        newLocal = { createLauncher.launch("Weine.xlsx") },
        open = { openLauncher.launch(arrayOf(XLSX_MIME, "application/octet-stream", "*/*")) },
        nas = { dlg = Dlg.Nas },
        sources = { dlg = Dlg.Sources },
        editWine = { w, hit -> dlg = Dlg.EditWine(w, hit) },
        book = { key, kind -> dlg = Dlg.Book(key, kind) },
    )

    when (val p = vm.phase) {
        Phase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Phase.NoFile -> StartScreen(p.error, actions)
        Phase.Ready -> MainScreen(vm, actions)
    }

    when (val d = dlg) {
        Dlg.None -> Unit
        Dlg.Nas -> NasDialog(
            initial = vm.nasConfig(),
            onTest = { vm.testNas(it) },
            onConnect = { vm.connectNas(it) },
            onDisconnect = { vm.disconnectNas() },
            onDismiss = { dlg = Dlg.None },
        )
        Dlg.Sources -> SourcesDialog(vm.lookupConfig(), onSave = { vm.saveLookupConfig(it) }, onDismiss = { dlg = Dlg.None })
        is Dlg.EditWine -> WineDialog(
            initial = d.wine,
            ean = d.hit?.ean ?: d.wine?.ean.orEmpty(),
            online = d.hit?.online.orEmpty(),
            onImportPhoto = vm::importPhoto,
            onAutoRead = vm::autoRead,
            canAi = vm.hasAi,
            onSave = { w ->
                vm.saveWine(w, replacingKey = d.wine?.key)
                vm.closeScan()
                // Neu angelegt → gleich den Einkauf buchen
                dlg = if (d.wine == null) Dlg.Book(w.key, MoveKind.BUY) else Dlg.None
            },
            onDismiss = { if (dlg == d) dlg = Dlg.None },
            initialLabel = d.hit?.label,
            initialPhoto = d.hit?.photo,
        )
        is Dlg.Book -> vm.cellar.wine(d.key)?.let { w ->
            MovementDialog(
                wine = w, stats = vm.cellar.stats(w.key), initialKind = d.kind,
                onSave = { vm.addMovement(it); vm.closeScan() },
                onDismiss = { dlg = Dlg.None },
            )
        } ?: run { dlg = Dlg.None }
    }

    if (vm.nasConflict) {
        AlertDialog(
            onDismissRequest = { vm.dismissConflict() },
            title = { Text("Datei auf dem NAS geändert") },
            text = { Text("Seit dem Laden wurde die Weinliste auf dem NAS gespeichert (z. B. am PC). Überschreiben ersetzt diese Version – sie bleibt als .bak erhalten.") },
            confirmButton = { TextButton(onClick = { vm.save(force = true) }) { Text("Überschreiben") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.dismissConflict(); vm.reload() }) { Text("NAS-Stand laden") }
                    TextButton(onClick = { vm.dismissConflict() }) { Text("Abbrechen") }
                }
            },
        )
    }
}

private class Actions(
    val newLocal: () -> Unit,
    val open: () -> Unit,
    val nas: () -> Unit,
    val sources: () -> Unit,
    val editWine: (Wine?, ScanHit?) -> Unit,
    val book: (String, MoveKind) -> Unit,
)

@Composable
private fun StartScreen(error: String?, a: Actions) {
    Box(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Rot", "Weiß", "Rosé", "Schaumwein").forEach { WineDot(it, 22) }
            }
            Spacer(Modifier.height(24.dp))
            Text("Weinkeller", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Leg eine neue Weinliste an – auf dem NAS oder als Datei. Beim nächsten Start lädt die App automatisch wieder von dort.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(28.dp))
            Button(onClick = a.nas, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) {
                Text("Auf dem NAS", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = a.newLocal, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) {
                Text("Neue Datei auf dem Handy", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = a.open) { Text("Vorhandene Datei öffnen") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: AppViewModel, a: Actions) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.events.collect { msg -> launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }
    }
    BackHandler(enabled = openKey != null) { openKey = null }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (openKey != null) IconButton(onClick = { openKey = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                title = {
                    Column {
                        Text("Weinkeller", style = MaterialTheme.typography.titleLarge)
                        Text(
                            (if (vm.isNas) (if (vm.offline) "NAS offline · " else "NAS · ") else "") + vm.fileName,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            color = if (vm.offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (vm.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else if (vm.unsaved > 0) FilledTonalButton(onClick = { vm.save() }) { Text("Speichern (${vm.unsaved})") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menü") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Wein ohne Barcode anlegen …") }, onClick = { menu = false; a.editWine(null, null) })
                        HorizontalDivider()
                        if (vm.isNas) DropdownMenuItem(text = { Text("Vom NAS neu laden") }, onClick = { menu = false; vm.reload() })
                        DropdownMenuItem(text = { Text(if (vm.isNas) "NAS-Einstellungen …" else "Auf NAS umstellen …") }, onClick = { menu = false; a.nas() })
                        DropdownMenuItem(text = { Text("Online-Quellen …") }, onClick = { menu = false; a.sources() })
                        DropdownMenuItem(text = { Text("Neue Datei anlegen …") }, onClick = { menu = false; a.newLocal() })
                        DropdownMenuItem(text = { Text("Andere Datei öffnen …") }, onClick = { menu = false; a.open() })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0; openKey = null },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Bestand") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1; openKey = null },
                    icon = { Icon(Icons.Filled.Search, null) }, label = { Text("Scannen") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2; openKey = null },
                    icon = { Icon(Icons.Filled.DateRange, null) }, label = { Text("Auswertung") })
            }
        },
        floatingActionButton = {
            if (tab == 0 && openKey == null) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Wein ganz ohne Barcode von Hand eintragen
                ExtendedFloatingActionButton(
                    onClick = { a.editWine(null, null) },
                    icon = { Icon(Icons.Filled.Edit, null) }, text = { Text("Ohne Barcode") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
                FloatingActionButton(onClick = { tab = 1 }) { Icon(Icons.Filled.Add, "Wein scannen") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(pad)) {
            val key = openKey
            when {
                key != null -> WineDetail(vm, key, a, onClose = { openKey = null })
                tab == 0 -> StockTab(vm, onOpen = { openKey = it })
                tab == 2 -> ReportTab(vm, onOpen = { openKey = it })
                else -> ScanTab(vm) { action ->
                    when (action) {
                        is ScanAction.NewWine -> a.editWine(null, action.hit)
                        is ScanAction.Book -> a.book(action.wine.key, action.kind)
                        ScanAction.Manual -> a.editWine(null, null)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Bestand

@Composable
private fun StockTab(vm: AppViewModel, onOpen: (String) -> Unit) {
    val cellar = vm.cellar
    val list = remember(cellar, vm.query, vm.showAll) {
        cellar.search(vm.query).filter { vm.showAll || cellar.stats(it.key).stock > 0 }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            vm.query, { vm.query = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Filled.Clear, "Leeren") } },
            placeholder = { Text("z. B. Riesling Mosel 2021") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = MaterialTheme.shapes.large,
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !vm.showAll, onClick = { vm.showAll = false }, label = { Text("Auf Lager") })
            FilterChip(selected = vm.showAll, onClick = { vm.showAll = true }, label = { Text("Alle") })
            Spacer(Modifier.weight(1f))
            Text("${cellar.totalStock} Fl. · ${euro(cellar.stockValue)}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (list.isEmpty()) {
            Text(
                if (cellar.wines.isEmpty()) "Noch keine Weine. Unten rechts auf + tippen und den ersten Barcode scannen – oder „Ohne Barcode“: Fotos machen und Daten selbst eintragen oder einlesen lassen."
                else "Nichts gefunden.",
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.key }) { w ->
                val s = cellar.stats(w.key)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(w.key) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WineThumb(w, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(w.producer, w.vintageLabel, w.region.ifBlank { w.country }).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${s.stock}", style = MaterialTheme.typography.titleLarge,
                            color = if (s.stock > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        s.avgBuy?.let { Text("Ø ${euro(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- Details

@Composable
private fun WineDetail(vm: AppViewModel, key: String, a: Actions, onClose: () -> Unit) {
    val w = vm.cellar.wine(key)
    if (w == null) { LaunchedEffect(key) { onClose() }; return }
    val s = vm.cellar.stats(key)
    val moves = vm.cellar.movementsOf(key)
    var confirmDeleteWine by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf<de.robin.wein.core.Movement?>(null) }
    var listing by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            if (w.photos.isNotEmpty()) {
                Box(Modifier.padding(bottom = 12.dp)) { PhotoStrip(w.photos, 180.dp) }
            } else if (w.imageUrl.isNotBlank()) {
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                    WineImage(w.imageUrl, w.color, 180.dp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                WineDot(w.color, 18)
                Spacer(Modifier.width(10.dp))
                Text(w.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { a.editWine(w, null) }) { Icon(Icons.Filled.Edit, "Bearbeiten") }
            }
            Text(listOf(w.producer, w.vintageLabel, w.grape, w.region, w.country).filter { it.isNotBlank() }.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (w.rating.isNotBlank()) Text("Bewertung: ${w.rating}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            if (w.note.isNotBlank()) Text(w.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Text("EAN ${w.ean}" + (if (w.source.isNotBlank()) " · Daten: ${w.source}" else ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${s.stock}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text(" Flaschen auf Lager", modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            StatRow("Eingekauft", "${s.bought} Fl.", "Ø Einkauf", euro(s.avgBuy))
            StatRow("Verkauft", "${s.sold} Fl.", "Ø Verkauf", euro(s.avgSell))
            StatRow("Umsatz", euro(s.revenue), "Gewinn (FIFO)", euro(s.profit))
            if (s.unknownCost > 0) Text("${s.unknownCost} Verkauf/Verkäufe ohne bekannten Einkaufspreis – nicht im Gewinn enthalten.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { a.book(key, MoveKind.BUY) }) { Text("+ Einkauf") }
                OutlinedButton(onClick = { a.book(key, MoveKind.SELL) }) { Text("− Verkauf") }
            }
            TextButton(onClick = { listing = true }) { Text("Auf Kleinanzeigen / eBay inserieren …") }
            Spacer(Modifier.height(20.dp))
            Text("Buchungen", style = MaterialTheme.typography.titleMedium)
            if (moves.isEmpty()) Text("Noch keine.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(moves) { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.date.format(DATE_FMT), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
                Column(Modifier.weight(1f)) {
                    Text("${if (m.kind == MoveKind.BUY) "+" else "−"}${m.quantity} ${m.kind.label}" +
                        (m.price?.let { " à ${euro(it)}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                    if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    vm.cellar.sale(m)?.let { sl -> SaleLine(sl) }
                }
                IconButton(onClick = { confirmMove = m }) { Icon(Icons.Filled.Delete, "Buchung löschen", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { confirmDeleteWine = true }) { Text("Wein komplett löschen", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (listing) ListingDialog(w, s) { listing = false }
    confirmMove?.let { m ->
        ConfirmDialog("Buchung löschen?", "${m.date.format(DATE_FMT)}: ${m.quantity}× ${m.kind.label}. Der Bestand wird entsprechend korrigiert.",
            "Löschen", onConfirm = { vm.removeMovement(m) }, onDismiss = { confirmMove = null })
    }
    if (confirmDeleteWine) {
        ConfirmDialog("Wein löschen?", "${w.name} ${w.vintageLabel} und alle ${moves.size} Buchungen werden entfernt.",
            "Löschen", onConfirm = { vm.deleteWine(key); onClose() }, onDismiss = { confirmDeleteWine = false })
    }
}

@Composable
private fun StatRow(l1: String, v1: String, l2: String, v2: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(l1, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v1, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Text(l2, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v2, Modifier.weight(1f), fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}
