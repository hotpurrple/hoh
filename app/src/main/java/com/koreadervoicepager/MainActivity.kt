package com.koreadervoicepager

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.koreadervoicepager.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(VoiceForegroundService.EXTRA_STATUS) ?: return
            binding.statusText.text = status
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startService() else
            Toast.makeText(this, "Microphone permission is required to listen for commands.", Toast.LENGTH_LONG).show()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort; app still works without it, just less visibly */ }

    private val pickModelZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) installUserModel(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = AppConfig(this)
        loadConfigIntoUi()

        binding.saveButton.setOnClickListener { saveAndApply() }
        binding.pickModelButton.setOnClickListener {
            pickModelZip.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        binding.startStopButton.setOnClickListener { toggleService() }
        binding.testConnectionButton.setOnClickListener { testConnection() }
        binding.batteryOptButton.setOnClickListener { requestIgnoreBatteryOptimizations() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Extract the bundled model on first launch (no-op if already done).
        ensureBundledModel()
    }

    /**
     * Extracts the model baked into assets on first launch. Once done it auto-starts.
     * If the user has already picked their own model (voskModelPath is set and valid),
     * we skip extraction entirely and leave their choice untouched.
     */
    private fun ensureBundledModel() {
        // User has a custom model already — leave it alone.
        if (config.isModelReady()) return

        val bundledDir = config.bundledModelDir()
        if (bundledDir.exists() && bundledDir.resolve("conf").exists()) {
            // Already extracted on a previous launch.
            config.voskModelPath = bundledDir.absolutePath
            updateModelUi()
            return
        }

        // Check if the bundled assets actually exist (they do in the CI build; won't in a
        // local build where the dev didn't run the download step).
        val hasBundled = try { assets.list("vosk-model")?.isNotEmpty() == true } catch (e: Exception) { false }
        if (!hasBundled) {
            binding.modelPathText.text = "No bundled model — pick a model .zip below."
            return
        }

        binding.modelPathText.text = "Preparing speech model…"
        binding.startStopButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ModelInstaller.extractBundled(this@MainActivity, bundledDir)
            }
            binding.startStopButton.isEnabled = true
            result.onSuccess {
                config.voskModelPath = bundledDir.absolutePath
                updateModelUi()
                // Auto-start right after extraction on first run.
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    startService()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }.onFailure { e ->
                binding.modelPathText.text = "Model extraction failed — pick one manually below."
                Toast.makeText(this@MainActivity, e.message ?: "Extraction failed.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateModelUi() {
        if (config.isModelReady()) {
            val isBundled = config.voskModelPath == config.bundledModelDir().absolutePath
            binding.modelPathText.text = if (isBundled) "Built-in model ready ✓" else "Custom model ready ✓"
        } else {
            binding.modelPathText.text = "No model installed yet"
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(VoiceForegroundService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    private fun loadConfigIntoUi() {
        binding.hostInput.setText(config.host)
        binding.portInput.setText(config.port.toString())
        updateModelUi()
        binding.chunkSlider.value = config.chunkMs.toFloat()
        binding.chunkLabel.text = "${config.chunkMs} ms"
        binding.chunkSlider.addOnChangeListener { _, value, _ ->
            binding.chunkLabel.text = "${value.toInt()} ms"
        }
        binding.startOnBootSwitch.isChecked = config.startOnBoot
    }

    private fun saveAndApply() {
        val host = binding.hostInput.text.toString().trim()
        val port = binding.portInput.text.toString().trim().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            Toast.makeText(this, "Enter a valid IP address and port.", Toast.LENGTH_SHORT).show()
            return
        }

        config.host = host
        config.port = port
        config.chunkMs = binding.chunkSlider.value.toInt()
        config.startOnBoot = binding.startOnBootSwitch.isChecked

        Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, VoiceForegroundService::class.java)
        startService(intent)
    }

    private fun installUserModel(uri: Uri) {
        binding.modelPathText.text = "Installing model…"
        binding.pickModelButton.isEnabled = false
        lifecycleScope.launch {
            val dest = config.modelUnpackDir()
            val result = withContext(Dispatchers.IO) { ModelInstaller.install(this@MainActivity, uri, dest) }
            binding.pickModelButton.isEnabled = true
            result.onSuccess {
                config.voskModelPath = dest.absolutePath
                updateModelUi()
                Toast.makeText(this@MainActivity, "Model installed.", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                updateModelUi()
                Toast.makeText(this@MainActivity, e.message ?: "Couldn't install that model.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleService() {
        if (!config.isModelReady()) {
            Toast.makeText(this, "Model not ready yet — wait a moment or pick a model zip.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startService()
    }

    private fun startService() {
        val intent = Intent(this, VoiceForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        binding.startStopButton.text = "Restart listening"
    }

    private fun testConnection() {
        binding.statusText.text = "Testing connection…"
        val intent = Intent(this, VoiceForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Already excluded from battery optimization.", Toast.LENGTH_SHORT).show()
        }
    }
}
