package com.wa2c.android.cifsdocumentsprovider.domain.repository

import android.net.Uri
import android.provider.DocumentsContract
import com.wa2c.android.cifsdocumentsprovider.common.exception.SendException
import com.wa2c.android.cifsdocumentsprovider.common.utils.generateUUID
import com.wa2c.android.cifsdocumentsprovider.common.utils.logD
import com.wa2c.android.cifsdocumentsprovider.common.utils.logE
import com.wa2c.android.cifsdocumentsprovider.data.storage.manager.DocumentFileManager
import com.wa2c.android.cifsdocumentsprovider.data.storage.manager.TreeNode
import com.wa2c.android.cifsdocumentsprovider.domain.IoDispatcher
import com.wa2c.android.cifsdocumentsprovider.domain.mapper.DomainMapper.toSendData
import com.wa2c.android.cifsdocumentsprovider.domain.model.SendData
import com.wa2c.android.cifsdocumentsprovider.domain.model.SendDirection
import com.wa2c.android.cifsdocumentsprovider.domain.model.SendDataState
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncScanProgress
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncAction
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncActionType
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncEntry
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncMode
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncResult
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncSide
import com.wa2c.android.cifsdocumentsprovider.domain.sync.PreparedSync
import com.wa2c.android.cifsdocumentsprovider.domain.sync.SyncPlanner
import com.wa2c.android.cifsdocumentsprovider.domain.model.getCurrentReady
import com.wa2c.android.cifsdocumentsprovider.domain.model.toSendFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
     */
    suspend fun syncFolderTwoWay(localFolderUri: Uri, remoteUri: Uri): SyncResult {
        return executeSync(prepareSync(SyncMode.TWO_WAY, localFolderUri, remoteUri))
    }

    /**
     * Compares [localFolderUri] with [remoteUri] and plans a sync in [mode], changing nothing.
     * Reports its progress in [syncScanProgress].
     */
    suspend fun prepareSync(mode: SyncMode, localFolderUri: Uri, remoteUri: Uri, canWriteRemote: Boolean = true): PreparedSync {
        return withContext(dispatcher) {
            try {
                var progress = SyncScanProgress(stage = SyncScanProgress.Stage.COMPARE)
                _syncScanProgress.value = progress
                val comparison = documentFileManager.compareTrees(localFolderUri, remoteUri) { path, folders, paths ->
                    progress = progress.copy(folders = folders, files = paths, currentFolder = path, updateTime = System.currentTimeMillis())
                    _syncScanProgress.value = progress
                }
                val plan = SyncPlanner.plan(
                    mode = mode,
                    entries = comparison.entries.map { SyncEntry(it.path, it.local?.toSide(), it.remote?.toSide()) },
                    failedFolders = comparison.failedFolders,
                    canWriteLocal = documentFileManager.canWriteTree(localFolderUri),
                    canWriteRemote = canWriteRemote,
                )
                PreparedSync(plan, localFolderUri, remoteUri, comparison.entries.associateBy { it.path })
            } finally {
                _syncScanProgress.value = null
            }
        }
    }

    private fun TreeNode.toSide() = SyncSide(isDirectory = isDirectory, size = size, lastModified = lastModified)

    /**
     * Runs exactly the actions of [prepared]: deletions first (moved into the trash folder, and
     * only if the file has not changed since the scan), then the copies are queued for [SendWorker].
     * Phone-side actions are left out without write access to the phone folder.
     */
    suspend fun executeSync(prepared: PreparedSync): SyncResult {
        val plan = prepared.plan
        var trashed = 0
        val skippedChanged = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var localBlocked = 0
        val trashName = SimpleDateFormat(TRASH_NAME_FORMAT, Locale.US).format(Date())
        val queue = withContext(dispatcher) {
            val canWriteLocal = documentFileManager.canWriteTree(prepared.localFolderUri)
            val localFolders = documentFileManager.DirectoryResolver(prepared.localFolderUri)
            val remoteFolders = documentFileManager.DirectoryResolver(prepared.remoteUri)
            val isLocalTarget = { action: SyncAction -> action.type == SyncActionType.DOWNLOAD || action.type == SyncActionType.DELETE_LOCAL }
            val actions = plan.actions.filter { canWriteLocal || !isLocalTarget(it) }
            localBlocked = plan.actions.size - actions.size

            val deletes = actions.filter { it.type.isDelete }
            try {
                var progress = SyncScanProgress(stage = SyncScanProgress.Stage.TRASH, total = deletes.size)
                deletes.forEach { action ->
                    progress = progress.copy(files = progress.files + 1, currentFolder = action.path, updateTime = System.currentTimeMillis())
                    _syncScanProgress.value = progress
                    val isLocal = action.type == SyncActionType.DELETE_LOCAL
                    val entry = prepared.entries[action.path]
                    val node = if (isLocal) entry?.local else entry?.remote
                    when {
                        node == null -> failed += action.path
                        !node.isDirectory && documentFileManager.getFileSize(node.uri) != node.size -> skippedChanged += action.path
                        documentFileManager.moveToTrash(if (isLocal) localFolders else remoteFolders, action.path, node.uri, trashName) -> {
                            trashed += if (action.isDirectory) action.fileCount else 1
                        }
                        else -> failed += action.path
                    }
                }
            } finally {
                _syncScanProgress.value = null
            }

            actions.filter { !it.type.isDelete }.mapNotNull { action ->
                val isUpload = action.type == SyncActionType.UPLOAD
                val entry = prepared.entries[action.path]
                val source = (if (isUpload) entry?.local else entry?.remote) ?: return@mapNotNull null.also { failed += action.path }
                val parentPath = action.path.substringBeforeLast('/', "")
                val targetDir = (if (isUpload) remoteFolders else localFolders).resolve(parentPath)
                    ?: return@mapNotNull null.also { failed += action.path }
                SendData(
                    id = generateUUID(),
                    name = action.path.substringAfterLast('/'),
                    size = source.size,
                    mimeType = source.mimeType,
                    sourceFileUri = source.uri,
                    targetFileUri = targetDir,
                    targetMayExist = action.overwrite,
                    direction = if (isUpload) SendDirection.UPLOAD else SendDirection.DOWNLOAD,
                )
            }
        }
        _sendDataList.emit(sendDataList.value + queue)
        return SyncResult(
            uploads = queue.count { it.direction == SendDirection.UPLOAD },
            downloads = queue.count { it.direction == SendDirection.DOWNLOAD },
            trashed = trashed,
            trashFolder = if (trashed > 0) "${DocumentFileManager.TRASH_DIR}/$trashName" else null,
            skippedChanged = skippedChanged,
            failed = failed,
            conflicts = plan.conflicts,
            failedFolders = plan.failedFolders,
            localBlocked = localBlocked,
        )
    }

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
        private const val TRASH_NAME_FORMAT = "yyyy-MM-dd_HH-mm-ss"
    }

}
