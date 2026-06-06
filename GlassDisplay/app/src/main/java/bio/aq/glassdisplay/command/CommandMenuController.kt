package bio.aq.glassdisplay.command

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class CommandMenuState(
    val visible: Boolean = false,
    val page: CommandMenuPage = CommandMenuPage.Main,
    val selectedIndex: Int = 0
) {
    val actions: List<MenuAction>
        get() = menuActionsFor(page)

    val selectedAction: MenuAction?
        get() = actions.getOrNull(selectedIndex)
}

class CommandMenuController(
    private val log: (String) -> Unit = {}
) {
    var state by mutableStateOf(CommandMenuState())
        private set

    val isVisible: Boolean
        get() = state.visible

    fun show() {
        state = CommandMenuState(visible = true)
        log("Command menu shown")
    }

    fun close() {
        state = CommandMenuState()
    }

    fun selectOffset(offset: Int): Boolean {
        val current = state
        if (!current.visible) {
            return false
        }

        val actions = current.actions
        val selectedIndex = (current.selectedIndex + offset + actions.size) % actions.size
        state = current.copy(selectedIndex = selectedIndex)
        log("Command menu selected: ${current.page}:${actions[selectedIndex]}")
        return true
    }

    fun handleEnter(onAction: (MenuAction) -> Unit) {
        if (!state.visible) {
            show()
            return
        }

        executeSelectedAction(onAction)
    }

    private fun executeSelectedAction(onAction: (MenuAction) -> Unit) {
        val current = state
        val action = current.selectedAction ?: return
        log("Command menu execute: ${current.page}:$action")

        when (action) {
            MenuAction.OpenResolution -> showSubmenu(CommandMenuPage.Resolution)
            MenuAction.OpenDisplayMode -> showSubmenu(CommandMenuPage.DisplayMode)
            MenuAction.Back -> showMainMenuFromSubmenu(current.page)
            MenuAction.Close -> close()
            else -> {
                close()
                onAction(action)
            }
        }
    }

    private fun showSubmenu(menuPage: CommandMenuPage) {
        state = state.copy(page = menuPage, selectedIndex = 0)
        log("Command submenu shown: $menuPage")
    }

    private fun showMainMenuFromSubmenu(previousPage: CommandMenuPage) {
        state = state.copy(
            page = CommandMenuPage.Main,
            selectedIndex = when (previousPage) {
                CommandMenuPage.Resolution -> MAIN_RESOLUTION_INDEX
                CommandMenuPage.DisplayMode -> MAIN_DISPLAY_MODE_INDEX
                CommandMenuPage.Main -> 0
            }
        )
        log("Command menu back from: $previousPage")
    }

    companion object {
        private const val MAIN_RESOLUTION_INDEX = 1
        private const val MAIN_DISPLAY_MODE_INDEX = 2
    }
}
