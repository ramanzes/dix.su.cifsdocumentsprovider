package com.wa2c.android.cifsdocumentsprovider.domain.repository

import android.net.Uri
import android.provider.DocumentsContract
import com.wa2c.android.cifsdocumentsprovider.common.exception.SendException
import com.wa2c.android.cifsdocumentsprovider.common.utils.generateUUID
import com.wa2c.android.cifsdocumentsprovider.common.utils.logD
import com.wa2c.android.cifsdocumentsprovider.common.utils.logE
import com.wa2c.android.cifsdocumentsprovider.data.storage.manager.DocumentFileManager
import com.wa2c.android.cifsdocumentsprovider.data.storage.manager.SyncTransfer
import com.wa2c.android.cifsdocumentsprovider.domain.IoDispatcher
import com.wa2c.android.cifsdocumentsprovider.domain.mapper.DomainMapper.toSendData
import com.wa2c.android.cifsdocumentsprovider.domain.model.SendData
import com.wa2c.android.cifsdocumentsprovider.domain.model.SendDirection
import com.wa2c.android.cifsdocumentsprovider.domain.model.SendDataState
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncScanProgress
import com.wa2c.android.cifsdocumentsprovider.domain.model.TwoWaySyncResult
import com.wa2c.android.cifsdocumentsprovider.domain.model.getCurrentReady
import com.wa2c.android.cifsdocumentsprovider.domain.model.toSendFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Send Repository
 */
@Singleton
class SendRepository @Inject internal constructor(
    private val documentFileManager: DocumentFileManager,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    private val _sendDataList = MutableStateFlow<List<SendData>>(emptyList())
    val sendDataList = _sendDataList.asStateFlow()

    private val _syncScanProgress = MutableStateFlow<SyncScanProgress?>(null)
    /** Check phase of [sendFolderSkippingExisting]; null when no check is running. */
    val syncScanProgress = _syncScanProgress.asStateFlow()

    suspend fun sendUri(sourceUriList: List<Uri>, targetUri: Uri) {
        val inputList = withContext(dispatcher) {
            sourceUriList.mapNotNull { uri ->
                val (storageFile, mimeType) = documentFileManager.getStorageFileAndMimeType(uri) ?: return@mapNotNull null
                val exists = documentFileManager.existsFile(targetUri, storageFile.name)
                storageFile.toSendData(mimeType, targetUri, exists)
            }
        }
        _sendDataList.emit(sendDataList.value + inputList)
    }

    /**
     * Like [sendUri], but for unattended backup-style sends: files that already exist at the
     * target are skipped outright instead of being queued in [SendDataState.CONFIRM] awaiting a
     * user decision - there's no one watching to confirm an overwrite.
     */
    suspend fun sendUriSkippingExisting(sourceUriList: List<Uri>, targetUri: Uri) {
        val inputList = withContext(dispatcher) {
            sourceUriList.mapNotNull { uri ->
                val (storageFile, mimeType) = documentFileManager.getStorageFileAndMimeType(uri) ?: return@mapNotNull null
                if (documentFileManager.existsFile(targetUri, storageFile.name)) return@mapNotNull null
                storageFile.toSendData(mimeType, targetUri, exists = false)
            }
        }
        _sendDataList.emit(sendDataList.value + inputList)
    }

    /**
     * Like [sendUriSkippingExisting], but walks [sourceFolderUri] recursively, mirroring the
     * subdirectory tree under [targetUri] and skipping files already there with the same size.
     * Reports its progress in [syncScanProgress].
     * @return Number of files queued
     */
    suspend fun sendFolderSkippingExisting(sourceFolderUri: Uri, targetUri: Uri): Int {
        val inputList = withContext(dispatcher) {
            try {
                var progress = SyncScanProgress(stage = SyncScanProgress.Stage.COLLECT)
                fun report(update: SyncScanProgress) {
                    progress = update.copy(updateTime = System.currentTimeMillis())
                    _syncScanProgress.value = progress
                }
                report(progress)

                val pairs = documentFileManager.collectFilesRecursively(sourceFolderUri, targetUri) { path, filesFound ->
                    report(progress.copy(folders = progress.folders + 1, files = filesFound, currentFolder = path))
                }
                report(progress.copy(stage = SyncScanProgress.Stage.CHECK, files = 0, total = pairs.size, currentFolder = ""))

                // One listing per target directory instead of one existsFile() (itself a full listing) per file.
                val existingByDir = mutableMapOf<Uri, Map<String, Long>>()
                pairs.mapNotNull { (sourceUri, targetDirUri) ->
                    val existing = existingByDir.getOrPut(targetDirUri) {
                        report(progress.copy(currentFolder = targetDirUri.folderPath()))
                        documentFileManager.listFileSizes(targetDirUri)
                    }
                    val sendData = documentFileManager.getStorageFileAndMimeType(sourceUri)?.let { (storageFile, mimeType) ->
                        // Skip only a complete copy - a size mismatch means an interrupted upload (or a changed file), send it again.
                        if (existing[storageFile.name] == storageFile.size) null
                        else storageFile.toSendData(mimeType, targetDirUri, exists = false)
                            .copy(targetMayExist = existing.containsKey(storageFile.name))
                    }
                    report(progress.copy(files = progress.files + 1, newFiles = progress.newFiles + if (sendData != null) 1 else 0))
                    sendData
                }
            } finally {
                _syncScanProgress.value = null
            }
        }
        _sendDataList.emit(sendDataList.value + inputList)
        return inputList.size
    }

    /**
     * Two-way counterpart of [sendFolderSkippingExisting]: also queues downloads of files that are
     * only on the server (or newer there) into [localFolderUri]. Nothing is deleted on either side.
     * Without write access to [localFolderUri] only the uploads are queued.
     * Reports its progress in [syncScanProgress].
     */
    suspend fun syncFolderTwoWay(localFolderUri: Uri, remoteUri: Uri): TwoWaySyncResult {
        val (diff, canWrite) = withContext(dispatcher) {
            try {
                var progress = SyncScanProgress(stage = SyncScanProgress.Stage.COMPARE)
                _syncScanProgress.value = progress
                val canWrite = documentFileManager.canWriteTree(localFolderUri)
                documentFileManager.diffTreesRecursively(localFolderUri, remoteUri) { path, diff ->
                    progress = progress.copy(
                        folders = progress.folders + 1,
                        files = diff.files,
                        newFiles = diff.upload.size,
                        downloadFiles = diff.download.size,
                        currentFolder = path,
                        updateTime = System.currentTimeMillis(),
                    )
                    _syncScanProgress.value = progress
                } to canWrite
            } finally {
                _syncScanProgress.value = null
            }
        }
        val uploads = diff.upload.map { it.toSendData(SendDirection.UPLOAD) }
        val downloads = if (canWrite) diff.download.map { it.toSendData(SendDirection.DOWNLOAD) } else emptyList()
        _sendDataList.emit(sendDataList.value + uploads + downloads)
        return TwoWaySyncResult(
            uploads = uploads.size,
            downloads = downloads.size,
            conflicts = diff.conflicts,
            failedFolders = diff.failedFolders,
            downloadsBlocked = if (canWrite) 0 else diff.download.size,
        )
    }

    private fun SyncTransfer.toSendData(direction: SendDirection) = SendData(
        id = generateUUID(),
        name = name,
        size = size,
        mimeType = mimeType,
        sourceFileUri = sourceUri,
        targetFileUri = targetDirUri,
        targetMayExist = targetExists,
        direction = direction,
    )

    /** Folder path of a target document URI, relative to the connection root. */
    private fun Uri.folderPath(): String {
        return runCatching { DocumentsContract.getDocumentId(this).substringAfter(':') }.getOrDefault("")
    }

    suspend fun start(toReadyConfirm: Boolean) {
        val list = sendDataList.value.map { data ->
            if (data.state == SendDataState.CONFIRM) {
                data.copy(state = if (toReadyConfirm) SendDataState.READY else SendDataState.OVERWRITE)
            } else {
                data
            }
        }
        _sendDataList.emit(list)
    }

    suspend fun cancel(id: String) {
        val list = sendDataList.value.map { data ->
            if (data.id == id) data.copy(state = SendDataState.CANCEL, progressSize = 0L) else data
        }
        _sendDataList.emit(list)
    }

    suspend fun retry(id: String) {
        val list = sendDataList.value.map { data ->
            if (data.id == id) data.copy(state = SendDataState.READY, failure = null) else data
        }
        _sendDataList.emit(list)
    }

    suspend fun remove(id: String) {
        val list = sendDataList.value.filter { (id != it.id) }
        _sendDataList.emit(list) // reload
    }


    /**
     * Cancel all
     */
    suspend fun cancelAll() {
        logD("cancelAll")
        val list = sendDataList.value.map {
            if (it.state.isCancelable) it.copy(state = SendDataState.CANCEL, progressSize = 0L) else it
        }
        _sendDataList.emit(list)
    }

    suspend fun clear() {
        cancelAll()
        _sendDataList.emit(emptyList())
    }

    /**
     * Send a data.
     */
    suspend fun sendReadyData(): Boolean {
        return withContext(dispatcher) {
            val sendData = sendDataList.value.getCurrentReady() ?: return@withContext false
            var previousTime = 0L
            var previousSendData = sendData

            logD("sendJob Start: uri=${sendData.sourceFileUri}")

            send(sendData) { currentSendData ->
                if (!sendDataList.value.contains(previousSendData)) {
                    return@send false
                }

                val currentTime = System.currentTimeMillis()
                val change = (currentTime >= previousTime + NOTIFY_CYCLE || currentSendData.progress >= 100 || previousSendData.state != currentSendData.state)
                if (!change) return@send true
                previousTime = currentTime
                previousSendData = currentSendData
                val list = sendDataList.value.map {
                    if (it.id == currentSendData.id) {
                        currentSendData
                    } else {
                        it
                    }
                }
                _sendDataList.emit(list)
                true
            }
            true
        }
    }

    /**
     * Send data
     */
    private suspend fun send(sendData: SendData, callback: suspend (sendParam: SendData) -> Boolean) {
        withContext(dispatcher) {
            // A download replacing a local file goes to a temporary file first, so that a failed
            // transfer (whose target is deleted below) cannot take the existing file with it.
            val viaTemp = sendData.direction == SendDirection.DOWNLOAD && sendData.targetMayExist
            // Get permitted output file URI
            val targetFileUri = try {
                if (viaTemp) {
                    documentFileManager.createTempFile(sendData.targetFileUri, sendData.name)
                } else {
                    documentFileManager.getPermittedFileUri(sendData.targetFileUri, sendData.name, sendData.mimeType, sendData.targetMayExist)
                } ?: throw SendException.CreateTarget()
            } catch (e: Exception) {
                logE(e)
                callback(sendData.copy(state = SendDataState.FAILURE, failure = e.toSendFailure()))
                return@withContext
            }

            var currentSendData = sendData.copy(
                state = SendDataState.PROGRESS,
                startTime = System.currentTimeMillis(),
            )
            callback(currentSendData).let { if (!it) return@withContext }

            try {
                val isSuccess = documentFileManager.sendFile(currentSendData.sourceFileUri, targetFileUri) { progressSize ->
                    if (!currentSendData.state.inProgress) {
                        return@sendFile false
                    }
                    currentSendData = currentSendData.copy(progressSize = progressSize)
                    callback(currentSendData).let { if (!it) return@sendFile false }
                    return@sendFile true
                }
                if (isSuccess) awaitTargetSize(targetFileUri, currentSendData.progressSize)
                if (isSuccess && viaTemp) {
                    try {
                        documentFileManager.replaceWithTemp(sendData.targetFileUri, sendData.name, targetFileUri)
                    } catch (e: Exception) {
                        throw SendException.WriteTarget(e)
                    }
                }

                currentSendData = currentSendData.copy(
                    state = when {
                        isSuccess -> SendDataState.SUCCESS
                        currentSendData.state == SendDataState.PROGRESS -> SendDataState.FAILURE
                        else -> currentSendData.state
                    }
                )
            } catch (e: Exception) {
                logE(e)
                currentSendData = currentSendData.copy(state = SendDataState.FAILURE, failure = e.toSendFailure())
            } finally {
                // Delete if incomplete
                if (currentSendData.state.isIncomplete) {
                    documentFileManager.deleteFile(targetFileUri)
                }
                callback(currentSendData)
            }
        }
    }


    /**
     * Waits until the provider reports [expectedSize] for [targetFileUri]: closing the written file
     * finishes after the stream close returns (the provider releases it asynchronously), and only
     * then is the whole file on the server. Unknown sizes are taken as complete.
     */
    private suspend fun awaitTargetSize(targetFileUri: Uri, expectedSize: Long) {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT
        var size: Long?
        while (true) {
            size = documentFileManager.getFileSize(targetFileUri)
            logD("awaitTargetSize: $size / $expectedSize")
            if (size == null || size == expectedSize || System.currentTimeMillis() >= deadline) break
            delay(VERIFY_INTERVAL)
        }
        if (size != null && size != expectedSize) {
            throw SendException.WriteTarget(IOException("Only $size of $expectedSize bytes reached the server."))
        }
    }

    companion object {
        private const val NOTIFY_CYCLE = 500
        private const val VERIFY_INTERVAL = 500L
        private const val VERIFY_TIMEOUT = 60_000L
    }

}
