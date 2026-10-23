package com.wa2c.android.cifsdocumentsprovider.domain.model

/**
 * Progress of the check phase of a folder sync, before any file is queued for upload.
 */
data class SyncScanProgress(
    val stage: Stage,
    /** Folders walked so far */
    val folders: Int = 0,
    /** Files found (COLLECT) or compared with the server (CHECK) so far */
    val files: Int = 0,
    /** Total files to compare (CHECK only) */
    val total: Int = 0,
    /** Files not on the server yet (CHECK), or to upload (COMPARE) */
    val newFiles: Int = 0,
    /** Files to download to the phone (COMPARE only) */
    val downloadFiles: Int = 0,
    /** Folder being processed, relative to the backup folder */
    val currentFolder: String = "",
    val startTime: Long = System.currentTimeMillis(),
    /** Last time any of the counters moved - a stale value means the server is not answering */
    val updateTime: Long = System.currentTimeMillis(),
) {
    enum class Stage {
        /** Walking the local folder tree and mirroring subfolders on the server */
        COLLECT,
        /** Comparing local files with the server listings */
        CHECK,
        /** Two-way sync: walking the local and the server tree together */
        COMPARE,
    }
}
