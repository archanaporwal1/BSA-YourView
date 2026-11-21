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
import com.example.bsa.model.*
import com.example.bsa.ui.BsaUiState
import com.example.bsa.ui.ReportSubTab
import com.example.bsa.ui.components.*
import com.example.ui.theme.JetBrainsMono

@Composable
fun DashboardScreen(
    uiState: BsaUiState,
    onSelectSubTab: (ReportSubTab) -> Unit,
    onExportExcel: () -> Unit,
    onShareExcel: () -> Unit,
    onOpenExcelConfig: () -> Unit,
    onApplyDateFilter: (String?, String?) -> Unit,
    onLedgerSearchChange: (String) -> Unit,
    onLedgerCategoryChange: (String) -> Unit,
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
                    Button(
                        onClick = onGoToAnalyze,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Go to Statement Intake")
                    }
                    YvBrandEndorsementFooter()
                }
            }
        }
        return
    }

    val categories = remember(report.transactions) {
        listOf("All", "Self Transfers") + report.transactions.map { it.category }.distinct().sorted()
    }

    val filteredTransactions by remember(
        report.transactions,
        uiState.ledgerSearchQuery,
        uiState.ledgerCategoryFilter
    ) {
        derivedStateOf {
            report.transactions.filter { t ->
                val matchesCat = when (uiState.ledgerCategoryFilter) {
                    "All" -> true
                    "Self Transfers" -> t.isSelfTransfer
                    else -> t.category == uiState.ledgerCategoryFilter
                }
                val q = uiState.ledgerSearchQuery.trim()
                val matchesQuery = q.isBlank() ||
                    t.description.contains(q, ignoreCase = true) ||
                    t.category.contains(q, ignoreCase = true) ||
                    t.partyName.contains(q, ignoreCase = true) ||
                    t.displayDate.contains(q, ignoreCase = true)
                matchesCat && matchesQuery
            }
        }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onExportExcel,
                        enabled = !uiState.isExporting,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_excel_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    ) {
                        Icon(Icons.Filled.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (uiState.isExporting) "Building .xlsx..." else stringResource(R.string.btn_export_excel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onShareExcel,
                        enabled = !uiState.isExporting,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("share_excel_button")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_share_excel))
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

        // 1a. Color-Coded 10-Point Risk Score Summary Card
        item {
            RiskScoreSummaryCard(
                indicators = report.fraudIndicators,
                onInspectTriggers = { onSelectSubTab(ReportSubTab.FRAUD) }
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
                    text = "Output & 11-tab Excel cover strictly: ${report.metadata.statementPeriod}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
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

        // 2. Executive KPI Strip
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
                    subtitle = "${report.transactions.count { it.amount > 0 }} inflows",
                    accentColor = MaterialTheme.colorScheme.secondary
                )
                KpiMetricTile(
                    label = "Total Debits",
                    value = BsaFormatters.formatCurrency(report.totalOutflow, useCompact),
                    subtitle = "${report.transactions.count { it.amount < 0 }} outflows",
                    accentColor = MaterialTheme.colorScheme.error
                )
                KpiMetricTile(
                    label = "Avg EOD Balance",
                    value = BsaFormatters.formatCurrency(report.averageMonthlyBalance, useCompact),
                    subtitle = "${report.monthlyMetrics.size} month(s)",
                    accentColor = MaterialTheme.colorScheme.primary
                )
                if (report.config.facilityType != FacilityType.NONE) {
                    KpiMetricTile(
                        label = "Peak OD Util",
                        value = BsaFormatters.formatPercent(report.peakOdUtilizationPct),
                        subtitle = "${BsaFormatters.formatCurrency(report.peakOdUtilizationAmt, useCompact)} on ${report.peakOdUtilizationDate}",
                        accentColor = if (report.peakOdUtilizationPct >= 90.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    )
                }
                KpiMetricTile(
                    label = "Fraud / Behav Flags",
                    value = "${report.flaggedFraudCount} / 10",
                    subtitle = "EMI Total ${BsaFormatters.formatCurrency(report.totalEmiOutflow, useCompact)}",
                    accentColor = if (report.flaggedFraudCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                )
            }
        }

        // 3. Report Deep-Dive Sub-Tab Selector
        item {
            ScrollableTabRow(
                selectedTabIndex = ReportSubTab.entries.indexOf(uiState.activeReportTab),
                edgePadding = 0.dp,
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
                                fontWeight = if (uiState.activeReportTab == tab) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                }
            }
        }

        // 4. Sub-Tab Content
        when (uiState.activeReportTab) {
            ReportSubTab.OVERVIEW -> {
                item { OnlineEnrichmentSummaryCard(report.onlineEnrichment) }
                item { ForensicAuditAccordionCard(report) }
                item { MonthlyCashFlowChartCard(report) }
                items(report.monthlyMetrics, key = { it.monthKey }) { month ->
                    MonthlyDetailCard(month = month, isOdFacility = report.config.facilityType != FacilityType.NONE)
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
                            placeholder = { Text("Search narration, date, party, or category...") },
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
                                    label = { Text(cat) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Showing ${filteredTransactions.size} of ${report.transactions.size} deduplicated rows",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(filteredTransactions, key = { it.slNo }) { txn ->
                    TransactionRowCard(txn)
                }
            }

            ReportSubTab.PARTIES -> {
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
                    text = "Forensic ePDF Verification Audit (${report.fileAudits.size} file)",
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
                                horizontalArrangement = Arrangement.SpaceBetween,
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Monthly Inflow vs Outflow Trend",
                style = MaterialTheme.typography.titleMedium
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

        // Key checkpoint balances (2nd, 10th, 20th, Month End)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniCheckpoint("Bal 2nd", month.balAsOn2nd, Modifier.weight(1f))
            MiniCheckpoint("Bal 10th", month.balAsOn10th, Modifier.weight(1f))
            MiniCheckpoint("Bal 20th", month.balAsOn20th, Modifier.weight(1f))
            MiniCheckpoint("Month End", month.balAsOnMonthEnd, Modifier.weight(1f))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Credits (${month.creditCount})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(BsaFormatters.formatInr(month.totalCredits), fontFamily = JetBrainsMono, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Debits (${month.debitCount})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(BsaFormatters.formatInr(month.totalDebits), fontFamily = JetBrainsMono, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("EMI / Loan Outflow (${month.emiLoanCount})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(BsaFormatters.formatInr(month.emiLoanOutflow), fontFamily = JetBrainsMono, fontSize = 12.5.sp)
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
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("OD Utilization (Peak / Avg)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${BsaFormatters.formatPercent(month.highestOdUtilizationPct)} / ${BsaFormatters.formatPercent(month.avgOdUtilizationPct)}",
                            fontFamily = JetBrainsMono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
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
            horizontalArrangement = Arrangement.SpaceBetween
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
private fun TransactionRowCard(txn: BankTransaction) {
    Surface(
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                Text(
                    text = "Bal: ${BsaFormatters.formatInr(txn.balance)}",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.5.sp,
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
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = BsaFormatters.formatInr(loan.emiAmount),
                                    fontFamily = JetBrainsMono,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${loan.category} • ${loan.totalInstallments} paid",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            horizontalArrangement = Arrangement.SpaceBetween
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = BsaFormatters.formatInrCompact(party.netFlow),
                fontFamily = JetBrainsMono,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (party.netFlow >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
        }
    }
}
