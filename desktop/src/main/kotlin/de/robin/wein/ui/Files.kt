package de.robin.wein.ui

import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/** Fenster, an das sich die Windows-Dateidialoge hängen (wird in Main.kt gesetzt). */
var mainFrame: Frame? = null

private fun dialog(title: String, mode: Int, file: String? = null, vararg ext: String): File? {
    val d = FileDialog(mainFrame, title, mode)
    // Windows nutzt das Muster im Dateinamen-Feld als Filter, andere Systeme den FilenameFilter
    if (file != null) d.file = file else if (ext.isNotEmpty()) d.file = ext.joinToString(";") { "*.$it" }
    if (ext.isNotEmpty()) d.setFilenameFilter { _, name -> ext.any { name.endsWith(".$it", ignoreCase = true) } }
    d.isVisible = true
    val name = d.file ?: return null
    return File(d.directory, name)
}

fun chooseExcelToOpen(): File? = dialog("Weinliste öffnen", FileDialog.LOAD, null, "xlsx", "xlsm")

fun chooseExcelToCreate(): File? = dialog("Neue Weinliste anlegen", FileDialog.SAVE, "Weine.xlsx")
    ?.let { if (it.extension.equals("xlsx", true) || it.extension.equals("xlsm", true)) it else File(it.path + ".xlsx") }

/** Mehrere Fotos auf einmal wählen (Strg/Umschalt im Windows-Dialog). */
fun chooseImages(): List<File> {
    val ext = listOf("jpg", "jpeg", "png", "bmp", "gif")
    val d = FileDialog(mainFrame, "Fotos hinzufügen", FileDialog.LOAD)
    d.isMultipleMode = true
    d.file = ext.joinToString(";") { "*.$it" }
    d.setFilenameFilter { _, name -> ext.any { name.endsWith(".$it", ignoreCase = true) } }
    d.isVisible = true
    return d.files.orEmpty().toList()
}

fun copyToClipboard(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}
