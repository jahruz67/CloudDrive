package dev.clouddrive.core.transfers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.clouddrive.core.data.settings.SettingsRepository
import dev.clouddrive.core.model.CacheManager
import kotlinx.coroutines.flow.first

@HiltWorker
class CacheMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val cacheManager: CacheManager,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching {
        cacheManager.evictToLimit(settingsRepository.settings.first().cacheLimitBytes)
        Result.success()
    }.getOrElse { Result.retry() }
}

