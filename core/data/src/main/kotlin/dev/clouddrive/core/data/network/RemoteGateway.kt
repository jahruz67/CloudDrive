package dev.clouddrive.core.data.network

import dev.clouddrive.core.model.*
import okhttp3.RequestBody
import java.io.File

data class DownloadResponse(val etag: String?, val bytesWritten: Long, val totalBytes: Long)

interface RemoteGateway {
    suspend fun startLogin(serverUrl: String): LoginStart
    suspend fun pollLogin(endpoint: String, token: String): LoginPollResult
    suspend fun listFolder(path: String): List<RemoteNode>
    suspend fun search(query: String, scope: String): List<RemoteNode>
    suspend fun stat(path: String): RemoteNode
    suspend fun createFolder(path: String): RemoteNode
    suspend fun createEmptyFile(path: String): RemoteNode
    suspend fun delete(path: String)
    suspend fun move(fromPath: String, toPath: String, overwrite: Boolean = false): RemoteNode
    suspend fun copy(fromPath: String, toPath: String, overwrite: Boolean = false): RemoteNode
    suspend fun setFavorite(path: String, favorite: Boolean)
    suspend fun download(path: String, destination: File, offset: Long = 0, ifRange: String? = null, progress: suspend (Long, Long) -> Unit = { _, _ -> }): DownloadResponse
    suspend fun downloadPreview(path: String, width: Int, height: Int, destination: File): File
    suspend fun upload(path: String, body: RequestBody, ifMatch: String? = null, ifNoneMatch: Boolean = false, progress: suspend (Long, Long) -> Unit = { _, _ -> }): RemoteNode
    suspend fun uploadChunk(uploadId: String, destinationPath: String, chunkNumber: Int, totalBytes: Long, body: RequestBody)
    suspend fun assembleChunks(uploadId: String, destinationPath: String, totalBytes: Long, ifMatch: String? = null, ifNoneMatch: Boolean = false): RemoteNode
    suspend fun abortChunks(uploadId: String)
    suspend fun quota(): StorageQuota
    suspend fun revokeCredential()
}
