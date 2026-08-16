package com.wa2c.android.cifsdocumentsprovider.presentation.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wa2c.android.cifsdocumentsprovider.common.utils.logD
import com.wa2c.android.cifsdocumentsprovider.presentation.provideEditRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

/**
 * Dixsu Proxy Worker — holds a foreground-service presence for as long as a standalone local
 * SFTP proxy (started via [com.wa2c.android.cifsdocumentsprovider.domain.repository.EditRepository.startDixsuProxy])
 * should keep running in the background, so third-party apps (backup/sync apps) can rely on it
 * being reachable even while this app's UI isn't open. Doesn't do the forwarding itself — that
 * already happens in [com.wa2c.android.cifsdocumentsprovider.data.storage.apache.DixsuProxyManager];
 * this worker just stops it once cancelled.
 */
class DixsuProxyWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        logD("DixsuProxyWorker begin")
        val connectionId = inputData.getString(KEY_CONNECTION_ID)
        val port = inputData.getInt(KEY_PORT, -1).takeIf { it > 0 }
        if (connectionId == null || port == null) return Result.failure()

        try {
            setForeground(DixsuProxyNotification(context).getNotificationInfo(port))
            awaitCancellation()
        } catch (e: CancellationException) {
            // ignored
        } finally {
            provideEditRepository(context).stopDixsuProxy(connectionId)
        }

        logD("DixsuProxyWorker end")
        return Result.success()
    }

    companion object {
        const val KEY_CONNECTION_ID = "connectionId"
        const val KEY_PORT = "port"

        fun workerName(connectionId: String) = "DixsuProxyWorker:$connectionId"
    }
}
