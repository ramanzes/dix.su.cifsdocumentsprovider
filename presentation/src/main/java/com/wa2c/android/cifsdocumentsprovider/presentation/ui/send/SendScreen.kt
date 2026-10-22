package com.wa2c.android.cifsdocumentsprovider.presentation.ui.send

import android.content.res.Configuration
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wa2c.android.cifsdocumentsprovider.domain.model.SendData
import com.wa2c.android.cifsdocumentsprovider.domain.model.SendDataState
import com.wa2c.android.cifsdocumentsprovider.presentation.R
import com.wa2c.android.cifsdocumentsprovider.presentation.ext.getSummaryText
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.AppSnackbarHost
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.CommonDialog
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.DialogButton
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.DividerNormal
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.DividerThin
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.Theme
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.getAppTopAppBarColors
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.rememberNow
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.edit.STALLED_SECONDS

@Composable
fun SendScreen(
    viewModel: SendViewModel = hiltViewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onNavigateDone: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = LocalContext.current as ComponentActivity

    val sendDataList = viewModel.sendDataList.collectAsStateWithLifecycle().value
    val showCloseDialog = remember { mutableStateOf(false) }
    val confirmCount = sendDataList.count { it.state == SendDataState.CONFIRM }
    val isRunning = sendDataList.any { it.state.isCancelable }

    val onClickDone = {
        viewModel.onClickDone()
        onNavigateDone()
    }

    SendScreenContainer(
        snackbarHostState = snackbarHostState,
        sendDataList = sendDataList,
        onClickCancel = { viewModel.onClickCancel(it) },
        onClickRetry = { viewModel.onClickRetry(it) },
        onClickRemove = { viewModel.onClickRemove(it) },
        onClickCancelAll = { viewModel.onClickCancelAll() },
        onClickBackground = { activity.moveTaskToBack(true) },
        onClickDone = onClickDone,
        onClickClose = {
            // Nothing left to cancel: leaving is just "Done", no need to ask
            if (isRunning) showCloseDialog.value = true else onClickDone()
        },
    )

    // Overwrite confirmation
    if (confirmCount > 0) {
        CommonDialog(
            confirmButtons = listOf(
                DialogButton(label = stringResource(id = R.string.general_accept)) {
                    viewModel.onStartSend(true)
                },
            ),
            dismissButton = DialogButton(label = stringResource(id = R.string.general_close)) {
                viewModel.onStartSend(false)
            },
            onDismiss = {
                viewModel.onStartSend(false)
            },
        ) {
            Text(stringResource(id = R.string.send_overwrite_confirmation_message, confirmCount))
        }
    }

    // Exit confirmation: back / close while transfers are running
    if (showCloseDialog.value) {
        CommonDialog(
            confirmButtons = listOf(
                DialogButton(label = stringResource(id = R.string.send_action_background)) {
                    showCloseDialog.value = false
                    activity.moveTaskToBack(true)
                },
            ),
            dismissButton = DialogButton(label = stringResource(id = R.string.send_action_cancel_all)) {
                showCloseDialog.value = false
                viewModel.onClickCancelAll()
            },
            onDismiss = {
                showCloseDialog.value = false
            }
        ) {
            Text(stringResource(id = R.string.send_exit_running_message))
        }
    }
}

/**
 * Send Screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreenContainer(
    snackbarHostState: SnackbarHostState,
    sendDataList: List<SendData>,
    onClickCancel: (SendData) -> Unit,
    onClickRetry: (SendData) -> Unit,
    onClickRemove: (SendData) -> Unit,
    onClickCancelAll: () -> Unit,
    onClickBackground: () -> Unit,
    onClickDone: () -> Unit,
    onClickClose: () -> Unit,
) {
    val isRunning = sendDataList.any { it.state.isCancelable }
    val currentIndex = sendDataList.indexOfFirst { it.state.inProgress }
    val listState = rememberLazyListState()

    // Keep the file being sent in view
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) listState.animateScrollToItem(currentIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.send_title)) },
                colors = getAppTopAppBarColors(),
                actions = {
                    IconButton(
                        onClick = { onClickClose() }
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_close),
                            contentDescription = stringResource(id = R.string.send_close_button),
                        )
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(paddingValues)
        ) {
            SendSummary(sendDataList = sendDataList, isRunning = isRunning)

            DividerNormal()

            Box(
                modifier = Modifier.weight(weight = 1f, fill = true)
            ) {
                LazyColumn(state = listState) {
                    items(items = sendDataList) { sendData ->
                        SendDataItem(
                            sendData = sendData,
                            onClickCancel = onClickCancel,
                            onClickRetry = onClickRetry,
                            onClickRemove = onClickRemove,
                        )
                        DividerThin()
                    }
                }
            }

            DividerNormal()

            Column(
                modifier = Modifier
                    .padding(Theme.Sizes.S),
            ) {
                if (isRunning) {
                    Text(
                        text = stringResource(id = R.string.send_background_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = onClickBackground,
                        shape = RoundedCornerShape(Theme.Sizes.SS),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Theme.Sizes.S)
                    ) {
                        Text(text = stringResource(id = R.string.send_action_background))
                    }
                    OutlinedButton(
                        onClick = onClickCancelAll,
                        shape = RoundedCornerShape(Theme.Sizes.SS),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Theme.Sizes.S)
                    ) {
                        Text(text = stringResource(id = R.string.send_action_cancel_all))
                    }
                } else {
                    Button(
                        onClick = onClickDone,
                        shape = RoundedCornerShape(Theme.Sizes.SS),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Theme.Sizes.S)
                    ) {
                        Text(text = stringResource(id = R.string.send_action_done))
                    }
                }
            }
        }
    }

    // Back button
    BackHandler { onClickClose() }
}

/**
 * Totals over the whole queue, the file being sent and how long ago data last moved - tells a
 * slow transfer from a hung one.
 */
@Composable
private fun SendSummary(
    sendDataList: List<SendData>,
    isRunning: Boolean,
) {
    val context = LocalContext.current
    val succeeded = sendDataList.count { it.state == SendDataState.SUCCESS }
    val failed = sendDataList.count { it.state == SendDataState.FAILURE }
    val cancelled = sendDataList.count { it.state == SendDataState.CANCEL }
    val totalBytes = sendDataList.sumOf { it.size }
    val sentBytes = sendDataList.sumOf {
        when {
            it.state == SendDataState.SUCCESS -> it.size
            it.state.inProgress -> it.progressSize
            else -> 0L
        }
    }
    val current = sendDataList.firstOrNull { it.state.inProgress }

    // Anything moving - bytes or a finished file - counts as progress
    val now = rememberNow()
    var lastChange by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sentBytes, succeeded + failed + cancelled) {
        lastChange = System.currentTimeMillis()
    }
    val sinceChange = ((now - lastChange) / 1000).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Theme.Sizes.M, vertical = Theme.Sizes.S)
    ) {
        if (!isRunning) {
            Text(
                text = stringResource(id = if (failed + cancelled > 0) R.string.send_stopped else R.string.send_finished),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = stringResource(id = R.string.send_summary, succeeded, sendDataList.size, failed, cancelled),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(
                id = R.string.send_summary_bytes,
                Formatter.formatShortFileSize(context, sentBytes),
                Formatter.formatShortFileSize(context, totalBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (isRunning) {
            LinearProgressIndicator(
                progress = { if (totalBytes > 0) sentBytes.toFloat() / totalBytes else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Theme.Sizes.SS),
            )
            Text(
                text = current?.let { stringResource(id = R.string.send_current, it.name) }
                    ?: stringResource(id = R.string.send_preparing),
                style = MaterialTheme.typography.bodySmall,
            )
            if (sinceChange >= STALLED_SECONDS) {
                Text(
                    text = stringResource(id = R.string.send_stalled, sinceChange),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = stringResource(id = R.string.send_alive, sinceChange),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SendDataItem(
    sendData: SendData,
    onClickCancel: (SendData) -> Unit,
    onClickRetry: (SendData) -> Unit,
    onClickRemove: (SendData) -> Unit,
) {
    val showPopup = remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = true, onClick = {
                showPopup.value = true
            })
            .padding(horizontal = Theme.Sizes.M, vertical = Theme.Sizes.S)
    ) {
        Text(
            text = sendData.name,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = sendData.getSummaryText(context = LocalContext.current),
            style = MaterialTheme.typography.bodyMedium,
            color = if (sendData.state == SendDataState.FAILURE) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        // Raw message from the system / server, for when the reason above is not enough
        sendData.failure?.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LinearProgressIndicator(
            progress = { (sendData.progress.toFloat() / 100) },
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (sendData.state.inProgress) 1f else 0f),
        )
    }

    if (showPopup.value) {
        DropdownMenu(
            expanded = showPopup.value ,
            onDismissRequest = {
                showPopup.value = false
            }
        ) {
            if (sendData.state.isCancelable) {
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.send_action_cancel)) },
                    onClick = {
                        onClickCancel(sendData)
                        showPopup.value = false
                    }
                )
            }
            if (sendData.state.isRetryable) {
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.send_action_retry)) },
                    onClick = {
                        onClickRetry(sendData)
                        showPopup.value = false
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.send_action_remove)) },
                onClick = {
                    onClickRemove(sendData)
                    showPopup.value = false
                }
            )
        }
    }
}


/**
 * Preview
 */
@Preview(
    name = "Preview",
    group = "Group",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun SendScreenPreview() {
    Theme.AppTheme {
        SendScreenContainer(
            snackbarHostState = SnackbarHostState(),
            sendDataList = listOf(
                SendData(
                    id = "1",
                    name = "Sample1.jpg",
                    size = 1000000,
                    mimeType = "image/jpeg",
                    sourceFileUri = Uri.parse("smb://pc1/send"),
                    targetFileUri = Uri.parse("smb://pc2/receive"),
                    state = SendDataState.PROGRESS,
                    progressSize = 500000,
                ),
                SendData(
                    id = "2",
                    name = "Sample2.MP3",
                    size = 2000000,
                    mimeType = "audio/mpeg",
                    sourceFileUri = Uri.parse("smb://pc1/send"),
                    targetFileUri = Uri.parse("smb://pc2/receive"),
                    state = SendDataState.READY,
                    progressSize = 0,
                ),
                SendData(
                    id = "3",
                    name = "sample3.mp4",
                    size = 3000000,
                    mimeType = "video/mp4",
                    sourceFileUri = Uri.parse("smb://pc1/send"),
                    targetFileUri = Uri.parse("smb://pc2/receive"),
                    state = SendDataState.CANCEL,
                    progressSize = 2000000,
                ),
            ),
            onClickCancel = {},
            onClickRetry = {},
            onClickRemove = {},
            onClickCancelAll = {},
            onClickBackground = {},
            onClickDone = {},
            onClickClose = {},
        )
    }
}
