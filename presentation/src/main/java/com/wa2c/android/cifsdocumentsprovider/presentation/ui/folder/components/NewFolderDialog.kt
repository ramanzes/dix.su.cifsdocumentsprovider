package com.wa2c.android.cifsdocumentsprovider.presentation.ui.folder.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wa2c.android.cifsdocumentsprovider.domain.model.FolderNameRule
import com.wa2c.android.cifsdocumentsprovider.presentation.R
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.CommonDialog
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.DialogButton
import com.wa2c.android.cifsdocumentsprovider.presentation.ui.common.Theme

/**
 * New folder dialog.
 */
@Composable
fun NewFolderDialog(
    onInput: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val problem = FolderNameRule.check(name)

    CommonDialog(
        title = stringResource(id = R.string.folder_new_dialog_title),
        confirmButtons = listOf(
            DialogButton(label = stringResource(id = R.string.general_accept), enabled = problem == null) {
                onInput(name)
            }
        ),
        dismissButton = DialogButton(label = stringResource(id = R.string.general_close)) {
            onDismiss()
        },
        onDismiss = onDismiss
    ) {
        Column {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(id = R.string.folder_new_dialog_hint)) },
                singleLine = true,
                isError = name.isNotEmpty() && problem != null,
                supportingText = {
                    Text(
                        when (problem.takeIf { name.isNotEmpty() }) {
                            FolderNameRule.Problem.TOO_LONG -> stringResource(R.string.folder_new_dialog_too_long, FolderNameRule.MAX_LENGTH, name.length)
                            FolderNameRule.Problem.INVALID_CHARACTERS -> stringResource(R.string.folder_new_dialog_invalid_characters)
                            else -> stringResource(R.string.folder_new_dialog_rule, FolderNameRule.MAX_LENGTH)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Preview",
    group = "Group",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun NewFolderDialogPreview() {
    Theme.AppTheme {
        NewFolderDialog(
            onInput = {},
            onDismiss = {},
        )
    }
}
