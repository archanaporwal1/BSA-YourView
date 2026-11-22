package com.example.bsa.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JetBrainsMono

private val YourViewButtonShape = RoundedCornerShape(12.dp)
private val YourViewButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/**
 * Primary YourView branded filled button (Sovereign Navy / Primary).
 * Guarantees >= 48dp touch target, single-line ellipsized text, and zero vertical text squishing.
 */
@Composable
fun YourViewPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = YourViewButtonShape,
        contentPadding = YourViewButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        modifier = modifier.heightIn(min = 50.dp)
    ) {
        YourViewButtonContent(
            text = text,
            icon = icon,
            isLoading = isLoading,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/**
 * Accent YourView branded action button (Emerald Secondary) for primary execution & export CTAs.
 */
@Composable
fun YourViewAccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = YourViewButtonShape,
        contentPadding = YourViewButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary
        ),
        modifier = modifier.heightIn(min = 50.dp)
    ) {
        YourViewButtonContent(
            text = text,
            icon = icon,
            isLoading = isLoading,
            contentColor = MaterialTheme.colorScheme.onSecondary
        )
    }
}

/**
 * Secondary YourView branded tonal button with crisp border for benchmark/reference actions
 * (replaces generic/placeholder "Sample" buttons with a cohesive YourView surface).
 */
@Composable
fun YourViewSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = YourViewButtonShape,
        contentPadding = YourViewButtonPadding,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        YourViewButtonContent(
            text = text,
            icon = icon,
            isLoading = false,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * Outlined YourView branded button for secondary share, configure, or navigation actions.
 */
@Composable
fun YourViewOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = YourViewButtonShape,
        contentPadding = YourViewButtonPadding,
        border = BorderStroke(
            width = 1.25.dp,
            color = if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        YourViewButtonContent(
            text = text,
            icon = icon,
            isLoading = false,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Destructive YourView outlined button for irreversible actions like clearing saved cases.
 */
@Composable
fun YourViewDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = YourViewButtonShape,
        contentPadding = YourViewButtonPadding,
        border = BorderStroke(1.25.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        ),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        YourViewButtonContent(
            text = text,
            icon = icon,
            isLoading = false,
            contentColor = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun YourViewButtonContent(
    text: String,
    icon: ImageVector?,
    isLoading: Boolean,
    contentColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Consistent YourView section header with leading icon, ellipsized title, and optional trailing monospace badge.
 */
@Composable
fun YourViewSectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badgeText: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = iconTint.copy(alpha = 0.12f)
        ) {
            Box(
                modifier = Modifier.size(34.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!badgeText.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            ) {
                Text(
                    text = badgeText,
                    fontFamily = JetBrainsMono,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}
