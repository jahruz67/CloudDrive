package dev.clouddrive.core.data.repository

import androidx.room.withTransaction
import dev.clouddrive.core.data.db.*
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.data.security.CredentialStore
import dev.clouddrive.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCloudRepository @Inject constructor(
    private val database: CloudDatabase,
    private val accountDao: AccountDao,
    private val nodeDao: RemoteNodeDao,
    private val transferDao: TransferDao,
    private val cacheDao: CacheDao,
    private val pinDao: OfflinePinDao,
    private val gateway: RemoteGateway,
    private val cacheManager: CacheManager,
    private val credentials: CredentialStore,
    private val folderSync: FolderSyncCoordinator,
    private val snapshots: FolderSnapshotDao,
    private val indexScheduler: MetadataIndexScheduler,
) : CloudRepository {
    override fun observeAccount(): Flow<Account?> = accountDao.observe().map { it?.toModel() }
    override fun observeFolder(parentPath: String) = nodeDao.observeFolder("primary", RemotePath.normalize(parentPath)).map { rows -> rows.map(RemoteNodeEntity::toModel) }
    override fun observeOfflineFiles() = nodeDao.observeByCacheState("primary", CacheState.OFFLINE).map { rows -> rows.map(RemoteNodeEntity::toModel) }
    override fun observeRecent(limit: Int) = nodeDao.observeRecent("primary", limit).map { rows -> rows.map(RemoteNodeEntity::toModel) }
    override fun observeFavorites() = nodeDao.observeFavorites("primary").map { rows -> rows.map(RemoteNodeEntity::toModel) }

    override suspend fun refreshFolder(path: String): CloudResult<List<RemoteNode>> =
        when (val result = folderSync.refresh(path, force = true, refreshOfflineFiles = true)) {
            is CloudResult.Success -> CloudResult.Success(folderSync.cachedChildren(path))
            is CloudResult.Failure -> result
        }

    override suspend fun refreshCloudIndex(): CloudResult<Unit> = capture {
        snapshots.markAllStale("primary")
        indexScheduler.start(restartCompleted = true)
        folderSync.refresh("/", force = true, refreshOfflineFiles = true).let {
            if (it is CloudResult.Failure) throw it.error
        }
    }

    override suspend fun search(query: String, scope: String): CloudResult<List<RemoteNode>> {
        if (query.isBlank()) return CloudResult.Success(emptyList())
        val normalizedScope = RemotePath.normalize(scope)
        return try {
            val remote = gateway.search(query, normalizedScope)
            nodeDao.upsertAll(remote.map(RemoteNode::toEntity))
            CloudResult.Success(remote)
        } catch (error: CloudError.Network) {
            CloudResult.Success(nodeDao.searchCached("primary", query, normalizedScope).map(RemoteNodeEntity::toModel))
        } catch (error: CloudError) {
            CloudResult.Failure(error)
        } catch (error: Exception) {
            CloudResult.Success(nodeDao.searchCached("primary", query, normalizedScope).map(RemoteNodeEntity::toModel))
        }
    }

    override suspend fun createFolder(parentPath: String, name: String) = mutate(parentPath) { gateway.createFolder(RemotePath.child(parentPath, name)) }
    override suspend fun rename(node: RemoteNode, newName: String): CloudResult<RemoteNode> = capture {
        val updated = gateway.move(node.path, RemotePath.child(node.parentPath, newName))
        database.withTransaction { nodeDao.deleteTree(node.documentId, "${node.path}/%"); nodeDao.upsert(updated.toEntity()) }
        folderSync.notifyFolder(node.parentPath)
        updated
    }
    override suspend fun move(node: RemoteNode, newParentPath: String): CloudResult<RemoteNode> = capture {
        val updated = gateway.move(node.path, RemotePath.child(newParentPath, node.name))
        database.withTransaction { nodeDao.deleteTree(node.documentId, "${node.path}/%"); nodeDao.upsert(updated.toEntity()) }
        folderSync.notifyFolder(node.parentPath); folderSync.notifyFolder(newParentPath)
        updated
    }
    override suspend fun copy(node: RemoteNode, newParentPath: String) = mutate(newParentPath) { gateway.copy(node.path, RemotePath.child(newParentPath, node.name)) }

    override suspend fun delete(node: RemoteNode): CloudResult<Unit> = capture {
        gateway.delete(node.path)
        nodeDao.deleteTree(node.documentId, "${node.path}/%")
        folderSync.notifyFolder(node.parentPath)
    }

    override suspend fun setFavorite(node: RemoteNode, favorite: Boolean): CloudResult<Unit> = capture {
        gateway.setFavorite(node.path, favorite)
        nodeDao.updateFavorite(node.documentId, favorite)
    }

    override suspend fun pinOffline(node: RemoteNode): CloudResult<Unit> = capture { cacheManager.pin(node) }
    override suspend fun unpinOffline(node: RemoteNode): CloudResult<Unit> = capture { cacheManager.unpin(node) }
    override suspend fun cachedFile(node: RemoteNode): CloudResult<File> = capture { cacheManager.acquire(node) }
    override suspend fun quota(): CloudResult<StorageQuota> = capture { gateway.quota() }

    override suspend fun logout(): CloudResult<Unit> = capture {
        runCatching { gateway.revokeCredential() }
        cacheManager.clearDisposable()
        database.withTransaction {
            pinDao.clear(); cacheDao.clear(); transferDao.clear(); snapshots.clear(); nodeDao.clear(); accountDao.clear()
        }
        credentials.clear()
        indexScheduler.pause()
        folderSync.notifyRoots()
    }

    private suspend fun mutate(parentPath: String, block: suspend () -> RemoteNode): CloudResult<RemoteNode> = capture {
        block().also { nodeDao.upsert(it.toEntity()); folderSync.notifyFolder(parentPath) }
    }

    private suspend fun <T> capture(block: suspend () -> T): CloudResult<T> = withContext(Dispatchers.IO) {
        try { CloudResult.Success(block()) }
        catch (error: CloudError) { CloudResult.Failure(error) }
        catch (error: Exception) { CloudResult.Failure(CloudError.Network(error.message ?: "Unexpected network error", error)) }
    }
}
