package com.koreadervoicepager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android's analog of the Windows version's StartupManager (HKCU Run key): if the user checked
 * "Start automatically" in Settings, relaunch the listening service after the device boots.
 * Starting a foreground service from a BOOT_COMPLETED receiver is one of the exemptions to
 * Android's background-start restrictions, so this works without any extra permission dance.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val config = AppConfig(context)
        if (config.startOnBoot && config.isModelReady()) {
            val serviceIntent = Intent(context, VoiceForegroundService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
