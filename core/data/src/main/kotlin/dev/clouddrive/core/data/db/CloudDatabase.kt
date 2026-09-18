package dev.clouddrive.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.clouddrive.core.model.CacheState
import dev.clouddrive.core.model.TransferDirection
import dev.clouddrive.core.model.TransferState

class EnumConverters {
    @TypeConverter fun cacheState(value: String) = CacheState.valueOf(value)
    @TypeConverter fun cacheState(value: CacheState) = value.name
    @TypeConverter fun transferDirection(value: String) = TransferDirection.valueOf(value)
    @TypeConverter fun transferDirection(value: TransferDirection) = value.name
    @TypeConverter fun transferState(value: String) = TransferState.valueOf(value)
    @TypeConverter fun transferState(value: TransferState) = value.name
}

@Database(
    entities = [AccountEntity::class, RemoteNodeEntity::class, TransferEntity::class, CacheEntryEntity::class, OfflinePinEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
abstract class CloudDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun remoteNodeDao(): RemoteNodeDao
    abstract fun transferDao(): TransferDao
    abstract fun cacheDao(): CacheDao
    abstract fun offlinePinDao(): OfflinePinDao
}

