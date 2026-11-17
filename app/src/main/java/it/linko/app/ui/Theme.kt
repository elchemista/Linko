package it.linko.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF101014)
val Panel = Color(0xFF1C1C23)
val Muted = Color(0xFF96939F)
val Lavender = Color(0xFFBEAAFF)
val Line = Color(0xFF30303A)

/** Material 3 sheets use a separate window; keep its system icons readable in the dark theme. */
@Composable
fun DarkSheetBars() {
    val view = LocalView.current
    SideEffect {
        view.post {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }
}

@Composable
fun LinkoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Lavender, onPrimary = Color(0xFF261D3B),
            primaryContainer = Color(0xFF332942), onPrimaryContainer = Color(0xFFE7DEFF),
            secondary = Color(0xFFA4D5BF), background = Ink, onBackground = Color(0xFFF6F3FC),
            surface = Panel, onSurface = Color(0xFFF6F3FC),
            surfaceVariant = Color(0xFF27262F), onSurfaceVariant = Color(0xFFABA7B5),
            outline = Line, error = Color(0xFFFFB4AB)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp)
        ), content = content
    )
}
