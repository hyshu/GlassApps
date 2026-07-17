package bio.aq.glassdisplay.command

import bio.aq.glassdisplay.R

enum class CommandMenuPage(val titleResId: Int) {
    Main(R.string.menu_title),
    Resolution(R.string.menu_title_resolution),
    DisplayMode(R.string.menu_title_display_mode),
    Transport(R.string.menu_title_transport)
}

enum class MenuAction(val labelResId: Int) {
    FpsOverlay(R.string.menu_item_fps),
    OpenResolution(R.string.menu_item_resolution),
    OpenDisplayMode(R.string.menu_item_display_mode),
    OpenTransport(R.string.menu_item_transport),
    Resolution480x640(R.string.menu_item_resolution_480x640),
    Resolution480x320(R.string.menu_item_resolution_480x320),
    ResolutionOff(R.string.menu_item_resolution_off),
    DisplayFull(R.string.menu_item_display_full),
    DisplaySplit(R.string.menu_item_display_split),
    TransportAuto(R.string.menu_item_transport_auto),
    TransportWifi(R.string.menu_item_transport_wifi),
    TransportBle(R.string.menu_item_transport_ble),
    Back(R.string.menu_item_back),
    Close(R.string.menu_item_close)
}

fun menuActionsFor(menuPage: CommandMenuPage): List<MenuAction> {
    return when (menuPage) {
        CommandMenuPage.Main -> listOf(
            MenuAction.Close,
            MenuAction.OpenResolution,
            MenuAction.OpenDisplayMode,
            MenuAction.OpenTransport,
            MenuAction.FpsOverlay
        )
        CommandMenuPage.Resolution -> listOf(
            MenuAction.Resolution480x640,
            MenuAction.Resolution480x320,
            MenuAction.ResolutionOff,
            MenuAction.Back
        )
        CommandMenuPage.DisplayMode -> listOf(
            MenuAction.DisplayFull,
            MenuAction.DisplaySplit,
            MenuAction.Back
        )
        CommandMenuPage.Transport -> listOf(
            MenuAction.TransportAuto,
            MenuAction.TransportWifi,
            MenuAction.TransportBle,
            MenuAction.Back
        )
    }
}
