package dev.clouddrive.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clouddrive.core.data.db.AccountDao
import dev.clouddrive.core.data.db.RemoteNodeDao
import dev.clouddrive.core.data.settings.SettingsRepository
import dev.clouddrive.core.model.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class MetadataIndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val accountDao: AccountDao,
    private val nodeDao: RemoteNodeDao,
    private val coordinator: FolderSyncCoordinator,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (accountDao.get() == null) return Result.success()
        if (settingsRepository.settings.first().metadataIndexPaused) return Result.success()
        val root = coordinator.snapshot("/")
        if (root.state == FolderLoadState.NEVER_LOADED || root.state == FolderLoadState.FAILED) {
            if (coordinator.refresh("/", force = true) is CloudResult.Failure) return Result.retry()
        }
        while (!isStopped) {
            if (settingsRepository.settings.first().metadataIndexPaused) return Result.success()
            val pending = nodeDao.unindexedDirectories("primary", 25)
            if (pending.isEmpty()) return Result.success()
            for (folder in pending) {
                if (isStopped || settingsRepository.settings.first().metadataIndexPaused) return Result.success()
                when (coordinator.refresh(folder.path, force = true)) {
                    is CloudResult.Success -> Unit
                    is CloudResult.Failure -> return Result.retry()
                }
            }
        }
        return Result.success()
    }
}

@Singleton
class WorkMetadataIndexScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MetadataIndexScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    override fun start(restartCompleted: Boolean) {
        val request = OneTimeWorkRequestBuilder<MetadataIndexWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(
            WORK_NAME,
            if (restartCompleted) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun pause() { workManager.cancelUniqueWork(WORK_NAME) }
    override fun resume() { start(restartCompleted = true) }

    private companion object { const val WORK_NAME = "metadata-index" }
}
