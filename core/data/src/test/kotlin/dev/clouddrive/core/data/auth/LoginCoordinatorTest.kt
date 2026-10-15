package dev.clouddrive.core.data.auth

import dev.clouddrive.core.data.db.AccountDao
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.data.security.CredentialStore
import dev.clouddrive.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class LoginCoordinatorTest {
    private val start = LoginStart("https://cloud.example/login/v2/flow/test", "https://cloud.example/login/v2/poll", "poll-token")
    private val credential = LoginCredential("https://cloud.example", "alice", "app-password")
    private var saved: LoginCredential? = null
    private var accounts = 0
    private var indexStarts = 0
    private var polls = 0

    private fun coordinator(poll: () -> LoginPollResult): LoginCoordinator {
        val gateway = proxy<RemoteGateway> { method, args ->
            check(method == "pollLogin")
            assertEquals(start.pollEndpoint, args[0])
            assertEquals(start.pollToken, args[1])
            polls++
            poll()
        }
        val credentials = object : CredentialStore {
            override fun save(credential: LoginCredential) { saved = credential }
            override fun read() = saved
            override fun clear() { saved = null }
        }
        val accountDao = proxy<AccountDao> { method, _ ->
            check(method == "upsert")
            accounts++
            Unit
        }
        val folders = proxy<FolderSyncCoordinator> { method, _ ->
            when (method) {
                "notifyRoots" -> Unit
                "refresh" -> CloudResult.Success(FolderSnapshot(path = "/", state = FolderLoadState.EMPTY))
                else -> error(method)
            }
        }
        val scheduler = object : MetadataIndexScheduler {
            override fun start(restartCompleted: Boolean) { indexStarts++ }
            override fun pause() = Unit
            override fun resume() = Unit
        }
        return LoginCoordinator(gateway, credentials, accountDao, folders, scheduler)
    }

    @Test fun `brief connection failure keeps polling the same session and saves the granted account once`() = runBlocking {
        val coordinator = coordinator {
            if (polls == 1) throw IOException("Connection interrupted")
            LoginPollResult.Complete(credential)
        }
        assertEquals("alice", coordinator.await(start).loginName)
        assertEquals(2, polls)
        assertEquals(credential, saved)
        assertEquals(1, accounts)
        assertEquals(1, indexStarts)
    }

    @Test fun `temporary server failure can recover without starting another login`() = runBlocking {
        val coordinator = coordinator {
            if (polls == 1) throw CloudError.Network("Temporarily unavailable")
            LoginPollResult.Complete(credential)
        }
        coordinator.await(start)
        assertEquals(2, polls)
        assertEquals(credential, saved)
    }

    @Test fun `permission rejection is surfaced without silently retrying`() = runBlocking {
        try {
            coordinator { throw CloudError.PermissionDenied() }.await(start)
            fail("Permission rejection should be surfaced")
        } catch (_: CloudError.PermissionDenied) {
            assertEquals(1, polls)
            assertNull(saved)
            assertEquals(0, accounts)
        }
    }

    @Test fun `canceling pending browser sign-in stops polling without creating an account`() = runBlocking {
        val coordinator = coordinator { LoginPollResult.Pending }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.await(start) }
        assertEquals(1, polls)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(saved)
        assertEquals(0, accounts)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            try {
                call(method.name, args ?: emptyArray())
            } catch (problem: Exception) {
                val continuation = args?.lastOrNull() as? Continuation<Any?> ?: throw problem
                continuation.resumeWith(Result.failure(problem))
                COROUTINE_SUSPENDED
            }
        } as T
}
