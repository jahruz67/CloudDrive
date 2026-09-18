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
) : CloudRepository {
    override fun observeAccount(): Flow<Account?> = accountDao.observe().map { it?.toModel() }
    override fun observeFolder(parentPath: String) = nodeDao.observeFolder("primary", RemotePath.normalize(parentPath)).map { rows -> rows.map(RemoteNodeEntity::toModel) }
    override fun observeOfflineFiles() = nodeDao.observeByCacheState("primary", CacheState.OFFLINE).map { rows -> rows.map(RemoteNodeEntity::toModel) }
    override fun observeRecent(limit: Int) = nodeDao.observeRecent("primary", limit).map { rows -> rows.map(RemoteNodeEntity::toModel) }
    override fun observeFavorites() = nodeDao.observeFavorites("primary").map { rows -> rows.map(RemoteNodeEntity::toModel) }

    override suspend fun refreshFolder(path: String): CloudResult<List<RemoteNode>> = capture {
        val normalized = RemotePath.normalize(path)
        val remote = gateway.listFolder(normalized)
        val existing = nodeDao.children("primary", normalized).associateBy { it.documentId }
        val merged = remote.map { node -> node.copy(cacheState = existing[node.documentId]?.cacheState ?: CacheState.CLOUD_ONLY) }
        database.withTransaction {
            nodeDao.upsertAll(merged.map(RemoteNode::toEntity))
            nodeDao.deleteMissing("primary", normalized, merged.map(RemoteNode::documentId).ifEmpty { listOf("__none__") })
        }
        merged
    }

    override suspend fun search(query: String, scope: String): CloudResult<List<RemoteNode>> = capture {
        if (query.isBlank()) emptyList() else gateway.search(query, scope).also { nodeDao.upsertAll(it.map(RemoteNode::toEntity)) }
    }

    override suspend fun createFolder(parentPath: String, name: String) = mutate { gateway.createFolder(RemotePath.child(parentPath, name)) }
    override suspend fun rename(node: RemoteNode, newName: String) = mutate { gateway.move(node.path, RemotePath.child(node.parentPath, newName)) }
    override suspend fun move(node: RemoteNode, newParentPath: String) = mutate { gateway.move(node.path, RemotePath.child(newParentPath, node.name)) }
    override suspend fun copy(node: RemoteNode, newParentPath: String) = mutate { gateway.copy(node.path, RemotePath.child(newParentPath, node.name)) }

    override suspend fun delete(node: RemoteNode): CloudResult<Unit> = capture {
        gateway.delete(node.path)
        nodeDao.deleteTree(node.documentId, "${node.path}/%")
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
            pinDao.clear(); cacheDao.clear(); transferDao.clear(); nodeDao.clear(); accountDao.clear()
        }
        credentials.clear()
    }

    private suspend fun mutate(block: suspend () -> RemoteNode): CloudResult<RemoteNode> = capture {
        block().also { nodeDao.upsert(it.toEntity()) }
    }

    private suspend fun <T> capture(block: suspend () -> T): CloudResult<T> = withContext(Dispatchers.IO) {
        try { CloudResult.Success(block()) }
        catch (error: CloudError) { CloudResult.Failure(error) }
        catch (error: Exception) { CloudResult.Failure(CloudError.Network(error.message ?: "Unexpected network error", error)) }
    }
}

