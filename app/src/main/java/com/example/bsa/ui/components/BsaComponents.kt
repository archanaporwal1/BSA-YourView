package com.example.bsa.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.bsa.model.ForensicStatus
import com.example.bsa.model.FraudIndicatorResult
import com.example.bsa.model.IndicatorGroup
import com.example.bsa.model.IndicatorStatus
import com.example.bsa.service.FraudScreeningService
import com.example.bsa.service.ScreeningRiskTier
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.SovereignNavy
import com.example.ui.theme.SovereignNavyLight
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

@Composable
fun BrandTopHeader(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    isOnlineBoostActive: Boolean = false,
    hasUpdateAvailable: Boolean = false,
    onOpenUpdate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(SovereignNavy, SovereignNavyLight)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    YvBrandLogoBadge(size = 42.dp, cornerRadius = 11.dp)
                    Column {
                        Text(
                            text = stringResource(R.string.brand_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.brand_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFBFDBFE),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (hasUpdateAvailable) {
                        IconButton(
                            onClick = onOpenUpdate,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("header_update_badge")
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0x33F59E0B),
                                border = BorderStroke(1.dp, Color(0x99FBBF24))
                            ) {
                                Box(
                                    modifier = Modifier.size(34.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SystemUpdate,
                                        contentDescription = "New app update available",
                                        tint = Color(0xFFFDE68A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isOnlineBoostActive) Color(0x330EA5E9) else Color(0x2910B981),
                        border = BorderStroke(
                            1.dp,
                            if (isOnlineBoostActive) Color(0x8838BDF8) else Color(0x6634D399)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isOnlineBoostActive) Color(0xFF38BDF8) else Color(0xFF34D399))
                            )
                            Text(
                                text = if (isOnlineBoostActive) "ONLINE BOOST" else "OFFLINE",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOnlineBoostActive) Color(0xFFE0F2FE) else Color(0xFFD1FAE5),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Quick Dark / Light Mode Toggle
                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("theme_mode_toggle")
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0x26FFFFFF),
                            border = BorderStroke(1.dp, Color(0x4DFFFFFF))
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                    contentDescription = stringResource(R.string.cd_toggle_theme),
                                    tint = if (isDarkTheme) Color(0xFFFDE68A) else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YvBrandLogoBadge(
    size: androidx.compose.ui.unit.Dp = 40.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(cornerRadius),
        color = Color(0xFF131720),
        border = BorderStroke(1.dp, Color(0x66E4C16F))
    ) {
        Image(
            painter = painterResource(id = R.drawable.yv_brand_logo),
            contentDescription = stringResource(R.string.cd_brand_logo),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius))
        )
    }
}

@Composable
fun YvBrandEndorsementFooter(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        YvBrandLogoBadge(size = 24.dp, cornerRadius = 6.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "BSA-YourView • YourView (yourview.in) • 100% Offline",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PasswordPromptDialog(
    fileName: String,
    passwordInput: String,
    errorText: String?,
    isVerifying: Boolean,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        modifier = Modifier.testTag("password_popup_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.password_dialog_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "This bank statement PDF is encrypted. Enter its password to unlock it in memory:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = onPasswordChange,
                    label = { Text("Enter PDF Password") },
                    singleLine = true,
                    enabled = !isVerifying,
                    isError = errorText != null,
                    supportingText = errorText?.let { err -> { Text(err) } },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_password)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("password_popup_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = !isVerifying,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("password_popup_unlock")
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Unlocking...")
                } else {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.password_dialog_unlock))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isVerifying,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(stringResource(R.string.password_dialog_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerRow(
    startDateIso: String?,
    endDateIso: String?,
    onStartDateSelected: (String?) -> Unit,
    onEndDateSelected: (String?) -> Unit,
    onClearDates: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DateFieldBox(
                label = stringResource(R.string.label_from_date),
                displayValue = BsaFormatters.formatIsoToDisplay(startDateIso) ?: "Start of statement",
                isActive = startDateIso != null,
                onClick = { pickingStart = true },
                modifier = Modifier
                    .weight(1f)
                    .testTag("date_filter_from_button")
            )
            DateFieldBox(
                label = stringResource(R.string.label_to_date),
                displayValue = BsaFormatters.formatIsoToDisplay(endDateIso) ?: "End of statement",
                isActive = endDateIso != null,
                onClick = { pickingEnd = true },
                modifier = Modifier
                    .weight(1f)
                    .testTag("date_filter_to_button")
            )
        }

        if (startDateIso != null || endDateIso != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Active exact range: ${BsaFormatters.formatIsoToDisplay(startDateIso) ?: "Beginning"} -> ${BsaFormatters.formatIsoToDisplay(endDateIso) ?: "Latest"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(
                    onClick = onClearDates,
                    modifier = Modifier.testTag("date_filter_clear_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_clear_dates))
                }
            }
        }
    }

    if (pickingStart) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = BsaFormatters.isoToUtcMillis(startDateIso)
        )
        DatePickerDialog(
            onDismissRequest = { pickingStart = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = state.selectedDateMillis
                        onStartDateSelected(millis?.let { BsaFormatters.utcMillisToIso(it) })
                        pickingStart = false
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { pickingStart = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(
                state = state,
                title = {
                    Text(
                        text = "Select Exact From Date",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp)
                    )
                }
            )
        }
    }

    if (pickingEnd) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = BsaFormatters.isoToUtcMillis(endDateIso)
                ?: BsaFormatters.isoToUtcMillis(startDateIso)
        )
        DatePickerDialog(
            onDismissRequest = { pickingEnd = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = state.selectedDateMillis
                        onEndDateSelected(millis?.let { BsaFormatters.utcMillisToIso(it) })
                        pickingEnd = false
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { pickingEnd = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(
                state = state,
                title = {
                    Text(
                        text = "Select Exact To Date",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun DateFieldBox(
    label: String,
    displayValue: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.CalendarMonth,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = displayValue,
                    fontFamily = if (isActive) JetBrainsMono else MaterialTheme.typography.bodyMedium.fontFamily,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 12.5.sp,
                    color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun LedgerCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
fun ForensicStatusPill(status: ForensicStatus) {
    val (bg, fg, icon) = when (status) {
        ForensicStatus.VERIFIED -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Filled.Shield
        )
        ForensicStatus.WARNING -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Filled.Warning
        )
        ForensicStatus.REJECTED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Error
        )
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun IndicatorStatusPill(status: IndicatorStatus) {
    val (bg, fg, icon) = when (status) {
        IndicatorStatus.CLEAN -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Filled.CheckCircle
        )
        IndicatorStatus.FLAGGED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Warning
        )
        IndicatorStatus.SUPPRESSED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Info
        )
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun KpiMetricTile(
    label: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(min = 152.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Dashboard Risk Score Summary Card derived from the 10-point Fraud & Behavioural Screening engine.
 * Color-coded by tier (Low = Emerald, Moderate = Amber, High = Crimson) with animated radial gauge,
 * per-group counts, 10-checkpoint micro-bar, and direct navigation to the 10 Triggers tab.
 */
@Composable
fun RiskScoreSummaryCard(
    indicators: List<FraudIndicatorResult>,
    onInspectTriggers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = remember(indicators) { FraudScreeningService.computeRiskSummary(indicators) }
    val cleanCount = remember(indicators) { indicators.count { it.status == IndicatorStatus.CLEAN } }
    val suppressedCount = remember(indicators) { indicators.count { it.status == IndicatorStatus.SUPPRESSED } }

    val accentColor = when (summary.riskTier) {
        ScreeningRiskTier.LOW -> MaterialTheme.colorScheme.secondary
        ScreeningRiskTier.MODERATE -> MaterialTheme.colorScheme.tertiary
        ScreeningRiskTier.HIGH -> MaterialTheme.colorScheme.error
    }
    val containerTint = when (summary.riskTier) {
        ScreeningRiskTier.LOW -> MaterialTheme.colorScheme.secondaryContainer
        ScreeningRiskTier.MODERATE -> MaterialTheme.colorScheme.tertiaryContainer
        ScreeningRiskTier.HIGH -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainerTint = when (summary.riskTier) {
        ScreeningRiskTier.LOW -> MaterialTheme.colorScheme.onSecondaryContainer
        ScreeningRiskTier.MODERATE -> MaterialTheme.colorScheme.onTertiaryContainer
        ScreeningRiskTier.HIGH -> MaterialTheme.colorScheme.onErrorContainer
    }
    val tierIcon = when (summary.riskTier) {
        ScreeningRiskTier.LOW -> Icons.Filled.VerifiedUser
        ScreeningRiskTier.MODERATE -> Icons.Filled.WarningAmber
        ScreeningRiskTier.HIGH -> Icons.Filled.GppBad
    }
    val verdictHeadline = when (summary.riskTier) {
        ScreeningRiskTier.LOW -> "Clean profile — low underwriting anomaly exposure"
        ScreeningRiskTier.MODERATE -> "Review advised — behavioural or isolated fraud flags"
        ScreeningRiskTier.HIGH -> "High alert — multiple forensic / fraud triggers flagged"
    }

    val animatedProgress by animateFloatAsState(
        targetValue = (summary.riskScore / 100f).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "risk_score_arc"
    )
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("risk_score_summary_card"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.25.dp, accentColor.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            containerTint.copy(alpha = 0.30f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Animated Circular Risk Score Gauge (0-100)
                Box(
                    modifier = Modifier
                        .size(82.dp)
                        .semantics {
                            contentDescription = "Risk Score ${summary.riskScore} out of 100, ${summary.riskTier.label}"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokePx = 8.dp.toPx()
                        val arcSize = Size(size.width - strokePx, size.height - strokePx)
                        val topLeft = Offset(strokePx / 2f, strokePx / 2f)

                        drawArc(
                            color = trackColor,
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                        val sweep = (270f * animatedProgress).coerceAtLeast(if (summary.riskScore == 0) 6f else 12f)
                        drawArc(
                            color = accentColor,
                            startAngle = 135f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${summary.riskScore}",
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp,
                            color = accentColor,
                            modifier = Modifier.testTag("risk_score_value")
                        )
                        Text(
                            text = "/ 100",
                            fontFamily = JetBrainsMono,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Right Column: Risk Tier Badge + Breakdown
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "10-Point Risk Score",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = containerTint
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = tierIcon,
                                    contentDescription = null,
                                    tint = onContainerTint,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = summary.riskTier.label.uppercase(Locale.ROOT),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = onContainerTint
                                )
                            }
                        }
                    }

                    Text(
                        text = verdictHeadline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RiskCountChip(
                            label = "Fraud ${summary.fraudFlaggedCount}/5",
                            color = if (summary.fraudFlaggedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                        )
                        RiskCountChip(
                            label = "Behav ${summary.behaviouralFlaggedCount}/5",
                            color = if (summary.behaviouralFlaggedCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                        )
                        RiskCountChip(
                            label = if (suppressedCount > 0) "$cleanCount Clean • $suppressedCount OD" else "$cleanCount Clean",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 10-Checkpoint Visual Micro-Strip (Items 1..10)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val ordered = indicators.sortedBy { it.itemNo }
                    ordered.forEach { ind ->
                        val segColor = when (ind.status) {
                            IndicatorStatus.CLEAN -> MaterialTheme.colorScheme.secondary
                            IndicatorStatus.SUPPRESSED -> MaterialTheme.colorScheme.outlineVariant
                            IndicatorStatus.FLAGGED -> if (ind.group == IndicatorGroup.FRAUD) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(segColor)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${summary.flaggedCount} of 10 triggers flagged (1–5 Fraud, 6–10 Behavioural)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(
                        onClick = onInspectTriggers,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("inspect_fraud_triggers_button")
                    ) {
                        Text("Inspect 10 Triggers", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Inspect 10 fraud triggers",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskCountChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMono,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

/**
 * Real-time LinearProgressIndicator panel shown while generating the 11-tab Excel (.xlsx) workbook.
 */
@Composable
fun ExcelExportProgressPanel(
    visible: Boolean,
    progress: Float,
    stageMessage: String,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120),
        label = "excel_export_linear_progress"
    )
    AnimatedVisibility(visible = visible, modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TableChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stageMessage.ifBlank { "Generating 11-tab Excel workbook..." },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("excel_export_stage_label")
                        )
                    }
                    Text(
                        text = "${(animatedProgress * 100).toInt()}%",
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .testTag("excel_export_progress"),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}

object BsaFormatters {

    /**
     * Formats numbers using the Indian numbering system (e.g., ₹14,79,500.00).
     */
    fun formatInr(amount: Double, showSign: Boolean = false): String {
        val negative = amount < 0
        val absVal = abs(amount)
        val intPart = absVal.toLong()
        val fracPart = ((absVal - intPart) * 100.0 + 0.5).toInt().coerceIn(0, 99)

        val intStr = intPart.toString()
        val formattedInt = if (intStr.length <= 3) {
            intStr
        } else {
            val last3 = intStr.takeLast(3)
            val rest = intStr.dropLast(3)
            val grouped = rest.reversed().chunked(2).joinToString(",").reversed()
            "$grouped,$last3"
        }

        val prefix = when {
            negative -> "-₹"
            showSign && absVal > 0.0 -> "+₹"
            else -> "₹"
        }
        return String.format(Locale.ROOT, "%s%s.%02d", prefix, formattedInt, fracPart)
    }

    fun formatInrCompact(amount: Double): String {
        val neg = amount < 0
        val a = abs(amount)
        val sign = if (neg) "-" else ""
        return when {
            a >= 1_00_00_000.0 -> String.format(Locale.ROOT, "%s₹%.2f Cr", sign, a / 1_00_00_000.0)
            a >= 1_00_000.0 -> String.format(Locale.ROOT, "%s₹%.2f L", sign, a / 1_00_000.0)
            a >= 1_000.0 -> String.format(Locale.ROOT, "%s₹%.1f K", sign, a / 1_000.0)
            else -> String.format(Locale.ROOT, "%s₹%.0f", sign, a)
        }
    }

    fun formatCurrency(amount: Double, compact: Boolean): String {
        return if (compact) formatInrCompact(amount) else formatInr(amount)
    }

    fun formatPercent(pct: Double): String {
        val df = DecimalFormat("0.00", DecimalFormatSymbols(Locale.US))
        return "${df.format(pct)}%"
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes <= 0L -> "Local ePDF"
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "${bytes / 1024} KB"
            else -> String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun formatTimestamp(millis: Long): String {
        return SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(millis))
    }

    fun formatIsoToDisplay(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val out = SimpleDateFormat("dd-MMM-yyyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = parser.parse(iso) ?: return iso
            out.format(parsed)
        }.getOrDefault(iso)
    }

    fun isoToUtcMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(iso)?.time
        }.getOrNull()
    }

    fun utcMillisToIso(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))
    }
}
