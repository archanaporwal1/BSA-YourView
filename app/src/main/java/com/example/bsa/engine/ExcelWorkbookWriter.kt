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
        "Statement_Metadata",
        "Segment_Eligibility",
        "Internal_Credits_Contra",
        "Bounces_And_EMI_DeepDive",
        "Channel_And_Cash_Analysis",
        "Category_Spend_Analytics",
        "Counterparty_Concentration",
        "Underwriter_CAM_360"
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
        camProfile: UnderwriterCamProfile? = null,
        onProgress: (completedStep: Int, totalSteps: Int, stageLabel: String) -> Unit = { _, _, _ -> }
    ) {
        val totalSheets = SHEET_NAMES.size
        val totalSteps = totalSheets + 1 // 18 sheets + ZIP packaging
        val topLimit = excelConfig.topTransactionsLimit.coerceIn(10, 50)
        val topCreditsList = report.transactions
            .filter { it.amount > 0 }
            .sortedByDescending { it.amount }
            .take(topLimit)
        val topDebitsList = report.transactions
            .filter { it.amount < 0 }
            .sortedBy { it.amount }
            .take(topLimit)

        val intel = CreditIntelligenceEngine.evaluate(report)
        val deep = DeepUnderwritingAnalytics.analyze(report)
        val effectiveCam = camProfile ?: UnderwriterCamProfile.defaultForReport(intel)

        val builders: List<() -> String> = listOf(
            { buildSummarySheet(report, intel, includeApplicantBlock = true, excelConfig = excelConfig) },
            { buildSummarySheet(report, intel, includeApplicantBlock = false, excelConfig = excelConfig) },
            { buildEodBalancesSheet(report, excelConfig) },
            { buildLoanTrackSheet(report, excelConfig) },
            { buildTransactionsSheet(report, excelConfig) },
            { buildTopTransactionsSheet("Top $topLimit Credit Inflows — BSA-YourView", topCreditsList, excelConfig) },
            { buildTopTransactionsSheet("Top $topLimit Debit Outflows — BSA-YourView", topDebitsList, excelConfig) },
            { buildTopTransactionsSheet("Self Transfer Ledger — BSA-YourView", report.selfTransfers, excelConfig) },
            { buildGroupedPartiesSheet(report, excelConfig) },
            { buildFraudIndicatorsSheet(report, excelConfig) },
            { buildStatementMetadataSheet(report, excelConfig) },
            { buildSegmentEligibilitySheet(report, intel, excelConfig) },
            { buildInternalCreditsContraSheet(deep, excelConfig) },
            { buildBouncesAndEmiDeepDiveSheet(deep, excelConfig) },
            { buildChannelAndCashSheet(deep, excelConfig) },
            { buildCategorySpendAnalyticsSheet(deep, excelConfig) },
            { buildCounterpartyConcentrationSheet(deep, excelConfig) },
            { buildUnderwriterCam360Sheet(report, intel, deep, effectiveCam, excelConfig) }
        )

        val sheetsXml = ArrayList<String>(totalSheets)
        builders.forEachIndexed { idx, buildFn ->
            val tabName = SHEET_NAMES[idx]
            onProgress(idx, totalSteps, "Generating Tab ${idx + 1}/$totalSheets: $tabName")
            sheetsXml.add(buildFn())
            onProgress(idx + 1, totalSteps, "Completed Tab ${idx + 1}/$totalSheets: $tabName")
        }

        onProgress(totalSheets, totalSteps, "Packaging $totalSheets-tab .xlsx workbook...")
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
        onProgress(totalSteps, totalSteps, "$totalSheets-tab workbook ready")
    }

    // -------------------------------------------------------------------------
    // Tab 1 (Summary) & Tab 2 (Derived_Sheet) — Exact 35-Row Reverse-Chron Metrics
    // -------------------------------------------------------------------------
    private fun buildSummarySheet(
        report: AnalysisReport,
        intel: CreditIntelligenceSummary,
        includeApplicantBlock: Boolean,
        excelConfig: ExcelExportConfig
    ): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) (if (includeApplicantBlock) 10 else 4) else 0,
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
        val headers = mutableListOf(CellSpec.str("Particulars (41 Underwriting Metrics)", S_TABLE_HEADER))
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
            ),
            MetricSpec("36. Internal Credits Amount", S_CURRENCY, S_CURRENCY_BOLD, true) { it.internalCredits },
            MetricSpec("37. Internal Credits Count", S_INT, S_INT_BOLD, true) { it.internalCreditCount.toDouble() },
            MetricSpec("38. Cheque Bounce Amount", S_CURRENCY, S_CURRENCY_BOLD, true) { it.chequeBounceAmount },
            MetricSpec("39. Cheque Bounce Count", S_INT, S_INT_BOLD, true) { it.chequeBounceCount.toDouble() },
            MetricSpec("40. EMI Return / NACH Bounce Amount", S_CURRENCY, S_CURRENCY_BOLD, true) { it.emiReturnAmount },
            MetricSpec("41. EMI Return / NACH Bounce Count", S_INT, S_INT_BOLD, true) { it.emiReturnCount.toDouble() }
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

        // Executive Credit Intelligence & 4-Segment Loan Eligibility Matrix on Tab 1 (Summary)
        if (includeApplicantBlock) {
            val verdictStyle = when (intel.verdict) {
                UnderwritingVerdict.STRONG_APPROVE -> S_FLAG_GREEN
                UnderwritingVerdict.CONDITIONAL_REVIEW -> S_FLAG_AMBER
                UnderwritingVerdict.HIGH_RISK_DECLINE -> S_FLAG_RED
            }
            sb.addEmptyRow()
            sb.addRow(listOf(CellSpec.str("EXECUTIVE CREDIT INTELLIGENCE & UNDERWRITING VERDICT", S_SECTION_HEADER)))
            sb.addRow(listOf(
                CellSpec.str("Credit Health Score (0-100)", S_LABEL_BOLD),
                CellSpec.num(intel.creditHealthScore.toDouble(), S_INT_BOLD),
                CellSpec.str("Underwriting Verdict", S_LABEL_BOLD),
                CellSpec.str(intel.verdict.label, verdictStyle),
                CellSpec.str("Borrower Profile", S_LABEL_BOLD),
                CellSpec.str(intel.borrowerProfile.label)
            ))
            sb.addRow(listOf(
                CellSpec.str("FOIR (Fixed Obligation / Income)", S_LABEL_BOLD),
                CellSpec.num(intel.foirPercent / 100.0, S_PERCENT_BOLD),
                CellSpec.str("Cash Dependency %", S_LABEL_BOLD),
                CellSpec.num(intel.cashDependencyPercent / 100.0, S_PERCENT_BOLD),
                CellSpec.str("Cheque / NACH Bounces", S_LABEL_BOLD),
                CellSpec.num(intel.bounceAndReturnCount.toDouble(), if (intel.bounceAndReturnCount > 0) S_FLAG_RED else S_FLAG_GREEN)
            ))
            sb.addRow(listOf(
                CellSpec.str("Checkpoint ABB (2nd/10th/20th/End)", S_LABEL_BOLD),
                CellSpec.num(intel.avgBankBalanceAbb, S_CURRENCY_BOLD),
                CellSpec.str("Avg Monthly Business Inflow", S_LABEL_BOLD),
                CellSpec.num(intel.avgMonthlyBusinessCredits, S_CURRENCY_BOLD),
                CellSpec.str("Monthly EMI Track", S_LABEL_BOLD),
                CellSpec.num(intel.avgMonthlyEmiObligation, S_CURRENCY_BOLD)
            ))
            sb.addRow(listOf(
                CellSpec.str("Internal Credits (Excluded)", S_LABEL_BOLD),
                CellSpec.num(report.totalInternalCredits, S_CURRENCY_BOLD),
                CellSpec.str("Cheque Bounces (Count)", S_LABEL_BOLD),
                CellSpec.num(report.totalChequeBounceCount.toDouble(), if (report.totalChequeBounceCount > 0) S_FLAG_RED else S_FLAG_GREEN),
                CellSpec.str("EMI Returns / NACH (Count)", S_LABEL_BOLD),
                CellSpec.num(report.totalEmiReturnCount.toDouble(), if (report.totalEmiReturnCount > 0) S_FLAG_RED else S_FLAG_GREEN)
            ))

            sb.addEmptyRow()
            sb.addRow(listOf(CellSpec.str("4-SEGMENT LOAN ELIGIBILITY MATRIX (AUTO | BUSINESS | WORKING CAPITAL | HOME)", S_SECTION_HEADER)))
            sb.addRow(listOf(
                CellSpec.str("Loan Segment", S_TABLE_HEADER),
                CellSpec.str("Underwriting Program", S_TABLE_HEADER),
                CellSpec.str("Status", S_TABLE_HEADER),
                CellSpec.str("Conservative (INR)", S_TABLE_HEADER),
                CellSpec.str("Recommended (INR)", S_TABLE_HEADER),
                CellSpec.str("Max Eligible (INR)", S_TABLE_HEADER),
                CellSpec.str("Benchmark Terms", S_TABLE_HEADER),
                CellSpec.str("Limiting Factor", S_TABLE_HEADER)
            ))
            intel.segmentEligibilities.forEach { seg ->
                val statusStyle = when (seg.status) {
                    EligibilityStatus.ELIGIBLE -> S_FLAG_GREEN
                    EligibilityStatus.CONDITIONAL -> S_FLAG_AMBER
                    EligibilityStatus.INELIGIBLE -> S_FLAG_RED
                }
                val termsText = if (seg.isRevolvingFacility) {
                    "Revolving OD/CC @ ${seg.benchmarkRatePct}% p.a."
                } else {
                    "${seg.benchmarkTenureMonths}m @ ${seg.benchmarkRatePct}% p.a."
                }
                val segLabel = if (seg.productType == report.config.productType) {
                    "${seg.productType.displayName} [SELECTED]"
                } else seg.productType.displayName
                sb.addRow(listOf(
                    CellSpec.str(segLabel, S_LABEL_BOLD),
                    CellSpec.str(seg.programName),
                    CellSpec.str(seg.status.label, statusStyle),
                    CellSpec.num(seg.conservativeAmount, S_CURRENCY),
                    CellSpec.num(seg.recommendedAmount, S_CURRENCY_BOLD),
                    CellSpec.num(seg.maxEligibleAmount, S_CURRENCY),
                    CellSpec.str(termsText),
                    CellSpec.str(seg.limitingFactor)
                ))
            }
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
    // Tab 12: Segment_Eligibility (Detailed 4-Segment Formulas & Policy Notes)
    // -------------------------------------------------------------------------
    private fun buildSegmentEligibilitySheet(
        report: AnalysisReport,
        intel: CreditIntelligenceSummary,
        excelConfig: ExcelExportConfig
    ): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        sb.setColumnWidths(listOf(24.0, 28.0, 16.0, 18.0, 18.0, 18.0, 18.0, 22.0, 42.0))

        sb.addRow(listOf(CellSpec.str("4-SEGMENT LOAN ELIGIBILITY ENGINE — BSA-YourView | yourview.in", S_BRAND_BANNER)))
        sb.addRow(listOf(
            CellSpec.str("Applicant: ${report.config.applicantId} | Holder: ${report.metadata.holderName} | Profile: ${intel.borrowerProfile.label}", S_LABEL_BOLD)
        ))
        sb.addRow(listOf(
            CellSpec.str("Segment", S_TABLE_HEADER),
            CellSpec.str("Program / Policy", S_TABLE_HEADER),
            CellSpec.str("Status", S_TABLE_HEADER),
            CellSpec.str("Conservative (INR)", S_TABLE_HEADER),
            CellSpec.str("Recommended (INR)", S_TABLE_HEADER),
            CellSpec.str("Max Eligible (INR)", S_TABLE_HEADER),
            CellSpec.str("Est. Monthly Servicing", S_TABLE_HEADER),
            CellSpec.str("Benchmark Terms", S_TABLE_HEADER),
            CellSpec.str("Primary Limiting Factor", S_TABLE_HEADER)
        ))

        intel.segmentEligibilities.forEach { seg ->
            val stStyle = when (seg.status) {
                EligibilityStatus.ELIGIBLE -> S_FLAG_GREEN
                EligibilityStatus.CONDITIONAL -> S_FLAG_AMBER
                EligibilityStatus.INELIGIBLE -> S_FLAG_RED
            }
            val terms = if (seg.isRevolvingFacility) {
                "Revolving OD/CC @ ${seg.benchmarkRatePct}% p.a."
            } else {
                "${seg.benchmarkTenureMonths}m @ ${seg.benchmarkRatePct}% p.a."
            }
            sb.addRow(listOf(
                CellSpec.str(seg.productType.displayName, S_LABEL_BOLD),
                CellSpec.str(seg.programName),
                CellSpec.str(seg.status.label, stStyle),
                CellSpec.num(seg.conservativeAmount, S_CURRENCY),
                CellSpec.num(seg.recommendedAmount, S_CURRENCY_BOLD),
                CellSpec.num(seg.maxEligibleAmount, S_CURRENCY),
                CellSpec.num(seg.maxMonthlyServicing, S_CURRENCY),
                CellSpec.str(terms),
                CellSpec.str(seg.limitingFactor)
            ))
        }

        intel.segmentEligibilities.forEach { seg ->
            sb.addEmptyRow()
            sb.addRow(listOf(CellSpec.str("${seg.productType.displayName.uppercase(Locale.ROOT)} — STEP-BY-STEP FORMULA BREAKDOWN", S_SECTION_HEADER)))
            sb.addRow(listOf(
                CellSpec.str("Calculation Parameter", S_TABLE_HEADER),
                CellSpec.str("Computed Value / Norm", S_TABLE_HEADER)
            ))
            seg.breakdownRows.forEach { (k, v) ->
                sb.addRow(listOf(CellSpec.str(k, S_LABEL_BOLD), CellSpec.str(v)))
            }
            seg.policyNotes.forEachIndexed { i, note ->
                sb.addRow(listOf(CellSpec.str("Policy Note ${i + 1}", S_LABEL_BOLD), CellSpec.str(note)))
            }
        }
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 13: Internal_Credits_Contra (Internal Credits, Inflation & Contra Pairs)
    // -------------------------------------------------------------------------
    private fun buildInternalCreditsContraSheet(
        deep: DeepUnderwritingSummary,
        excelConfig: ExcelExportConfig
    ): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        sb.setColumnWidths(listOf(34.0, 20.0, 16.0, 22.0, 24.0, 24.0, 36.0))
        val itx = deep.internalTransfers

        sb.addRow(listOf(CellSpec.str("INTERNAL CREDITS, TRUE BTO RECONCILIATION & CONTRA ROTATION — BSA-YourView", S_BRAND_BANNER)))
        sb.addEmptyRow()
        sb.addRow(listOf(
            CellSpec.str("Reconciliation Metric", S_TABLE_HEADER),
            CellSpec.str("Amount / Value", S_TABLE_HEADER),
            CellSpec.str("Count / Share", S_TABLE_HEADER)
        ))

        sb.addRow(listOf(CellSpec.str("Gross Total Credits (All Inflows)", S_LABEL_BOLD), CellSpec.num(itx.grossTotalCredits, S_CURRENCY_BOLD), CellSpec.str("100.0%")))
        sb.addRow(listOf(CellSpec.str("Less: Internal / Self-Account Credits", S_LABEL_BOLD), CellSpec.num(itx.internalSelfCredits, S_CURRENCY), CellSpec.num(itx.internalSelfCreditCount.toDouble(), S_INT)))
        sb.addRow(listOf(CellSpec.str("Less: Credit Reversal Offsets", S_LABEL_BOLD), CellSpec.num(itx.reversalCreditOffsets, S_CURRENCY), CellSpec.str("Reversals")))
        sb.addRow(listOf(CellSpec.str("Net True Business Credits (Period)", S_LABEL_BOLD), CellSpec.num(itx.netTrueBusinessCredits, S_CURRENCY_BOLD), CellSpec.str("True BTO")))
        sb.addRow(listOf(CellSpec.str("Annualized True Business Turnover", S_LABEL_BOLD), CellSpec.num(itx.annualizedTrueBusinessCredits, S_CURRENCY_BOLD), CellSpec.str("12-Mo Run Rate")))
        val inflationStyle = when {
            itx.creditInflationPercent >= 25.0 -> S_FLAG_RED
            itx.creditInflationPercent >= 12.0 -> S_FLAG_AMBER
            else -> S_FLAG_GREEN
        }
        sb.addRow(listOf(
            CellSpec.str("Credit Inflation % (Internal + Reversals / Gross)", S_LABEL_BOLD),
            CellSpec.num(itx.creditInflationPercent / 100.0, S_PERCENT_BOLD),
            CellSpec.str(if (itx.creditInflationPercent >= 25.0) "High Inflation Alert" else "Normal", inflationStyle)
        ))
        sb.addRow(listOf(CellSpec.str("Internal / Self-Account Debits (Outflows)", S_LABEL_BOLD), CellSpec.num(itx.internalSelfDebits, S_CURRENCY), CellSpec.num(itx.internalSelfDebitCount.toDouble(), S_INT)))

        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("DETECTED CONTRA / CIRCULAR ROTATION PAIRS (±3 DAYS EXACT MATCH >= ₹10,000)", S_SECTION_HEADER)))
        sb.addRow(listOf(
            CellSpec.str("Credit Date", S_TABLE_HEADER),
            CellSpec.str("Debit Date", S_TABLE_HEADER),
            CellSpec.str("Days Gap", S_TABLE_HEADER),
            CellSpec.str("Matched Amount (INR)", S_TABLE_HEADER),
            CellSpec.str("Credit Source", S_TABLE_HEADER),
            CellSpec.str("Debit Destination", S_TABLE_HEADER),
            CellSpec.str("Underwriter Flag / Note", S_TABLE_HEADER)
        ))
        if (itx.contraRotationPairs.isNotEmpty()) {
            val startRow = sb.nextRowIndex()
            itx.contraRotationPairs.forEach { p ->
                sb.addRow(listOf(
                    CellSpec.str(p.creditDate),
                    CellSpec.str(p.debitDate),
                    CellSpec.num(p.daysApart.toDouble(), S_INT),
                    CellSpec.num(p.amount, S_CURRENCY_BOLD),
                    CellSpec.str(p.creditCounterparty),
                    CellSpec.str(p.debitCounterparty),
                    CellSpec.str(p.rotationNote, S_FLAG_AMBER)
                ))
            }
            val endRow = sb.nextRowIndex() - 1
            sb.addRow(listOf(
                CellSpec.str("TOTAL CONTRA ROTATION VOLUME", S_LABEL_BOLD),
                CellSpec.str("${itx.contraRotationPairs.size} Pair(s)"),
                CellSpec.str(""),
                CellSpec.formula("SUM(D$startRow:D$endRow)", itx.contraRotationPairs.sumOf { it.amount }, S_CURRENCY_BOLD)
            ))
        } else {
            sb.addRow(listOf(CellSpec.str("No matching contra or same-amount circular rotation pairs detected.", S_FLAG_GREEN)))
        }

        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("INTERNAL / SELF CREDITS LEDGER (EXCLUDED FROM TRUE BTO)", S_SECTION_HEADER)))
        sb.addRow(listOf(
            CellSpec.str("Date", S_TABLE_HEADER),
            CellSpec.str("Amount (INR)", S_TABLE_HEADER),
            CellSpec.str("Balance (INR)", S_TABLE_HEADER),
            CellSpec.str("Category", S_TABLE_HEADER),
            CellSpec.str("Counterparty", S_TABLE_HEADER),
            CellSpec.str("Source ePDF", S_TABLE_HEADER),
            CellSpec.str("Narration", S_TABLE_HEADER)
        ))
        if (itx.internalCreditTransactions.isNotEmpty()) {
            val startRow = sb.nextRowIndex()
            itx.internalCreditTransactions.forEach { t ->
                sb.addRow(listOf(
                    CellSpec.str(excelConfig.dateFormat.formatIso(t.dateIso)),
                    CellSpec.num(t.amount, S_CURRENCY),
                    CellSpec.num(t.balance, S_CURRENCY),
                    CellSpec.str(t.category),
                    CellSpec.str(t.partyName),
                    CellSpec.str(t.sourceFile),
                    CellSpec.str(t.description)
                ))
            }
            val endRow = sb.nextRowIndex() - 1
            sb.addRow(listOf(
                CellSpec.str("TOTAL INTERNAL CREDITS", S_LABEL_BOLD),
                CellSpec.formula("SUM(B$startRow:B$endRow)", itx.internalSelfCredits, S_CURRENCY_BOLD)
            ))
        } else {
            sb.addRow(listOf(CellSpec.str("Zero internal/self-account credits detected.", S_FLAG_GREEN)))
        }
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 14: Bounces_And_EMI_DeepDive (Bounce Register + EMI Servicing Health)
    // -------------------------------------------------------------------------
    private fun buildBouncesAndEmiDeepDiveSheet(
        deep: DeepUnderwritingSummary,
        excelConfig: ExcelExportConfig
    ): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        sb.setColumnWidths(listOf(24.0, 18.0, 18.0, 18.0, 20.0, 20.0, 44.0))
        val b = deep.bounces

        sb.addRow(listOf(CellSpec.str("BOUNCES, RETURNS & EMI REPAYMENT HEALTH DEEP-DIVE — BSA-YourView", S_BRAND_BANNER)))
        sb.addEmptyRow()
        sb.addRow(listOf(
            CellSpec.str("Bounce & Return KPI", S_TABLE_HEADER),
            CellSpec.str("Count / Metric", S_TABLE_HEADER),
            CellSpec.str("Status / Impact", S_TABLE_HEADER)
        ))
        sb.addRow(listOf(CellSpec.str("Total Bounce / Return / Penal Events", S_LABEL_BOLD), CellSpec.num(b.totalBounceCount.toDouble(), S_INT_BOLD), CellSpec.str(b.emiTrackRating, if (b.totalBounceCount == 0) S_FLAG_GREEN else if (b.totalBounceCount >= 3) S_FLAG_RED else S_FLAG_AMBER)))
        sb.addRow(listOf(CellSpec.str("Inward Cheque Bounces", S_LABEL_BOLD), CellSpec.num(b.inwardChequeBounces.toDouble(), S_INT), CellSpec.str(if (b.inwardChequeBounces > 0) "Severe Payment Dishonour" else "Clean")))
        sb.addRow(listOf(CellSpec.str("NACH / ECS / Mandate Bounces", S_LABEL_BOLD), CellSpec.num(b.nachEcsBounces.toDouble(), S_INT), CellSpec.str(if (b.nachEcsBounces > 0) "EMI / Mandate Failure" else "Clean")))
        sb.addRow(listOf(CellSpec.str("Outward Cheque Returns", S_LABEL_BOLD), CellSpec.num(b.outwardChequeReturns.toDouble(), S_INT), CellSpec.str("Buyer Cheque Return")))
        sb.addRow(listOf(CellSpec.str("Penal / Min-Balance Charges", S_LABEL_BOLD), CellSpec.num(b.penalChargeEvents.toDouble(), S_INT), CellSpec.str("Liquidity Discipline")))
        sb.addRow(listOf(CellSpec.str("Bounce Rate (% of Debit Txns)", S_LABEL_BOLD), CellSpec.num(b.bounceRatePercent / 100.0, S_PERCENT_BOLD), CellSpec.num(b.totalBounceAndPenaltyAmount, S_CURRENCY_BOLD)))

        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("EMI SERVICING HEALTH & BALANCE CUSHION MATRIX", S_SECTION_HEADER)))
        sb.addRow(listOf(
            CellSpec.str("Lender / Financer", S_TABLE_HEADER),
            CellSpec.str("Loan Type", S_TABLE_HEADER),
            CellSpec.str("Monthly EMI (INR)", S_TABLE_HEADER),
            CellSpec.str("Installments", S_TABLE_HEADER),
            CellSpec.str("Total Paid (INR)", S_TABLE_HEADER),
            CellSpec.str("Avg Bal Cushion", S_TABLE_HEADER),
            CellSpec.str("Repayment Health Status", S_TABLE_HEADER)
        ))
        if (b.emiHealthRecords.isNotEmpty()) {
            val startRow = sb.nextRowIndex()
            b.emiHealthRecords.forEach { rec ->
                val stStyle = when {
                    rec.relatedBounceCount > 0 -> S_FLAG_RED
                    rec.repaymentStatus.contains("Watchlist") -> S_FLAG_AMBER
                    else -> S_FLAG_GREEN
                }
                sb.addRow(listOf(
                    CellSpec.str(rec.lenderHint, S_LABEL_BOLD),
                    CellSpec.str(rec.category),
                    CellSpec.num(rec.monthlyEmi, S_CURRENCY_BOLD),
                    CellSpec.num(rec.installmentsObserved.toDouble(), S_INT),
                    CellSpec.num(rec.totalPaid, S_CURRENCY),
                    CellSpec.num(rec.avgBalanceCushionAfterEmi, S_CURRENCY),
                    CellSpec.str(rec.repaymentStatus, stStyle)
                ))
            }
            val endRow = sb.nextRowIndex() - 1
            sb.addRow(listOf(
                CellSpec.str("TOTAL MONTHLY EMI LOAD", S_LABEL_BOLD),
                CellSpec.str(""),
                CellSpec.formula("SUM(C$startRow:C$endRow)", b.totalMonthlyEmiLoad, S_CURRENCY_BOLD),
                CellSpec.formula("SUM(D$startRow:D$endRow)", b.emiHealthRecords.sumOf { it.installmentsObserved }.toDouble(), S_INT_BOLD),
                CellSpec.formula("SUM(E$startRow:E$endRow)", b.emiHealthRecords.sumOf { it.totalPaid }, S_CURRENCY_BOLD)
            ))
        } else {
            sb.addRow(listOf(CellSpec.str("No recurring Loan / EMI obligations identified in this statement.")))
        }

        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("COMPLETE BOUNCE, RETURN & PENALTY REGISTER", S_SECTION_HEADER)))
        sb.addRow(listOf(
            CellSpec.str("Date", S_TABLE_HEADER),
            CellSpec.str("Bounce Classification", S_TABLE_HEADER),
            CellSpec.str("Amount / Charge", S_TABLE_HEADER),
            CellSpec.str("Balance After", S_TABLE_HEADER),
            CellSpec.str("Chq / Ref", S_TABLE_HEADER),
            CellSpec.str("Party", S_TABLE_HEADER),
            CellSpec.str("Full Bank Narration", S_TABLE_HEADER)
        ))
        if (b.bounceEvents.isNotEmpty()) {
            val startRow = sb.nextRowIndex()
            b.bounceEvents.forEach { ev ->
                sb.addRow(listOf(
                    CellSpec.str(excelConfig.dateFormat.formatIso(ev.dateIso)),
                    CellSpec.str(ev.bounceType.label, S_FLAG_RED),
                    CellSpec.num(ev.amount, S_CURRENCY),
                    CellSpec.num(ev.balanceAfter, S_CURRENCY),
                    CellSpec.str(ev.chequeNo),
                    CellSpec.str(ev.partyName),
                    CellSpec.str(ev.description)
                ))
            }
            val endRow = sb.nextRowIndex() - 1
            sb.addRow(listOf(
                CellSpec.str("TOTAL BOUNCE / PENAL VALUE", S_LABEL_BOLD),
                CellSpec.str("${b.bounceEvents.size} Event(s)"),
                CellSpec.formula("SUM(C$startRow:C$endRow)", b.totalBounceAndPenaltyAmount, S_CURRENCY_BOLD)
            ))
        } else {
            sb.addRow(listOf(CellSpec.str("Zero cheque, ECS/NACH bounces or penalty charges detected.", S_FLAG_GREEN)))
        }
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 15: Channel_And_Cash_Analysis (Payment Rails + High-Value Cash Register)
    // -------------------------------------------------------------------------
    private fun buildChannelAndCashSheet(
        deep: DeepUnderwritingSummary,
        excelConfig: ExcelExportConfig
    ): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        sb.setColumnWidths(listOf(28.0, 12.0, 18.0, 12.0, 18.0, 20.0, 14.0))
        val ch = deep.channelsAndCash

        sb.addRow(listOf(CellSpec.str("PAYMENT CHANNEL MIX & HIGH-VALUE CASH REGISTER — BSA-YourView", S_BRAND_BANNER)))
        sb.addRow(listOf(
            CellSpec.str("Digital Banking Ratio: ${String.format(Locale.ROOT, "%.1f%%", ch.digitalBankingRatioPct)} | Sec 269ST Cash Alerts (>= ₹2L): ${ch.sec269StAlertCount}", S_LABEL_BOLD)
        ))
        sb.addRow(listOf(
            CellSpec.str("Payment Channel / Mode", S_TABLE_HEADER),
            CellSpec.str("Cr Count", S_TABLE_HEADER),
            CellSpec.str("Credit Volume (INR)", S_TABLE_HEADER),
            CellSpec.str("Dr Count", S_TABLE_HEADER),
            CellSpec.str("Debit Volume (INR)", S_TABLE_HEADER),
            CellSpec.str("Total Volume (INR)", S_TABLE_HEADER),
            CellSpec.str("Share %", S_TABLE_HEADER)
        ))
        if (ch.channels.isNotEmpty()) {
            val startRow = sb.nextRowIndex()
            ch.channels.forEach { row ->
                sb.addRow(listOf(
                    CellSpec.str(row.channelName, S_LABEL_BOLD),
                    CellSpec.num(row.creditCount.toDouble(), S_INT),
                    CellSpec.num(row.creditAmount, S_CURRENCY),
                    CellSpec.num(row.debitCount.toDouble(), S_INT),
                    CellSpec.num(row.debitAmount, S_CURRENCY),
                    CellSpec.num(row.totalVolume, S_CURRENCY_BOLD),
                    CellSpec.num(row.volumeSharePct / 100.0, S_PERCENT)
                ))
            }
            val endRow = sb.nextRowIndex() - 1
            sb.addRow(listOf(
                CellSpec.str("TOTAL ACROSS CHANNELS", S_LABEL_BOLD),
                CellSpec.formula("SUM(B$startRow:B$endRow)", ch.channels.sumOf { it.creditCount }.toDouble(), S_INT_BOLD),
                CellSpec.formula("SUM(C$startRow:C$endRow)", ch.channels.sumOf { it.creditAmount }, S_CURRENCY_BOLD),
                CellSpec.formula("SUM(D$startRow:D$endRow)", ch.channels.sumOf { it.debitCount }.toDouble(), S_INT_BOLD),
                CellSpec.formula("SUM(E$startRow:E$endRow)", ch.channels.sumOf { it.debitAmount }, S_CURRENCY_BOLD),
                CellSpec.formula("SUM(F$startRow:F$endRow)", ch.channels.sumOf { it.totalVolume }, S_CURRENCY_BOLD),
                CellSpec.num(1.0, S_PERCENT_BOLD)
            ))
        }

        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("HIGH-VALUE CASH TRANSACTIONS (>= ₹50,000 | SEC 269ST WATCH >= ₹2,00,000)", S_SECTION_HEADER)))
        sb.addRow(listOf(
            CellSpec.str("Date", S_TABLE_HEADER),
            CellSpec.str("Type", S_TABLE_HEADER),
            CellSpec.str("Amount (INR)", S_TABLE_HEADER),
            CellSpec.str("Balance (INR)", S_TABLE_HEADER),
            CellSpec.str("Compliance Flag", S_TABLE_HEADER),
            CellSpec.str("Source ePDF", S_TABLE_HEADER),
            CellSpec.str("Narration", S_TABLE_HEADER)
        ))
        if (ch.highValueCashTxns.isNotEmpty()) {
            ch.highValueCashTxns.forEach { t ->
                val is269 = t.amount >= 200000.0
                sb.addRow(listOf(
                    CellSpec.str(excelConfig.dateFormat.formatIso(t.dateIso)),
                    CellSpec.str(if (t.amount > 0) "Cash Deposit" else "Cash Withdrawal"),
                    CellSpec.num(kotlin.math.abs(t.amount), S_CURRENCY_BOLD),
                    CellSpec.num(t.balance, S_CURRENCY),
                    CellSpec.str(if (is269) "Sec 269ST Alert (>= ₹2L)" else "High-Value Cash", if (is269) S_FLAG_RED else S_FLAG_AMBER),
                    CellSpec.str(t.sourceFile),
                    CellSpec.str(t.description)
                ))
            }
        } else {
            sb.addRow(listOf(CellSpec.str("No single cash deposit or withdrawal >= ₹50,000 observed.", S_FLAG_GREEN)))
        }
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 16: Category_Spend_Analytics (Category Inflow/Outflow + Statutory)
    // -------------------------------------------------------------------------
    private fun buildCategorySpendAnalyticsSheet(
        deep: DeepUnderwritingSummary,
        excelConfig: ExcelExportConfig
    ): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        sb.setColumnWidths(listOf(30.0, 12.0, 18.0, 18.0, 18.0, 18.0, 14.0))

        sb.addRow(listOf(CellSpec.str("CATEGORY-WISE CASHFLOW & STATUTORY COMPLIANCE — BSA-YourView", S_BRAND_BANNER)))
        sb.addEmptyRow()
        sb.addRow(listOf(
            CellSpec.str("Category", S_TABLE_HEADER),
            CellSpec.str("Txn Count", S_TABLE_HEADER),
            CellSpec.str("Total Credits (INR)", S_TABLE_HEADER),
            CellSpec.str("Total Debits (INR)", S_TABLE_HEADER),
            CellSpec.str("Net Flow (INR)", S_TABLE_HEADER),
            CellSpec.str("Monthly Avg Vol", S_TABLE_HEADER),
            CellSpec.str("Share %", S_TABLE_HEADER)
        ))
        if (deep.categoryBreakdown.isNotEmpty()) {
            val startRow = sb.nextRowIndex()
            deep.categoryBreakdown.forEach { cat ->
                sb.addRow(listOf(
                    CellSpec.str(cat.category, S_LABEL_BOLD),
                    CellSpec.num(cat.txnCount.toDouble(), S_INT),
                    CellSpec.num(cat.creditTotal, S_CURRENCY),
                    CellSpec.num(cat.debitTotal, S_CURRENCY),
                    CellSpec.num(cat.netFlow, S_CURRENCY),
                    CellSpec.num(cat.monthlyAverageVolume, S_CURRENCY),
                    CellSpec.num(cat.shareOfTotalTurnoverPct / 100.0, S_PERCENT)
                ))
            }
            val endRow = sb.nextRowIndex() - 1
            sb.addRow(listOf(
                CellSpec.str("GRAND TOTAL", S_LABEL_BOLD),
                CellSpec.formula("SUM(B$startRow:B$endRow)", deep.categoryBreakdown.sumOf { it.txnCount }.toDouble(), S_INT_BOLD),
                CellSpec.formula("SUM(C$startRow:C$endRow)", deep.categoryBreakdown.sumOf { it.creditTotal }, S_CURRENCY_BOLD),
                CellSpec.formula("SUM(D$startRow:D$endRow)", deep.categoryBreakdown.sumOf { it.debitTotal }, S_CURRENCY_BOLD),
                CellSpec.formula("SUM(E$startRow:E$endRow)", deep.categoryBreakdown.sumOf { it.netFlow }, S_CURRENCY_BOLD)
            ))
        }

        val st = deep.statutory
        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("STATUTORY, TAX & FIXED OPERATING COMPLIANCE SIGNALS", S_SECTION_HEADER)))
        sb.addRow(listOf(
            CellSpec.str("Compliance Indicator", S_TABLE_HEADER),
            CellSpec.str("Observed Count", S_TABLE_HEADER),
            CellSpec.str("Total Value (INR)", S_TABLE_HEADER),
            CellSpec.str("Underwriting Observation", S_TABLE_HEADER)
        ))
        sb.addRow(listOf(CellSpec.str("GST Challan / CBIC Payments", S_LABEL_BOLD), CellSpec.num(st.gstPaymentCount.toDouble(), S_INT), CellSpec.num(st.gstPaymentTotal, S_CURRENCY), CellSpec.str(if (st.gstPaymentCount > 0) "Active GST remittance observed in banking" else "No direct GST debit spotted — verify GSTR-3B")))
        sb.addRow(listOf(CellSpec.str("Income Tax / Advance Tax / TDS", S_LABEL_BOLD), CellSpec.num(st.incomeTaxTdsCount.toDouble(), S_INT), CellSpec.num(st.incomeTaxTdsTotal, S_CURRENCY), CellSpec.str(if (st.incomeTaxTdsCount > 0) "Tax payment discipline observed" else "Check ITR & Form 26AS")))
        sb.addRow(listOf(CellSpec.str("Salary / Payroll Flows", S_LABEL_BOLD), CellSpec.num(st.salaryPayrollCount.toDouble(), S_INT), CellSpec.num(st.salaryPayrollTotal, S_CURRENCY), CellSpec.str(if (st.salaryPayrollCount > 0) "Regular payroll activity detected" else "No payroll narrations")))
        sb.addRow(listOf(CellSpec.str("Premises / Office / Godown Rent", S_LABEL_BOLD), CellSpec.num(st.rentOutflowCount.toDouble(), S_INT), CellSpec.num(st.rentOutflowTotal, S_CURRENCY), CellSpec.str(if (st.rentOutflowCount > 0) "Rented premises indicator" else "Check owned vs leased premises")))
        sb.addRow(listOf(CellSpec.str("Electricity & Utilities", S_LABEL_BOLD), CellSpec.num(st.utilityBillCount.toDouble(), S_INT), CellSpec.num(st.utilityBillTotal, S_CURRENCY), CellSpec.str(if (st.utilityBillCount > 0) "Active operational premise verified" else "Verify utility bills")))
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 17: Counterparty_Concentration (Top Buyers & Suppliers Concentration)
    // -------------------------------------------------------------------------
    private fun buildCounterpartyConcentrationSheet(
        deep: DeepUnderwritingSummary,
        excelConfig: ExcelExportConfig
    ): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        sb.setColumnWidths(listOf(34.0, 12.0, 20.0, 16.0, 18.0))
        val c = deep.concentration

        sb.addRow(listOf(CellSpec.str("COUNTERPARTY CONCENTRATION RISK (TOP BUYERS & SUPPLIERS) — BSA-YourView", S_BRAND_BANNER)))
        sb.addRow(listOf(
            CellSpec.str("Concentration Risk: ${c.concentrationRiskLabel} | Top-3 Buyers: ${String.format(Locale.ROOT, "%.1f%%", c.top3CustomerSharePct)} | Top-3 Suppliers: ${String.format(Locale.ROOT, "%.1f%%", c.top3SupplierSharePct)}", S_LABEL_BOLD)
        ))
        sb.addRow(listOf(
            CellSpec.str("Top Customer / Buyer (Non-Self Credits)", S_TABLE_HEADER),
            CellSpec.str("Txn Count", S_TABLE_HEADER),
            CellSpec.str("Inflow Amount (INR)", S_TABLE_HEADER),
            CellSpec.str("Share %", S_TABLE_HEADER),
            CellSpec.str("Cumulative %", S_TABLE_HEADER)
        ))
        if (c.topCustomers.isNotEmpty()) {
            val startRow = sb.nextRowIndex()
            c.topCustomers.forEach { row ->
                sb.addRow(listOf(
                    CellSpec.str(row.partyName, S_LABEL_BOLD),
                    CellSpec.num(row.txnCount.toDouble(), S_INT),
                    CellSpec.num(row.totalAmount, S_CURRENCY),
                    CellSpec.num(row.sharePercent / 100.0, S_PERCENT),
                    CellSpec.num(row.cumulativeSharePercent / 100.0, S_PERCENT)
                ))
            }
            val endRow = sb.nextRowIndex() - 1
            sb.addRow(listOf(
                CellSpec.str("TOP ${c.topCustomers.size} BUYERS TOTAL", S_LABEL_BOLD),
                CellSpec.formula("SUM(B$startRow:B$endRow)", c.topCustomers.sumOf { it.txnCount }.toDouble(), S_INT_BOLD),
                CellSpec.formula("SUM(C$startRow:C$endRow)", c.topCustomers.sumOf { it.totalAmount }, S_CURRENCY_BOLD),
                CellSpec.formula("SUM(D$startRow:D$endRow)", c.topCustomers.sumOf { it.sharePercent } / 100.0, S_PERCENT_BOLD),
                CellSpec.str("")
            ))
        } else {
            sb.addRow(listOf(CellSpec.str("No non-self customer credits found.")))
        }

        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("TOP SUPPLIERS / PAYEES (NON-SELF BUSINESS DEBITS)", S_SECTION_HEADER)))
        sb.addRow(listOf(
            CellSpec.str("Top Supplier / Payee", S_TABLE_HEADER),
            CellSpec.str("Txn Count", S_TABLE_HEADER),
            CellSpec.str("Outflow Amount (INR)", S_TABLE_HEADER),
            CellSpec.str("Share %", S_TABLE_HEADER),
            CellSpec.str("Cumulative %", S_TABLE_HEADER)
        ))
        if (c.topSuppliers.isNotEmpty()) {
            val startRow = sb.nextRowIndex()
            c.topSuppliers.forEach { row ->
                sb.addRow(listOf(
                    CellSpec.str(row.partyName, S_LABEL_BOLD),
                    CellSpec.num(row.txnCount.toDouble(), S_INT),
                    CellSpec.num(row.totalAmount, S_CURRENCY),
                    CellSpec.num(row.sharePercent / 100.0, S_PERCENT),
                    CellSpec.num(row.cumulativeSharePercent / 100.0, S_PERCENT)
                ))
            }
            val endRow = sb.nextRowIndex() - 1
            sb.addRow(listOf(
                CellSpec.str("TOP ${c.topSuppliers.size} SUPPLIERS TOTAL", S_LABEL_BOLD),
                CellSpec.formula("SUM(B$startRow:B$endRow)", c.topSuppliers.sumOf { it.txnCount }.toDouble(), S_INT_BOLD),
                CellSpec.formula("SUM(C$startRow:C$endRow)", c.topSuppliers.sumOf { it.totalAmount }, S_CURRENCY_BOLD),
                CellSpec.formula("SUM(D$startRow:D$endRow)", c.topSuppliers.sumOf { it.sharePercent } / 100.0, S_PERCENT_BOLD),
                CellSpec.str("")
            ))
        } else {
            sb.addRow(listOf(CellSpec.str("No non-self supplier debits found.")))
        }
        return sb.buildXml()
    }

    // -------------------------------------------------------------------------
    // Tab 18: Underwriter_CAM_360 (Beyond-BSA Credit Appraisal Memo Sheet)
    // -------------------------------------------------------------------------
    private fun buildUnderwriterCam360Sheet(
        report: AnalysisReport,
        intel: CreditIntelligenceSummary,
        deep: DeepUnderwritingSummary,
        cam: UnderwriterCamProfile,
        excelConfig: ExcelExportConfig
    ): String {
        val sb = SheetBuilder(
            freezeRow = if (excelConfig.freezeHeaderRows) 3 else 0,
            includeFormulas = excelConfig.includeFormulas
        )
        sb.setColumnWidths(listOf(34.0, 22.0, 22.0, 44.0))
        val eval = UnderwriterCamEvaluator.evaluate(report, intel, deep, cam)

        sb.addRow(listOf(CellSpec.str("ONE-STOP CREDIT APPRAISAL MEMO (CAM 360°) — BSA-YourView | yourview.in", S_BRAND_BANNER)))
        sb.addRow(listOf(
            CellSpec.str("Applicant: ${report.config.applicantId} | ${report.metadata.holderName} | Composite 360° Score: ${eval.compositeCamScore}/100 | Decision: ${cam.decisionStatus.label}", S_LABEL_BOLD)
        ))
        sb.addRow(listOf(
            CellSpec.str("CAM Underwriting Pillar", S_TABLE_HEADER),
            CellSpec.str("Recorded Value", S_TABLE_HEADER),
            CellSpec.str("Benchmark / Norm", S_TABLE_HEADER),
            CellSpec.str("Assessment / Notes", S_TABLE_HEADER)
        ))

        // 1. Bureau
        sb.addRow(listOf(CellSpec.str("1. CREDIT BUREAU PROFILE (${cam.bureauName})", S_SECTION_HEADER)))
        val scoreStyle = when {
            cam.bureauScore >= 720 -> S_FLAG_GREEN
            cam.bureauScore >= 650 -> S_FLAG_AMBER
            else -> S_FLAG_RED
        }
        sb.addRow(listOf(CellSpec.str("${cam.bureauName} Score", S_LABEL_BOLD), CellSpec.num(cam.bureauScore.toDouble(), scoreStyle), CellSpec.str(">= 700 Preferred"), CellSpec.str("Active Tradelines: ${cam.activeTradelines} | 90d Enquiries: ${cam.recentEnquiries90d}")))
        sb.addRow(listOf(CellSpec.str("Bureau Overdue / 30+ DPD / 90+ DPD", S_LABEL_BOLD), CellSpec.num(cam.overdueAmount, S_CURRENCY), CellSpec.str("0 Overdue / 0 DPD"), CellSpec.str("30+ DPD (12m): ${cam.dpd30PlusCount12m} | 90+ DPD: ${cam.dpd90PlusCount} | Write-Off: ${if (cam.writtenOffOrSettled) "YES" else "No"}")))
        sb.addRow(listOf(CellSpec.str("Consolidated Monthly EMI (Bank + Bureau)", S_LABEL_BOLD), CellSpec.num(eval.consolidatedMonthlyEmi, S_CURRENCY_BOLD), CellSpec.num(eval.consolidatedFoirPct / 100.0, S_PERCENT_BOLD), CellSpec.str("Bank EMI + ₹${String.format(Locale.ROOT, "%,.0f", cam.additionalBureauMonthlyEmi)} off-statement bureau EMI")))

        // 2. GST & ITR Financial Ratios
        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("2. GST RECONCILIATION & ITR FINANCIAL RATIOS", S_SECTION_HEADER)))
        sb.addRow(listOf(CellSpec.str("Annualized True Bank Turnover (BTO)", S_LABEL_BOLD), CellSpec.num(eval.annualizedBankTurnover, S_CURRENCY_BOLD), CellSpec.str("Excl. Self/Reversals"), CellSpec.str("Credit Inflation: ${String.format(Locale.ROOT, "%.1f%%", deep.internalTransfers.creditInflationPercent)}")))
        sb.addRow(listOf(
            CellSpec.str("Annual GST Turnover & Routing %", S_LABEL_BOLD),
            CellSpec.num(cam.annualGstTurnover, S_CURRENCY),
            eval.bankToGstRoutingPct?.let { CellSpec.num(it / 100.0, S_PERCENT_BOLD) } ?: CellSpec.str("Not Entered"),
            CellSpec.str("Norm: 75%–125% Bank-to-GST routing")
        ))
        sb.addRow(listOf(
            CellSpec.str("ITR Gross Turnover & Net Profit (PAT)", S_LABEL_BOLD),
            CellSpec.num(cam.itrGrossTurnover, S_CURRENCY),
            eval.netProfitMarginPct?.let { CellSpec.num(it / 100.0, S_PERCENT_BOLD) } ?: CellSpec.str("Not Entered"),
            CellSpec.str("PAT: ₹${String.format(Locale.ROOT, "%,.0f", cam.itrNetProfitPat)} | Cash Accrual: ₹${String.format(Locale.ROOT, "%,.0f", eval.cashAccrualAnnual ?: 0.0)}")
        ))
        sb.addRow(listOf(
            CellSpec.str("DSCR (Debt Service Coverage Ratio)", S_LABEL_BOLD),
            eval.dscrRatio?.let { CellSpec.str(String.format(Locale.ROOT, "%.2fx", it), if (it >= 1.25) S_FLAG_GREEN else S_FLAG_RED) } ?: CellSpec.str("Not Entered"),
            CellSpec.str(">= 1.25x Minimum"),
            CellSpec.str("(PAT + Dep + Interest) / Annual Debt Servicing")
        ))
        sb.addRow(listOf(
            CellSpec.str("Leverage Ratio (TOL / TNW)", S_LABEL_BOLD),
            eval.leverageTolTnwRatio?.let { CellSpec.str(String.format(Locale.ROOT, "%.2fx", it), if (it <= 3.0) S_FLAG_GREEN else S_FLAG_AMBER) } ?: CellSpec.str("Not Entered"),
            CellSpec.str("<= 3.00x Norm"),
            CellSpec.str("TNW: ₹${String.format(Locale.ROOT, "%,.0f", cam.tangibleNetWorth)} | TOL: ₹${String.format(Locale.ROOT, "%,.0f", cam.totalOutsideLiabilities)}")
        ))
        sb.addRow(listOf(
            CellSpec.str("Current Ratio & Computed Drawing Power", S_LABEL_BOLD),
            eval.currentRatio?.let { CellSpec.str(String.format(Locale.ROOT, "%.2fx", it), if (it >= 1.33) S_FLAG_GREEN else S_FLAG_AMBER) } ?: CellSpec.str("Not Entered"),
            eval.computedDrawingPower?.let { CellSpec.num(it, S_CURRENCY_BOLD) } ?: CellSpec.str("DP Not Entered"),
            CellSpec.str("Tandon Norm >= 1.33x | DP = 75%(Stock + Debtors) - Creditors")
        ))

        // 3. Collateral & LTV
        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("3. COLLATERAL SECURITY & LTV ASSESSMENT", S_SECTION_HEADER)))
        sb.addRow(listOf(
            CellSpec.str("Collateral Type & Market Value", S_LABEL_BOLD),
            CellSpec.num(cam.collateralMarketValue, S_CURRENCY_BOLD),
            CellSpec.str(cam.collateralType.label),
            CellSpec.str("Max Norm LTV: ${if (cam.collateralType.maxNormLtvPct > 0) "${cam.collateralType.maxNormLtvPct.toInt()}%" else "Unsecured"}")
        ))
        sb.addRow(listOf(
            CellSpec.str("Proposed Sanction & Computed LTV %", S_LABEL_BOLD),
            CellSpec.num(if (cam.proposedSanctionAmount > 0) cam.proposedSanctionAmount else intel.primarySegmentEligibility.recommendedAmount, S_CURRENCY_BOLD),
            eval.ltvPercent?.let { CellSpec.num(it / 100.0, if (cam.collateralType.maxNormLtvPct > 0 && it > cam.collateralType.maxNormLtvPct) S_FLAG_RED else S_FLAG_GREEN) } ?: CellSpec.str("N/A"),
            CellSpec.str("Segment: ${report.config.productType.displayName}")
        ))

        // 4. Verification Checklist
        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("4. KYC, FI, PD & LEGAL VERIFICATION CHECKLIST (${eval.checklistCompletedCount}/${eval.checklistTotalCount})", S_SECTION_HEADER)))
        fun checkRow(label: String, done: Boolean) {
            sb.addRow(listOf(CellSpec.str(label, S_LABEL_BOLD), CellSpec.str(if (done) "CLEARED" else "PENDING", if (done) S_FLAG_GREEN else S_FLAG_AMBER)))
        }
        checkRow("KYC / PAN / Aadhaar Verification", cam.kycPanAadhaarVerified)
        checkRow("GST Registration Active & Regular Filings", cam.gstStatusActiveVerified)
        checkRow("Residence / Office Field Investigation (FI)", cam.residenceOfficeFiCleared)
        checkRow("Credit Officer Personal Discussion (PD)", cam.personalDiscussionDone)
        checkRow("Legal & Technical Collateral Valuation", cam.legalTechnicalCleared)
        checkRow("Positive Trade / Market Reference Check", cam.tradeReferencePositive)

        // 5. Policy Deviations & Final Decision
        sb.addEmptyRow()
        sb.addRow(listOf(CellSpec.str("5. AUTO-DETECTED POLICY DEVIATIONS & FINAL UNDERWRITER DECISION", S_SECTION_HEADER)))
        if (eval.deviations.isEmpty()) {
            sb.addRow(listOf(CellSpec.str("Policy Deviations", S_LABEL_BOLD), CellSpec.str("Zero Policy Deviations Detected", S_FLAG_GREEN)))
        } else {
            eval.deviations.forEach { d ->
                val sevStyle = when (d.severity) {
                    "Critical" -> S_FLAG_RED
                    "Moderate" -> S_FLAG_AMBER
                    else -> S_DEFAULT
                }
                sb.addRow(listOf(
                    CellSpec.str(d.category, S_LABEL_BOLD),
                    CellSpec.str(d.severity, sevStyle),
                    CellSpec.str(""),
                    CellSpec.str(d.message)
                ))
            }
        }
        sb.addRow(listOf(
            CellSpec.str("Final Credit Officer Decision", S_LABEL_BOLD),
            CellSpec.str(cam.decisionStatus.label, when (cam.decisionStatus) {
                CamDecisionStatus.RECOMMEND_SANCTION -> S_FLAG_GREEN
                CamDecisionStatus.DECLINE -> S_FLAG_RED
                else -> S_FLAG_AMBER
            }),
            CellSpec.str("Underwriter Remarks", S_LABEL_BOLD),
            CellSpec.str(cam.underwriterRemarks.ifBlank { "No custom remarks entered." })
        ))

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
