package dev.clouddrive.core.transfers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.clouddrive.core.data.db.RemoteNodeDao
import dev.clouddrive.core.data.db.TransferDao
import dev.clouddrive.core.data.db.toEntity
import dev.clouddrive.core.data.db.toModel
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.data.settings.SettingsRepository
import dev.clouddrive.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferQueueDrainer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val transferDao: TransferDao,
    private val nodeDao: RemoteNodeDao,
    private val gateway: RemoteGateway,
    private val repository: CloudRepository,
    private val settingsRepository: SettingsRepository,
    private val folderSync: FolderSyncCoordinator,
) {
    private val drainMutex = Mutex()
    private val progressSamples = ConcurrentHashMap<String, Pair<Long, Long>>()

    init {
        createChannel()
    }

    suspend fun drain(isRecovery: Boolean = false): Boolean {
        if (!drainMutex.tryLock()) {
            return false
        }
        try {
            transferDao.requeueInterrupted(now())
            while (true) {
                val settings = settingsRepository.settings.first()
                if (settings.wifiOnly && !isUnmetered()) {
                    updateNotification("Waiting for Wi-Fi", 0, 0)
                    delay(5_000)
                    val queued = transferDao.byStates(listOf(TransferState.QUEUED))
                    if (queued.isEmpty()) break
                    if (isRecovery) {
                        delay(10_000)
                    }
                    continue
                }
                val queued = transferDao.byStates(listOf(TransferState.QUEUED)).take(settings.maxConcurrentTransfers)
                if (queued.isEmpty()) break
                coroutineScope {
                    queued.map { async { runTransfer(it.toModel()) } }.awaitAll()
                }
            }
            return true
        } finally {
            drainMutex.unlock()
        }
    }

    private suspend fun runTransfer(transfer: Transfer) {
        transferDao.updateState(transfer.id, TransferState.RUNNING, now())
        updateNotification(transfer.displayName, transfer.bytesTransferred, transfer.totalBytes)
        try {
            var attempt = 0
            while (true) {
                try {
                    when (transfer.direction) {
                        TransferDirection.UPLOAD -> upload(transfer)
                        TransferDirection.DOWNLOAD -> download(transfer)
                    }
                    break
                } catch (error: Exception) {
                    if (error is TransferControlException || error is CancellationException || error is CloudError.Authentication ||
                        error is CloudError.PermissionDenied || error is CloudError.QuotaExceeded || error is CloudError.Conflict || attempt >= 3
                    ) throw error
                    delay((1L shl attempt) * 2_000L)
                    attempt++
                }
            }
            ensureRunning(transfer.id)
            transferDao.updateState(transfer.id, TransferState.COMPLETED, now())
        } catch (_: TransferControlException) {
            // The requested PAUSED or CANCELED state is already persisted.
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            transferDao.updateState(transfer.id, TransferState.FAILED, now(), friendlyError(error))
        }
    }

    private suspend fun download(transfer: Transfer) {
        val metadata = transfer.contentUri.orEmpty()
        val documentId = metadata.substringAfter("node:").substringBefore(';')
        val makeOffline = metadata.substringAfter("offline:", "0") == "1"
        val node = nodeDao.get(documentId)?.toModel() ?: gateway.stat(transfer.remotePath).also { nodeDao.upsert(it.toEntity()) }
        val result = if (makeOffline) repository.pinOffline(node) else repository.cachedFile(node)
        if (result is CloudResult.Failure) throw result.error
        transferDao.updateProgress(transfer.id, node.size, node.size, 0, now())
    }

    private suspend fun upload(transfer: Transfer) {
        val staged = transfer.localPath?.let(::File)?.takeIf(File::exists) ?: stageUri(transfer)
        val updated = transfer.copy(localPath = staged.absolutePath, totalBytes = staged.length(), state = TransferState.RUNNING, updatedAt = Instant.now())
        transferDao.upsert(updated.toEntity())

        val uploaded = if (staged.length() < CHUNK_SIZE) {
            val mediaType = transfer.contentUri?.let(Uri::parse)?.let(context.contentResolver::getType)?.toMediaTypeOrNull()
            val body = staged.asRequestBody(mediaType)
            try {
                gateway.upload(transfer.remotePath, body, ifMatch = transfer.baseEtag, ifNoneMatch = transfer.baseEtag == null) { sent, total ->
                    progress(transfer.id, sent, total)
                }
            } catch (_: CloudError.Conflict) {
                val original = transfer.remotePath.substringAfterLast('/')
                val parent = RemotePath.parent(transfer.remotePath)
                var attempt = 0
                var result: RemoteNode? = null
                val maxAttempts = 10
                while (result == null && attempt < maxAttempts) {
                    val conflictPath = RemotePath.child(parent, ConflictNames.candidate(original, java.time.LocalDateTime.now(), attempt))
                    try {
                        result = gateway.upload(conflictPath, staged.asRequestBody(mediaType), ifNoneMatch = true) { sent, total ->
                            progress(transfer.id, sent, total)
                        }
                    } catch (_: CloudError.Conflict) {
                        attempt++
                    }
                }
                result ?: throw CloudError.Conflict("Could not resolve upload conflict after $maxAttempts attempts")
            }
        } else {
            val uploadId = transfer.uploadId ?: "clouddrive-${transfer.id}"
            var chunk = transfer.completedChunks.coerceAtLeast(0)
            while (chunk * CHUNK_SIZE < staged.length()) {
                ensureRunning(transfer.id)
                val offset = chunk * CHUNK_SIZE
                val length = minOf(CHUNK_SIZE, staged.length() - offset)
                gateway.uploadChunk(uploadId, transfer.remotePath, chunk + 1, staged.length(), FileSliceRequestBody(staged, offset, length))
                chunk++
                val bytesSoFar = minOf(chunk * CHUNK_SIZE, staged.length())
                transferDao.upsert(updated.copy(uploadId = uploadId, completedChunks = chunk, bytesTransferred = bytesSoFar).toEntity())
                progress(transfer.id, bytesSoFar, staged.length())
            }
            try {
                gateway.assembleChunks(uploadId, transfer.remotePath, staged.length(), ifMatch = transfer.baseEtag, ifNoneMatch = transfer.baseEtag == null)
            } catch (_: CloudError.Conflict) {
                val original = transfer.remotePath.substringAfterLast('/')
                val parent = RemotePath.parent(transfer.remotePath)
                var attempt = 0
                var result: RemoteNode? = null
                val maxAttempts = 10
                while (result == null && attempt < maxAttempts) {
                    val conflictPath = RemotePath.child(parent, ConflictNames.candidate(original, java.time.LocalDateTime.now(), attempt))
                    try {
                        result = gateway.assembleChunks(uploadId, conflictPath, staged.length(), ifNoneMatch = true)
                    } catch (_: CloudError.Conflict) {
                        attempt++
                    }
                }
                result ?: throw CloudError.Conflict("Could not resolve chunked upload conflict after $maxAttempts attempts")
            }
        }
        nodeDao.upsert(uploaded.toEntity())
        folderSync.notifyFolder(uploaded.parentPath)
        staged.delete()
    }

    private suspend fun stageUri(transfer: Transfer): File = withContext(Dispatchers.IO) {
        val uri = Uri.parse(requireNotNull(transfer.contentUri))
        val dir = File(context.filesDir, "transfer-staging").apply { mkdirs() }
        val target = File(dir, transfer.id)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var copied = 0L
                while (true) {
                    ensureRunning(transfer.id)
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    transferDao.updateProgress(transfer.id, copied, -1, 0, now())
                }
            }
        } ?: error("The selected file is no longer available")
        target
    }

    private suspend fun progress(id: String, bytes: Long, total: Long) {
        ensureRunning(id)
        val timestamp = now()
        val previous = progressSamples.put(id, bytes to timestamp)
        val speed = previous?.let { (oldBytes, oldTime) ->
            val elapsed = (timestamp - oldTime).coerceAtLeast(1)
            ((bytes - oldBytes).coerceAtLeast(0) * 1_000 / elapsed)
        } ?: 0L
        transferDao.updateProgress(id, bytes, total, speed, timestamp)
        updateNotification("Transferring", bytes, total)
    }

    private suspend fun ensureRunning(id: String) {
        val state = transferDao.get(id)?.state ?: TransferState.CANCELED
        if (state != TransferState.RUNNING) throw TransferControlException()
    }

    private fun isUnmetered(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    private fun createChannel() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "File transfers", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun notification(title: String, progress: Long, total: Long): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("CloudDrive")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (total > 0) ((progress * 100 / total).coerceIn(0, 100)).toInt() else 0, total <= 0)
            .build()

    fun updateNotification(title: String, progress: Long, total: Long) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification(title, progress, total))
    }

    private fun now() = Instant.now().toEpochMilli()

    private fun friendlyError(error: Exception): String = when (error) {
        is CloudError.Authentication -> "Your login expired. Sign in again, then retry this transfer."
        is CloudError.PermissionDenied -> "Nextcloud denied permission for this transfer."
        is CloudError.QuotaExceeded -> "There is not enough storage quota on Nextcloud."
        is CloudError.Conflict -> error.message ?: "The file changed on Nextcloud."
        is CloudError.Network -> error.message ?: "The network connection was interrupted."
        else -> error.message ?: "Transfer failed. Try again."
    }

    companion object {
        const val CHANNEL_ID = "file_transfers"
        const val NOTIFICATION_ID = 40
        const val CHUNK_SIZE = 10L * 1024 * 1024
    }
}

private class TransferControlException : Exception()

private class FileSliceRequestBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
    override fun contentLength() = length
    override fun writeTo(sink: okio.BufferedSink) {
        file.inputStream().use { input ->
            input.channel.position(offset)
            val buffer = ByteArray(128 * 1024)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                sink.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}
