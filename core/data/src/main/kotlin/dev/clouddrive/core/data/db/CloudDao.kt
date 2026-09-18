package dev.clouddrive.core.data.db

import androidx.room.*
import dev.clouddrive.core.model.CacheState
import dev.clouddrive.core.model.TransferState
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts LIMIT 1") fun observe(): Flow<AccountEntity?>
    @Query("SELECT * FROM accounts LIMIT 1") suspend fun get(): AccountEntity?
    @Upsert suspend fun upsert(account: AccountEntity)
    @Query("DELETE FROM accounts") suspend fun clear()
}

@Dao
interface RemoteNodeDao {
    @Query("SELECT * FROM remote_nodes WHERE accountId = :accountId AND parentPath = :parentPath ORDER BY isDirectory DESC, name COLLATE NOCASE")
    fun observeFolder(accountId: String, parentPath: String): Flow<List<RemoteNodeEntity>>

    @Query("SELECT * FROM remote_nodes WHERE accountId = :accountId AND cacheState = :state ORDER BY name COLLATE NOCASE")
    fun observeByCacheState(accountId: String, state: CacheState): Flow<List<RemoteNodeEntity>>

    @Query("SELECT * FROM remote_nodes WHERE accountId = :accountId AND isFavorite = 1 ORDER BY name COLLATE NOCASE")
    fun observeFavorites(accountId: String): Flow<List<RemoteNodeEntity>>

    @Query("SELECT * FROM remote_nodes WHERE accountId = :accountId ORDER BY modifiedAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(accountId: String, limit: Int): Flow<List<RemoteNodeEntity>>

    @Query("SELECT * FROM remote_nodes WHERE documentId = :documentId") suspend fun get(documentId: String): RemoteNodeEntity?
    @Query("SELECT * FROM remote_nodes WHERE accountId = :accountId AND path = :path") suspend fun getByPath(accountId: String, path: String): RemoteNodeEntity?
    @Query("SELECT * FROM remote_nodes WHERE accountId = :accountId AND parentPath = :path") suspend fun children(accountId: String, path: String): List<RemoteNodeEntity>
    @Upsert suspend fun upsert(node: RemoteNodeEntity)
    @Upsert suspend fun upsertAll(nodes: List<RemoteNodeEntity>)
    @Query("DELETE FROM remote_nodes WHERE accountId = :accountId AND parentPath = :parentPath AND documentId NOT IN (:keepIds)")
    suspend fun deleteMissing(accountId: String, parentPath: String, keepIds: List<String>)
    @Query("DELETE FROM remote_nodes WHERE documentId = :documentId OR path LIKE :descendantPrefix")
    suspend fun deleteTree(documentId: String, descendantPrefix: String)
    @Query("UPDATE remote_nodes SET cacheState = :state WHERE documentId = :documentId") suspend fun updateCacheState(documentId: String, state: CacheState)
    @Query("UPDATE remote_nodes SET isFavorite = :favorite WHERE documentId = :documentId") suspend fun updateFavorite(documentId: String, favorite: Boolean)
    @Query("DELETE FROM remote_nodes") suspend fun clear()
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAtEpochMillis DESC") fun observeAll(): Flow<List<TransferEntity>>
    @Query("SELECT * FROM transfers WHERE id = :id") suspend fun get(id: String): TransferEntity?
    @Query("SELECT * FROM transfers WHERE state IN (:states) ORDER BY createdAtEpochMillis") suspend fun byStates(states: List<TransferState>): List<TransferEntity>
    @Upsert suspend fun upsert(transfer: TransferEntity)
    @Query("UPDATE transfers SET state = :state, updatedAtEpochMillis = :now, errorMessage = :error WHERE id = :id")
    suspend fun updateState(id: String, state: TransferState, now: Long, error: String? = null)
    @Query("UPDATE transfers SET bytesTransferred = :bytes, totalBytes = :total, speedBytesPerSecond = :speed, updatedAtEpochMillis = :now WHERE id = :id")
    suspend fun updateProgress(id: String, bytes: Long, total: Long, speed: Long, now: Long)
    @Query("DELETE FROM transfers") suspend fun clear()
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache_entries WHERE nodeDocumentId = :documentId AND kind = :kind LIMIT 1")
    suspend fun find(documentId: String, kind: String): CacheEntryEntity?
    @Query("SELECT * FROM cache_entries WHERE disposable = 1 ORDER BY lastAccessedEpochMillis") suspend fun disposableOldestFirst(): List<CacheEntryEntity>
    @Query("SELECT COALESCE(SUM(size), 0) FROM cache_entries WHERE disposable = 1") suspend fun disposableBytes(): Long
    @Upsert suspend fun upsert(entry: CacheEntryEntity)
    @Query("DELETE FROM cache_entries WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM cache_entries") suspend fun clear()
}

@Dao
interface OfflinePinDao {
    @Query("SELECT * FROM offline_pins WHERE nodeDocumentId = :documentId") suspend fun get(documentId: String): OfflinePinEntity?
    @Upsert suspend fun upsert(pin: OfflinePinEntity)
    @Query("DELETE FROM offline_pins WHERE nodeDocumentId = :documentId") suspend fun delete(documentId: String)
    @Query("DELETE FROM offline_pins") suspend fun clear()
}

