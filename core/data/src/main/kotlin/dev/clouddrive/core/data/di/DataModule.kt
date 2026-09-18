package dev.clouddrive.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clouddrive.core.data.cache.DefaultCacheManager
import dev.clouddrive.core.data.db.*
import dev.clouddrive.core.data.network.NextcloudRemoteGateway
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.data.repository.DefaultCloudRepository
import dev.clouddrive.core.data.security.CredentialStore
import dev.clouddrive.core.data.security.KeystoreCredentialStore
import dev.clouddrive.core.model.CacheManager
import dev.clouddrive.core.model.CloudRepository
import dev.clouddrive.core.model.FolderSyncCoordinator
import dev.clouddrive.core.model.MetadataIndexScheduler
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataProvidesModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): CloudDatabase =
        Room.databaseBuilder(context, CloudDatabase::class.java, "cloud-drive.db")
            .addMigrations(object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `folder_snapshots` (`accountId` TEXT NOT NULL, `path` TEXT NOT NULL, `state` TEXT NOT NULL, `loadedAtEpochMillis` INTEGER, `errorMessage` TEXT, PRIMARY KEY(`accountId`, `path`))")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_snapshots_state` ON `folder_snapshots` (`state`)")
                }
            })
            .build()

    @Provides fun accountDao(db: CloudDatabase) = db.accountDao()
    @Provides fun remoteNodeDao(db: CloudDatabase) = db.remoteNodeDao()
    @Provides fun transferDao(db: CloudDatabase) = db.transferDao()
    @Provides fun cacheDao(db: CloudDatabase) = db.cacheDao()
    @Provides fun offlinePinDao(db: CloudDatabase) = db.offlinePinDao()
    @Provides fun folderSnapshotDao(db: CloudDatabase) = db.folderSnapshotDao()

    @Provides @Singleton fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds @Singleton abstract fun credentials(implementation: KeystoreCredentialStore): CredentialStore
    @Binds @Singleton abstract fun gateway(implementation: NextcloudRemoteGateway): RemoteGateway
    @Binds @Singleton abstract fun repository(implementation: DefaultCloudRepository): CloudRepository
    @Binds @Singleton abstract fun cache(implementation: DefaultCacheManager): CacheManager
    @Binds @Singleton abstract fun folderSync(implementation: dev.clouddrive.core.data.sync.DefaultFolderSyncCoordinator): FolderSyncCoordinator
    @Binds @Singleton abstract fun metadataIndexScheduler(implementation: dev.clouddrive.core.data.sync.WorkMetadataIndexScheduler): MetadataIndexScheduler
}
