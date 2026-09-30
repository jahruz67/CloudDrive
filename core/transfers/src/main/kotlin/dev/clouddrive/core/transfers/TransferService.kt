package dev.clouddrive.core.transfers

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class TransferService : Service() {
    @Inject lateinit var drainer: TransferQueueDrainer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runner: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(TransferQueueDrainer.NOTIFICATION_ID, drainer.notification("Preparing transfers…", 0, 0))
        if (runner?.isActive != true) runner = serviceScope.launch {
            try {
                drainer.drain()
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
