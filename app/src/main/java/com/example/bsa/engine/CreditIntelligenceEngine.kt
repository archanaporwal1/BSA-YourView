package com.example.bsa.engine

import com.example.bsa.model.*
import com.example.bsa.service.FraudScreeningService
import com.example.bsa.service.ScreeningRiskTier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

enum class UnderwritingVerdict(val label: String, val shortLabel: String) {
    STRONG_APPROVE("Strong Profile • Approve", "APPROVE"),
    CONDITIONAL_REVIEW("Conditional • Manual Review", "REVIEW"),
    HIGH_RISK_DECLINE("High Risk • Caution / Decline", "HIGH RISK")
}

enum class EligibilityStatus(val label: String) {
    ELIGIBLE("Eligible"),
    CONDITIONAL("Conditional"),
    INELIGIBLE("Ineligible / Refer")
}

enum class BorrowerProfileType(val label: String) {
    SALARIED("Salaried Individual"),
    SELF_EMPLOYED_BUSINESS("Self-Employed / Business")
}

data class SegmentEligibilityResult(
    val productType: ProductType,
    val programName: String,
    val status: EligibilityStatus,
    val conservativeAmount: Double,
    val recommendedAmount: Double,
    val maxEligibleAmount: Double,
    val maxMonthlyServicing: Double,       // Max incremental EMI (or monthly interest for WC OD/CC)
    val isRevolvingFacility: Boolean,      // True for Working Capital (OD/CC)
    val benchmarkRatePct: Double,
    val benchmarkTenureMonths: Int,
    val maxAllowedFoirPct: Double,
    val currentFoirPct: Double,
    val existingFacilityGap: Double? = null, // For WC on existing OD/CC: incremental top-up headroom
    val limitingFactor: String,
    val breakdownRows: List<Pair<String, String>>,
    val policyNotes: List<String>
)

data class CreditIntelligenceSummary(
    val creditHealthScore: Int,               // 0..100 (higher = stronger borrower)
    val verdict: UnderwritingVerdict,
    val borrowerProfile: BorrowerProfileType,
    val avgMonthlySalaryCredits: Double,
    val avgMonthlyBusinessCredits: Double,
    val estimatedNetMonthlyIncome: Double,    // Salary (if salaried) or MSME net margin proxy
    val avgMonthlyNetCashFlow: Double,
    val avgBankBalanceAbb: Double,            // Standard 2nd/10th/20th/End checkpoint ABB
    val avgMonthlyEmiObligation: Double,
    val foirPercent: Double,                  // Fixed Obligation to Income Ratio %
    val cashDependencyPercent: Double,        // Cash volume / Total turnover %
    val bounceAndReturnCount: Int,            // Cheque / NACH / ECS bounces & return charges
    val maxAdditionalEmiAt50Foir: Double,     // Safe incremental EMI headroom
    val estimatedEligibleLoan: Double,        // Primary selected segment recommended eligibility
    val benchmarkRatePct: Double,
    val benchmarkTenureMonths: Int,
    val segmentEligibilities: List<SegmentEligibilityResult>,
    val primarySegmentEligibility: SegmentEligibilityResult,
    val strengths: List<String>,
    val watchOuts: List<String>
)

/**
 * Smart Indian Banking Underwriting & Multi-Segment Credit Intelligence Engine (100% Deterministic, Offline-First).
 * Computes distinct Indian bank/NBFC eligibility programs across all 4 segments:
 *   1. Auto Loan       — Banking Surrogate (ABB coverage) + FOIR/Income Cap (60m @ 9.50%)
 *   2. Business Loan   — Banking Turnover (10–15% BTO) + ABB Surrogate (4–6x ABB) + Debt-Service Cap (36m @ 14.50%)
 *   3. Working Capital — RBI Nayak Committee / Turnover Method (20% of Annualized BTO) + OD/CC Utilization & Top-Up Check (11.50% revolving)
 *   4. Home Loan       — Long-Tenure FOIR (55–60% Net Income) + Self-Employed Banking/ABB Surrogate (240m @ 8.75%)
 */
object CreditIntelligenceEngine {

    private val BOUNCE_RETURN_REGEX = Regex(
        """\b(BOUNCE|INSUFFICIENT\s*FUND|DISHONOUR|DISHONOR|CHQ\s*RET|CHQ\s*RTN|I/W\s*CHQ\s*RET|ECS\s*RET|ECS\s*RTN|NACH\s*RET|NACH\s*RTN|ACH\s*RET|ACH\s*RTN|RETURN\s*CHG|BOUNCE\s*CHG|MIN\s*BAL\s*CHG|AMB\s*CHG|PENAL\s*CHG)\b""",
        RegexOption.IGNORE_CASE
    )

    fun evaluate(report: AnalysisReport): CreditIntelligenceSummary {
        val months = report.monthlyMetrics
        val monthCount = months.size.coerceAtLeast(1)

        val avgBusinessCredits = if (months.isNotEmpty()) {
            months.sumOf { it.businessCredits } / monthCount
        } else 0.0

        val avgTotalCredits = if (months.isNotEmpty()) {
            months.sumOf { it.totalCredits } / monthCount
        } else 0.0

        val effectiveMonthlyTurnover = if (avgBusinessCredits > 0.0) avgBusinessCredits else avgTotalCredits

        val salaryCredits = report.transactions.filter { it.amount > 0 && it.category == "Salary / Payroll" }
        val avgMonthlySalary = salaryCredits.sumOf { it.amount } / monthCount

        // Classify borrower profile automatically from statement behaviour
        val isSalaried = report.config.facilityType == FacilityType.NONE &&
            avgMonthlySalary >= 15000.0 &&
            (effectiveMonthlyTurnover <= 0.0 || (avgMonthlySalary / effectiveMonthlyTurnover) >= 0.45)

        val borrowerProfile = if (isSalaried) {
            BorrowerProfileType.SALARIED
        } else {
            BorrowerProfileType.SELF_EMPLOYED_BUSINESS
        }

        // Standard Indian Banking Net Monthly Income proxy:
        // - Salaried: actual average monthly salary credits
        // - Business/Self-Employed: conservative 18% net margin on non-self business credits
        val estimatedNetMonthlyIncome = if (isSalaried) {
            avgMonthlySalary
        } else {
            effectiveMonthlyTurnover * 0.18
        }

        val avgNetCashFlow = if (months.isNotEmpty()) {
            months.sumOf { it.netCashFlow } / monthCount
        } else 0.0

        // Standard Indian Banking ABB: average of 2nd, 10th, 20th & Month-End checkpoints
        val avgBankBalanceAbb = if (months.isNotEmpty()) {
            months.map { m ->
                (m.balAsOn2nd + m.balAsOn10th + m.balAsOn20th + m.balAsOnMonthEnd) / 4.0
            }.average()
        } else 0.0

        val monthlyEmiFromMetrics = if (months.isNotEmpty()) {
            months.sumOf { it.emiLoanOutflow } / monthCount
        } else 0.0
        val monthlyEmiFromTrack = report.loanTrack.sumOf { it.emiAmount }
        val avgMonthlyEmi = maxOf(monthlyEmiFromMetrics, monthlyEmiFromTrack)

        val avgMonthlyOdInterest = if (months.isNotEmpty()) {
            months.sumOf { it.odInterestServicing } / monthCount
        } else 0.0

        val fixedObligations = avgMonthlyEmi + avgMonthlyOdInterest

        // Display FOIR against appropriate income base:
        // For salaried -> fixedObligations / salary; for business -> fixedObligations / effectiveMonthlyTurnover
        // (keeps business FOIR aligned with statement inflow while segment calculators apply net-margin & BTO norms)
        val foirDenominator = if (isSalaried) avgMonthlySalary else effectiveMonthlyTurnover
        val foirPct = if (foirDenominator > 0.0) {
            ((fixedObligations / foirDenominator) * 100.0).coerceIn(0.0, 200.0)
        } else 0.0

        val totalCashVolume = months.sumOf { it.cashDeposits + it.cashWithdrawals }
        val totalTurnover = (report.totalInflow + report.totalOutflow).coerceAtLeast(0.0)
        val cashDepPct = if (totalTurnover > 0.0) {
            ((totalCashVolume / totalTurnover) * 100.0).coerceIn(0.0, 100.0)
        } else 0.0

        val bounceCount = report.transactions.count { t ->
            BOUNCE_RETURN_REGEX.containsMatchIn(t.description) ||
                (t.category == "Reversal" && t.amount < 0)
        }

        // Compute deterministic 0..100 Credit Health Score
        val riskSummary = FraudScreeningService.computeRiskSummary(report.fraudIndicators)
        var score = 100
        score -= (riskSummary.fraudFlaggedCount * 15 + riskSummary.behaviouralFlaggedCount * 5)

        when (report.overallForensicStatus) {
            ForensicStatus.REJECTED -> score -= 28
            ForensicStatus.WARNING -> score -= 8
            ForensicStatus.VERIFIED -> Unit
        }

        val netIncomeFoirPct = if (estimatedNetMonthlyIncome > 0.0) {
            ((fixedObligations / estimatedNetMonthlyIncome) * 100.0).coerceIn(0.0, 200.0)
        } else 0.0

        when {
            foirPct > 65.0 -> score -= 20
            foirPct > 50.0 -> score -= 10
            foirPct > 40.0 -> score -= 4
        }

        when {
            bounceCount >= 3 -> score -= 22
            bounceCount in 1..2 -> score -= 10
        }

        when {
            cashDepPct > 45.0 -> score -= 12
            cashDepPct > 30.0 -> score -= 6
        }

        if (report.config.facilityType != FacilityType.NONE) {
            when {
                report.peakOdUtilizationPct >= 98.0 -> score -= 14
                report.peakOdUtilizationPct >= 88.0 -> score -= 7
            }
        } else if (report.averageMonthlyBalance < 0.0) {
            score -= 14
        }

        val finalScore = score.coerceIn(0, 100)

        val verdict = when {
            report.overallForensicStatus == ForensicStatus.REJECTED ||
                riskSummary.riskTier == ScreeningRiskTier.HIGH ||
                finalScore < 50 -> UnderwritingVerdict.HIGH_RISK_DECLINE
            finalScore >= 75 && riskSummary.fraudFlaggedCount == 0 && bounceCount <= 1 ->
                UnderwritingVerdict.STRONG_APPROVE
            else -> UnderwritingVerdict.CONDITIONAL_REVIEW
        }

        // Compute risk haircut factor (0.0 .. 1.0) applied consistently across all 4 segments
        val riskHaircut = computeRiskHaircut(
            forensicStatus = report.overallForensicStatus,
            fraudFlags = riskSummary.fraudFlaggedCount,
            bounceCount = bounceCount,
            cashDepPct = cashDepPct
        )

        val ctx = EligibilityCalculationContext(
            report = report,
            borrowerProfile = borrowerProfile,
            avgMonthlyTurnover = effectiveMonthlyTurnover,
            annualizedBto = effectiveMonthlyTurnover * 12.0,
            avgMonthlySalary = avgMonthlySalary,
            estimatedNetMonthlyIncome = estimatedNetMonthlyIncome,
            abb = avgBankBalanceAbb,
            existingMonthlyEmi = avgMonthlyEmi,
            existingMonthlyObligations = fixedObligations,
            cashDepPct = cashDepPct,
            bounceCount = bounceCount,
            fraudFlagCount = riskSummary.fraudFlaggedCount,
            creditHealthScore = finalScore,
            riskHaircutMultiplier = riskHaircut.multiplier,
            haircutPercent = riskHaircut.haircutPct,
            haircutReason = riskHaircut.reason
        )

        val allSegments = listOf(
            calculateAutoLoanEligibility(ctx),
            calculateBusinessLoanEligibility(ctx),
            calculateWorkingCapitalEligibility(ctx),
            calculateHomeLoanEligibility(ctx)
        )

        val primarySegment = allSegments.firstOrNull { it.productType == report.config.productType }
            ?: allSegments.first()

        val maxAdditionalEmi = ((foirDenominator * 0.50) - fixedObligations).coerceAtLeast(0.0)

        val strengths = mutableListOf<String>()
        val watchOuts = mutableListOf<String>()

        if (report.overallForensicStatus == ForensicStatus.VERIFIED) {
            strengths.add("Authentic bank ePDF verified (${report.fileAudits.size} file${if (report.fileAudits.size == 1) "" else "s"} passed forensic check).")
        } else {
            watchOuts.add("Forensic ePDF status is ${report.overallForensicStatus.label} — obtain original bank-generated eStatement.")
        }

        if (bounceCount == 0) {
            strengths.add("Zero cheque/ECS/NACH bounces or insufficient-fund returns observed.")
        } else {
            watchOuts.add("$bounceCount cheque/NACH/ECS bounce or return charge event(s) detected.")
        }

        if (foirDenominator > 0.0) {
            if (foirPct <= 45.0) {
                strengths.add("Healthy obligation load (${formatPct(foirPct)} of inflow) leaves comfortable servicing headroom.")
            } else if (foirPct > 55.0) {
                watchOuts.add("Elevated existing EMI/interest burden (${formatPct(foirPct)} of monthly inflow).")
            }
        }

        if (cashDepPct <= 20.0) {
            strengths.add("High banking transparency with low cash dependency (${formatPct(cashDepPct)}).")
        } else if (cashDepPct > 35.0) {
            watchOuts.add("High cash volume (${formatPct(cashDepPct)} of turnover) — verify cash business source.")
        }

        if (report.config.facilityType != FacilityType.NONE) {
            if (report.peakOdUtilizationPct >= 92.0) {
                watchOuts.add("Peak ${report.config.facilityType.code} utilization touched ${formatPct(report.peakOdUtilizationPct)} (near limit ceiling).")
            } else if (report.peakOdUtilizationPct > 0.0) {
                strengths.add("Disciplined ${report.config.facilityType.code} peak utilization at ${formatPct(report.peakOdUtilizationPct)}.")
            }
        } else if (avgBankBalanceAbb > 0.0) {
            strengths.add("Positive checkpoint Average Bank Balance (ABB) of ₹${formatInr(avgBankBalanceAbb)}.")
        }

        if (riskSummary.fraudFlaggedCount > 0) {
            watchOuts.add("${riskSummary.fraudFlaggedCount} primary fraud indicator(s) triggered — inspect Fraud (10) tab.")
        }

        if (netIncomeFoirPct > 65.0 && !isSalaried && effectiveMonthlyTurnover > 0.0 && watchOuts.size < 4) {
            watchOuts.add("Existing EMIs absorb ${formatPct(netIncomeFoirPct)} of estimated net business margin — review ITR/GST.")
        }

        return CreditIntelligenceSummary(
            creditHealthScore = finalScore,
            verdict = verdict,
            borrowerProfile = borrowerProfile,
            avgMonthlySalaryCredits = avgMonthlySalary,
            avgMonthlyBusinessCredits = avgBusinessCredits,
            estimatedNetMonthlyIncome = estimatedNetMonthlyIncome,
            avgMonthlyNetCashFlow = avgNetCashFlow,
            avgBankBalanceAbb = avgBankBalanceAbb,
            avgMonthlyEmiObligation = avgMonthlyEmi,
            foirPercent = foirPct,
            cashDependencyPercent = cashDepPct,
            bounceAndReturnCount = bounceCount,
            maxAdditionalEmiAt50Foir = maxAdditionalEmi,
            estimatedEligibleLoan = primarySegment.recommendedAmount,
            benchmarkRatePct = primarySegment.benchmarkRatePct,
            benchmarkTenureMonths = primarySegment.benchmarkTenureMonths,
            segmentEligibilities = allSegments,
            primarySegmentEligibility = primarySegment,
            strengths = strengths.take(4),
            watchOuts = watchOuts.take(4)
        )
    }

    private data class RiskHaircut(
        val multiplier: Double,
        val haircutPct: Int,
        val reason: String
    )

    private data class EligibilityCalculationContext(
        val report: AnalysisReport,
        val borrowerProfile: BorrowerProfileType,
        val avgMonthlyTurnover: Double,
        val annualizedBto: Double,
        val avgMonthlySalary: Double,
        val estimatedNetMonthlyIncome: Double,
        val abb: Double,
        val existingMonthlyEmi: Double,
        val existingMonthlyObligations: Double,
        val cashDepPct: Double,
        val bounceCount: Int,
        val fraudFlagCount: Int,
        val creditHealthScore: Int,
        val riskHaircutMultiplier: Double,
        val haircutPercent: Int,
        val haircutReason: String
    )

    private fun computeRiskHaircut(
        forensicStatus: ForensicStatus,
        fraudFlags: Int,
        bounceCount: Int,
        cashDepPct: Double
    ): RiskHaircut {
        if (forensicStatus == ForensicStatus.REJECTED) {
            return RiskHaircut(0.0, 100, "Tampered/scanned ePDF rejected by Forensic Gatekeeper")
        }
        var totalCut = 0
        val reasons = mutableListOf<String>()

        if (forensicStatus == ForensicStatus.WARNING) {
            totalCut += 10
            reasons.add("Forensic warning (-10%)")
        }
        when {
            fraudFlags >= 2 -> {
                totalCut += 40
                reasons.add("$fraudFlags fraud flags (-40%)")
            }
            fraudFlags == 1 -> {
                totalCut += 15
                reasons.add("1 fraud flag (-15%)")
            }
        }
        when {
            bounceCount >= 4 -> {
                totalCut += 45
                reasons.add("$bounceCount bounces (-45%)")
            }
            bounceCount in 2..3 -> {
                totalCut += 25
                reasons.add("$bounceCount bounces (-25%)")
            }
            bounceCount == 1 -> {
                totalCut += 10
                reasons.add("1 bounce (-10%)")
            }
        }
        if (cashDepPct > 40.0) {
            totalCut += 15
            reasons.add("Cash dep >40% (-15%)")
        }
        val cappedCut = totalCut.coerceIn(0, 75)
        return RiskHaircut(
            multiplier = (100 - cappedCut) / 100.0,
            haircutPct = cappedCut,
            reason = if (reasons.isEmpty()) "Clean profile (0% haircut)" else reasons.joinToString(", ")
        )
    }

    // =========================================================================
    // 1. AUTO LOAN ELIGIBILITY (ABB Banking Surrogate + 55% FOIR Income Cap)
    // =========================================================================
    private fun calculateAutoLoanEligibility(ctx: EligibilityCalculationContext): SegmentEligibilityResult {
        val rate = 9.50
        val tenureMonths = 60 // 5 years standard Indian auto loan
        val maxFoirPct = 55.0

        // Path A: Income / FOIR EMI Capacity
        val incomeBase = if (ctx.borrowerProfile == BorrowerProfileType.SALARIED) {
            ctx.avgMonthlySalary
        } else {
            // Self-employed banking-surrogate auto loans assess 22% net margin or steady inflow surplus
            ctx.avgMonthlyTurnover * 0.22
        }
        val foirMaxEmi = ((incomeBase * (maxFoirPct / 100.0)) - ctx.existingMonthlyEmi).coerceAtLeast(0.0)
        val foirLoanCap = calculateMaxLoanFromEmi(foirMaxEmi, rate, tenureMonths)

        // Path B: ABB Banking Surrogate (Indian Auto Loan norm: ABB >= 1.75x proposed EMI; loan cap 6x–8x ABB)
        val hasPositiveAbb = ctx.abb > 0.0 && ctx.report.config.facilityType == FacilityType.NONE
        val abbSurrogateEmi = if (hasPositiveAbb) (ctx.abb / 1.75) else 0.0
        val abbSurrogateLoan = if (hasPositiveAbb) {
            minOf(
                calculateMaxLoanFromEmi(abbSurrogateEmi, rate, tenureMonths),
                ctx.abb * 8.0
            )
        } else 0.0

        // Gross turnover safety ceiling (total EMI <= 25% of monthly banking credits)
        val turnoverSafeEmi = ((ctx.avgMonthlyTurnover * 0.25) - ctx.existingMonthlyEmi).coerceAtLeast(0.0)
        val turnoverSafeLoan = calculateMaxLoanFromEmi(turnoverSafeEmi, rate, tenureMonths)

        val rawConservative: Double
        val rawRecommended: Double
        val rawMax: Double
        val limitingFactor: String

        if (ctx.borrowerProfile == BorrowerProfileType.SALARIED) {
            rawRecommended = foirLoanCap
            rawConservative = foirLoanCap * 0.85
            rawMax = calculateMaxLoanFromEmi(((incomeBase * 0.60) - ctx.existingMonthlyEmi).coerceAtLeast(0.0), rate, tenureMonths)
            limitingFactor = if (foirMaxEmi <= 0.0) "Existing EMIs exceed 55% FOIR cap" else "55% Net Salary FOIR (60m @ 9.50%)"
        } else if (hasPositiveAbb) {
            // Self-Employed Banking Surrogate: blends ABB surrogate with turnover servicing capacity
            val surrogateCapped = minOf(abbSurrogateLoan, turnoverSafeLoan)
            rawRecommended = maxOf(foirLoanCap, surrogateCapped * 0.85)
            rawConservative = minOf(foirLoanCap.takeIf { it > 0 } ?: (surrogateCapped * 0.70), surrogateCapped * 0.75)
            rawMax = maxOf(foirLoanCap, surrogateCapped)
            limitingFactor = when {
                surrogateCapped <= 0.0 && foirLoanCap <= 0.0 -> "Insufficient ABB & servicing surplus"
                abbSurrogateLoan < turnoverSafeLoan -> "ABB Surrogate (ABB ÷ 1.75 EMI coverage)"
                else -> "Banking Turnover EMI Cap (25% monthly inflow)"
            }
        } else {
            // OD/CC account (ABB negative by design) -> rely on BTO & net margin servicing
            rawRecommended = minOf(foirLoanCap.takeIf { it > 0 } ?: (turnoverSafeLoan * 0.65), turnoverSafeLoan)
            rawConservative = rawRecommended * 0.80
            rawMax = turnoverSafeLoan * 0.85
            limitingFactor = "OD/CC Account — Sized via Business Margin & Turnover FOIR"
        }

        val finalConservative = roundToTenThousand(rawConservative * ctx.riskHaircutMultiplier)
        val finalRecommended = roundToTenThousand(rawRecommended * ctx.riskHaircutMultiplier)
        val finalMax = roundToTenThousand(maxOf(rawMax, rawRecommended) * ctx.riskHaircutMultiplier)
        val maxServicingEmi = calculateMonthlyEmi(finalRecommended, rate, tenureMonths)
        val currentSegmentFoir = if (incomeBase > 0.0) ((ctx.existingMonthlyEmi / incomeBase) * 100.0).coerceIn(0.0, 200.0) else 0.0

        val status = resolveEligibilityStatus(ctx, finalRecommended, minViableLoan = 100000.0)

        val breakdown = buildList {
            add("Borrower Track" to ctx.borrowerProfile.label)
            add("Assessed Monthly Base" to "₹${formatInr(incomeBase)}/mo")
            add("Existing Monthly EMIs" to "₹${formatInr(ctx.existingMonthlyEmi)}/mo")
            if (hasPositiveAbb) {
                add("ABB Surrogate (ABB ÷ 1.75 EMI)" to "₹${formatInr(abbSurrogateLoan)}")
            }
            add("FOIR Cap (55%, 60m @ 9.50%)" to "₹${formatInr(foirLoanCap)}")
            add("Risk / Bounce Haircut" to "${ctx.haircutPercent}% (${ctx.haircutReason})")
            add("Recommended Auto Loan" to "₹${formatInr(finalRecommended)}")
        }

        val notes = buildList {
            add("Typically financed up to 85%–90% of On-Road / Ex-Showroom vehicle price (LTV).")
            if (hasPositiveAbb) add("Banking surrogate requires ABB ≥ 1.75× proposed EMI over last 6 months.")
            if (ctx.bounceCount > 0) add("Auto financiers strictly scrutinize inward ECS/NACH bounces in last 6 months.")
        }

        return SegmentEligibilityResult(
            productType = ProductType.AUTO_LOAN,
            programName = if (hasPositiveAbb) "ABB Banking Surrogate + FOIR" else "Income / Margin FOIR Program",
            status = status,
            conservativeAmount = finalConservative,
            recommendedAmount = finalRecommended,
            maxEligibleAmount = finalMax,
            maxMonthlyServicing = maxServicingEmi,
            isRevolvingFacility = false,
            benchmarkRatePct = rate,
            benchmarkTenureMonths = tenureMonths,
            maxAllowedFoirPct = maxFoirPct,
            currentFoirPct = currentSegmentFoir,
            limitingFactor = limitingFactor,
            breakdownRows = breakdown,
            policyNotes = notes
        )
    }

    // =========================================================================
    // 2. BUSINESS LOAN — UNSECURED BL (10–15% Annual BTO + 4–6x ABB Surrogate)
    // =========================================================================
    private fun calculateBusinessLoanEligibility(ctx: EligibilityCalculationContext): SegmentEligibilityResult {
        val rate = 14.50
        val tenureMonths = 36 // 3 years standard Indian Unsecured BL
        val maxFoirPct = 50.0

        // Program 1: Banking Turnover (BTO) Method — 10% (conservative) to 15% (max) of Annualized Non-Self Credits
        val btoConservative = ctx.annualizedBto * 0.10
        val btoStandard = ctx.annualizedBto * 0.12
        val btoMax = ctx.annualizedBto * 0.15

        // Program 2: ABB Surrogate Program — 4x to 6x Checkpoint ABB (when non-OD & positive ABB)
        val hasPositiveAbb = ctx.abb > 0.0 && ctx.report.config.facilityType == FacilityType.NONE
        val abbProgramStandard = if (hasPositiveAbb) ctx.abb * 4.5 else 0.0
        val abbProgramMax = if (hasPositiveAbb) ctx.abb * 6.0 else 0.0

        // Program 3: Debt-Servicing Cap (Total EMI <= 20% of Gross Monthly Business Inflow or 50% of est. net margin)
        val servicingBase = maxOf(ctx.estimatedNetMonthlyIncome, ctx.avgMonthlyTurnover * 0.18)
        val maxTotalEmiAllowed = servicingBase * (maxFoirPct / 100.0)
        // Also ensure total EMI doesn't exceed 22% of gross monthly business credits
        val grossEmiCap = ctx.avgMonthlyTurnover * 0.22
        val effectiveIncrementalEmi = (maxOf(maxTotalEmiAllowed, grossEmiCap * 0.65) - ctx.existingMonthlyObligations).coerceAtLeast(0.0)
        val dscrLoanCap = calculateMaxLoanFromEmi(effectiveIncrementalEmi, rate, tenureMonths)

        val rawConservative: Double
        val rawRecommended: Double
        val rawMax: Double
        val limitingFactor: String

        if (ctx.annualizedBto <= 0.0) {
            rawConservative = 0.0
            rawRecommended = 0.0
            rawMax = 0.0
            limitingFactor = "No verifiable business banking turnover"
        } else if (hasPositiveAbb) {
            val blendedStandard = minOf(maxOf(btoStandard, abbProgramStandard), dscrLoanCap.coerceAtLeast(btoConservative))
            val blendedMax = minOf(maxOf(btoMax, abbProgramMax), dscrLoanCap.coerceAtLeast(btoStandard))
            rawRecommended = minOf(blendedStandard, dscrLoanCap)
            rawConservative = minOf(btoConservative, ctx.abb * 3.5, dscrLoanCap)
            rawMax = minOf(blendedMax, dscrLoanCap * 1.15)
            limitingFactor = when {
                dscrLoanCap < btoStandard && dscrLoanCap < abbProgramStandard -> "Existing EMI / Interest Obligation Load"
                btoStandard <= abbProgramStandard -> "12% Annual Banking Turnover (BTO) Norm"
                else -> "4.5× Checkpoint ABB Surrogate Norm"
            }
        } else {
            // OD/CC account -> evaluate via BTO + Debt Servicing Capacity
            rawConservative = minOf(btoConservative, dscrLoanCap)
            rawRecommended = minOf(btoStandard, dscrLoanCap)
            rawMax = minOf(btoMax, dscrLoanCap * 1.15)
            limitingFactor = if (dscrLoanCap < btoStandard) {
                "Existing EMI & OD Interest Servicing Load"
            } else {
                "12% Annual Banking Turnover (BTO) Norm"
            }
        }

        // Unsecured BL requires extra caution on bounces (>=3 bounces usually rejected by NBFCs)
        val blBounceExtraPenalty = if (ctx.bounceCount >= 3) 0.5 else 1.0
        val finalConservative = roundToTenThousand(rawConservative * ctx.riskHaircutMultiplier * blBounceExtraPenalty)
        val finalRecommended = roundToTenThousand(rawRecommended * ctx.riskHaircutMultiplier * blBounceExtraPenalty)
        val finalMax = roundToTenThousand(maxOf(rawMax, rawRecommended) * ctx.riskHaircutMultiplier * blBounceExtraPenalty)
        val maxServicingEmi = calculateMonthlyEmi(finalRecommended, rate, tenureMonths)
        val currentSegmentFoir = if (servicingBase > 0.0) ((ctx.existingMonthlyObligations / servicingBase) * 100.0).coerceIn(0.0, 200.0) else 0.0

        val status = when {
            ctx.bounceCount >= 3 -> EligibilityStatus.INELIGIBLE
            else -> resolveEligibilityStatus(ctx, finalRecommended, minViableLoan = 100000.0)
        }

        val breakdown = buildList {
            add("Annualized Business Credits (BTO)" to "₹${formatInr(ctx.annualizedBto)}/yr")
            add("BTO Program (10%–15% BTO)" to "₹${formatInr(btoConservative)} – ₹${formatInr(btoMax)}")
            if (hasPositiveAbb) {
                add("ABB Surrogate (4.5×–6× ABB)" to "₹${formatInr(abbProgramStandard)} – ₹${formatInr(abbProgramMax)}")
            }
            add("Debt-Servicing Cap (36m @ 14.5%)" to "₹${formatInr(dscrLoanCap)}")
            add("Risk / Cash / Bounce Haircut" to "${ctx.haircutPercent}% (${ctx.haircutReason})")
            add("Recommended Unsecured BL" to "₹${formatInr(finalRecommended)}")
        }

        val notes = buildList {
            add("Unsecured MSME Business Loans (36m @ ~14.5% p.a.) typically cap at 10%–15% of banking turnover.")
            if (ctx.cashDepPct > 30.0) add("Cash turnover (${formatPct(ctx.cashDepPct)}) attracts higher scrutiny — GST/ITR corroboration advised.")
            if (ctx.bounceCount >= 3) add("3+ inward bounces typically trigger automatic rejection under unsecured BL policy.")
        }

        return SegmentEligibilityResult(
            productType = ProductType.BUSINESS_LOAN,
            programName = if (hasPositiveAbb) "BTO (12%) + ABB Surrogate" else "Annual BTO (12%) + DSCR",
            status = status,
            conservativeAmount = finalConservative,
            recommendedAmount = finalRecommended,
            maxEligibleAmount = finalMax,
            maxMonthlyServicing = maxServicingEmi,
            isRevolvingFacility = false,
            benchmarkRatePct = rate,
            benchmarkTenureMonths = tenureMonths,
            maxAllowedFoirPct = maxFoirPct,
            currentFoirPct = currentSegmentFoir,
            limitingFactor = limitingFactor,
            breakdownRows = breakdown,
            policyNotes = notes
        )
    }

    // =========================================================================
    // 3. WORKING CAPITAL — OD / CC LIMIT (RBI Nayak Committee 20% BTO Method)
    // =========================================================================
    private fun calculateWorkingCapitalEligibility(ctx: EligibilityCalculationContext): SegmentEligibilityResult {
        val rate = 11.50
        val tenureMonths = 12 // Annual renewal revolving OD/CC facility
        val maxFoirPct = 50.0

        // RBI Nayak Committee / Turnover Method:
        // Total Working Capital Req = 25% of Annual BTO; Bank MPBF (OD/CC Limit) = 20% of Annual BTO (5% borrower NWC)
        val rawConservative = ctx.annualizedBto * 0.15 // 15% conservative BTO
        val rawRecommended = ctx.annualizedBto * 0.20  // 20% RBI Nayak Committee norm
        val rawMax = ctx.annualizedBto * 0.25          // 25% peak seasonal/collateralized norm

        // Peak OD utilization discipline penalty if already over-drawing > 95%
        val existingLimit = if (ctx.report.config.facilityType != FacilityType.NONE) {
            ctx.report.config.sanctionedOdLimit.coerceAtLeast(0.0)
        } else 0.0

        val odDisciplineMultiplier = when {
            existingLimit > 0.0 && ctx.report.peakOdUtilizationPct >= 98.0 -> 0.85
            existingLimit > 0.0 && ctx.report.peakOdUtilizationPct >= 92.0 -> 0.92
            else -> 1.0
        }

        val finalConservative = roundToTenThousand(rawConservative * ctx.riskHaircutMultiplier * odDisciplineMultiplier)
        val finalRecommended = roundToTenThousand(rawRecommended * ctx.riskHaircutMultiplier * odDisciplineMultiplier)
        val finalMax = roundToTenThousand(rawMax * ctx.riskHaircutMultiplier * odDisciplineMultiplier)

        val incrementalEnhancement = if (existingLimit > 0.0) {
            (finalRecommended - existingLimit).coerceAtLeast(0.0)
        } else null

        // Monthly interest-only servicing on recommended OD/CC limit @ 11.50% p.a. (assuming 80% average utilization)
        val estMonthlyInterest = (finalRecommended * 0.80) * (rate / 100.0) / 12.0

        val limitingFactor = when {
            ctx.annualizedBto <= 0.0 -> "No business credit summation detected"
            existingLimit > 0.0 && finalRecommended <= existingLimit ->
                "Existing ${ctx.report.config.facilityType.code} Limit (₹${formatInr(existingLimit)}) already meets 20% BTO norm"
            existingLimit > 0.0 && ctx.report.peakOdUtilizationPct >= 95.0 ->
                "Peak OD Utilization (${formatPct(ctx.report.peakOdUtilizationPct)}) triggered -15% discipline haircut"
            else -> "RBI Nayak Committee Norm (20% of Annualized BTO)"
        }

        val status = when {
            ctx.report.overallForensicStatus == ForensicStatus.REJECTED -> EligibilityStatus.INELIGIBLE
            existingLimit > 0.0 && ctx.report.peakOdUtilizationPct >= 98.0 -> EligibilityStatus.CONDITIONAL
            else -> resolveEligibilityStatus(ctx, finalRecommended, minViableLoan = 200000.0)
        }

        val creditSummationRatio = if (existingLimit > 0.0) ctx.annualizedBto / existingLimit else 5.0

        val breakdown = buildList {
            add("Annualized Non-Self Credits (BTO)" to "₹${formatInr(ctx.annualizedBto)}/yr")
            add("RBI Nayak Norm (20% of BTO)" to "₹${formatInr(rawRecommended)}")
            add("Conservative – Peak Band (15%–25%)" to "₹${formatInr(finalConservative)} – ₹${formatInr(finalMax)}")
            if (existingLimit > 0.0) {
                add("Existing Sanctioned ${ctx.report.config.facilityType.code} Limit" to "₹${formatInr(existingLimit)}")
                add("Credit Summation / Limit Ratio" to String.format(Locale.ROOT, "%.2fx (Norm ≥ 4.0x)", creditSummationRatio))
                add("Incremental Enhancement Potential" to "₹${formatInr(incrementalEnhancement ?: 0.0)}")
            }
            add("Est. Monthly Interest (@ 80% Util)" to "₹${formatInr(estMonthlyInterest)}/mo")
            add("Risk / Discipline Haircut" to "${ctx.haircutPercent}% (${ctx.haircutReason})")
        }

        val notes = buildList {
            add("Based on RBI Turnover Method: Bank WC Finance = 20% of Annualized Business Turnover (5% borrower margin).")
            if (existingLimit > 0.0 && creditSummationRatio < 4.0) {
                add("Credit summation (${String.format(Locale.ROOT, "%.1fx", creditSummationRatio)}) is below 4× of existing limit — verify routing of full sales.")
            }
            add("Subject to Drawing Power (DP) backed by Stock + Receivables (<90 days) & collateral norms.")
        }

        return SegmentEligibilityResult(
            productType = ProductType.WORKING_CAPITAL,
            programName = "RBI Turnover Method (20% BTO)",
            status = status,
            conservativeAmount = finalConservative,
            recommendedAmount = finalRecommended,
            maxEligibleAmount = finalMax,
            maxMonthlyServicing = estMonthlyInterest,
            isRevolvingFacility = true,
            benchmarkRatePct = rate,
            benchmarkTenureMonths = tenureMonths,
            maxAllowedFoirPct = maxFoirPct,
            currentFoirPct = ctx.report.peakOdUtilizationPct,
            existingFacilityGap = incrementalEnhancement,
            limitingFactor = limitingFactor,
            breakdownRows = breakdown,
            policyNotes = notes
        )
    }

    // =========================================================================
    // 4. HOME LOAN ELIGIBILITY (20-Yr FOIR 55–60% + Self-Employed ABB Surrogate)
    // =========================================================================
    private fun calculateHomeLoanEligibility(ctx: EligibilityCalculationContext): SegmentEligibilityResult {
        val rate = 8.75
        val tenureMonths = 240 // 20 years standard Indian Home Loan

        val isSalaried = ctx.borrowerProfile == BorrowerProfileType.SALARIED
        val netMonthlyIncome = ctx.estimatedNetMonthlyIncome
        val maxFoirPct = if (netMonthlyIncome >= 100000.0) 60.0 else 55.0

        // Path A: Standard Long-Tenure FOIR Program
        val foirMaxEmi = ((netMonthlyIncome * (maxFoirPct / 100.0)) - ctx.existingMonthlyEmi).coerceAtLeast(0.0)
        val foirLoan = calculateMaxLoanFromEmi(foirMaxEmi, rate, tenureMonths)

        // Path B: Self-Employed Banking / ABB Surrogate Program (HFC Liquid Income / Banking Program)
        // HFCs allow total EMI up to 0.75x–1.0x ABB (for steady savings/current balances), capped by 25% of gross turnover
        val hasPositiveAbb = ctx.abb > 0.0 && ctx.report.config.facilityType == FacilityType.NONE
        val abbSurrogateEmi = if (hasPositiveAbb) {
            minOf(ctx.abb * 0.75, ctx.avgMonthlyTurnover * 0.25) - ctx.existingMonthlyEmi
        } else 0.0
        val abbSurrogateLoan = calculateMaxLoanFromEmi(abbSurrogateEmi.coerceAtLeast(0.0), rate, tenureMonths)

        // Multiplier sanity cap: 60x–72x Net Monthly Income (standard Indian HFC ceiling)
        val incomeMultiplierCap = netMonthlyIncome * 72.0

        val rawConservative: Double
        val rawRecommended: Double
        val rawMax: Double
        val limitingFactor: String

        if (isSalaried) {
            val cappedFoir = minOf(foirLoan, incomeMultiplierCap)
            rawRecommended = cappedFoir
            rawConservative = cappedFoir * 0.85
            rawMax = minOf(calculateMaxLoanFromEmi(((netMonthlyIncome * 0.65) - ctx.existingMonthlyEmi).coerceAtLeast(0.0), rate, tenureMonths), incomeMultiplierCap * 1.1)
            limitingFactor = if (foirMaxEmi <= 0.0) {
                "Existing EMIs exhaust ${maxFoirPct.toInt()}% Net Salary FOIR"
            } else {
                "${maxFoirPct.toInt()}% Net Salary FOIR (20 yrs @ 8.75%)"
            }
        } else if (hasPositiveAbb) {
            val upperSurrogate = maxOf(foirLoan, abbSurrogateLoan)
            rawConservative = minOf(foirLoan.takeIf { it > 0 } ?: (abbSurrogateLoan * 0.75), upperSurrogate * 0.80)
            rawRecommended = (foirLoan * 0.45 + upperSurrogate * 0.55).coerceAtLeast(foirLoan)
            rawMax = upperSurrogate
            limitingFactor = when {
                upperSurrogate <= 0.0 -> "Insufficient Net Margin & ABB for Home Loan EMI"
                abbSurrogateLoan > foirLoan -> "Self-Employed Banking / ABB Surrogate (20 yrs @ 8.75%)"
                else -> "${maxFoirPct.toInt()}% Est. Net Margin FOIR (20 yrs @ 8.75%)"
            }
        } else {
            rawConservative = foirLoan * 0.85
            rawRecommended = foirLoan
            rawMax = calculateMaxLoanFromEmi(((netMonthlyIncome * 0.65) - ctx.existingMonthlyEmi).coerceAtLeast(0.0), rate, tenureMonths)
            limitingFactor = "${maxFoirPct.toInt()}% Est. Net Business Margin FOIR (20 yrs @ 8.75%)"
        }

        val finalConservative = roundToTenThousand(rawConservative * ctx.riskHaircutMultiplier)
        val finalRecommended = roundToTenThousand(rawRecommended * ctx.riskHaircutMultiplier)
        val finalMax = roundToTenThousand(maxOf(rawMax, rawRecommended) * ctx.riskHaircutMultiplier)
        val maxServicingEmi = calculateMonthlyEmi(finalRecommended, rate, tenureMonths)
        val currentSegmentFoir = if (netMonthlyIncome > 0.0) ((ctx.existingMonthlyEmi / netMonthlyIncome) * 100.0).coerceIn(0.0, 200.0) else 0.0

        val status = resolveEligibilityStatus(ctx, finalRecommended, minViableLoan = 300000.0)

        val breakdown = buildList {
            add("Borrower Track" to ctx.borrowerProfile.label)
            add(
                (if (isSalaried) "Avg Monthly Net Salary" else "Est. Net Monthly Income (18% BTO)") to
                    "₹${formatInr(netMonthlyIncome)}/mo"
            )
            add("Existing Monthly EMIs" to "₹${formatInr(ctx.existingMonthlyEmi)}/mo")
            add("FOIR Cap (${maxFoirPct.toInt()}%, 240m @ 8.75%)" to "₹${formatInr(foirLoan)}")
            if (!isSalaried && hasPositiveAbb) {
                add("Banking / ABB Surrogate Cap" to "₹${formatInr(abbSurrogateLoan)}")
            }
            add("Risk / Bounce Haircut" to "${ctx.haircutPercent}% (${ctx.haircutReason})")
            add("Recommended Home Loan" to "₹${formatInr(finalRecommended)}")
        }

        val notes = buildList {
            add("Calculated at 8.75% p.a. over 20 years; final sanction subject to RBI LTV norms (75%–80% of property value).")
            if (!isSalaried) add("Self-employed applicants can combine Banking Surrogate with 2-year ITR/CA-certified financials to enhance eligibility.")
            add("Adding an earning co-applicant directly increases eligible FOIR income base.")
        }

        return SegmentEligibilityResult(
            productType = ProductType.HOME_LOAN,
            programName = if (isSalaried) "20-Yr Net Salary FOIR" else "20-Yr Margin FOIR + ABB Surrogate",
            status = status,
            conservativeAmount = finalConservative,
            recommendedAmount = finalRecommended,
            maxEligibleAmount = finalMax,
            maxMonthlyServicing = maxServicingEmi,
            isRevolvingFacility = false,
            benchmarkRatePct = rate,
            benchmarkTenureMonths = tenureMonths,
            maxAllowedFoirPct = maxFoirPct,
            currentFoirPct = currentSegmentFoir,
            limitingFactor = limitingFactor,
            breakdownRows = breakdown,
            policyNotes = notes
        )
    }

    private fun resolveEligibilityStatus(
        ctx: EligibilityCalculationContext,
        recommendedAmount: Double,
        minViableLoan: Double
    ): EligibilityStatus {
        return when {
            ctx.report.overallForensicStatus == ForensicStatus.REJECTED -> EligibilityStatus.INELIGIBLE
            recommendedAmount < minViableLoan -> EligibilityStatus.INELIGIBLE
            ctx.fraudFlagCount >= 2 || ctx.creditHealthScore < 50 -> EligibilityStatus.INELIGIBLE
            ctx.fraudFlagCount == 1 || ctx.bounceCount >= 1 || ctx.creditHealthScore < 72 || ctx.cashDepPct > 35.0 ->
                EligibilityStatus.CONDITIONAL
            else -> EligibilityStatus.ELIGIBLE
        }
    }

    private fun roundToTenThousand(value: Double): Double {
        if (value <= 0.0 || value.isNaN() || value.isInfinite()) return 0.0
        return kotlin.math.floor(value / 10000.0) * 10000.0
    }

    /**
     * Standard reducing-balance Monthly EMI calculation.
     */
    fun calculateMonthlyEmi(principal: Double, annualRatePct: Double, tenureMonths: Int): Double {
        if (principal <= 0.0 || tenureMonths <= 0) return 0.0
        val monthlyRate = (annualRatePct / 12.0) / 100.0
        if (monthlyRate <= 0.0) return principal / tenureMonths
        val factor = (1.0 + monthlyRate).pow(tenureMonths.toDouble())
        val denom = factor - 1.0
        if (denom <= 0.0 || factor.isInfinite() || factor.isNaN()) return principal / tenureMonths
        return (principal * monthlyRate * factor) / denom
    }

    /**
     * Inverse EMI calculation: Maximum loan principal serviceable by a given monthly EMI budget.
     */
    fun calculateMaxLoanFromEmi(maxMonthlyEmi: Double, annualRatePct: Double, tenureMonths: Int): Double {
        if (maxMonthlyEmi <= 0.0 || tenureMonths <= 0) return 0.0
        val monthlyRate = (annualRatePct / 12.0) / 100.0
        if (monthlyRate <= 0.0) return maxMonthlyEmi * tenureMonths
        val factor = (1.0 + monthlyRate).pow(tenureMonths.toDouble())
        if (factor.isInfinite() || factor.isNaN() || factor <= 1.0) return 0.0
        return maxMonthlyEmi * (factor - 1.0) / (monthlyRate * factor)
    }

    /**
     * Generates a concise, executive WhatsApp/Email/LOS credit underwriting memo branded for YourView,
     * including all 4 segment eligibility bands (Auto Loan, Business Loan, Working Capital, Home Loan).
     */
    fun buildExecutiveBriefText(
        report: AnalysisReport,
        intel: CreditIntelligenceSummary = evaluate(report),
        camProfile: UnderwriterCamProfile? = null
    ): String {
        val risk = FraudScreeningService.computeRiskSummary(report.fraudIndicators)
        val deep = DeepUnderwritingAnalytics.analyze(report)
        val camEval = camProfile?.let { UnderwriterCamEvaluator.evaluate(report, intel, deep, it) }
        return buildString {
            appendLine("BSA-YourView | 360° CREDIT UNDERWRITING BRIEF")
            appendLine("Powered by YourView (yourview.in)")
            appendLine("----------------------------------------")
            appendLine("Applicant ID: ${report.config.applicantId}")
            appendLine("Holder: ${report.metadata.holderName} (${intel.borrowerProfile.label})")
            appendLine("Bank: ${report.metadata.bankName} | A/c: ${report.metadata.accountNumber}")
            appendLine("IFSC: ${report.metadata.ifscCode} | Facility: ${report.config.facilityType.label}")
            appendLine("Period: ${report.metadata.statementPeriod} (${report.transactions.size} txns)")
            appendLine()
            appendLine("VERDICT: ${intel.verdict.label} (Bank Score: ${intel.creditHealthScore}/100${camEval?.let { " | 360° CAM: ${it.compositeCamScore}/100" } ?: ""})")
            appendLine("Forensic ePDF: ${report.overallForensicStatus.label} | Risk Tier: ${risk.riskTier.label} (${risk.flaggedCount}/10 flags)")
            appendLine()
            appendLine("KEY BANKING & TRANSACTION METRICS:")
            appendLine("• Gross Credits: ₹${formatInr(report.totalInflow)} | Net True BTO: ₹${formatInr(deep.internalTransfers.netTrueBusinessCredits)}")
            appendLine("• Internal / Self Credits: ₹${formatInr(deep.internalTransfers.internalSelfCredits)} (Inflation: ${formatPct(deep.internalTransfers.creditInflationPercent)})")
            appendLine("• Total Debits: ₹${formatInr(report.totalOutflow)} | Digital Ratio: ${formatPct(deep.channelsAndCash.digitalBankingRatioPct)}")
            appendLine("• Avg Monthly Business Inflow: ₹${formatInr(intel.avgMonthlyBusinessCredits)}")
            appendLine("• Checkpoint ABB (2/10/20/End): ₹${formatInr(intel.avgBankBalanceAbb)}")
            appendLine("• Monthly EMI Track: ₹${formatInr(intel.avgMonthlyEmiObligation)} (${report.loanTrack.size} loan(s))")
            appendLine("• FOIR: ${formatPct(intel.foirPercent)} | Cash Dependency: ${formatPct(intel.cashDependencyPercent)}")
            appendLine("• Bounces / Returns: ${deep.bounces.totalBounceCount} (${formatPct(deep.bounces.bounceRatePercent)} rate | ${deep.bounces.emiTrackRating})")
            if (report.config.facilityType != FacilityType.NONE) {
                appendLine("• Peak ${report.config.facilityType.code} Utilization: ${formatPct(report.peakOdUtilizationPct)} (₹${formatInr(report.peakOdUtilizationAmt)} on ${report.peakOdUtilizationDate})")
            }
            appendLine()
            appendLine("4-SEGMENT LOAN ELIGIBILITY MATRIX:")
            intel.segmentEligibilities.forEach { seg ->
                val star = if (seg.productType == report.config.productType) " [SELECTED]" else ""
                appendLine("• ${seg.productType.displayName}$star: ₹${formatInr(seg.recommendedAmount)} (${seg.status.label} | ${seg.programName})")
            }
            if (camProfile != null && camEval != null) {
                appendLine()
                appendLine("BEYOND-BSA UNDERWRITER 360° CAM:")
                appendLine("• Bureau (${camProfile.bureauName}): ${camProfile.bureauScore} | 30+ DPD: ${camProfile.dpd30PlusCount12m} | 90+ DPD: ${camProfile.dpd90PlusCount}")
                if (camProfile.additionalBureauMonthlyEmi > 0.0) {
                    appendLine("• Consolidated FOIR (Bank + Bureau EMI): ${formatPct(camEval.consolidatedFoirPct)} (₹${formatInr(camEval.consolidatedMonthlyEmi)}/mo)")
                }
                camEval.bankToGstRoutingPct?.let { appendLine("• Bank-to-GST Routing Ratio: ${formatPct(it)}") }
                camEval.dscrRatio?.let { appendLine("• DSCR: ${String.format(Locale.ROOT, "%.2fx", it)}") }
                camEval.leverageTolTnwRatio?.let { appendLine("• Leverage (TOL/TNW): ${String.format(Locale.ROOT, "%.2fx", it)}") }
                camEval.currentRatio?.let { appendLine("• Current Ratio: ${String.format(Locale.ROOT, "%.2fx", it)}") }
                camEval.ltvPercent?.let { appendLine("• Collateral LTV (${camProfile.collateralType.label}): ${formatPct(it)}") }
                appendLine("• Decision: ${camProfile.decisionStatus.label} (${camEval.checklistCompletedCount}/${camEval.checklistTotalCount} checks)")
                if (camProfile.underwriterRemarks.isNotBlank()) {
                    appendLine("• Remarks: ${camProfile.underwriterRemarks.trim()}")
                }
            }
            if (intel.watchOuts.isNotEmpty()) {
                appendLine()
                appendLine("WATCH-OUTS:")
                intel.watchOuts.forEach { appendLine("⚠ $it") }
            }
            if (intel.strengths.isNotEmpty()) {
                appendLine()
                appendLine("STRENGTHS:")
                intel.strengths.forEach { appendLine("✓ $it") }
            }
        }.trim()
    }

    private fun formatInr(v: Double): String {
        val safe = if (v.isNaN() || v.isInfinite()) 0.0 else abs(v)
        val sign = if (v < -0.005) "-" else ""
        return "$sign${String.format(Locale.ROOT, "%,.0f", safe)}"
    }

    private fun formatPct(v: Double): String {
        val safe = if (v.isNaN() || v.isInfinite()) 0.0 else v
        return String.format(Locale.ROOT, "%.1f%%", safe)
    }
}
