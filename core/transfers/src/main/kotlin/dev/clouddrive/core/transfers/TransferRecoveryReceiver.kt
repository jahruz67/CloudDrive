package dev.clouddrive.core.transfers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.clouddrive.core.data.db.TransferDao
import dev.clouddrive.core.model.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TransferRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, TransferRecoveryEntryPoint::class.java)
            val transferDao = entryPoint.transferDao()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val pending = transferDao.byStates(listOf(TransferState.QUEUED, TransferState.RUNNING))
                    if (pending.isNotEmpty()) {
                        val request = OneTimeWorkRequestBuilder<TransferRecoveryWorker>().build()
                        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                            WORK_NAME,
                            ExistingWorkPolicy.KEEP,
                            request,
                        )
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val WORK_NAME = "transfer-recovery"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TransferRecoveryEntryPoint {
    fun transferDao(): TransferDao
}
