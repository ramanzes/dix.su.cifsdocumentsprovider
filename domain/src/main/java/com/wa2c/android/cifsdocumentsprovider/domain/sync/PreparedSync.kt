package com.wa2c.android.cifsdocumentsprovider.domain.sync

import android.net.Uri
import com.wa2c.android.cifsdocumentsprovider.data.storage.manager.TreeEntry
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncPlan

/**
 * A planned sync, with what the scan found at each path - running it does exactly what [plan] shows.
 */
class PreparedSync internal constructor(
    val plan: SyncPlan,
    internal val localFolderUri: Uri,
    internal val remoteUri: Uri,
    internal val entries: Map<String, TreeEntry>,
)
