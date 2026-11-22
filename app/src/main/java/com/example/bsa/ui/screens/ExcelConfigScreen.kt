package com.example.bsa.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.bsa.model.ExcelDateFormat
import com.example.bsa.model.ExcelExportConfig
import com.example.bsa.ui.components.LedgerCard
import com.example.bsa.ui.components.YourViewAccentButton
import com.example.bsa.ui.components.YourViewOutlinedButton
import com.example.bsa.ui.components.YvBrandEndorsementFooter
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.SovereignNavy

private val TOP_LIMIT_OPTIONS = listOf(10, 20, 30, 50)

@Composable
fun ExcelConfigScreen(
    config: ExcelExportConfig,
    hasActiveReport: Boolean,
    isExporting: Boolean,
    onDateFormatChange: (ExcelDateFormat) -> Unit,
    onConfigChange: ((ExcelExportConfig) -> ExcelExportConfig) -> Unit,
    onResetDefaults: () -> Unit,
    onBack: () -> Unit,
    onExportNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeHeaders = remember(config) { config.activeLedgerColumnHeaders(isRanked = false) }
    val defaultConfig = remember { ExcelExportConfig() }
    val isCustomized = config != defaultConfig

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header Bar with Back + Reset
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("excel_config_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.excel_config_title),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.excel_config_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    onClick = onResetDefaults,
                    enabled = isCustomized,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("excel_config_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_reset_defaults))
                }
            }

            // 2. Live 11-Tab Spreadsheet Preview Card
            SpreadsheetLivePreviewCard(
                config = config,
                activeHeaders = activeHeaders
            )

            // 3. Preferred Date Format Selector Card
            LedgerCard {
                ConfigSectionTitle(
                    icon = Icons.Filled.CalendarMonth,
                    title = "Preferred Date Format",
                    badgeText = config.dateFormat.pattern
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Applied across Summary metadata, Transactions, Deep Analytics, and 18-tab .xlsx workbook sheets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExcelDateFormat.entries.forEach { fmt ->
                        val selected = config.dateFormat == fmt
                        DateFormatOptionRow(
                            format = fmt,
                            selected = selected,
                            onSelect = { onDateFormatChange(fmt) }
                        )
                    }
                }
            }

            // 4. Ledger Columns Toggle Card (Tabs 5–8)
            LedgerCard {
                ConfigSectionTitle(
                    icon = Icons.Filled.ViewColumn,
                    title = "Ledger Columns (Tabs 5–8)",
                    badgeText = "${activeHeaders.size} active"
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Customize which columns appear in Transactions, Top Credits, Top Debits, and Self Transfers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                // Locked-on mandatory core columns
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Core:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf("Sl. No. / Rank", "Date", "Description", "Balance").forEach { col ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = col,
                                    fontFamily = JetBrainsMono,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                ConfigSwitchRow(
                    title = "Split Withdrawal (Dr) & Deposit (Cr)",
                    description = "Replace single signed Amount column with separate Withdrawal (Dr) and Deposit (Cr) columns.",
                    checked = config.splitDebitCreditColumns,
                    onCheckedChange = { checked ->
                        onConfigChange { it.copy(splitDebitCreditColumns = checked) }
                    },
                    testTag = "excel_toggle_split_drcr"
                )
                ConfigDivider()
                ConfigSwitchRow(
                    title = "Cheque / Ref No. Column",
                    description = "Include instrument or cheque reference numbers parsed from bank narrations.",
                    checked = config.includeChequeNoColumn,
                    onCheckedChange = { checked ->
                        onConfigChange { it.copy(includeChequeNoColumn = checked) }
                    },
                    testTag = "excel_toggle_cheque"
                )
                ConfigDivider()
                ConfigSwitchRow(
                    title = "Category Column",
                    description = "Include underwriting category tags (Home Loan, OD Interest, Transfer to Party, etc.).",
                    checked = config.includeCategoryColumn,
                    onCheckedChange = { checked ->
                        onConfigChange { it.copy(includeCategoryColumn = checked) }
                    },
                    testTag = "excel_toggle_category"
                )
                ConfigDivider()
                ConfigSwitchRow(
                    title = "Counterparty Name Column",
                    description = "Add extracted beneficiary or remitter party name as a dedicated filterable column.",
                    checked = config.includePartyColumn,
                    onCheckedChange = { checked ->
                        onConfigChange { it.copy(includePartyColumn = checked) }
                    },
                    testTag = "excel_toggle_party"
                )
                ConfigDivider()
                ConfigSwitchRow(
                    title = "Self-Transfer Flag Column",
                    description = "Add explicit Yes / No indicator for fuzzy-matched self-account transfers.",
                    checked = config.includeSelfTransferFlagColumn,
                    onCheckedChange = { checked ->
                        onConfigChange { it.copy(includeSelfTransferFlagColumn = checked) }
                    },
                    testTag = "excel_toggle_self_flag"
                )
                ConfigDivider()
                ConfigSwitchRow(
                    title = "Source ePDF Filename Column",
                    description = "Show which uploaded statement PDF each transaction row originated from.",
                    checked = config.includeSourceFileColumn,
                    onCheckedChange = { checked ->
                        onConfigChange { it.copy(includeSourceFileColumn = checked) }
                    },
                    testTag = "excel_toggle_source_file"
                )
            }

            // 5. Workbook Structure, Formulas & Top-N Card (All 11 Tabs)
            LedgerCard {
                ConfigSectionTitle(
                    icon = Icons.Filled.Functions,
                    title = "Workbook Structure & Formulas",
                    badgeText = "All 18 Tabs"
                )
                Spacer(Modifier.height(12.dp))

                ConfigSwitchRow(
                    title = "Live =SUM() & =AVERAGE() Formulas",
                    description = "Embed live Excel formulas with cached fallback values. Disable to export static numeric values only.",
                    checked = config.includeFormulas,
                    onCheckedChange = { checked ->
                        onConfigChange { it.copy(includeFormulas = checked) }
                    },
                    testTag = "excel_toggle_formulas"
                )
                ConfigDivider()
                ConfigSwitchRow(
                    title = "Freeze Header Rows",
                    description = "Lock table header rows at the top of each worksheet while scrolling in Excel or Sheets.",
                    checked = config.freezeHeaderRows,
                    onCheckedChange = { checked ->
                        onConfigChange { it.copy(freezeHeaderRows = checked) }
                    },
                    testTag = "excel_toggle_freeze"
                )
                ConfigDivider()
                ConfigSwitchRow(
                    title = "Extended Forensic Metadata (Tab 11)",
                    description = "Include PDF version, encryption status, SHA-256 hash, creation/mod timestamps, and image object counts.",
                    checked = config.includeForensicAuditDetails,
                    onCheckedChange = { checked ->
                        onConfigChange { it.copy(includeForensicAuditDetails = checked) }
                    },
                    testTag = "excel_toggle_forensic"
                )
                ConfigDivider()

                Text(
                    text = "Top Credits & Top Debits Row Limit (Tabs 6 & 7)",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Choose how many highest-value inflow and outflow transactions are listed in ranked sheets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TOP_LIMIT_OPTIONS.forEach { limit ->
                        val selected = config.topTransactionsLimit == limit
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onConfigChange { it.copy(topTransactionsLimit = limit) }
                                }
                                .testTag("excel_top_limit_$limit"),
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Top $limit",
                                    fontFamily = JetBrainsMono,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // 6. Action Buttons
            if (hasActiveReport) {
                YourViewAccentButton(
                    text = stringResource(R.string.btn_export_customized_excel),
                    icon = Icons.Filled.TableChart,
                    onClick = onExportNow,
                    enabled = !isExporting,
                    isLoading = isExporting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("excel_config_export_now")
                )
            }

            YourViewOutlinedButton(
                text = if (hasActiveReport) "Save & Return to Dashboard" else "Save Configuration",
                icon = Icons.Filled.Check,
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("excel_config_done_button")
            )

            YvBrandEndorsementFooter()
        }
    }
}

@Composable
private fun SpreadsheetLivePreviewCard(
    config: ExcelExportConfig,
    activeHeaders: List<String>
) {
    val sampleDate = config.dateFormat.formatIso("2026-08-15")
    val sampleRow1 = remember(config) {
        buildList {
            add("1")
            add(sampleDate)
            if (config.includeChequeNoColumn) add("441298")
            add("NEFT/ALPHA INFRA CREDIT")
            if (config.splitDebitCreditColumns) {
                add("0.00")
                add("2,50,000.00")
            } else {
                add("+2,50,000.00")
            }
            if (config.includeCategoryColumn) add("Transfer from Alpha")
            if (config.includePartyColumn) add("Alpha Infra")
            if (config.includeSelfTransferFlagColumn) add("No")
            add("4,85,200.00")
            if (config.includeSourceFileColumn) add("stmt_aug.pdf")
        }
    }
    val sampleTotalRow = remember(config) {
        buildList {
            add("TOTAL")
            add("")
            if (config.includeChequeNoColumn) add("")
            add(if (config.includeFormulas) "Live Excel Formula" else "Static Value")
            if (config.splitDebitCreditColumns) {
                add(if (config.includeFormulas) "=SUM(E4:E99)" else "1,25,000.00")
                add(if (config.includeFormulas) "=SUM(F4:F99)" else "2,50,000.00")
            } else {
                add(if (config.includeFormulas) "=SUM(E4:E99)" else "1,25,000.00")
            }
            if (config.includeCategoryColumn) add("")
            if (config.includePartyColumn) add("")
            if (config.includeSelfTransferFlagColumn) add("")
            add("")
            if (config.includeSourceFileColumn) add("")
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("excel_live_preview_card"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Sheet tab banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SovereignNavy)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.GridOn,
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Live Preview • Tab 5",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PreviewBadge(
                        text = if (config.freezeHeaderRows) "Frozen" else "Unfrozen",
                        bg = Color(0x33FFFFFF),
                        fg = Color(0xFFBFDBFE)
                    )
                    PreviewBadge(
                        text = if (config.includeFormulas) "fx Live" else "Values",
                        bg = if (config.includeFormulas) Color(0x3310B981) else Color(0x33F59E0B),
                        fg = if (config.includeFormulas) Color(0xFF6EE7B7) else Color(0xFFFDE68A)
                    )
                }
            }

            // Horizontally scrollable miniature worksheet grid
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(12.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                ) {
                    activeHeaders.forEach { header ->
                        PreviewCell(
                            text = header,
                            width = columnWidthFor(header),
                            bold = true,
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                // Sample Transaction Row
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                ) {
                    activeHeaders.forEachIndexed { idx, header ->
                        val cellText = sampleRow1.getOrElse(idx) { "" }
                        val cellColor = when {
                            header == "Amount" || header == "Deposit (Cr)" -> MaterialTheme.colorScheme.secondary
                            header == "Date" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        PreviewCell(
                            text = cellText,
                            width = columnWidthFor(header),
                            bold = header == "Date",
                            textColor = cellColor
                        )
                    }
                }
                // Total / Formula Row
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                ) {
                    activeHeaders.forEachIndexed { idx, header ->
                        PreviewCell(
                            text = sampleTotalRow.getOrElse(idx) { "" },
                            width = columnWidthFor(header),
                            bold = true,
                            textColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Compact footer summary across remaining tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Top Credits/Debits: Top ${config.topTransactionsLimit} • Tab 11: ${if (config.includeForensicAuditDetails) "14 Forensic Cols" else "7 Compact Cols"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun columnWidthFor(header: String): Dp = when (header) {
    "Sl. No.", "Rank" -> 58.dp
    "Date" -> 96.dp
    "Cheque No." -> 88.dp
    "Description" -> 164.dp
    "Withdrawal (Dr)", "Deposit (Cr)" -> 114.dp
    "Amount", "Balance" -> 104.dp
    "Category" -> 136.dp
    "Counterparty" -> 106.dp
    "Self Transfer" -> 86.dp
    "Source ePDF" -> 106.dp
    else -> 96.dp
}

@Composable
private fun PreviewCell(
    text: String,
    width: Dp,
    bold: Boolean,
    textColor: Color
) {
    Box(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMono,
            fontSize = 10.5.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PreviewBadge(text: String, bg: Color, fg: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DateFormatOptionRow(
    format: ExcelDateFormat,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .testTag("excel_date_fmt_${format.name.lowercase()}"),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = format.pattern,
                        fontFamily = JetBrainsMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when (format) {
                            ExcelDateFormat.DD_MMM_YY -> "Standard Indian Banking Format (Default)"
                            ExcelDateFormat.DD_MM_YYYY -> "Indian Slash Format (Day First)"
                            ExcelDateFormat.DD_DASH_MM_YYYY -> "Hyphenated Day-Month-Year"
                            ExcelDateFormat.YYYY_MM_DD -> "ISO-8601 Sortable Format"
                            ExcelDateFormat.MM_DD_YYYY -> "US Month-First Slash Format"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ) {
                Text(
                    text = format.sampleDisplay,
                    fontFamily = JetBrainsMono,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ConfigSectionTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    badgeText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        ) {
            Text(
                text = badgeText,
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun ConfigSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun ConfigDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
