package dev.clouddrive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDateTime

class PathRulesTest {
    @Test fun `normalizes trusted https URL`() {
        assertEquals("https://cloud.example.com/nextcloud", ServerUrlNormalizer.normalize(" HTTPS://Cloud.Example.Com/nextcloud/ "))
    }

    @Test fun `rejects http and embedded credentials`() {
        assertThrows(CloudError.InvalidServer::class.java) { ServerUrlNormalizer.normalize("http://cloud.example.com") }
        assertThrows(CloudError.InvalidServer::class.java) { ServerUrlNormalizer.normalize("https://user:pass@cloud.example.com") }
    }

    @Test fun `builds safe remote paths`() {
        assertEquals("/School/Essay.docx", RemotePath.child("/School", "Essay.docx"))
        assertThrows(IllegalArgumentException::class.java) { RemotePath.child("/School", "../secret") }
    }

    @Test fun `creates extension preserving conflict names`() {
        assertEquals(
            "Essay (conflict 2026-09-18 143022).docx",
            ConflictNames.candidate("Essay.docx", LocalDateTime.of(2026, 9, 18, 14, 30, 22)),
        )
    }
}
