package dev.clouddrive.core.transfers

import android.content.Context
import android.content.Intent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clouddrive.core.data.db.TransferDao
import dev.clouddrive.core.data.db.CloudDatabase
import dev.clouddrive.core.data.db.CacheEntryEntity
import androidx.room.withTransaction
import dev.clouddrive.core.data.db.toEntity
import dev.clouddrive.core.data.db.toModel
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTransferManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val transferDao: TransferDao,
    private val gateway: RemoteGateway,
    private val database: CloudDatabase,
) : TransferManager {
    override fun observeTransfers(): Flow<List<Transfer>> = transferDao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun enqueueUpload(contentUri: String, remotePath: String, displayName: String): String {
        val id = UUID.randomUUID().toString()
        val resolvedName = resolveDisplayName(contentUri, displayName)
        val finalRemotePath = if (remotePath.isBlank() || remotePath.endsWith("/")) {
            RemotePath.child(remotePath, resolvedName)
        } else {
            val parent = RemotePath.parent(remotePath)
            RemotePath.child(parent, resolvedName)
        }
        transferDao.upsert(
            Transfer(
                id = id, direction = TransferDirection.UPLOAD, displayName = resolvedName,
                contentUri = contentUri, localPath = null, remotePath = RemotePath.normalize(finalRemotePath),
            ).toEntity(),
        )
        wake()
        return id
    }

    private fun resolveDisplayName(uriString: String, givenName: String): String {
        val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull() ?: return givenName.ifBlank { "upload" }
        val cursor = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        }.getOrNull()
        val resolved = cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx) else null
            } else null
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?')
        val candidate = (resolved ?: givenName.ifBlank { null } ?: fallback)?.trim()?.ifBlank { null } ?: "upload"
        return candidate.replace('/', '_').replace('\\', '_').ifBlank { "upload" }
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

    override suspend fun enqueueLocalUpload(file: File, remotePath: String, displayName: String, baseEtag: String?): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        // Upload immutable bytes: an editor may reopen or truncate its descriptor immediately.
        val staged = File(File(context.filesDir, "transfer-staging").apply { mkdirs() }, id)
        file.copyTo(staged)
        val node = database.remoteNodeDao().getByPath("primary", RemotePath.normalize(remotePath))
        database.withTransaction {
            transferDao.upsert(Transfer(
                id = id, direction = TransferDirection.UPLOAD, displayName = displayName,
                contentUri = node?.let { "edit:${it.documentId}" }, localPath = staged.absolutePath, remotePath = RemotePath.normalize(remotePath),
                totalBytes = staged.length(), baseEtag = baseEtag,
            ).toEntity())
            if (node != null) database.cacheDao().upsert(CacheEntryEntity(
                "working:${node.documentId}", node.documentId, staged.absolutePath, baseEtag,
                staged.length(), System.currentTimeMillis(), "working", false,
            ))
        }
        wake()
        id
    }

    override suspend fun pause(id: String) = setState(id, TransferState.PAUSED)
    override suspend fun cancel(id: String) {
        val transfer = transferDao.get(id)
        setState(id, TransferState.CANCELED)
        transfer?.uploadId?.let { try { gateway.abortChunks(it) } catch (_: Exception) { } }
        transfer?.localPath?.let { path ->
            transfer.contentUri?.takeIf { it.startsWith("edit:") }?.removePrefix("edit:")?.let {
                database.cacheDao().deleteWorkingFile(it, path)
            }
            // A running request may still be reading this file; maintenance removes canceled data.
        }
        wake()
    }
    override suspend fun resume(id: String) { setState(id, TransferState.QUEUED); wake() }
    override suspend fun retry(id: String) { setState(id, TransferState.QUEUED); wake() }

    private suspend fun setState(id: String, state: TransferState) {
        transferDao.updateState(id, state, Instant.now().toEpochMilli())
    }

    private fun wake() {
        TransferRecoveryWorker.schedule(context)
        try {
            context.startForegroundService(Intent(context, TransferService::class.java))
        } catch (_: IllegalStateException) {
            // Android can prohibit service starts when an external editor saves in the background.
            // The persisted WorkManager request will drain the same queue when allowed.
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TransferBindings {
    @Binds @Singleton abstract fun transferManager(implementation: DefaultTransferManager): TransferManager
}
