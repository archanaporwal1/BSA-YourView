package com.example.bsa.engine

import com.example.bsa.model.AnalysisReport
import com.example.bsa.model.BankTransaction
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

enum class BounceCategory(val label: String) {
    INWARD_CHEQUE_BOUNCE("Inward Cheque Bounce"),
    OUTWARD_CHEQUE_RETURN("Outward Cheque Return"),
    NACH_ECS_BOUNCE("NACH / ECS / Mandate Bounce"),
    PENAL_AMB_CHARGE("Penal / Min-Balance Charge"),
    REVERSAL_OTHER("Transaction Reversal")
}

data class BounceEventRecord(
    val slNo: Int,
    val dateIso: String,
    val displayDate: String,
    val chequeNo: String,
    val bounceType: BounceCategory,
    val amount: Double,
    val balanceAfter: Double,
    val partyName: String,
    val description: String
)

data class EmiHealthRecord(
    val lenderHint: String,
    val category: String,
    val monthlyEmi: Double,
    val installmentsObserved: Int,
    val totalPaid: Double,
    val avgBalanceCushionAfterEmi: Double,
    val relatedBounceCount: Int,
    val repaymentStatus: String // "Regular", "Watchlist (Low Cushion)", "Stressed (Bounce)"
)

data class BounceEmiDeepAnalysis(
    val totalBounceCount: Int,
    val inwardChequeBounces: Int,
    val outwardChequeReturns: Int,
    val nachEcsBounces: Int,
    val penalChargeEvents: Int,
    val totalBounceAndPenaltyAmount: Double,
    val bounceRatePercent: Double,            // Bounces / (Total Debits + Bounces) %
    val monthWiseBounceCounts: Map<String, Int>,
    val bounceEvents: List<BounceEventRecord>,
    val emiHealthRecords: List<EmiHealthRecord>,
    val totalMonthlyEmiLoad: Double,
    val totalEmiOutflowPeriod: Double,
    val emiTrackRating: String                // "Clean", "Monitor", "Policy Breach"
)

data class ContraPairRecord(
    val creditDate: String,
    val debitDate: String,
    val daysApart: Int,
    val amount: Double,
    val creditCounterparty: String,
    val debitCounterparty: String,
    val rotationNote: String
)

data class InternalTransferDeepAnalysis(
    val grossTotalCredits: Double,
    val internalSelfCredits: Double,
    val internalSelfCreditCount: Int,
    val internalSelfDebits: Double,
    val internalSelfDebitCount: Int,
    val reversalCreditOffsets: Double,
    val netTrueBusinessCredits: Double,       // Gross Credits - Internal Credits - Reversals
    val annualizedTrueBusinessCredits: Double,
    val creditInflationPercent: Double,       // (Internal + Reversal Credits) / Gross Credits * 100
    val internalCreditTransactions: List<BankTransaction>,
    val internalDebitTransactions: List<BankTransaction>,
    val contraRotationPairs: List<ContraPairRecord>
)

data class PaymentChannelRow(
    val channelName: String,
    val creditCount: Int,
    val creditAmount: Double,
    val debitCount: Int,
    val debitAmount: Double,
    val totalVolume: Double,
    val volumeSharePct: Double
)

data class ChannelAndCashDeepAnalysis(
    val channels: List<PaymentChannelRow>,
    val totalCashDeposits: Double,
    val totalCashWithdrawals: Double,
    val cashDepositCount: Int,
    val cashWithdrawalCount: Int,
    val highValueCashTxns: List<BankTransaction>, // >= ₹50,000 cash events
    val sec269StAlertCount: Int,                  // Single cash deposit/receipt >= ₹2,00,000
    val digitalBankingRatioPct: Double            // (UPI+NEFT+RTGS+IMPS+NACH) / Total Volume %
)

data class CategoryAnalyticsRow(
    val category: String,
    val txnCount: Int,
    val creditTotal: Double,
    val debitTotal: Double,
    val netFlow: Double,
    val monthlyAverageVolume: Double,
    val shareOfTotalTurnoverPct: Double
)

data class ConcentrationEntityRow(
    val partyName: String,
    val txnCount: Int,
    val totalAmount: Double,
    val sharePercent: Double,
    val cumulativeSharePercent: Double
)

data class CounterpartyConcentrationAnalysis(
    val topCustomers: List<ConcentrationEntityRow>,
    val topSuppliers: List<ConcentrationEntityRow>,
    val top3CustomerSharePct: Double,
    val top5CustomerSharePct: Double,
    val top3SupplierSharePct: Double,
    val top5SupplierSharePct: Double,
    val concentrationRiskLabel: String
)

data class StatutoryComplianceSnapshot(
    val gstPaymentCount: Int,
    val gstPaymentTotal: Double,
    val incomeTaxTdsCount: Int,
    val incomeTaxTdsTotal: Double,
    val salaryPayrollCount: Int,
    val salaryPayrollTotal: Double,
    val rentOutflowCount: Int,
    val rentOutflowTotal: Double,
    val utilityBillCount: Int,
    val utilityBillTotal: Double
)

data class DeepUnderwritingSummary(
    val bounces: BounceEmiDeepAnalysis,
    val internalTransfers: InternalTransferDeepAnalysis,
    val channelsAndCash: ChannelAndCashDeepAnalysis,
    val categoryBreakdown: List<CategoryAnalyticsRow>,
    val concentration: CounterpartyConcentrationAnalysis,
    val statutory: StatutoryComplianceSnapshot
)

/**
 * In-depth Deterministic Bank Transaction Intelligence Engine (BSA-YourView):
 * Powers the Deep Analytics dashboard tab and Tabs 13–17 of the 18-tab Underwriting Excel Workbook.
 */
object DeepUnderwritingAnalytics {

    private val NACH_ECS_BOUNCE_REGEX = Regex(
        """\b(NACH|ECS|ACH|MANDATE|AUTO\s*DEBIT).*(RET|RTN|BOUNCE|FAIL|DISHON|INSUFF|REJECT)|\b(ECS|NACH|ACH)\s*(RET|RTN|BOUNCE)\b""",
        RegexOption.IGNORE_CASE
    )
    private val INWARD_CHQ_BOUNCE_REGEX = Regex(
        """\b(I/W\s*CHQ\s*RET|INWARD.*(RET|RTN|BOUNCE|DISHON)|CHQ\s*BOUNCE|CHEQUE\s*BOUNCE|INSUFFICIENT\s*FUND|EXCEEDS\s*ARRANGEMENT)\b""",
        RegexOption.IGNORE_CASE
    )
    private val OUTWARD_CHQ_RETURN_REGEX = Regex(
        """\b(O/W\s*CHQ\s*RET|OUTWARD.*(RET|RTN|BOUNCE)|CLG\s*RET|CHQ\s*RET|CHQ\s*RTN)\b""",
        RegexOption.IGNORE_CASE
    )
    private val PENAL_CHARGE_REGEX = Regex(
        """\b(BOUNCE\s*CHG|RETURN\s*CHG|RET\s*CHG|MIN\s*BAL\s*CHG|AMB\s*CHG|QAB\s*CHG|PENAL\s*CHG|PENALTY|OVERLIMIT\s*CHG|LATE\s*FEE)\b""",
        RegexOption.IGNORE_CASE
    )

    private val GST_REGEX = Regex("""\b(GST|GSTN|CGST|SGST|IGST|CBIC|GST\s*CHALLAN)\b""", RegexOption.IGNORE_CASE)
    private val TAX_TDS_REGEX = Regex("""\b(INCOME\s*TAX|CBDT|NSDL\s*TAX|OLTAS|ADVANCE\s*TAX|TDS|TIN\s*NSDL|TAX\s*PAYMENT)\b""", RegexOption.IGNORE_CASE)
    private val RENT_REGEX = Regex("""\b(RENT|LEASE|LANDLORD|PREMISES|GODOWN\s*RENT|OFFICE\s*RENT)\b""", RegexOption.IGNORE_CASE)
    private val UTILITY_REGEX = Regex("""\b(ELECTRICITY|BESCOM|MSEDCL|TATA\s*POWER|ADANI\s*ELEC|TSSPDCL|DISCOM|BROADBAND|AIRTEL|JIO|WATER\s*BILL|GAS\s*BILL)\b""", RegexOption.IGNORE_CASE)

    fun analyze(report: AnalysisReport): DeepUnderwritingSummary {
        val txns = report.transactions
        val monthCount = report.monthlyMetrics.size.coerceAtLeast(1)

        val bounces = computeBounceAndEmiAnalysis(report, txns)
        val internalTransfers = computeInternalAndContraAnalysis(txns, monthCount)
        val channelsAndCash = computeChannelAndCashAnalysis(txns)
        val categoryBreakdown = computeCategoryBreakdown(txns, monthCount)
        val concentration = computeCounterpartyConcentration(txns)
        val statutory = computeStatutoryCompliance(txns)

        return DeepUnderwritingSummary(
            bounces = bounces,
            internalTransfers = internalTransfers,
            channelsAndCash = channelsAndCash,
            categoryBreakdown = categoryBreakdown,
            concentration = concentration,
            statutory = statutory
        )
    }

    private fun computeBounceAndEmiAnalysis(
        report: AnalysisReport,
        txns: List<BankTransaction>
    ): BounceEmiDeepAnalysis {
        val bounceEvents = mutableListOf<BounceEventRecord>()
        var inwardChq = 0
        var outwardChq = 0
        var nachEcs = 0
        var penal = 0
        val byMonth = linkedMapOf<String, Int>()

        for (t in txns) {
            val d = t.description
            val bounceType: BounceCategory? = when {
                NACH_ECS_BOUNCE_REGEX.containsMatchIn(d) -> {
                    nachEcs++
                    BounceCategory.NACH_ECS_BOUNCE
                }
                INWARD_CHQ_BOUNCE_REGEX.containsMatchIn(d) -> {
                    inwardChq++
                    BounceCategory.INWARD_CHEQUE_BOUNCE
                }
                OUTWARD_CHQ_RETURN_REGEX.containsMatchIn(d) -> {
                    outwardChq++
                    BounceCategory.OUTWARD_CHEQUE_RETURN
                }
                PENAL_CHARGE_REGEX.containsMatchIn(d) -> {
                    penal++
                    BounceCategory.PENAL_AMB_CHARGE
                }
                t.category == "Reversal" && t.amount < 0 -> {
                    BounceCategory.REVERSAL_OTHER
                }
                else -> null
            }

            if (bounceType != null) {
                val monthKey = t.dateIso.take(7).ifBlank { "Unknown" }
                byMonth[monthKey] = (byMonth[monthKey] ?: 0) + 1
                bounceEvents.add(
                    BounceEventRecord(
                        slNo = t.slNo,
                        dateIso = t.dateIso,
                        displayDate = t.displayDate,
                        chequeNo = t.chequeNo,
                        bounceType = bounceType,
                        amount = abs(t.amount),
                        balanceAfter = t.balance,
                        partyName = t.partyName,
                        description = t.description
                    )
                )
            }
        }

        val totalDebitsCount = txns.count { it.amount < 0 }.coerceAtLeast(1)
        val totalBounces = bounceEvents.size
        val bounceRatePct = ((totalBounces.toDouble() / totalDebitsCount.toDouble()) * 100.0).coerceIn(0.0, 100.0)
        val totalBounceAmt = bounceEvents.sumOf { it.amount }

        // Deep EMI Health Table (correlates each detected LoanTrackRow with balances & bounces)
        val emiTxns = txns.filter {
            it.amount < 0 && (it.category == "Loan" || it.category == "Home Loan")
        }
        val emiHealthRecords = report.loanTrack.map { row ->
            val matchingTxns = emiTxns.filter {
                abs(abs(it.amount) - row.emiAmount) <= maxOf(50.0, row.emiAmount * 0.02) &&
                    (row.lenderHint.isBlank() || it.partyName.equals(row.lenderHint, ignoreCase = true) ||
                        it.description.contains(row.lenderHint.take(8), ignoreCase = true))
            }
            val avgCushion = if (matchingTxns.isNotEmpty()) {
                matchingTxns.map { it.balance }.average()
            } else {
                report.averageMonthlyBalance
            }
            val lenderToken = row.lenderHint.trim().split(" ").firstOrNull { it.length >= 4 } ?: ""
            val relatedBounces = if (lenderToken.isNotBlank()) {
                bounceEvents.count {
                    it.description.contains(lenderToken, ignoreCase = true) ||
                        it.partyName.contains(lenderToken, ignoreCase = true)
                }
            } else 0

            val status = when {
                relatedBounces > 0 -> "Stressed ($relatedBounces bounce${if (relatedBounces == 1) "" else "s"})"
                avgCushion < row.emiAmount && report.config.facilityType.code == "NONE" -> "Watchlist (Low Cushion)"
                else -> "Regular Servicing"
            }
            EmiHealthRecord(
                lenderHint = row.lenderHint.ifBlank { "Detected Lender EMI" },
                category = row.category,
                monthlyEmi = row.emiAmount,
                installmentsObserved = row.totalInstallments,
                totalPaid = row.totalAmountPaid,
                avgBalanceCushionAfterEmi = avgCushion,
                relatedBounceCount = relatedBounces,
                repaymentStatus = status
            )
        }

        val rating = when {
            inwardChq + nachEcs >= 3 || totalBounces >= 4 -> "Policy Breach"
            totalBounces >= 1 -> "Monitor / Clarify"
            else -> "Clean Track"
        }

        return BounceEmiDeepAnalysis(
            totalBounceCount = totalBounces,
            inwardChequeBounces = inwardChq,
            outwardChequeReturns = outwardChq,
            nachEcsBounces = nachEcs,
            penalChargeEvents = penal,
            totalBounceAndPenaltyAmount = totalBounceAmt,
            bounceRatePercent = bounceRatePct,
            monthWiseBounceCounts = byMonth,
            bounceEvents = bounceEvents,
            emiHealthRecords = emiHealthRecords,
            totalMonthlyEmiLoad = report.loanTrack.sumOf { it.emiAmount },
            totalEmiOutflowPeriod = report.totalEmiOutflow,
            emiTrackRating = rating
        )
    }

    private fun computeInternalAndContraAnalysis(
        txns: List<BankTransaction>,
        monthCount: Int
    ): InternalTransferDeepAnalysis {
        val grossCredits = txns.filter { it.amount > 0 }.sumOf { it.amount }
        val internalCreditsList = txns.filter { it.isSelfTransfer && it.amount > 0 }
        val internalDebitsList = txns.filter { it.isSelfTransfer && it.amount < 0 }

        val internalCreditsSum = internalCreditsList.sumOf { it.amount }
        val internalDebitsSum = internalDebitsList.sumOf { abs(it.amount) }

        val reversalCreditOffsets = txns.filter {
            it.amount > 0 && !it.isSelfTransfer && it.category.equals("Reversal", ignoreCase = true)
        }.sumOf { it.amount }

        val netTrueBusinessCredits = (grossCredits - internalCreditsSum - reversalCreditOffsets).coerceAtLeast(0.0)
        val annualizedTrueBto = (netTrueBusinessCredits / monthCount.coerceAtLeast(1)) * 12.0
        val inflationPct = if (grossCredits > 0.0) {
            (((internalCreditsSum + reversalCreditOffsets) / grossCredits) * 100.0).coerceIn(0.0, 100.0)
        } else 0.0

        // Detect Contra / Circular Rotation Pairs: matching credit & debit (>= ₹10,000) within ±3 calendar days
        val credits = txns.filter { it.amount >= 10000.0 }
        val debits = txns.filter { it.amount <= -10000.0 }
        val usedDebitSlNos = mutableSetOf<Int>()
        val contraPairs = mutableListOf<ContraPairRecord>()

        for (cr in credits) {
            val crEpoch = isoDateToDays(cr.dateIso) ?: continue
            val match = debits.firstOrNull { dr ->
                if (dr.slNo in usedDebitSlNos) return@firstOrNull false
                val drEpoch = isoDateToDays(dr.dateIso) ?: return@firstOrNull false
                val gap = abs(drEpoch - crEpoch)
                if (gap > 3) return@firstOrNull false
                val diff = abs(cr.amount - abs(dr.amount))
                diff <= 1.0 // Exact rupee match
            }
            if (match != null) {
                usedDebitSlNos.add(match.slNo)
                val gap = abs((isoDateToDays(match.dateIso) ?: crEpoch) - crEpoch)
                val note = when {
                    cr.isSelfTransfer || match.isSelfTransfer -> "Self / Internal Contra"
                    cr.partyName.equals(match.partyName, ignoreCase = true) && cr.partyName.isNotBlank() -> "Same-Party Round Trip"
                    gap == 0 -> "Same-Day Equal In/Out"
                    else -> "Circular Rotation ($gap day gap)"
                }
                contraPairs.add(
                    ContraPairRecord(
                        creditDate = cr.displayDate,
                        debitDate = match.displayDate,
                        daysApart = gap,
                        amount = cr.amount,
                        creditCounterparty = cr.partyName.ifBlank { cr.category },
                        debitCounterparty = match.partyName.ifBlank { match.category },
                        rotationNote = note
                    )
                )
            }
        }

        return InternalTransferDeepAnalysis(
            grossTotalCredits = grossCredits,
            internalSelfCredits = internalCreditsSum,
            internalSelfCreditCount = internalCreditsList.size,
            internalSelfDebits = internalDebitsSum,
            internalSelfDebitCount = internalDebitsList.size,
            reversalCreditOffsets = reversalCreditOffsets,
            netTrueBusinessCredits = netTrueBusinessCredits,
            annualizedTrueBusinessCredits = annualizedTrueBto,
            creditInflationPercent = inflationPct,
            internalCreditTransactions = internalCreditsList,
            internalDebitTransactions = internalDebitsList,
            contraRotationPairs = contraPairs.sortedByDescending { it.amount }
        )
    }

    private fun computeChannelAndCashAnalysis(txns: List<BankTransaction>): ChannelAndCashDeepAnalysis {
        data class Acc(var crCnt: Int = 0, var crAmt: Double = 0.0, var drCnt: Int = 0, var drAmt: Double = 0.0)
        val map = linkedMapOf<String, Acc>()
        val channelOrder = listOf(
            "NEFT", "RTGS", "IMPS", "UPI", "Cheque / Clearing",
            "NACH / ECS / Auto-Debit", "Cash (Branch / ATM)", "Card / POS",
            "Interest & Charges", "Internal / Other Transfer"
        )
        channelOrder.forEach { map[it] = Acc() }

        var cashDepAmt = 0.0
        var cashDepCnt = 0
        var cashWdlAmt = 0.0
        var cashWdlCnt = 0
        val highValueCash = mutableListOf<BankTransaction>()
        var sec269StCount = 0

        for (t in txns) {
            val channel = detectPaymentChannel(t)
            val acc = map.getOrPut(channel) { Acc() }
            if (t.amount >= 0) {
                acc.crCnt++
                acc.crAmt += t.amount
            } else {
                acc.drCnt++
                acc.drAmt += abs(t.amount)
            }

            if (channel == "Cash (Branch / ATM)") {
                if (t.amount > 0) {
                    cashDepAmt += t.amount
                    cashDepCnt++
                    if (t.amount >= 200000.0) sec269StCount++
                } else {
                    cashWdlAmt += abs(t.amount)
                    cashWdlCnt++
                }
                if (abs(t.amount) >= 50000.0) {
                    highValueCash.add(t)
                }
            }
        }

        val totalVolume = txns.sumOf { abs(it.amount) }.coerceAtLeast(1.0)
        val rows = map.entries
            .map { (name, a) ->
                val vol = a.crAmt + a.drAmt
                PaymentChannelRow(
                    channelName = name,
                    creditCount = a.crCnt,
                    creditAmount = a.crAmt,
                    debitCount = a.drCnt,
                    debitAmount = a.drAmt,
                    totalVolume = vol,
                    volumeSharePct = (vol / totalVolume) * 100.0
                )
            }
            .filter { it.creditCount + it.debitCount > 0 }
            .sortedByDescending { it.totalVolume }

        val digitalChannels = setOf("NEFT", "RTGS", "IMPS", "UPI", "NACH / ECS / Auto-Debit", "Card / POS")
        val digitalVol = rows.filter { it.channelName in digitalChannels }.sumOf { it.totalVolume }
        val digitalRatioPct = ((digitalVol / totalVolume) * 100.0).coerceIn(0.0, 100.0)

        return ChannelAndCashDeepAnalysis(
            channels = rows,
            totalCashDeposits = cashDepAmt,
            totalCashWithdrawals = cashWdlAmt,
            cashDepositCount = cashDepCnt,
            cashWithdrawalCount = cashWdlCnt,
            highValueCashTxns = highValueCash.sortedByDescending { abs(it.amount) },
            sec269StAlertCount = sec269StCount,
            digitalBankingRatioPct = digitalRatioPct
        )
    }

    private fun detectPaymentChannel(t: BankTransaction): String {
        val d = t.description.uppercase(Locale.ROOT)
        return when {
            t.category == "Cash Deposit" || t.category == "Cash Withdrawal" ||
                Regex("""\b(ATM|CASH\s*DEP|CASH\s*WDL|BY\s*CASH|TO\s*CASH|CDM|NFS/CASH)\b""").containsMatchIn(d) ->
                "Cash (Branch / ATM)"
            t.category == "Bank Charges" || t.category == "OD Interest / Servicing" || t.category == "Interest Credit" ->
                "Interest & Charges"
            d.contains("UPI") -> "UPI"
            d.contains("RTGS") -> "RTGS"
            d.contains("NEFT") -> "NEFT"
            d.contains("IMPS") -> "IMPS"
            Regex("""\b(NACH|ECS|ACH|AUTODEBIT|AUTO\s*DEBIT|SI\s*DEBIT)\b""").containsMatchIn(d) ->
                "NACH / ECS / Auto-Debit"
            t.chequeNo.isNotBlank() || Regex("""\b(CHQ|CHEQUE|CLG|CLEARING|CTS)\b""").containsMatchIn(d) ->
                "Cheque / Clearing"
            Regex("""\b(POS|CARD|VISA|MASTERCARD|RUPAY|ECOM)\b""").containsMatchIn(d) ->
                "Card / POS"
            else -> "Internal / Other Transfer"
        }
    }

    private fun computeCategoryBreakdown(
        txns: List<BankTransaction>,
        monthCount: Int
    ): List<CategoryAnalyticsRow> {
        val totalTurnover = txns.sumOf { abs(it.amount) }.coerceAtLeast(1.0)
        return txns.groupBy { it.category.ifBlank { "Others" } }
            .map { (cat, list) ->
                val cr = list.filter { it.amount > 0 }.sumOf { it.amount }
                val dr = list.filter { it.amount < 0 }.sumOf { abs(it.amount) }
                val vol = cr + dr
                CategoryAnalyticsRow(
                    category = cat,
                    txnCount = list.size,
                    creditTotal = cr,
                    debitTotal = dr,
                    netFlow = cr - dr,
                    monthlyAverageVolume = vol / monthCount.coerceAtLeast(1),
                    shareOfTotalTurnoverPct = (vol / totalTurnover) * 100.0
                )
            }
            .sortedByDescending { it.creditTotal + it.debitTotal }
    }

    private fun computeCounterpartyConcentration(txns: List<BankTransaction>): CounterpartyConcentrationAnalysis {
        val nonSelfCredits = txns.filter { it.amount > 0 && !it.isSelfTransfer && it.category != "Reversal" && it.category != "Cash Deposit" }
        val nonSelfDebits = txns.filter { it.amount < 0 && !it.isSelfTransfer && it.category != "Reversal" && it.category != "Cash Withdrawal" && it.category != "Bank Charges" }

        val totalBusCr = nonSelfCredits.sumOf { it.amount }.coerceAtLeast(1.0)
        val totalBusDr = nonSelfDebits.sumOf { abs(it.amount) }.coerceAtLeast(1.0)

        var cumCr = 0.0
        val topCustomers = nonSelfCredits
            .groupBy { normalizePartyLabel(it) }
            .entries
            .sortedByDescending { (_, list) -> list.sumOf { it.amount } }
            .take(15)
            .map { (party, list) ->
                val amt = list.sumOf { it.amount }
                val share = (amt / totalBusCr) * 100.0
                cumCr = (cumCr + share).coerceAtMost(100.0)
                ConcentrationEntityRow(party, list.size, amt, share, cumCr)
            }

        var cumDr = 0.0
        val topSuppliers = nonSelfDebits
            .groupBy { normalizePartyLabel(it) }
            .entries
            .sortedByDescending { (_, list) -> list.sumOf { abs(it.amount) } }
            .take(15)
            .map { (party, list) ->
                val amt = list.sumOf { abs(it.amount) }
                val share = (amt / totalBusDr) * 100.0
                cumDr = (cumDr + share).coerceAtMost(100.0)
                ConcentrationEntityRow(party, list.size, amt, share, cumDr)
            }

        val top3Cr = topCustomers.take(3).sumOf { it.sharePercent }.coerceAtMost(100.0)
        val top5Cr = topCustomers.take(5).sumOf { it.sharePercent }.coerceAtMost(100.0)
        val top3Dr = topSuppliers.take(3).sumOf { it.sharePercent }.coerceAtMost(100.0)
        val top5Dr = topSuppliers.take(5).sumOf { it.sharePercent }.coerceAtMost(100.0)

        val riskLabel = when {
            topCustomers.isEmpty() -> "No Business Inflows"
            top3Cr >= 75.0 -> "High Concentration"
            top3Cr >= 50.0 -> "Moderate Concentration"
            else -> "Well Diversified"
        }

        return CounterpartyConcentrationAnalysis(
            topCustomers = topCustomers,
            topSuppliers = topSuppliers,
            top3CustomerSharePct = top3Cr,
            top5CustomerSharePct = top5Cr,
            top3SupplierSharePct = top3Dr,
            top5SupplierSharePct = top5Dr,
            concentrationRiskLabel = riskLabel
        )
    }

    private fun computeStatutoryCompliance(txns: List<BankTransaction>): StatutoryComplianceSnapshot {
        val debits = txns.filter { it.amount < 0 }
        val gst = debits.filter { GST_REGEX.containsMatchIn(it.description) || it.partyName.equals("GST", ignoreCase = true) }
        val tax = debits.filter { TAX_TDS_REGEX.containsMatchIn(it.description) }
        val salary = txns.filter { it.category == "Salary / Payroll" }
        val rent = debits.filter { RENT_REGEX.containsMatchIn(it.description) }
        val utility = debits.filter { UTILITY_REGEX.containsMatchIn(it.description) || it.category == "Utilities" }

        return StatutoryComplianceSnapshot(
            gstPaymentCount = gst.size,
            gstPaymentTotal = gst.sumOf { abs(it.amount) },
            incomeTaxTdsCount = tax.size,
            incomeTaxTdsTotal = tax.sumOf { abs(it.amount) },
            salaryPayrollCount = salary.size,
            salaryPayrollTotal = salary.sumOf { abs(it.amount) },
            rentOutflowCount = rent.size,
            rentOutflowTotal = rent.sumOf { abs(it.amount) },
            utilityBillCount = utility.size,
            utilityBillTotal = utility.sumOf { abs(it.amount) }
        )
    }

    private fun normalizePartyLabel(t: BankTransaction): String {
        val clean = t.partyName.trim()
        if (clean.isNotBlank() && !clean.equals("Others", ignoreCase = true)) return clean
        return t.category.ifBlank { "Unclassified Counterparty" }
    }

    private fun isoDateToDays(iso: String): Int? {
        val parts = iso.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull()?.coerceIn(1, 12) ?: return null
        val d = parts[2].toIntOrNull()?.coerceIn(1, 31) ?: return null
        val cal = Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d)
        }
        return (cal.timeInMillis / 86_400_000L).toInt()
    }
}
