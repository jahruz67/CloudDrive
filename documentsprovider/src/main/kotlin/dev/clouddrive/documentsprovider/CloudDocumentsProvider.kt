package dev.clouddrive.documentsprovider

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.*
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.clouddrive.core.data.db.AccountDao
import dev.clouddrive.core.data.db.CacheDao
import dev.clouddrive.core.data.db.RemoteNodeDao
import dev.clouddrive.core.data.db.toEntity
import dev.clouddrive.core.data.db.toModel
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.model.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class CloudDocumentsProvider : DocumentsProvider() {
    private lateinit var dependencies: ProviderDependencies
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authority by lazy { appContext().packageName + ".documents" }
    private var quotaRefreshJob: Job? = null
    private val quotaLock = Any()
    @Volatile private var cachedQuota: StorageQuota? = null
    private val refreshAttempts = ConcurrentHashMap<String, Long>()
    private val closeHandler by lazy {
        Handler(HandlerThread("CloudDocumentSaves").apply { start() }.looper)
    }

    override fun onCreate(): Boolean {
        dependencies = EntryPointAccessors.fromApplication(appContext(), ProviderDependencies::class.java)
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        val account = blocking { dependencies.accountDao().get() } ?: return cursor

        synchronized(quotaLock) {
            if (quotaRefreshJob?.isActive != true) {
                quotaRefreshJob = scope.launch {
                    try {
                        val newQuota = dependencies.gateway().quota()
                        if (newQuota != cachedQuota) {
                            cachedQuota = newQuota
                            dependencies.folderSync().notifyRoots()
                        }
                    } catch (_: Exception) {
                        // Background refresh failure (e.g. server unreachable) should not crash or leak
                    }
                }
            }
        }

        val quota = cachedQuota
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "CloudDrive")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "${account.displayName} · ${account.serverUrl}")
            // Surface the branded adaptive app icon in Android's system file picker instead
            // of the old platform upload glyph, which looks like an action rather than a location.
            add(DocumentsContract.Root.COLUMN_ICON, appContext().applicationInfo.icon)
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or DocumentsContract.Root.FLAG_SUPPORTS_RECENTS)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            val availableBytes = quota?.totalBytes?.let { total ->
                (total - quota.usedBytes).coerceAtLeast(0)
            }
            if (availableBytes != null) {
                add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, availableBytes)
            } else {
                add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, -1L)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        cursor.setNotificationUri(appContext().contentResolver, DocumentsContract.buildDocumentUri(authority, documentId))
        if (documentId == ROOT_DOCUMENT_ID) includeRoot(cursor) else {
            val node = requireNode(documentId)
            val pending = blocking { dependencies.cacheDao().find(documentId, "working") }
                ?.let { File(it.absolutePath) }?.takeIf(File::isFile)
            includeNode(cursor, if (pending == null) node else node.copy(
                size = pending.length(), modifiedAt = java.time.Instant.ofEpochMilli(pending.lastModified()),
            ))
        }
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        cursor.setNotificationUri(appContext().contentResolver, DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId))
        val parentPath = if (parentDocumentId == ROOT_DOCUMENT_ID) "/" else requireNode(parentDocumentId).path
        var snapshot = blocking { dependencies.folderSync().snapshot(parentPath) }
        var loading = false
        if (snapshot.state in setOf(FolderLoadState.NEVER_LOADED, FolderLoadState.STALE, FolderLoadState.FAILED) && claimRefresh(parentPath)) {
            if (snapshot.loadedAt == null) {
                blocking { dependencies.folderSync().refresh(parentPath, force = true) }
                snapshot = blocking { dependencies.folderSync().snapshot(parentPath) }
            } else {
                dependencies.folderSync().refreshAsync(parentPath, force = true)
                loading = true
            }
        }
        blocking { dependencies.folderSync().cachedChildren(parentPath) }.forEach { includeNode(cursor, it) }
        cursor.setExtras(Bundle().apply {
            putBoolean(DocumentsContract.EXTRA_LOADING, loading)
            snapshot.errorMessage?.let { putString(DocumentsContract.EXTRA_ERROR, it) }
        })
        return cursor
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        blocking { dependencies.nodeDao().recent("primary", 100) }
            .forEach { includeNode(cursor, it.toModel()) }
        return cursor
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        blocking {
            dependencies.repository().search(query, "/").let { result ->
                when (result) { is CloudResult.Success -> result.value; is CloudResult.Failure -> throw result.error }
            }
        }.forEach { includeNode(cursor, it) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val node = requireNode(documentId)
        if (node.isDirectory) throw FileNotFoundException("Folders cannot be opened as files")
        signal?.throwIfCanceled()
        return if (mode.contains('w')) {
            if (!node.canWrite) throw FileNotFoundException("This cloud file is read-only")
            val working = blocking { dependencies.cacheManager().workingCopy(node) }
            val before = digest(working)
            val baseEtag = blocking {
                dependencies.cacheDao().find(documentId, "working")?.etag
                    ?: dependencies.cacheDao().find(documentId, "offline")?.etag
                    ?: dependencies.cacheDao().find(documentId, "content")?.etag ?: node.etag
            }
            signal?.throwIfCanceled()
            ParcelFileDescriptor.open(working, ParcelFileDescriptor.parseMode(mode), closeHandler) { error ->
                try {
                    if (error == null && !before.contentEquals(digest(working))) {
                        blocking {
                            DocumentEditLocks.withLock(node.path) {
                                // An earlier local save may have finished while this descriptor was open.
                                // Rebase only against identical source bytes, preserving real remote conflicts.
                                val latestBase = listOf("working", "offline", "content").firstNotNullOfOrNull { kind ->
                                    dependencies.cacheDao().find(documentId, kind)?.takeIf { entry ->
                                        val source = File(entry.absolutePath)
                                        source.isFile && before.contentEquals(digest(source))
                                    }?.etag
                                } ?: baseEtag
                                dependencies.transferManager().enqueueLocalUpload(working, node.path, node.name, latestBase)
                            }
                        }
                        dependencies.folderSync().notifyFolder(node.parentPath)
                        appContext().contentResolver.notifyChange(DocumentsContract.buildDocumentUri(authority, documentId), null)
                    }
                    if (error == null) working.delete()
                } catch (failure: Exception) {
                    android.util.Log.e("CloudDocuments", "Unable to queue document save; working file retained", failure)
                }
            }
        } else {
            val file = blocking { dependencies.cacheManager().acquire(node) }
            signal?.throwIfCanceled()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val node = requireNode(documentId)
        signal?.throwIfCanceled()
        val file = blocking { dependencies.cacheManager().thumbnail(node, sizeHint.x, sizeHint.y) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = if (parentDocumentId == ROOT_DOCUMENT_ID) "/" else requireNode(parentDocumentId).path
        val path = RemotePath.child(parent, displayName)
        val node = blocking {
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) resultValue(dependencies.repository().createFolder(parent, displayName))
            else dependencies.gateway().createEmptyFile(path).also { dependencies.nodeDao().upsert(it.toEntity()) }
        }
        dependencies.folderSync().notifyFolder(parent)
        return node.documentId
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val node = requireNode(documentId)
        val updated = blocking { resultValue(dependencies.repository().rename(node, displayName)) }
        return updated.documentId
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val node = requireNode(sourceDocumentId)
        val target = if (targetParentDocumentId == ROOT_DOCUMENT_ID) "/" else requireNode(targetParentDocumentId).path
        val updated = blocking { resultValue(dependencies.repository().move(node, target)) }
        return updated.documentId
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val node = requireNode(sourceDocumentId)
        val target = if (targetParentDocumentId == ROOT_DOCUMENT_ID) "/" else requireNode(targetParentDocumentId).path
        val copied = blocking { resultValue(dependencies.repository().copy(node, target)) }
        return copied.documentId
    }

    override fun deleteDocument(documentId: String) {
        val node = requireNode(documentId)
        blocking { resultValue(dependencies.repository().delete(node)) }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parentPath = if (parentDocumentId == ROOT_DOCUMENT_ID) "/" else requireNode(parentDocumentId).path.trimEnd('/') + "/"
        return requireNode(documentId).path.startsWith(parentPath)
    }

    private fun includeRoot(cursor: MatrixCursor) {
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Cloud Drive")
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
            add(DocumentsContract.Document.COLUMN_SIZE, 0L)
            add(DocumentsContract.Document.COLUMN_ICON, appContext().applicationInfo.icon)
        }
    }

    private fun includeNode(cursor: MatrixCursor, node: RemoteNode) {
        var flags = 0
        if (node.isDirectory && node.canCreate) flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        if (!node.isDirectory && node.canWrite) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        if (node.canDelete) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        if (node.canRename) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        if (node.canWrite) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_MOVE or DocumentsContract.Document.FLAG_SUPPORTS_COPY
        if (node.hasPreview) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, node.documentId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, node.name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, node.mimeType)
            add(DocumentsContract.Document.COLUMN_SIZE, node.size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, node.modifiedAt.toEpochMilli())
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            add(DocumentsContract.Document.COLUMN_ICON, if (node.isDirectory) android.R.drawable.ic_menu_agenda else null)
        }
    }

    private fun requireNode(documentId: String): RemoteNode = blocking { dependencies.nodeDao().get(documentId)?.toModel() }
        ?: throw FileNotFoundException("Unknown cloud document")

    private fun appContext(): Context = checkNotNull(context)
    private fun claimRefresh(path: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        var claimed = false
        refreshAttempts.compute(path) { _, previous ->
            if (previous == null || now - previous >= 30_000) { claimed = true; now } else previous
        }
        return claimed
    }
    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
    private fun <T> blocking(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun <T> resultValue(result: CloudResult<T>): T = when (result) {
        is CloudResult.Success -> result.value
        is CloudResult.Failure -> throw FileNotFoundException(result.error.message)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderDependencies {
        fun accountDao(): AccountDao
        fun nodeDao(): RemoteNodeDao
        fun cacheDao(): CacheDao
        fun gateway(): RemoteGateway
        fun cacheManager(): CacheManager
        fun transferManager(): TransferManager
        fun repository(): CloudRepository
        fun folderSync(): FolderSyncCoordinator
    }

    private companion object {
        const val ROOT_ID = "primary"
        val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_ICON,
        )
    }
}
