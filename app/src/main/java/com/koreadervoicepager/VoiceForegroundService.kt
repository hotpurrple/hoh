package com.koreadervoicepager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class PageCommand { NEXT, BACK }

/**
 * The Android analog of the Windows version's invisible tray host (MainForm) plus its
 * VoiceCommander: an always-running foreground service that owns the microphone, feeds audio
 * to a grammar-restricted Vosk recognizer, and fires an HTTP page-turn the instant a command
 * is recognized - nothing awaited in between.
 *
 * Latency techniques ported 1:1 from the Windows version:
 *  - Grammar-restricted recognition (just "next"/"back" + synonyms), not free dictation, so
 *    Vosk can commit to a match almost immediately.
 *  - Small audio chunks fed to the recognizer (configurable "Reaction speed", default 20ms,
 *    floor of 10ms) instead of large buffers.
 *  - Acting on the *partial* hypothesis rather than waiting for Vosk's silence-endpointed
 *    "final" result, which normally waits several hundred ms of trailing silence we don't need.
 *  - A pre-warmed, single kept-alive HTTP connection (KOReaderClient) so the page-turn request
 *    has no handshake to wait on.
 *  - The HTTP call fires directly from the recognition callback; notification/UI updates
 *    happen after, not before.
 *
 * Floored-latency pass: the audio read/recognize loop now runs on its own dedicated thread
 * bumped to Android's THREAD_PRIORITY_URGENT_AUDIO (the same class of priority real audio
 * drivers use), instead of a coroutine sharing Dispatchers.Default with the rest of the app -
 * that removes it from general-purpose scheduling contention. The command-firing HTTP call
 * runs on its own dedicated, pre-warmed, max-priority thread for the same reason (see
 * KOReaderClient) instead of Dispatchers.Default, which is tuned for CPU-bound work, not for
 * a thread that needs to wake up and act instantly.
 */
class VoiceForegroundService : Service() {

    companion object {
        const val ACTION_STATUS_UPDATE = "com.koreadervoicepager.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        const val ACTION_STOP = "com.koreadervoicepager.STOP"
        private const val CHANNEL_ID = "voice_pager_status"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000

        // Same fixed vocabulary as the Windows version: "next"/"back" plus a couple of
        // synonyms, and a catch-all "[unk]" bucket for everything else (kept out-of-grammar
        // words from ever being force-matched onto next/back).
        private val GRAMMAR = """["next", "previous", "[unk]"]"""

        // Ignore anything else for a short window after firing a command - stops the tail end
        // of "next" from producing a spurious second match a moment later. Deliberately not a
        // manual recognizer reset (that used to live here in early versions and occasionally
        // ate the *next* command by interrupting Vosk mid-word); letting it finalize naturally
        // is more reliable. 2.5s comfortably swallows any trailing re-match.
        private const val COOLDOWN_MS = 2500L
    }

    private lateinit var config: AppConfig
    private lateinit var client: KOReaderClient
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val running = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private var cooldownUntil = 0L

    // Dedicated, pre-started, max-priority single thread for firing the page-turn command -
    // deliberately not Dispatchers.Default (a shared CPU-bound pool that isn't tuned for
    // "wake up and act in the next few ms" work). Started eagerly so the very first command
    // doesn't pay thread-startup cost.
    private val commandExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VoicePager-command").apply { priority = Thread.MAX_PRIORITY; isDaemon = true }
    }.also { it.submit {} }
    private val commandScope = CoroutineScope(commandExecutor.asCoroutineDispatcher() + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        config = AppConfig(this)
        client = KOReaderClient(config.host, config.port) { msg -> broadcastStatus(msg) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        startListening()
        // If the system kills us under memory pressure, come back and resume listening -
        // matches the "always listening, no user action needed" behavior of the tray app.
        return START_STICKY
    }

    fun applyUpdatedConfig() {
        client.updateEndpoint(config.host, config.port)
        restartRecognizer()
    }

    private fun startListening() {
        if (running.get()) return

        if (!config.isModelReady()) {
            updateStatus("Not listening: no speech model configured - open the app and pick one in Settings.")
            return
        }

        try {
            initRecognizer()
            initAudioRecord()
            acquireWakeLock()

            running.set(true)
            audioRecord?.startRecording()
            recordingThread = Thread({
                // Same priority class the OS gives its own audio pipeline - keeps this loop
                // from getting starved by unrelated background work on the device.
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                recordingLoop()
            }, "VoicePager-audio").apply { start() }
            updateStatus("Listening for \"next\" / \"back\"")
        } catch (e: Exception) {
            updateStatus("Not listening: ${e.message?.substringBefore('\n') ?: "unknown error"}")
            teardownAudio()
        }
    }

    private fun stopListening() {
        running.set(false)
        // Stop the record *before* joining the thread: AudioRecord.read() is a blocking
        // native call that Thread.interrupt() alone won't wake up - calling stop() on the
        // record itself is what unblocks it, so the loop can actually see running==false
        // and exit instead of us waiting out the full join timeout every time.
        try { audioRecord?.stop() } catch (e: Exception) { /* already stopped */ }
        recordingThread?.let { t ->
            t.interrupt()
            try { t.join(500) } catch (e: InterruptedException) { /* shutting down anyway */ }
        }
        recordingThread = null
        teardownAudio()
        releaseWakeLock()
    }

    private fun restartRecognizer() {
        stopListening()
        startListening()
    }

    private fun initRecognizer() {
        recognizer?.close()
        model?.close()

        val m = Model(config.voskModelPath)
        val r = Recognizer(m, SAMPLE_RATE.toFloat(), GRAMMAR)
        r.setWords(true)
        model = m
        recognizer = r
    }

    private fun initAudioRecord() {
        val chunkMs = config.chunkMs.coerceIn(10, 300)
        val chunkSamples = SAMPLE_RATE * chunkMs / 1000

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("No usable microphone on this device.")

        // Buffer sized around the requested chunk (min two chunks) so short chunkMs values
        // actually shrink read latency instead of being masked by a huge OS-side buffer.
        val bufferSizeBytes = maxOf(minBuf, chunkSamples * 2 * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException(
                "Couldn't access the microphone. Check that microphone permission is granted."
            )
        }
        audioRecord = record
    }

    private fun recordingLoop() {
        val chunkMs = config.chunkMs.coerceIn(10, 300)
        val chunkSamples = SAMPLE_RATE * chunkMs / 1000
        val buffer = ShortArray(chunkSamples)

        while (running.get() && !Thread.currentThread().isInterrupted) {
            val record = audioRecord ?: break
            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                break // record torn down mid-read during shutdown - exit cleanly
            }
            if (read <= 0) continue

            val rec = recognizer ?: break
            val isFinal = try {
                rec.acceptWaveForm(buffer, read)
            } catch (e: Exception) {
                continue // recognizer torn down mid-callback during shutdown/reconfig - ignore
            }

            if (!isFinal) {
                // Fast path, and the whole reason this is fast at all: Vosk's "final" result
                // only shows up after several hundred ms of trailing silence, way more than
                // a two-word command needs. The partial hypothesis usually locks onto
                // "next"/"back" almost as soon as the word finishes, so act on it directly.
                handleRecognized(extractField(rec.partialResult, "partial"))
            } else {
                // Finalized result - fallback for a quiet/borderline utterance that never
                // stabilized into a clean partial match above.
                handleRecognized(extractField(rec.result, "text"))
            }
        }
    }

    private fun extractField(json: String?, field: String): String? = try
    {
        if (json.isNullOrBlank()) null else JSONObject(json).optString(field, null)
    } catch (e: Exception) {
        null
    }

    private fun handleRecognized(text: String??) {
        if (text.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) return

        val cmd = when (text.trim().lowercase()) {
            "next", "forward" -> PageCommand.NEXT
            "back", "previous", "go back" -> PageCommand.BACK
            else -> null // includes "[unk]" (out-of-grammar noise) and partial fragments
        } ?: return

        cooldownUntil = now + COOLDOWN_MS

        // Fire the HTTP request immediately, on the dedicated hot command thread - nothing
        // else on the call path before this.
        commandScope.launch {
            val ok = if (cmd == PageCommand.NEXT) client.nextPage() else client.prevPage()
            val label = if (cmd == PageCommand.NEXT) "Next" else "Back"
            updateStatus(if (ok) "Last: $label \u2713" else "Last: $label \u2717 (failed)")
        }
    }

    private fun teardownAudio() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) { /* already stopped */ }
        audioRecord?.release()
        audioRecord = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KOReaderVoicePager:listening").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun updateStatus(text: String) {
        broadcastStatus(text)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun broadcastStatus(text: String) {
        val intent = Intent(ACTION_STATUS_UPDATE).putExtra(EXTRA_STATUS, text).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Voice Pager status", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows whether KOReader Voice Pager is listening" }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KOReader Voice Pager")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopListening()
        client.close()
        commandExecutor.shutdown()
        super.onDestroy()
    }
}
