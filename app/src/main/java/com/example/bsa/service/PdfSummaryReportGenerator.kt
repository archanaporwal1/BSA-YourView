package com.example.bsa.service

import com.example.bsa.engine.CreditIntelligenceEngine
import com.example.bsa.engine.DeepUnderwritingAnalytics
import com.example.bsa.engine.EligibilityStatus
import com.example.bsa.engine.PortfolioRiskEngine
import com.example.bsa.engine.UnderwritingVerdict
import com.example.bsa.model.AnalysisReport
import com.example.bsa.model.ForensicStatus
import com.example.bsa.model.UnderwriterCamProfile
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Generates a client-ready, BSA-YourView branded 2-page Executive PDF Summary Report (.pdf)
 * presenting Underwriting Risk Score, Credit Health Verdict, 4-Segment Loan Eligibility Matrix,
 * Multi-Account Portfolio Risk Breakdown, Monthly Cash-Flow Trend, and Underwriter 360 CAM Notes.
 *
 * Uses PDFBox-Android (pure JVM + Android compatible) with strict WinAnsi ASCII sanitization.
 */
class PdfSummaryReportGenerator {

    private val indianNumFmt = DecimalFormat("#,##,##0", DecimalFormatSymbols(Locale.ENGLISH))

    suspend fun generateToBytes(
        report: AnalysisReport,
        camProfile: UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): ByteArray = withContext(Dispatchers.IO) {
        val baos = ByteArrayOutputStream()
        generateToStream(report, baos, camProfile, onProgress)
        baos.toByteArray()
    }

    suspend fun generateToFile(
        report: AnalysisReport,
        targetFile: File,
        camProfile: UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            generateToStream(report, fos, camProfile, onProgress)
        }
        targetFile
    }

    suspend fun generateToStream(
        report: AnalysisReport,
        outputStream: OutputStream,
        camProfile: UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        onProgress(0.10f, "Computing Credit Intelligence & Portfolio Risk...")
        val intel = CreditIntelligenceEngine.evaluate(report)
        val deep = DeepUnderwritingAnalytics.analyze(report)
        val portfolio = PortfolioRiskEngine.evaluate(report, intel)
        val riskSummary = FraudScreeningService.computeRiskSummary(report.fraudIndicators)
        val cam = camProfile ?: UnderwriterCamProfile.defaultForReport(intel)
        val generatedDateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).format(Date(report.generatedAtMillis))

        PDDocument().use { doc ->
            // =====================================================================
            // PAGE 1: Executive Verdict, Risk Score, KPIs & 4-Segment Eligibility
            // =====================================================================
            onProgress(0.40f, "Rendering Page 1: Eligibility & Risk Score Summary...")
            val page1 = PDPage(PDRectangle.A4)
            doc.addPage(page1)
            val pageW = page1.mediaBox.width   // 595 pt
            val pageH = page1.mediaBox.height  // 842 pt
            val margin = 36f
            val contentW = pageW - (margin * 2f)

            PDPageContentStream(doc, page1).use { cs ->
                // Top Sovereign Navy Brand Banner
                fillRect(cs, 0f, pageH - 76f, pageW, 76f, 15, 36, 62)
                // Teal accent bottom stripe
                fillRect(cs, 0f, pageH - 80f, pageW, 4f, 13, 148, 136)

                drawText(cs, "BSA-YourView", margin, pageH - 34f, PDType1Font.HELVETICA_BOLD, 18f, 255, 255, 255)
                drawText(cs, "Executive Credit Eligibility & Risk Summary Report", margin, pageH - 52f, PDType1Font.HELVETICA, 10f, 191, 219, 254)
                drawText(cs, "yourview.in | Confidential Underwriting Brief", margin, pageH - 66f, PDType1Font.HELVETICA, 8f, 148, 163, 184)

                // Right-aligned Report Date & Forensic Badge
                val badgeText = "FORENSIC: ${report.overallForensicStatus.label}"
                val (br, bg, bb) = when (report.overallForensicStatus) {
                    ForensicStatus.VERIFIED -> Triple(16, 185, 129)
                    ForensicStatus.WARNING -> Triple(217, 119, 6)
                    ForensicStatus.REJECTED -> Triple(220, 38, 38)
                }
                fillRect(cs, pageW - margin - 132f, pageH - 42f, 132f, 20f, br, bg, bb)
                drawText(cs, badgeText, pageW - margin - 124f, pageH - 35f, PDType1Font.HELVETICA_BOLD, 8.5f, 255, 255, 255)
                drawText(cs, "Generated: $generatedDateStr", pageW - margin - 132f, pageH - 60f, PDType1Font.HELVETICA, 7.5f, 203, 213, 225)

                var y = pageH - 102f

                // Applicant & Account Metadata Strip
                fillRect(cs, margin, y - 52f, contentW, 56f, 241, 245, 249)
                strokeRect(cs, margin, y - 52f, contentW, 56f, 203, 213, 225)
                drawText(cs, "APPLICANT & STATEMENT PROFILE", margin + 10f, y - 10f, PDType1Font.HELVETICA_BOLD, 8.5f, 15, 36, 62)
                drawText(cs, "Applicant ID: ${report.config.applicantId}   |   Holder: ${report.metadata.holderName}", margin + 10f, y - 24f, PDType1Font.HELVETICA_BOLD, 9f, 30, 41, 59)
                drawText(cs, "Bank: ${report.metadata.bankName}   |   A/c: ${report.metadata.accountNumber}   |   IFSC: ${report.metadata.ifscCode}", margin + 10f, y - 36f, PDType1Font.HELVETICA, 8.2f, 71, 85, 105)
                drawText(cs, "Period: ${report.metadata.statementPeriod}   |   Facility: ${report.config.facilityType.label}   |   Primary Segment: ${report.config.productType.displayName}", margin + 10f, y - 47f, PDType1Font.HELVETICA, 8.2f, 71, 85, 105)

                y -= 68f

                // 3 Executive Score Cards (Risk Score | Credit Health | Portfolio Risk)
                val boxGap = 10f
                val boxW = (contentW - boxGap * 2f) / 3f
                val boxH = 74f

                // Box 1: 10-Point Risk Score
                val (rR, rG, rB) = when (riskSummary.riskTier) {
                    ScreeningRiskTier.LOW -> Triple(16, 185, 129)
                    ScreeningRiskTier.MODERATE -> Triple(217, 119, 6)
                    ScreeningRiskTier.HIGH -> Triple(220, 38, 38)
                }
                drawScoreCard(
                    cs, margin, y - boxH, boxW, boxH,
                    title = "10-POINT RISK SCORE",
                    bigValue = "${riskSummary.riskScore} / 100",
                    badge = riskSummary.riskTier.label.uppercase(Locale.ROOT),
                    subtext = "${riskSummary.flaggedCount}/10 triggers (Fraud ${riskSummary.fraudFlaggedCount}, Behav ${riskSummary.behaviouralFlaggedCount})",
                    accentR = rR, accentG = rG, accentB = rB
                )

                // Box 2: Credit Health Score & Verdict
                val (cR, cG, cB) = when (intel.verdict) {
                    UnderwritingVerdict.STRONG_APPROVE -> Triple(13, 148, 136)
                    UnderwritingVerdict.CONDITIONAL_REVIEW -> Triple(217, 119, 6)
                    UnderwritingVerdict.HIGH_RISK_DECLINE -> Triple(220, 38, 38)
                }
                drawScoreCard(
                    cs, margin + boxW + boxGap, y - boxH, boxW, boxH,
                    title = "CREDIT HEALTH SCORE",
                    bigValue = "${intel.creditHealthScore} / 100",
                    badge = intel.verdict.shortLabel.uppercase(Locale.ROOT),
                    subtext = "FOIR ${pct(intel.foirPercent)} | Cash Dep ${pct(intel.cashDependencyPercent)}",
                    accentR = cR, accentG = cG, accentB = cB
                )

                // Box 3: Consolidated Portfolio Risk
                val (pR, pG, pB) = when {
                    portfolio.portfolioRiskScore >= 60 -> Triple(220, 38, 38)
                    portfolio.portfolioRiskScore >= 30 -> Triple(217, 119, 6)
                    else -> Triple(15, 36, 62)
                }
                drawScoreCard(
                    cs, margin + (boxW + boxGap) * 2f, y - boxH, boxW, boxH,
                    title = "PORTFOLIO RISK (${portfolio.accountCount} A/C)",
                    bigValue = "${portfolio.portfolioRiskScore} / 100",
                    badge = portfolio.portfolioRiskTier.uppercase(Locale.ROOT),
                    subtext = "Best Fit: ${portfolio.bestFitSegment.productType.displayName}",
                    accentR = pR, accentG = pG, accentB = pB
                )

                y -= (boxH + 16f)

                // Banking Conduct & Cash-Flow KPI Grid (2 rows x 4 columns)
                drawSectionHeading(cs, "BANKING CONDUCT & TRANSACTION METRICS", margin, y)
                y -= 12f
                val kpiW = (contentW - 18f) / 4f
                val kpiH = 42f
                val kpiRow1 = listOf(
                    Triple("Total Inflow", inr(report.totalInflow), "${report.transactions.count { it.amount > 0 }} credit txns"),
                    Triple("Total Outflow", inr(report.totalOutflow), "${report.transactions.count { it.amount < 0 }} debit txns"),
                    Triple("Checkpoint ABB", inr(intel.avgBankBalanceAbb), "2nd/10th/20th/End"),
                    Triple("Peak OD Util %", pct(report.peakOdUtilizationPct), "Limit ${inr(report.config.sanctionedOdLimit)}")
                )
                val kpiRow2 = listOf(
                    Triple("Internal Credits", inr(report.totalInternalCredits), "${report.totalInternalCreditCount} self/contra excl."),
                    Triple("Cheque Bounces", "${report.totalChequeBounceCount}", inr(report.totalChequeBounceAmount)),
                    Triple("EMI / NACH Returns", "${report.totalEmiReturnCount}", inr(report.totalEmiReturnAmount)),
                    Triple("ABB / EMI Cover", String.format(Locale.ROOT, "%.2fx", portfolio.emiToAbbCoverageRatio), "Norm >= 1.50x")
                )

                kpiRow1.forEachIndexed { idx, (lbl, valStr, sub) ->
                    drawKpiCell(cs, margin + idx * (kpiW + 6f), y - kpiH, kpiW, kpiH, lbl, valStr, sub)
                }
                y -= (kpiH + 6f)
                kpiRow2.forEachIndexed { idx, (lbl, valStr, sub) ->
                    drawKpiCell(cs, margin + idx * (kpiW + 6f), y - kpiH, kpiW, kpiH, lbl, valStr, sub)
                }

                y -= (kpiH + 18f)

                // 4-Segment Loan Eligibility Matrix Table
                drawSectionHeading(cs, "4-SEGMENT LOAN ELIGIBILITY MATRIX (INDIAN BANKING NORMS)", margin, y)
                y -= 10f

                val colWidths = floatArrayOf(102f, 66f, 84f, 84f, 76f, contentW - 412f)
                val headers = listOf("Loan Segment", "Status", "Recommended", "Max Eligible", "Terms", "Limiting Factor")
                drawTableHeader(cs, margin, y - 18f, colWidths, 18f, headers)
                y -= 18f

                intel.segmentEligibilities.forEachIndexed { idx, seg ->
                    val rowH = 26f
                    val bgTint = if (idx % 2 == 0) 255 else 248
                    fillRect(cs, margin, y - rowH, contentW, rowH, bgTint, bgTint, if (bgTint == 255) 255 else 250)
                    strokeRect(cs, margin, y - rowH, contentW, rowH, 226, 232, 240)

                    val termsStr = if (seg.isRevolvingFacility) {
                        "OD @ ${String.format(Locale.ROOT, "%.1f%%", seg.benchmarkRatePct)}"
                    } else {
                        "${seg.benchmarkTenureMonths}m @ ${String.format(Locale.ROOT, "%.1f%%", seg.benchmarkRatePct)}"
                    }
                    val statusShort = when (seg.status) {
                        EligibilityStatus.ELIGIBLE -> "ELIGIBLE"
                        EligibilityStatus.CONDITIONAL -> "REVIEW"
                        EligibilityStatus.INELIGIBLE -> "INELIGIBLE"
                    }
                    val rowCells = listOf(
                        seg.productType.displayName,
                        statusShort,
                        inr(seg.recommendedAmount),
                        inr(seg.maxEligibleAmount),
                        termsStr,
                        seg.limitingFactor.take(28)
                    )
                    var cx = margin
                    rowCells.forEachIndexed { cIdx, cellText ->
                        val font = if (cIdx == 0 || cIdx == 2) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
                        val (tr, tg, tb) = when (cIdx) {
                            1 -> when (seg.status) {
                                EligibilityStatus.ELIGIBLE -> Triple(13, 148, 136)
                                EligibilityStatus.CONDITIONAL -> Triple(180, 83, 9)
                                EligibilityStatus.INELIGIBLE -> Triple(185, 28, 28)
                            }
                            else -> Triple(30, 41, 59)
                        }
                        drawText(cs, cellText, cx + 4f, y - 16f, font, 7.8f, tr, tg, tb)
                        cx += colWidths[cIdx]
                    }
                    y -= rowH
                }

                y -= 16f

                // Key Underwriting Strengths & Watch-Outs
                drawSectionHeading(cs, "KEY UNDERWRITING HIGHLIGHTS & WATCH-OUTS", margin, y)
                y -= 14f
                val allBullets = buildList {
                    intel.strengths.take(3).forEach { add("[+] $it") }
                    intel.watchOuts.take(3).forEach { add("[!] $it") }
                    portfolio.insights.take(2).forEach { add("[*] $it") }
                }
                allBullets.forEach { bullet ->
                    if (y > 54f) {
                        val isAlert = bullet.startsWith("[!]")
                        drawText(
                            cs,
                            bullet.take(102),
                            margin + 6f,
                            y,
                            PDType1Font.HELVETICA,
                            8.2f,
                            if (isAlert) 185 else 30,
                            if (isAlert) 28 else 41,
                            if (isAlert) 28 else 59
                        )
                        y -= 13f
                    }
                }

                drawFooter(cs, pageW, margin, "Page 1 of 2 | BSA-YourView (yourview.in) | Generated Offline")
            }

            // =====================================================================
            // PAGE 2: Multi-Account Portfolio Risk, Monthly Trend & CAM 360 Summary
            // =====================================================================
            onProgress(0.75f, "Rendering Page 2: Portfolio Risk, Monthly Trend & CAM 360...")
            val page2 = PDPage(PDRectangle.A4)
            doc.addPage(page2)

            PDPageContentStream(doc, page2).use { cs ->
                // Compact Header Banner on Page 2
                fillRect(cs, 0f, pageH - 46f, pageW, 46f, 15, 36, 62)
                fillRect(cs, 0f, pageH - 49f, pageW, 3f, 13, 148, 136)
                drawText(cs, "BSA-YourView | Portfolio Risk & Credit Appraisal Memorandum", margin, pageH - 28f, PDType1Font.HELVETICA_BOLD, 12f, 255, 255, 255)
                drawText(cs, "Applicant: ${report.config.applicantId} (${report.metadata.holderName})", pageW - margin - 200f, pageH - 28f, PDType1Font.HELVETICA, 8.5f, 191, 219, 254)

                var y = pageH - 72f

                // Section 1: Multi-Account Consolidated Portfolio Breakdown
                drawSectionHeading(
                    cs,
                    "CONSOLIDATED PORTFOLIO RISK BREAKDOWN (${portfolio.accountCount} BANK ACCOUNT${if (portfolio.accountCount == 1) "" else "S"})",
                    margin,
                    y
                )
                y -= 10f

                val pCols = floatArrayOf(148f, 78f, 78f, 74f, 54f, 44f, contentW - 476f)
                drawTableHeader(
                    cs, margin, y - 18f, pCols, 18f,
                    listOf("Bank & Account", "Total Credits", "Total Debits", "Avg EOD Bal", "Bounces", "Risk", "Tier")
                )
                y -= 18f

                portfolio.accounts.take(6).forEachIndexed { idx, acc ->
                    val rowH = 22f
                    val bgTint = if (idx % 2 == 0) 255 else 248
                    fillRect(cs, margin, y - rowH, contentW, rowH, bgTint, bgTint, if (bgTint == 255) 255 else 250)
                    strokeRect(cs, margin, y - rowH, contentW, rowH, 226, 232, 240)
                    val cells = listOf(
                        acc.accountLabel.take(26),
                        inr(acc.totalCredits),
                        inr(acc.totalDebits),
                        inr(acc.avgEodBalance),
                        "${acc.chequeBounceCount + acc.emiReturnCount}",
                        "${acc.accountRiskScore}/100",
                        acc.riskTier
                    )
                    var cx = margin
                    cells.forEachIndexed { cIdx, txt ->
                        drawText(cs, txt, cx + 4f, y - 14f, if (cIdx == 0 || cIdx == 5) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA, 7.8f, 30, 41, 59)
                        cx += pCols[cIdx]
                    }
                    y -= rowH
                }

                y -= 10f
                drawText(
                    cs,
                    "Cross-Account Contra Excluded: ${inr(portfolio.crossAccountContraTotal)} (${portfolio.crossAccountTransfers.size} pair(s)) | Consolidated True Turnover: ${inr(portfolio.consolidatedTrueInflow)}",
                    margin, y, PDType1Font.HELVETICA_BOLD, 8f, 15, 36, 62
                )
                y -= 18f

                // Section 2: Monthly Cash-Flow & Conduct Trend Table (up to 6 most recent months)
                drawSectionHeading(cs, "MONTHLY CASH-FLOW, ABB & CONDUCT TREND", margin, y)
                y -= 10f
                val mCols = floatArrayOf(60f, 84f, 84f, 84f, 76f, 66f, contentW - 454f)
                drawTableHeader(
                    cs, margin, y - 18f, mCols, 18f,
                    listOf("Month", "Total Credits", "Total Debits", "Avg EOD Bal", "EMI Outflow", "Internal Cr", "Bounces/Ret")
                )
                y -= 18f

                report.monthlyMetrics.take(6).forEachIndexed { idx, m ->
                    val rowH = 20f
                    val bgTint = if (idx % 2 == 0) 255 else 248
                    fillRect(cs, margin, y - rowH, contentW, rowH, bgTint, bgTint, if (bgTint == 255) 255 else 250)
                    strokeRect(cs, margin, y - rowH, contentW, rowH, 226, 232, 240)
                    val cells = listOf(
                        m.monthLabel,
                        inr(m.totalCredits),
                        inr(m.totalDebits),
                        inr(m.averageEodBalance),
                        inr(m.emiLoanOutflow),
                        inr(m.internalCredits),
                        "${m.chequeBounceCount} Chq / ${m.emiReturnCount} EMI"
                    )
                    var cx = margin
                    cells.forEachIndexed { cIdx, txt ->
                        drawText(cs, txt, cx + 4f, y - 13f, if (cIdx == 0) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA, 7.6f, 30, 41, 59)
                        cx += mCols[cIdx]
                    }
                    y -= rowH
                }

                y -= 18f

                // Section 3: Underwriter 360 CAM & Collateral Summary
                drawSectionHeading(cs, "UNDERWRITER 360 CAM & SANCTION READINESS", margin, y)
                y -= 12f

                val camBoxH = 112f
                fillRect(cs, margin, y - camBoxH, contentW, camBoxH, 248, 250, 252)
                strokeRect(cs, margin, y - camBoxH, contentW, camBoxH, 203, 213, 225)

                val ltvPct = if (cam.collateralMarketValue > 0.0 && cam.proposedSanctionAmount > 0.0) {
                    (cam.proposedSanctionAmount / cam.collateralMarketValue) * 100.0
                } else 0.0
                val overdueLabel = if (cam.overdueAmount > 0.0) inr(cam.overdueAmount) else "Nil (Clean)"
                drawText(cs, "Bureau Score: ${cam.bureauScore} (${cam.bureauName})   |   Active Tradelines: ${cam.activeTradelines}   |   Overdue: $overdueLabel", margin + 10f, y - 16f, PDType1Font.HELVETICA_BOLD, 8.2f, 15, 36, 62)
                drawText(cs, "Proposed Sanction: ${inr(cam.proposedSanctionAmount)}   |   Collateral: ${cam.collateralType.label} (${inr(cam.collateralMarketValue)})   |   LTV: ${pct(ltvPct)}", margin + 10f, y - 30f, PDType1Font.HELVETICA, 8.2f, 30, 41, 59)
                drawText(cs, "Annual GST Turnover: ${inr(cam.annualGstTurnover)}   |   ITR PAT: ${inr(cam.itrNetProfitPat)}   |   Other Bureau EMI: ${inr(cam.additionalBureauMonthlyEmi)}/mo", margin + 10f, y - 44f, PDType1Font.HELVETICA, 8.2f, 30, 41, 59)
                drawText(cs, "Deep Analytics: Net True Credits ${inr(deep.internalTransfers.netTrueBusinessCredits)} | Top-3 Customer Share ${pct(deep.concentration.top3CustomerSharePct)}", margin + 10f, y - 58f, PDType1Font.HELVETICA, 8.2f, 30, 41, 59)
                drawText(cs, "Underwriter Recommendation: ${cam.decisionStatus.label}", margin + 10f, y - 74f, PDType1Font.HELVETICA_BOLD, 8.8f, 13, 148, 136)
                val remarksClean = cam.underwriterRemarks.ifBlank { "Auto-generated via BSA-YourView Deterministic Underwriting Engine." }
                drawText(cs, "Remarks: ${remarksClean.take(96)}", margin + 10f, y - 90f, PDType1Font.HELVETICA, 8f, 71, 85, 105)
                if (remarksClean.length > 96) {
                    drawText(cs, remarksClean.substring(96).take(102), margin + 10f, y - 102f, PDType1Font.HELVETICA, 8f, 71, 85, 105)
                }

                y -= (camBoxH + 18f)

                // Disclaimer Note
                if (y > 68f) {
                    drawText(
                        cs,
                        "Disclaimer: Eligibility amounts and risk scores are indicative underwriting estimates derived deterministically from parsed bank statement ePDFs and applicant CAM inputs. Final sanction remains subject to lender KYC, CIBIL/Bureau verification, legal & technical valuation.",
                        margin, y, PDType1Font.HELVETICA, 7.2f, 100, 116, 139
                    )
                }

                drawFooter(cs, pageW, margin, "Page 2 of 2 | BSA-YourView (yourview.in) | Client-Ready Executive Summary")
            }

            onProgress(0.95f, "Writing branded PDF Summary stream...")
            doc.save(outputStream)
            onProgress(1.0f, "Branded PDF Summary (.pdf) ready!")
        }
    }

    private fun drawSectionHeading(cs: PDPageContentStream, title: String, x: Float, y: Float) {
        drawText(cs, title, x, y, PDType1Font.HELVETICA_BOLD, 9.2f, 15, 36, 62)
        cs.setStrokingColor(13, 148, 136)
        cs.setLineWidth(1.2f)
        cs.moveTo(x, y - 3.5f)
        cs.lineTo(x + 120f, y - 3.5f)
        cs.stroke()
    }

    private fun drawScoreCard(
        cs: PDPageContentStream,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        title: String,
        bigValue: String,
        badge: String,
        subtext: String,
        accentR: Int,
        accentG: Int,
        accentB: Int
    ) {
        fillRect(cs, x, y, w, h, 255, 255, 255)
        strokeRect(cs, x, y, w, h, 203, 213, 225)
        // Top colored accent bar
        fillRect(cs, x, y + h - 4f, w, 4f, accentR, accentG, accentB)

        drawText(cs, title, x + 8f, y + h - 16f, PDType1Font.HELVETICA_BOLD, 7.5f, 100, 116, 139)
        drawText(cs, bigValue, x + 8f, y + h - 36f, PDType1Font.HELVETICA_BOLD, 15f, accentR, accentG, accentB)
        drawText(cs, badge, x + 8f, y + h - 50f, PDType1Font.HELVETICA_BOLD, 7.8f, 15, 36, 62)
        drawText(cs, subtext.take(34), x + 8f, y + 8f, PDType1Font.HELVETICA, 7.0f, 100, 116, 139)
    }

    private fun drawKpiCell(
        cs: PDPageContentStream,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        label: String,
        value: String,
        sub: String
    ) {
        fillRect(cs, x, y, w, h, 248, 250, 252)
        strokeRect(cs, x, y, w, h, 226, 232, 240)
        drawText(cs, label, x + 7f, y + h - 11f, PDType1Font.HELVETICA, 7.2f, 100, 116, 139)
        drawText(cs, value, x + 7f, y + h - 24f, PDType1Font.HELVETICA_BOLD, 9.5f, 15, 36, 62)
        drawText(cs, sub.take(22), x + 7f, y + 6f, PDType1Font.HELVETICA, 6.8f, 100, 116, 139)
    }

    private fun drawTableHeader(
        cs: PDPageContentStream,
        x: Float,
        y: Float,
        colWidths: FloatArray,
        h: Float,
        headers: List<String>
    ) {
        val totalW = colWidths.sum()
        fillRect(cs, x, y, totalW, h, 15, 36, 62)
        var cx = x
        headers.forEachIndexed { idx, title ->
            drawText(cs, title, cx + 4f, y + 6f, PDType1Font.HELVETICA_BOLD, 7.8f, 255, 255, 255)
            cx += colWidths[idx]
        }
    }

    private fun drawFooter(cs: PDPageContentStream, pageW: Float, margin: Float, text: String) {
        cs.setStrokingColor(203, 213, 225)
        cs.setLineWidth(0.75f)
        cs.moveTo(margin, 34f)
        cs.lineTo(pageW - margin, 34f)
        cs.stroke()
        drawText(cs, text, margin, 22f, PDType1Font.HELVETICA, 7.5f, 100, 116, 139)
    }

    private fun fillRect(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float, r: Int, g: Int, b: Int) {
        cs.setNonStrokingColor(r, g, b)
        cs.addRect(x, y, w, h)
        cs.fill()
    }

    private fun strokeRect(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float, r: Int, g: Int, b: Int) {
        cs.setStrokingColor(r, g, b)
        cs.setLineWidth(0.6f)
        cs.addRect(x, y, w, h)
        cs.stroke()
    }

    private fun drawText(
        cs: PDPageContentStream,
        rawText: String,
        x: Float,
        y: Float,
        font: PDType1Font,
        size: Float,
        r: Int,
        g: Int,
        b: Int
    ) {
        val safe = toPdfAscii(rawText)
        cs.beginText()
        cs.setFont(font, size)
        cs.setNonStrokingColor(r, g, b)
        cs.newLineAtOffset(x, y)
        cs.showText(safe)
        cs.endText()
    }

    private fun inr(amount: Double): String {
        val sign = if (amount < 0) "-" else ""
        return "${sign}Rs.${indianNumFmt.format(abs(amount))}"
    }

    private fun pct(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value)

    /**
     * PDFBox built-in Type1 Helvetica supports WinAnsi ASCII only.
     * Sanitizes ₹, em-dash, bullets, and non-ASCII characters safely to prevent font encoding crashes.
     */
    private fun toPdfAscii(text: String): String {
        return text
            .replace("\u20B9", "Rs.")
            .replace("\u2022", "|")
            .replace("\u2014", "-")
            .replace("\u2013", "-")
            .replace("\u2265", ">=")
            .replace("\u2264", "<=")
            .replace("\u00B0", " deg")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .filter { ch -> ch.code in 32..126 }
    }
}
