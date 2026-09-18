package dev.clouddrive.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.clouddrive.core.model.*
import java.time.Instant

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val serverUrl: String,
    val loginName: String,
    val userId: String,
    val displayName: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_nodes",
    indices = [
        Index(value = ["accountId", "path"], unique = true),
        Index(value = ["accountId", "parentPath"]),
        Index(value = ["accountId", "isFavorite"]),
        Index(value = ["accountId", "cacheState"]),
    ],
)
data class RemoteNodeEntity(
    @PrimaryKey val documentId: String,
    val accountId: String,
    val fileId: String,
    val path: String,
    val parentPath: String,
    val name: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAtEpochMillis: Long,
    val etag: String?,
    val permissions: String,
    val isFavorite: Boolean,
    val hasPreview: Boolean,
    val cacheState: CacheState,
    val lastRefreshedAtEpochMillis: Long,
)

@Entity(tableName = "transfers", indices = [Index("state"), Index("createdAtEpochMillis")])
data class TransferEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val direction: TransferDirection,
    val displayName: String,
    val localPath: String?,
    val contentUri: String?,
    val remotePath: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
    val state: TransferState,
    val baseEtag: String?,
    val uploadId: String?,
    val completedChunks: Int,
    val errorMessage: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "cache_entries", indices = [Index("lastAccessedEpochMillis"), Index("nodeDocumentId")])
data class CacheEntryEntity(
    @PrimaryKey val id: String,
    val nodeDocumentId: String,
    val absolutePath: String,
    val etag: String?,
    val size: Long,
    val lastAccessedEpochMillis: Long,
    val kind: String,
    val disposable: Boolean,
)

@Entity(tableName = "offline_pins")
data class OfflinePinEntity(
    @PrimaryKey val nodeDocumentId: String,
    val expectedEtag: String?,
    val pinnedAtEpochMillis: Long,
)

@Entity(tableName = "folder_snapshots", primaryKeys = ["accountId", "path"], indices = [Index("state")])
data class FolderSnapshotEntity(
    val accountId: String,
    val path: String,
    val state: FolderLoadState,
    val loadedAtEpochMillis: Long?,
    val errorMessage: String?,
)

fun FolderSnapshotEntity.toModel(now: Instant = Instant.now()): FolderSnapshot {
    val loaded = loadedAtEpochMillis?.let(Instant::ofEpochMilli)
    val effectiveState = if (state == FolderLoadState.CURRENT || state == FolderLoadState.EMPTY) {
        if (loaded == null || loaded.plusSeconds(5 * 60).isBefore(now)) FolderLoadState.STALE else state
    } else state
    return FolderSnapshot(accountId, path, effectiveState, loaded, errorMessage)
}

fun FolderSnapshot.toEntity() = FolderSnapshotEntity(accountId, path, state, loadedAt?.toEpochMilli(), errorMessage)

fun AccountEntity.toModel() = Account(id, serverUrl, loginName, userId, displayName, Instant.ofEpochMilli(createdAtEpochMillis))
fun Account.toEntity() = AccountEntity(id, serverUrl, loginName, userId, displayName, createdAt.toEpochMilli())

fun RemoteNodeEntity.toModel() = RemoteNode(
    documentId, accountId, fileId, path, parentPath, name, mimeType, isDirectory, size,
    Instant.ofEpochMilli(modifiedAtEpochMillis), etag, permissions, isFavorite, hasPreview, cacheState,
    Instant.ofEpochMilli(lastRefreshedAtEpochMillis),
)

fun RemoteNode.toEntity() = RemoteNodeEntity(
    documentId, accountId, fileId, path, parentPath, name, mimeType, isDirectory, size,
    modifiedAt.toEpochMilli(), etag, permissions, isFavorite, hasPreview, cacheState,
    lastRefreshedAt.toEpochMilli(),
)

fun TransferEntity.toModel() = Transfer(
    id, accountId, direction, displayName, localPath, contentUri, remotePath, bytesTransferred,
    totalBytes, speedBytesPerSecond, state, baseEtag, uploadId, completedChunks, errorMessage,
    Instant.ofEpochMilli(createdAtEpochMillis), Instant.ofEpochMilli(updatedAtEpochMillis),
)

fun Transfer.toEntity() = TransferEntity(
    id, accountId, direction, displayName, localPath, contentUri, remotePath, bytesTransferred,
    totalBytes, speedBytesPerSecond, state, baseEtag, uploadId, completedChunks, errorMessage,
    createdAt.toEpochMilli(), updatedAt.toEpochMilli(),
)
