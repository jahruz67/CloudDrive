package dev.clouddrive.core.data.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clouddrive.core.data.db.*
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.model.CacheManager
import dev.clouddrive.core.model.CacheState
import dev.clouddrive.core.model.RemoteNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheDao: CacheDao,
    private val nodeDao: RemoteNodeDao,
    private val pinDao: OfflinePinDao,
    private val gateway: RemoteGateway,
) : CacheManager {
    private val contentDir by lazy { File(context.cacheDir, "cloud-content").apply { mkdirs() } }
    private val offlineDir by lazy { File(context.filesDir, "offline-content").apply { mkdirs() } }
    private val workingDir by lazy { File(context.filesDir, "pending-edits").apply { mkdirs() } }
    private val thumbnailDir by lazy { File(context.cacheDir, "cloud-thumbnails").apply { mkdirs() } }

    override suspend fun acquire(node: RemoteNode): File = withContext(Dispatchers.IO) {
        val pinned = pinDao.get(node.documentId) != null
        val existing = cacheDao.find(node.documentId, if (pinned) "offline" else "content")
        if (existing != null && existing.etag == node.etag && File(existing.absolutePath).isFile) {
            cacheDao.upsert(existing.copy(lastAccessedEpochMillis = System.currentTimeMillis()))
            return@withContext File(existing.absolutePath)
        }
        val target = File(if (pinned) offlineDir else contentDir, key(node))
        val partial = File(target.absolutePath + ".partial")
        val offset = partial.takeIf(File::exists)?.length() ?: 0L
        val response = gateway.download(node.path, partial, offset, node.etag)
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Unable to finalize cached file" }
        existing?.takeIf { it.absolutePath != target.absolutePath }?.let { File(it.absolutePath).delete() }
        cacheDao.upsert(
            CacheEntryEntity(
                id = "${if (pinned) "offline" else "content"}:${node.documentId}", nodeDocumentId = node.documentId,
                absolutePath = target.absolutePath, etag = response.etag ?: node.etag, size = target.length(),
                lastAccessedEpochMillis = System.currentTimeMillis(), kind = if (pinned) "offline" else "content", disposable = !pinned,
            ),
        )
        nodeDao.updateCacheState(node.documentId, if (pinned) CacheState.OFFLINE else CacheState.CACHED)
        maintain()
        target
    }

    override suspend fun workingCopy(node: RemoteNode): File = withContext(Dispatchers.IO) {
        val source = acquire(node)
        val target = File(workingDir, key(node) + ".working")
        source.copyTo(target, overwrite = true)
        cacheDao.upsert(
            CacheEntryEntity("working:${node.documentId}", node.documentId, target.absolutePath, node.etag, target.length(), System.currentTimeMillis(), "working", false),
        )
        target
    }

    override suspend fun pin(node: RemoteNode) {
        pinDao.upsert(OfflinePinEntity(node.documentId, node.etag, Instant.now().toEpochMilli()))
        nodeDao.updateCacheState(node.documentId, CacheState.OFFLINE)
        acquire(node)
    }

    override suspend fun unpin(node: RemoteNode) = withContext(Dispatchers.IO) {
        pinDao.delete(node.documentId)
        val entry = cacheDao.find(node.documentId, "offline")
        if (entry != null) {
            val destination = File(contentDir, key(node))
            File(entry.absolutePath).copyTo(destination, overwrite = true)
            File(entry.absolutePath).delete()
            cacheDao.delete(entry.id)
            cacheDao.upsert(entry.copy(id = "content:${node.documentId}", absolutePath = destination.absolutePath, kind = "content", disposable = true))
            nodeDao.updateCacheState(node.documentId, CacheState.CACHED)
        } else nodeDao.updateCacheState(node.documentId, CacheState.CLOUD_ONLY)
    }

    override suspend fun clearDisposable(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        cacheDao.disposableOldestFirst().forEach { entry ->
            freed += removeEntry(entry)
        }
        freed
    }

    override suspend fun evictToLimit(limitBytes: Long): Long = withContext(Dispatchers.IO) {
        var current = cacheDao.disposableBytes()
        var freed = 0L
        if (current <= limitBytes) return@withContext 0L
        for (entry in cacheDao.disposableOldestFirst()) {
            val size = removeEntry(entry)
            current -= size
            freed += size
            if (current <= (limitBytes * 9 / 10)) break
        }
        freed
    }

    override suspend fun disposableBytes() = cacheDao.disposableBytes()

    override suspend fun thumbnail(node: RemoteNode, width: Int, height: Int): File = withContext(Dispatchers.IO) {
        if (!node.hasPreview) throw dev.clouddrive.core.model.CloudError.Unsupported("A preview is not available for this file")
        val kind = "thumbnail:${width.coerceAtLeast(1)}x${height.coerceAtLeast(1)}"
        val existing = cacheDao.find(node.documentId, kind)
        if (existing != null && existing.etag == node.etag && File(existing.absolutePath).isFile) {
            cacheDao.upsert(existing.copy(lastAccessedEpochMillis = System.currentTimeMillis()))
            return@withContext File(existing.absolutePath)
        }
        val target = File(thumbnailDir, "${key(node)}-${width}x${height}")
        gateway.downloadPreview(node.path, width, height, target)
        existing?.takeIf { it.absolutePath != target.absolutePath }?.let { File(it.absolutePath).delete() }
        cacheDao.upsert(CacheEntryEntity(
            id = "$kind:${node.documentId}", nodeDocumentId = node.documentId, absolutePath = target.absolutePath,
            etag = node.etag, size = target.length(), lastAccessedEpochMillis = System.currentTimeMillis(),
            kind = kind, disposable = true,
        ))
        maintain()
        target
    }

    override suspend fun maintain(limitBytes: Long, maxAgeDays: Int): Long = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeDays.coerceAtLeast(1) * 24L * 60 * 60 * 1_000
        var freed = 0L
        cacheDao.expired(cutoff).forEach { freed += removeEntry(it) }
        cacheDao.workingEntries().filter { it.lastAccessedEpochMillis < cutoff }.forEach { freed += removeEntry(it) }
        listOf(contentDir, thumbnailDir, workingDir).forEach { dir ->
            dir.listFiles()?.filter { it.name.endsWith(".partial") && it.lastModified() < cutoff }?.forEach {
                freed += it.length(); it.delete()
            }
        }
        freed + evictToLimit(limitBytes)
    }

    private suspend fun removeEntry(entry: CacheEntryEntity): Long {
        val file = File(entry.absolutePath)
        val size = file.length()
        file.delete()
        cacheDao.delete(entry.id)
        if (entry.kind == "content") nodeDao.updateCacheState(entry.nodeDocumentId, CacheState.CLOUD_ONLY)
        return size
    }

    private fun key(node: RemoteNode): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${node.documentId}:${node.etag}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
