package com.wa2c.android.cifsdocumentsprovider.domain.model

import com.wa2c.android.cifsdocumentsprovider.data.storage.manager.DocumentFileManager

/**
 * How a folder sync treats the phone folder and the server.
 */
enum class SyncMode {
    /** Upload new and changed files (by size) - the classic backup */
    UPLOAD,
    /** Copy what is missing both ways, newer side wins on a size mismatch, nothing is deleted */
    TWO_WAY,
    /** The phone folder is the source: the server is made the same, deleting what the phone lacks */
    MIRROR_TO_SERVER,
    /** The server is the source: the phone folder is made the same, deleting what the server lacks */
    MIRROR_TO_PHONE,
    ;

    val isMirror: Boolean
        get() = this == MIRROR_TO_SERVER || this == MIRROR_TO_PHONE
}

/** One path and what each side has there, as the planner sees it. */
data class SyncEntry(
    /** Relative to the roots */
    val path: String,
    val local: SyncSide?,
    val remote: SyncSide?,
)

data class SyncSide(
    val isDirectory: Boolean,
    val size: Long = 0,
    /** Milliseconds, 0 if unknown */
    val lastModified: Long = 0,
)

enum class SyncActionType {
    UPLOAD,
    DOWNLOAD,
    /** Move into the trash folder on the phone */
    DELETE_LOCAL,
    /** Move into the trash folder on the server */
    DELETE_REMOTE,
    ;

    val isDelete: Boolean
        get() = this == DELETE_LOCAL || this == DELETE_REMOTE
}

data class SyncAction(
    val path: String,
    val type: SyncActionType,
    val isDirectory: Boolean = false,
    /** File size, or the total of the files in a deleted folder */
    val size: Long = 0,
    /** Files in a deleted folder */
    val fileCount: Int = 0,
    /** A copy replaces a different file of the same name */
    val overwrite: Boolean = false,
)

/** Why a plan cannot be run. */
enum class SyncBlockReason {
    /** Some folders could not be listed: a mirror would delete or miss what is in them */
    FAILED_FOLDERS,
    /** The source side has no files, yet the mirror would delete files - most likely a lost folder access */
    EMPTY_SOURCE,
    /** Writing to the server is needed, but the connection is read-only */
    REMOTE_READ_ONLY,
    /** Writing to the phone folder is needed, but there is no write access to it */
    LOCAL_NO_WRITE,
}

/**
 * What a sync would do - shown to the user before anything is changed.
 */
data class SyncPlan(
    val mode: SyncMode,
    /** Sorted by type, then path */
    val actions: List<SyncAction>,
    /** Paths that differ but are left alone: neither side newer, or a file on one side and a folder on the other */
    val conflicts: List<String>,
    /** Folders that could not be listed */
    val failedFolders: List<String>,
    /** Files in the phone folder */
    val localFiles: Int,
    /** Files on the server */
    val remoteFiles: Int,
    val blockReason: SyncBlockReason?,
) {
    val deletes: List<SyncAction>
        get() = actions.filter { it.type.isDelete }

    val overwrites: List<SyncAction>
        get() = actions.filter { it.overwrite }

    /** Files gone from their side after the run (each file in a deleted folder counts) */
    val deletedFiles: Int
        get() = deletes.sumOf { if (it.isDirectory) it.fileCount else 1 }

    /** Files on the side a mirror changes */
    val targetFiles: Int
        get() = when (mode) {
            SyncMode.MIRROR_TO_SERVER -> remoteFiles
            SyncMode.MIRROR_TO_PHONE -> localFiles
            else -> 0
        }

    /** Something would be lost: the run must be confirmed */
    val needsConfirm: Boolean
        get() = deletes.isNotEmpty() || (mode.isMirror && overwrites.isNotEmpty())

    /** A large share of the target would be deleted: confirmed by typing the count */
    val needsStrongConfirm: Boolean
        get() = deletedFiles > 0 && deletedFiles * 100 > targetFiles * STRONG_CONFIRM_PERCENT

    companion object {
        const val STRONG_CONFIRM_PERCENT = 20
        /** Folder deleted items are moved into, on the side they are deleted from */
        const val TRASH_DIR = DocumentFileManager.TRASH_DIR
    }
}

/**
 * What running a plan (or a two-way "Sync now") did.
 */
data class SyncResult(
    val uploads: Int,
    val downloads: Int,
    /** Files moved into the trash folder (each file in a moved folder counts) */
    val trashed: Int,
    /** Folder under which deleted items were put, if any */
    val trashFolder: String?,
    /** Not deleted: the file changed after the scan */
    val skippedChanged: List<String>,
    /** Could not be prepared or moved to the trash */
    val failed: List<String>,
    val conflicts: List<String>,
    val failedFolders: List<String>,
    /** Downloads and phone-side deletions not done: no write access to the phone folder */
    val localBlocked: Int,
)
