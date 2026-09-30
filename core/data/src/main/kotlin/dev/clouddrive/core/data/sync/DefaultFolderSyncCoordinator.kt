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
    @param:ApplicationContext private val context: Context,
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
                val existingList = nodeDao.children("primary", normalized)
                val existing = existingList.associateBy { it.documentId }
                val now = Instant.now()

                val incomingDocIds = remote.map { it.documentId }.toSet()
                val incomingByPath = remote.associateBy { it.path }

                // Identify missing children and stale database rows occupying paths of replacement files (Defect 3 & 4)
                val missingChildren = existingList.filter { it.documentId !in incomingDocIds }
                val stalePathOccupants = existingList.filter {
                    val incoming = incomingByPath[it.path]
                    incoming != null && incoming.documentId != it.documentId
                }
                val toCleanUp = (missingChildren + stalePathOccupants).distinctBy { it.documentId }

                val merged = remote.map { node ->
                    val old = existing[node.documentId]
                    // Preserve cacheState only when it still belongs to the same remote file
                    val cacheState = if (old != null && old.path == node.path) old.cacheState else CacheState.CLOUD_ONLY
                    node.copy(cacheState = cacheState, lastRefreshedAt = now)
                }
                val updatedSnapshot = FolderSnapshot(
                    path = normalized,
                    state = if (merged.isEmpty()) FolderLoadState.EMPTY else FolderLoadState.CURRENT,
                    loadedAt = now,
                )
                database.withTransaction {
                    for (stale in toCleanUp) {
                        cleanUpNode(stale)
                    }
                    nodeDao.upsertAll(merged.map(RemoteNode::toEntity))
                    snapshotDao.upsert(updatedSnapshot.toEntity())
                }
                if (refreshOfflineFiles) {
                    merged.forEach { fresh ->
                        val old = existing[fresh.documentId]
                        if (old?.cacheState == CacheState.OFFLINE && !EtagUtils.matches(old.etag, fresh.etag)) {
                            runCatching { cacheManager.acquire(fresh.copy(cacheState = CacheState.OFFLINE)) }
                        }
                    }
                }
                notifyFolder(normalized)
                CloudResult.Success(updatedSnapshot)
            } catch (error: CloudError.NotFound) {
                // When a directory disappears during sync, remove its complete metadata subtree and snapshots
                database.withTransaction {
                    cleanUpSubtree("primary", normalized)
                }
                notifyFolder(RemotePath.parent(normalized))
                CloudResult.Failure(error)
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

    private suspend fun cleanUpSubtree(accountId: String, path: String) {
        val exactPath = RemotePath.normalize(path)
        val escapedPrefix = SqlUtils.subtreePrefix(exactPath)
        val subtree = nodeDao.getSubtree(accountId, exactPath, escapedPrefix)
        val docIds = subtree.map { it.documentId }
        if (docIds.isNotEmpty()) {
            val entries = database.cacheDao().findByDocumentIds(docIds)
            entries.forEach { entry -> runCatching { java.io.File(entry.absolutePath).delete() } }
            database.cacheDao().deleteByDocumentIds(docIds)
            database.offlinePinDao().deleteByDocumentIds(docIds)
        }
        snapshotDao.deleteSnapshotsForSubtree(accountId, exactPath, escapedPrefix)
        nodeDao.deleteSubtree(accountId, exactPath, escapedPrefix)
    }

    private suspend fun cleanUpNode(node: RemoteNodeEntity) {
        if (node.isDirectory) {
            cleanUpSubtree(node.accountId, node.path)
        } else {
            val entries = database.cacheDao().findByDocumentIds(listOf(node.documentId))
            entries.forEach { entry -> runCatching { java.io.File(entry.absolutePath).delete() } }
            database.cacheDao().deleteByDocumentIds(listOf(node.documentId))
            database.offlinePinDao().deleteByDocumentIds(listOf(node.documentId))
            nodeDao.delete(node.documentId)
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
