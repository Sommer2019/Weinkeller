package de.robin.wein

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.robin.wein.ui.App
import de.robin.wein.ui.WeinTheme
import de.robin.wein.ui.mainFrame

fun main() = application {
    val vm = remember { AppViewModel() }
    var askClose by remember { mutableStateOf(false) }
    // Ungespeicherte Änderungen gehen nie verloren (Arbeitskopie), aber besser gleich zurück in die Datei
    val tryClose = { if (vm.unsaved > 0) askClose = true else exitApplication() }

    Window(
        onCloseRequest = tryClose,
        title = "Weinkeller",
        icon = painterResource("weinkeller.png"),
        state = rememberWindowState(width = 1100.dp, height = 780.dp),
        onPreviewKeyEvent = {
            if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.S && vm.unsaved > 0 && !vm.busy) {
                vm.save(); true
            } else false
        },
    ) {
        mainFrame = window
        WeinTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                App(vm)
                if (askClose) {
                    AlertDialog(
                        onDismissRequest = { askClose = false },
                        title = { Text("Änderungen speichern?") },
                        text = {
                            Text("${vm.unsaved} Änderung(en) sind noch nicht in ${vm.fileName} gespeichert. " +
                                "Ohne Speichern bleiben sie als Arbeitskopie erhalten und sind beim nächsten Start wieder da.")
                        },
                        confirmButton = {
                            TextButton(onClick = { askClose = false; vm.save { exitApplication() } }) { Text("Speichern & beenden") }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = { exitApplication() }) { Text("Später speichern") }
                                TextButton(onClick = { askClose = false }) { Text("Abbrechen") }
                            }
                        },
                    )
                }
            }
        }
    }
}
