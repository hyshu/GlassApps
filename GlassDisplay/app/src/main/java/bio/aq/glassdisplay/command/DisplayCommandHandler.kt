package bio.aq.glassdisplay.command

import androidx.compose.runtime.MutableState
import bio.aq.glassdisplay.display.FrameView
import bio.aq.glassdisplay.protocol.HostCommand
import bio.aq.glassdisplay.streaming.StreamCoordinator
import bio.aq.glassdisplay.ui.StatusPresenter

class DisplayCommandHandler(
    private val frameView: FrameView,
    private val streamCoordinator: StreamCoordinator,
    private val statusPresenter: StatusPresenter,
    private val fpsOverlayVisible: MutableState<Boolean>,
    private val displayMode: MutableState<FrameView.DisplayMode>,
    private val hideSystemBars: () -> Unit,
    private val log: (String) -> Unit
) {
    fun execute(action: MenuAction) {
        when (action) {
            MenuAction.FpsOverlay -> triggerFpsOverlay()
            MenuAction.Resolution480x640 -> selectResolution(HostCommand.Resolution480x640)
            MenuAction.Resolution480x320 -> selectResolution(HostCommand.Resolution480x320)
            MenuAction.ResolutionOff -> selectResolution(HostCommand.ResolutionOff)
            MenuAction.DisplayFull -> selectDisplayMode(FrameView.DisplayMode.Full)
            MenuAction.DisplaySplit -> selectDisplayMode(FrameView.DisplayMode.Split)
            MenuAction.OpenResolution,
            MenuAction.OpenDisplayMode,
            MenuAction.Back,
            MenuAction.Close -> Unit
        }
    }

    private fun selectResolution(command: HostCommand) {
        log("Resolution selected: $command")
        streamCoordinator.queueResolutionCommand(command)
        log("Queued host command: $command")
        statusPresenter.showResolutionSwitching(command)
    }

    private fun selectDisplayMode(mode: FrameView.DisplayMode) {
        val selectedMode = frameView.setDisplayMode(mode)
        displayMode.value = selectedMode
        log("Display mode selected: $selectedMode")
    }

    private fun triggerFpsOverlay() {
        val visible = frameView.toggleFpsOverlay()
        fpsOverlayVisible.value = visible
        log("FPS overlay: $visible")
        hideSystemBars()
    }
}
