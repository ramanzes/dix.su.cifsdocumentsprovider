package com.wa2c.android.cifsdocumentsprovider.data.storage.manager

import android.net.Uri

/**
 * Result of [DocumentFileManager.diffTreesRecursively].
 */
data class TreeDiff(
    /** Phone to server */
    val upload: List<SyncTransfer> = emptyList(),
    /** Server to phone */
    val download: List<SyncTransfer> = emptyList(),
    /** Paths, relative to the roots, that differ but neither side is newer - left as they are */
    val conflicts: List<String> = emptyList(),
    /** Folders, relative to the roots, that could not be listed on either side - skipped */
    val failedFolders: List<String> = emptyList(),
    /** Files already the same on both sides */
    val unchanged: Int = 0,
) {
    val files: Int
        get() = upload.size + download.size + conflicts.size + unchanged
}

/**
 * One file to copy into [targetDirUri].
 */
data class SyncTransfer(
    val sourceUri: Uri,
    val targetDirUri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    /** A file of this name is already in [targetDirUri] and gets replaced */
    val targetExists: Boolean,
)
