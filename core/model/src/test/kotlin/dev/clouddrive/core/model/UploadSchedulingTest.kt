package dev.clouddrive.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class UploadSchedulingTest {
    private fun upload(id: String, path: String, time: Long, state: TransferState = TransferState.QUEUED) = Transfer(
        id = id, direction = TransferDirection.UPLOAD, displayName = id, localPath = null,
        contentUri = null, remotePath = path, createdAt = Instant.ofEpochSecond(time), state = state,
    )

    @Test fun `rapid saves upload in order while other documents can run concurrently`() {
        val first = upload("first", "/report.docx", 1)
        val second = upload("second", "/report.docx", 2)
        val other = upload("other", "/budget.xlsx", 3)
        assertEquals(listOf(first, other), UploadScheduling.ready(listOf(other, second, first)))
    }

    @Test fun `paused and failed edits block newer saves from overtaking them`() {
        for (state in listOf(TransferState.PAUSED, TransferState.FAILED, TransferState.RUNNING)) {
            val blocked = upload("older", "/report.docx", 1, state)
            val newer = upload("newer", "/report.docx", 2)
            assertEquals(emptyList<Transfer>(), UploadScheduling.ready(listOf(newer, blocked)))
        }
    }

    @Test fun `completed and canceled edits release the next save`() {
        for (state in listOf(TransferState.COMPLETED, TransferState.CANCELED)) {
            val older = upload("older", "/report.docx", 1, state)
            val newer = upload("newer", "/report.docx", 2)
            assertEquals(listOf(newer), UploadScheduling.ready(listOf(older, newer)))
        }
    }
}
