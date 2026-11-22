package com.example.bsa.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bsa.engine.AccountPortfolioBreakdown
import com.example.bsa.engine.CreditIntelligenceSummary
import com.example.bsa.engine.EligibilityStatus
import com.example.bsa.engine.PortfolioRiskSummary
import com.example.bsa.engine.SegmentEligibilityResult
import com.example.bsa.model.AnalysisReport
import com.example.bsa.model.ProductType
import com.example.bsa.service.FraudScreeningService
import com.example.ui.theme.JetBrainsMono
import java.util.Locale

/**
 * Structured content shown inside the interactive YourView tap-to-explain tooltip modal.
 */
data class MetricTooltipContent(
    val title: String,
    val formula: String,
    val explanation: String,
    val breakdownBullets: List<String> = emptyList()
)

/**
 * Subtle, accessible info icon button (`>= 48.dp` touch target) placed on metric cards
 * to explain underlying underwriting formulas and score weightings on tap.
 */
@Composable
fun InfoTooltipIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(36.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Branded BSA-YourView interactive tooltip dialog explaining metric formulas, risk score weightings,
 * and live underwriting breakdowns when a user taps any KPI tile or card info icon.
 */
@Composable
fun YourViewTooltipDialog(
    content: MetricTooltipContent,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("yourview_tooltip_dialog"),
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        title = {
            Text(
                text = content.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (content.formula.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "UNDERWRITING FORMULA",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = content.formula,
                                fontFamily = JetBrainsMono,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Text(
                    text = content.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (content.breakdownBullets.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        content.breakdownBullets.forEach { bullet ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "•",
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = bullet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            YourViewPrimaryButton(
                text = "Got It",
                onClick = onDismiss,
                modifier = Modifier.testTag("tooltip_dismiss_button")
            )
        }
    )
}

/**
 * Consolidated Multi-Account Portfolio Risk Card shown in Dashboard Overview:
 * Surfaces Weighted Portfolio Risk Score, Per-Account Breakdown, Cross-Account Contra Deduction,
 * Concentration Alerts, Best-Fit Loan Segment, and ABB-to-EMI Coverage Ratio.
 */
@Composable
fun ConsolidatedPortfolioRiskCard(
    portfolio: PortfolioRiskSummary,
    intel: CreditIntelligenceSummary,
    useCompactCurrency: Boolean,
    onShowTooltip: (MetricTooltipContent) -> Unit,
    modifier: Modifier = Modifier
) {
    val riskColor = when {
        portfolio.portfolioRiskScore >= 60 -> MaterialTheme.colorScheme.error
        portfolio.portfolioRiskScore >= 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val riskContainer = when {
        portfolio.portfolioRiskScore >= 60 -> MaterialTheme.colorScheme.errorContainer
        portfolio.portfolioRiskScore >= 30 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onRiskContainer = when {
        portfolio.portfolioRiskScore >= 60 -> MaterialTheme.colorScheme.onErrorContainer
        portfolio.portfolioRiskScore >= 30 -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    LedgerCard(modifier = modifier.testTag("consolidated_portfolio_risk_card")) {
        // Header Row with Interactive Tooltip Trigger
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            ) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.AccountTree,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Consolidated Portfolio Risk",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${portfolio.accountCount} Bank Account${if (portfolio.accountCount == 1) "" else "s"} • True Inflow ${BsaFormatters.formatCurrency(portfolio.consolidatedTrueInflow, useCompactCurrency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = riskContainer
            ) {
                Text(
                    text = "${portfolio.portfolioRiskScore}/100",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = onRiskContainer,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
            InfoTooltipIconButton(
                contentDescription = "Explain Consolidated Portfolio Risk weighting",
                onClick = { onShowTooltip(UnderwritingTooltips.portfolioRisk(portfolio)) },
                modifier = Modifier.testTag("portfolio_risk_tooltip_button")
            )
        }

        Spacer(Modifier.height(10.dp))

        // Best-Fit Segment & ABB-to-EMI Sanction Readiness Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val best = portfolio.bestFitSegment
            val bestColor = when (best.status) {
                EligibilityStatus.ELIGIBLE -> MaterialTheme.colorScheme.secondary
                EligibilityStatus.CONDITIONAL -> MaterialTheme.colorScheme.tertiary
                EligibilityStatus.INELIGIBLE -> MaterialTheme.colorScheme.error
            }
            PortfolioMiniHighlight(
                label = "Best-Fit Segment",
                value = best.productType.displayName,
                subtext = "${BsaFormatters.formatCurrency(best.recommendedAmount, useCompactCurrency)} (${best.status.label})",
                accent = bestColor,
                modifier = Modifier.weight(1f)
            )
            PortfolioMiniHighlight(
                label = "ABB / EMI Coverage",
                value = String.format(Locale.ROOT, "%.2fx", portfolio.emiToAbbCoverageRatio),
                subtext = if (portfolio.emiToAbbCoverageRatio >= 1.5) "Meets >= 1.5x bank norm" else "Watch (< 1.5x norm)",
                accent = if (portfolio.emiToAbbCoverageRatio >= 1.5) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
        }

        // Cross-Account Contra Deduction Banner (shown when multi-bank contra transfers detected)
        if (portfolio.crossAccountTransfers.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CompareArrows,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "${portfolio.crossAccountTransfers.size} Cross-Account Contra Transfer(s) totaling ${BsaFormatters.formatCurrency(portfolio.crossAccountContraTotal, useCompactCurrency)} excluded to prevent turnover inflation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Concentration Risk Banner
        if (portfolio.hasConcentrationRisk) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PieChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = "Single-Account Concentration: ${portfolio.dominantAccountLabel} drives ${String.format(Locale.ROOT, "%.1f%%", portfolio.dominantAccountSharePct)} of total credits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Per-Account Risk & Cash-Flow Breakdown",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            portfolio.accounts.forEach { acc ->
                AccountBreakdownRow(
                    account = acc,
                    useCompactCurrency = useCompactCurrency
                )
            }
        }

        if (!portfolio.isMultiAccount) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tip: Queue ePDFs from additional bank accounts in the Analyze tab to unlock cross-bank contra elimination & concentration analytics.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PortfolioMiniHighlight(
    label: String,
    value: String,
    subtext: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtext,
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AccountBreakdownRow(
    account: AccountPortfolioBreakdown,
    useCompactCurrency: Boolean
) {
    val tierColor = when {
        account.accountRiskScore >= 60 -> MaterialTheme.colorScheme.error
        account.accountRiskScore >= 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.accountLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${account.txnCount} txns • ${String.format(Locale.ROOT, "%.1f%%", account.creditSharePct)} share • ${account.sourceFiles.size} file(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = tierColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = "Risk ${account.accountRiskScore} • ${account.riskTier.substringBefore(" ")}",
                        fontFamily = JetBrainsMono,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = tierColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { (account.creditSharePct / 100.0).toFloat().coerceIn(0.05f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Cr ${BsaFormatters.formatCurrency(account.totalCredits, useCompactCurrency)}",
                    fontFamily = JetBrainsMono,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Dr ${BsaFormatters.formatCurrency(account.totalDebits, useCompactCurrency)}",
                    fontFamily = JetBrainsMono,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Avg ${BsaFormatters.formatCurrency(account.avgEodBalance, useCompactCurrency)}",
                    fontFamily = JetBrainsMono,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (account.chequeBounceCount > 0 || account.emiReturnCount > 0 || account.internalCredits > 0.0) {
                Text(
                    text = "Bounces: ${account.chequeBounceCount} Chq / ${account.emiReturnCount} EMI • Internal Cr: ${BsaFormatters.formatCurrency(account.internalCredits, useCompactCurrency)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (account.chequeBounceCount + account.emiReturnCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Centralized Underwriting Tooltip Factory ensuring consistent, accurate formula explanations
 * across every Dashboard KPI tile, Risk Score card, Credit Health card, Eligibility card, and Portfolio view.
 */
object UnderwritingTooltips {

    fun riskScore(report: AnalysisReport): MetricTooltipContent {
        val summary = FraudScreeningService.computeRiskSummary(report.fraudIndicators)
        return MetricTooltipContent(
            title = "How 10-Point Risk Score Is Weighted",
            formula = "Risk Score = min(100, High×25 + Medium×12 + Low×5)",
            explanation = "Evaluates 5 Forensic Fraud Indicators (Items 1–5) and 5 Behavioural Triggers (Items 6–10). Higher scores signal elevated underwriting anomaly risk.",
            breakdownBullets = listOf(
                "Current Score: ${summary.riskScore} / 100 (${summary.riskTier.label})",
                "High-Severity Flag: +25 pts each (e.g., Balance Mismatch, PDF Tampering, Cash >70%)",
                "Medium-Severity Flag: +12 pts each (e.g., Round-Figure Transfers, Holiday Activity, OD Stress)",
                "Low-Severity Flag: +5 pts each (isolated anomalies)",
                "Tiers: Low Risk (0–24), Moderate Risk (25–59), High Risk (60–100)",
                "Active Flags: ${summary.fraudFlaggedCount}/5 Fraud, ${summary.behaviouralFlaggedCount}/5 Behavioural"
            )
        )
    }

    fun creditHealth(intel: CreditIntelligenceSummary): MetricTooltipContent {
        return MetricTooltipContent(
            title = "Credit Health Score & Verdict Logic",
            formula = "Start 100 − FOIR Penalty − Bounce Penalty − Cash Dep Penalty − Fraud Risk Penalty + ABB Bonus",
            explanation = "Measures overall borrower repayment capacity and banking discipline on a 0–100 scale (higher = stronger creditworthiness).",
            breakdownBullets = listOf(
                "Current Credit Health: ${intel.creditHealthScore} / 100 (${intel.verdict.shortLabel})",
                "FOIR Ratio: ${String.format(Locale.ROOT, "%.1f%%", intel.foirPercent)} (Monthly EMIs / Net Monthly Inflow; ideal <= 50%)",
                "Checkpoint ABB: ${BsaFormatters.formatInr(intel.avgBankBalanceAbb)} averaged across 2nd, 10th, 20th & Month-End",
                "Cash Dependency: ${String.format(Locale.ROOT, "%.1f%%", intel.cashDependencyPercent)} of gross credits deposited in cash",
                "Verdict Bands: >= 75 Strong Approve, 50–74 Conditional Review, < 50 High Risk / Decline"
            )
        )
    }

    fun segmentEligibility(segment: SegmentEligibilityResult): MetricTooltipContent {
        val formula = when (segment.productType) {
            ProductType.AUTO_LOAN -> "Min(FOIR 60% Capacity, 3.5x Checkpoint ABB, Income Multiplier) @ 9.25% / 60m"
            ProductType.BUSINESS_LOAN -> "Min(55% FOIR Capacity, 12%–18% Annualized True BTO, 4x ABB) @ 14.5% / 36m"
            ProductType.WORKING_CAPITAL -> "Nayak Committee Norm: 20% of Annualized True Business Turnover (excl. Self/Contra) @ 11.25% OD/CC"
            ProductType.HOME_LOAN -> "Min(55%–60% Long-Tenure FOIR Capacity, 60x Net Monthly Surplus) @ 8.65% / 240m"
        }
        return MetricTooltipContent(
            title = "${segment.productType.displayName} Formula Explained",
            formula = formula,
            explanation = "Applies Indian Bank & NBFC policy caps using true non-internal banking credits, existing EMI obligations, Checkpoint ABB, and conduct haircuts (cheque bounces, EMI returns, OD over-utilization).",
            breakdownBullets = listOf(
                "Program: ${segment.programName}",
                "Status: ${segment.status.label}",
                "Conservative: ${BsaFormatters.formatInr(segment.conservativeAmount)}",
                "Recommended: ${BsaFormatters.formatInr(segment.recommendedAmount)}",
                "Max Eligible: ${BsaFormatters.formatInr(segment.maxEligibleAmount)}",
                "Limiting Factor: ${segment.limitingFactor}"
            )
        )
    }

    fun portfolioRisk(portfolio: PortfolioRiskSummary): MetricTooltipContent {
        return MetricTooltipContent(
            title = "Consolidated Portfolio Risk Logic",
            formula = "Portfolio Risk = 55% Weighted Account Risk + 35% 10-Point Screening + Contra/Concentration/Forensic Penalties",
            explanation = "Aggregates every queued bank account into a unified borrower view. Inter-bank contra transfers (matching debit & credit within ±2 days across accounts) are stripped so turnover is never double-counted.",
            breakdownBullets = listOf(
                "Accounts Consolidated: ${portfolio.accountCount} distinct bank account(s)",
                "Portfolio Risk Score: ${portfolio.portfolioRiskScore} / 100 (${portfolio.portfolioRiskTier})",
                "Gross Consolidated Credits: ${BsaFormatters.formatInr(portfolio.consolidatedGrossInflow)}",
                "Cross-Account Contras Excluded: ${BsaFormatters.formatInr(portfolio.crossAccountContraTotal)} (${portfolio.crossAccountTransfers.size} pair(s))",
                "True Consolidated Turnover: ${BsaFormatters.formatInr(portfolio.consolidatedTrueInflow)}",
                "ABB-to-EMI Coverage: ${String.format(Locale.ROOT, "%.2fx", portfolio.emiToAbbCoverageRatio)} (Indian Bank norm >= 1.50x)"
            )
        )
    }

    fun kpiTooltip(key: String, report: AnalysisReport): MetricTooltipContent = when (key) {
        "credits" -> MetricTooltipContent(
            title = "Total Credits (Gross Inflow)",
            formula = "Sum of all positive transaction amounts in statement period",
            explanation = "Captures every credit posted across all uploaded bank accounts. Eligibility engines automatically strip Internal/Self Credits and Cross-Account Contras to arrive at True Business Turnover.",
            breakdownBullets = listOf(
                "Gross Credits: ${BsaFormatters.formatInr(report.totalInflow)}",
                "Internal Credits Excluded: ${BsaFormatters.formatInr(report.totalInternalCredits)}",
                "Net Non-Internal Inflow: ${BsaFormatters.formatInr((report.totalInflow - report.totalInternalCredits).coerceAtLeast(0.0))}"
            )
        )
        "debits" -> MetricTooltipContent(
            title = "Total Debits (Gross Outflow)",
            formula = "Sum of all negative transaction amounts in statement period",
            explanation = "Includes vendor payouts, EMIs, statutory payments (GST/TDS), cash withdrawals, bank charges, and inter-account transfers.",
            breakdownBullets = listOf(
                "Total Debits: ${BsaFormatters.formatInr(report.totalOutflow)}",
                "Tracked EMI Outflow: ${BsaFormatters.formatInr(report.totalEmiOutflow)}",
                "Net Cash Flow: ${BsaFormatters.formatInr(report.totalInflow - report.totalOutflow, showSign = true)}"
            )
        )
        "abb" -> MetricTooltipContent(
            title = "Average EOD Balance",
            formula = "Mean of daily End-of-Day balances across all calendar days",
            explanation = "Every calendar day (including non-transaction days & holidays) carries forward the prior closing balance so EOD averages reflect genuine daily liquidity.",
            breakdownBullets = listOf(
                "Avg EOD Balance: ${BsaFormatters.formatInr(report.averageMonthlyBalance)}",
                "Months Analyzed: ${report.monthlyMetrics.size}",
                "Used directly in Auto Loan, Business Loan & ABB/EMI coverage norms"
            )
        )
        "od" -> MetricTooltipContent(
            title = "Peak OD / CC Utilization %",
            formula = "(Peak Overdrawn Dr Balance / Drawing Power or Sanctioned Limit) × 100",
            explanation = "Tracks maximum daily drawdown against your Drawing Power (or Sanctioned OD Limit). Utilization above 90% signals liquidity stretch; >100% indicates over-limit breach.",
            breakdownBullets = listOf(
                "Peak Utilization: ${String.format(Locale.ROOT, "%.1f%%", report.peakOdUtilizationPct)}",
                "Peak Overdrawn Amt: ${BsaFormatters.formatInr(report.peakOdUtilizationAmt)} on ${report.peakOdUtilizationDate}",
                "Sanctioned Limit: ${BsaFormatters.formatInr(report.config.sanctionedOdLimit)}"
            )
        )
        "internal" -> MetricTooltipContent(
            title = "Internal / Self Credits",
            formula = "Credits matched to Account Holder Name (Fuzzy >= 82%) or Sister/Contra tags",
            explanation = "Self transfers and sister-concern circular credits artificially inflate banking turnover. BSA-YourView isolates and excludes them before calculating loan eligibility.",
            breakdownBullets = listOf(
                "Internal Credits Found: ${BsaFormatters.formatInr(report.totalInternalCredits)}",
                "Transaction Count: ${report.totalInternalCreditCount}",
                "Share of Gross Credits: ${String.format(Locale.ROOT, "%.1f%%", if (report.totalInflow > 0) report.totalInternalCredits / report.totalInflow * 100.0 else 0.0)}"
            )
        )
        "cheque_bounce" -> MetricTooltipContent(
            title = "Cheque Bounces (I/W & O/W Returns)",
            formula = "Count & value of CHQ RTN / Dishonour / Insufficient Funds narrations",
            explanation = "Inward cheque bounces due to insufficient funds are a primary negative conduct indicator in Indian underwriting and trigger automatic eligibility haircuts.",
            breakdownBullets = listOf(
                "Cheque Bounces Detected: ${report.totalChequeBounceCount}",
                "Total Bounce Value: ${BsaFormatters.formatInr(report.totalChequeBounceAmount)}",
                "Policy Impact: >= 2 bounces trigger conservative haircut & Credit Committee review"
            )
        )
        "emi_return" -> MetricTooltipContent(
            title = "EMI / NACH / ECS Returns",
            formula = "Count & value of NACH RTN / ACH RET / ECS FAIL / EMI Bounce narrations",
            explanation = "Failed auto-debit loan mandates indicate direct repayment stress and carry the heaviest weight in eligibility haircuts and risk scoring.",
            breakdownBullets = listOf(
                "EMI / NACH Returns: ${report.totalEmiReturnCount}",
                "Returned EMI Value: ${BsaFormatters.formatInr(report.totalEmiReturnAmount)}",
                "Policy Impact: Even 1 recent EMI bounce flags Caution; >= 2 may render segment Ineligible"
            )
        )
        else -> riskScore(report)
    }
}
