package com.example.bsa.engine

import com.example.bsa.model.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-account risk & cashflow metrics inside a multi-account consolidated portfolio.
 */
data class AccountPortfolioBreakdown(
    val accountKey: String,
    val accountLabel: String,
    val bankName: String,
    val accountNumber: String,
    val sourceFiles: List<String>,
    val txnCount: Int,
    val totalCredits: Double,
    val totalDebits: Double,
    val avgEodBalance: Double,
    val internalCredits: Double,
    val chequeBounceCount: Int,
    val emiReturnCount: Int,
    val emiOutflow: Double,
    val peakOdUtilizationPct: Double,
    val creditSharePct: Double,
    val accountRiskScore: Int,
    val riskTier: String
)

/**
 * Detected inter-account contra transfer where a debit in Account A matches a credit in Account B
 * for the exact same amount within ±2 calendar days (eliminated from consolidated turnover).
 */
data class CrossAccountTransferMatch(
    val dateIso: String,
    val fromAccount: String,
    val toAccount: String,
    val amount: Double,
    val narration: String
)

/**
 * Consolidated Portfolio Risk summary across 1 or more staged bank accounts.
 */
data class PortfolioRiskSummary(
    val accountCount: Int,
    val isMultiAccount: Boolean,
    val accounts: List<AccountPortfolioBreakdown>,
    val crossAccountTransfers: List<CrossAccountTransferMatch>,
    val crossAccountContraTotal: Double,
    val consolidatedGrossInflow: Double,
    val consolidatedTrueInflow: Double,
    val consolidatedOutflow: Double,
    val consolidatedAbb: Double,
    val consolidatedMonthlyEmi: Double,
    val dominantAccountSharePct: Double,
    val dominantAccountLabel: String,
    val hasConcentrationRisk: Boolean,
    val portfolioRiskScore: Int,
    val portfolioRiskTier: String,
    val bestFitSegment: SegmentEligibilityResult,
    val emiToAbbCoverageRatio: Double,
    val emiCoverageVerdict: String,
    val insights: List<String>
)

/**
 * Multi-Account Consolidated Portfolio Risk Engine:
 * Groups transactions by distinct bank accounts (via FileForensicAudit bank+account or staged account tags),
 * detects inter-bank cross-account contra transfers to prevent double-counting consolidated turnover,
 * computes weighted Portfolio Risk Score (0-100), concentration risk, best-fit loan segment,
 * and ABB-to-EMI coverage ratio (Indian NBFC/Bank 1.5x benchmark).
 */
object PortfolioRiskEngine {

    private val BOUNCE_REGEX = Regex(
        """\b(BOUNCE|BOUNCED|CHQ\s*RTN|CHQ\s*RET|CHEQUE\s*RET|CHEQUE\s*RETURN|INSUFFICIENT\s*FUND|INSUFF|DISHONOUR|DISHONOR|INWARD\s*RET|OUTWARD\s*RET|I/W\s*CHQ\s*RET|O/W\s*CHQ\s*RET)\b""",
        RegexOption.IGNORE_CASE
    )
    private val EMI_RETURN_REGEX = Regex(
        """\b(NACH\s*RTN|NACH\s*RET|NACH\s*RETURN|ECS\s*RTN|ECS\s*RET|ECS\s*RETURN|ACH\s*RTN|ACH\s*RET|EMI\s*BOUNCE|EMI\s*RET|MANDATE\s*FAIL|SI\s*FAIL|AUTO\s*DEBIT\s*FAIL|LOAN\s*RTN)\b""",
        RegexOption.IGNORE_CASE
    )

    fun evaluate(
        report: AnalysisReport,
        intel: CreditIntelligenceSummary = CreditIntelligenceEngine.evaluate(report)
    ): PortfolioRiskSummary {
        val auditByFile = report.fileAudits.associateBy { it.fileName }

        // Group transactions into distinct accounts via (detectedBank + normalizedAccountNumber),
        // falling back to user-assigned queue slot (accountGroupLabel) when account numbers are missing/masked.
        val groupedByAccountKey = linkedMapOf<String, MutableList<BankTransaction>>()
        val filesByAccountKey = linkedMapOf<String, MutableSet<String>>()
        val bankByAccountKey = linkedMapOf<String, String>()
        val accNoByAccountKey = linkedMapOf<String, String>()
        val slotLabelByAccountKey = linkedMapOf<String, String>()

        for (txn in report.transactions) {
            val audit = auditByFile[txn.sourceFile]
            val slotLabel = audit?.accountGroupLabel?.trim().orEmpty()
            val bank = audit?.detectedBank?.takeIf { it.isNotBlank() && it != "Unknown" }
                ?: report.metadata.bankName.ifBlank { "Primary Bank" }
            val rawAuditAcc = audit?.accountNumber?.trim().orEmpty()
            val hasRealAccNo = rawAuditAcc.isNotBlank() &&
                rawAuditAcc != "-" &&
                !rawAuditAcc.equals("Not Detected", ignoreCase = true)
            val accNo = if (hasRealAccNo) {
                rawAuditAcc
            } else if (slotLabel.isNotBlank()) {
                slotLabel
            } else {
                report.metadata.accountNumber.ifBlank { "Primary A/c" }
            }
            val normAcc = accNo.replace(Regex("""[^A-Za-z0-9]"""), "").uppercase(Locale.ROOT)
            val key = if (hasRealAccNo) {
                "${bank.uppercase(Locale.ROOT)}|$normAcc"
            } else if (slotLabel.isNotBlank()) {
                "SLOT|${slotLabel.uppercase(Locale.ROOT)}"
            } else {
                "${bank.uppercase(Locale.ROOT)}|$normAcc"
            }

            groupedByAccountKey.getOrPut(key) { mutableListOf() }.add(txn)
            filesByAccountKey.getOrPut(key) { linkedSetOf() }.add(txn.sourceFile)
            bankByAccountKey.putIfAbsent(key, bank)
            accNoByAccountKey.putIfAbsent(key, accNo)
            if (slotLabel.isNotBlank()) {
                slotLabelByAccountKey.putIfAbsent(key, slotLabel)
            }
        }

        if (groupedByAccountKey.isEmpty()) {
            val fallbackBest = intel.primarySegmentEligibility
            return PortfolioRiskSummary(
                accountCount = 0,
                isMultiAccount = false,
                accounts = emptyList(),
                crossAccountTransfers = emptyList(),
                crossAccountContraTotal = 0.0,
                consolidatedGrossInflow = 0.0,
                consolidatedTrueInflow = 0.0,
                consolidatedOutflow = 0.0,
                consolidatedAbb = 0.0,
                consolidatedMonthlyEmi = 0.0,
                dominantAccountSharePct = 0.0,
                dominantAccountLabel = "-",
                hasConcentrationRisk = false,
                portfolioRiskScore = 0,
                portfolioRiskTier = "Low Risk",
                bestFitSegment = fallbackBest,
                emiToAbbCoverageRatio = 0.0,
                emiCoverageVerdict = "Insufficient Data",
                insights = listOf("Upload bank statement ePDFs to calculate consolidated portfolio risk.")
            )
        }

        val totalPortfolioCredits = report.transactions.filter { it.amount > 0 }.sumOf { it.amount }
        val totalPortfolioDebits = report.transactions.filter { it.amount < 0 }.sumOf { -it.amount }
        val odLimit = report.config.drawingPowerLimit.takeIf { it > 0.0 } ?: report.config.sanctionedOdLimit

        val accountBreakdowns = groupedByAccountKey.entries.mapIndexed { idx, (key, txns) ->
            val bank = bankByAccountKey[key] ?: "Bank ${idx + 1}"
            val accNo = accNoByAccountKey[key] ?: "-"
            val shortAcc = if (accNo.length > 4) "XX${accNo.takeLast(4)}" else accNo
            val label = "$bank ($shortAcc)"

            val credits = txns.filter { it.amount > 0 }.sumOf { it.amount }
            val debits = txns.filter { it.amount < 0 }.sumOf { -it.amount }
            val internalCr = txns.filter { it.amount > 0 && it.isSelfTransfer }.sumOf { it.amount }

            val chqBounces = txns.count { t ->
                !EMI_RETURN_REGEX.containsMatchIn(t.description) &&
                    (BOUNCE_REGEX.containsMatchIn(t.description) ||
                        (t.category == "Reversal" && t.description.contains("CHQ", ignoreCase = true)))
            }
            val emiReturns = txns.count { t -> EMI_RETURN_REGEX.containsMatchIn(t.description) }
            val emiOutflow = txns.filter { it.amount < 0 && it.category == "Loan / EMI" }.sumOf { -it.amount }

            // Per-account Average EOD Balance from date-end balances
            val eodByDate = linkedMapOf<String, Double>()
            txns.sortedWith(compareBy<BankTransaction> { it.dateIso }.thenBy { it.slNo }).forEach {
                eodByDate[it.dateIso] = it.balance
            }
            val avgEod = if (eodByDate.isNotEmpty()) eodByDate.values.average() else 0.0
            val minBal = eodByDate.values.minOrNull() ?: 0.0
            val peakOdPct = if (odLimit > 0.0 && minBal < 0.0) {
                ((-minBal) / odLimit * 100.0).coerceAtLeast(0.0)
            } else 0.0

            // Per-account Risk Score (0-100; higher = riskier)
            var riskPts = 8 // baseline exposure
            riskPts += (chqBounces * 14).coerceAtMost(35)
            riskPts += (emiReturns * 20).coerceAtMost(40)
            if (peakOdPct > 95.0) riskPts += 20 else if (peakOdPct > 85.0) riskPts += 10
            val internalShare = if (credits > 0.0) internalCr / credits else 0.0
            if (internalShare > 0.35) riskPts += 15 else if (internalShare > 0.20) riskPts += 8
            val mismatchCount = txns.count { it.hasBalanceMismatch }
            if (mismatchCount > 0) riskPts += (mismatchCount * 10).coerceAtMost(25)
            val clampedRisk = riskPts.coerceIn(0, 100)
            val tier = when {
                clampedRisk >= 60 -> "High Risk"
                clampedRisk >= 30 -> "Moderate Risk"
                else -> "Low Risk"
            }

            val sharePct = if (totalPortfolioCredits > 0.0) (credits / totalPortfolioCredits) * 100.0 else 0.0

            AccountPortfolioBreakdown(
                accountKey = key,
                accountLabel = label,
                bankName = bank,
                accountNumber = accNo,
                sourceFiles = filesByAccountKey[key]?.toList().orEmpty(),
                txnCount = txns.size,
                totalCredits = credits,
                totalDebits = debits,
                avgEodBalance = avgEod,
                internalCredits = internalCr,
                chequeBounceCount = chqBounces,
                emiReturnCount = emiReturns,
                emiOutflow = emiOutflow,
                peakOdUtilizationPct = peakOdPct,
                creditSharePct = sharePct,
                accountRiskScore = clampedRisk,
                riskTier = tier
            )
        }

        // Cross-Account Inter-Bank Contra Transfer Detection (only meaningful when >= 2 accounts)
        val crossMatches = mutableListOf<CrossAccountTransferMatch>()
        if (accountBreakdowns.size >= 2) {
            val accountKeys = groupedByAccountKey.keys.toList()
            val labelByKey = accountBreakdowns.associate { it.accountKey to it.accountLabel }
            val usedCreditIds = mutableSetOf<Int>()

            for (i in accountKeys.indices) {
                val fromKey = accountKeys[i]
                val debits = groupedByAccountKey[fromKey].orEmpty().filter { it.amount < 0 && abs(it.amount) >= 10000.0 }
                for (dTxn in debits) {
                    val dAmt = -dTxn.amount
                    val dDay = isoToEpochDay(dTxn.dateIso)
                    var matched = false
                    for (j in accountKeys.indices) {
                        if (i == j || matched) continue
                        val toKey = accountKeys[j]
                        val candidateCredit = groupedByAccountKey[toKey].orEmpty().firstOrNull { cTxn ->
                            cTxn.slNo !in usedCreditIds &&
                                cTxn.amount > 0 &&
                                abs(cTxn.amount - dAmt) <= 1.0 &&
                                abs(isoToEpochDay(cTxn.dateIso) - dDay) <= 2L
                        }
                        if (candidateCredit != null) {
                            usedCreditIds.add(candidateCredit.slNo)
                            crossMatches.add(
                                CrossAccountTransferMatch(
                                    dateIso = dTxn.dateIso,
                                    fromAccount = labelByKey[fromKey] ?: fromKey,
                                    toAccount = labelByKey[toKey] ?: toKey,
                                    amount = dAmt,
                                    narration = dTxn.description.take(65)
                                )
                            )
                            matched = true
                        }
                    }
                }
            }
        }

        val crossContraTotal = crossMatches.sumOf { it.amount }
        val internalSelfTotal = report.totalInternalCredits
        val trueConsolidatedInflow = (totalPortfolioCredits - internalSelfTotal - crossContraTotal).coerceAtLeast(0.0)
        val consolidatedAbb = accountBreakdowns.sumOf { it.avgEodBalance }

        val dominant = accountBreakdowns.maxByOrNull { it.creditSharePct }
        val dominantShare = dominant?.creditSharePct ?: 0.0
        val hasConcentration = accountBreakdowns.size >= 2 && dominantShare >= 80.0

        // Weighted Portfolio Risk Score:
        // 55% credit-weighted per-account risk + 35% 10-point screening risk + 10% cross-account/concentration adjustment
        val weightedAccountRisk = if (totalPortfolioCredits > 0.0) {
            accountBreakdowns.sumOf { it.accountRiskScore * (it.totalCredits / totalPortfolioCredits) }
        } else {
            accountBreakdowns.map { it.accountRiskScore }.average()
        }
        val screeningRisk = com.example.bsa.service.FraudScreeningService.computeRiskSummary(report.fraudIndicators).riskScore
        val crossContraPenalty = if (totalPortfolioCredits > 0.0 && (crossContraTotal / totalPortfolioCredits) > 0.15) 12.0 else 0.0
        val concentrationPenalty = if (hasConcentration) 6.0 else 0.0
        val forensicPenalty = when (report.overallForensicStatus) {
            ForensicStatus.VERIFIED -> 0.0
            ForensicStatus.WARNING -> 10.0
            ForensicStatus.REJECTED -> 25.0
        }
        val portfolioScore = (weightedAccountRisk * 0.55 + screeningRisk * 0.35 + crossContraPenalty + concentrationPenalty + forensicPenalty)
            .roundToInt()
            .coerceIn(0, 100)

        val portfolioTier = when {
            portfolioScore >= 60 -> "High Risk"
            portfolioScore >= 30 -> "Moderate Risk"
            else -> "Low Risk"
        }

        // Best-Fit Loan Segment across all 4 segments
        val bestFit = intel.segmentEligibilities
            .sortedWith(
                compareBy<SegmentEligibilityResult> {
                    when (it.status) {
                        EligibilityStatus.ELIGIBLE -> 0
                        EligibilityStatus.CONDITIONAL -> 1
                        EligibilityStatus.INELIGIBLE -> 2
                    }
                }.thenByDescending { it.recommendedAmount }
            )
            .firstOrNull() ?: intel.primarySegmentEligibility

        // ABB-to-Proposed-EMI Coverage Ratio (Indian NBFC/Bank benchmark >= 1.50x)
        val proposedEmi = bestFit.maxMonthlyServicing.coerceAtLeast(1.0)
        val coverageRatio = if (consolidatedAbb > 0.0) {
            (consolidatedAbb / proposedEmi).coerceAtMost(99.9)
        } else 0.0
        val coverageVerdict = when {
            report.config.facilityType != FacilityType.NONE && consolidatedAbb <= 0.0 -> "OD/CC Revolving (Check Drawing Power)"
            coverageRatio >= 2.0 -> "Strong Liquidity (${String.format(Locale.ROOT, "%.2fx", coverageRatio)} ABB/EMI)"
            coverageRatio >= 1.5 -> "Meets Bank Norm (${String.format(Locale.ROOT, "%.2fx", coverageRatio)} ABB/EMI)"
            coverageRatio >= 1.0 -> "Tight Liquidity (${String.format(Locale.ROOT, "%.2fx", coverageRatio)} ABB/EMI)"
            else -> "Below 1.5x Norm (${String.format(Locale.ROOT, "%.2fx", coverageRatio)} ABB/EMI)"
        }

        val insights = buildList {
            if (accountBreakdowns.size >= 2) {
                add("Consolidated ${accountBreakdowns.size} distinct bank accounts into a unified portfolio risk view.")
            } else {
                add("Single bank account analyzed — add statements from secondary accounts to unlock cross-bank contra checks.")
            }
            if (crossMatches.isNotEmpty()) {
                add("Detected ${crossMatches.size} inter-bank contra transfer(s) totaling Rs.${String.format(Locale.ROOT, "%,.0f", crossContraTotal)} — excluded from true consolidated turnover.")
            }
            if (hasConcentration && dominant != null) {
                add("Concentration Alert: ${dominant.accountLabel} accounts for ${String.format(Locale.ROOT, "%.1f%%", dominantShare)} of total inflows.")
            }
            add("Best-Fit Segment: ${bestFit.productType.displayName} (${bestFit.status.label}) — Recommended Rs.${String.format(Locale.ROOT, "%,.0f", bestFit.recommendedAmount)}.")
        }

        return PortfolioRiskSummary(
            accountCount = accountBreakdowns.size,
            isMultiAccount = accountBreakdowns.size >= 2,
            accounts = accountBreakdowns,
            crossAccountTransfers = crossMatches,
            crossAccountContraTotal = crossContraTotal,
            consolidatedGrossInflow = totalPortfolioCredits,
            consolidatedTrueInflow = trueConsolidatedInflow,
            consolidatedOutflow = totalPortfolioDebits,
            consolidatedAbb = consolidatedAbb,
            consolidatedMonthlyEmi = intel.avgMonthlyEmiObligation,
            dominantAccountSharePct = dominantShare,
            dominantAccountLabel = dominant?.accountLabel ?: "-",
            hasConcentrationRisk = hasConcentration,
            portfolioRiskScore = portfolioScore,
            portfolioRiskTier = portfolioTier,
            bestFitSegment = bestFit,
            emiToAbbCoverageRatio = coverageRatio,
            emiCoverageVerdict = coverageVerdict,
            insights = insights
        )
    }

    private fun isoToEpochDay(iso: String): Long {
        val parts = iso.split("-")
        if (parts.size != 3) return 0L
        val y = parts[0].toIntOrNull() ?: return 0L
        val m = parts[1].toIntOrNull() ?: return 0L
        val d = parts[2].toIntOrNull() ?: return 0L
        // Compact monotonic day approximation sufficient for ±2 day delta
        return (y * 365L) + (m * 31L) + d
    }
}
