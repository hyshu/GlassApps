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

        assertEquals(MenuAction.FpsOverlay, controller.state.selectedAction)
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
    fun handleEnter_opensTransportSubmenuAndBackRestoresMainSelection() {
        val controller = CommandMenuController()

        controller.show()
        controller.selectOffset(3)
        controller.handleEnter { error("No action expected.") }
        assertEquals(CommandMenuPage.Transport, controller.state.page)
        assertEquals(
            listOf(MenuAction.TransportAuto, MenuAction.TransportWifi, MenuAction.TransportBle, MenuAction.Back),
            controller.state.actions
        )

        controller.selectOffset(-1)
        controller.handleEnter { error("No action expected.") }
        assertEquals(CommandMenuPage.Main, controller.state.page)
        assertEquals(MenuAction.OpenTransport, controller.state.selectedAction)
    }

    @Test
    fun handleEnter_emitsTransportLeafAction() {
        val emitted = mutableListOf<MenuAction>()
        val controller = CommandMenuController()

        controller.show()
        controller.selectOffset(3)
        controller.handleEnter { error("No action expected.") }
        controller.handleEnter { emitted += it }

        assertFalse(controller.state.visible)
        assertEquals(listOf(MenuAction.TransportAuto), emitted)
    }

    @Test
    fun handleEnter_closesWithoutEmittingAction() {
        val emitted = mutableListOf<MenuAction>()
        val controller = CommandMenuController()

        controller.show()
        controller.handleEnter { emitted += it }

        assertFalse(controller.state.visible)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun mainMenu_placesCloseFirstAndOverlayLast() {
        assertEquals(
            listOf(
                MenuAction.Close,
                MenuAction.OpenResolution,
                MenuAction.OpenDisplayMode,
                MenuAction.OpenTransport,
                MenuAction.FpsOverlay
            ),
            menuActionsFor(CommandMenuPage.Main)
        )
    }
}
