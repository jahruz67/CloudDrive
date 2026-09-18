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
import dev.clouddrive.core.data.db.RemoteNodeDao
import dev.clouddrive.core.data.db.toEntity
import dev.clouddrive.core.data.db.toModel
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.model.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileNotFoundException

class CloudDocumentsProvider : DocumentsProvider() {
    private lateinit var dependencies: ProviderDependencies
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authority by lazy { appContext().packageName + ".documents" }

    override fun onCreate(): Boolean {
        dependencies = EntryPointAccessors.fromApplication(appContext(), ProviderDependencies::class.java)
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        val account = blocking { dependencies.accountDao().get() } ?: return cursor
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "CloudDrive")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "${account.displayName} · ${account.serverUrl}")
            add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_upload)
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or DocumentsContract.Root.FLAG_SUPPORTS_RECENTS)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, -1L)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        if (documentId == ROOT_DOCUMENT_ID) includeRoot(cursor) else includeNode(cursor, requireNode(documentId))
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val parentPath = if (parentDocumentId == ROOT_DOCUMENT_ID) "/" else requireNode(parentDocumentId).path
        blocking { dependencies.nodeDao().children("primary", parentPath) }.forEach { includeNode(cursor, it.toModel()) }
        cursor.setExtras(Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) })
        refresh(parentPath, parentDocumentId)
        return cursor
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        blocking { dependencies.nodeDao().children("primary", "/") }.sortedByDescending { it.modifiedAtEpochMillis }.take(100)
            .forEach { includeNode(cursor, it.toModel()) }
        return cursor
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        blocking {
            dependencies.gateway().search(query, "/").also { dependencies.nodeDao().upsertAll(it.map(RemoteNode::toEntity)) }
        }.forEach { includeNode(cursor, it) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val node = requireNode(documentId)
        if (node.isDirectory) throw FileNotFoundException("Folders cannot be opened as files")
        signal?.throwIfCanceled()
        return if (mode.contains('w')) {
            val working = blocking { dependencies.cacheManager().workingCopy(node) }
            ParcelFileDescriptor.open(
                working,
                ParcelFileDescriptor.parseMode(mode),
                Handler(Looper.getMainLooper()),
            ) {
                scope.launch { dependencies.transferManager().enqueueLocalUpload(working, node.path, node.name, node.etag) }
            }
        } else {
            val file = blocking { dependencies.cacheManager().acquire(node) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val node = requireNode(documentId)
        val file = blocking { dependencies.cacheManager().acquire(node) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = if (parentDocumentId == ROOT_DOCUMENT_ID) "/" else requireNode(parentDocumentId).path
        val path = RemotePath.child(parent, displayName)
        val node = blocking {
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) dependencies.gateway().createFolder(path)
            else dependencies.gateway().createEmptyFile(path)
        }
        blocking { dependencies.nodeDao().upsert(node.toEntity()) }
        notifyParent(parentDocumentId)
        return node.documentId
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val node = requireNode(documentId)
        val updated = blocking { dependencies.gateway().move(node.path, RemotePath.child(node.parentPath, displayName)) }
        blocking { dependencies.nodeDao().upsert(updated.toEntity()) }
        notifyParent(documentId)
        return updated.documentId
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val node = requireNode(sourceDocumentId)
        val target = if (targetParentDocumentId == ROOT_DOCUMENT_ID) "/" else requireNode(targetParentDocumentId).path
        val updated = blocking { dependencies.gateway().move(node.path, RemotePath.child(target, node.name)) }
        blocking { dependencies.nodeDao().upsert(updated.toEntity()) }
        notifyParent(sourceParentDocumentId); notifyParent(targetParentDocumentId)
        return updated.documentId
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val node = requireNode(sourceDocumentId)
        val target = if (targetParentDocumentId == ROOT_DOCUMENT_ID) "/" else requireNode(targetParentDocumentId).path
        val copied = blocking { dependencies.gateway().copy(node.path, RemotePath.child(target, node.name)) }
        blocking { dependencies.nodeDao().upsert(copied.toEntity()) }
        notifyParent(targetParentDocumentId)
        return copied.documentId
    }

    override fun deleteDocument(documentId: String) {
        val node = requireNode(documentId)
        blocking { dependencies.gateway().delete(node.path); dependencies.nodeDao().deleteTree(documentId, "${node.path}/%") }
        notifyParent(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parentPath = if (parentDocumentId == ROOT_DOCUMENT_ID) "/" else requireNode(parentDocumentId).path.trimEnd('/') + "/"
        return requireNode(documentId).path.startsWith(parentPath)
    }

    private fun refresh(path: String, parentDocumentId: String) {
        scope.launch {
            runCatching {
                val rows = dependencies.gateway().listFolder(path)
                dependencies.nodeDao().upsertAll(rows.map(RemoteNode::toEntity))
            }
            notifyParent(parentDocumentId)
        }
    }

    private fun includeRoot(cursor: MatrixCursor) {
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Cloud Drive")
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
            add(DocumentsContract.Document.COLUMN_SIZE, 0L)
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

    private fun notifyParent(documentId: String) {
        context?.contentResolver?.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, documentId), null, false)
        context?.contentResolver?.notifyChange(DocumentsContract.buildDocumentUri(authority, documentId), null, false)
    }

    private fun appContext(): Context = checkNotNull(context)
    private fun <T> blocking(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderDependencies {
        fun accountDao(): AccountDao
        fun nodeDao(): RemoteNodeDao
        fun gateway(): RemoteGateway
        fun cacheManager(): CacheManager
        fun transferManager(): TransferManager
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
