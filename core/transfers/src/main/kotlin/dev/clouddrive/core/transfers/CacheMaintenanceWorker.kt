package dev.clouddrive.core.transfers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.clouddrive.core.data.settings.SettingsRepository
import dev.clouddrive.core.model.CacheManager
import dev.clouddrive.core.data.db.TransferDao
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.model.TransferState
import kotlinx.coroutines.flow.first
import java.io.File

@HiltWorker
class CacheMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val cacheManager: CacheManager,
    private val settingsRepository: SettingsRepository,
    private val transferDao: TransferDao,
    private val gateway: RemoteGateway,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching {
        val settings = settingsRepository.settings.first()
        cacheManager.maintain(settings.cacheLimitBytes, settings.cacheAgeDays)
        val cutoff = System.currentTimeMillis() - settings.cacheAgeDays * 24L * 60 * 60 * 1_000
        transferDao.byStates(listOf(TransferState.CANCELED, TransferState.FAILED)).filter {
            it.state == TransferState.CANCELED || it.updatedAtEpochMillis < cutoff
        }.forEach { transfer ->
            transfer.uploadId?.let { try { gateway.abortChunks(it) } catch (_: Exception) { } }
            transfer.localPath?.let(::File)?.takeIf { it.parentFile?.name == "transfer-staging" }?.delete()
        }
        File(applicationContext.filesDir, "transfer-staging").listFiles()?.filter { it.lastModified() < cutoff }?.forEach(File::delete)
        Result.success()
    }.getOrElse { Result.retry() }
}
