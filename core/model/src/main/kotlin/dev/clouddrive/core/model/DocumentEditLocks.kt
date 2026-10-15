package dev.clouddrive.core.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Coordinate save snapshots and upload acknowledgements for the same document. */
object DocumentEditLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(path: String, block: suspend () -> T): T =
        locks.getOrPut(RemotePath.normalize(path), ::Mutex).withLock { block() }
}
