package com.walkp.cadence.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
}

// 语义色都映射到 Material3 槽位，CadenceScreen 里不要再用硬编码颜色：
//   主数字/图表线 -> primary，强调绿 -> secondary
//   徽章底/字：步行 secondaryContainer，不稳 tertiaryContainer，未检测 surfaceVariant
//   次要文字 -> onSurfaceVariant，弱提示 -> outline，图表参考线 -> outlineVariant
private val LightColors = lightColorScheme(
    primary = Color(0xFF185FA5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F1FB),
    onPrimaryContainer = Color(0xFF0C447C),
    secondary = Color(0xFF0F6E56),
    secondaryContainer = Color(0xFFE1F5EE),
    onSecondaryContainer = Color(0xFF0F6E56),
    tertiaryContainer = Color(0xFFFAEEDA),
    onTertiaryContainer = Color(0xFF854F0B),
    surface = Color.White,
    onSurface = Color(0xFF2C2C2A),
    surfaceVariant = Color(0xFFF1EFE8),
    onSurfaceVariant = Color(0xFF5F5E5A),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF2C2C2A),
    outline = Color(0xFFB4B2A9),
    outlineVariant = Color(0xFFD3D1C7)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB3E8),
    onPrimary = Color(0xFF0B2B4A),
    primaryContainer = Color(0xFF1C3145),
    onPrimaryContainer = Color(0xFFC4DDF5),
    secondary = Color(0xFF4CC3A1),
    secondaryContainer = Color(0xFF14362A),
    onSecondaryContainer = Color(0xFF4CC3A1),
    tertiaryContainer = Color(0xFF3B2E14),
    onTertiaryContainer = Color(0xFFE0A84F),
    surface = Color(0xFF1E1E1B),
    onSurface = Color(0xFFE6E4DD),
    surfaceVariant = Color(0xFF2A2925),
    onSurfaceVariant = Color(0xFFA8A69D),
    background = Color(0xFF141412),
    onBackground = Color(0xFFE6E4DD),
    outline = Color(0xFF6E6C64),
    outlineVariant = Color(0xFF4A4843)
)

@Composable
fun WalkPTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // 状态栏/导航栏图标颜色跟随当前实际主题
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        WindowInsetsControllerCompat(window, view).run {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
