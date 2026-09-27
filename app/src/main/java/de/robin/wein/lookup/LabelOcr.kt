package de.robin.wein.lookup

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import de.robin.wein.core.LabelLine
import de.robin.wein.core.LabelParser
import de.robin.wein.core.LabelResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDate
import kotlin.coroutines.resume

/** Etikett-Foto → Text (ML Kit, offline) → Vorschläge für Name, Erzeuger, Jahrgang … */
object LabelOcr {
    suspend fun read(ctx: Context, uri: Uri): LabelResult? {
        val image = runCatching { InputImage.fromFilePath(ctx, uri) }.getOrNull() ?: return null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val lines = suspendCancellableCoroutine<List<LabelLine>> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        cont.resume(text.textBlocks.flatMap { b ->
                            b.lines.map { l -> LabelLine(l.text, l.boundingBox?.height() ?: 0) }
                        })
                    }
                    .addOnFailureListener { cont.resume(emptyList()) }
            }
            if (lines.isEmpty()) null else LabelParser.parse(lines, LocalDate.now().year + 1)
        } finally {
            recognizer.close()
        }
    }
}
