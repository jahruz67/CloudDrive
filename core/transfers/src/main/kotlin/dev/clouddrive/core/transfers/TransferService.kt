package dev.clouddrive.core.transfers

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.clouddrive.core.data.db.*
import dev.clouddrive.core.data.network.RemoteGateway
import dev.clouddrive.core.data.settings.SettingsRepository
import dev.clouddrive.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class TransferService : Service() {
    @Inject lateinit var transferDao: TransferDao
    @Inject lateinit var nodeDao: RemoteNodeDao
    @Inject lateinit var gateway: RemoteGateway
    @Inject lateinit var repository: CloudRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runner: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Preparing transfers…", 0, 0))
        if (runner?.isActive != true) runner = serviceScope.launch { drainQueue() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun drainQueue() {
        try {
            while (true) {
                val settings = settingsRepository.settings.first()
                if (settings.wifiOnly && !isUnmetered()) {
                    updateNotification("Waiting for Wi-Fi", 0, 0)
                    delay(15_000)
                    continue
                }
                val queued = transferDao.byStates(listOf(TransferState.QUEUED)).take(settings.maxConcurrentTransfers)
                if (queued.isEmpty()) break
                coroutineScope { queued.map { async { runTransfer(it.toModel()) } }.awaitAll() }
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun runTransfer(transfer: Transfer) {
        transferDao.updateState(transfer.id, TransferState.RUNNING, now())
        updateNotification(transfer.displayName, transfer.bytesTransferred, transfer.totalBytes)
        try {
            when (transfer.direction) {
                TransferDirection.UPLOAD -> upload(transfer)
                TransferDirection.DOWNLOAD -> download(transfer)
            }
            ensureRunning(transfer.id)
            transferDao.updateState(transfer.id, TransferState.COMPLETED, now())
        } catch (control: TransferControlException) {
            // The requested PAUSED or CANCELED state is already persisted.
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            transferDao.updateState(transfer.id, TransferState.FAILED, now(), error.message ?: "Transfer failed")
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
        val updated = transfer.copy(localPath = staged.absolutePath, totalBytes = staged.length(), updatedAt = Instant.now())
        transferDao.upsert(updated.toEntity())
        if (staged.length() < CHUNK_SIZE) {
            val mediaType = transfer.contentUri?.let(Uri::parse)?.let(contentResolver::getType)?.toMediaTypeOrNull()
            val body = staged.asRequestBody(mediaType)
            try {
                gateway.upload(transfer.remotePath, body, ifMatch = transfer.baseEtag) { sent, total -> progress(transfer.id, sent, total) }
            } catch (conflict: CloudError.Conflict) {
                val original = transfer.remotePath.substringAfterLast('/')
                val parent = RemotePath.parent(transfer.remotePath)
                var attempt = 0
                while (true) {
                    val conflictPath = RemotePath.child(parent, ConflictNames.candidate(original, java.time.LocalDateTime.now(), attempt))
                    try {
                        gateway.upload(conflictPath, staged.asRequestBody(mediaType), ifNoneMatch = true) { sent, total -> progress(transfer.id, sent, total) }
                        break
                    } catch (_: CloudError.Conflict) {
                        attempt++
                    }
                }
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
                transferDao.upsert(updated.copy(uploadId = uploadId, completedChunks = chunk, bytesTransferred = minOf(chunk * CHUNK_SIZE, staged.length())).toEntity())
                progress(transfer.id, minOf(chunk * CHUNK_SIZE, staged.length()), staged.length())
            }
            gateway.assembleChunks(uploadId, transfer.remotePath, staged.length())
        }
        staged.delete()
    }

    private suspend fun stageUri(transfer: Transfer): File = withContext(Dispatchers.IO) {
        val uri = Uri.parse(requireNotNull(transfer.contentUri))
        val dir = File(filesDir, "transfer-staging").apply { mkdirs() }
        val target = File(dir, transfer.id)
        contentResolver.openInputStream(uri)?.use { input ->
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
        transferDao.updateProgress(id, bytes, total, 0, now())
        updateNotification("Transferring", bytes, total)
    }

    private suspend fun ensureRunning(id: String) {
        val state = transferDao.get(id)?.state ?: TransferState.CANCELED
        if (state != TransferState.RUNNING) throw TransferControlException()
    }

    private fun isUnmetered(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    private fun createChannel() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "File transfers", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(title: String, progress: Long, total: Long): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("CloudDrive")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (total > 0) ((progress * 100 / total).coerceIn(0, 100)).toInt() else 0, total <= 0)
            .build()

    private fun updateNotification(title: String, progress: Long, total: Long) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(title, progress, total))
    }

    private fun now() = Instant.now().toEpochMilli()

    private companion object {
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
