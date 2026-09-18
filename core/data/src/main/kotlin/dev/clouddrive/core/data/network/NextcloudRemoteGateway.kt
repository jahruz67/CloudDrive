package dev.clouddrive.core.data.network

import android.util.Xml
import dev.clouddrive.core.data.security.CredentialStore
import dev.clouddrive.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URLDecoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NextcloudRemoteGateway @Inject constructor(
    private val client: OkHttpClient,
    private val credentialStore: CredentialStore,
) : RemoteGateway {

    override suspend fun startLogin(serverUrl: String): LoginStart = withContext(Dispatchers.IO) {
        val normalized = ServerUrlNormalizer.normalize(serverUrl)
        val request = Request.Builder().url("$normalized/index.php/login/v2").post(EMPTY_BODY).build()
        client.newCall(request).execute().use { response ->
            requireSuccess(response)
            val json = response.body?.string().orEmpty()
            LoginStart(
                loginUrl = jsonValue(json, "login"),
                pollEndpoint = jsonValue(json.substringAfter("\"poll\""), "endpoint"),
                pollToken = jsonValue(json.substringAfter("\"poll\""), "token"),
            )
        }
    }

    override suspend fun pollLogin(endpoint: String, token: String): LoginPollResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("token", token).build()
        client.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use { response ->
            if (response.code == 404) return@withContext LoginPollResult.Pending
            requireSuccess(response)
            val json = response.body?.string().orEmpty()
            LoginPollResult.Complete(
                LoginCredential(
                    ServerUrlNormalizer.normalize(jsonValue(json, "server")),
                    jsonValue(json, "loginName"),
                    jsonValue(json, "appPassword"),
                ),
            )
        }
    }

    override suspend fun listFolder(path: String): List<RemoteNode> = withContext(Dispatchers.IO) {
        val request = davRequest(path).method("PROPFIND", PROPFIND_BODY).header("Depth", "1").build()
        executeXml(request).filter { it.path != RemotePath.normalize(path) }
    }

    override suspend fun stat(path: String): RemoteNode = withContext(Dispatchers.IO) {
        val request = davRequest(path).method("PROPFIND", PROPFIND_BODY).header("Depth", "0").build()
        executeXml(request).firstOrNull() ?: throw CloudError.NotFound()
    }

    override suspend fun search(query: String, scope: String): List<RemoteNode> = withContext(Dispatchers.IO) {
        val credential = requireCredential()
        val escaped = xmlEscape("%${query.trim()}%")
        val scopeHref = "/files/${xmlEscape(credential.loginName)}${RemotePath.normalize(scope).let { if (it == "/") "" else it }}"
        val xml = SEARCH_TEMPLATE.replace("{{scope}}", scopeHref).replace("{{query}}", escaped)
        val request = Request.Builder().url(davRoot(credential)).method("SEARCH", xml.toRequestBody(XML)).authenticated(credential).build()
        executeXml(request)
    }

    override suspend fun createFolder(path: String): RemoteNode = mutateAndStat(path, Request.Builder().url(davUrl(path)).method("MKCOL", null).authenticated(requireCredential()).build())

    override suspend fun createEmptyFile(path: String): RemoteNode = upload(path, EMPTY_BODY, ifNoneMatch = true)

    override suspend fun delete(path: String) = executeNoContent(davRequest(path).delete().build())

    override suspend fun move(fromPath: String, toPath: String, overwrite: Boolean): RemoteNode {
        val request = davRequest(fromPath).method("MOVE", null)
            .header("Destination", davUrl(toPath).toString()).header("Overwrite", if (overwrite) "T" else "F").build()
        return mutateAndStat(toPath, request)
    }

    override suspend fun copy(fromPath: String, toPath: String, overwrite: Boolean): RemoteNode {
        val request = davRequest(fromPath).method("COPY", null)
            .header("Destination", davUrl(toPath).toString()).header("Overwrite", if (overwrite) "T" else "F").build()
        return mutateAndStat(toPath, request)
    }

    override suspend fun setFavorite(path: String, favorite: Boolean) {
        val xml = FAVORITE_TEMPLATE.replace("{{favorite}}", if (favorite) "1" else "0")
        executeNoContent(davRequest(path).method("PROPPATCH", xml.toRequestBody(XML)).build())
    }

    override suspend fun download(path: String, destination: File, offset: Long, ifRange: String?, progress: suspend (Long, Long) -> Unit): DownloadResponse = withContext(Dispatchers.IO) {
        val builder = davRequest(path).get()
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        ifRange?.let { builder.header("If-Range", it) }
        client.newCall(builder.build()).execute().use { response ->
            requireSuccess(response)
            val append = offset > 0 && response.code == 206
            val start = if (append) offset else 0L
            if (!append && destination.exists()) destination.delete()
            val body = response.body ?: throw CloudError.Network("The server returned an empty download")
            destination.parentFile?.mkdirs()
            destination.sink(append = append).buffer().use { sink ->
                val source = body.source()
                val buffer = okio.Buffer()
                var copied = start
                val total = start + body.contentLength().coerceAtLeast(0)
                while (true) {
                    val read = source.read(buffer, 128 * 1024)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    copied += read
                    progress(copied, total)
                }
            }
            DownloadResponse(response.header("ETag"), destination.length(), start + body.contentLength().coerceAtLeast(0))
        }
    }

    override suspend fun downloadPreview(path: String, width: Int, height: Int, destination: File): File = withContext(Dispatchers.IO) {
        val credential = requireCredential()
        val url = credential.serverUrl.toHttpUrl().newBuilder()
            .addPathSegments("index.php/core/preview.png")
            .addQueryParameter("file", RemotePath.normalize(path))
            .addQueryParameter("x", width.coerceIn(32, 2048).toString())
            .addQueryParameter("y", height.coerceIn(32, 2048).toString())
            .addQueryParameter("a", "true")
            .build()
        client.newCall(Request.Builder().url(url).authenticated(credential).build()).execute().use { response ->
            requireSuccess(response)
            destination.parentFile?.mkdirs()
            val body = response.body ?: throw CloudError.Network("The server returned an empty preview")
            destination.sink().buffer().use { body.source().readAll(it) }
        }
        destination
    }

    override suspend fun upload(path: String, body: RequestBody, ifMatch: String?, ifNoneMatch: Boolean, progress: suspend (Long, Long) -> Unit): RemoteNode = withContext(Dispatchers.IO) {
        val progressBody = ProgressRequestBody(body, progress)
        val builder = davRequest(path).put(progressBody)
        ifMatch?.let { builder.header("If-Match", it) }
        if (ifNoneMatch) builder.header("If-None-Match", "*")
        client.newCall(builder.build()).execute().use(::requireSuccess)
        stat(path)
    }

    override suspend fun uploadChunk(uploadId: String, destinationPath: String, chunkNumber: Int, totalBytes: Long, body: RequestBody) = withContext(Dispatchers.IO) {
        val credential = requireCredential()
        if (chunkNumber == 1) {
            val start = Request.Builder().url(uploadFolderUrl(credential, uploadId)).method("MKCOL", null)
                .header("Destination", davUrl(destinationPath).toString()).authenticated(credential).build()
            client.newCall(start).execute().use { if (!it.isSuccessful && it.code != 405) requireSuccess(it) }
        }
        val chunkUrl = uploadFolderUrl(credential, uploadId).newBuilder().addPathSegment(chunkNumber.toString().padStart(5, '0')).build()
        val request = Request.Builder().url(chunkUrl).put(body).header("Destination", davUrl(destinationPath).toString())
            .header("OC-Total-Length", totalBytes.toString()).authenticated(credential).build()
        client.newCall(request).execute().use(::requireSuccess)
    }

    override suspend fun assembleChunks(uploadId: String, destinationPath: String, totalBytes: Long, ifMatch: String?, ifNoneMatch: Boolean): RemoteNode = withContext(Dispatchers.IO) {
        val credential = requireCredential()
        val source = uploadFolderUrl(credential, uploadId).newBuilder().addPathSegment(".file").build()
        val builder = Request.Builder().url(source).method("MOVE", null).header("Destination", davUrl(destinationPath).toString())
            .header("OC-Total-Length", totalBytes.toString()).authenticated(credential)
        ifMatch?.let { builder.header("If-Match", it) }
        if (ifNoneMatch) builder.header("If-None-Match", "*")
        val request = builder.build()
        client.newCall(request).execute().use(::requireSuccess)
        stat(destinationPath)
    }

    override suspend fun abortChunks(uploadId: String) {
        val credential = requireCredential()
        executeNoContent(Request.Builder().url(uploadFolderUrl(credential, uploadId)).delete().authenticated(credential).build())
    }

    override suspend fun quota(): StorageQuota = withContext(Dispatchers.IO) {
        val request = davRequest("/").method("PROPFIND", QUOTA_BODY).header("Depth", "0").build()
        client.newCall(request).execute().use { response ->
            requireSuccess(response)
            val stream = response.body?.byteStream() ?: throw CloudError.Network("The server returned no quota information")
            DavXmlParser.parseQuota(stream)
        }
    }

    override suspend fun revokeCredential() {
        val credential = requireCredential()
        val request = Request.Builder().url("${credential.serverUrl}/ocs/v2.php/core/apppassword").delete()
            .header("OCS-APIRequest", "true").authenticated(credential).build()
        withContext(Dispatchers.IO) { client.newCall(request).execute().close() }
    }

    private suspend fun mutateAndStat(path: String, request: Request): RemoteNode = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use(::requireSuccess)
        stat(path)
    }

    private suspend fun executeNoContent(request: Request) = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use(::requireSuccess)
    }

    private fun executeXml(request: Request): List<RemoteNode> {
        client.newCall(request).execute().use { response ->
            requireSuccess(response)
            val stream = response.body?.byteStream() ?: return emptyList()
            return DavXmlParser.parse(stream, requireCredential().loginName)
        }
    }

    private fun davRequest(path: String): Request.Builder {
        val credential = requireCredential()
        return Request.Builder().url(davUrl(path)).authenticated(credential)
    }

    private fun davUrl(path: String): HttpUrl {
        val credential = requireCredential()
        val builder = credential.serverUrl.toHttpUrl().newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(credential.loginName)
        RemotePath.normalize(path).split('/').filter(String::isNotBlank).forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun davRoot(credential: LoginCredential) = credential.serverUrl.toHttpUrl().newBuilder().addPathSegments("remote.php/dav/").build()
    private fun uploadFolderUrl(credential: LoginCredential, uploadId: String) = credential.serverUrl.toHttpUrl().newBuilder()
        .addPathSegments("remote.php/dav/uploads").addPathSegment(credential.loginName).addPathSegment(uploadId).build()

    private fun requireCredential() = credentialStore.read() ?: throw CloudError.Authentication()

    private fun Request.Builder.authenticated(credential: LoginCredential) = header("Authorization", Credentials.basic(credential.loginName, credential.appPassword))

    private fun requireSuccess(response: Response) {
        if (response.isSuccessful || response.code == 207) return
        val message = response.body?.string()?.take(500).orEmpty()
        throw when (response.code) {
            401 -> CloudError.Authentication("Your Nextcloud login has expired. Sign in again to continue.")
            403 -> CloudError.PermissionDenied("You do not have permission to perform this operation.")
            404 -> CloudError.NotFound()
            409, 412 -> CloudError.Conflict()
            413, 507 -> CloudError.QuotaExceeded("Your Nextcloud storage quota is full.")
            else -> CloudError.Network("Nextcloud returned HTTP ${response.code}${if (message.isBlank()) "" else ": $message"}")
        }
    }

    private fun jsonValue(json: String, key: String): String {
        val regex = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
        val encoded = regex.find(json)?.groupValues?.get(1) ?: throw CloudError.Network("The login response did not contain $key")
        return encoded.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private companion object {
        val XML = "application/xml; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        val PROPFIND_BODY = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop><oc:fileid/><d:displayname/><d:resourcetype/><d:getcontenttype/><d:getcontentlength/><oc:size/><d:getlastmodified/><d:getetag/><oc:permissions/><oc:favorite/><nc:has-preview/><oc:quota-used-bytes/><oc:quota-available-bytes/></d:prop></d:propfind>""".toRequestBody(XML)
        val QUOTA_BODY = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:quota-used-bytes/><oc:quota-available-bytes/></d:prop></d:propfind>""".toRequestBody(XML)
        const val FAVORITE_TEMPLATE = """<?xml version="1.0"?><d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:set><d:prop><oc:favorite>{{favorite}}</oc:favorite></d:prop></d:set></d:propertyupdate>"""
        const val SEARCH_TEMPLATE = """<?xml version="1.0"?><d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:basicsearch><d:select><d:prop><oc:fileid/><d:displayname/><d:resourcetype/><d:getcontenttype/><d:getcontentlength/><oc:size/><d:getlastmodified/><d:getetag/><oc:permissions/><oc:favorite/><nc:has-preview/></d:prop></d:select><d:from><d:scope><d:href>{{scope}}</d:href><d:depth>infinity</d:depth></d:scope></d:from><d:where><d:like><d:prop><d:displayname/></d:prop><d:literal>{{query}}</d:literal></d:like></d:where><d:orderby><d:order><d:prop><d:displayname/></d:prop><d:ascending/></d:order></d:orderby></d:basicsearch></d:searchrequest>"""
        fun xmlEscape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    }
}

private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val progress: suspend (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun writeTo(sink: okio.BufferedSink) {
        val counting = object : okio.ForwardingSink(sink) {
            var written = 0L
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                kotlinx.coroutines.runBlocking { progress(written, contentLength()) }
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

private object DavXmlParser {
    fun parseQuota(input: java.io.InputStream): StorageQuota {
        val parser = Xml.newPullParser().apply { setInput(input, Charsets.UTF_8.name()) }
        var used: Long? = null
        var available: Long? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name?.lowercase()) {
                "quota-used-bytes" -> used = runCatching { parser.nextText().toLong() }.getOrNull()
                "quota-available-bytes" -> available = runCatching { parser.nextText().toLong() }.getOrNull()
            }
            event = parser.next()
        }
        val consumed = used?.coerceAtLeast(0) ?: 0L
        val remaining = available?.takeIf { it >= 0 }
        return StorageQuota(consumed, remaining?.let { consumed + it })
    }

    fun parse(input: java.io.InputStream, userId: String): List<RemoteNode> {
        val parser = Xml.newPullParser().apply { setInput(input, Charsets.UTF_8.name()) }
        val results = mutableListOf<RemoteNode>()
        var response: MutableMap<String, String>? = null
        var inCollection = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.lowercase()
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "response" -> response = mutableMapOf()
                    "collection" -> inCollection = true
                    "href", "displayname", "getcontenttype", "getcontentlength", "size", "getlastmodified", "getetag", "fileid", "permissions", "favorite", "has-preview" -> {
                        response?.set(name, runCatching { parser.nextText() }.getOrDefault(""))
                    }
                }
                XmlPullParser.END_TAG -> if (name == "response") {
                    response?.toNode(userId, inCollection)?.let(results::add)
                    response = null
                    inCollection = false
                }
            }
            event = parser.next()
        }
        return results
    }

    private fun Map<String, String>.toNode(userId: String, directory: Boolean): RemoteNode? {
        val href = this["href"] ?: return null
        val marker = "/remote.php/dav/files/$userId"
        val encodedPath = href.substringAfter(marker, "")
        val path = RemotePath.normalize(URLDecoder.decode(encodedPath, Charsets.UTF_8.name()))
        val fileId = this["fileid"].orEmpty().ifBlank { "path:${path.hashCode()}" }
        val name = this["displayname"].orEmpty().ifBlank { path.substringAfterLast('/').ifBlank { "Cloud Drive" } }
        val modified = runCatching { ZonedDateTime.parse(this["getlastmodified"], DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrDefault(Instant.EPOCH)
        return RemoteNode(
            documentId = "account:primary:file:$fileId", fileId = fileId, path = path,
            parentPath = RemotePath.parent(path), name = name,
            mimeType = if (directory) "vnd.android.document/directory" else this["getcontenttype"].orEmpty().substringBefore(';').ifBlank { "application/octet-stream" },
            isDirectory = directory, size = (this["size"] ?: this["getcontentlength"]).orEmpty().toLongOrNull() ?: 0,
            modifiedAt = modified, etag = this["getetag"]?.trim('"'), permissions = this["permissions"].orEmpty(),
            isFavorite = this["favorite"] == "1", hasPreview = this["has-preview"] == "true" || this["has-preview"] == "1",
            lastRefreshedAt = Instant.now(),
        )
    }
}
