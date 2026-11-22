package com.example.bsa.model

import android.content.Context
import com.example.bsa.engine.CreditIntelligenceSummary
import com.example.bsa.engine.DeepUnderwritingSummary
import org.json.JSONObject
import java.util.Locale

enum class CollateralType(val label: String, val maxNormLtvPct: Double) {
    UNSECURED("Unsecured / CGTMSE", 0.0),
    RESIDENTIAL_PROPERTY("Residential Property", 80.0),
    COMMERCIAL_PROPERTY("Commercial Property", 70.0),
    INDUSTRIAL_PROPERTY("Industrial / Plant", 65.0),
    VEHICLE_ASSET("Vehicle / Commercial Fleet", 85.0),
    FD_LIQUID_SECURITY("FD / Liquid Collateral", 90.0)
}

enum class CamDecisionStatus(val label: String, val shortLabel: String) {
    PENDING_REVIEW("Pending Underwriter Review", "PENDING"),
    RECOMMEND_SANCTION("Recommend Sanction", "SANCTION"),
    SANCTION_WITH_CONDITIONS("Sanction with Conditions", "CONDITIONAL"),
    ESCALATE_COMMITTEE("Escalate to Credit Committee", "ESCALATE"),
    DECLINE("Decline / Reject", "DECLINE")
}

/**
 * Beyond-Bank-Statement Credit Appraisal Memo (CAM) inputs captured by an Indian underwriter:
 * Bureau (CIBIL/CRIF), GST & ITR Financials, Working Capital Stock/Debtors DP, Collateral LTV,
 * and Field/PD/KYC Verification Checklist.
 */
data class UnderwriterCamProfile(
    // 1. Credit Bureau (CIBIL / CRIF / Experian)
    val bureauName: String = "CIBIL",
    val bureauScore: Int = 748,
    val activeTradelines: Int = 4,
    val overdueAmount: Double = 0.0,
    val dpd30PlusCount12m: Int = 0,
    val dpd90PlusCount: Int = 0,
    val writtenOffOrSettled: Boolean = false,
    val recentEnquiries90d: Int = 2,
    val additionalBureauMonthlyEmi: Double = 0.0, // EMIs visible in bureau but paid from another bank A/c

    // 2. GST & ITR Financials (Annual Figures in INR)
    val annualGstTurnover: Double = 0.0,
    val itrGrossTurnover: Double = 0.0,
    val itrNetProfitPat: Double = 0.0,
    val itrDepreciation: Double = 0.0,
    val itrInterestPaid: Double = 0.0,
    val tangibleNetWorth: Double = 0.0,
    val totalOutsideLiabilities: Double = 0.0,
    val currentAssets: Double = 0.0,
    val currentLiabilities: Double = 0.0,
    val stockInventoryValue: Double = 0.0,
    val debtorsUnder90Days: Double = 0.0,
    val tradeCreditors: Double = 0.0,

    // 3. Collateral & Proposed Facility
    val collateralType: CollateralType = CollateralType.RESIDENTIAL_PROPERTY,
    val collateralMarketValue: Double = 0.0,
    val proposedSanctionAmount: Double = 0.0,

    // 4. Verification & PD Checklist
    val kycPanAadhaarVerified: Boolean = true,
    val gstStatusActiveVerified: Boolean = true,
    val residenceOfficeFiCleared: Boolean = false,
    val personalDiscussionDone: Boolean = false,
    val legalTechnicalCleared: Boolean = false,
    val tradeReferencePositive: Boolean = false,

    // 5. Final Credit Officer Decision & Memo
    val decisionStatus: CamDecisionStatus = CamDecisionStatus.PENDING_REVIEW,
    val underwriterRemarks: String = ""
) {
    fun toJson(): String = JSONObject().apply {
        put("bureauName", bureauName)
        put("bureauScore", bureauScore)
        put("activeTradelines", activeTradelines)
        put("overdueAmount", overdueAmount)
        put("dpd30PlusCount12m", dpd30PlusCount12m)
        put("dpd90PlusCount", dpd90PlusCount)
        put("writtenOffOrSettled", writtenOffOrSettled)
        put("recentEnquiries90d", recentEnquiries90d)
        put("additionalBureauMonthlyEmi", additionalBureauMonthlyEmi)
        put("annualGstTurnover", annualGstTurnover)
        put("itrGrossTurnover", itrGrossTurnover)
        put("itrNetProfitPat", itrNetProfitPat)
        put("itrDepreciation", itrDepreciation)
        put("itrInterestPaid", itrInterestPaid)
        put("tangibleNetWorth", tangibleNetWorth)
        put("totalOutsideLiabilities", totalOutsideLiabilities)
        put("currentAssets", currentAssets)
        put("currentLiabilities", currentLiabilities)
        put("stockInventoryValue", stockInventoryValue)
        put("debtorsUnder90Days", debtorsUnder90Days)
        put("tradeCreditors", tradeCreditors)
        put("collateralType", collateralType.name)
        put("collateralMarketValue", collateralMarketValue)
        put("proposedSanctionAmount", proposedSanctionAmount)
        put("kycPanAadhaarVerified", kycPanAadhaarVerified)
        put("gstStatusActiveVerified", gstStatusActiveVerified)
        put("residenceOfficeFiCleared", residenceOfficeFiCleared)
        put("personalDiscussionDone", personalDiscussionDone)
        put("legalTechnicalCleared", legalTechnicalCleared)
        put("tradeReferencePositive", tradeReferencePositive)
        put("decisionStatus", decisionStatus.name)
        put("underwriterRemarks", underwriterRemarks)
    }.toString()

    companion object {
        fun fromJson(raw: String): UnderwriterCamProfile? = runCatching {
            val o = JSONObject(raw)
            UnderwriterCamProfile(
                bureauName = o.optString("bureauName", "CIBIL"),
                bureauScore = o.optInt("bureauScore", 748),
                activeTradelines = o.optInt("activeTradelines", 4),
                overdueAmount = o.optDouble("overdueAmount", 0.0),
                dpd30PlusCount12m = o.optInt("dpd30PlusCount12m", 0),
                dpd90PlusCount = o.optInt("dpd90PlusCount", 0),
                writtenOffOrSettled = o.optBoolean("writtenOffOrSettled", false),
                recentEnquiries90d = o.optInt("recentEnquiries90d", 2),
                additionalBureauMonthlyEmi = o.optDouble("additionalBureauMonthlyEmi", 0.0),
                annualGstTurnover = o.optDouble("annualGstTurnover", 0.0),
                itrGrossTurnover = o.optDouble("itrGrossTurnover", 0.0),
                itrNetProfitPat = o.optDouble("itrNetProfitPat", 0.0),
                itrDepreciation = o.optDouble("itrDepreciation", 0.0),
                itrInterestPaid = o.optDouble("itrInterestPaid", 0.0),
                tangibleNetWorth = o.optDouble("tangibleNetWorth", 0.0),
                totalOutsideLiabilities = o.optDouble("totalOutsideLiabilities", 0.0),
                currentAssets = o.optDouble("currentAssets", 0.0),
                currentLiabilities = o.optDouble("currentLiabilities", 0.0),
                stockInventoryValue = o.optDouble("stockInventoryValue", 0.0),
                debtorsUnder90Days = o.optDouble("debtorsUnder90Days", 0.0),
                tradeCreditors = o.optDouble("tradeCreditors", 0.0),
                collateralType = runCatching {
                    CollateralType.valueOf(o.optString("collateralType", CollateralType.RESIDENTIAL_PROPERTY.name))
                }.getOrDefault(CollateralType.RESIDENTIAL_PROPERTY),
                collateralMarketValue = o.optDouble("collateralMarketValue", 0.0),
                proposedSanctionAmount = o.optDouble("proposedSanctionAmount", 0.0),
                kycPanAadhaarVerified = o.optBoolean("kycPanAadhaarVerified", true),
                gstStatusActiveVerified = o.optBoolean("gstStatusActiveVerified", true),
                residenceOfficeFiCleared = o.optBoolean("residenceOfficeFiCleared", false),
                personalDiscussionDone = o.optBoolean("personalDiscussionDone", false),
                legalTechnicalCleared = o.optBoolean("legalTechnicalCleared", false),
                tradeReferencePositive = o.optBoolean("tradeReferencePositive", false),
                decisionStatus = runCatching {
                    CamDecisionStatus.valueOf(o.optString("decisionStatus", CamDecisionStatus.PENDING_REVIEW.name))
                }.getOrDefault(CamDecisionStatus.PENDING_REVIEW),
                underwriterRemarks = o.optString("underwriterRemarks", "")
            )
        }.getOrNull()

        /**
         * Creates a sensible starting CAM profile aligned with the analyzed bank statement's
         * primary recommended eligibility so underwriters can start tweaking immediately.
         */
        fun defaultForReport(intel: CreditIntelligenceSummary): UnderwriterCamProfile {
            val defaultCollateral = when (intel.primarySegmentEligibility.productType) {
                ProductType.HOME_LOAN -> CollateralType.RESIDENTIAL_PROPERTY
                ProductType.AUTO_LOAN -> CollateralType.VEHICLE_ASSET
                ProductType.WORKING_CAPITAL -> CollateralType.COMMERCIAL_PROPERTY
                ProductType.BUSINESS_LOAN -> CollateralType.UNSECURED
            }
            return UnderwriterCamProfile(
                collateralType = defaultCollateral,
                proposedSanctionAmount = intel.primarySegmentEligibility.recommendedAmount
            )
        }
    }
}

data class PolicyDeviationItem(
    val category: String, // "Bureau", "Banking", "GST/ITR", "Collateral", "Verification"
    val severity: String, // "Critical", "Moderate", "Info"
    val message: String
)

data class CamFinancialEvaluation(
    val consolidatedMonthlyEmi: Double,       // Bank EMIs + Bureau Unreported EMIs
    val consolidatedFoirPct: Double,
    val annualizedBankTurnover: Double,
    val bankToGstRoutingPct: Double?,         // (Annualized Bank BTO / Annual GST Turnover) * 100
    val bankToItrRoutingPct: Double?,         // (Annualized Bank BTO / ITR Gross Turnover) * 100
    val netProfitMarginPct: Double?,          // (PAT / ITR Turnover) * 100
    val cashAccrualAnnual: Double?,           // PAT + Depreciation + Interest
    val dscrRatio: Double?,                   // Cash Accrual / Annual Debt Obligations
    val leverageTolTnwRatio: Double?,         // TOL / TNW
    val currentRatio: Double?,                // Current Assets / Current Liabilities
    val computedDrawingPower: Double?,        // 75% Stock + 75% Debtors - Creditors
    val ltvPercent: Double?,                  // Proposed Sanction / Collateral Market Value
    val checklistCompletedCount: Int,
    val checklistTotalCount: Int,
    val compositeCamScore: Int,               // 0..100 (Bank 50% + Bureau 25% + Financials/LTV/Checklist 25%)
    val deviations: List<PolicyDeviationItem>
)

object UnderwriterCamEvaluator {

    fun evaluate(
        report: AnalysisReport,
        intel: CreditIntelligenceSummary,
        deep: DeepUnderwritingSummary,
        cam: UnderwriterCamProfile
    ): CamFinancialEvaluation {
        val consolidatedMonthlyEmi = intel.avgMonthlyEmiObligation + cam.additionalBureauMonthlyEmi.coerceAtLeast(0.0)
        val foirBase = if (intel.estimatedNetMonthlyIncome > 0.0) {
            maxOf(intel.avgMonthlyBusinessCredits, intel.avgMonthlySalaryCredits)
        } else {
            intel.avgMonthlyBusinessCredits
        }
        val consolidatedFoirPct = if (foirBase > 0.0) {
            ((consolidatedMonthlyEmi / foirBase) * 100.0).coerceIn(0.0, 200.0)
        } else 0.0

        val annualizedBto = deep.internalTransfers.annualizedTrueBusinessCredits
        val bankToGstPct = if (cam.annualGstTurnover > 0.0) {
            (annualizedBto / cam.annualGstTurnover) * 100.0
        } else null
        val bankToItrPct = if (cam.itrGrossTurnover > 0.0) {
            (annualizedBto / cam.itrGrossTurnover) * 100.0
        } else null

        val netProfitMarginPct = if (cam.itrGrossTurnover > 0.0 && cam.itrNetProfitPat != 0.0) {
            (cam.itrNetProfitPat / cam.itrGrossTurnover) * 100.0
        } else null

        val hasFinancialProfitInputs = cam.itrNetProfitPat != 0.0 || cam.itrDepreciation > 0.0 || cam.itrInterestPaid > 0.0
        val cashAccrualAnnual = if (hasFinancialProfitInputs) {
            cam.itrNetProfitPat + cam.itrDepreciation + cam.itrInterestPaid
        } else null

        val annualDebtObligation = (consolidatedMonthlyEmi * 12.0) + cam.itrInterestPaid
        val dscrRatio = if (cashAccrualAnnual != null && annualDebtObligation > 0.0) {
            (cashAccrualAnnual / annualDebtObligation).coerceIn(0.0, 25.0)
        } else null

        val leverageRatio = if (cam.tangibleNetWorth > 0.0 && cam.totalOutsideLiabilities > 0.0) {
            (cam.totalOutsideLiabilities / cam.tangibleNetWorth).coerceIn(0.0, 50.0)
        } else null

        val currentRatio = if (cam.currentLiabilities > 0.0 && cam.currentAssets > 0.0) {
            (cam.currentAssets / cam.currentLiabilities).coerceIn(0.0, 25.0)
        } else null

        val hasDpInputs = cam.stockInventoryValue > 0.0 || cam.debtorsUnder90Days > 0.0
        val computedDp = if (hasDpInputs) {
            ((cam.stockInventoryValue * 0.75) + (cam.debtorsUnder90Days * 0.75) - cam.tradeCreditors).coerceAtLeast(0.0)
        } else null

        val effectiveProposed = if (cam.proposedSanctionAmount > 0.0) {
            cam.proposedSanctionAmount
        } else {
            intel.primarySegmentEligibility.recommendedAmount
        }
        val ltvPct = if (cam.collateralType != CollateralType.UNSECURED && cam.collateralMarketValue > 0.0 && effectiveProposed > 0.0) {
            ((effectiveProposed / cam.collateralMarketValue) * 100.0).coerceIn(0.0, 200.0)
        } else null

        val checks = listOf(
            cam.kycPanAadhaarVerified,
            cam.gstStatusActiveVerified,
            cam.residenceOfficeFiCleared,
            cam.personalDiscussionDone,
            cam.legalTechnicalCleared,
            cam.tradeReferencePositive
        )
        val completedChecks = checks.count { it }

        // Build Policy Deviations
        val deviations = mutableListOf<PolicyDeviationItem>()

        // Bureau Checks
        when {
            cam.bureauScore in 1..649 -> deviations.add(
                PolicyDeviationItem("Bureau", "Critical", "${cam.bureauName} score ${cam.bureauScore} is below standard 650 threshold.")
            )
            cam.bureauScore in 650..699 -> deviations.add(
                PolicyDeviationItem("Bureau", "Moderate", "${cam.bureauName} score ${cam.bureauScore} is borderline (prefer ≥ 700).")
            )
        }
        if (cam.dpd90PlusCount > 0 || cam.writtenOffOrSettled) {
            deviations.add(
                PolicyDeviationItem("Bureau", "Critical", "Severe bureau delinquency detected (${cam.dpd90PlusCount}× 90+ DPD${if (cam.writtenOffOrSettled) " / Write-Off or Settlement" else ""}).")
            )
        }
        if (cam.dpd30PlusCount12m >= 2) {
            deviations.add(
                PolicyDeviationItem("Bureau", "Moderate", "${cam.dpd30PlusCount12m} instances of 30+ DPD in last 12 months.")
            )
        }
        if (cam.overdueAmount > 0.0) {
            deviations.add(
                PolicyDeviationItem("Bureau", "Critical", "Active bureau overdue of ₹${String.format(Locale.ROOT, "%,.0f", cam.overdueAmount)} — require NOC / closure.")
            )
        }
        if (cam.recentEnquiries90d >= 6) {
            deviations.add(
                PolicyDeviationItem("Bureau", "Moderate", "Credit-hungry profile: ${cam.recentEnquiries90d} bureau enquiries in 90 days.")
            )
        }

        // Banking Deep Checks
        if (deep.internalTransfers.creditInflationPercent >= 25.0) {
            deviations.add(
                PolicyDeviationItem("Banking", "Moderate", "High internal/self credit inflation (${String.format(Locale.ROOT, "%.1f%%", deep.internalTransfers.creditInflationPercent)} of gross credits).")
            )
        }
        if (deep.bounces.totalBounceCount >= 3) {
            deviations.add(
                PolicyDeviationItem("Banking", "Critical", "${deep.bounces.totalBounceCount} cheque/NACH bounce events detected (${String.format(Locale.ROOT, "%.1f%%", deep.bounces.bounceRatePercent)} bounce rate).")
            )
        }
        if (deep.concentration.top3CustomerSharePct >= 70.0 && deep.concentration.topCustomers.size >= 3) {
            deviations.add(
                PolicyDeviationItem("Banking", "Moderate", "High buyer concentration: Top 3 customers drive ${String.format(Locale.ROOT, "%.0f%%", deep.concentration.top3CustomerSharePct)} of business inflows.")
            )
        }
        if (report.overallForensicStatus == ForensicStatus.REJECTED) {
            deviations.add(
                PolicyDeviationItem("Banking", "Critical", "ePDF Forensic Gatekeeper REJECTED — tampered/edited bank statement suspected.")
            )
        }

        // GST / ITR Checks
        if (bankToGstPct != null) {
            when {
                bankToGstPct < 65.0 -> deviations.add(
                    PolicyDeviationItem("GST/ITR", "Moderate", "Low bank routing vs GST (${String.format(Locale.ROOT, "%.0f%%", bankToGstPct)} — verify other bank accounts or cash sales).")
                )
                bankToGstPct > 135.0 -> deviations.add(
                    PolicyDeviationItem("GST/ITR", "Moderate", "Bank credits exceed GST sales by ${String.format(Locale.ROOT, "%.0f%%", bankToGstPct - 100.0)} — check for circular credits or non-GST receipts.")
                )
            }
        }
        if (dscrRatio != null && dscrRatio < 1.25) {
            deviations.add(
                PolicyDeviationItem("GST/ITR", "Critical", "DSCR of ${String.format(Locale.ROOT, "%.2fx", dscrRatio)} is below minimum 1.25x norm.")
            )
        }
        if (leverageRatio != null && leverageRatio > 3.5) {
            deviations.add(
                PolicyDeviationItem("GST/ITR", "Moderate", "High leverage (TOL/TNW = ${String.format(Locale.ROOT, "%.2fx", leverageRatio)} vs 3.0x norm).")
            )
        }
        if (currentRatio != null && currentRatio < 1.15) {
            deviations.add(
                PolicyDeviationItem("GST/ITR", "Moderate", "Tight liquidity (Current Ratio = ${String.format(Locale.ROOT, "%.2fx", currentRatio)} vs 1.33x Tandon norm).")
            )
        }

        // Collateral LTV Check
        if (ltvPct != null && cam.collateralType.maxNormLtvPct > 0.0 && ltvPct > cam.collateralType.maxNormLtvPct) {
            deviations.add(
                PolicyDeviationItem(
                    "Collateral",
                    "Critical",
                    "Proposed LTV ${String.format(Locale.ROOT, "%.1f%%", ltvPct)} exceeds ${cam.collateralType.label} cap (${cam.collateralType.maxNormLtvPct.toInt()}%)."
                )
            )
        }

        // Verification Checklist
        if (!cam.kycPanAadhaarVerified || !cam.personalDiscussionDone) {
            deviations.add(
                PolicyDeviationItem("Verification", "Info", "Pre-disbursement KYC/PD/FI checks pending ($completedChecks / ${checks.size} completed).")
            )
        }

        // Composite 360° CAM Score (0..100)
        val bankComponent = intel.creditHealthScore * 0.50
        val bureauScorePts = when {
            cam.bureauScore >= 760 -> 25.0
            cam.bureauScore >= 720 -> 21.0
            cam.bureauScore >= 680 -> 15.0
            cam.bureauScore >= 650 -> 10.0
            else -> 4.0
        } - (if (cam.dpd90PlusCount > 0 || cam.writtenOffOrSettled) 12.0 else 0.0) -
            (if (cam.overdueAmount > 0.0) 6.0 else 0.0)

        var financialAndCheckPts = 15.0 + (completedChecks.toDouble() / checks.size) * 10.0
        if (dscrRatio != null) {
            financialAndCheckPts += when {
                dscrRatio >= 1.50 -> 4.0
                dscrRatio < 1.15 -> -7.0
                else -> 0.0
            }
        }
        if (ltvPct != null && cam.collateralType.maxNormLtvPct > 0.0 && ltvPct > cam.collateralType.maxNormLtvPct) {
            financialAndCheckPts -= 6.0
        }
        val compositeScore = (bankComponent + bureauScorePts.coerceAtLeast(0.0) + financialAndCheckPts.coerceIn(0.0, 25.0))
            .toInt()
            .coerceIn(0, 100)

        return CamFinancialEvaluation(
            consolidatedMonthlyEmi = consolidatedMonthlyEmi,
            consolidatedFoirPct = consolidatedFoirPct,
            annualizedBankTurnover = annualizedBto,
            bankToGstRoutingPct = bankToGstPct,
            bankToItrRoutingPct = bankToItrPct,
            netProfitMarginPct = netProfitMarginPct,
            cashAccrualAnnual = cashAccrualAnnual,
            dscrRatio = dscrRatio,
            leverageTolTnwRatio = leverageRatio,
            currentRatio = currentRatio,
            computedDrawingPower = computedDp,
            ltvPercent = ltvPct,
            checklistCompletedCount = completedChecks,
            checklistTotalCount = checks.size,
            compositeCamScore = compositeScore,
            deviations = deviations
        )
    }
}

/**
 * Lightweight offline per-case store for Underwriter CAM profiles so saved cases
 * retain their Bureau, GST/ITR, Collateral LTV, and Underwriter Remarks across sessions.
 */
class CamProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("bsa_yourview_cam_profiles", Context.MODE_PRIVATE)

    fun load(reportId: String): UnderwriterCamProfile? {
        val raw = prefs.getString("cam_$reportId", null) ?: return null
        return UnderwriterCamProfile.fromJson(raw)
    }

    fun save(reportId: String, profile: UnderwriterCamProfile) {
        prefs.edit().putString("cam_$reportId", profile.toJson()).apply()
    }

    fun delete(reportId: String) {
        prefs.edit().remove("cam_$reportId").apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
