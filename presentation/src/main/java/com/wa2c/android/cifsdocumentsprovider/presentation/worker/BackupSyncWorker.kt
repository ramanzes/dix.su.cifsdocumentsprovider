package com.wa2c.android.cifsdocumentsprovider.presentation.worker

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wa2c.android.cifsdocumentsprovider.common.utils.logD
import com.wa2c.android.cifsdocumentsprovider.common.utils.logE
import com.wa2c.android.cifsdocumentsprovider.common.values.URI_AUTHORITY
import com.wa2c.android.cifsdocumentsprovider.common.values.URI_START
import com.wa2c.android.cifsdocumentsprovider.domain.model.DocumentId
import com.wa2c.android.cifsdocumentsprovider.presentation.provideEditRepository
import com.wa2c.android.cifsdocumentsprovider.presentation.provideSendRepository

/**
 * Backup Sync Worker — runs periodically in the background (per [androidx.work.PeriodicWorkRequest],
 * scheduled from the Edit screen when a connection's "Auto backup" checkbox is on) to pick up any
 * new files in the connection's backup folder, the unattended counterpart to the foreground
 * "Sync now" button. Only queues the work; the actual upload (with its own foreground notification)
 * is done by [SendWorker], same as every other producer of [com.wa2c.android.cifsdocumentsprovider.domain.model.SendData].
 */
class BackupSyncWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        logD("BackupSyncWorker begin")
        val connectionId = inputData.getString(KEY_CONNECTION_ID) ?: return Result.failure()

        val editRepository = provideEditRepository(context)
        val connection = editRepository.getConnection(connectionId)
        val folderUri = connection?.backupLocalFolderUri?.toUri()
        if (connection == null || !connection.autoBackupEnabled || folderUri == null) {
            return Result.success()
        }
        if (connection.optionReadOnly) {
            logD("BackupSyncWorker skipped: connection is read-only")
            return Result.success()
        }

        val sendRepository = provideSendRepository(context)
        runCatching {
            val documentId = DocumentId.fromConnection(connection.id) ?: return@runCatching
            val targetUri = "content$URI_START$URI_AUTHORITY/tree/${Uri.encode(documentId.idText)}".toUri()
            if (connection.twoWaySyncEnabled) {
                val result = sendRepository.syncFolderTwoWay(folderUri, targetUri)
                logD("BackupSyncWorker two-way: $result")
            } else {
                sendRepository.sendFolderSkippingExisting(folderUri, targetUri)
            }
        }.onFailure {
            logE(it)
        }

        if (sendRepository.sendDataList.value.any { it.state.isReady }) {
            val request = OneTimeWorkRequest.Builder(SendWorker::class.java).build()
            WorkManager.getInstance(context).enqueueUniqueWork(SendWorker.WORKER_NAME, ExistingWorkPolicy.KEEP, request)
        }

        logD("BackupSyncWorker end")
        return Result.success()
    }

    companion object {
        const val KEY_CONNECTION_ID = "connectionId"

        fun workerName(connectionId: String) = "BackupSyncWorker:$connectionId"
    }
}
