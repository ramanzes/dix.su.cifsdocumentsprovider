package com.wa2c.android.cifsdocumentsprovider.presentation.ui.edit

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncScanProgress
import com.wa2c.android.cifsdocumentsprovider.presentation.R
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.Theme
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.rememberNow

/** No progress for this long is shown as a warning */
internal const val STALLED_SECONDS = 15L

/**
 * Overlay shown while "Sync now" checks which files to upload, so that a slow but working check
 * can be told apart from a hung one.
 */
@Composable
fun SyncScanBox(progress: SyncScanProgress) {
    val now = rememberNow()
    val sinceUpdate = ((now - progress.updateTime) / 1000).coerceAtLeast(0)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.Colors.LoadingBackground)
            .clickable(indication = null, interactionSource = interactionSource, onClick = {}),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(Theme.Sizes.M)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Theme.Sizes.S))
                .padding(Theme.Sizes.M),
        ) {
            CircularProgressIndicator()
            val isCheck = progress.stage == SyncScanProgress.Stage.CHECK
            val isCompare = progress.stage == SyncScanProgress.Stage.COMPARE
            Text(
                text = stringResource(
                    when (progress.stage) {
                        SyncScanProgress.Stage.COLLECT -> R.string.sync_scan_stage_collect
                        SyncScanProgress.Stage.CHECK -> R.string.sync_scan_stage_check
                        SyncScanProgress.Stage.COMPARE -> R.string.sync_scan_stage_compare
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Theme.Sizes.M),
            )
            if (isCheck) {
                Text(stringResource(R.string.sync_scan_check_counts, progress.files, progress.total, progress.newFiles))
                LinearProgressIndicator(
                    progress = { if (progress.total > 0) progress.files.toFloat() / progress.total else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Theme.Sizes.S),
                )
            } else if (isCompare) {
                Text(stringResource(R.string.sync_scan_compare_counts, progress.folders, progress.files, progress.newFiles, progress.downloadFiles))
            } else {
                Text(stringResource(R.string.sync_scan_collect_counts, progress.folders, progress.files))
            }
            Text(
                text = stringResource(
                    R.string.sync_scan_folder,
                    progress.currentFolder.ifEmpty { stringResource(R.string.sync_scan_root_folder) },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.sync_scan_elapsed, DateUtils.formatElapsedTime((now - progress.startTime) / 1000)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (sinceUpdate >= STALLED_SECONDS) {
                Text(
                    text = stringResource(R.string.sync_scan_stalled, sinceUpdate),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Theme.Sizes.S),
                )
            } else {
                Text(
                    text = stringResource(R.string.sync_scan_alive, sinceUpdate),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = stringResource(if (isCompare) R.string.sync_scan_transfer_next else R.string.sync_scan_upload_next),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = Theme.Sizes.S),
            )
        }
    }
}
