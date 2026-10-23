package com.wa2c.android.cifsdocumentsprovider.domain.model

/**
 * What a two-way sync queued, and what it left alone.
 */
data class TwoWaySyncResult(
    val uploads: Int,
    val downloads: Int,
    /** Paths that differ on the two sides with neither one newer */
    val conflicts: List<String>,
    /** Paths of folders that could not be listed */
    val failedFolders: List<String>,
    /** Downloads were found but not queued: no write access to the phone folder */
    val downloadsBlocked: Int,
)
