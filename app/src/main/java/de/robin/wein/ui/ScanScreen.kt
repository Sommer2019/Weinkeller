package de.robin.wein.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import de.robin.wein.AppViewModel
import de.robin.wein.ScanHit
import de.robin.wein.core.MoveKind
import de.robin.wein.core.Wine

/** Was der Scan-Tab als Nächstes öffnen möchte. */
sealed interface ScanAction {
    data class NewWine(val hit: ScanHit) : ScanAction
    data class Book(val wine: Wine, val kind: MoveKind) : ScanAction
}

@Composable
fun ScanTab(vm: AppViewModel, onAction: (ScanAction) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    Box(Modifier.fillMaxSize()) {
        if (granted) {
            BarcodeCamera(onCode = { vm.onBarcode(it) }, paused = vm.scan != null)
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Zum Scannen der Barcodes braucht die App die Kamera.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { perm.launch(Manifest.permission.CAMERA) }) { Text("Kamera erlauben") }
            }
        }

        val hit = vm.scan
        if (hit == null) {
            ManualEan(Modifier.align(Alignment.BottomCenter)) { vm.onBarcode(it) }
        } else {
            ScanSheet(
                hit = hit,
                stockOf = { vm.cellar.stats(it.key).stock },
                onBook = { w, k -> onAction(ScanAction.Book(w, k)) },
                onNew = { onAction(ScanAction.NewWine(hit)) },
                onWeb = { ctx.startActivity(vm.webSearchIntent(hit.ean)) },
                onClose = { vm.closeScan() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun BarcodeCamera(onCode: (String) -> Unit, paused: Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentPaused by rememberUpdatedState(paused)
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val controller = remember { LifecycleCameraController(ctx) }
    var torch by remember { mutableStateOf(false) }

    DisposableEffect(owner) {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .build()
        )
        val exec = ContextCompat.getMainExecutor(ctx)
        controller.setImageAnalysisAnalyzer(exec, MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, exec) { result ->
            if (currentPaused) return@MlKitAnalyzer
            result?.getValue(scanner)?.firstOrNull()?.rawValue?.let { currentOnCode(it) }
        })
        controller.bindToLifecycle(owner)
        previewView.controller = controller
        onDispose {
            controller.unbind()
            scanner.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Zielrahmen für das Etikett
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val w = size.width * 0.8f
            val h = w * 0.45f
            val tl = Offset((size.width - w) / 2, size.height * 0.33f - h / 2)
            drawRect(Color.Black.copy(alpha = 0.5f))
            drawRoundRect(Color.Transparent, tl, Size(w, h), CornerRadius(24f, 24f), blendMode = BlendMode.Clear)
            drawRoundRect(Color.White, tl, Size(w, h), CornerRadius(24f, 24f), style = Stroke(2.dp.toPx()))
        }
        Text("Barcode in den Rahmen halten", color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp))
        TextButton(onClick = { torch = !torch; controller.enableTorch(torch) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 8.dp)) {
            Text(if (torch) "Licht aus" else "Licht an", color = Color.White)
        }
    }
}

@Composable
private fun ManualEan(modifier: Modifier, onEan: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), tonalElevation = 2.dp) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { v -> text = v.filter { it.isDigit() }.take(14) }, Modifier.weight(1f),
                label = { Text("EAN eintippen") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (text.length >= 8) { onEan(text); text = "" } }),
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = text.length >= 8, onClick = { onEan(text); text = "" }) { Text("Suchen") }
        }
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
            Text("EAN ${hit.ean}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            when {
                hit.searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Suche online …")
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
                        WineImage(w.imageUrl, w.color, 36.dp)
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
                OutlinedButton(onClick = onWeb) { Text("Im Web suchen") }
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Weiter scannen") }
        }
    }
}

@Composable
fun WineDot(color: String, size: Int = 14) {
    Canvas(Modifier.size(size.dp)) { drawCircle(WineColors.of(color)) }
}
