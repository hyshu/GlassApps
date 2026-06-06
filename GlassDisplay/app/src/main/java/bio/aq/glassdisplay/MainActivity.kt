package bio.aq.glassdisplay

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import bio.aq.glassdisplay.command.CommandMenuController
import bio.aq.glassdisplay.command.DisplayCommandHandler
import bio.aq.glassdisplay.command.DirectionCombo
import bio.aq.glassdisplay.command.DirectionKeyController
import bio.aq.glassdisplay.command.EnterKeyController
import bio.aq.glassdisplay.command.MenuAction
import bio.aq.glassdisplay.display.FrameView
import bio.aq.glassdisplay.protocol.HostCommand
import bio.aq.glassdisplay.protocol.StreamStats
import bio.aq.glassdisplay.protocol.Transport
import bio.aq.glassdisplay.streaming.FrameServerListener
import bio.aq.glassdisplay.streaming.StreamCoordinator
import bio.aq.glassdisplay.streaming.ble.BleFrameServer
import bio.aq.glassdisplay.streaming.ble.BlePermissionController
import bio.aq.glassdisplay.streaming.tcp.FrameServer
import bio.aq.glassdisplay.ui.GlassDisplayScreen
import bio.aq.glassdisplay.ui.GlassDisplayTheme
import bio.aq.glassdisplay.ui.StatusPanelPosition
import bio.aq.glassdisplay.ui.StatusPresenter
import bio.aq.glassdisplay.ui.StatusUiState

class MainActivity : ComponentActivity(), FrameServerListener {
    private val logTag = "GlassDisplay"

    private lateinit var frameView: FrameView
    private lateinit var frameServer: FrameServer
    private lateinit var bleFrameServer: BleFrameServer
    private lateinit var streamCoordinator: StreamCoordinator
    private lateinit var statusPresenter: StatusPresenter
    private lateinit var blePermissionController: BlePermissionController
    private lateinit var displayCommandHandler: DisplayCommandHandler

    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusUiState = mutableStateOf<StatusUiState?>(null)
    private val fpsOverlayVisible = mutableStateOf(false)
    private val displayMode = mutableStateOf(FrameView.DisplayMode.Full)
    private val commandMenuController = CommandMenuController { message -> Log.i(logTag, message) }
    private val enterKeyController = EnterKeyController()
    private val directionKeyController = DirectionKeyController(DIRECTION_COMBO_WINDOW_MS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        frameView = FrameView(this)
        frameServer = FrameServer(applicationContext, FrameServer.DEFAULT_PORT, this)
        bleFrameServer = BleFrameServer(applicationContext, this)
        statusPresenter = StatusPresenter(this, statusUiState)
        streamCoordinator = StreamCoordinator(
            mainHandler = mainHandler,
            onRestartBle = { ensureBlePermissionsAndStart() },
            onResolutionPending = { command -> statusPresenter.showResolutionPending(command) }
        )
        blePermissionController = BlePermissionController(
            activity = this,
            onGranted = { bleFrameServer.start() },
            onDenied = { statusPresenter.showBlePermissionMissing() }
        )
        displayCommandHandler = DisplayCommandHandler(
            frameView = frameView,
            streamCoordinator = streamCoordinator,
            statusPresenter = statusPresenter,
            fpsOverlayVisible = fpsOverlayVisible,
            displayMode = displayMode,
            hideSystemBars = { hideSystemBars() },
            log = { message -> Log.i(logTag, message) }
        )

        setContent {
            GlassDisplayTheme {
                val menuState = commandMenuController.state
                val fpsStateLabel = stringResource(
                    if (fpsOverlayVisible.value) R.string.state_on else R.string.state_off
                )
                val displayModeLabel = stringResource(
                    when (displayMode.value) {
                        FrameView.DisplayMode.Full -> R.string.display_mode_full
                        FrameView.DisplayMode.Split -> R.string.display_mode_split
                    }
                )
                GlassDisplayScreen(
                    frameView = frameView,
                    status = statusUiState.value,
                    commandMenuVisible = menuState.visible,
                    selectedMenuIndex = menuState.selectedIndex,
                    menuTitle = stringResource(menuState.page.titleResId),
                    menuLabels = menuState.actions.map { action ->
                        if (action == MenuAction.FpsOverlay) {
                            stringResource(R.string.menu_item_fps_state, fpsStateLabel)
                        } else if (action == MenuAction.OpenDisplayMode) {
                            stringResource(R.string.menu_item_display_mode_state, displayModeLabel)
                        } else {
                            stringResource(action.labelResId)
                        }
                    }
                )
            }
        }

        window.decorView.isFocusableInTouchMode = true
        window.decorView.requestFocus()

        statusPresenter.showWaitingForHost(FrameServer.DEFAULT_PORT)
        ensureBlePermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        hideSystemBars()
        frameServer.start()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        frameServer.stop()
        super.onStop()
    }

    override fun onDestroy() {
        streamCoordinator.clearCallbacks()
        bleFrameServer.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleDirectionalKey(event)) {
            return true
        }
        if (handleEnterKey(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStatusChanged(title: String, detail: String) {
        mainHandler.post {
            if (title == "Connected") {
                statusPresenter.hide()
            } else {
                statusPresenter.show(title, detail, position = statusPositionFor(title))
            }
        }
    }

    override fun onFrameReceived(
        sourceId: String,
        transport: Transport,
        width: Int,
        height: Int,
        packedFrame: ByteArray,
        stats: StreamStats
    ) {
        frameView.submitPackedFrame(sourceId, transport, width, height, packedFrame)
        frameView.submitFps(stats.framesPerSecond)

        val shouldHideStatus = shouldHideStatusAfterFrame(width, height)
        mainHandler.post {
            if (shouldHideStatus) {
                statusPresenter.hide()
            }
        }
    }

    override fun onFrameSourceDisconnected(sourceId: String) {
        frameView.removeSource(sourceId)
    }

    override fun onHostStatus(title: String, detail: String) {
        streamCoordinator.onHostStatusReceived()
        mainHandler.post {
            statusPresenter.show(title = title, detail = detail, loading = false)
        }
    }

    override fun consumeHostCommand(transport: Transport): HostCommand? {
        return streamCoordinator.consumeHostCommand(transport)
    }

    override fun onTransportConnected(transport: Transport) {
        streamCoordinator.onTransportConnected(transport)
    }

    override fun onTransportDisconnected(transport: Transport) {
        streamCoordinator.onTransportDisconnected(transport)
    }

    override fun shouldAcceptFrame(transport: Transport): Boolean {
        return displayMode.value != FrameView.DisplayMode.Full ||
            streamCoordinator.shouldAcceptFrame(transport)
    }

    override fun shouldShowStreamError(transport: Transport): Boolean {
        if (displayMode.value == FrameView.DisplayMode.Full && frameView.hasFrames()) {
            return false
        }
        return shouldAcceptFrame(transport)
    }

    private fun statusPositionFor(title: String): StatusPanelPosition {
        if (title == "BLE stream error" &&
            displayMode.value == FrameView.DisplayMode.Split &&
            frameView.hasFrames() &&
            frameView.visibleSplitFrameCount() < SPLIT_SOURCE_LIMIT
        ) {
            return StatusPanelPosition.BottomStart
        }
        return StatusPanelPosition.TopStart
    }

    private fun shouldHideStatusAfterFrame(width: Int, height: Int): Boolean {
        return streamCoordinator.shouldHideStatusAfterFrame(width, height)
    }

    private fun ensureBlePermissionsAndStart() {
        blePermissionController.ensureGrantedOrRequest()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView) ?: return
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun handleEnterKey(event: KeyEvent): Boolean {
        return enterKeyController.handle(event) {
            handleMenuEnter()
        }
    }

    private fun handleDirectionalKey(event: KeyEvent): Boolean {
        return directionKeyController.handle(
            event = event,
            onCombo = { combo -> triggerDirectionCombo(combo) },
            onPendingKey = { hideSystemBars() },
            log = { message -> Log.d(logTag, message) }
        )
    }

    private fun triggerDirectionCombo(combo: DirectionCombo) {
        if (!commandMenuController.isVisible) {
            Log.i(logTag, "Direction combo ignored while menu hidden: $combo")
            hideSystemBars()
            return
        }

        val offset = when (combo) {
            DirectionCombo.Next -> 1
            DirectionCombo.Previous -> -1
        }
        commandMenuController.selectOffset(offset)
        hideSystemBars()
    }

    private fun handleMenuEnter() {
        directionKeyController.clearPending()
        commandMenuController.handleEnter { action -> displayCommandHandler.execute(action) }
        hideSystemBars()
    }

    companion object {
        private const val DIRECTION_COMBO_WINDOW_MS = 180L
        private const val SPLIT_SOURCE_LIMIT = 2
    }
}
