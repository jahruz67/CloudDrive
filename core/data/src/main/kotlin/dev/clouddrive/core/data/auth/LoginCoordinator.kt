package dev.clouddrive.core.data.auth

import dev.clouddrive.core.data.db.AccountDao
import dev.clouddrive.core.data.db.toEntity
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.data.security.CredentialStore
import dev.clouddrive.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoginCoordinator @Inject constructor(
    private val gateway: RemoteGateway,
    private val credentials: CredentialStore,
    private val accountDao: AccountDao,
    private val folderSync: FolderSyncCoordinator,
    private val indexScheduler: MetadataIndexScheduler,
) {
    suspend fun start(serverUrl: String): LoginStart = gateway.startLogin(ServerUrlNormalizer.normalize(serverUrl))

    suspend fun await(start: LoginStart): Account = withTimeout(20 * 60 * 1_000L) {
        while (true) {
            when (val result = gateway.pollLogin(start.pollEndpoint, start.pollToken)) {
                LoginPollResult.Pending -> delay(1_000)
                is LoginPollResult.Complete -> {
                    credentials.save(result.credential)
                    val account = Account(
                        serverUrl = result.credential.serverUrl,
                        loginName = result.credential.loginName,
                        userId = result.credential.loginName,
                        displayName = result.credential.loginName,
                        createdAt = Instant.now(),
                    )
                    accountDao.upsert(account.toEntity())
                    folderSync.notifyRoots()
                    folderSync.refresh("/", force = true)
                    indexScheduler.start(restartCompleted = true)
                    return@withTimeout account
                }
            }
        }
        error("Unreachable")
    }
}
