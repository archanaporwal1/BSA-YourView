package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val InstitutionalLightScheme = lightColorScheme(
    primary = SovereignNavy,
    onPrimary = Color.White,
    primaryContainer = SovereignNavyContainer,
    onPrimaryContainer = OnSovereignNavyContainer,
    secondary = AuditEmerald,
    onSecondary = Color.White,
    secondaryContainer = AuditEmeraldContainer,
    onSecondaryContainer = OnAuditEmeraldContainer,
    tertiary = LedgerAmber,
    onTertiary = Color.White,
    tertiaryContainer = LedgerAmberContainer,
    onTertiaryContainer = OnLedgerAmberContainer,
    error = ForensicCrimson,
    onError = Color.White,
    errorContainer = ForensicCrimsonContainer,
    onErrorContainer = OnForensicCrimsonContainer,
    background = PaperSlateBackground,
    onBackground = LedgerInk,
    surface = CrispLedgerSurface,
    onSurface = LedgerInk,
    surfaceVariant = LedgerSurfaceVariant,
    onSurfaceVariant = LedgerMutedInk,
    outline = LedgerBorder,
    outlineVariant = LedgerHairline
)

private val InstitutionalDarkScheme = darkColorScheme(
    primary = DarkTerminalPrimary,
    onPrimary = DarkTerminalOnPrimary,
    primaryContainer = DarkTerminalPrimaryContainer,
    onPrimaryContainer = DarkTerminalInk,
    secondary = DarkTerminalSecondary,
    onSecondary = DarkTerminalOnSecondary,
    secondaryContainer = DarkTerminalSecondaryContainer,
    onSecondaryContainer = DarkTerminalInk,
    tertiary = DarkTerminalTertiary,
    onTertiary = Color(0xFF2B1700),
    tertiaryContainer = DarkTerminalTertiaryContainer,
    onTertiaryContainer = Color(0xFFFEF0D6),
    error = DarkTerminalError,
    onError = Color(0xFF3E0404),
    errorContainer = DarkTerminalErrorContainer,
    onErrorContainer = Color(0xFFFDE2E2),
    background = DarkTerminalBackground,
    onBackground = DarkTerminalInk,
    surface = DarkTerminalSurface,
    onSurface = DarkTerminalInk,
    surfaceVariant = DarkTerminalSurfaceVariant,
    onSurfaceVariant = DarkTerminalMutedInk,
    outline = DarkTerminalBorder,
    outlineVariant = Color(0xFF1F2D42)
)

val InstitutionalShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun BankAnalysierTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) InstitutionalDarkScheme else InstitutionalLightScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = InstitutionalShapes,
        content = content
    )
}
