package dev.clouddrive.core.data.sync

import android.content.Context
import android.provider.DocumentsContract
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clouddrive.core.data.db.*
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultFolderSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: CloudDatabase,
    private val nodeDao: RemoteNodeDao,
    private val snapshotDao: FolderSnapshotDao,
    private val gateway: RemoteGateway,
    private val cacheManager: CacheManager,
) : FolderSyncCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val authority get() = context.packageName + ".documents"

    override suspend fun snapshot(path: String): FolderSnapshot {
        val normalized = RemotePath.normalize(path)
        return snapshotDao.get("primary", normalized)?.toModel()
            ?: FolderSnapshot(path = normalized, state = FolderLoadState.NEVER_LOADED)
    }

    override suspend fun cachedChildren(path: String): List<RemoteNode> =
        nodeDao.children("primary", RemotePath.normalize(path)).map(RemoteNodeEntity::toModel)

    override suspend fun refresh(path: String, force: Boolean, refreshOfflineFiles: Boolean): CloudResult<FolderSnapshot> {
        val normalized = RemotePath.normalize(path)
        return locks.getOrPut(normalized, ::Mutex).withLock {
            val before = snapshot(normalized)
            if (!force && before.state in setOf(FolderLoadState.CURRENT, FolderLoadState.EMPTY)) {
                return@withLock CloudResult.Success(before)
            }
            try {
                val remote = gateway.listFolder(normalized)
                val existing = nodeDao.children("primary", normalized).associateBy { it.documentId }
                val now = Instant.now()
                val merged = remote.map { node ->
                    val old = existing[node.documentId]
                    node.copy(cacheState = old?.cacheState ?: CacheState.CLOUD_ONLY, lastRefreshedAt = now)
                }
                val updatedSnapshot = FolderSnapshot(
                    path = normalized,
                    state = if (merged.isEmpty()) FolderLoadState.EMPTY else FolderLoadState.CURRENT,
                    loadedAt = now,
                )
                database.withTransaction {
                    nodeDao.upsertAll(merged.map(RemoteNode::toEntity))
                    nodeDao.deleteMissing("primary", normalized, merged.map(RemoteNode::documentId).ifEmpty { listOf("__none__") })
                    snapshotDao.upsert(updatedSnapshot.toEntity())
                }
                if (refreshOfflineFiles) {
                    merged.forEach { fresh ->
                        val old = existing[fresh.documentId]
                        if (old?.cacheState == CacheState.OFFLINE && old.etag != fresh.etag) {
                            runCatching { cacheManager.acquire(fresh.copy(cacheState = CacheState.OFFLINE)) }
                        }
                    }
                }
                notifyFolder(normalized)
                CloudResult.Success(updatedSnapshot)
            } catch (error: CloudError) {
                val failed = before.copy(state = FolderLoadState.FAILED, errorMessage = error.message)
                snapshotDao.upsert(failed.toEntity())
                notifyFolder(normalized)
                CloudResult.Failure(error)
            } catch (error: Exception) {
                val cloudError = CloudError.Network(error.message ?: "Unable to refresh this folder", error)
                snapshotDao.upsert(before.copy(state = FolderLoadState.FAILED, errorMessage = cloudError.message).toEntity())
                notifyFolder(normalized)
                CloudResult.Failure(cloudError)
            }
        }
    }

    override fun refreshAsync(path: String, force: Boolean) {
        scope.launch { refresh(path, force) }
    }

    override fun notifyFolder(path: String) {
        val normalized = RemotePath.normalize(path)
        scope.launch {
            val documentId = if (normalized == "/") ROOT_DOCUMENT_ID
            else nodeDao.getByPath("primary", normalized)?.documentId ?: return@launch
            context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, documentId), null, false)
            context.contentResolver.notifyChange(DocumentsContract.buildDocumentUri(authority, documentId), null, false)
        }
    }

    override fun notifyRoots() {
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null, false)
        context.contentResolver.notifyChange(DocumentsContract.buildRootUri(authority, "primary"), null, false)
        context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, ROOT_DOCUMENT_ID), null, false)
        context.contentResolver.notifyChange(DocumentsContract.buildDocumentUri(authority, ROOT_DOCUMENT_ID), null, false)
    }
}
