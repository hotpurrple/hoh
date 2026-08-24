package com.koreadervoicepager

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Improvement over the Windows version: instead of asking the user to manually unzip the Vosk
 * model somewhere on disk and browse to the folder (awkward on Android's scoped storage),
 * the user just picks the model .zip via the system file picker and this unpacks it straight
 * into app-private storage. Runs on a background thread - call from Dispatchers.IO.
 */
object ModelInstaller {

    /**
     * Unzips [zipUri] into [destDir], replacing any previous contents. Some Vosk model zips
     * wrap everything in a single top-level folder (e.g. "vosk-model-small-en-us-0.15/") -
     * detected and stripped so [destDir] ends up directly containing "am", "conf", "graph", etc.
     */
    fun install(context: Context, zipUri: Uri, destDir: File): Result<Unit> {
        return try {
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            val tempDir = File(context.cacheDir, "vosk-unzip-tmp").apply {
                deleteRecursively()
                mkdirs()
            }

            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val outFile = File(tempDir, entry.name)
                        // Zip-slip guard: refuse entries that would escape tempDir.
                        if (!outFile.canonicalPath.startsWith(tempDir.canonicalPath + File.separator)) {
                            entry = zip.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return Result.failure(IllegalStateException("Couldn't open the selected file."))

            // If everything landed inside one top-level directory, use that as the real root
            // so destDir directly contains "am"/"conf"/"graph" the way Vosk expects.
            val topLevel = tempDir.listFiles() ?: emptyArray()
            val sourceRoot = if (topLevel.size == 1 && topLevel[0].isDirectory) topLevel[0] else tempDir

            if (!File(sourceRoot, "conf").exists()) {
                tempDir.deleteRecursively()
                return Result.failure(IllegalStateException(
                    "That doesn't look like a Vosk model zip (no \"conf\" folder found inside). " +
                    "Download one from https://alphacephei.com/vosk/models, e.g. " +
                    "vosk-model-small-en-us-0.15.zip, and pick that file directly."
                ))
            }

            sourceRoot.copyRecursively(destDir, overwrite = true)
            tempDir.deleteRecursively()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
