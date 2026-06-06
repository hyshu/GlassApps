package bio.aq.glassdisplay.command

import bio.aq.glassdisplay.R

enum class CommandMenuPage(val titleResId: Int) {
    Main(R.string.menu_title),
    Resolution(R.string.menu_title_resolution),
    DisplayMode(R.string.menu_title_display_mode)
}

enum class MenuAction(val labelResId: Int) {
    FpsOverlay(R.string.menu_item_fps),
    OpenResolution(R.string.menu_item_resolution),
    OpenDisplayMode(R.string.menu_item_display_mode),
    Resolution480x640(R.string.menu_item_resolution_480x640),
    Resolution480x320(R.string.menu_item_resolution_480x320),
    ResolutionOff(R.string.menu_item_resolution_off),
    DisplayFull(R.string.menu_item_display_full),
    DisplaySplit(R.string.menu_item_display_split),
    Back(R.string.menu_item_back),
    Close(R.string.menu_item_close)
}

fun menuActionsFor(menuPage: CommandMenuPage): List<MenuAction> {
    return when (menuPage) {
        CommandMenuPage.Main -> listOf(
            MenuAction.FpsOverlay,
            MenuAction.OpenResolution,
            MenuAction.OpenDisplayMode,
            MenuAction.Close
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
    }
}
