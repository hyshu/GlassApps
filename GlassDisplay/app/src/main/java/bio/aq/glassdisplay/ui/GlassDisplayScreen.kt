package bio.aq.glassdisplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import bio.aq.glassdisplay.display.FrameView

data class StatusUiState(
    val title: String,
    val detail: String,
    val loading: Boolean = false,
    val position: StatusPanelPosition = StatusPanelPosition.TopStart
)

enum class StatusPanelPosition {
    TopStart,
    BottomStart
}

private val GlassDisplayColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White
)

private val PanelBackground = Color(0xAA000000)
private val StatusPrimary = Color(0xFFF2F2F2)
private val StatusSecondary = Color(0xFFB5B5B5)

@Composable
fun GlassDisplayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GlassDisplayColors,
        typography = Typography(),
        content = content
    )
}

@Composable
fun GlassDisplayScreen(
    frameView: FrameView,
    status: StatusUiState?,
    commandMenuVisible: Boolean,
    selectedMenuIndex: Int,
    menuTitle: String,
    menuLabels: List<String>
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { frameView },
            modifier = Modifier.fillMaxSize()
        )

        status?.let {
            StatusPanel(
                status = it,
                modifier = Modifier
                    .align(
                        when (it.position) {
                            StatusPanelPosition.TopStart -> Alignment.TopStart
                            StatusPanelPosition.BottomStart -> Alignment.BottomStart
                        }
                    )
                    .padding(12.dp)
            )
        }

        if (commandMenuVisible) {
            CommandMenu(
                title = menuTitle,
                labels = menuLabels,
                selectedIndex = selectedMenuIndex,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun StatusPanel(
    status: StatusUiState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(PanelBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (status.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = StatusPrimary
                )
            }
            Text(
                text = status.title,
                color = StatusPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = status.detail,
            color = StatusSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CommandMenu(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(min = 180.dp)
            .background(PanelBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = title,
            color = StatusSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        labels.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Text(
                text = if (isSelected) "> $label" else "  $label",
                color = if (isSelected) StatusPrimary else StatusSecondary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(top = if (index == 0) 6.dp else 4.dp)
            )
        }
    }
}
