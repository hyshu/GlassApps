package bio.aq.glassdisplay.command

import android.os.SystemClock
import android.view.KeyEvent

enum class DirectionCombo {
    Next,
    Previous
}

class EnterKeyController {
    private var repeatSeen = false

    fun handle(event: KeyEvent, onRelease: () -> Unit): Boolean {
        if (!isEnterKey(event.keyCode)) {
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                repeatSeen = event.repeatCount != 0
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (!repeatSeen) {
                    onRelease()
                }
                repeatSeen = false
                return true
            }
            else -> return true
        }
    }

    private fun isEnterKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER
    }
}

class DirectionKeyController(
    private val comboWindowMs: Long,
    private val nowMs: () -> Long = SystemClock::uptimeMillis
) {
    private var repeatSeen = false
    private var pendingKeyCode: Int? = null
    private var pendingUptimeMs = 0L

    fun handle(
        event: KeyEvent,
        onCombo: (DirectionCombo) -> Unit,
        onPendingKey: (Int) -> Unit,
        log: (String) -> Unit
    ): Boolean {
        if (!isDirectionalKey(event.keyCode)) {
            return false
        }

        val keyName = KeyEvent.keyCodeToString(event.keyCode)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                repeatSeen = event.repeatCount != 0
                log("Direction key down: $keyName repeat=${event.repeatCount} scan=${event.scanCode}")
                return true
            }
            KeyEvent.ACTION_UP -> {
                log("Direction key up: $keyName scan=${event.scanCode}")
                if (!repeatSeen) {
                    handleRelease(event.keyCode, onCombo, onPendingKey, log)
                }
                repeatSeen = false
                return true
            }
            else -> return true
        }
    }

    fun clearPending() {
        pendingKeyCode = null
    }

    private fun handleRelease(
        keyCode: Int,
        onCombo: (DirectionCombo) -> Unit,
        onPendingKey: (Int) -> Unit,
        log: (String) -> Unit
    ) {
        val now = nowMs()
        val previousKeyCode = pendingKeyCode
        if (previousKeyCode != null && now - pendingUptimeMs <= comboWindowMs) {
            val combo = directionComboFor(previousKeyCode, keyCode)
            if (combo != null) {
                pendingKeyCode = null
                onCombo(combo)
                return
            }
        }

        pendingKeyCode = keyCode
        pendingUptimeMs = now
        log("Direction key pending: ${KeyEvent.keyCodeToString(keyCode)}")
        onPendingKey(keyCode)
    }

    private fun directionComboFor(firstKeyCode: Int, secondKeyCode: Int): DirectionCombo? {
        return when {
            isDirectionPair(
                firstKeyCode,
                secondKeyCode,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN
            ) -> DirectionCombo.Next
            isDirectionPair(
                firstKeyCode,
                secondKeyCode,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP
            ) -> DirectionCombo.Previous
            else -> null
        }
    }

    private fun isDirectionalKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    }

    private fun isDirectionPair(firstKeyCode: Int, secondKeyCode: Int, a: Int, b: Int): Boolean {
        return (firstKeyCode == a && secondKeyCode == b) ||
            (firstKeyCode == b && secondKeyCode == a)
    }
}
