package com.wa2c.android.cifsdocumentsprovider.presentation.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wa2c.android.cifsdocumentsprovider.common.utils.logD
import com.wa2c.android.cifsdocumentsprovider.presentation.provideEditRepository

/**
 * Enqueued once from [com.wa2c.android.cifsdocumentsprovider.App.onCreate] on every process
 * start. Restarts the standalone dixsu proxy for every connection with "keep proxy running"
 * enabled, so it comes back up after the process was killed (background app limits, OEM battery
 * managers, etc.) without the user needing to revisit each connection's edit screen.
 *
 * Only covers process starts, not a full device reboot with the app never opened - that would
 * need a BOOT_COMPLETED receiver, which this app deliberately doesn't declare (manifest strips
 * RECEIVE_BOOT_COMPLETED). In practice the process restarts often anyway: opening the app, or any
 * other app querying this app's DocumentsProvider, both spin the process (and this worker) back up.
 */
class DixsuAutoStartWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        logD("DixsuAutoStartWorker begin")
        val editRepository = provideEditRepository(context)
        val workManager = WorkManager.getInstance(context)

        editRepository.getAutoStartDixsuConnections().forEach { connection ->
            val port = editRepository.startDixsuProxy(connection) ?: return@forEach
            val request = OneTimeWorkRequest.Builder(DixsuProxyWorker::class.java)
                .setInputData(
                    workDataOf(
                        DixsuProxyWorker.KEY_CONNECTION_ID to connection.id,
                        DixsuProxyWorker.KEY_PORT to port,
                    )
                )
                .build()
            // KEEP, not REPLACE: this is "make sure it's running", not "force restart" - avoid
            // disrupting an already-running proxy (e.g. process wasn't actually fully killed).
            workManager.enqueueUniqueWork(DixsuProxyWorker.workerName(connection.id), ExistingWorkPolicy.KEEP, request)
        }

        logD("DixsuAutoStartWorker end")
        return Result.success()
    }

    companion object {
        const val WORKER_NAME = "DixsuAutoStartWorker"
    }
}
