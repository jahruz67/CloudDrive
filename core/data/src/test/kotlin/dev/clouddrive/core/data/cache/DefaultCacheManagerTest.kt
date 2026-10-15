package dev.clouddrive.core.data.cache

import android.content.ContextWrapper
import dev.clouddrive.core.data.db.*
import dev.clouddrive.core.data.network.DownloadResponse
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class DefaultCacheManagerTest {
    @get:Rule val temp = TemporaryFolder()
    private val entries = ConcurrentHashMap<String, CacheEntryEntity>()
    private val downloads = AtomicInteger()
    private var pinned = false
    private var downloadFailure: Exception? = null
    private val node = RemoteNode(
        "doc:1", fileId = "1", path = "/report.docx", parentPath = "/", name = "report.docx",
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        isDirectory = false, size = 6, modifiedAt = Instant.EPOCH, etag = "\"v1\"",
        permissions = "W", isFavorite = false, hasPreview = false,
    )

    private fun manager(): DefaultCacheManager {
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = temp.root
            override fun getCacheDir() = File(temp.root, "cache").apply { mkdirs() }
        }
        val cache = proxy<CacheDao> { method, args ->
            when (method) {
                "find" -> entries.values.firstOrNull { it.nodeDocumentId == args[0] && it.kind == args[1] }
                "upsert" -> { val entry = args[0] as CacheEntryEntity; entries[entry.id] = entry; Unit }
                "deleteWorkingFile" -> { entries.values.removeIf { it.nodeDocumentId == args[0] && it.kind == "working" && it.absolutePath == args[1] }; Unit }
                "disposableBytes" -> entries.values.filter { it.disposable }.sumOf { it.size }
                "expired" -> entries.values.filter { it.disposable && it.lastAccessedEpochMillis < args[0] as Long }
                "disposableOldestFirst" -> entries.values.filter { it.disposable }.sortedBy { it.lastAccessedEpochMillis }
                "delete" -> { entries.remove(args[0]); Unit }
                else -> error("Unexpected cache call: $method")
            }
        }
        val nodes = proxy<RemoteNodeDao> { method, _ ->
            when (method) { "updateCacheState" -> Unit; else -> error("Unexpected node call: $method") }
        }
        val pins = proxy<OfflinePinDao> { method, _ ->
            when (method) { "get" -> if (pinned) OfflinePinEntity(node.documentId, node.etag, 0) else null; else -> error(method) }
        }
        val gateway = proxy<RemoteGateway> { method, args ->
            when (method) {
                "download" -> {
                    downloads.incrementAndGet()
                    downloadFailure?.let { throw it }
                    Thread.sleep(30)
                    val destination = args[1] as File
                    destination.parentFile?.mkdirs()
                    destination.writeText("remote")
                    DownloadResponse(node.etag, 6, 6)
                }
                else -> error("Unexpected remote call: $method")
            }
        }
        return DefaultCacheManager(context, cache, nodes, pins, gateway)
    }

    private fun pending(text: String): File {
        val file = temp.newFile().apply { writeText(text) }
        entries["working:${node.documentId}"] = CacheEntryEntity(
            "working:${node.documentId}", node.documentId, file.absolutePath, node.etag,
            file.length(), 0, "working", false,
        )
        return file
    }

    @Test fun `reopening a pending edit reads saved bytes without a network request`() = runBlocking {
        val saved = pending("saved offline")
        assertEquals(saved, manager().acquire(node.copy(etag = "\"changed-remotely\"")))
        assertEquals(0, downloads.get())
    }

    @Test fun `writable opens have independent files and preserve earlier saved bytes`() = runBlocking {
        val saved = pending("first save")
        val manager = manager()
        val first = manager.workingCopy(node)
        first.writeText("new edit")
        val second = manager.workingCopy(node)
        assertNotEquals(first, second)
        assertEquals("new edit", first.readText())
        assertEquals("first save", second.readText())
        assertEquals("first save", saved.readText())
    }

    @Test fun `simultaneous reads share a single download`() = runBlocking {
        val manager = manager()
        val files = coroutineScope { List(5) { async(Dispatchers.IO) { manager.acquire(node) } }.awaitAll() }
        assertEquals(1, downloads.get())
        assertTrue(files.all { it.readText() == "remote" })
    }

    @Test fun `upload completion preserves a newer pending save and the offline pin`() = runBlocking {
        pinned = true
        val newer = pending("newer save")
        val uploaded = temp.newFile().apply { writeText("uploaded save") }
        val manager = manager()
        manager.storeUploaded(node.copy(etag = "\"v2\""), uploaded)
        assertEquals(newer.absolutePath, entries["working:${node.documentId}"]?.absolutePath)
        assertEquals("uploaded save", File(entries.getValue("offline:${node.documentId}").absolutePath).readText())
        assertFalse(entries.getValue("offline:${node.documentId}").disposable)
        assertEquals("newer save", manager.acquire(node).readText())
    }

    @Test fun `acknowledged edit is immediately readable from the refreshed cache`() = runBlocking {
        val saved = pending("saved")
        val manager = manager()
        val fresh = node.copy(etag = "\"v2\"")
        manager.storeUploaded(fresh, saved)
        assertNull(entries["working:${node.documentId}"])
        assertEquals("saved", manager.acquire(fresh).readText())
        assertEquals(0, downloads.get())
    }

    @Test fun `cache expiry never removes unsynced edits`() = runBlocking {
        val saved = pending("not uploaded yet")
        manager().maintain(limitBytes = 0, maxAgeDays = 1)
        assertTrue(saved.isFile)
        assertNotNull(entries["working:${node.documentId}"])
    }

    @Test fun `a pinned file still opens offline when newer server metadata was indexed`() = runBlocking {
        pinned = true
        val manager = manager()
        val original = manager.acquire(node)
        downloadFailure = CloudError.Network("Offline")
        assertEquals(original, manager.acquire(node.copy(etag = "\"v2\"")))
        assertEquals("remote", original.readText())
        assertEquals(node.etag, entries.getValue("offline:${node.documentId}").etag)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            try {
                call(method.name, args ?: emptyArray())
            } catch (problem: Exception) {
                // Suspend failures travel through the continuation, avoiding Java Proxy's checked-exception wrapper.
                val continuation = args?.lastOrNull() as? Continuation<Any?> ?: throw problem
                continuation.resumeWith(Result.failure(problem))
                COROUTINE_SUSPENDED
            }
        } as T
}
