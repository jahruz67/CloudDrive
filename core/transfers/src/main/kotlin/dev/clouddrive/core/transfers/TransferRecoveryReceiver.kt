package dev.clouddrive.core.transfers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TransferRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.startForegroundService(Intent(context, TransferService::class.java))
        }
    }
}
