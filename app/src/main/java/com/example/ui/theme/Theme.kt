package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = Color(0xFF3B82F6),        // Blue 500
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A), // Blue 900
    onPrimaryContainer = Color(0xFF93C5FD), // Blue 300
    secondary = Color(0xFF94A3B8),      // Slate 400
    onSecondary = Color(0xFF0F172A),
    background = Color(0xFF0F172A),     // Slate 900
    onBackground = Color(0xFFF8FAFC),   // Slate 50
    surface = Color(0xFF1E293B),        // Slate 800
    onSurface = Color(0xFFF1F5F9),      // Slate 100
    surfaceVariant = Color(0xFF334155),  // Slate 700
    onSurfaceVariant = Color(0xFF94A3B8), // Slate 400
    outlineVariant = Color(0xFF475569)   // Slate 600
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF2563EB),        // Blue 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE), // Blue 100 / cool active button bg
    onPrimaryContainer = Color(0xFF1D4ED8), // Blue 700 / text
    secondary = Color(0xFF64748B),      // Slate 500
    onSecondary = Color.White,
    tertiary = Color(0xFF0D9488),       // Teal 600
    background = Color(0xFFF7F9FB),     // Slate-50-ish white bg
    onBackground = Color(0xFF0F172A),   // Slate 900
    surface = Color(0xFFFFFFFF),        // Card background
    onSurface = Color(0xFF1E293B),      // Slate 800
    surfaceVariant = Color(0xFFF1F5F9),  // Slate 100 for table header/tab inactive
    onSurfaceVariant = Color(0xFF64748B), // Slate 500
    outlineVariant = Color(0xFFE2E8F0)   // Slate 200
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disable dynamic colors by default so that the custom Professional Polish theme is preserved
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
