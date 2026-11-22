package com.example.bsa.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bsa.engine.CreditIntelligenceSummary
import com.example.bsa.engine.EligibilityStatus
import com.example.bsa.engine.SegmentEligibilityResult
import com.example.bsa.model.AnalysisReport
import com.example.bsa.model.ProductType
import com.example.bsa.service.EligibilityCalculatorService
import com.example.bsa.service.SummarizedMetricsInput
import com.example.ui.theme.JetBrainsMono
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Interactive 4-Segment Loan Eligibility Matrix Card:
 * Displays distinct Indian banking/NBFC underwriting calculations across
 * Auto Loan, Business Loan, Working Capital (OD/CC), and Home Loan.
 */
@Composable
fun SegmentEligibilityMatrixCard(
    report: AnalysisReport,
    intel: CreditIntelligenceSummary,
    useCompactCurrency: Boolean,
    onSimulateSegment: (ProductType) -> Unit,
    modifier: Modifier = Modifier,
    onExplainEligibility: ((SegmentEligibilityResult) -> Unit)? = null
) {
    var selectedType by remember(report.reportId, report.config.productType) {
        mutableStateOf(report.config.productType)
    }
    var showFormulaBreakdown by remember(report.reportId) { mutableStateOf(true) }

    val selectedSegment = remember(intel.segmentEligibilities, selectedType) {
        intel.segmentEligibilities.firstOrNull { it.productType == selectedType }
            ?: intel.primarySegmentEligibility
    }

    LedgerCard(modifier = modifier.testTag("segment_eligibility_matrix_card")) {
        // Header + Auto-detected Borrower Profile Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.AccountBalance,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "4-Segment Loan Eligibility",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Tap any segment to inspect Indian banking formula",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onExplainEligibility != null) {
                InfoTooltipIconButton(
                    contentDescription = "Explain ${selectedSegment.productType.displayName} eligibility formula",
                    onClick = { onExplainEligibility(selectedSegment) },
                    modifier = Modifier.testTag("eligibility_tooltip_button")
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            ) {
                Text(
                    text = intel.borrowerProfile.label.substringBefore(" /"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 2x2 Interactive Segment Grid (narrow-screen safe, zero truncation)
        val rows = remember(intel.segmentEligibilities) { intel.segmentEligibilities.chunked(2) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { rowSegments ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowSegments.forEach { seg ->
                        SegmentTile(
                            segment = seg,
                            isSelected = seg.productType == selectedSegment.productType,
                            isReportPrimary = seg.productType == report.config.productType,
                            useCompactCurrency = useCompactCurrency,
                            onClick = { selectedType = seg.productType },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowSegments.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Selected Segment Deep-Dive Panel
        SegmentDeepDivePanel(
            segment = selectedSegment,
            useCompactCurrency = useCompactCurrency,
            showBreakdown = showFormulaBreakdown,
            onToggleBreakdown = { showFormulaBreakdown = !showFormulaBreakdown },
            onSimulate = { onSimulateSegment(selectedSegment.productType) }
        )
    }
}

@Composable
private fun SegmentTile(
    segment: SegmentEligibilityResult,
    isSelected: Boolean,
    isReportPrimary: Boolean,
    useCompactCurrency: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when (segment.status) {
        EligibilityStatus.ELIGIBLE -> MaterialTheme.colorScheme.secondary
        EligibilityStatus.CONDITIONAL -> MaterialTheme.colorScheme.tertiary
        EligibilityStatus.INELIGIBLE -> MaterialTheme.colorScheme.error
    }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = modifier
            .heightIn(min = 84.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("segment_tile_${segment.productType.name.lowercase()}"),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(if (isSelected) 1.75.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = segmentIcon(segment.productType),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = segmentDisplayLabel(segment.productType),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isReportPrimary) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "CASE",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Text(
                text = BsaFormatters.formatCurrency(segment.recommendedAmount, useCompactCurrency),
                fontFamily = JetBrainsMono,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (segment.recommendedAmount > 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val shortStatus = when (segment.status) {
                    EligibilityStatus.ELIGIBLE -> "Eligible"
                    EligibilityStatus.CONDITIONAL -> "Review"
                    EligibilityStatus.INELIGIBLE -> "Ineligible"
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = statusColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = shortStatus,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                    )
                }
                Text(
                    text = if (segment.isRevolvingFacility) "OD/CC" else "${segment.benchmarkTenureMonths / 12}y",
                    fontFamily = JetBrainsMono,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun SegmentDeepDivePanel(
    segment: SegmentEligibilityResult,
    useCompactCurrency: Boolean,
    showBreakdown: Boolean,
    onToggleBreakdown: () -> Unit,
    onSimulate: () -> Unit
) {
    val (statusBg, statusFg) = when (segment.status) {
        EligibilityStatus.ELIGIBLE ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        EligibilityStatus.CONDITIONAL ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        EligibilityStatus.INELIGIBLE ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Header: Segment Name + Program + Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${segmentDisplayLabel(segment.productType)} Eligibility",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = segment.programName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = statusBg
                ) {
                    Text(
                        text = segment.status.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusFg,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            // Recommended Sanction Band Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SegmentMiniStat(
                    label = "Conservative",
                    value = BsaFormatters.formatCurrency(segment.conservativeAmount, useCompactCurrency),
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SegmentMiniStat(
                    label = "Recommended",
                    value = BsaFormatters.formatCurrency(segment.recommendedAmount, useCompactCurrency),
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                SegmentMiniStat(
                    label = "Max Band",
                    value = BsaFormatters.formatCurrency(segment.maxEligibleAmount, useCompactCurrency),
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }

            // Benchmark Terms & Servicing Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SegmentMiniStat(
                    label = if (segment.isRevolvingFacility) "Facility Terms" else "Benchmark Terms",
                    value = if (segment.isRevolvingFacility) {
                        "OD/CC @ ${String.format(Locale.ROOT, "%.2f%%", segment.benchmarkRatePct)}"
                    } else {
                        "${segment.benchmarkTenureMonths}m @ ${String.format(Locale.ROOT, "%.2f%%", segment.benchmarkRatePct)}"
                    },
                    accent = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                SegmentMiniStat(
                    label = if (segment.isRevolvingFacility) "Est. Int (@80% Util)" else "Est. Monthly EMI",
                    value = "${BsaFormatters.formatCurrency(segment.maxMonthlyServicing, useCompactCurrency)}/mo",
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
            }

            // Working Capital Enhancement Headroom Banner (if existing OD/CC limit exists)
            segment.existingFacilityGap?.let { gap ->
                val isPositive = gap > 0.0
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isPositive) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isPositive) Icons.Filled.TrendingUp else Icons.Filled.Info,
                            contentDescription = null,
                            tint = if (isPositive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (isPositive) {
                                "Incremental OD/CC Enhancement Headroom: +${BsaFormatters.formatCurrency(gap, useCompactCurrency)} over existing limit"
                            } else {
                                "Existing OD/CC limit already meets 20% BTO norm — recommend retention / renewal"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Limiting Factor Callout
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Rule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp)
                )
                Text(
                    text = "Limiting Factor: ${segment.limitingFactor}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Collapsible Formula Breakdown & Policy Notes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleBreakdown)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Formula Breakdown & Policy Norms",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (showBreakdown) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Toggle formula breakdown",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = showBreakdown) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    segment.breakdownRows.forEach { (label, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = value,
                                fontFamily = JetBrainsMono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.End,
                                modifier = Modifier.widthIn(max = 180.dp)
                            )
                        }
                    }
                    if (segment.policyNotes.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        segment.policyNotes.forEach { note ->
                            Text(
                                text = "• $note",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Action CTA: Open Segment-Specific Simulator
            YourViewSecondaryButton(
                text = "Simulate ${segmentDisplayLabel(segment.productType)} in Calculator",
                icon = Icons.Filled.Calculate,
                onClick = onSimulate,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("simulate_segment_button")
            )
        }
    }
}

@Composable
private fun SegmentMiniStat(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun segmentDisplayLabel(type: ProductType): String = when (type) {
    ProductType.AUTO_LOAN -> "Auto Loan"
    ProductType.BUSINESS_LOAN -> "Business Loan"
    ProductType.WORKING_CAPITAL -> "Working Capital"
    ProductType.HOME_LOAN -> "Home Loan"
}

fun segmentIcon(type: ProductType): ImageVector = when (type) {
    ProductType.AUTO_LOAN -> Icons.Filled.DirectionsCar
    ProductType.BUSINESS_LOAN -> Icons.Filled.BusinessCenter
    ProductType.WORKING_CAPITAL -> Icons.Filled.AccountBalanceWallet
    ProductType.HOME_LOAN -> Icons.Filled.Home
}

/**
 * Interactive YourView Quick Eligibility Calculator powered by [EligibilityCalculatorService].
 * Pre-populated from the active report's summarized transaction metrics (Total Inflow, Total Outflow,
 * Average Balance, OD Utilization %, Internal Credits, Cheque Bounces, and EMI Returns) so underwriters
 * can tweak metrics and test instant 4-segment funding eligibility.
 */
@Composable
fun QuickMetricsEligibilityCalculatorCard(
    report: AnalysisReport,
    useCompactCurrency: Boolean,
    modifier: Modifier = Modifier
) {
    val baseline = remember(report.reportId, report.transactions.size) {
        EligibilityCalculatorService.calculateFromReport(report).input
    }
    var expanded by remember(report.reportId) { mutableStateOf(false) }
    var inflowInput by remember(baseline) { mutableStateOf(baseline.totalInflow.roundToLong().toString()) }
    var outflowInput by remember(baseline) { mutableStateOf(baseline.totalOutflow.roundToLong().toString()) }
    var avgBalInput by remember(baseline) { mutableStateOf(baseline.averageBalance.roundToLong().toString()) }
    var odUtilInput by remember(baseline) { mutableStateOf(baseline.odUtilizationPct.roundToLong().toString()) }
    var internalCrInput by remember(baseline) { mutableStateOf(baseline.internalCreditsTotal.roundToLong().toString()) }
    var chqBounceInput by remember(baseline) { mutableStateOf(baseline.chequeBounceCount.toString()) }
    var emiReturnInput by remember(baseline) { mutableStateOf(baseline.emiReturnCount.toString()) }

    val quickResult = remember(
        inflowInput, outflowInput, avgBalInput, odUtilInput, internalCrInput, chqBounceInput, emiReturnInput, baseline
    ) {
        EligibilityCalculatorService.calculateFromMetrics(
            baseline.copy(
                totalInflow = inflowInput.toDoubleOrNull() ?: 0.0,
                totalOutflow = outflowInput.toDoubleOrNull() ?: 0.0,
                averageBalance = avgBalInput.toDoubleOrNull() ?: 0.0,
                odUtilizationPct = odUtilInput.toDoubleOrNull() ?: 0.0,
                internalCreditsTotal = internalCrInput.toDoubleOrNull() ?: 0.0,
                chequeBounceCount = chqBounceInput.toIntOrNull() ?: 0,
                emiReturnCount = emiReturnInput.toIntOrNull() ?: 0
            )
        )
    }

    LedgerCard(modifier = modifier.testTag("quick_eligibility_calculator_card")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            ) {
                Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "YourView Quick Eligibility Calculator",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Adjust Inflow, Outflow, Avg Bal & OD Util % for instant 4-segment eligibility",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = "Toggle quick eligibility calculator",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickMetricField(
                        label = "Total Inflow (₹)",
                        value = inflowInput,
                        onValueChange = { inflowInput = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.weight(1f)
                    )
                    QuickMetricField(
                        label = "Total Outflow (₹)",
                        value = outflowInput,
                        onValueChange = { outflowInput = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickMetricField(
                        label = "Avg Balance (₹)",
                        value = avgBalInput,
                        onValueChange = { avgBalInput = it.filter { c -> c.isDigit() || c == '-' } },
                        modifier = Modifier.weight(1f)
                    )
                    QuickMetricField(
                        label = "OD Util (%)",
                        value = odUtilInput,
                        onValueChange = { odUtilInput = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickMetricField(
                        label = "Internal Cr (₹)",
                        value = internalCrInput,
                        onValueChange = { internalCrInput = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.weight(1f)
                    )
                    QuickMetricField(
                        label = "Chq Bounces",
                        value = chqBounceInput,
                        onValueChange = { chqBounceInput = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.weight(1f)
                    )
                }
                QuickMetricField(
                    label = "EMI / NACH Returns (Count)",
                    value = emiReturnInput,
                    onValueChange = { emiReturnInput = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Haircut: ${quickResult.riskHaircutSummary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (quickResult.riskHaircutPct > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold
                        )
                        quickResult.segments.forEach { seg ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = seg.productType.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = BsaFormatters.formatCurrency(seg.recommendedAmount, useCompactCurrency),
                                    fontFamily = JetBrainsMono,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (seg.recommendedAmount > 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                YourViewOutlinedButton(
                    text = "Reset to Statement Metrics",
                    icon = Icons.Filled.RestartAlt,
                    onClick = {
                        inflowInput = baseline.totalInflow.roundToLong().toString()
                        outflowInput = baseline.totalOutflow.roundToLong().toString()
                        avgBalInput = baseline.averageBalance.roundToLong().toString()
                        odUtilInput = baseline.odUtilizationPct.roundToLong().toString()
                        internalCrInput = baseline.internalCreditsTotal.roundToLong().toString()
                        chqBounceInput = baseline.chequeBounceCount.toString()
                        emiReturnInput = baseline.emiReturnCount.toString()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun QuickMetricField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono),
        modifier = modifier
    )
}
