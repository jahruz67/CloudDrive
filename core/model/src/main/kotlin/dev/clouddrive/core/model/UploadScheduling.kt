package dev.clouddrive.core.model

object UploadScheduling {
    /** Preserve save order for a path, including edits waiting for the user to retry or resume. */
    fun ready(pending: List<Transfer>): List<Transfer> {
        val seenPaths = mutableSetOf<String>()
        return pending.filter { it.state in setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED, TransferState.FAILED) }
            .sortedBy { it.createdAt }.filter { transfer ->
            val firstForPath = transfer.direction != TransferDirection.UPLOAD || seenPaths.add(transfer.remotePath)
            transfer.state == TransferState.QUEUED && firstForPath
        }
    }
}
