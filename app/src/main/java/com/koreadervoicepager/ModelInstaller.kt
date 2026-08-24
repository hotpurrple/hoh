package com.koreadervoicepager

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ModelInstaller {

    /**
     * Unzips a user-picked [zipUri] into [destDir], replacing any previous contents.
     * Strips a single top-level wrapper folder if present so destDir ends up directly
     * containing "am", "conf", "graph", etc. as Vosk expects.
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

            val topLevel = tempDir.listFiles() ?: emptyArray()
            val sourceRoot = if (topLevel.size == 1 && topLevel[0].isDirectory) topLevel[0] else tempDir

            if (!File(sourceRoot, "conf").exists()) {
                tempDir.deleteRecursively()
                return Result.failure(IllegalStateException(
                    "That doesn't look like a Vosk model zip (no \"conf\" folder found inside). " +
                    "Download one from https://alphacephei.com/vosk/models"
                ))
            }

            sourceRoot.copyRecursively(destDir, overwrite = true)
            tempDir.deleteRecursively()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extracts the bundled model from assets/vosk-model/ into [destDir] if it isn't already
     * there. This runs once on first launch; subsequent launches skip it (destDir exists).
     * The assets folder is pre-populated by the GitHub Actions build step.
     */
    fun extractBundled(context: Context, destDir: File): Result<Unit> {
        if (File(destDir, "conf").exists()) return Result.success(Unit) // already done

        return try {
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            val assetManager = context.assets
            copyAssetDir(assetManager, "vosk-model", destDir)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun copyAssetDir(
        assets: android.content.res.AssetManager,
        assetPath: String,
        destDir: File
    ) {
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // It's a file
            assets.open(assetPath).use { input ->
                FileOutputStream(destDir).use { out -> input.copyTo(out) }
            }
        } else {
            // It's a directory
            destDir.mkdirs()
            for (child in children) {
                copyAssetDir(assets, "$assetPath/$child", File(destDir, child))
            }
        }
    }
}
