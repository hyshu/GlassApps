package bio.aq.glassdisplay.ui

import android.content.Context
import androidx.compose.runtime.MutableState
import bio.aq.glassdisplay.R
import bio.aq.glassdisplay.protocol.HostCommand

class StatusPresenter(
    private val context: Context,
    private val state: MutableState<StatusUiState?>
) {
    fun show(
        title: String,
        detail: String,
        loading: Boolean = false,
        position: StatusPanelPosition = StatusPanelPosition.TopStart
    ) {
        state.value = StatusUiState(title, detail, loading, position)
    }

    fun hide() {
        state.value = null
    }

    fun showWaitingForHost(port: Int) {
        show(
            title = context.getString(R.string.status_waiting_title),
            detail = context.getString(R.string.status_waiting_detail, port)
        )
    }

    fun showBlePermissionMissing() {
        show(
            title = "BLE permission missing",
            detail = "Grant Nearby devices permission to enable BLE."
        )
    }

    fun showResolutionPending(command: HostCommand) {
        show(
            title = context.getString(R.string.status_resolution_pending_title),
            detail = context.getString(R.string.status_resolution_pending_detail, command.label),
            loading = false
        )
    }

    fun showResolutionSwitching(command: HostCommand) {
        show(
            title = context.getString(R.string.status_resolution_switching_title),
            detail = context.getString(R.string.status_resolution_switching_detail, command.label),
            loading = true
        )
    }
}
