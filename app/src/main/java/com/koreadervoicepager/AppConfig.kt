package com.koreadervoicepager

import android.content.Context
import java.io.File

/**
 * Persisted user settings, backed by SharedPreferences instead of a JSON file in %AppData% -
 * the direct Android analog. Mirrors the Windows version's AppConfig field-for-field.
 */
class AppConfig(context: Context) {

    private val prefs = context.getSharedPreferences("koreader_voice_pager", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    var host: String
        get() = prefs.getString(KEY_HOST, "192.168.1.50") ?: "192.168.1.50"
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    // Directory the Vosk model was unzipped into (app-private storage). Empty until the user
    // picks a model .zip in Settings - same "not configured yet" state as the Windows version.
    var voskModelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL_PATH, value).apply()

    // How often audio is handed to the recognizer, in milliseconds. Single biggest lever on
    // perceived latency, same as the Windows "Reaction speed" slider. Defaulted as low as it
    // goes (floor is 10ms, enforced in VoiceForegroundService) since this is the "floor the
    // latency" build - raise it in Settings if a device's mic/CPU can't keep up at 20ms.
    var chunkMs: Int
        get() = prefs.getInt(KEY_CHUNK_MS, 20)
        set(value) = prefs.edit().putInt(KEY_CHUNK_MS, value).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    fun isModelReady(): Boolean {
        if (voskModelPath.isBlank()) return false
        val dir = File(voskModelPath)
        // A real Vosk model folder contains at least these; cheap sanity check so we don't
        // hand a half-unzipped or wrong folder to the native recognizer and get a cryptic crash.
        return dir.isDirectory && File(dir, "conf").exists()
    }

    fun modelUnpackDir(): File = File(appContext.filesDir, "vosk-model")

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_CHUNK_MS = "chunk_ms"
        private const val KEY_START_ON_BOOT = "start_on_boot"
    }
}
