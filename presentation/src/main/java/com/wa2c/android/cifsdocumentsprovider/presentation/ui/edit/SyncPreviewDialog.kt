package com.wa2c.android.cifsdocumentsprovider.presentation.ui.edit

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncAction
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncActionType
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncBlockReason
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncMode
import com.wa2c.android.cifsdocumentsprovider.domain.model.SyncPlan
import com.wa2c.android.cifsdocumentsprovider.presentation.R
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.Theme

/** Warning color for files that get replaced */
private val OverwriteColor = Color(0xFFE08A00)

/**
 * What a sync would do, before anything is changed. Runs it on [onConfirm] once the user has
 * confirmed what would be lost.
 */
@Composable
fun SyncPreviewDialog(
    plan: SyncPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var understood by remember(plan) { mutableStateOf(false) }
    var typedCount by remember(plan) { mutableStateOf("") }
    val confirmed = !plan.needsConfirm || (understood && (!plan.needsStrongConfirm || typedCount.trim() == plan.deletedFiles.toString()))
    val canRun = plan.blockReason == null && plan.actions.isNotEmpty() && confirmed

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(Theme.Sizes.M)) {
                    Text(
                        text = stringResource(plan.mode.titleRes),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.sync_preview_nothing_changed),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = if (plan.actions.isEmpty()) {
                            stringResource(R.string.sync_preview_no_actions)
                        } else {
                            stringResource(
                                R.string.sync_preview_summary,
                                plan.actions.count { it.type == SyncActionType.UPLOAD },
                                plan.actions.count { it.type == SyncActionType.DOWNLOAD },
                                plan.deletedFiles,
                                plan.overwrites.size,
                            )
                        },
                        modifier = Modifier.padding(top = Theme.Sizes.S),
                    )
                    plan.blockReason?.let {
                        Text(
                            text = stringResource(it.messageRes),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = Theme.Sizes.S),
                        )
                    }
                }
                HorizontalDivider()

                val deleteColor = MaterialTheme.colorScheme.error
                LazyColumn(modifier = Modifier.weight(1f)) {
                    val deletes = plan.deletes
                    val overwrites = plan.overwrites
                    val copies = plan.actions.filter { !it.type.isDelete && !it.overwrite }
                    actionSection(
                        title = R.string.sync_preview_section_delete_remote,
                        actions = deletes.filter { it.type == SyncActionType.DELETE_REMOTE },
                        color = deleteColor,
                    )
                    actionSection(
                        title = R.string.sync_preview_section_delete_local,
                        actions = deletes.filter { it.type == SyncActionType.DELETE_LOCAL },
                        color = deleteColor,
                    )
                    actionSection(
                        title = R.string.sync_preview_section_overwrite_remote,
                        actions = overwrites.filter { it.type == SyncActionType.UPLOAD },
                        color = OverwriteColor,
                    )
                    actionSection(
                        title = R.string.sync_preview_section_overwrite_local,
                        actions = overwrites.filter { it.type == SyncActionType.DOWNLOAD },
                        color = OverwriteColor,
                    )
                    actionSection(
                        title = R.string.sync_preview_section_upload,
                        actions = copies.filter { it.type == SyncActionType.UPLOAD },
                        color = Color.Unspecified,
                    )
                    actionSection(
                        title = R.string.sync_preview_section_download,
                        actions = copies.filter { it.type == SyncActionType.DOWNLOAD },
                        color = Color.Unspecified,
                    )
                    pathSection(R.string.sync_preview_section_conflicts, plan.conflicts)
                    pathSection(R.string.sync_preview_section_failed_folders, plan.failedFolders)
                }

                HorizontalDivider()
                Column(modifier = Modifier.padding(Theme.Sizes.M)) {
                    if (plan.needsConfirm && plan.blockReason == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { understood = !understood },
                        ) {
                            Checkbox(checked = understood, onCheckedChange = { understood = it })
                            Text(
                                text = stringResource(
                                    R.string.sync_preview_confirm,
                                    plan.deletedFiles,
                                    SyncPlan.TRASH_DIR,
                                    plan.overwrites.size,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (plan.needsStrongConfirm) {
                            OutlinedTextField(
                                value = typedCount,
                                onValueChange = { typedCount = it },
                                label = {
                                    Text(stringResource(R.string.sync_preview_type_count, plan.deletedFiles, plan.targetFiles))
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Theme.Sizes.S),
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Theme.Sizes.S, Alignment.End),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Theme.Sizes.S),
                    ) {
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(R.string.general_close))
                        }
                        if (plan.actions.isNotEmpty()) {
                            Button(onClick = onConfirm, enabled = canRun) {
                                Text(stringResource(R.string.sync_preview_run))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.actionSection(title: Int, actions: List<SyncAction>, color: Color) {
    if (actions.isEmpty()) return
    item(key = "header-$title") {
        SectionHeader(text = stringResource(title, actions.sumOf { if (it.isDirectory) it.fileCount else 1 }), color = color)
    }
    items(actions, key = { "$title-${it.path}" }) { action ->
        val context = LocalContext.current
        val size = Formatter.formatShortFileSize(context, action.size)
        Column(modifier = Modifier.padding(horizontal = Theme.Sizes.M, vertical = Theme.Sizes.SS)) {
            Text(text = action.path, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (action.isDirectory) stringResource(R.string.sync_preview_folder_files, action.fileCount, size) else size,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun LazyListScope.pathSection(title: Int, paths: List<String>) {
    if (paths.isEmpty()) return
    item(key = "header-$title") {
        SectionHeader(text = stringResource(title, paths.size), color = Color.Unspecified)
    }
    items(paths, key = { "$title-$it" }) { path ->
        Text(
            text = path.ifEmpty { stringResource(R.string.sync_scan_root_folder) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = Theme.Sizes.M, vertical = Theme.Sizes.SS),
        )
    }
}

@Composable
private fun SectionHeader(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Theme.Sizes.M, vertical = Theme.Sizes.S),
    )
}

private val SyncMode.titleRes: Int
    get() = when (this) {
        SyncMode.UPLOAD -> R.string.sync_preview_title_upload
        SyncMode.TWO_WAY -> R.string.sync_preview_title_two_way
        SyncMode.MIRROR_TO_SERVER -> R.string.sync_preview_title_mirror_to_server
        SyncMode.MIRROR_TO_PHONE -> R.string.sync_preview_title_mirror_to_phone
    }

private val SyncBlockReason.messageRes: Int
    get() = when (this) {
        SyncBlockReason.FAILED_FOLDERS -> R.string.sync_preview_block_failed_folders
        SyncBlockReason.EMPTY_SOURCE -> R.string.sync_preview_block_empty_source
        SyncBlockReason.REMOTE_READ_ONLY -> R.string.sync_preview_block_read_only
        SyncBlockReason.LOCAL_NO_WRITE -> R.string.sync_preview_block_no_write
    }
