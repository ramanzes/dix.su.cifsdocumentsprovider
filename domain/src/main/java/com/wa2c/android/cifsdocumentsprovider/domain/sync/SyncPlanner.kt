package com.wa2c.android.cifsdocumentsprovider.domain.sync

import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncAction
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncActionType
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncBlockReason
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncEntry
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncMode
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncPlan
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncSide

/**
 * Decides what a folder sync does with each path. Files are compared by name and size only.
 */
object SyncPlanner {

    fun plan(
        mode: SyncMode,
        entries: List<SyncEntry>,
        failedFolders: List<String>,
        canWriteLocal: Boolean = true,
        canWriteRemote: Boolean = true,
    ): SyncPlan {
        // A folder deleted as a whole: its contents are not listed one by one
        val deletedFolders = entries.filter { it.isDirectory && deleteType(mode, it) != null }.map { it.path }.toSet()
        val folderFiles = mutableMapOf<String, Int>()
        val folderSizes = mutableMapOf<String, Long>()

        val actions = mutableListOf<SyncAction>()
        val conflicts = mutableListOf<String>()
        for (entry in entries) {
            val deletedAncestor = entry.ancestors().firstOrNull { it in deletedFolders }
            if (deletedAncestor != null) {
                val side = entry.local ?: entry.remote
                if (side != null && !side.isDirectory) {
                    folderFiles[deletedAncestor] = (folderFiles[deletedAncestor] ?: 0) + 1
                    folderSizes[deletedAncestor] = (folderSizes[deletedAncestor] ?: 0) + side.size
                }
                continue
            }
            val l = entry.local
            val r = entry.remote
            when {
                l != null && r != null && l.isDirectory != r.isDirectory -> conflicts += entry.path
                l != null && r != null && l.isDirectory -> Unit
                l != null && r != null -> differing(mode, entry.path, l, r)?.let { actions += it } ?: run {
                    if (l.size != r.size) conflicts += entry.path
                }
                l != null || r != null -> deleteType(mode, entry)?.let {
                    actions += SyncAction(entry.path, it, isDirectory = entry.isDirectory, size = (l ?: r)!!.size)
                } ?: oneSidedCopy(mode, entry)?.let { actions += it }
            }
        }

        val finalActions = actions.map {
            if (it.isDirectory) it.copy(fileCount = folderFiles[it.path] ?: 0, size = folderSizes[it.path] ?: 0) else it
        }.sortedWith(compareBy({ it.type.ordinal }, { it.path }))

        val localFiles = entries.count { it.local?.isDirectory == false }
        val remoteFiles = entries.count { it.remote?.isDirectory == false }
        val deletedFiles = finalActions.filter { it.type.isDelete }.sumOf { if (it.isDirectory) it.fileCount else 1 }
        val sourceFiles = if (mode == SyncMode.MIRROR_TO_SERVER) localFiles else remoteFiles
        val needsLocal = finalActions.any { it.type == SyncActionType.DOWNLOAD || it.type == SyncActionType.DELETE_LOCAL }
        val needsRemote = finalActions.any { it.type == SyncActionType.UPLOAD || it.type == SyncActionType.DELETE_REMOTE }
        val blockReason = when {
            mode.isMirror && failedFolders.isNotEmpty() -> SyncBlockReason.FAILED_FOLDERS
            mode.isMirror && sourceFiles == 0 && deletedFiles > 0 -> SyncBlockReason.EMPTY_SOURCE
            needsRemote && !canWriteRemote -> SyncBlockReason.REMOTE_READ_ONLY
            needsLocal && !canWriteLocal -> SyncBlockReason.LOCAL_NO_WRITE
            else -> null
        }

        return SyncPlan(
            mode = mode,
            actions = finalActions,
            conflicts = conflicts.sorted(),
            failedFolders = failedFolders,
            localFiles = localFiles,
            remoteFiles = remoteFiles,
            blockReason = blockReason,
        )
    }

    /** Deletion of a path present on one side only, if [mode] deletes it */
    private fun deleteType(mode: SyncMode, entry: SyncEntry): SyncActionType? {
        return when {
            mode == SyncMode.MIRROR_TO_PHONE && entry.local != null && entry.remote == null -> SyncActionType.DELETE_LOCAL
            mode == SyncMode.MIRROR_TO_SERVER && entry.remote != null && entry.local == null -> SyncActionType.DELETE_REMOTE
            else -> null
        }
    }

    /** Copy of a file present on one side only. Folders are created as their files are copied. */
    private fun oneSidedCopy(mode: SyncMode, entry: SyncEntry): SyncAction? {
        val l = entry.local
        val r = entry.remote
        return when {
            l != null && !l.isDirectory && mode != SyncMode.MIRROR_TO_PHONE -> SyncAction(entry.path, SyncActionType.UPLOAD, size = l.size)
            r != null && !r.isDirectory && (mode == SyncMode.TWO_WAY || mode == SyncMode.MIRROR_TO_PHONE) -> SyncAction(entry.path, SyncActionType.DOWNLOAD, size = r.size)
            else -> null
        }
    }

    /** Copy of a file present on both sides, null if they are the same or neither side wins */
    private fun differing(mode: SyncMode, path: String, l: SyncSide, r: SyncSide): SyncAction? {
        if (l.size == r.size) return null
        val upload = SyncAction(path, SyncActionType.UPLOAD, size = l.size, overwrite = true)
        val download = SyncAction(path, SyncActionType.DOWNLOAD, size = r.size, overwrite = true)
        return when (mode) {
            SyncMode.UPLOAD, SyncMode.MIRROR_TO_SERVER -> upload
            SyncMode.MIRROR_TO_PHONE -> download
            SyncMode.TWO_WAY -> when {
                l.lastModified > r.lastModified && r.lastModified > 0 -> upload
                r.lastModified > l.lastModified && l.lastModified > 0 -> download
                else -> null
            }
        }
    }

    private val SyncEntry.isDirectory: Boolean
        get() = (local ?: remote)?.isDirectory == true

    /** Paths of the folders containing this entry, nearest last */
    private fun SyncEntry.ancestors(): List<String> {
        return path.indices.filter { path[it] == '/' }.map { path.substring(0, it) }
    }
}
