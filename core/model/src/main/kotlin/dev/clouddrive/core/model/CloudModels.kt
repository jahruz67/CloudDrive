package dev.clouddrive.core.model

import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Instant

const val ROOT_DOCUMENT_ID = "account:primary:root"

enum class CacheState { CLOUD_ONLY, CACHED, OFFLINE }
enum class TransferDirection { UPLOAD, DOWNLOAD }
enum class TransferState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED }
enum class SortField { NAME, MODIFIED, SIZE, TYPE }
enum class SortDirection { ASCENDING, DESCENDING }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class FileViewMode { LIST, GRID }

data class Account(
    val id: String = "primary",
    val serverUrl: String,
    val loginName: String,
    val userId: String,
    val displayName: String = loginName,
    val createdAt: Instant = Instant.now(),
)

data class RemoteNode(
    val documentId: String,
    val accountId: String = "primary",
    val fileId: String,
    val path: String,
    val parentPath: String,
    val name: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Instant,
    val etag: String?,
    val permissions: String,
    val isFavorite: Boolean,
    val hasPreview: Boolean,
    val cacheState: CacheState = CacheState.CLOUD_ONLY,
    val lastRefreshedAt: Instant = Instant.EPOCH,
) {
    val canCreate: Boolean get() = isDirectory && permissions.contains("C")
    val canWrite: Boolean get() = permissions.contains("W") || permissions.contains("V")
    val canDelete: Boolean get() = permissions.contains("D")
    val canRename: Boolean get() = permissions.contains("N") || canWrite
}

data class Transfer(
    val id: String,
    val accountId: String = "primary",
    val direction: TransferDirection,
    val displayName: String,
    val localPath: String?,
    val contentUri: String?,
    val remotePath: String,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSecond: Long = 0,
    val state: TransferState = TransferState.QUEUED,
    val baseEtag: String? = null,
    val uploadId: String? = null,
    val completedChunks: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class StorageQuota(val usedBytes: Long, val totalBytes: Long?)

data class LoginStart(
    val loginUrl: String,
    val pollEndpoint: String,
    val pollToken: String,
)

data class LoginCredential(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
)

sealed interface LoginPollResult {
    data object Pending : LoginPollResult
    data class Complete(val credential: LoginCredential) : LoginPollResult
}

sealed interface CloudResult<out T> {
    data class Success<T>(val value: T) : CloudResult<T>
    data class Failure(val error: CloudError) : CloudResult<Nothing>
}

sealed class CloudError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Authentication(message: String = "Authentication is required") : CloudError(message)
    class Network(message: String, cause: Throwable? = null) : CloudError(message, cause)
    class Conflict(message: String = "The file changed on the server") : CloudError(message)
    class NotFound(message: String = "The file was not found") : CloudError(message)
    class PermissionDenied(message: String = "The server denied this operation") : CloudError(message)
    class QuotaExceeded(message: String = "The server has insufficient storage") : CloudError(message)
    class InvalidServer(message: String) : CloudError(message)
    class Unsupported(message: String) : CloudError(message)
}

interface CloudRepository {
    fun observeAccount(): Flow<Account?>
    fun observeFolder(parentPath: String): Flow<List<RemoteNode>>
    fun observeOfflineFiles(): Flow<List<RemoteNode>>
    fun observeRecent(limit: Int = 100): Flow<List<RemoteNode>>
    fun observeFavorites(): Flow<List<RemoteNode>>
    suspend fun refreshFolder(path: String): CloudResult<List<RemoteNode>>
    suspend fun search(query: String, scope: String = "/"): CloudResult<List<RemoteNode>>
    suspend fun createFolder(parentPath: String, name: String): CloudResult<RemoteNode>
    suspend fun rename(node: RemoteNode, newName: String): CloudResult<RemoteNode>
    suspend fun move(node: RemoteNode, newParentPath: String): CloudResult<RemoteNode>
    suspend fun copy(node: RemoteNode, newParentPath: String): CloudResult<RemoteNode>
    suspend fun delete(node: RemoteNode): CloudResult<Unit>
    suspend fun setFavorite(node: RemoteNode, favorite: Boolean): CloudResult<Unit>
    suspend fun pinOffline(node: RemoteNode): CloudResult<Unit>
    suspend fun unpinOffline(node: RemoteNode): CloudResult<Unit>
    suspend fun cachedFile(node: RemoteNode): CloudResult<File>
    suspend fun quota(): CloudResult<StorageQuota>
    suspend fun logout(): CloudResult<Unit>
}

interface TransferManager {
    fun observeTransfers(): Flow<List<Transfer>>
    suspend fun enqueueUpload(contentUri: String, remotePath: String, displayName: String): String
    suspend fun enqueueDownload(node: RemoteNode, makeOffline: Boolean = false): String
    suspend fun enqueueLocalUpload(file: File, remotePath: String, displayName: String, baseEtag: String?): String
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun cancel(id: String)
    suspend fun retry(id: String)
}

interface CacheManager {
    suspend fun acquire(node: RemoteNode): File
    suspend fun workingCopy(node: RemoteNode): File
    suspend fun pin(node: RemoteNode)
    suspend fun unpin(node: RemoteNode)
    suspend fun clearDisposable(): Long
    suspend fun evictToLimit(limitBytes: Long): Long
    suspend fun disposableBytes(): Long
}
