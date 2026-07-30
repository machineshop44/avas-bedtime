package com.avas.bedtime.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class AppThemeId(val storageKey: String, val label: String) {
    Night("night", "Night sky"),
    Unicorn("unicorn", "Unicorn"),
    Rainbow("rainbow", "Rainbow mist"),
    Ocean("ocean", "Ocean dreams"),
    Forest("forest", "Firefly forest"),
    Galaxy("galaxy", "Galaxy");

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
    val accentChip: Color,
    val photoRing: Color,
    val shadowTint: Color
)

fun themeColors(id: AppThemeId): BedtimeThemeColors = when (id) {
    AppThemeId.Night -> BedtimeThemeColors(
        id = id,
        background = Brush.verticalGradient(
            listOf(Color(0xFF0A121C), Color(0xFF152433), Color(0xFF1C3348), Color(0xFF0E1A26))
        ),
        title = Color(0xFFF4ECDF),
        subtitle = Color(0xFFD0B07E),
        body = Color(0xFFD8CFC0),
        startButton = Color(0xFF5A9E9A),
        startButtonDisabled = Color(0xFF3A4555),
        stopButton = Color(0xFFA86060),
        buttonText = Color(0xFFF4ECDF),
        settingsBar = Color(0xD9121E2C),
        settingsText = Color(0xFFD8CFC0),
        accentChip = Color(0xFF243044),
        photoRing = Color(0x44F4ECDF),
        shadowTint = Color(0x99000000)
    )
    AppThemeId.Unicorn -> BedtimeThemeColors(
        id = id,
        background = Brush.verticalGradient(
            listOf(
                Color(0xFFFFEAF4),
                Color(0xFFF0E4FF),
                Color(0xFFE4F4FF),
                Color(0xFFFFF4E4)
            )
        ),
        title = Color(0xFF5E3A68),
        subtitle = Color(0xFFB85A92),
        body = Color(0xFF5A4A6A),
        startButton = Color(0xFFFF8AC4),
        startButtonDisabled = Color(0xFFC9B0C4),
        stopButton = Color(0xFFE57A7A),
        buttonText = Color(0xFFFFFBFF),
        settingsBar = Color(0xD9C9B6E8),
        settingsText = Color(0xFF4A3560),
        accentChip = Color(0xFFFFD6EC),
        photoRing = Color(0xEEFFFFFF),
        shadowTint = Color(0x77000000)
    )
    AppThemeId.Rainbow -> BedtimeThemeColors(
        id = id,
        background = Brush.verticalGradient(
            listOf(
                Color(0xFFFFD0D6),
                Color(0xFFFFE4B8),
                Color(0xFFFFF8C8),
                Color(0xFFD4F7DE),
                Color(0xFFC8E8FF)
            )
        ),
        title = Color(0xFF3D2C5C),
        subtitle = Color(0xFF6B3FA0),
        body = Color(0xFF3F3A55),
        startButton = Color(0xFF74C4E0),
        startButtonDisabled = Color(0xFFA9B4C0),
        stopButton = Color(0xFFFF8B7B),
        buttonText = Color(0xFFFFFBFF),
        settingsBar = Color(0xD9786AA8),
        settingsText = Color(0xFFFFF5FF),
        accentChip = Color(0xFFFFE0A8),
        photoRing = Color(0xEEFFFFFF),
        shadowTint = Color(0x66000000)
    )
    AppThemeId.Ocean -> BedtimeThemeColors(
        id = id,
        background = Brush.verticalGradient(
            listOf(Color(0xFF083040), Color(0xFF166878), Color(0xFF3FA8B0), Color(0xFF9AE0D4))
        ),
        title = Color(0xFFE8FFF8),
        subtitle = Color(0xFFB8F0E8),
        body = Color(0xFFD5F5F0),
        startButton = Color(0xFF3ACABC),
        startButtonDisabled = Color(0xFF4A7070),
        stopButton = Color(0xFFD97070),
        buttonText = Color(0xFFE8FFF8),
        settingsBar = Color(0xD90A2E38),
        settingsText = Color(0xFFD5F5F0),
        accentChip = Color(0xFF1B5560),
        photoRing = Color(0x55E8FFF8),
        shadowTint = Color(0x99000000)
    )
    AppThemeId.Forest -> BedtimeThemeColors(
        id = id,
        background = Brush.verticalGradient(
            listOf(Color(0xFF132418), Color(0xFF1E3A28), Color(0xFF2F5A3A), Color(0xFF1A2E22))
        ),
        title = Color(0xFFE8F5E4),
        subtitle = Color(0xFFC6E08A),
        body = Color(0xFFD4E8D0),
        startButton = Color(0xFF6FBF6A),
        startButtonDisabled = Color(0xFF4A6050),
        stopButton = Color(0xFFC97858),
        buttonText = Color(0xFFF4FFF0),
        settingsBar = Color(0xD914281C),
        settingsText = Color(0xFFD4E8D0),
        accentChip = Color(0xFF2A4834),
        photoRing = Color(0x44E8F5E4),
        shadowTint = Color(0x99000000)
    )
    AppThemeId.Galaxy -> BedtimeThemeColors(
        id = id,
        background = Brush.verticalGradient(
            listOf(Color(0xFF10081C), Color(0xFF1C1040), Color(0xFF2A1860), Color(0xFF120C28))
        ),
        title = Color(0xFFF0E8FF),
        subtitle = Color(0xFFB8A0FF),
        body = Color(0xFFD8D0F0),
        startButton = Color(0xFF8B6CFF),
        startButtonDisabled = Color(0xFF4A4560),
        stopButton = Color(0xFFE070A8),
        buttonText = Color(0xFFF8F4FF),
        settingsBar = Color(0xD9181030),
        settingsText = Color(0xFFD8D0F0),
        accentChip = Color(0xFF2A1848),
        photoRing = Color(0x55F0E8FF),
        shadowTint = Color(0xAA000000)
    )
}
