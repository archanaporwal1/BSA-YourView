package com.example.bsa.service

import com.example.bsa.engine.EligibilityStatus
import com.example.bsa.engine.SegmentEligibilityResult
import com.example.bsa.model.AnalysisReport
import com.example.bsa.model.ProductType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Summarized transaction metrics input for initial multi-segment funding eligibility calculation.
 * Can be populated directly from an [AnalysisReport] or entered/adjusted manually by an underwriter.
 */
data class SummarizedMetricsInput(
    val totalInflow: Double,
    val totalOutflow: Double,
    val averageBalance: Double,
    val odUtilizationPct: Double = 0.0,
    val sanctionedOdLimit: Double = 0.0,
    val monthlyEmiObligation: Double = 0.0,
    val monthCount: Int = 6,
    val internalCreditsTotal: Double = 0.0,
    val chequeBounceCount: Int = 0,
    val emiReturnCount: Int = 0,
    val isSalaried: Boolean = false
)

/**
 * Structured output returned by [EligibilityCalculatorService] across all 4 Indian lending segments.
 */
data class QuickEligibilityReport(
    val input: SummarizedMetricsInput,
    val adjustedTotalInflow: Double,
    val monthlyAdjustedInflow: Double,
    val monthlyOutflow: Double,
    val monthlyNetSurplus: Double,
    val annualizedTurnover: Double,
    val riskHaircutPct: Int,
    val riskHaircutSummary: String,
    val segments: List<SegmentEligibilityResult>
) {
    fun segmentFor(type: ProductType): SegmentEligibilityResult? =
        segments.firstOrNull { it.productType == type }
}

/**
 * Dedicated Eligibility Calculator Service for BSA-YourView.
 *
 * Accepts summarized transaction metrics (Total Inflow, Total Outflow, Average Balance,
 * OD Utilization %, Internal Credits, Cheque Bounces, and EMI Returns) and applies
 * industry-standard Indian NBFC / Bank underwriting formulas across:
 *   1. Auto Loan
 *   2. Business Loan
 *   3. Working Capital
 *   4. Home Loan
 */
object EligibilityCalculatorService {

    private const val AUTO_RATE = 9.25
    private const val AUTO_TENURE = 84

    private const val BUSINESS_RATE = 15.0
    private const val BUSINESS_TENURE = 48

    private const val WC_RATE = 11.25
    private const val WC_TENURE = 12

    private const val HOME_RATE = 8.75
    private const val HOME_TENURE = 240

    fun calculateFromReport(report: AnalysisReport): QuickEligibilityReport {
        val monthCount = max(1, report.monthlyMetrics.size)
        val totalInflow = report.totalInflow
        val totalOutflow = report.totalOutflow
        val avgBal = report.averageMonthlyBalance
        val avgOdUtilPct = if (report.monthlyMetrics.isNotEmpty()) {
            report.monthlyMetrics.map { it.avgOdUtilizationPct }.average()
        } else 0.0
        val peakOdUtilPct = report.peakOdUtilizationPct
        val effectiveOdUtil = max(avgOdUtilPct, peakOdUtilPct * 0.90)
        val monthlyEmi = report.totalEmiOutflow / monthCount.toDouble()
        val salaryCredits = report.transactions
            .filter { it.amount > 0 && it.category.equals("Salary / Payroll", ignoreCase = true) }
            .sumOf { it.amount }
        val isSalaried = totalInflow > 0.0 && (salaryCredits / totalInflow) >= 0.45

        return calculateFromMetrics(
            SummarizedMetricsInput(
                totalInflow = totalInflow,
                totalOutflow = totalOutflow,
                averageBalance = avgBal,
                odUtilizationPct = effectiveOdUtil,
                sanctionedOdLimit = report.config.sanctionedOdLimit,
                monthlyEmiObligation = monthlyEmi,
                monthCount = monthCount,
                internalCreditsTotal = report.totalInternalCredits,
                chequeBounceCount = report.totalChequeBounceCount,
                emiReturnCount = report.totalEmiReturnCount,
                isSalaried = isSalaried
            )
        )
    }

    fun calculateFromMetrics(input: SummarizedMetricsInput): QuickEligibilityReport {
        val safeMonths = input.monthCount.coerceIn(1, 36)
        val safeInflow = input.totalInflow.coerceAtLeast(0.0)
        val safeOutflow = input.totalOutflow.coerceAtLeast(0.0)
        val safeInternal = input.internalCreditsTotal.coerceIn(0.0, safeInflow)
        val adjustedInflow = (safeInflow - safeInternal).coerceAtLeast(0.0)
        val monthlyAdjustedInflow = adjustedInflow / safeMonths.toDouble()
        val monthlyOutflow = safeOutflow / safeMonths.toDouble()
        val monthlySurplus = (monthlyAdjustedInflow - monthlyOutflow).coerceAtLeast(0.0)
        val annualizedTurnover = monthlyAdjustedInflow * 12.0
        val positiveAbb = input.averageBalance.coerceAtLeast(0.0)
        val existingEmi = input.monthlyEmiObligation.coerceAtLeast(0.0)
        val odUtil = input.odUtilizationPct.coerceIn(0.0, 150.0)
        val sanctionedOd = input.sanctionedOdLimit.coerceAtLeast(0.0)

        // Risk Haircut Computation (Cheque Bounces + EMI Returns + OD Stress)
        val haircutReasons = mutableListOf<String>()
        var haircutPct = 0
        if (input.emiReturnCount >= 2) {
            haircutPct += 35
            haircutReasons += "${input.emiReturnCount} EMI returns (-35%)"
        } else if (input.emiReturnCount == 1) {
            haircutPct += 20
            haircutReasons += "1 EMI return (-20%)"
        }
        if (input.chequeBounceCount >= 3) {
            haircutPct += 25
            haircutReasons += "${input.chequeBounceCount} cheque bounces (-25%)"
        } else if (input.chequeBounceCount in 1..2) {
            haircutPct += 12
            haircutReasons += "${input.chequeBounceCount} cheque bounce(s) (-12%)"
        }
        if (odUtil >= 95.0) {
            haircutPct += 20
            haircutReasons += "OD util ${odUtil.toInt()}% (-20%)"
        } else if (odUtil >= 85.0) {
            haircutPct += 10
            haircutReasons += "OD util ${odUtil.toInt()}% (-10%)"
        }
        haircutPct = haircutPct.coerceAtMost(65)
        val riskFactor = (100.0 - haircutPct) / 100.0
        val haircutSummary = if (haircutReasons.isEmpty()) {
            "0% (Clean banking conduct)"
        } else {
            "$haircutPct% (${haircutReasons.joinToString(", ")})"
        }

        val autoLoan = computeAutoLoan(
            monthlyInflow = monthlyAdjustedInflow,
            monthlySurplus = monthlySurplus,
            positiveAbb = positiveAbb,
            existingEmi = existingEmi,
            odUtil = odUtil,
            isSalaried = input.isSalaried,
            riskFactor = riskFactor,
            haircutSummary = haircutSummary,
            emiReturns = input.emiReturnCount,
            chequeBounces = input.chequeBounceCount
        )
        val businessLoan = computeBusinessLoan(
            annualizedTurnover = annualizedTurnover,
            monthlySurplus = monthlySurplus,
            positiveAbb = positiveAbb,
            existingEmi = existingEmi,
            odUtil = odUtil,
            riskFactor = riskFactor,
            haircutSummary = haircutSummary,
            emiReturns = input.emiReturnCount,
            chequeBounces = input.chequeBounceCount
        )
        val workingCapital = computeWorkingCapital(
            annualizedTurnover = annualizedTurnover,
            positiveAbb = positiveAbb,
            odUtil = odUtil,
            sanctionedOd = sanctionedOd,
            riskFactor = riskFactor,
            haircutSummary = haircutSummary,
            emiReturns = input.emiReturnCount,
            chequeBounces = input.chequeBounceCount
        )
        val homeLoan = computeHomeLoan(
            annualizedTurnover = annualizedTurnover,
            monthlyInflow = monthlyAdjustedInflow,
            monthlySurplus = monthlySurplus,
            positiveAbb = positiveAbb,
            existingEmi = existingEmi,
            odUtil = odUtil,
            isSalaried = input.isSalaried,
            riskFactor = riskFactor,
            haircutSummary = haircutSummary,
            emiReturns = input.emiReturnCount,
            chequeBounces = input.chequeBounceCount
        )

        return QuickEligibilityReport(
            input = input,
            adjustedTotalInflow = adjustedInflow,
            monthlyAdjustedInflow = monthlyAdjustedInflow,
            monthlyOutflow = monthlyOutflow,
            monthlyNetSurplus = monthlySurplus,
            annualizedTurnover = annualizedTurnover,
            riskHaircutPct = haircutPct,
            riskHaircutSummary = haircutSummary,
            segments = listOf(autoLoan, businessLoan, workingCapital, homeLoan)
        )
    }

    private fun computeAutoLoan(
        monthlyInflow: Double,
        monthlySurplus: Double,
        positiveAbb: Double,
        existingEmi: Double,
        odUtil: Double,
        isSalaried: Boolean,
        riskFactor: Double,
        haircutSummary: String,
        emiReturns: Int,
        chequeBounces: Int
    ): SegmentEligibilityResult {
        val assessedMonthlyIncome = if (isSalaried) {
            monthlyInflow
        } else {
            max(monthlyInflow * 0.18, monthlySurplus * 0.65)
        }
        val maxFoirRatio = if (isSalaried) 0.55 else 0.50
        val currentFoirPct = if (assessedMonthlyIncome > 0.0) (existingEmi / assessedMonthlyIncome) * 100.0 else 0.0
        val availableEmi = (assessedMonthlyIncome * maxFoirRatio - existingEmi).coerceAtLeast(0.0)
        val foirLoan = loanPrincipalFromEmi(availableEmi, AUTO_RATE, AUTO_TENURE)
        val abbLoan = positiveAbb * 5.0
        val odAdjustment = if (odUtil >= 90.0) 0.80 else 1.0
        val baseAmount = max(foirLoan, abbLoan * 0.65) * odAdjustment
        val finalAmount = roundToNearestTenThousand(min(baseAmount * riskFactor, 15_000_000.0))
        val conservative = roundToNearestTenThousand(finalAmount * 0.80)
        val maxEligible = roundToNearestTenThousand(min(baseAmount * 1.10, 15_000_000.0))

        val status = when {
            finalAmount < 150_000.0 || emiReturns >= 3 -> EligibilityStatus.INELIGIBLE
            emiReturns >= 1 || chequeBounces >= 2 || odUtil >= 90.0 -> EligibilityStatus.CONDITIONAL
            else -> EligibilityStatus.ELIGIBLE
        }

        val limiting = when {
            emiReturns >= 1 -> "EMI / NACH return conduct"
            odUtil >= 90.0 -> "High OD utilization (${odUtil.toInt()}%)"
            foirLoan >= abbLoan * 0.65 -> "FOIR ${(maxFoirRatio * 100).toInt()}% net income capacity"
            else -> "5x Average Bank Balance surrogate"
        }

        val notes = mutableListOf<String>()
        if (emiReturns > 0) notes += "$emiReturns EMI return(s) detected — require fresh NACH & 6m clean track."
        if (chequeBounces > 0) notes += "$chequeBounces cheque bounce(s) applied in conduct haircut."
        if (odUtil >= 85.0) notes += "High OD utilization (${odUtil.toInt()}%) reduces vehicle loan headroom."
        if (notes.isEmpty()) notes += "Strong cash-flow & ABB coverage for Auto Loan (up to 85%–90% on-road LTV)."

        return SegmentEligibilityResult(
            productType = ProductType.AUTO_LOAN,
            programName = if (isSalaried) "Salaried FOIR + ABB Auto Program" else "Banking Surrogate Auto Program",
            status = status,
            conservativeAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else conservative,
            recommendedAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else finalAmount,
            maxEligibleAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else maxEligible,
            maxMonthlyServicing = if (status == EligibilityStatus.INELIGIBLE) 0.0 else emiForPrincipal(finalAmount, AUTO_RATE, AUTO_TENURE),
            isRevolvingFacility = false,
            benchmarkRatePct = AUTO_RATE,
            benchmarkTenureMonths = AUTO_TENURE,
            maxAllowedFoirPct = maxFoirRatio * 100.0,
            currentFoirPct = currentFoirPct,
            limitingFactor = limiting,
            breakdownRows = listOf(
                "Assessed Monthly Income" to formatRupee(assessedMonthlyIncome),
                "Net Available EMI (FOIR)" to formatRupee(availableEmi),
                "FOIR Principal (${AUTO_TENURE}m)" to formatRupee(foirLoan),
                "ABB Surrogate (5x Avg Bal)" to formatRupee(abbLoan),
                "Risk Haircut" to haircutSummary
            ),
            policyNotes = notes
        )
    }

    private fun computeBusinessLoan(
        annualizedTurnover: Double,
        monthlySurplus: Double,
        positiveAbb: Double,
        existingEmi: Double,
        odUtil: Double,
        riskFactor: Double,
        haircutSummary: String,
        emiReturns: Int,
        chequeBounces: Int
    ): SegmentEligibilityResult {
        val turnoverMethod = annualizedTurnover * 0.12
        val abbMethod = positiveAbb * 5.0
        val assessedMonthlyProfit = max(annualizedTurnover / 12.0 * 0.12, monthlySurplus)
        val currentFoirPct = if (assessedMonthlyProfit > 0.0) (existingEmi / assessedMonthlyProfit) * 100.0 else 0.0
        val surplusMonthlyCapacity = (assessedMonthlyProfit * 0.55 - existingEmi).coerceAtLeast(0.0)
        val dscrMethod = loanPrincipalFromEmi(surplusMonthlyCapacity, BUSINESS_RATE, BUSINESS_TENURE)

        val blendedBase = listOf(turnoverMethod, max(abbMethod, dscrMethod)).filter { it > 0.0 }.minOrNull() ?: 0.0
        val odMultiplier = when {
            odUtil >= 95.0 -> 0.50
            odUtil >= 85.0 -> 0.75
            else -> 1.0
        }
        val finalAmount = roundToNearestTenThousand(min(blendedBase * odMultiplier * riskFactor, 7_500_000.0))
        val conservative = roundToNearestTenThousand(finalAmount * 0.80)
        val maxEligible = roundToNearestTenThousand(min(max(turnoverMethod, abbMethod) * odMultiplier, 7_500_000.0))

        val status = when {
            finalAmount < 200_000.0 || emiReturns >= 2 -> EligibilityStatus.INELIGIBLE
            emiReturns == 1 || chequeBounces >= 2 || odUtil >= 85.0 -> EligibilityStatus.CONDITIONAL
            else -> EligibilityStatus.ELIGIBLE
        }

        val limiting = when {
            odUtil >= 85.0 -> "OD utilization stress (${odUtil.toInt()}%)"
            turnoverMethod <= max(abbMethod, dscrMethod) -> "12% Adjusted Annual Turnover cap"
            else -> "ABB / DSCR repayment headroom"
        }

        val notes = mutableListOf<String>()
        if (odUtil >= 85.0) notes += "OD utilization at ${odUtil.toInt()}% signals working-capital tightness — unsecured cap moderated."
        if (emiReturns > 0 || chequeBounces > 0) notes += "Conduct stress ($chequeBounces cheque bounce(s), $emiReturns EMI return(s)) — verify GST 3B & bureau."
        if (notes.isEmpty()) notes += "Eligible under Unsecured Banking-Turnover (12% BTO) + 5x ABB Business Loan matrix."

        return SegmentEligibilityResult(
            productType = ProductType.BUSINESS_LOAN,
            programName = "Unsecured BTO + ABB Surrogate",
            status = status,
            conservativeAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else conservative,
            recommendedAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else finalAmount,
            maxEligibleAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else maxEligible,
            maxMonthlyServicing = if (status == EligibilityStatus.INELIGIBLE) 0.0 else emiForPrincipal(finalAmount, BUSINESS_RATE, BUSINESS_TENURE),
            isRevolvingFacility = false,
            benchmarkRatePct = BUSINESS_RATE,
            benchmarkTenureMonths = BUSINESS_TENURE,
            maxAllowedFoirPct = 55.0,
            currentFoirPct = currentFoirPct,
            limitingFactor = limiting,
            breakdownRows = listOf(
                "Adjusted Annual Turnover" to formatRupee(annualizedTurnover),
                "12% Turnover Eligibility" to formatRupee(turnoverMethod),
                "5x Average Balance Cap" to formatRupee(abbMethod),
                "Surplus EMI Principal" to formatRupee(dscrMethod),
                "OD Utilization Factor" to "${(odMultiplier * 100).toInt()}% (OD Util ${odUtil.toInt()}%)",
                "Risk Haircut" to haircutSummary
            ),
            policyNotes = notes
        )
    }

    private fun computeWorkingCapital(
        annualizedTurnover: Double,
        positiveAbb: Double,
        odUtil: Double,
        sanctionedOd: Double,
        riskFactor: Double,
        haircutSummary: String,
        emiReturns: Int,
        chequeBounces: Int
    ): SegmentEligibilityResult {
        // Nayak Committee: 20% of projected/adjusted annual banking turnover
        val nayakLimit = annualizedTurnover * 0.20
        val abbSupport = positiveAbb * 6.0
        val grossLimit = if (nayakLimit > 0.0) nayakLimit else abbSupport

        val odBehaviorFactor = when {
            odUtil >= 98.0 -> 0.70
            odUtil >= 90.0 -> 0.85
            else -> 1.0
        }
        val totalAssessedLimit = roundToNearestTenThousand(min(grossLimit * odBehaviorFactor * riskFactor, 50_000_000.0))
        val conservative = roundToNearestTenThousand(totalAssessedLimit * 0.80)
        val maxEligible = roundToNearestTenThousand(min(grossLimit, 50_000_000.0))
        val enhancementGap = if (sanctionedOd > 0.0) (totalAssessedLimit - sanctionedOd).coerceAtLeast(0.0) else null
        val monthlyInterestCost = (totalAssessedLimit * (WC_RATE / 100.0)) / 12.0

        val status = when {
            totalAssessedLimit < 500_000.0 || emiReturns >= 3 -> EligibilityStatus.INELIGIBLE
            odUtil >= 92.0 || chequeBounces >= 2 || emiReturns >= 1 -> EligibilityStatus.CONDITIONAL
            else -> EligibilityStatus.ELIGIBLE
        }

        val limiting = when {
            odUtil >= 90.0 -> "OD utilization saturation (${odUtil.toInt()}%)"
            else -> "Nayak Committee 20% Adjusted Turnover"
        }

        val notes = mutableListOf<String>()
        if (sanctionedOd > 0.0) {
            val gap = enhancementGap ?: 0.0
            notes += if (gap > 0.0) {
                "Existing OD ${formatRupeeCompact(sanctionedOd)} — supports enhancement up to ${formatRupeeCompact(gap)}."
            } else {
                "Existing OD ${formatRupeeCompact(sanctionedOd)} fully covers assessed Nayak Committee requirement."
            }
        }
        if (odUtil >= 90.0) notes += "OD utilization ${odUtil.toInt()}% signals limit saturation — check stock/debtors & interest servicing."
        if (notes.isEmpty()) notes += "Qualifies under RBI Nayak Committee (20% of adjusted annual turnover) revolving CC/OD assessment."

        return SegmentEligibilityResult(
            productType = ProductType.WORKING_CAPITAL,
            programName = "Nayak Committee 20% Turnover CC/OD",
            status = status,
            conservativeAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else conservative,
            recommendedAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else totalAssessedLimit,
            maxEligibleAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else maxEligible,
            maxMonthlyServicing = if (status == EligibilityStatus.INELIGIBLE) 0.0 else monthlyInterestCost,
            isRevolvingFacility = true,
            benchmarkRatePct = WC_RATE,
            benchmarkTenureMonths = WC_TENURE,
            maxAllowedFoirPct = 65.0,
            currentFoirPct = odUtil.coerceAtMost(100.0),
            existingFacilityGap = enhancementGap,
            limitingFactor = limiting,
            breakdownRows = listOf(
                "Adjusted Annual Turnover" to formatRupee(annualizedTurnover),
                "Nayak 20% Gross Limit" to formatRupee(nayakLimit),
                "Existing Sanctioned OD" to formatRupee(sanctionedOd),
                "OD Utilization Observed" to "${odUtil.toInt()}%",
                "Est. Monthly Interest" to formatRupee(monthlyInterestCost),
                "Risk Haircut" to haircutSummary
            ),
            policyNotes = notes
        )
    }

    private fun computeHomeLoan(
        annualizedTurnover: Double,
        monthlyInflow: Double,
        monthlySurplus: Double,
        positiveAbb: Double,
        existingEmi: Double,
        odUtil: Double,
        isSalaried: Boolean,
        riskFactor: Double,
        haircutSummary: String,
        emiReturns: Int,
        chequeBounces: Int
    ): SegmentEligibilityResult {
        val assessedMonthlyIncome = if (isSalaried) {
            monthlyInflow
        } else {
            max(monthlyInflow * 0.16, monthlySurplus * 0.70)
        }
        val foirCap = if (isSalaried) 0.65 else 0.60
        val currentFoirPct = if (assessedMonthlyIncome > 0.0) (existingEmi / assessedMonthlyIncome) * 100.0 else 0.0
        val availableEmi = (assessedMonthlyIncome * foirCap - existingEmi).coerceAtLeast(0.0)
        val foirPrincipal = loanPrincipalFromEmi(availableEmi, HOME_RATE, HOME_TENURE)
        val abbSurrogate = positiveAbb * 12.0
        val turnoverSurrogate = annualizedTurnover * 0.55

        val baseAmount = if (isSalaried) {
            foirPrincipal
        } else {
            max(foirPrincipal, min(turnoverSurrogate, abbSurrogate * 1.5))
        }
        val odFactor = if (odUtil >= 92.0) 0.85 else 1.0
        val finalAmount = roundToNearestTenThousand(min(baseAmount * odFactor * riskFactor, 75_000_000.0))
        val conservative = roundToNearestTenThousand(finalAmount * 0.85)
        val maxEligible = roundToNearestTenThousand(min(baseAmount * 1.08, 75_000_000.0))

        val status = when {
            finalAmount < 500_000.0 || emiReturns >= 3 -> EligibilityStatus.INELIGIBLE
            emiReturns >= 1 || chequeBounces >= 3 || odUtil >= 92.0 -> EligibilityStatus.CONDITIONAL
            else -> EligibilityStatus.ELIGIBLE
        }

        val limiting = when {
            emiReturns >= 1 -> "EMI / NACH return conduct"
            isSalaried -> "65% Salaried FOIR @ $HOME_RATE% / 20Y"
            else -> "60% FOIR & Self-Employed Banking Surrogate"
        }

        val notes = mutableListOf<String>()
        if (emiReturns > 0) notes += "$emiReturns EMI return(s) require explanation & 6m clean repayment conduct for prime Home Loan rate."
        if (odUtil >= 90.0) notes += "High OD utilization (${odUtil.toInt()}%) factored into prudent repayment margin."
        if (notes.isEmpty()) notes += "Strong 20-year repayment headroom (up to 75%–80% property LTV subject to legal/technical valuation)."

        return SegmentEligibilityResult(
            productType = ProductType.HOME_LOAN,
            programName = if (isSalaried) "Salaried 65% FOIR Home Loan" else "Self-Employed Banking + FOIR Home Loan",
            status = status,
            conservativeAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else conservative,
            recommendedAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else finalAmount,
            maxEligibleAmount = if (status == EligibilityStatus.INELIGIBLE) 0.0 else maxEligible,
            maxMonthlyServicing = if (status == EligibilityStatus.INELIGIBLE) 0.0 else emiForPrincipal(finalAmount, HOME_RATE, HOME_TENURE),
            isRevolvingFacility = false,
            benchmarkRatePct = HOME_RATE,
            benchmarkTenureMonths = HOME_TENURE,
            maxAllowedFoirPct = foirCap * 100.0,
            currentFoirPct = currentFoirPct,
            limitingFactor = limiting,
            breakdownRows = listOf(
                "Assessed Monthly Income" to formatRupee(assessedMonthlyIncome),
                "Max Permissible EMI" to formatRupee(availableEmi),
                "FOIR 20Y Principal" to formatRupee(foirPrincipal),
                "ABB Surrogate (12x)" to formatRupee(abbSurrogate),
                "Risk Haircut" to haircutSummary
            ),
            policyNotes = notes
        )
    }

    fun loanPrincipalFromEmi(monthlyEmi: Double, annualRatePct: Double, tenureMonths: Int): Double {
        if (monthlyEmi <= 0.0 || tenureMonths <= 0) return 0.0
        val r = (annualRatePct / 100.0) / 12.0
        if (r <= 0.0) return monthlyEmi * tenureMonths
        val factor = (1.0 + r).pow(tenureMonths)
        return monthlyEmi * ((factor - 1.0) / (r * factor))
    }

    fun emiForPrincipal(principal: Double, annualRatePct: Double, tenureMonths: Int): Double {
        if (principal <= 0.0 || tenureMonths <= 0) return 0.0
        val r = (annualRatePct / 100.0) / 12.0
        if (r <= 0.0) return principal / tenureMonths
        val factor = (1.0 + r).pow(tenureMonths)
        return principal * r * factor / (factor - 1.0)
    }

    private fun roundToNearestTenThousand(amount: Double): Double {
        if (amount <= 0.0) return 0.0
        return ((amount / 10_000.0).roundToLong() * 10_000L).toDouble()
    }

    private fun formatRupee(amount: Double): String {
        val sign = if (amount < 0) "-" else ""
        val absVal = abs(amount).roundToLong()
        val s = absVal.toString()
        val formatted = if (s.length <= 3) s else {
            val last3 = s.takeLast(3)
            val rest = s.dropLast(3)
            val grouped = rest.reversed().chunked(2).joinToString(",").reversed()
            "$grouped,$last3"
        }
        return "${sign}₹$formatted"
    }

    private fun formatRupeeCompact(amount: Double): String {
        val absVal = abs(amount)
        val sign = if (amount < 0) "-" else ""
        return when {
            absVal >= 10_000_000.0 -> "${sign}₹%.2f Cr".format(java.util.Locale.ROOT, absVal / 10_000_000.0)
            absVal >= 100_000.0 -> "${sign}₹%.2f L".format(java.util.Locale.ROOT, absVal / 100_000.0)
            else -> formatRupee(amount)
        }
    }
}
