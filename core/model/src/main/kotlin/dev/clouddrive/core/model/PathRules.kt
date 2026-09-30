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

object DavPathDecoder {
    /**
     * Decodes a percent-encoded DAV path hierarchy.
     * Splits on literal '/' first so hierarchy is defined by raw path separators,
     * decodes path segments without turning literal '+' into spaces,
     * and preserves escaped path separators (%2F, %5C) to avoid injecting hierarchy.
     */
    fun decodeDavPath(encodedPath: String): String {
        val rawSegments = encodedPath.split('/')
        val decodedSegments = mutableListOf<String>()
        for (rawSegment in rawSegments) {
            if (rawSegment.isEmpty() || rawSegment == ".") continue
            decodedSegments.add(decodeSegment(rawSegment))
        }
        return "/" + decodedSegments.joinToString("/")
    }

    fun decodeSegment(segment: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%' && i + 2 < segment.length) {
                val hex = segment.substring(i + 1, i + 3)
                val byteVal = hex.toIntOrNull(16)
                if (byteVal != null) {
                    // Do not decode escaped path separators (%2F = '/', %5C = '\') into hierarchy
                    if (byteVal == 0x2F || byteVal == 0x5C) {
                        bytes.write('%'.code)
                        bytes.write(hex[0].code)
                        bytes.write(hex[1].code)
                    } else {
                        bytes.write(byteVal)
                    }
                    i += 3
                    continue
                }
            }
            // Literal '+' remains '+', do NOT turn into space
            val charBytes = c.toString().toByteArray(Charsets.UTF_8)
            bytes.write(charBytes, 0, charBytes.size)
            i++
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}

object EtagUtils {
    /**
     * Normalizes an ETag string to a canonical HTTP entity-tag format:
     * - Strong: "\"opaque\""
     * - Weak: "W/\"opaque\""
     * Preserves weak ETag indicator if present.
     * Trims enclosing whitespace and ensures proper quotation.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val isWeak = trimmed.startsWith("W/", ignoreCase = true)
        val opaquePart = (if (isWeak) trimmed.substring(2) else trimmed).trim().trim('"')
        if (opaquePart.isEmpty()) return null
        return if (isWeak) "W/\"$opaquePart\"" else "\"$opaquePart\""
    }

    /**
     * Formats an ETag for use in HTTP conditional headers such as If-Match and If-Range.
     * Returns a valid quoted entity tag string.
     */
    fun formatHeader(etag: String?): String? = normalize(etag)

    /**
     * Consistent internal comparison of ETags.
     * Returns true if both ETags represent the same entity tag,
     * accounting for potential quotation differences in legacy database rows.
     */
    fun matches(etag1: String?, etag2: String?): Boolean {
        if (etag1 == null || etag2 == null) return false
        val n1 = normalize(etag1) ?: return false
        val n2 = normalize(etag2) ?: return false
        return n1 == n2
    }
}

object SqlUtils {
    /**
     * Escapes SQLite LIKE wildcards ('%', '_', and the escape character '\')
     * so the text can be matched literally in a query with ESCAPE '\'.
     */
    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    fun subtreePrefix(path: String): String {
        val normalized = RemotePath.normalize(path)
        return if (normalized == "/") "/%" else "${escapeLike(normalized)}/%"
    }
}

