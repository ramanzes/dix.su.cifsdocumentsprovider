package com.wa2c.android.cifsdocumentsprovider.data.storage.manager

import android.net.Uri

/**
 * Result of [DocumentFileManager.compareTrees].
 */
data class TreeComparison(
    /** Every path found on either side, relative to the roots */
    val entries: List<TreeEntry> = emptyList(),
    /** Folders, relative to the roots, that could not be listed on either side - skipped */
    val failedFolders: List<String> = emptyList(),
)

/**
 * One path and what each side has there.
 */
data class TreeEntry(
    val path: String,
    val local: TreeNode?,
    val remote: TreeNode?,
)

data class TreeNode(
    val uri: Uri,
    val isDirectory: Boolean,
    val size: Long,
    val mimeType: String,
    /** Milliseconds, 0 if unknown */
    val lastModified: Long,
)
