package bio.aq.glassdisplay.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandMenuControllerTest {
    @Test
    fun handleEnter_showsMenuWhenHidden() {
        val controller = CommandMenuController()

        controller.handleEnter { error("No action expected.") }

        assertTrue(controller.state.visible)
        assertEquals(CommandMenuPage.Main, controller.state.page)
        assertEquals(0, controller.state.selectedIndex)
    }

    @Test
    fun selectOffset_wrapsWithinVisiblePage() {
        val controller = CommandMenuController()

        controller.show()
        controller.selectOffset(-1)

        assertEquals(MenuAction.Close, controller.state.selectedAction)
    }

    @Test
    fun handleEnter_opensSubmenuAndBackRestoresMainSelection() {
        val controller = CommandMenuController()

        controller.show()
        controller.selectOffset(1)
        controller.handleEnter { error("No action expected.") }
        assertEquals(CommandMenuPage.Resolution, controller.state.page)
        assertEquals(0, controller.state.selectedIndex)

        controller.selectOffset(-1)
        controller.handleEnter { error("No action expected.") }
        assertEquals(CommandMenuPage.Main, controller.state.page)
        assertEquals(MenuAction.OpenResolution, controller.state.selectedAction)
    }

    @Test
    fun handleEnter_closesAndEmitsLeafAction() {
        val emitted = mutableListOf<MenuAction>()
        val controller = CommandMenuController()

        controller.show()
        controller.handleEnter { emitted += it }

        assertFalse(controller.state.visible)
        assertEquals(listOf(MenuAction.FpsOverlay), emitted)
    }
}
