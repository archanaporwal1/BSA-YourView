package com.example.bsa.engine

import com.example.bsa.model.*
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Module E: Pure-Kotlin OOXML (.xlsx) 11-Tab Workbook Exporter
 * Generates valid Excel 2007+ OOXML workbooks with LIVE =SUM() and =AVERAGE() formulas,
 * styled headers, INR number formatting, frozen panes, and auto-width columns across all 11 tabs.
 * Exclusively branded: Bank Analysier -YV | YourView (yourview.in)
 */
object ExcelWorkbookWriter {

    private val SHEET_NAMES = listOf(
        "Summary",
        "Derived_Sheet",
        "EOD_Balances",
        "Loan_Track",
        "Transactions",
        "Top_Credits",
        "Top_Debits",
        "Self_Transfers",
        "Grouped_Parties",
        "Fraud_Indicators",
        "Statement_Metadata"
    )

    // Style indices matching xl/styles.xml below
    private const val S_DEFAULT = 0
    private const val S_BRAND_BANNER = 1
    private const val S_TABLE_HEADER = 2
    private const val S_SECTION_HEADER = 3
    private const val S_LABEL_BOLD = 4
    private const val S_CURRENCY = 5
    private const val S_CURRENCY_BOLD = 6
    private const val S_INT = 7
    private const val S_INT_BOLD = 8
    private const val S_PERCENT = 9
    private const val S_PERCENT_BOLD = 10
    private const val S_FLAG_RED = 11
    private const val S_FLAG_GREEN = 12
    private const val S_FLAG_AMBER = 13

    fun writeWorkbook(
        report: AnalysisReport,
        outputStream: OutputStream,
        excelConfig: ExcelExportConfig = ExcelExportConfig(),
        onProgress: (completedStep: Int, totalSteps: Int, stageLabel: String) -> Unit = { _, _, _ -> }
    ) {
        val totalSteps = SHEET_NAMES.size + 1 // 11 sheets + ZIP packaging
        val topLimit = excelConfig.topTransactionsLimit.coerceIn(10, 50)
        val topCreditsList = report.transactions
            .filter { it.amount > 0 }
            .sortedByDescending { it.amount }
            .take(topLimit)
        val topDebitsList = report.transactions
            .filter { it.amount < 0 }
            .sortedBy { it.amount }
            .take(topLimit)

        val builders: List<() -> String> = listOf(
            { buildSummarySheet(report, includeApplicantBlock = true, excelConfig = excelConfig) },
            { buildSummarySheet(report, includeApplicantBlock = false, excelConfig = excelConfig) },
            { buildEodBalancesSheet(report, excelConfig) },
            { buildLoanTrackSheet(report, excelConfig) },
            { buildTransactionsSheet(report, excelConfig) },
            { buildTopTransactionsSheet("Top $topLimit Credit Inflows — BSA-YourView", topCreditsList, excelConfig) },
            { buildTopTransactionsSheet("Top $topLimit Debit Outflows — BSA-YourView", topDebitsList, excelConfig) },
            { buildTopTransactionsSheet("Self Transfer Ledger — BSA-YourView", report.selfTransfers, excelConfig) },
            { buildGroupedPartiesSheet(report, excelConfig) },
            { buildFraudIndicatorsSheet(report, excelConfig) },
            { buildStatementMetadataSheet(report, excelConfig) }
        )

        val sheetsXml = ArrayList<String>(SHEET_NAMES.size)
        builders.forEachIndexed { idx, buildFn ->
            val tabName = SHEET_NAMES[idx]
            onProgress(idx, totalSteps, "Generating Tab ${idx + 1}/11: $tabName")
            sheetsXml.add(buildFn())
            onProgress(idx + 1, totalSteps, "Completed Tab ${idx + 1}/11: $tabName")
        }

        onProgress(SHEET_NAMES.size, totalSteps, "Packaging 11-tab .xlsx workbook...")
        ZipOutputStream(outputStream).use { zos ->
            putEntry(zos, "[Content_Types].xml", buildContentTypesXml())
            putEntry(zos, "_rels/.rels", buildRootRelsXml())
            putEntry(zos, "xl/workbook.xml", buildWorkbookXml())
            putEntry(zos, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml())
            putEntry(zos, "xl/styles.xml", buildStylesXml())
            sheetsXml.forEachIndexed { idx, xml ->
                putEntry(zos, "xl/worksheets/sheet${idx + 1}.xml", xml)
            }
        }
        onProgress(totalSteps, totalSteps, "11-tab workbook ready")
    }

    // -------------------------------------------------------------------------
    // Tab 1 (Summary) & Tab 2 (Derived_Sheet) — Exact 35-Row Reverse-Chron Metrics
    // -------------------------------------------------------------------------
    private fun buildSummarySheet(
        report: AnalysisReport,
        includeApplicantBlock: Boolean,
        excelConfig: ExcelExportConfig
    ): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) (if (includeApplicantBlock) 12 else 4) else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        val months = report.monthlyMetrics
        val monthCount = months.size
        val firstMonthCol = 2 // Column B
        val lastMonthCol = 1 + monthCount
        val totalCol = lastMonthCol + 1
        val avgCol = lastMonthCol + 2

        val colWidths = mutableListOf(38.0)
        repeat(monthCount.coerceAtLeast(1)) { colWidths.add(16.0) }
        colWidths.add(18.0) // Total
        colWidths.add(18.0) // Average
        sb.setColumnWidths(colWidths)

        val generatedDate = SimpleDateFormat("${excelConfig.dateFormat.pattern} HH:mm", Locale.US)
            .format(Date(report.generatedAtMillis))

        if (includeApplicantBlock) {
            sb.addRow(listOf(CellSpec.str("BSA-YourView | UNDERWRITING SUMMARY REPORT — YourView (yourview.in)", S_BRAND_BANNER)))
            sb.addRow(listOf(
                CellSpec.str("Applicant ID", S_LABEL_BOLD), CellSpec.str(report.config.applicantId),
                CellSpec.str("Source Name", S_LABEL_BOLD), CellSpec.str(report.config.sourceName),
                CellSpec.str("Generated On", S_LABEL_BOLD), CellSpec.str(generatedDate)
            ))
            sb.addRow(listOf(
                CellSpec.str("Product Type", S_LABEL_BOLD), CellSpec.str(report.config.productType.displayName),
                CellSpec.str("Facility Type", S_LABEL_BOLD), CellSpec.str(report.config.facilityType.label),
                CellSpec.str("Forensic Status", S_LABEL_BOLD),
                CellSpec.str(
                    report.overallForensicStatus.label,
                    when (report.overallForensicStatus) {
                        ForensicStatus.VERIFIED -> S_FLAG_GREEN
                        ForensicStatus.WARNING -> S_FLAG_AMBER
                        ForensicStatus.REJECTED -> S_FLAG_RED
                    }
                )
            ))
            sb.addRow(listOf(
                CellSpec.str("Account Holder", S_LABEL_BOLD), CellSpec.str(report.metadata.holderName),
                CellSpec.str("Bank Name", S_LABEL_BOLD), CellSpec.str(report.metadata.bankName),
                CellSpec.str("IFSC Code", S_LABEL_BOLD), CellSpec.str(report.metadata.ifscCode)
            ))
            sb.addRow(listOf(
                CellSpec.str("Account Number", S_LABEL_BOLD), CellSpec.str(report.metadata.accountNumber),
                CellSpec.str("Statement Period", S_LABEL_BOLD), CellSpec.str(report.metadata.statementPeriod),
                CellSpec.str("Address", S_LABEL_BOLD), CellSpec.str(report.metadata.address)
            ))
            sb.addRow(listOf(
                CellSpec.str("Sanctioned OD Limit (INR)", S_LABEL_BOLD), CellSpec.num(report.config.sanctionedOdLimit, S_CURRENCY),
                CellSpec.str("Drawing Power Limit (INR)", S_LABEL_BOLD), CellSpec.num(report.config.drawingPowerLimit, S_CURRENCY),
                CellSpec.str("Flagged Indicators", S_LABEL_BOLD), CellSpec.num(report.flaggedFraudCount.toDouble(), S_INT)
            ))
            val branchText = report.onlineEnrichment.accountIfscInfo?.let { info ->
                listOf(info.branch, info.city, info.state).filter { it.isNotBlank() }.joinToString(", ")
            } ?: "Local Statement Header"
            sb.addRow(listOf(
                CellSpec.str("Processing Mode", S_LABEL_BOLD), CellSpec.str(report.onlineEnrichment.modeLabel),
                CellSpec.str("Verified Branch", S_LABEL_BOLD), CellSpec.str(branchText),
                CellSpec.str("Online Enriched Txns", S_LABEL_BOLD), CellSpec.num(report.onlineEnrichment.enrichedTransactionsCount.toDouble(), S_INT)
            ))
            sb.addEmptyRow()
            sb.addRow(listOf(CellSpec.str("MONTHWISE DETAILS (REVERSE CHRONOLOGICAL — 35 METRICS)", S_SECTION_HEADER)))
        } else {
            sb.addRow(listOf(CellSpec.str("DERIVED SHEET — CONSOLIDATED MULTI-FILE SUMMARY (BSA-YourView | yourview.in)", S_BRAND_BANNER)))
            sb.addRow(listOf(
                CellSpec.str("Applicant: ${report.config.applicantId} | ${report.metadata.holderName} | ${report.metadata.bankName} (${report.metadata.accountNumber})", S_LABEL_BOLD)
            ))
            sb.addEmptyRow()
        }

        // Table Header Row
        val headers = mutableListOf(CellSpec.str("Particulars (35 Underwriting Metrics)", S_TABLE_HEADER))
        months.forEach { headers.add(CellSpec.str(it.monthLabel, S_TABLE_HEADER)) }
        headers.add(CellSpec.str("Total", S_TABLE_HEADER))
        headers.add(CellSpec.str("Average", S_TABLE_HEADER))
        sb.addRow(headers)

        // Exact 35 metric definitions
        data class MetricSpec(
            val label: String,
            val style: Int,
            val boldStyle: Int,
            val supportsSum: Boolean,
            val isText: Boolean = false,
            val textFn: (MonthlyMetric) -> String = { "-" },
            val valueFn: (MonthlyMetric) -> Double = { 0.0 }
        )

        val specs = listOf(
            MetricSpec("1. Opening Balance", S_CURRENCY, S_CURRENCY_BOLD, false) { it.openingBalance },
            MetricSpec("2. Closing Balance", S_CURRENCY, S_CURRENCY_BOLD, false) { it.closingBalance },
            MetricSpec("3. Balance as on 2nd", S_CURRENCY, S_CURRENCY_BOLD, false) { it.balAsOn2nd },
            MetricSpec("4. Balance as on 10th", S_CURRENCY, S_CURRENCY_BOLD, false) { it.balAsOn10th },
            MetricSpec("5. Balance as on 20th", S_CURRENCY, S_CURRENCY_BOLD, false) { it.balAsOn20th },
            MetricSpec("6. Balance as on Month end", S_CURRENCY, S_CURRENCY_BOLD, false) { it.balAsOnMonthEnd },
            MetricSpec("7. Average EOD Balance", S_CURRENCY, S_CURRENCY_BOLD, false) { it.averageEodBalance },
            MetricSpec("8. Minimum EOD Balance", S_CURRENCY, S_CURRENCY_BOLD, false) { it.minEodBalance },
            MetricSpec("9. Maximum EOD Balance", S_CURRENCY, S_CURRENCY_BOLD, false) { it.maxEodBalance },
            MetricSpec("10. Total Credit Amount", S_CURRENCY, S_CURRENCY_BOLD, true) { it.totalCredits },
            MetricSpec("11. Total Credit Count", S_INT, S_INT_BOLD, true) { it.creditCount.toDouble() },
            MetricSpec("12. Total Debit Amount", S_CURRENCY, S_CURRENCY_BOLD, true) { it.totalDebits },
            MetricSpec("13. Total Debit Count", S_INT, S_INT_BOLD, true) { it.debitCount.toDouble() },
            MetricSpec("14. Net Cash Flow (Credits - Debits)", S_CURRENCY, S_CURRENCY_BOLD, true) { it.netCashFlow },
            MetricSpec("15. Cash Deposits Amount", S_CURRENCY, S_CURRENCY_BOLD, true) { it.cashDeposits },
            MetricSpec("16. Cash Deposit Count", S_INT, S_INT_BOLD, true) { it.cashDepositCount.toDouble() },
            MetricSpec("17. Cash Withdrawals Amount", S_CURRENCY, S_CURRENCY_BOLD, true) { it.cashWithdrawals },
            MetricSpec("18. Cash Withdrawal Count", S_INT, S_INT_BOLD, true) { it.cashWithdrawalCount.toDouble() },
            MetricSpec("19. Self Transfer Inflows", S_CURRENCY, S_CURRENCY_BOLD, true) { it.selfTransferIn },
            MetricSpec("20. Self Transfer Outflows", S_CURRENCY, S_CURRENCY_BOLD, true) { it.selfTransferOut },
            MetricSpec("21. Non-Self (Business) Credits", S_CURRENCY, S_CURRENCY_BOLD, true) { it.businessCredits },
            MetricSpec("22. Non-Self (Business) Debits", S_CURRENCY, S_CURRENCY_BOLD, true) { it.businessDebits },
            MetricSpec("23. Total Loan / EMI Outflow", S_CURRENCY, S_CURRENCY_BOLD, true) { it.emiLoanOutflow },
            MetricSpec("24. Loan / EMI Debit Count", S_INT, S_INT_BOLD, true) { it.emiLoanCount.toDouble() },
            MetricSpec("25. Credit Card Payments", S_CURRENCY, S_CURRENCY_BOLD, true) { it.creditCardPayments },
            MetricSpec("26. Interest Earned (Credits)", S_CURRENCY, S_CURRENCY_BOLD, true) { it.interestEarned },
            MetricSpec("27. OD Interest / Servicing Paid", S_CURRENCY, S_CURRENCY_BOLD, true) { it.odInterestServicing },
            MetricSpec("28. Reversals & Bounce Value", S_CURRENCY, S_CURRENCY_BOLD, true) { it.reversalsAndBounces },
            MetricSpec("29. Reversal / Bounce Count", S_INT, S_INT_BOLD, true) { it.reversalCount.toDouble() },
            MetricSpec("30. Sanctioned OD / CC Limit", S_CURRENCY, S_CURRENCY_BOLD, false) { report.config.sanctionedOdLimit },
            MetricSpec("31. Drawing Power (DP) Limit", S_CURRENCY, S_CURRENCY_BOLD, false) { report.config.drawingPowerLimit },
            MetricSpec("32. No of Overdrawn Days", S_INT, S_INT_BOLD, true) { it.overdrawnDays.toDouble() },
            MetricSpec("33. Average OD/CC Utilization %", S_PERCENT, S_PERCENT_BOLD, false) { it.avgOdUtilizationPct / 100.0 },
            MetricSpec("34. Highest OD/CC Utilization Amount", S_CURRENCY, S_CURRENCY_BOLD, false) { it.highestOdUtilizationAmt },
            MetricSpec(
                label = "35. Highest OD/CC Utilization Date",
                style = S_DEFAULT,
                boldStyle = S_LABEL_BOLD,
                supportsSum = false,
                isText = true,
                textFn = { it.highestOdUtilizationDate }
            )
        )

        for (spec in specs) {
            val rIdx = sb.nextRowIndex()
            val rowCells = mutableListOf<CellSpec>(CellSpec.str(spec.label, S_LABEL_BOLD))
            if (spec.isText) {
                months.forEach { m -> rowCells.add(CellSpec.str(spec.textFn(m))) }
                rowCells.add(CellSpec.str("-", S_LABEL_BOLD))
                rowCells.add(CellSpec.str(report.peakOdUtilizationDate, S_LABEL_BOLD))
            } else {
                val values = months.map { spec.valueFn(it) }
                values.forEach { v -> rowCells.add(CellSpec.num(v, spec.style)) }
                if (monthCount > 0) {
                    val range = "${colLetter(firstMonthCol)}$rIdx:${colLetter(lastMonthCol)}$rIdx"
                    if (spec.supportsSum) {
                        rowCells.add(CellSpec.formula("SUM($range)", values.sum(), spec.boldStyle))
                    } else {
                        rowCells.add(CellSpec.str("-", S_LABEL_BOLD))
                    }
                    rowCells.add(CellSpec.formula("AVERAGE($range)", values.average(), spec.boldStyle))
                } else {
                    rowCells.add(CellSpec.num(0.0, spec.boldStyle))
                    rowCells.add(CellSpec.num(0.0, spec.boldStyle))
                }
            }
            sb.addRow(rowCells)
        }

        // Unused warning guard on column vars
        if (totalCol < 0 || avgCol < 0) return ""
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 3: EOD_Balances (31-row x N-month matrix with =AVERAGE() / =MIN() / =MAX())
    // -------------------------------------------------------------------------
    private fun buildEodBalancesSheet(report: AnalysisReport, excelConfig: ExcelExportConfig): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        val months = report.eodMatrixMonths
        val widths = mutableListOf(14.0)
        repeat(months.size.coerceAtLeast(1)) { widths.add(16.0) }
        widths.add(18.0)
        sb.setColumnWidths(widths)

        sb.addRow(listOf(CellSpec.str("DAILY END-OF-DAY (EOD) BALANCE MATRIX — BSA-YourView | yourview.in", S_BRAND_BANNER)))
        sb.addEmptyRow()

        val header = mutableListOf(CellSpec.str("Day of Month", S_TABLE_HEADER))
        months.forEach { header.add(CellSpec.str(it, S_TABLE_HEADER)) }
        header.add(CellSpec.str("Row Average", S_TABLE_HEADER))
        sb.addRow(header)

        val firstDataRow = sb.nextRowIndex()
        for (day in 1..31) {
            val rIdx = sb.nextRowIndex()
            val row = mutableListOf<CellSpec>(CellSpec.num(day.toDouble(), S_INT_BOLD))
            val dayMap = report.eodMatrixByDay[day].orEmpty()
            val nonNullVals = mutableListOf<Double>()
            months.forEach { m ->
                val v = dayMap[m]
                if (v != null) {
                    row.add(CellSpec.num(v, S_CURRENCY))
                    nonNullVals.add(v)
                } else {
                    row.add(CellSpec.str(""))
                }
            }
            if (months.isNotEmpty() && nonNullVals.isNotEmpty()) {
                val range = "${colLetter(2)}$rIdx:${colLetter(1 + months.size)}$rIdx"
                row.add(CellSpec.formula("AVERAGE($range)", nonNullVals.average(), S_CURRENCY_BOLD))
            } else {
                row.add(CellSpec.str("-"))
            }
            sb.addRow(row)
        }
        val lastDataRow = firstDataRow + 30

        // Monthly Average EOD Footer Row with live =AVERAGE()
        val avgFooter = mutableListOf<CellSpec>(CellSpec.str("Monthly Avg", S_LABEL_BOLD))
        months.forEachIndexed { idx, m ->
            val col = colLetter(2 + idx)
            val colVals = (1..31).mapNotNull { d -> report.eodMatrixByDay[d]?.get(m) }
            avgFooter.add(
                CellSpec.formula(
                    "AVERAGE(${col}$firstDataRow:${col}$lastDataRow)",
                    if (colVals.isNotEmpty()) colVals.average() else 0.0,
                    S_CURRENCY_BOLD
                )
            )
        }
        sb.addRow(avgFooter)
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 4: Loan_Track (EMI Amounts as rows x Month/Year columns with payment dates)
    // -------------------------------------------------------------------------
    private fun buildLoanTrackSheet(report: AnalysisReport, excelConfig: ExcelExportConfig): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        val months = report.eodMatrixMonths
        val widths = mutableListOf(18.0, 28.0, 16.0)
        repeat(months.size.coerceAtLeast(1)) { widths.add(14.0) }
        widths.add(14.0)
        widths.add(18.0)
        sb.setColumnWidths(widths)

        sb.addRow(listOf(CellSpec.str("LOAN & EMI TRACKING MATRIX — BSA-YourView | yourview.in", S_BRAND_BANNER)))
        sb.addEmptyRow()

        val header = mutableListOf(
            CellSpec.str("EMI Amount (INR)", S_TABLE_HEADER),
            CellSpec.str("Lender / Narration Hint", S_TABLE_HEADER),
            CellSpec.str("Category", S_TABLE_HEADER)
        )
        months.forEach { header.add(CellSpec.str(it, S_TABLE_HEADER)) }
        header.add(CellSpec.str("Paid Count", S_TABLE_HEADER))
        header.add(CellSpec.str("Total Paid (INR)", S_TABLE_HEADER))
        sb.addRow(header)

        val firstDataRow = sb.nextRowIndex()
        for (loan in report.loanTrack) {
            val row = mutableListOf(
                CellSpec.num(loan.emiAmount, S_CURRENCY_BOLD),
                CellSpec.str(loan.lenderHint),
                CellSpec.str(loan.category)
            )
            months.forEach { m ->
                row.add(CellSpec.str(loan.monthToPaymentDate[m] ?: "-"))
            }
            row.add(CellSpec.num(loan.totalInstallments.toDouble(), S_INT))
            row.add(CellSpec.num(loan.totalAmountPaid, S_CURRENCY))
            sb.addRow(row)
        }

        if (report.loanTrack.isNotEmpty()) {
            val lastDataRow = sb.nextRowIndex() - 1
            val countCol = colLetter(4 + months.size)
            val totalCol = colLetter(5 + months.size)
            val footer = mutableListOf<CellSpec>(
                CellSpec.formula("SUM(A$firstDataRow:A$lastDataRow)", report.loanTrack.sumOf { it.emiAmount }, S_CURRENCY_BOLD),
                CellSpec.str("TOTAL MONTHLY EMI OBLIGATIONS", S_LABEL_BOLD),
                CellSpec.str("")
            )
            repeat(months.size) { footer.add(CellSpec.str("")) }
            footer.add(CellSpec.formula("SUM(${countCol}$firstDataRow:${countCol}$lastDataRow)", report.loanTrack.sumOf { it.totalInstallments }.toDouble(), S_INT_BOLD))
            footer.add(CellSpec.formula("SUM(${totalCol}$firstDataRow:${totalCol}$lastDataRow)", report.loanTrack.sumOf { it.totalAmountPaid }, S_CURRENCY_BOLD))
            sb.addRow(footer)
        } else {
            sb.addRow(listOf(CellSpec.str("No recurring Loan / Home Loan EMI debits detected in statement period.")))
        }
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 5: Transactions (Dynamic Column Layout & Preferred Date Format)
    // -------------------------------------------------------------------------
    private fun buildTransactionsSheet(report: AnalysisReport, excelConfig: ExcelExportConfig): String {
        return buildConfigurableLedgerSheet(
            bannerTitle = "COMPLETE DEDUPLICATED TRANSACTION LEDGER — BSA-YourView | yourview.in",
            items = report.transactions,
            isRanked = false,
            excelConfig = excelConfig
        )
    }

    // -------------------------------------------------------------------------
    // Tabs 6, 7, 8: Top_Credits, Top_Debits, Self_Transfers
    // -------------------------------------------------------------------------
    private fun buildTopTransactionsSheet(
        title: String,
        items: List<BankTransaction>,
        excelConfig: ExcelExportConfig
    ): String {
        return buildConfigurableLedgerSheet(
            bannerTitle = "$title | YourView (yourview.in)",
            items = items,
            isRanked = true,
            excelConfig = excelConfig
        )
    }

    private fun buildConfigurableLedgerSheet(
        bannerTitle: String,
        items: List<BankTransaction>,
        isRanked: Boolean,
        excelConfig: ExcelExportConfig
    ): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        val headers = excelConfig.activeLedgerColumnHeaders(isRanked = isRanked)
        val widths = headers.map { col ->
            when (col) {
                "Sl. No.", "Rank" -> 9.0
                "Date" -> 14.0
                "Cheque No." -> 14.0
                "Description" -> 50.0
                "Amount", "Withdrawal (Dr)", "Deposit (Cr)", "Balance" -> 18.0
                "Category", "Counterparty" -> 24.0
                "Self Transfer" -> 14.0
                "Source ePDF" -> 24.0
                else -> 16.0
            }
        }
        sb.setColumnWidths(widths)

        sb.addRow(listOf(CellSpec.str(bannerTitle, S_BRAND_BANNER)))
        sb.addEmptyRow()
        sb.addRow(headers.map { CellSpec.str(it, S_TABLE_HEADER) })

        val firstDataRow = sb.nextRowIndex()
        items.forEachIndexed { idx, t ->
            val row = mutableListOf<CellSpec>()
            row.add(CellSpec.num((if (isRanked) idx + 1 else t.slNo).toDouble(), S_INT))
            row.add(CellSpec.str(excelConfig.dateFormat.formatIso(t.dateIso)))
            if (excelConfig.includeChequeNoColumn) {
                row.add(CellSpec.str(t.chequeNo))
            }
            row.add(CellSpec.str(t.description))
            if (excelConfig.splitDebitCreditColumns) {
                val dr = if (t.amount < 0) -t.amount else 0.0
                val cr = if (t.amount > 0) t.amount else 0.0
                row.add(if (dr > 0.0) CellSpec.num(dr, S_CURRENCY) else CellSpec.str(""))
                row.add(if (cr > 0.0) CellSpec.num(cr, S_CURRENCY) else CellSpec.str(""))
            } else {
                row.add(CellSpec.num(t.amount, S_CURRENCY))
            }
            if (excelConfig.includeCategoryColumn) {
                row.add(CellSpec.str(t.category))
            }
            if (excelConfig.includePartyColumn) {
                row.add(CellSpec.str(t.partyName))
            }
            if (excelConfig.includeSelfTransferFlagColumn) {
                row.add(CellSpec.str(if (t.isSelfTransfer) "Yes" else "No"))
            }
            val balStyle = if (!isRanked && t.hasBalanceMismatch) S_FLAG_RED else S_CURRENCY
            row.add(CellSpec.num(t.balance, balStyle))
            if (excelConfig.includeSourceFileColumn) {
                row.add(CellSpec.str(t.sourceFile))
            }
            sb.addRow(row)
        }

        if (items.isNotEmpty()) {
            val lastDataRow = sb.nextRowIndex() - 1
            val footer = mutableListOf<CellSpec>()
            headers.forEachIndexed { cIdx, colName ->
                val col = colLetter(cIdx + 1)
                when (colName) {
                    "Description" -> footer.add(
                        CellSpec.str(if (isRanked) "TOTAL / AVERAGE" else "NET TRANSACTION SUM", S_LABEL_BOLD)
                    )
                    "Amount" -> footer.add(
                        CellSpec.formula(
                            "SUM(${col}$firstDataRow:${col}$lastDataRow)",
                            items.sumOf { it.amount },
                            S_CURRENCY_BOLD
                        )
                    )
                    "Withdrawal (Dr)" -> footer.add(
                        CellSpec.formula(
                            "SUM(${col}$firstDataRow:${col}$lastDataRow)",
                            items.filter { it.amount < 0 }.sumOf { -it.amount },
                            S_CURRENCY_BOLD
                        )
                    )
                    "Deposit (Cr)" -> footer.add(
                        CellSpec.formula(
                            "SUM(${col}$firstDataRow:${col}$lastDataRow)",
                            items.filter { it.amount > 0 }.sumOf { it.amount },
                            S_CURRENCY_BOLD
                        )
                    )
                    "Balance" -> footer.add(
                        CellSpec.formula(
                            "AVERAGE(${col}$firstDataRow:${col}$lastDataRow)",
                            items.map { it.balance }.average(),
                            S_CURRENCY_BOLD
                        )
                    )
                    else -> footer.add(CellSpec.str(""))
                }
            }
            sb.addRow(footer)
        } else {
            sb.addRow(listOf(CellSpec.str("No matching transactions in this category.")))
        }
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 9: Grouped_Parties
    // -------------------------------------------------------------------------
    private fun buildGroupedPartiesSheet(report: AnalysisReport, excelConfig: ExcelExportConfig): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        sb.setColumnWidths(listOf(36.0, 14.0, 18.0, 18.0, 18.0))

        sb.addRow(listOf(CellSpec.str("GROUPED COUNTERPARTIES & CATEGORIES — BSA-YourView | yourview.in", S_BRAND_BANNER)))
        sb.addEmptyRow()

        sb.addRow(listOf(
            CellSpec.str("Party / Category", S_TABLE_HEADER),
            CellSpec.str("Txn Count", S_TABLE_HEADER),
            CellSpec.str("Total Credits (INR)", S_TABLE_HEADER),
            CellSpec.str("Total Debits (INR)", S_TABLE_HEADER),
            CellSpec.str("Net Flow (INR)", S_TABLE_HEADER)
        ))

        val firstDataRow = sb.nextRowIndex()
        for (p in report.groupedParties) {
            sb.addRow(listOf(
                CellSpec.str(p.partyOrCategory),
                CellSpec.num(p.txnCount.toDouble(), S_INT),
                CellSpec.num(p.totalCredits, S_CURRENCY),
                CellSpec.num(p.totalDebits, S_CURRENCY),
                CellSpec.num(p.netFlow, S_CURRENCY)
            ))
        }

        if (report.groupedParties.isNotEmpty()) {
            val lastDataRow = sb.nextRowIndex() - 1
            sb.addRow(listOf(
                CellSpec.str("GRAND TOTAL", S_LABEL_BOLD),
                CellSpec.formula("SUM(B$firstDataRow:B$lastDataRow)", report.groupedParties.sumOf { it.txnCount }.toDouble(), S_INT_BOLD),
                CellSpec.formula("SUM(C$firstDataRow:C$lastDataRow)", report.groupedParties.sumOf { it.totalCredits }, S_CURRENCY_BOLD),
                CellSpec.formula("SUM(D$firstDataRow:D$lastDataRow)", report.groupedParties.sumOf { it.totalDebits }, S_CURRENCY_BOLD),
                CellSpec.formula("SUM(E$firstDataRow:E$lastDataRow)", report.groupedParties.sumOf { it.netFlow }, S_CURRENCY_BOLD)
            ))
        }
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 10: Fraud_Indicators (Items 1-5 Fraud + Items 6-10 Behavioural)
    // -------------------------------------------------------------------------
    private fun buildFraudIndicatorsSheet(report: AnalysisReport, excelConfig: ExcelExportConfig): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        sb.setColumnWidths(listOf(10.0, 26.0, 32.0, 18.0, 12.0, 12.0, 56.0))

        sb.addRow(listOf(CellSpec.str("FRAUD (1–5) & BEHAVIOURAL (6–10) INDICATORS — BSA-YourView | yourview.in", S_BRAND_BANNER)))
        sb.addEmptyRow()

        sb.addRow(listOf(
            CellSpec.str("Item #", S_TABLE_HEADER),
            CellSpec.str("Classification", S_TABLE_HEADER),
            CellSpec.str("Indicator Name", S_TABLE_HEADER),
            CellSpec.str("Status", S_TABLE_HEADER),
            CellSpec.str("Hit Count", S_TABLE_HEADER),
            CellSpec.str("Severity", S_TABLE_HEADER),
            CellSpec.str("Summary & Evidence", S_TABLE_HEADER)
        ))

        val firstDataRow = sb.nextRowIndex()
        for (ind in report.fraudIndicators) {
            val statusStyle = when (ind.status) {
                IndicatorStatus.FLAGGED -> if (ind.group == IndicatorGroup.FRAUD) S_FLAG_RED else S_FLAG_AMBER
                IndicatorStatus.CLEAN -> S_FLAG_GREEN
                IndicatorStatus.SUPPRESSED -> S_DEFAULT
            }
            val details = buildString {
                append(ind.summary)
                if (ind.evidence.isNotEmpty()) {
                    append(" | ")
                    append(ind.evidence.take(3).joinToString("; "))
                }
            }
            sb.addRow(listOf(
                CellSpec.num(ind.itemNo.toDouble(), S_INT_BOLD),
                CellSpec.str(ind.group.label),
                CellSpec.str(ind.title, S_LABEL_BOLD),
                CellSpec.str(ind.status.label, statusStyle),
                CellSpec.num(ind.triggeredCount.toDouble(), S_INT),
                CellSpec.str(ind.severity),
                CellSpec.str(details)
            ))
        }
        val lastDataRow = sb.nextRowIndex() - 1
        sb.addRow(listOf(
            CellSpec.str(""),
            CellSpec.str(""),
            CellSpec.str("TOTAL TRIGGERED EVENTS", S_LABEL_BOLD),
            CellSpec.str("${report.flaggedFraudCount} / 10 Flagged", S_LABEL_BOLD),
            CellSpec.formula("SUM(E$firstDataRow:E$lastDataRow)", report.fraudIndicators.sumOf { it.triggeredCount }.toDouble(), S_INT_BOLD),
            CellSpec.str(""),
            CellSpec.str("Audited offline by BSA-YourView (yourview.in)")
        ))
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 11: Statement_Metadata (File Forensic Audit Table)
    // -------------------------------------------------------------------------
    private fun buildStatementMetadataSheet(report: AnalysisReport, excelConfig: ExcelExportConfig): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        val fullDetails = excelConfig.includeForensicAuditDetails
        if (fullDetails) {
            sb.setColumnWidths(listOf(24.0, 20.0, 20.0, 26.0, 10.0, 16.0, 12.0, 14.0, 24.0, 24.0, 14.0, 16.0, 16.0, 44.0))
        } else {
            sb.setColumnWidths(listOf(24.0, 20.0, 20.0, 26.0, 10.0, 16.0, 44.0))
        }

        sb.addRow(listOf(CellSpec.str("STATEMENT METADATA & FORENSIC FILE AUDIT — BSA-YourView | yourview.in", S_BRAND_BANNER)))
        sb.addEmptyRow()

        if (fullDetails) {
            sb.addRow(listOf(
                CellSpec.str("File Name", S_TABLE_HEADER),
                CellSpec.str("Detected Bank", S_TABLE_HEADER),
                CellSpec.str("Account Number", S_TABLE_HEADER),
                CellSpec.str("Statement Period", S_TABLE_HEADER),
                CellSpec.str("Pages", S_TABLE_HEADER),
                CellSpec.str("Avg Chars/Page", S_TABLE_HEADER),
                CellSpec.str("PDF Ver", S_TABLE_HEADER),
                CellSpec.str("Encrypted", S_TABLE_HEADER),
                CellSpec.str("PDF /Producer", S_TABLE_HEADER),
                CellSpec.str("PDF /Creator", S_TABLE_HEADER),
                CellSpec.str("%%EOF Count", S_TABLE_HEADER),
                CellSpec.str("SHA-256", S_TABLE_HEADER),
                CellSpec.str("Forensic Status", S_TABLE_HEADER),
                CellSpec.str("Gatekeeper Findings", S_TABLE_HEADER)
            ))
        } else {
            sb.addRow(listOf(
                CellSpec.str("File Name", S_TABLE_HEADER),
                CellSpec.str("Detected Bank", S_TABLE_HEADER),
                CellSpec.str("Account Number", S_TABLE_HEADER),
                CellSpec.str("Statement Period", S_TABLE_HEADER),
                CellSpec.str("Pages", S_TABLE_HEADER),
                CellSpec.str("Forensic Status", S_TABLE_HEADER),
                CellSpec.str("Gatekeeper Findings", S_TABLE_HEADER)
            ))
        }

        val firstDataRow = sb.nextRowIndex()
        for (a in report.fileAudits) {
            val stStyle = when (a.status) {
                ForensicStatus.VERIFIED -> S_FLAG_GREEN
                ForensicStatus.WARNING -> S_FLAG_AMBER
                ForensicStatus.REJECTED -> S_FLAG_RED
            }
            if (fullDetails) {
                sb.addRow(listOf(
                    CellSpec.str(a.fileName, S_LABEL_BOLD),
                    CellSpec.str(a.detectedBank),
                    CellSpec.str(a.accountNumber),
                    CellSpec.str(a.statementPeriod),
                    CellSpec.num(a.pageCount.toDouble(), S_INT),
                    CellSpec.num(a.avgCharsPerPage.toDouble(), S_INT),
                    CellSpec.str(a.pdfVersion),
                    CellSpec.str(if (a.isPasswordProtected) "Yes" else "No"),
                    CellSpec.str(a.producer),
                    CellSpec.str(a.creator),
                    CellSpec.num(a.eofMarkerCount.toDouble(), S_INT),
                    CellSpec.str(a.sha256Short),
                    CellSpec.str(a.status.label, stStyle),
                    CellSpec.str(a.findings.joinToString(" | "))
                ))
            } else {
                sb.addRow(listOf(
                    CellSpec.str(a.fileName, S_LABEL_BOLD),
                    CellSpec.str(a.detectedBank),
                    CellSpec.str(a.accountNumber),
                    CellSpec.str(a.statementPeriod),
                    CellSpec.num(a.pageCount.toDouble(), S_INT),
                    CellSpec.str(a.status.label, stStyle),
                    CellSpec.str(a.findings.joinToString(" | "))
                ))
            }
        }
        if (report.fileAudits.isNotEmpty()) {
            val lastDataRow = sb.nextRowIndex() - 1
            if (fullDetails) {
                sb.addRow(listOf(
                    CellSpec.str("TOTAL / AVG", S_LABEL_BOLD),
                    CellSpec.str("${report.fileAudits.size} File(s)"),
                    CellSpec.str(""),
                    CellSpec.str(report.metadata.statementPeriod),
                    CellSpec.formula("SUM(E$firstDataRow:E$lastDataRow)", report.fileAudits.sumOf { it.pageCount }.toDouble(), S_INT_BOLD),
                    CellSpec.formula("AVERAGE(F$firstDataRow:F$lastDataRow)", report.fileAudits.map { it.avgCharsPerPage }.average(), S_INT_BOLD),
                    CellSpec.str(""),
                    CellSpec.str(""),
                    CellSpec.str(""),
                    CellSpec.str(""),
                    CellSpec.formula("SUM(K$firstDataRow:K$lastDataRow)", report.fileAudits.sumOf { it.eofMarkerCount }.toDouble(), S_INT_BOLD),
                    CellSpec.str(""),
                    CellSpec.str(report.overallForensicStatus.label, S_LABEL_BOLD),
                    CellSpec.str("Generated offline by BSA-YourView | YourView (yourview.in)")
                ))
            } else {
                sb.addRow(listOf(
                    CellSpec.str("TOTAL", S_LABEL_BOLD),
                    CellSpec.str("${report.fileAudits.size} File(s)"),
                    CellSpec.str(""),
                    CellSpec.str(report.metadata.statementPeriod),
                    CellSpec.formula("SUM(E$firstDataRow:E$lastDataRow)", report.fileAudits.sumOf { it.pageCount }.toDouble(), S_INT_BOLD),
                    CellSpec.str(report.overallForensicStatus.label, S_LABEL_BOLD),
                    CellSpec.str("Generated offline by BSA-YourView | YourView (yourview.in)")
                ))
            }
        }
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // OOXML Low-Level Builders
    // -------------------------------------------------------------------------
    private sealed class CellSpec(val style: Int) {
        class Str(val value: String, style: Int) : CellSpec(style)
        class Num(val value: Double, style: Int) : CellSpec(style)
        class Formula(val formula: String, val cachedValue: Double, style: Int) : CellSpec(style)

        companion object {
            fun str(v: String, style: Int = S_DEFAULT) = Str(v, style)
            fun num(v: Double, style: Int = S_DEFAULT) = Num(v, style)
            fun formula(f: String, cached: Double, style: Int = S_CURRENCY_BOLD) = Formula(f, cached, style)
        }
    }

    private class SheetBuilder(
        private val freezeRow: Int,
        private val includeFormulas: Boolean = true
    ) {
        private val rows = mutableListOf<List<CellSpec>>()
        private var colWidths: List<Double> = emptyList()

        fun setColumnWidths(widths: List<Double>) {
            colWidths = widths
        }

        fun nextRowIndex(): Int = rows.size + 1

        fun addRow(cells: List<CellSpec>) {
            rows.add(cells)
        }

        fun addEmptyRow() {
            rows.add(emptyList())
        }

        fun buildXml(): String {
            val sb = StringBuilder(8192)
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
            if (freezeRow > 0) {
                sb.append("<pane ySplit=\"$freezeRow\" topLeftCell=\"A${freezeRow + 1}\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            }
            sb.append("</sheetView></sheetViews>")
            if (colWidths.isNotEmpty()) {
                sb.append("<cols>")
                colWidths.forEachIndexed { idx, w ->
                    val colNum = idx + 1
                    sb.append("""<col min="$colNum" max="$colNum" width="$w" customWidth="1"/>""")
                }
                sb.append("</cols>")
            }
            sb.append("<sheetData>")
            rows.forEachIndexed { rIdx, rowCells ->
                val rowNum = rIdx + 1
                sb.append("""<row r="$rowNum">""")
                rowCells.forEachIndexed { cIdx, cell ->
                    val ref = "${colLetter(cIdx + 1)}$rowNum"
                    when (cell) {
                        is CellSpec.Str -> {
                            sb.append("""<c r="$ref" t="inlineStr" s="${cell.style}"><is><t xml:space="preserve">${escapeXml(cell.value)}</t></is></c>""")
                        }
                        is CellSpec.Num -> {
                            val safeNum = if (cell.value.isNaN() || cell.value.isInfinite()) 0.0 else cell.value
                            sb.append("""<c r="$ref" s="${cell.style}"><v>${formatNumXml(safeNum)}</v></c>""")
                        }
                        is CellSpec.Formula -> {
                            val safeCached = if (cell.cachedValue.isNaN() || cell.cachedValue.isInfinite()) 0.0 else cell.cachedValue
                            if (includeFormulas) {
                                sb.append("""<c r="$ref" s="${cell.style}"><f>${escapeXml(cell.formula)}</f><v>${formatNumXml(safeCached)}</v></c>""")
                            } else {
                                sb.append("""<c r="$ref" s="${cell.style}"><v>${formatNumXml(safeCached)}</v></c>""")
                            }
                        }
                    }
                }
                sb.append("</row>")
            }
            sb.append("</sheetData></worksheet>")
            return sb.toString()
        }
    }

    private fun colLetter(colIndex1Based: Int): String {
        var n = colIndex1Based
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun formatNumXml(v: Double): String {
        return String.format(Locale.ROOT, "%.4f", v)
            .trimEnd('0')
            .trimEnd('.')
            .ifBlank { "0" }
    }

    private fun escapeXml(input: String): String {
        val sb = StringBuilder(input.length + 16)
        for (ch in input) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> {
                    // Strip XML-illegal control characters
                    if (ch.code >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun putEntry(zos: ZipOutputStream, path: String, content: String) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildContentTypesXml(): String {
        val overrides = SHEET_NAMES.indices.joinToString("") { i ->
            """<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  $overrides
</Types>"""
    }

    private fun buildRootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun buildWorkbookXml(): String {
        val sheets = SHEET_NAMES.mapIndexed { idx, name ->
            """<sheet name="$name" sheetId="${idx + 1}" r:id="rId${idx + 1}"/>"""
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>$sheets</sheets>
</workbook>"""
    }

    private fun buildWorkbookRelsXml(): String {
        val sheetRels = SHEET_NAMES.indices.joinToString("") { idx ->
            """<Relationship Id="rId${idx + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${idx + 1}.xml"/>"""
        }
        val styleRelId = SHEET_NAMES.size + 1
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  $sheetRels
  <Relationship Id="rId$styleRelId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
    }

    private fun buildStylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <numFmts count="3">
    <numFmt numFmtId="164" formatCode="#,##0.00;(#,##0.00);&quot;0.00&quot;"/>
    <numFmt numFmtId="165" formatCode="#,##0"/>
    <numFmt numFmtId="166" formatCode="0.00%"/>
  </numFmts>
  <fonts count="7">
    <font><sz val="10"/><color rgb="FF0F172A"/><name val="Calibri"/></font>
    <font><b/><sz val="12"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
    <font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
    <font><b/><sz val="10"/><color rgb="FF0F2A4A"/><name val="Calibri"/></font>
    <font><b/><sz val="10"/><color rgb="FFB91C1C"/><name val="Calibri"/></font>
    <font><b/><sz val="10"/><color rgb="FF0B7A5C"/><name val="Calibri"/></font>
    <font><b/><sz val="10"/><color rgb="FFB45309"/><name val="Calibri"/></font>
  </fonts>
  <fills count="8">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF0F2A4A"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF1E3F6B"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFDCE7F5"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFFDE2E2"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFD2F3E7"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFFEF0D6"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFCBD5E1"/></left>
      <right style="thin"><color rgb="FFCBD5E1"/></right>
      <top style="thin"><color rgb="FFCBD5E1"/></top>
      <bottom style="thin"><color rgb="FFCBD5E1"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="14">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" applyBorder="1"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" applyFont="1" applyFill="1"/>
    <xf numFmtId="0" fontId="2" fillId="3" borderId="1" applyFont="1" applyFill="1" applyBorder="1"/>
    <xf numFmtId="0" fontId="3" fillId="4" borderId="1" applyFont="1" applyFill="1" applyBorder="1"/>
    <xf numFmtId="0" fontId="3" fillId="0" borderId="1" applyFont="1" applyBorder="1"/>
    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" applyNumberFormat="1" applyBorder="1"/>
    <xf numFmtId="164" fontId="3" fillId="4" borderId="1" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>
    <xf numFmtId="165" fontId="0" fillId="0" borderId="1" applyNumberFormat="1" applyBorder="1"/>
    <xf numFmtId="165" fontId="3" fillId="4" borderId="1" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>
    <xf numFmtId="166" fontId="0" fillId="0" borderId="1" applyNumberFormat="1" applyBorder="1"/>
    <xf numFmtId="166" fontId="3" fillId="4" borderId="1" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>
    <xf numFmtId="0" fontId="4" fillId="5" borderId="1" applyFont="1" applyFill="1" applyBorder="1"/>
    <xf numFmtId="0" fontId="5" fillId="6" borderId="1" applyFont="1" applyFill="1" applyBorder="1"/>
    <xf numFmtId="0" fontId="6" fillId="7" borderId="1" applyFont="1" applyFill="1" applyBorder="1"/>
  </cellXfs>
</styleSheet>"""
}
