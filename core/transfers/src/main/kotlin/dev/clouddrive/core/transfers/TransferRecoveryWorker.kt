package dev.clouddrive.core.transfers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TransferRecoveryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val drainer: TransferQueueDrainer,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching {
        drainer.drain(isRecovery = true)
        Result.success()
    }.getOrElse { Result.retry() }
}
