package de.robin.wein.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Farben für die Weinfarbe als Punkt vor jedem Eintrag. */
object WineColors {
    val Red = Color(0xFF7A1F3D)
    val White = Color(0xFFE3CF7A)
    val Rose = Color(0xFFE59AAE)
    val Sparkling = Color(0xFFB9C9D6)
    val Unknown = Color(0xFF9A9AA2)

    fun of(color: String): Color = when (color.lowercase()) {
        "rot" -> Red
        "weiß", "weiss" -> White
        "rosé", "rose" -> Rose
        "schaumwein" -> Sparkling
        else -> Unknown
    }
}

private val Light = lightColorScheme(
    primary = Color(0xFF6D1A36), onPrimary = Color.White,
    primaryContainer = Color(0xFFF3D9E0), onPrimaryContainer = Color(0xFF3A0718),
    secondary = Color(0xFF7A6420), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E3B5), onSecondaryContainer = Color(0xFF2A2000),
    background = Color(0xFFEEF0F2), onBackground = Color(0xFF1B1C20),
    surface = Color(0xFFF8F8FA), onSurface = Color(0xFF1B1C20),
    surfaceVariant = Color(0xFFDFE1E6), onSurfaceVariant = Color(0xFF4A4D55),
    outline = Color(0xFF8A8E98), outlineVariant = Color(0xFFC8CBD2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFEBA3B9), onPrimary = Color(0xFF4A0D22),
    primaryContainer = Color(0xFF5A1830), onPrimaryContainer = Color(0xFFF3D9E0),
    secondary = Color(0xFFE2CB7E), onSecondary = Color(0xFF3A2F00),
    secondaryContainer = Color(0xFF524411), onSecondaryContainer = Color(0xFFF6E7B8),
    background = Color(0xFF121216), onBackground = Color(0xFFE3E3E8),
    surface = Color(0xFF18181D), onSurface = Color(0xFFE3E3E8),
    surfaceVariant = Color(0xFF2B2C33), onSurfaceVariant = Color(0xFFBDBFC8),
    outline = Color(0xFF6F727C), outlineVariant = Color(0xFF3B3D45),
)

private val base = Typography()
private val Type = base.copy(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun WeinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
