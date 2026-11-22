package com.example.bsa.service

import com.example.bsa.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

enum class ScreeningRiskTier(val label: String) {
    LOW("Low Risk"),
    MODERATE("Moderate Risk"),
    HIGH("High Risk")
}

data class FraudScreeningReport(
    val indicators: List<FraudIndicatorResult>,
    val flaggedCount: Int,
    val fraudFlaggedCount: Int,
    val behaviouralFlaggedCount: Int,
    val riskScore: Int, // 0..100
    val riskTier: ScreeningRiskTier
)

/**
 * Dedicated 10-Point Fraud & Behavioural Screening Service (BSA-YourView)
 * Evaluates extracted transactions, daily EOD balances, facility parameters, and PDFBox forensic
 * audits against all 10 deterministic banking risk triggers:
 *   Items 1-5 (Fraud):
 *     1. Suspicious ATM Withdrawals
 *     2. Suspicious RTGS Transactions < 2L
 *     3. Transactions on Bank Holidays
 *     4. Amount-Balance Mismatches
 *     5. Suspicious bank eStatement
 *   Items 6-10 (Behavioural):
 *     6. Equal Credit Debit
 *     7. Negative EOD Balance (auto-suppressed for OD / CC facilities)
 *     8. Irregular Interest Charges
 *     9. Round figure Tax Payments
 *    10. Irregular Transfers to Parties
 */
class FraudScreeningService {

    companion object {
        private val MONTH_ABBR = arrayOf(
            "", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )

        // Indian National & Gazetted Bank Holidays (MM-dd)
        private val INDIAN_FIXED_BANK_HOLIDAYS = setOf(
            "01-26", // Republic Day
            "04-01", // Annual Bank Account Closing
            "04-14", // Dr. B.R. Ambedkar Jayanti
            "05-01", // Labour / Maharashtra Day
            "08-15", // Independence Day
            "10-02", // Gandhi Jayanti
            "12-25"  // Christmas
        )

        private val TAX_REGEX = Regex(
            """\b(GST|CBDT|INCOME\s*TAX|TDS|ADVANCE\s*TAX|CHALLAN|TIN\s*NSDL|TAX\s*PAY)\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Single source of truth computing the 0–100 Risk Score and Risk Tier from the 10 indicators.
         */
        fun computeRiskSummary(indicators: List<FraudIndicatorResult>): FraudScreeningReport {
            val fraudFlagged = indicators.count {
                it.group == IndicatorGroup.FRAUD && it.status == IndicatorStatus.FLAGGED
            }
            val behavFlagged = indicators.count {
                it.group == IndicatorGroup.BEHAVIOURAL && it.status == IndicatorStatus.FLAGGED
            }
            val totalFlagged = fraudFlagged + behavFlagged
            val score = (fraudFlagged * 18 + behavFlagged * 7).coerceIn(0, 100)
            val tier = when {
                fraudFlagged >= 2 || score >= 45 -> ScreeningRiskTier.HIGH
                fraudFlagged == 1 || behavFlagged >= 2 -> ScreeningRiskTier.MODERATE
                else -> ScreeningRiskTier.LOW
            }
            return FraudScreeningReport(
                indicators = indicators,
                flaggedCount = totalFlagged,
                fraudFlaggedCount = fraudFlagged,
                behaviouralFlaggedCount = behavFlagged,
                riskScore = score,
                riskTier = tier
            )
        }
    }

    suspend fun screenAsync(
        config: ApplicantConfig,
        fileAudits: List<FileForensicAudit>,
        transactions: List<BankTransaction>,
        dailyEodMap: LinkedHashMap<String, Double>,
        extraBankHolidaysByIso: Map<String, String> = emptyMap()
    ): FraudScreeningReport = withContext(Dispatchers.Default) {
        screen(config, fileAudits, transactions, dailyEodMap, extraBankHolidaysByIso)
    }

    fun screen(
        config: ApplicantConfig,
        fileAudits: List<FileForensicAudit>,
        transactions: List<BankTransaction>,
        dailyEodMap: LinkedHashMap<String, Double>,
        extraBankHolidaysByIso: Map<String, String> = emptyMap()
    ): FraudScreeningReport {
        val indicators = listOf(
            check1SuspiciousAtmWithdrawals(transactions),
            check2SuspiciousRtgsBelow2L(transactions),
            check3BankHolidayTransactions(transactions, extraBankHolidaysByIso),
            check4AmountBalanceMismatches(transactions),
            check5SuspiciousBankStatement(fileAudits),
            check6EqualCreditDebit(transactions),
            check7NegativeEodBalance(config, dailyEodMap),
            check8IrregularInterestCharges(transactions),
            check9RoundFigureTaxPayments(transactions),
            check10IrregularTransfersToParties(transactions)
        )
        return computeRiskSummary(indicators)
    }

    // 1. Suspicious ATM Withdrawals (non-₹100 denominations, single ATM > ₹50,000, or same-day split > ₹50,000)
    private fun check1SuspiciousAtmWithdrawals(transactions: List<BankTransaction>): FraudIndicatorResult {
        val atmTxns = transactions.filter { it.amount < 0 && it.category == "Cash Withdrawal" }
        val suspicious = mutableListOf<String>()

        for (t in atmTxns) {
            val amt = abs(t.amount)
            val rem = amt % 100.0
            if ((rem > 0.01 && rem < 99.99) || amt > 50000.0) {
                suspicious.add("${t.displayDate}: ₹${formatInr(amt)} — ${t.description.take(48)}")
            }
        }
        // Also flag rapid same-day multi-ATM withdrawals exceeding ₹50,000 combined
        atmTxns.groupBy { it.dateIso }.forEach { (_, dayList) ->
            val dayTotal = dayList.sumOf { abs(it.amount) }
            if (dayList.size >= 3 && dayTotal > 50000.0) {
                suspicious.add("${dayList.first().displayDate}: ${dayList.size} same-day ATM splits totalling ₹${formatInr(dayTotal)}")
            }
        }

        return FraudIndicatorResult(
            itemNo = 1,
            group = IndicatorGroup.FRAUD,
            title = "Suspicious ATM Withdrawals",
            status = if (suspicious.isNotEmpty()) IndicatorStatus.FLAGGED else IndicatorStatus.CLEAN,
            triggeredCount = suspicious.size,
            severity = if (suspicious.isNotEmpty()) "High" else "Low",
            summary = if (suspicious.isEmpty()) "All ATM withdrawals conform to standard denominations & daily limits."
            else "${suspicious.size} suspicious ATM withdrawal anomaly(ies) detected.",
            evidence = suspicious.take(8)
        )
    }

    // 2. Suspicious RTGS Transactions < 2L (RBI mandates ₹2,00,000 minimum floor for RTGS; excludes bank RTGS service charges/GST)
    private fun check2SuspiciousRtgsBelow2L(transactions: List<BankTransaction>): FraudIndicatorResult {
        val rtgsChargeRegex = Regex("""\b(CHG|CHRG|CHARGE|CHARGES|FEE|FEES|GST|COMMISSION|COMM|SERVICE\s*TAX|REV|REVERSAL|REFUND)\b""", RegexOption.IGNORE_CASE)
        val rtgsUnder2L = transactions.filter {
            it.description.contains("RTGS", ignoreCase = true) &&
                abs(it.amount) < 200000.0 &&
                !rtgsChargeRegex.containsMatchIn(it.description)
        }
        return FraudIndicatorResult(
            itemNo = 2,
            group = IndicatorGroup.FRAUD,
            title = "Suspicious RTGS Transactions < 2L",
            status = if (rtgsUnder2L.isNotEmpty()) IndicatorStatus.FLAGGED else IndicatorStatus.CLEAN,
            triggeredCount = rtgsUnder2L.size,
            severity = if (rtgsUnder2L.isNotEmpty()) "High" else "Low",
            summary = if (rtgsUnder2L.isEmpty()) "No RTGS entries below RBI ₹2,00,000 floor."
            else "${rtgsUnder2L.size} RTGS transaction(s) detected below ₹2,00,000 RBI floor.",
            evidence = rtgsUnder2L.take(8).map { "${it.displayDate}: ₹${formatInr(abs(it.amount))} — ${it.description.take(48)}" }
        )
    }

    // 3. Transactions on Bank Holidays (Branch/Cheque/Cash/RTGS on Indian national/state holiday, Sunday, or 2nd/4th Sat)
    private fun check3BankHolidayTransactions(
        transactions: List<BankTransaction>,
        extraBankHolidaysByIso: Map<String, String>
    ): FraudIndicatorResult {
        val holidayTxns = transactions.mapNotNull { txn ->
            val u = txn.description.uppercase(Locale.ROOT)
            val isBranchOrClearing = u.contains("CHQ") || u.contains("CHEQUE") || u.contains("CASH DEP") ||
                u.contains("BY CASH") || u.contains("RTGS") || u.contains("CLG")
            val holidayReason = if (isBranchOrClearing) resolveIndianBankHolidayReason(txn.dateIso, extraBankHolidaysByIso) else null
            if (holidayReason != null) txn to holidayReason else null
        }
        return FraudIndicatorResult(
            itemNo = 3,
            group = IndicatorGroup.FRAUD,
            title = "Transactions on Bank Holidays",
            status = if (holidayTxns.isNotEmpty()) IndicatorStatus.FLAGGED else IndicatorStatus.CLEAN,
            triggeredCount = holidayTxns.size,
            severity = if (holidayTxns.isNotEmpty()) "Medium" else "Low",
            summary = if (holidayTxns.isEmpty()) "No branch/clearing transactions posted on Indian bank holidays."
            else "${holidayTxns.size} branch/clearing transaction(s) recorded on a bank holiday or non-working weekend.",
            evidence = holidayTxns.take(8).map { (txn, reason) ->
                "${txn.displayDate} ($reason): ₹${formatInr(abs(txn.amount))} — ${txn.description.take(44)}"
            }
        )
    }

    // 4. Amount-Balance Mismatches (Prev_Balance + Signed_Amount != Current_Balance)
    private fun check4AmountBalanceMismatches(transactions: List<BankTransaction>): FraudIndicatorResult {
        val mismatches = transactions.filter { it.hasBalanceMismatch }
        return FraudIndicatorResult(
            itemNo = 4,
            group = IndicatorGroup.FRAUD,
            title = "Amount-Balance Mismatches",
            status = if (mismatches.isNotEmpty()) IndicatorStatus.FLAGGED else IndicatorStatus.CLEAN,
            triggeredCount = mismatches.size,
            severity = if (mismatches.isNotEmpty()) "High" else "Low",
            summary = if (mismatches.isEmpty()) "100% running balance reconciliation verified (Prev + Amount = Balance)."
            else "${mismatches.size} ledger row(s) failed running balance arithmetic verification.",
            evidence = mismatches.take(8).map { "Row #${it.slNo} (${it.displayDate}): Amt ₹${formatInr(it.amount)}, Bal ₹${formatInr(it.balance)}" }
        )
    }

    // 5. Suspicious bank eStatement (Gatekeeper rejection or warnings)
    private fun check5SuspiciousBankStatement(fileAudits: List<FileForensicAudit>): FraudIndicatorResult {
        val suspectFiles = fileAudits.filter { it.status != ForensicStatus.VERIFIED }
        return FraudIndicatorResult(
            itemNo = 5,
            group = IndicatorGroup.FRAUD,
            title = "Suspicious bank eStatement",
            status = if (suspectFiles.isNotEmpty()) IndicatorStatus.FLAGGED else IndicatorStatus.CLEAN,
            triggeredCount = suspectFiles.size,
            severity = when {
                suspectFiles.any { it.status == ForensicStatus.REJECTED } -> "High"
                suspectFiles.isNotEmpty() -> "Medium"
                else -> "Low"
            },
            summary = if (suspectFiles.isEmpty()) "All ${fileAudits.size} uploaded ePDF(s) passed native text, metadata, and %%EOF integrity checks."
            else "${suspectFiles.size} file(s) flagged by PDFBox Forensic Gatekeeper.",
            evidence = suspectFiles.flatMap { a -> a.findings.map { f -> "${a.fileName}: $f" } }.take(8)
        )
    }

    // 6. Equal Credit Debit (same-day or ±1 day equal amount inflow/outflow >= ₹10,000)
    private fun check6EqualCreditDebit(transactions: List<BankTransaction>): FraudIndicatorResult {
        val equalPairs = mutableListOf<String>()
        val creditsBig = transactions.filter { it.amount >= 10000.0 }
        val debitsBig = transactions.filter { it.amount <= -10000.0 }
        val usedDebits = mutableSetOf<Int>()

        for (cr in creditsBig) {
            val match = debitsBig.firstOrNull { dr ->
                dr.slNo !in usedDebits &&
                    abs(cr.amount - abs(dr.amount)) < 1.0 &&
                    abs(daysBetween(cr.dateIso, dr.dateIso)) <= 1
            }
            if (match != null) {
                usedDebits.add(match.slNo)
                equalPairs.add("${cr.displayDate}/${match.displayDate}: ₹${formatInr(cr.amount)} (${cr.partyName} ↔ ${match.partyName})")
            }
        }
        return FraudIndicatorResult(
            itemNo = 6,
            group = IndicatorGroup.BEHAVIOURAL,
            title = "Equal Credit Debit",
            status = if (equalPairs.isNotEmpty()) IndicatorStatus.FLAGGED else IndicatorStatus.CLEAN,
            triggeredCount = equalPairs.size,
            severity = if (equalPairs.size >= 3) "Medium" else "Low",
            summary = if (equalPairs.isEmpty()) "No matching equal-amount credit/debit rotation pairs detected."
            else "${equalPairs.size} equal credit-debit pair(s) (≥₹10,000 within ±1 day) observed.",
            evidence = equalPairs.take(8)
        )
    }

    // 7. Negative EOD Balance (AUTO-SUPPRESSED for OD / CC facilities)
    private fun check7NegativeEodBalance(
        config: ApplicantConfig,
        dailyEodMap: LinkedHashMap<String, Double>
    ): FraudIndicatorResult {
        val negativeEodDays = dailyEodMap.entries.filter { it.value < -0.01 }
        return if (config.facilityType == FacilityType.OD || config.facilityType == FacilityType.CC) {
            FraudIndicatorResult(
                itemNo = 7,
                group = IndicatorGroup.BEHAVIOURAL,
                title = "Negative EOD Balance",
                status = IndicatorStatus.SUPPRESSED,
                triggeredCount = 0,
                severity = "Info",
                summary = "Auto-suppressed for ${config.facilityType.label} facility (${negativeEodDays.size} normal overdrawn calendar days tracked under OD utilization).",
                evidence = emptyList()
            )
        } else {
            FraudIndicatorResult(
                itemNo = 7,
                group = IndicatorGroup.BEHAVIOURAL,
                title = "Negative EOD Balance",
                status = if (negativeEodDays.isNotEmpty()) IndicatorStatus.FLAGGED else IndicatorStatus.CLEAN,
                triggeredCount = negativeEodDays.size,
                severity = if (negativeEodDays.isNotEmpty()) "Medium" else "Low",
                summary = if (negativeEodDays.isEmpty()) "No negative EOD balances on Savings/Current account."
                else "${negativeEodDays.size} calendar day(s) closed with a negative balance.",
                evidence = negativeEodDays.take(8).map { "${formatIsoToDisplay(it.key)}: ₹${formatInr(it.value)}" }
            )
        }
    }

    // 8. Irregular Interest Charges (mid-month interest debits outside 25th..31st or 1st..3rd, or >1 per month)
    private fun check8IrregularInterestCharges(transactions: List<BankTransaction>): FraudIndicatorResult {
        val odIntTxns = transactions.filter { it.category == "OD Interest / Servicing" }
        val evidence = mutableListOf<String>()
        odIntTxns.filter { it.dateIso.length >= 10 }.groupBy { it.dateIso.substring(0, 7) }.forEach { (mKey, list) ->
            if (list.size > 1) {
                evidence.add("${formatMonthLabel(mKey)}: ${list.size} separate interest debit charges posted")
            }
            list.forEach { t ->
                val day = t.dateIso.substring(8, 10).toIntOrNull() ?: 1
                if (day in 4..24) {
                    evidence.add("${t.displayDate}: Mid-month interest debit ₹${formatInr(abs(t.amount))}")
                }
            }
        }
        return FraudIndicatorResult(
            itemNo = 8,
            group = IndicatorGroup.BEHAVIOURAL,
            title = "Irregular Interest Charges",
            status = if (evidence.isNotEmpty()) IndicatorStatus.FLAGGED else IndicatorStatus.CLEAN,
            triggeredCount = evidence.size,
            severity = if (evidence.isNotEmpty()) "Medium" else "Low",
            summary = if (evidence.isEmpty()) "Interest servicing cadence follows normal month-end billing."
            else "${evidence.size} irregular or repeated interest charge event(s) detected.",
            evidence = evidence.take(8)
        )
    }

    // 9. Round figure Tax Payments (GST/TDS/CBDT/Income Tax in exact ₹10,000 multiples)
    private fun check9RoundFigureTaxPayments(transactions: List<BankTransaction>): FraudIndicatorResult {
        val roundTaxTxns = transactions.filter { t ->
            t.amount < 0 &&
                TAX_REGEX.containsMatchIn(t.description) &&
                abs(t.amount) >= 10000.0 &&
                abs(t.amount) % 10000.0 < 0.01
        }
        return FraudIndicatorResult(
            itemNo = 9,
            group = IndicatorGroup.BEHAVIOURAL,
            title = "Round figure Tax Payments",
            status = if (roundTaxTxns.isNotEmpty()) IndicatorStatus.FLAGGED else IndicatorStatus.CLEAN,
            triggeredCount = roundTaxTxns.size,
            severity = "Low",
            summary = if (roundTaxTxns.isEmpty()) "No exact round-figure (₹10,000 multiple) tax remittances flagged."
            else "${roundTaxTxns.size} round-figure tax remittance(s) observed — verify against GST/ITR filings.",
            evidence = roundTaxTxns.take(8).map { "${it.displayDate}: ₹${formatInr(abs(it.amount))} — ${it.description.take(48)}" }
        )
    }

    // 10. Irregular Transfers to Parties (>=3 repetitive round-figure transfers >= ₹25,000 to same non-self party)
    private fun check10IrregularTransfersToParties(transactions: List<BankTransaction>): FraudIndicatorResult {
        val partyGroups = transactions.filter { t ->
            !t.isSelfTransfer &&
                t.category.startsWith("Transfer ") &&
                abs(t.amount) >= 25000.0 &&
                abs(t.amount) % 5000.0 < 0.01
        }.groupBy { it.partyName }

        val irregular = partyGroups.filter { it.value.size >= 3 }
        return FraudIndicatorResult(
            itemNo = 10,
            group = IndicatorGroup.BEHAVIOURAL,
            title = "Irregular Transfers to Parties",
            status = if (irregular.isNotEmpty()) IndicatorStatus.FLAGGED else IndicatorStatus.CLEAN,
            triggeredCount = irregular.size,
            severity = if (irregular.isNotEmpty()) "Medium" else "Low",
            summary = if (irregular.isEmpty()) "No repetitive round-sum transfer clusters to external parties detected."
            else "${irregular.size} counterparty(ies) received ≥3 repetitive round-figure transfers (≥₹25,000).",
            evidence = irregular.entries.take(8).map { (party, list) ->
                "$party: ${list.size} round transfers totalling ₹${formatInr(list.sumOf { abs(it.amount) })}"
            }
        )
    }

    private fun resolveIndianBankHolidayReason(
        isoDate: String,
        extraBankHolidaysByIso: Map<String, String>
    ): String? {
        if (isoDate.length < 10) return null
        extraBankHolidaysByIso[isoDate]?.let { return it }
        val mmDd = isoDate.substring(5, 10)
        if (mmDd in INDIAN_FIXED_BANK_HOLIDAYS) {
            return when (mmDd) {
                "01-26" -> "Republic Day"
                "04-01" -> "Bank Annual Closing"
                "04-14" -> "Ambedkar Jayanti"
                "05-01" -> "Labour Day"
                "08-15" -> "Independence Day"
                "10-02" -> "Gandhi Jayanti"
                "12-25" -> "Christmas"
                else -> "National Holiday"
            }
        }
        val cal = isoToCalendar(isoDate)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SUNDAY) return "Sunday"
        if (dow == Calendar.SATURDAY) {
            val weekOfMonth = (cal.get(Calendar.DAY_OF_MONTH) - 1) / 7 + 1
            if (weekOfMonth == 2) return "2nd Saturday"
            if (weekOfMonth == 4) return "4th Saturday"
        }
        return null
    }

    private fun daysBetween(iso1: String, iso2: String): Int {
        val c1 = isoToCalendar(iso1).timeInMillis
        val c2 = isoToCalendar(iso2).timeInMillis
        return ((c2 - c1) / (1000L * 60L * 60L * 24L)).toInt()
    }

    private fun isoToCalendar(iso: String): Calendar {
        val parts = iso.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val m = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
        val d = parts.getOrNull(2)?.toIntOrNull() ?: 1
        return Calendar.getInstance().apply {
            clear()
            set(y, m, d, 0, 0, 0)
        }
    }

    private fun formatMonthLabel(monthKey: String): String {
        val parts = monthKey.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 1
        return String.format(Locale.ROOT, "%s-%02d", MONTH_ABBR[m.coerceIn(1, 12)], y % 100)
    }

    private fun formatIsoToDisplay(iso: String): String {
        val parts = iso.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val d = parts.getOrNull(2)?.toIntOrNull() ?: 1
        return String.format(Locale.ROOT, "%02d-%s-%02d", d, MONTH_ABBR[m.coerceIn(1, 12)], y % 100)
    }

    private fun formatInr(v: Double): String = String.format(Locale.ROOT, "%,.2f", v)
}
