package com.example.bsa.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.bsa.engine.CreditIntelligenceEngine
import com.example.bsa.engine.DeepUnderwritingAnalytics
import com.example.bsa.engine.PortfolioRiskEngine
import com.example.bsa.model.*
import com.example.bsa.ui.BsaUiState
import com.example.bsa.ui.LedgerQuickFilter
import com.example.bsa.ui.LedgerSortOption
import com.example.bsa.ui.ReportSubTab
import com.example.bsa.ui.components.*
import com.example.ui.theme.JetBrainsMono
import kotlin.math.abs

@Composable
fun DashboardScreen(
    uiState: BsaUiState,
    onSelectSubTab: (ReportSubTab) -> Unit,
    onExportExcel: () -> Unit,
    onShareExcel: () -> Unit,
    onExportPdfSummary: () -> Unit = {},
    onSharePdfSummary: () -> Unit = {},
    onOpenExcelConfig: () -> Unit,
    onCopyBrief: () -> Unit,
    onShareBrief: () -> Unit,
    onApplyDateFilter: (String?, String?) -> Unit,
    onApplyQuickDatePreset: (Int?) -> Unit,
    onLedgerSearchChange: (String) -> Unit,
    onLedgerCategoryChange: (String) -> Unit,
    onLedgerQuickFilterChange: (LedgerQuickFilter) -> Unit,
    onLedgerSortOptionChange: (LedgerSortOption) -> Unit,
    onOverrideTransactionCategory: (BankTransaction, String) -> Unit,
    onUpdateCamProfile: ((UnderwriterCamProfile) -> UnderwriterCamProfile) -> Unit,
    onGoToAnalyze: () -> Unit,
    modifier: Modifier = Modifier
) {
    val report = uiState.activeReport
    val useCompact = uiState.settings.useCompactCurrency
    if (report == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            LedgerCard(modifier = Modifier.widthIn(max = 460.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    YvBrandLogoBadge(size = 56.dp, cornerRadius = 14.dp)
                    Text(
                        text = stringResource(R.string.empty_report_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.empty_report_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    YourViewPrimaryButton(
                        text = "Go to Statement Intake",
                        icon = Icons.Filled.UploadFile,
                        onClick = onGoToAnalyze,
                        modifier = Modifier.fillMaxWidth()
                    )
                    YvBrandEndorsementFooter()
                }
            }
        }
        return
    }

    val creditIntel = remember(report) { CreditIntelligenceEngine.evaluate(report) }
    val deepAnalytics = remember(report) { DeepUnderwritingAnalytics.analyze(report) }
    val portfolioRisk = remember(report, creditIntel) { PortfolioRiskEngine.evaluate(report, creditIntel) }
    var inspectedTxn by remember { mutableStateOf<BankTransaction?>(null) }
    var activeTooltip by remember { mutableStateOf<MetricTooltipContent?>(null) }
    var simulatorProductType by remember(report.reportId, report.config.productType) {
        mutableStateOf(report.config.productType)
    }

    val categories = remember(report.transactions) {
        listOf("All", "Self Transfers") + report.transactions.map { it.category }.distinct().sorted()
    }

    val filteredTransactions by remember(
        report.transactions,
        uiState.ledgerSearchQuery,
        uiState.ledgerCategoryFilter,
        uiState.ledgerQuickFilter,
        uiState.ledgerSortOption
    ) {
        derivedStateOf {
            val baseFiltered = report.transactions.filter { t ->
                val matchesCat = when (uiState.ledgerCategoryFilter) {
                    "All" -> true
                    "Self Transfers" -> t.isSelfTransfer
                    else -> t.category == uiState.ledgerCategoryFilter
                }
                val matchesQuick = when (uiState.ledgerQuickFilter) {
                    LedgerQuickFilter.ALL -> true
                    LedgerQuickFilter.HIGH_VALUE -> abs(t.amount) >= 100000.0
                    LedgerQuickFilter.BOUNCES -> t.category == "Reversal" ||
                        t.category == "Bank Charges" ||
                        t.description.contains("BOUNCE", ignoreCase = true) ||
                        t.description.contains("RTN", ignoreCase = true) ||
                        t.description.contains("RET", ignoreCase = true)
                    LedgerQuickFilter.CASH -> t.category == "Cash Deposit" || t.category == "Cash Withdrawal"
                    LedgerQuickFilter.SELF_TRANSFERS -> t.isSelfTransfer
                    LedgerQuickFilter.MISMATCHES -> t.hasBalanceMismatch
                }
                val q = uiState.ledgerSearchQuery.trim()
                val matchesQuery = q.isBlank() ||
                    t.description.contains(q, ignoreCase = true) ||
                    t.category.contains(q, ignoreCase = true) ||
                    t.partyName.contains(q, ignoreCase = true) ||
                    t.displayDate.contains(q, ignoreCase = true) ||
                    t.chequeNo.contains(q, ignoreCase = true)
                matchesCat && matchesQuick && matchesQuery
            }
            when (uiState.ledgerSortOption) {
                LedgerSortOption.DATE_ASC -> baseFiltered
                LedgerSortOption.DATE_DESC -> baseFiltered.reversed()
                LedgerSortOption.AMOUNT_DESC -> baseFiltered.sortedByDescending { abs(it.amount) }
                LedgerSortOption.CREDITS_FIRST -> baseFiltered.sortedByDescending { it.amount }
                LedgerSortOption.DEBITS_FIRST -> baseFiltered.sortedBy { it.amount }
            }
        }
    }

    inspectedTxn?.let { activeTxn ->
        TransactionInspectorDialog(
            txn = activeTxn,
            onSelectCategory = { newCat ->
                onOverrideTransactionCategory(activeTxn, newCat)
            },
            onDismiss = { inspectedTxn = null }
        )
    }

    activeTooltip?.let { tooltipContent ->
        YourViewTooltipDialog(
            content = tooltipContent,
            onDismiss = { activeTooltip = null }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. One-Click 11-Tab Excel Export Action Bar
        item {
            LedgerCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    YvBrandLogoBadge(size = 38.dp, cornerRadius = 9.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${report.config.applicantId} • ${report.metadata.holderName}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${report.metadata.bankName} • ${report.metadata.accountNumber} • ${report.config.facilityType.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    ForensicStatusPill(report.overallForensicStatus)
                }

                Spacer(Modifier.height(12.dp))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth >= 380.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                YourViewAccentButton(
                                    text = if (uiState.isExporting) "Building..." else stringResource(R.string.btn_export_excel),
                                    icon = Icons.Filled.TableChart,
                                    onClick = onExportExcel,
                                    enabled = !uiState.isExporting,
                                    isLoading = uiState.isExporting,
                                    modifier = Modifier
                                        .weight(1.35f)
                                        .testTag("export_excel_button")
                                )
                                YourViewOutlinedButton(
                                    text = stringResource(R.string.btn_share_excel),
                                    icon = Icons.Filled.Share,
                                    onClick = onShareExcel,
                                    enabled = !uiState.isExporting,
                                    modifier = Modifier
                                        .weight(0.65f)
                                        .testTag("share_excel_button")
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                YourViewPrimaryButton(
                                    text = stringResource(R.string.btn_export_pdf),
                                    icon = Icons.Filled.PictureAsPdf,
                                    onClick = onExportPdfSummary,
                                    enabled = !uiState.isExporting,
                                    modifier = Modifier
                                        .weight(1.35f)
                                        .testTag("export_pdf_summary_button")
                                )
                                YourViewSecondaryButton(
                                    text = stringResource(R.string.btn_share_pdf),
                                    icon = Icons.Filled.IosShare,
                                    onClick = onSharePdfSummary,
                                    enabled = !uiState.isExporting,
                                    modifier = Modifier
                                        .weight(0.65f)
                                        .testTag("share_pdf_summary_button")
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            YourViewAccentButton(
                                text = if (uiState.isExporting) "Building..." else stringResource(R.string.btn_export_excel),
                                icon = Icons.Filled.TableChart,
                                onClick = onExportExcel,
                                enabled = !uiState.isExporting,
                                isLoading = uiState.isExporting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("export_excel_button")
                            )
                            YourViewOutlinedButton(
                                text = stringResource(R.string.btn_share_excel),
                                icon = Icons.Filled.Share,
                                onClick = onShareExcel,
                                enabled = !uiState.isExporting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("share_excel_button")
                            )
                            YourViewPrimaryButton(
                                text = stringResource(R.string.btn_export_pdf),
                                icon = Icons.Filled.PictureAsPdf,
                                onClick = onExportPdfSummary,
                                enabled = !uiState.isExporting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("export_pdf_summary_button")
                            )
                            YourViewSecondaryButton(
                                text = stringResource(R.string.btn_share_pdf),
                                icon = Icons.Filled.IosShare,
                                onClick = onSharePdfSummary,
                                enabled = !uiState.isExporting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("share_pdf_summary_button")
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                val excelCfg = uiState.settings.excelExportConfig
                val activeColCount = excelCfg.activeLedgerColumnHeaders().size
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clickable(enabled = !uiState.isExporting, onClick = onOpenExcelConfig)
                        .testTag("open_excel_config_button"),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.btn_customize_excel),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${excelCfg.dateFormat.pattern} • $activeColCount cols • ${if (excelCfg.includeFormulas) "Live Formulas" else "Static Values"} • Top ${excelCfg.topTransactionsLimit}",
                                    fontFamily = JetBrainsMono,
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = "Open Excel Output Configuration",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Real-time 11-Tab Excel Export LinearProgressIndicator
                if (uiState.isExporting) {
                    Spacer(Modifier.height(12.dp))
                }
                ExcelExportProgressPanel(
                    visible = uiState.isExporting,
                    progress = uiState.exportProgress,
                    stageMessage = uiState.exportStageMessage
                )
            }
        }

        // 1a. Color-Coded 10-Point Risk Score Summary Card (with interactive weighting tooltip)
        item {
            RiskScoreSummaryCard(
                indicators = report.fraudIndicators,
                onInspectTriggers = { onSelectSubTab(ReportSubTab.FRAUD) },
                onExplainScore = { activeTooltip = UnderwritingTooltips.riskScore(report) }
            )
        }

        // 1b. Exact Date Range Live Filter Card
        item {
            val totalPool = uiState.fullReportTransactions.size.takeIf { it > 0 } ?: report.transactions.size
            LedgerCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.DateRange,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.section_date_range),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        text = "${report.transactions.size} / $totalPool txns",
                        fontFamily = JetBrainsMono,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Output & 18-tab Excel cover strictly: ${report.metadata.statementPeriod}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAllActive = report.config.filterStartDateIso == null && report.config.filterEndDateIso == null
                    FilterChip(
                        selected = isAllActive,
                        onClick = { onApplyQuickDatePreset(null) },
                        label = { Text("All", maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onApplyQuickDatePreset(30) },
                        label = { Text("Last 30D", maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onApplyQuickDatePreset(90) },
                        label = { Text("Last 3M", maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onApplyQuickDatePreset(180) },
                        label = { Text("Last 6M", maxLines = 1, softWrap = false) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                DateRangePickerRow(
                    startDateIso = report.config.filterStartDateIso,
                    endDateIso = report.config.filterEndDateIso,
                    onStartDateSelected = { start ->
                        onApplyDateFilter(start, report.config.filterEndDateIso)
                    },
                    onEndDateSelected = { end ->
                        onApplyDateFilter(report.config.filterStartDateIso, end)
                    },
                    onClearDates = {
                        onApplyDateFilter(null, null)
                    }
                )
            }
        }

        // 2. Executive KPI Strip (tap any metric tile for interactive formula & logic explanation)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiMetricTile(
                    label = "Total Credits",
                    value = BsaFormatters.formatCurrency(report.totalInflow, useCompact),
                    subtitle = "${report.transactions.count { it.amount > 0 }} inflows • Tap info",
                    accentColor = MaterialTheme.colorScheme.secondary,
                    onInfoClick = { activeTooltip = UnderwritingTooltips.kpiTooltip("credits", report) }
                )
                KpiMetricTile(
                    label = "Total Debits",
                    value = BsaFormatters.formatCurrency(report.totalOutflow, useCompact),
                    subtitle = "${report.transactions.count { it.amount < 0 }} outflows • Tap info",
                    accentColor = MaterialTheme.colorScheme.error,
                    onInfoClick = { activeTooltip = UnderwritingTooltips.kpiTooltip("debits", report) }
                )
                KpiMetricTile(
                    label = "Avg EOD Balance",
                    value = BsaFormatters.formatCurrency(report.averageMonthlyBalance, useCompact),
                    subtitle = "${report.monthlyMetrics.size} month(s) • Tap info",
                    accentColor = MaterialTheme.colorScheme.primary,
                    onInfoClick = { activeTooltip = UnderwritingTooltips.kpiTooltip("abb", report) }
                )
                if (report.config.facilityType != FacilityType.NONE) {
                    KpiMetricTile(
                        label = "Peak OD Util",
                        value = BsaFormatters.formatPercent(report.peakOdUtilizationPct),
                        subtitle = "${BsaFormatters.formatCurrency(report.peakOdUtilizationAmt, useCompact)} on ${report.peakOdUtilizationDate}",
                        accentColor = if (report.peakOdUtilizationPct >= 90.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        onInfoClick = { activeTooltip = UnderwritingTooltips.kpiTooltip("od", report) }
                    )
                }
                KpiMetricTile(
                    label = "Internal Credits",
                    value = BsaFormatters.formatCurrency(report.totalInternalCredits, useCompact),
                    subtitle = "${report.totalInternalCreditCount} self/internal • Tap info",
                    accentColor = MaterialTheme.colorScheme.primary,
                    onInfoClick = { activeTooltip = UnderwritingTooltips.kpiTooltip("internal", report) }
                )
                KpiMetricTile(
                    label = "Cheque Bounces",
                    value = "${report.totalChequeBounceCount}",
                    subtitle = BsaFormatters.formatCurrency(report.totalChequeBounceAmount, useCompact),
                    accentColor = if (report.totalChequeBounceCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    onInfoClick = { activeTooltip = UnderwritingTooltips.kpiTooltip("cheque_bounce", report) }
                )
                KpiMetricTile(
                    label = "EMI Returns",
                    value = "${report.totalEmiReturnCount}",
                    subtitle = BsaFormatters.formatCurrency(report.totalEmiReturnAmount, useCompact),
                    accentColor = if (report.totalEmiReturnCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    onInfoClick = { activeTooltip = UnderwritingTooltips.kpiTooltip("emi_return", report) }
                )
                KpiMetricTile(
                    label = "Fraud / Behav Flags",
                    value = "${report.flaggedFraudCount} / 10",
                    subtitle = "EMI Total ${BsaFormatters.formatCurrency(report.totalEmiOutflow, useCompact)}",
                    accentColor = if (report.flaggedFraudCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                    onInfoClick = { activeTooltip = UnderwritingTooltips.riskScore(report) }
                )
            }
        }

        // 3. Report Deep-Dive Sub-Tab Selector
        item {
            ScrollableTabRow(
                selectedTabIndex = ReportSubTab.entries.indexOf(uiState.activeReportTab),
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                ReportSubTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.activeReportTab == tab,
                        onClick = { onSelectSubTab(tab) },
                        text = {
                            Text(
                                text = tab.label,
                                fontWeight = if (uiState.activeReportTab == tab) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }

        // 4. Sub-Tab Content
        when (uiState.activeReportTab) {
            ReportSubTab.OVERVIEW -> {
                item {
                    ConsolidatedPortfolioRiskCard(
                        portfolio = portfolioRisk,
                        intel = creditIntel,
                        useCompactCurrency = useCompact,
                        onShowTooltip = { activeTooltip = it }
                    )
                }
                item {
                    CreditIntelligenceCard(
                        report = report,
                        intel = creditIntel,
                        useCompactCurrency = useCompact,
                        onCopyBrief = onCopyBrief,
                        onShareBrief = onShareBrief,
                        onOpenSimulator = {
                            simulatorProductType = report.config.productType
                            onSelectSubTab(ReportSubTab.PARTIES)
                        },
                        onExplainHealthScore = {
                            activeTooltip = UnderwritingTooltips.creditHealth(creditIntel)
                        }
                    )
                }
                item {
                    SegmentEligibilityMatrixCard(
                        report = report,
                        intel = creditIntel,
                        useCompactCurrency = useCompact,
                        onSimulateSegment = { chosenSegment ->
                            simulatorProductType = chosenSegment
                            onSelectSubTab(ReportSubTab.PARTIES)
                        },
                        onExplainEligibility = { seg ->
                            activeTooltip = UnderwritingTooltips.segmentEligibility(seg)
                        }
                    )
                }
                item {
                    QuickMetricsEligibilityCalculatorCard(
                        report = report,
                        useCompactCurrency = useCompact
                    )
                }
                item { OnlineEnrichmentSummaryCard(report.onlineEnrichment) }
                item { ForensicAuditAccordionCard(report) }
                item { MonthlyCashFlowChartCard(report) }
                items(report.monthlyMetrics, key = { it.monthKey }) { month ->
                    MonthlyDetailCard(month = month, isOdFacility = report.config.facilityType != FacilityType.NONE)
                }
            }

            ReportSubTab.UNDERWRITER_CAM -> {
                item {
                    UnderwriterCam360Workbench(
                        report = report,
                        intel = creditIntel,
                        deep = deepAnalytics,
                        camProfile = uiState.activeCamProfile,
                        useCompactCurrency = useCompact,
                        onUpdateCam = onUpdateCamProfile,
                        onCopyBrief = onCopyBrief,
                        onExportExcel = onExportExcel
                    )
                }
            }

            ReportSubTab.DEEP_ANALYTICS -> {
                item {
                    InternalCreditsContraCard(
                        data = deepAnalytics.internalTransfers,
                        useCompactCurrency = useCompact
                    )
                }
                item {
                    BouncesEmiDeepDiveCard(
                        data = deepAnalytics.bounces,
                        useCompactCurrency = useCompact
                    )
                }
                item {
                    ChannelAndCashMixCard(
                        data = deepAnalytics.channelsAndCash,
                        useCompactCurrency = useCompact
                    )
                }
                item {
                    ConcentrationAndStatutoryCard(
                        concentration = deepAnalytics.concentration,
                        statutory = deepAnalytics.statutory,
                        useCompactCurrency = useCompact
                    )
                }
            }

            ReportSubTab.FRAUD -> {
                item {
                    Text(
                        text = "Fraud Indicators (Items 1–5) & Behavioural Triggers (Items 6–10)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(report.fraudIndicators, key = { it.itemNo }) { ind ->
                    FraudIndicatorCard(ind)
                }
            }

            ReportSubTab.LEDGER -> {
                item {
                    LedgerCard {
                        OutlinedTextField(
                            value = uiState.ledgerSearchQuery,
                            onValueChange = onLedgerSearchChange,
                            placeholder = { Text("Search narration, date, cheque, party, or category...") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (uiState.ledgerSearchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onLedgerSearchChange("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        // Smart Quick-Triage Filters
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LedgerQuickFilter.entries.forEach { qf ->
                                FilterChip(
                                    selected = uiState.ledgerQuickFilter == qf,
                                    onClick = { onLedgerQuickFilterChange(qf) },
                                    label = { Text(qf.label, maxLines = 1, softWrap = false) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // Sort Order Strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Sort,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            LedgerSortOption.entries.forEach { sortOpt ->
                                FilterChip(
                                    selected = uiState.ledgerSortOption == sortOpt,
                                    onClick = { onLedgerSortOptionChange(sortOpt) },
                                    label = { Text(sortOpt.label, maxLines = 1, softWrap = false) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // Category Filter Strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categories.forEach { cat ->
                                FilterChip(
                                    selected = uiState.ledgerCategoryFilter == cat,
                                    onClick = { onLedgerCategoryChange(cat) },
                                    label = { Text(cat, maxLines = 1, softWrap = false) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Showing ${filteredTransactions.size} of ${report.transactions.size} rows • Tap any row to inspect or reclassify",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(filteredTransactions, key = { it.slNo }) { txn ->
                    TransactionRowCard(
                        txn = txn,
                        onClick = { inspectedTxn = txn }
                    )
                }
            }

            ReportSubTab.PARTIES -> {
                item {
                    WhatIfLoanCalculatorCard(
                        intel = creditIntel,
                        useCompactCurrency = useCompact,
                        selectedProductType = simulatorProductType,
                        onSelectProductType = { simulatorProductType = it }
                    )
                }
                item { LoanTrackSectionCard(report) }
                item {
                    Text(
                        text = "Clustered Counterparties & Categories (${report.groupedParties.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(report.groupedParties.take(30), key = { it.partyOrCategory }) { party ->
                    GroupedPartyCard(party)
                }
            }
        }

        item {
            YvBrandEndorsementFooter()
        }
    }
}

@Composable
private fun ForensicAuditAccordionCard(report: AnalysisReport) {
    var expanded by remember { mutableStateOf(report.overallForensicStatus != ForensicStatus.VERIFIED) }
    LedgerCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Forensic ePDF Verification Audit (${report.fileAudits.size} ${if (report.fileAudits.size == 1) "file" else "files"})",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Period: ${report.metadata.statementPeriod} • IFSC: ${report.metadata.ifscCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = "Toggle forensic audit details"
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                report.fileAudits.forEach { audit ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = audit.fileName,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ForensicStatusPill(audit.status)
                            }
                            Text(
                                text = "PDF v${audit.pdfVersion} • ${if (audit.isPasswordProtected) "Encrypted (Unlocked)" else "Unencrypted"} • Pages: ${audit.pageCount} • Chars/Pg: ${audit.avgCharsPerPage} • %%EOF: ${audit.eofMarkerCount}",
                                fontFamily = JetBrainsMono,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "SHA-256: ${audit.sha256Short} • Images: ${audit.imageObjectCount} • Created: ${audit.creationDate} • Mod: ${audit.modDate}",
                                fontFamily = JetBrainsMono,
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Producer: ${audit.producer} | Creator: ${audit.creator}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            audit.findings.forEach { finding ->
                                Text(
                                    text = "• $finding",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (audit.status == ForensicStatus.REJECTED) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
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
private fun MonthlyCashFlowChartCard(report: AnalysisReport) {
    val chronoMonths = remember(report.monthlyMetrics) { report.monthlyMetrics.reversed() }
    if (chronoMonths.isEmpty()) return

    val creditColor = MaterialTheme.colorScheme.secondary
    val debitColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    LedgerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Monthly Inflow vs Outflow",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot("Credits", creditColor)
                LegendDot("Debits", debitColor)
            }
        }
        Spacer(Modifier.height(14.dp))

        val maxVal = chronoMonths.maxOf { maxOf(it.totalCredits, it.totalDebits) }.coerceAtLeast(1.0)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val count = chronoMonths.size
            val slotW = size.width / count.coerceAtLeast(1)
            val barW = (slotW * 0.28f).coerceAtMost(28.dp.toPx())
            val gap = 4.dp.toPx()

            // Subtle baseline & mid-line
            drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            drawLine(gridColor.copy(alpha = 0.5f), Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), strokeWidth = 1.dp.toPx())

            chronoMonths.forEachIndexed { idx, m ->
                val centerX = slotW * idx + slotW / 2f
                val crH = ((m.totalCredits / maxVal) * (size.height - 12f)).toFloat().coerceAtLeast(3f)
                val drH = ((m.totalDebits / maxVal) * (size.height - 12f)).toFloat().coerceAtLeast(3f)

                drawRoundRect(
                    color = creditColor,
                    topLeft = Offset(centerX - barW - gap / 2f, size.height - crH),
                    size = Size(barW, crH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                drawRoundRect(
                    color = debitColor,
                    topLeft = Offset(centerX + gap / 2f, size.height - drH),
                    size = Size(barW, drH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            chronoMonths.forEach { m ->
                Text(
                    text = m.monthLabel,
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthlyDetailCard(month: MonthlyMetric, isOdFacility: Boolean) {
    LedgerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = month.monthLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Net: ${BsaFormatters.formatInr(month.netCashFlow, showSign = true)}",
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (month.netCashFlow >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(10.dp))

        // Key checkpoint balances (2x2 grid prevents narrow-screen squeezing)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniCheckpoint("Bal as on 2nd", month.balAsOn2nd, Modifier.weight(1f))
                MiniCheckpoint("Bal as on 10th", month.balAsOn10th, Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniCheckpoint("Bal as on 20th", month.balAsOn20th, Modifier.weight(1f))
                MiniCheckpoint("Month End Bal", month.balAsOnMonthEnd, Modifier.weight(1f))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Credits (${month.creditCount})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(BsaFormatters.formatInr(month.totalCredits), fontFamily = JetBrainsMono, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Debits (${month.debitCount})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(BsaFormatters.formatInr(month.totalDebits), fontFamily = JetBrainsMono, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "EMI / Loan Outflow (${month.emiLoanCount})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(BsaFormatters.formatInr(month.emiLoanOutflow), fontFamily = JetBrainsMono, fontSize = 12.5.sp, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Internal Credits (${month.internalCreditCount})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(BsaFormatters.formatInr(month.internalCredits), fontFamily = JetBrainsMono, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Cheque Bounces (${month.chequeBounceCount}) • EMI Returns (${month.emiReturnCount})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val totalBounceAmt = month.chequeBounceAmount + month.emiReturnAmount
            val hasBounce = (month.chequeBounceCount + month.emiReturnCount) > 0
            Text(
                text = if (hasBounce) BsaFormatters.formatInr(totalBounceAmt) else "Clean",
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (hasBounce) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                softWrap = false
            )
        }

        if (isOdFacility) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "OD Utilization (Peak / Avg)",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${BsaFormatters.formatPercent(month.highestOdUtilizationPct)} / ${BsaFormatters.formatPercent(month.avgOdUtilizationPct)}",
                            fontFamily = JetBrainsMono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (month.highestOdUtilizationPct / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = when {
                            month.highestOdUtilizationPct >= 95.0 -> MaterialTheme.colorScheme.error
                            month.highestOdUtilizationPct >= 80.0 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.secondary
                        }
                    )
                    Text(
                        text = "Overdrawn: ${month.overdrawnDays} days • Peak: ${BsaFormatters.formatInr(month.highestOdUtilizationAmt)} on ${month.highestOdUtilizationDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniCheckpoint(label: String, value: Double, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                text = BsaFormatters.formatInrCompact(value),
                fontFamily = JetBrainsMono,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FraudIndicatorCard(ind: FraudIndicatorResult) {
    var expanded by remember(ind.itemNo) { mutableStateOf(ind.status == IndicatorStatus.FLAGGED) }
    LedgerCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = ind.evidence.isNotEmpty()) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "#${ind.itemNo} • ${ind.title}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = ind.group.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IndicatorStatusPill(ind.status)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = ind.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AnimatedVisibility(visible = expanded && ind.evidence.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ind.evidence.forEach { line ->
                    Text(
                        text = "• $line",
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionRowCard(
    txn: BankTransaction,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (txn.hasBalanceMismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${txn.slNo} • ${txn.displayDate}",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = BsaFormatters.formatInr(txn.amount, showSign = true),
                    fontFamily = JetBrainsMono,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (txn.amount >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = txn.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (txn.isSelfTransfer) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        }
                    ) {
                        Text(
                            text = txn.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (txn.isSelfTransfer) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = "Bal: ${BsaFormatters.formatInr(txn.balance)}",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = if (txn.hasBalanceMismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LoanTrackSectionCard(report: AnalysisReport) {
    LedgerCard {
        Text(
            text = "Loan & EMI Obligation Track (${report.loanTrack.size})",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        if (report.loanTrack.isEmpty()) {
            Text(
                text = "No recurring Loan / Home Loan EMI debits detected in this statement period.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                report.loanTrack.forEach { loan ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = BsaFormatters.formatInr(loan.emiAmount),
                                    fontFamily = JetBrainsMono,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "${loan.category} • ${loan.totalInstallments} paid",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                text = loan.lenderHint,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Dates: ${loan.monthToPaymentDate.values.joinToString("  |  ")}",
                                fontFamily = JetBrainsMono,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupedPartyCard(party: GroupedPartyRow) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = party.partyOrCategory,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${party.txnCount} txn(s) • Cr ${BsaFormatters.formatInrCompact(party.totalCredits)} | Dr ${BsaFormatters.formatInrCompact(party.totalDebits)}",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = BsaFormatters.formatInrCompact(party.netFlow),
                fontFamily = JetBrainsMono,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                color = if (party.netFlow >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
        }
    }
}
