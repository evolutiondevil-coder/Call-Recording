package bg.evolution.callrecorder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import bg.evolution.callrecorder.R
import bg.evolution.callrecorder.update.UpdateState

/** Whether the home screen should show [UpdateCard] for this state. */
fun UpdateState.isWorthShowing(): Boolean = when (this) {
    is UpdateState.Downloading, is UpdateState.ReadyToInstall, is UpdateState.Installing -> true
    is UpdateState.Failed -> release != null
    else -> false
}

/** "New version" banner: download progress, then an Install button. */
@Composable
fun UpdateCard(state: UpdateState, onInstall: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val release = when (state) {
        is UpdateState.Downloading -> state.release
        is UpdateState.ReadyToInstall -> state.release
        is UpdateState.Installing -> state.release
        is UpdateState.Failed -> state.release
        else -> null
    } ?: return
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.SystemUpdate, null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_available, release.version), style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (state) {
                            is UpdateState.Downloading -> stringResource(R.string.update_downloading, (state.progress * 100).toInt())
                            is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready)
                            is UpdateState.Installing -> stringResource(R.string.update_installing)
                            is UpdateState.Failed -> stringResource(state.reason.message())
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state is UpdateState.Downloading || state is UpdateState.Installing) {
                Spacer(Modifier.height(12.dp))
                if (state is UpdateState.Downloading) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            // The button sits under the text, so the text keeps the full width on narrow screens.
            val button: Pair<Int, () -> Unit>? = when (state) {
                is UpdateState.ReadyToInstall -> R.string.action_install to onInstall
                is UpdateState.Failed -> when (state.reason) {
                    UpdateState.Reason.PERMISSION -> R.string.action_install to onInstall
                    UpdateState.Reason.INSTALL -> R.string.action_retry to onInstall
                    else -> R.string.action_retry to onRetry
                }
                else -> null
            }
            if (button != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = button.second, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(button.first), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

fun UpdateState.Reason.message(): Int = when (this) {
    UpdateState.Reason.NETWORK -> R.string.update_failed_network
    UpdateState.Reason.NOT_FOUND -> R.string.update_not_found
    UpdateState.Reason.SIGNATURE -> R.string.update_failed_signature
    UpdateState.Reason.INSTALL -> R.string.update_failed_install
    UpdateState.Reason.PERMISSION -> R.string.update_failed_permission
}
