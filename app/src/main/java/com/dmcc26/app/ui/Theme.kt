package com.dmcc26.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Saffron = Color(0xFFF37021)
val IndiaGreen = Color(0xFF138808)
val Navy = Color(0xFF0B3D91)

private val scheme = lightColorScheme(
    primary = IndiaGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F2D2),
    onPrimaryContainer = Color(0xFF0A3D05),
    secondary = Saffron,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3D1),
    onSecondaryContainer = Color(0xFF5A2300),
    tertiary = Navy,
    background = Color(0xFFFFFBF7),
    surface = Color(0xFFFFFBF7),
)

@Composable
fun DmccTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
