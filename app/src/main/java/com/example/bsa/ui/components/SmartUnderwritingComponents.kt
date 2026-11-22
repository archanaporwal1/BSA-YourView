package com.example.bsa.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bsa.engine.CreditIntelligenceEngine
import com.example.bsa.engine.CreditIntelligenceSummary
import com.example.bsa.engine.FuzzyMatcherAndCategorizer
import com.example.bsa.engine.UnderwritingVerdict
import com.example.bsa.model.AnalysisReport
import com.example.bsa.model.BankTransaction
import com.example.bsa.model.ProductType
import com.example.ui.theme.JetBrainsMono
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Executive Creditworthiness & Underwriting Intelligence Card:
 * Displays Credit Health Score (0-100), Verdict, FOIR %, Checkpoint ABB, Cash Dependency %,
 * Bounce/Return Count, Conservative Eligibility Band, Strengths/Watch-outs, and 1-tap Copy/Share Brief.
 */
@Composable
fun CreditIntelligenceCard(
    report: AnalysisReport,
    intel: CreditIntelligenceSummary,
    useCompactCurrency: Boolean,
    onCopyBrief: () -> Unit,
    onShareBrief: () -> Unit,
    onOpenSimulator: () -> Unit,
    modifier: Modifier = Modifier,
    onExplainHealthScore: (() -> Unit)? = null
) {
    var showInsights by remember(report.reportId) { mutableStateOf(true) }

    val (verdictBg, verdictFg) = when (intel.verdict) {
        UnderwritingVerdict.STRONG_APPROVE ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        UnderwritingVerdict.CONDITIONAL_REVIEW ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        UnderwritingVerdict.HIGH_RISK_DECLINE ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }

    val scoreColor = when {
        intel.creditHealthScore >= 75 -> MaterialTheme.colorScheme.secondary
        intel.creditHealthScore >= 50 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    LedgerCard(modifier = modifier.testTag("credit_intelligence_card")) {
        // Header Row: Title + Verdict Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Insights,
                contentDescription = null,
                tint = scoreColor,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Credit Intelligence Verdict",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Health Score: ${intel.creditHealthScore} / 100 • ${report.config.productType.displayName}",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onExplainHealthScore != null) {
                InfoTooltipIconButton(
                    contentDescription = "Explain Credit Health Score formula",
                    onClick = onExplainHealthScore,
                    modifier = Modifier.testTag("credit_health_tooltip_button")
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = verdictBg
            ) {
                Text(
                    text = intel.verdict.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = verdictFg,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Credit Health Score Progress Bar
        LinearProgressIndicator(
            progress = { (intel.creditHealthScore / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = scoreColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        // 2x2 Underwriting KPI Grid (narrow-screen safe)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val foirColor = when {
                    intel.foirPercent > 65.0 -> MaterialTheme.colorScheme.error
                    intel.foirPercent > 50.0 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.secondary
                }
                IntelMetricTile(
                    label = "FOIR Ratio",
                    value = BsaFormatters.formatPercent(intel.foirPercent),
                    subtext = "EMI ${BsaFormatters.formatCurrency(intel.avgMonthlyEmiObligation, useCompactCurrency)}/mo",
                    accentColor = foirColor,
                    modifier = Modifier.weight(1f)
                )
                IntelMetricTile(
                    label = "Checkpoint ABB",
                    value = BsaFormatters.formatCurrency(intel.avgBankBalanceAbb, useCompactCurrency),
                    subtext = "2/10/20/End avg",
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val cashColor = when {
                    intel.cashDependencyPercent > 40.0 -> MaterialTheme.colorScheme.error
                    intel.cashDependencyPercent > 25.0 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.secondary
                }
                IntelMetricTile(
                    label = "Cash Dependency",
                    value = BsaFormatters.formatPercent(intel.cashDependencyPercent),
                    subtext = "of total turnover",
                    accentColor = cashColor,
                    modifier = Modifier.weight(1f)
                )
                val bounceColor = if (intel.bounceAndReturnCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                }
                IntelMetricTile(
                    label = "Bounces / Returns",
                    value = "${intel.bounceAndReturnCount}",
                    subtext = if (intel.bounceAndReturnCount == 0) "Clean track" else "Chq/NACH/Penal",
                    accentColor = bounceColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Primary Segment Eligibility Callout
        if (intel.avgMonthlyBusinessCredits > 0.0 || intel.avgMonthlySalaryCredits > 0.0) {
            val primarySeg = intel.primarySegmentEligibility
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSimulator),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${segmentDisplayLabel(primarySeg.productType)} Recommended (${primarySeg.status.label})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = BsaFormatters.formatCurrency(primarySeg.recommendedAmount, useCompactCurrency),
                            fontFamily = JetBrainsMono,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${primarySeg.programName} • Tap to open Segment Simulator",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Calculate,
                        contentDescription = "Open What-If Loan Simulator",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Strengths & Watch-Outs Toggle
        if (intel.strengths.isNotEmpty() || intel.watchOuts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showInsights = !showInsights }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Key Credit Officer Notes (${intel.strengths.size + intel.watchOuts.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (showInsights) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Toggle credit notes",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = showInsights) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    intel.watchOuts.forEach { note ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(15.dp).padding(top = 2.dp)
                            )
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    intel.strengths.forEach { note ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(15.dp).padding(top = 2.dp)
                            )
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // One-Tap Executive Credit Memo Copy & Share Actions
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = onCopyBrief,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("copy_credit_brief_button")
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Copy Brief",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = onShareBrief,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("share_credit_brief_button")
            ) {
                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Share Brief",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun IntelMetricTile(
    label: String,
    value: String,
    subtext: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                fontFamily = JetBrainsMono,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Segment-Aware Interactive What-If Loan, OD/CC & Post-Loan FOIR Calculator:
 * Adapts rate, tenure, FOIR norms, and revolving OD/CC utilization math to each of the 4 Indian segments
 * (Auto Loan, Business Loan, Working Capital, Home Loan).
 */
@Composable
fun WhatIfLoanCalculatorCard(
    intel: CreditIntelligenceSummary,
    useCompactCurrency: Boolean,
    selectedProductType: ProductType = intel.primarySegmentEligibility.productType,
    onSelectProductType: (ProductType) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeSegment = remember(intel, selectedProductType) {
        intel.segmentEligibilities.firstOrNull { it.productType == selectedProductType }
            ?: intel.primarySegmentEligibility
    }

    val (minRate, maxRate) = when (activeSegment.productType) {
        ProductType.AUTO_LOAN -> 8f to 16f
        ProductType.BUSINESS_LOAN -> 10f to 24f
        ProductType.WORKING_CAPITAL -> 8f to 18f
        ProductType.HOME_LOAN -> 7.5f to 13f
    }
    val (minYears, maxYears) = when (activeSegment.productType) {
        ProductType.AUTO_LOAN -> 1f to 7f
        ProductType.BUSINESS_LOAN -> 1f to 5f
        ProductType.WORKING_CAPITAL -> 1f to 1f
        ProductType.HOME_LOAN -> 5f to 30f
    }
    val maxSliderLakhs = remember(activeSegment) {
        maxOf(200f, ((activeSegment.maxEligibleAmount / 100000.0) * 1.5).toFloat().coerceAtMost(1000f))
    }

    var loanLakhs by remember(activeSegment.productType, activeSegment.recommendedAmount) {
        val estLakhs = (activeSegment.recommendedAmount / 100000.0).roundToInt()
        mutableFloatStateOf(estLakhs.coerceIn(1, maxSliderLakhs.toInt()).toFloat())
    }
    var ratePct by remember(activeSegment.productType, activeSegment.benchmarkRatePct) {
        mutableFloatStateOf(activeSegment.benchmarkRatePct.toFloat().coerceIn(minRate, maxRate))
    }
    var tenureYears by remember(activeSegment.productType, activeSegment.benchmarkTenureMonths) {
        mutableFloatStateOf((activeSegment.benchmarkTenureMonths / 12f).coerceIn(minYears, maxYears))
    }
    var wcUtilPct by remember(activeSegment.productType) { mutableFloatStateOf(80f) }

    val proposedPrincipal = loanLakhs.roundToInt() * 100000.0
    val tenureMonths = (tenureYears.roundToInt().coerceIn(minYears.toInt(), maxYears.toInt())) * 12

    // Working Capital revolving OD/CC calculations
    val annualBto = intel.avgMonthlyBusinessCredits * 12.0
    val wcMonthlyInterest = (proposedPrincipal * (wcUtilPct / 100.0)) * (ratePct / 100.0) / 12.0
    val wcPercentOfBto = if (annualBto > 0.0) ((proposedPrincipal / annualBto) * 100.0).coerceIn(0.0, 200.0) else 0.0
    val wcCreditSummationRatio = if (proposedPrincipal > 0.0) annualBto / proposedPrincipal else 0.0

    // Term Loan (Auto / Business / Home) EMI & Post-Loan FOIR calculations
    val proposedEmi = remember(proposedPrincipal, ratePct, tenureMonths) {
        CreditIntelligenceEngine.calculateMonthlyEmi(proposedPrincipal, ratePct.toDouble(), tenureMonths)
    }
    val foirIncomeBase = when (activeSegment.productType) {
        ProductType.HOME_LOAN -> intel.estimatedNetMonthlyIncome.takeIf { it > 0.0 } ?: intel.avgMonthlyBusinessCredits
        ProductType.AUTO_LOAN -> if (intel.avgMonthlySalaryCredits >= 15000.0) intel.avgMonthlySalaryCredits else (intel.avgMonthlyBusinessCredits * 0.22)
        else -> maxOf(intel.estimatedNetMonthlyIncome, intel.avgMonthlyBusinessCredits * 0.20)
    }
    val totalPostLoanEmi = intel.avgMonthlyEmiObligation + proposedEmi
    val postLoanFoir = if (foirIncomeBase > 0.0) {
        ((totalPostLoanEmi / foirIncomeBase) * 100.0).coerceIn(0.0, 200.0)
    } else 0.0

    val foirColor = when {
        postLoanFoir > activeSegment.maxAllowedFoirPct + 10.0 -> MaterialTheme.colorScheme.error
        postLoanFoir > activeSegment.maxAllowedFoirPct -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val wcBtoColor = when {
        wcPercentOfBto > 25.0 -> MaterialTheme.colorScheme.error
        wcPercentOfBto > 20.0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    LedgerCard(modifier = modifier.testTag("what_if_loan_calculator_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Calculate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Segment What-If Simulator",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${segmentDisplayLabel(activeSegment.productType)} • ${activeSegment.programName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 4-Segment Selector Chip Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProductType.entries.forEach { pType ->
                FilterChip(
                    selected = activeSegment.productType == pType,
                    onClick = { onSelectProductType(pType) },
                    leadingIcon = {
                        Icon(
                            imageVector = segmentIcon(pType),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(segmentDisplayLabel(pType), maxLines = 1, softWrap = false) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Live Result Strip (Working Capital OD/CC Mode vs Term Loan Mode)
        if (activeSegment.isRevolvingFacility) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IntelMetricTile(
                    label = "Est. Monthly Interest",
                    value = BsaFormatters.formatCurrency(wcMonthlyInterest, useCompactCurrency),
                    subtext = "@ ${wcUtilPct.roundToInt()}% util • ${String.format(Locale.ROOT, "%.1f%%", ratePct)}",
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IntelMetricTile(
                    label = "% of Annual BTO",
                    value = BsaFormatters.formatPercent(wcPercentOfBto),
                    subtext = "Sum: ${String.format(Locale.ROOT, "%.1fx", wcCreditSummationRatio)} (≥4x)",
                    accentColor = wcBtoColor,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (wcPercentOfBto / 30.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = wcBtoColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        } else {
            val foirStatusLabel = when {
                foirIncomeBase <= 0.0 -> "No Income Base"
                postLoanFoir <= activeSegment.maxAllowedFoirPct -> "Within ${activeSegment.maxAllowedFoirPct.toInt()}% Norm"
                postLoanFoir <= activeSegment.maxAllowedFoirPct + 10.0 -> "Borderline FOIR"
                else -> "Breaches FOIR Cap"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IntelMetricTile(
                    label = "Proposed EMI",
                    value = BsaFormatters.formatCurrency(proposedEmi, useCompactCurrency),
                    subtext = "${tenureYears.roundToInt()} yrs @ ${String.format(Locale.ROOT, "%.2f%%", ratePct)}",
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IntelMetricTile(
                    label = "Post-Loan FOIR",
                    value = BsaFormatters.formatPercent(postLoanFoir),
                    subtext = foirStatusLabel,
                    accentColor = foirColor,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (postLoanFoir / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = foirColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        // Slider 1: Proposed Loan / OD Limit Amount
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (activeSegment.isRevolvingFacility) "Proposed OD / CC Limit" else "Proposed Loan Amount",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = BsaFormatters.formatCurrency(proposedPrincipal, useCompactCurrency),
                fontFamily = JetBrainsMono,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = loanLakhs.coerceIn(1f, maxSliderLakhs),
            onValueChange = { loanLakhs = it },
            valueRange = 1f..maxSliderLakhs,
            modifier = Modifier.testTag("loan_amount_slider")
        )

        // Slider 2: Interest Rate
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Interest Rate (p.a.)", style = MaterialTheme.typography.labelMedium)
            Text(
                text = String.format(Locale.ROOT, "%.2f%%", ratePct),
                fontFamily = JetBrainsMono,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = ratePct.coerceIn(minRate, maxRate),
            onValueChange = { ratePct = it },
            valueRange = minRate..maxRate,
            modifier = Modifier.testTag("loan_rate_slider")
        )

        // Slider 3: Utilization % (Working Capital) OR Tenure Years (Term Loan)
        if (activeSegment.isRevolvingFacility) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Expected Avg Utilization", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "${wcUtilPct.roundToInt()}% of Limit",
                    fontFamily = JetBrainsMono,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = wcUtilPct,
                onValueChange = { wcUtilPct = it },
                valueRange = 20f..100f,
                modifier = Modifier.testTag("wc_utilization_slider")
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Loan Tenure", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "${tenureYears.roundToInt()} Year(s) ($tenureMonths mo)",
                    fontFamily = JetBrainsMono,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = tenureYears.coerceIn(minYears, maxYears),
                onValueChange = { tenureYears = it },
                valueRange = minYears..maxYears,
                steps = ((maxYears - minYears).toInt() - 1).coerceAtLeast(0),
                modifier = Modifier.testTag("loan_tenure_slider")
            )
        }

        // Apply Recommended Eligibility Suggestion Bar
        if (activeSegment.recommendedAmount >= 100000.0) {
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Recommended ${segmentDisplayLabel(activeSegment.productType)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = BsaFormatters.formatCurrency(activeSegment.recommendedAmount, useCompactCurrency),
                            fontFamily = JetBrainsMono,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    TextButton(
                        onClick = {
                            loanLakhs = (activeSegment.recommendedAmount / 100000.0).toFloat().coerceIn(1f, maxSliderLakhs)
                            ratePct = activeSegment.benchmarkRatePct.toFloat().coerceIn(minRate, maxRate)
                            if (!activeSegment.isRevolvingFacility) {
                                tenureYears = (activeSegment.benchmarkTenureMonths / 12f).coerceIn(minYears, maxYears)
                            }
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Apply Rec.", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * Tap-to-Inspect Transaction Detail & Live Category Override Dialog:
 * Shows complete unwrapped bank narration, balance verification, and 1-tap category override
 * that recalculates Monthly Metrics, Loan Track, FOIR, Fraud Triggers, and 11-tab Excel immediately.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionInspectorDialog(
    txn: BankTransaction,
    onSelectCategory: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Transaction #${txn.slNo} • ${txn.displayDate}",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Amount & Balance Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IntelMetricTile(
                        label = if (txn.amount >= 0) "Credit Amount" else "Debit Amount",
                        value = BsaFormatters.formatInr(txn.amount, showSign = true),
                        subtext = if (txn.isSelfTransfer) "Self Transfer" else txn.partyName.take(18),
                        accentColor = if (txn.amount >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    IntelMetricTile(
                        label = "Running Balance",
                        value = BsaFormatters.formatInr(txn.balance),
                        subtext = if (txn.hasBalanceMismatch) "Math Mismatch!" else "Math Verified",
                        accentColor = if (txn.hasBalanceMismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Full Unwrapped Narration
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Full Bank Narration",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = txn.description,
                            fontFamily = JetBrainsMono,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (txn.chequeNo.isNotBlank() || txn.sourceFile.isNotBlank()) {
                            Text(
                                text = listOfNotNull(
                                    txn.chequeNo.takeIf { it.isNotBlank() }?.let { "Chq/Ref: $it" },
                                    txn.sourceFile.takeIf { it.isNotBlank() }?.let { "File: $it" }
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "Reclassify Category (recalculates FOIR, EMI & Excel)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FuzzyMatcherAndCategorizer.STANDARD_CATEGORIES.forEach { cat ->
                        val selected = txn.category.equals(cat, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (!selected) onSelectCategory(cat)
                                onDismiss()
                            },
                            label = {
                                Text(
                                    text = cat,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Close")
            }
        }
    )
}
