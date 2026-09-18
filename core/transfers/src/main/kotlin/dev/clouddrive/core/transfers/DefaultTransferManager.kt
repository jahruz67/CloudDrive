package dev.clouddrive.core.transfers

import android.content.Context
import android.content.Intent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clouddrive.core.data.db.TransferDao
import dev.clouddrive.core.data.db.toEntity
import dev.clouddrive.core.data.db.toModel
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transferDao: TransferDao,
    private val gateway: RemoteGateway,
) : TransferManager {
    override fun observeTransfers(): Flow<List<Transfer>> = transferDao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun enqueueUpload(contentUri: String, remotePath: String, displayName: String): String {
        val id = UUID.randomUUID().toString()
        transferDao.upsert(
            Transfer(
                id = id, direction = TransferDirection.UPLOAD, displayName = displayName,
                contentUri = contentUri, localPath = null, remotePath = RemotePath.normalize(remotePath),
            ).toEntity(),
        )
        wake()
        return id
    }

    override suspend fun enqueueDownload(node: RemoteNode, makeOffline: Boolean): String {
        val id = UUID.randomUUID().toString()
        transferDao.upsert(
            Transfer(
                id = id, direction = TransferDirection.DOWNLOAD, displayName = node.name,
                contentUri = "node:${node.documentId};offline:${if (makeOffline) 1 else 0}", localPath = null,
                remotePath = node.path, totalBytes = node.size, baseEtag = node.etag,
            ).toEntity(),
        )
        wake()
        return id
    }

    override suspend fun enqueueLocalUpload(file: java.io.File, remotePath: String, displayName: String, baseEtag: String?): String {
        val id = UUID.randomUUID().toString()
        transferDao.upsert(
            Transfer(
                id = id, direction = TransferDirection.UPLOAD, displayName = displayName,
                contentUri = null, localPath = file.absolutePath, remotePath = RemotePath.normalize(remotePath),
                totalBytes = file.length(), baseEtag = baseEtag,
            ).toEntity(),
        )
        wake()
        return id
    }

    override suspend fun pause(id: String) = setState(id, TransferState.PAUSED)
    override suspend fun cancel(id: String) {
        val transfer = transferDao.get(id)
        setState(id, TransferState.CANCELED)
        transfer?.uploadId?.let { runCatching { gateway.abortChunks(it) } }
        transfer?.localPath?.let(::java.io.File)?.takeIf { it.parentFile?.name == "transfer-staging" }?.delete()
    }
    override suspend fun resume(id: String) { setState(id, TransferState.QUEUED); wake() }
    override suspend fun retry(id: String) { setState(id, TransferState.QUEUED); wake() }

    private suspend fun setState(id: String, state: TransferState) {
        transferDao.updateState(id, state, Instant.now().toEpochMilli())
    }

    private fun wake() {
        context.startForegroundService(Intent(context, TransferService::class.java))
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TransferBindings {
    @Binds @Singleton abstract fun transferManager(implementation: DefaultTransferManager): TransferManager
}
