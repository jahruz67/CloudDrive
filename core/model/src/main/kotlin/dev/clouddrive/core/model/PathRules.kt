package dev.clouddrive.core.model

import java.net.URI
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ServerUrlNormalizer {
    fun normalize(input: String): String {
        val raw = input.trim().trimEnd('/')
        val uri = runCatching { URI(raw) }.getOrElse {
            throw CloudError.InvalidServer("Enter a valid HTTPS Nextcloud address")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw CloudError.InvalidServer("CloudDrive only connects over HTTPS")
        }
        if (uri.userInfo != null) throw CloudError.InvalidServer("Credentials cannot be embedded in the address")
        if (uri.host.isNullOrBlank()) throw CloudError.InvalidServer("The server address needs a host name")
        if (uri.query != null || uri.fragment != null) {
            throw CloudError.InvalidServer("Remove query parameters and fragments from the server address")
        }
        return URI("https", null, uri.host.lowercase(), uri.port, uri.path.trimEnd('/'), null, null).toASCIIString()
    }
}

object RemotePath {
    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "Parent path segments are not allowed" }
        return "/" + parts.joinToString("/")
    }

    fun child(parent: String, name: String): String {
        val safeName = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
        require(safeName.isNotEmpty()) { "Name cannot be empty" }
        require('/' !in safeName && '\\' !in safeName && safeName != "." && safeName != "..") {
            "Name contains unsupported path characters"
        }
        return if (normalize(parent) == "/") "/$safeName" else "${normalize(parent)}/$safeName"
    }

    fun parent(path: String): String = normalize(path).substringBeforeLast('/', "").ifBlank { "/" }
}

object ConflictNames {
    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss")

    fun candidate(original: String, at: LocalDateTime, attempt: Int = 0): String {
        val dot = original.lastIndexOf('.').takeIf { it > 0 }
        val base = dot?.let { original.substring(0, it) } ?: original
        val extension = dot?.let { original.substring(it) }.orEmpty()
        val suffix = if (attempt == 0) "" else " $attempt"
        return "$base (conflict ${timestamp.format(at)}$suffix)$extension"
    }
}
