package com.avas.bedtime.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class AppThemeId(val storageKey: String, val label: String) {
    Night("night", "Night sky"),
    Unicorn("unicorn", "Unicorn"),
    Rainbow("rainbow", "Rainbow mist"),
    Ocean("ocean", "Ocean dreams");

    companion object {
        fun fromStorage(key: String): AppThemeId =
            entries.firstOrNull { it.storageKey == key } ?: Unicorn
    }
}

data class BedtimeThemeColors(
    val id: AppThemeId,
    val background: Brush,
    val title: Color,
    val subtitle: Color,
    val body: Color,
    val startButton: Color,
    val startButtonDisabled: Color,
    val stopButton: Color,
    val buttonText: Color,
    val settingsBar: Color,
    val settingsText: Color,
    val accentChip: Color
)

fun themeColors(id: AppThemeId): BedtimeThemeColors = when (id) {
    AppThemeId.Night -> BedtimeThemeColors(
        id = id,
        background = Brush.verticalGradient(
            listOf(Color(0xFF0F1A28), Color(0xFF1A2F44), Color(0xFF0F1A28))
        ),
        title = Color(0xFFF2E8D5),
        subtitle = Color(0xFFC4A574),
        body = Color(0xFFD5CBB8),
        startButton = Color(0xFF3D7A7A),
        startButtonDisabled = Color(0xFF3A4555),
        stopButton = Color(0xFF8B4A4A),
        buttonText = Color(0xFFF2E8D5),
        settingsBar = Color(0xFF152233),
        settingsText = Color(0xFFD5CBB8),
        accentChip = Color(0xFF243044)
    )
    AppThemeId.Unicorn -> BedtimeThemeColors(
        id = id,
        background = Brush.verticalGradient(
            listOf(
                Color(0xFFFFE6F2),
                Color(0xFFE8D9FF),
                Color(0xFFD9F3FF),
                Color(0xFFFFF0D6)
            )
        ),
        title = Color(0xFF6B3A6E),
        subtitle = Color(0xFFC45C9A),
        body = Color(0xFF5A4A6A),
        startButton = Color(0xFFFF7EB9),
        startButtonDisabled = Color(0xFFC9B0C4),
        stopButton = Color(0xFFE56B6B),
        buttonText = Color(0xFFFFFBFF),
        settingsBar = Color(0xFFB8A0D9),
        settingsText = Color(0xFF4A3560),
        accentChip = Color(0xFFFFD6EC)
    )
    AppThemeId.Rainbow -> BedtimeThemeColors(
        id = id,
        background = Brush.verticalGradient(
            listOf(
                Color(0xFFFFADB8),
                Color(0xFFFFD39A),
                Color(0xFFFFF3A3),
                Color(0xFFB8F0C8),
                Color(0xFFA8D8FF)
            )
        ),
        title = Color(0xFF3D2C5C),
        subtitle = Color(0xFF6B3FA0),
        body = Color(0xFF3F3A55),
        startButton = Color(0xFF7EC8E3),
        startButtonDisabled = Color(0xFFA9B4C0),
        stopButton = Color(0xFFFF8B7B),
        buttonText = Color(0xFFFFFBFF),
        settingsBar = Color(0xFF5C4B8A),
        settingsText = Color(0xFFFFF5FF),
        accentChip = Color(0xFFFFE0A8)
    )
    AppThemeId.Ocean -> BedtimeThemeColors(
        id = id,
        background = Brush.verticalGradient(
            listOf(Color(0xFF0B3A4A), Color(0xFF1B6B7A), Color(0xFF3FA0A8), Color(0xFF7ED6C5))
        ),
        title = Color(0xFFE8FFF8),
        subtitle = Color(0xFFB8F0E8),
        body = Color(0xFFD5F5F0),
        startButton = Color(0xFF2BBBAD),
        startButtonDisabled = Color(0xFF4A7070),
        stopButton = Color(0xFFD97070),
        buttonText = Color(0xFFE8FFF8),
        settingsBar = Color(0xFF0A2E38),
        settingsText = Color(0xFFD5F5F0),
        accentChip = Color(0xFF1B5560)
    )
}
