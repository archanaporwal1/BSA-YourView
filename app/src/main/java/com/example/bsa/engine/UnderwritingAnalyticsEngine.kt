package com.example.bsa.engine

import com.example.bsa.model.*
import com.example.bsa.service.FraudScreeningService
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Module D: Underwriting, Daily EOD Resampling, OD/CC Utilization & Analytics Orchestrator
 * Delegates all 10 fraud & behavioural trigger checks to FraudScreeningService.
 */
object UnderwritingAnalyticsEngine {

    private val MONTH_ABBR = arrayOf(
        "", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    private val fraudScreeningService = FraudScreeningService()

    fun analyze(
        config: ApplicantConfig,
        metadata: AccountMetadata,
        fileAudits: List<FileForensicAudit>,
        transactions: List<BankTransaction>,
        autoSuppressOdNegativeEod: Boolean = true,
        extraBankHolidaysByIso: Map<String, String> = emptyMap(),
        onlineEnrichment: OnlineEnrichmentSummary = OnlineEnrichmentSummary()
    ): AnalysisReport {
        val overallForensic = when {
            fileAudits.any { it.status == ForensicStatus.REJECTED } -> ForensicStatus.REJECTED
            fileAudits.any { it.status == ForensicStatus.WARNING } -> ForensicStatus.WARNING
            else -> ForensicStatus.VERIFIED
        }

        // Filter transactions strictly to the user-selected date range (if configured)
        val startFilter = config.filterStartDateIso?.takeIf { it.isNotBlank() }
        val endFilter = config.filterEndDateIso?.takeIf { it.isNotBlank() }
        val hasDateFilter = startFilter != null || endFilter != null

        // Determine exact carried-forward opening balance prior to startFilter
        val priorBalanceBeforeFilter: Double? = if (startFilter != null && transactions.isNotEmpty()) {
            transactions.lastOrNull { it.dateIso < startFilter }?.balance
                ?: transactions.firstOrNull { it.dateIso >= startFilter }?.let { it.balance - it.amount }
        } else {
            null
        }

        val scopedTransactions = if (hasDateFilter) {
            transactions.filter { t ->
                val afterStart = startFilter == null || t.dateIso >= startFilter
                val beforeEnd = endFilter == null || t.dateIso <= endFilter
                afterStart && beforeEnd
            }.mapIndexed { idx, t -> t.copy(slNo = idx + 1) }
        } else {
            transactions
        }

        val effectivePeriod = when {
            startFilter != null && endFilter != null ->
                "${formatIsoToDisplay(startFilter)} to ${formatIsoToDisplay(endFilter)} (Filtered)"
            startFilter != null ->
                "From ${formatIsoToDisplay(startFilter)} (Filtered)"
            endFilter != null ->
                "Up to ${formatIsoToDisplay(endFilter)} (Filtered)"
            else -> metadata.statementPeriod
        }
        val effectiveMeta = metadata.copy(statementPeriod = effectivePeriod)

        // 1. Daily EOD Calendar Resampling strictly across the exact selected date window
        val dailyEodMap = resampleDailyEodBalances(
            transactions = scopedTransactions,
            explicitStartIso = startFilter,
            explicitEndIso = endFilter,
            openingBalanceHint = priorBalanceBeforeFilter
        )
        val chronologicalMonthKeys = dailyEodMap.keys
            .filter { it.length >= 7 }
            .map { it.substring(0, 7) }
            .distinct()
            .sorted()
        val eodMatrixMonths = chronologicalMonthKeys.map { formatMonthLabel(it) }

        val eodMatrixByDay = linkedMapOf<Int, Map<String, Double?>>()
        for (day in 1..31) {
            val row = linkedMapOf<String, Double?>()
            for (monthKey in chronologicalMonthKeys) {
                val dateKey = String.format(Locale.ROOT, "%s-%02d", monthKey, day)
                row[formatMonthLabel(monthKey)] = dailyEodMap[dateKey]
            }
            eodMatrixByDay[day] = row
        }

        // 2. Build Reverse-Chronological Monthly Metrics (all 35 rows)
        val dpLimit = when {
            config.drawingPowerLimit > 0.0 -> config.drawingPowerLimit
            config.sanctionedOdLimit > 0.0 -> config.sanctionedOdLimit
            else -> 0.0
        }

        val monthlyMetrics = chronologicalMonthKeys.reversed().map { monthKey ->
            computeMonthMetric(
                monthKey = monthKey,
                monthTxns = scopedTransactions.filter { it.dateIso.startsWith(monthKey) },
                dailyEodMap = dailyEodMap,
                dpLimit = dpLimit
            )
        }

        // 3. Loan Track Matrix (EMI amounts as rows x Month/Year columns with payment dates)
        val loanTrack = buildLoanTrack(scopedTransactions)

        // 4. Top Credits & Top Debits (store up to 50 so Excel Top-50 setting works seamlessly), Self Transfers, Grouped Parties
        val topCredits = scopedTransactions.filter { it.amount > 0 }.sortedByDescending { it.amount }.take(50)
        val topDebits = scopedTransactions.filter { it.amount < 0 }.sortedBy { it.amount }.take(50)
        val selfTransfers = scopedTransactions.filter { it.isSelfTransfer }
        val groupedParties = buildGroupedParties(scopedTransactions)

        // 5. All 10 Exact Fraud & Behavioural Triggers via FraudScreeningService
        val screeningConfig = if (!autoSuppressOdNegativeEod && config.facilityType != FacilityType.NONE) {
            config.copy(facilityType = FacilityType.NONE)
        } else {
            config
        }
        val screeningReport = fraudScreeningService.screen(
            config = screeningConfig,
            fileAudits = fileAudits,
            transactions = scopedTransactions,
            dailyEodMap = dailyEodMap,
            extraBankHolidaysByIso = extraBankHolidaysByIso
        )
        val fraudIndicators = screeningReport.indicators

        return AnalysisReport(
            config = config,
            metadata = effectiveMeta,
            overallForensicStatus = overallForensic,
            fileAudits = fileAudits,
            transactions = scopedTransactions,
            monthlyMetrics = monthlyMetrics,
            eodMatrixMonths = eodMatrixMonths,
            eodMatrixByDay = eodMatrixByDay,
            loanTrack = loanTrack,
            topCredits = topCredits,
            topDebits = topDebits,
            selfTransfers = selfTransfers,
            groupedParties = groupedParties,
            fraudIndicators = fraudIndicators,
            onlineEnrichment = onlineEnrichment
        )
    }

    /**
     * Resamples daily End-of-Day balances across every calendar day from the 1st of the earliest month
     * through the end of the latest month (forward-filling last known EOD balance).
     */
    private fun resampleDailyEodBalances(
        transactions: List<BankTransaction>,
        explicitStartIso: String? = null,
        explicitEndIso: String? = null,
        openingBalanceHint: Double? = null
    ): LinkedHashMap<String, Double> {
        val result = linkedMapOf<String, Double>()
        if (transactions.isEmpty() && (explicitStartIso == null || explicitEndIso == null || openingBalanceHint == null)) {
            return result
        }

        val txnEodByDate = linkedMapOf<String, Double>()
        for (txn in transactions) {
            txnEodByDate[txn.dateIso] = txn.balance
        }

        val startCal = when {
            explicitStartIso != null -> isoToCalendar(explicitStartIso)
            else -> isoToCalendar(transactions.first().dateIso).apply { set(Calendar.DAY_OF_MONTH, 1) }
        }
        val endCal = when {
            explicitEndIso != null -> isoToCalendar(explicitEndIso)
            else -> isoToCalendar(transactions.last().dateIso).apply {
                set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            }
        }
        if (startCal.after(endCal)) return result

        var runningBalance = openingBalanceHint
            ?: transactions.firstOrNull()?.let { it.balance - it.amount }
            ?: 0.0

        val cursor = startCal.clone() as Calendar
        var dayGuard = 0
        val maxCalendarDays = 366 * 5 // Guard against malformed outlier year causing OOM
        while (!cursor.after(endCal) && dayGuard < maxCalendarDays) {
            val iso = calendarToIso(cursor)
            val eodForDay = txnEodByDate[iso]
            if (eodForDay != null) {
                runningBalance = eodForDay
            }
            result[iso] = runningBalance
            cursor.add(Calendar.DAY_OF_MONTH, 1)
            dayGuard++
        }
        return result
    }

    private fun computeMonthMetric(
        monthKey: String,
        monthTxns: List<BankTransaction>,
        dailyEodMap: LinkedHashMap<String, Double>,
        dpLimit: Double
    ): MonthlyMetric {
        val monthDays = dailyEodMap.entries.filter { it.key.startsWith(monthKey) }
        val eodValues = monthDays.map { it.value }
        val openingBal = monthTxns.firstOrNull()?.let { it.balance - it.amount }
            ?: eodValues.firstOrNull() ?: 0.0
        val closingBal = eodValues.lastOrNull() ?: monthTxns.lastOrNull()?.balance ?: 0.0

        val bal2nd = dailyEodMap["$monthKey-02"] ?: eodValues.firstOrNull() ?: closingBal
        val bal10th = dailyEodMap["$monthKey-10"] ?: bal2nd
        val bal20th = dailyEodMap["$monthKey-20"] ?: bal10th
        val balMonthEnd = closingBal

        val avgEod = if (eodValues.isNotEmpty()) eodValues.average() else 0.0
        val minEod = eodValues.minOrNull() ?: 0.0
        val maxEod = eodValues.maxOrNull() ?: 0.0

        val credits = monthTxns.filter { it.amount > 0 }
        val debits = monthTxns.filter { it.amount < 0 }
        val totalCredits = credits.sumOf { it.amount }
        val totalDebits = debits.sumOf { -it.amount }

        val cashDepTxns = credits.filter { it.category == "Cash Deposit" }
        val cashWdlTxns = debits.filter { it.category == "Cash Withdrawal" }
        val internalCreditTxns = credits.filter { FuzzyMatcherAndCategorizer.isInternalCredit(it) }
        val selfIn = internalCreditTxns.sumOf { it.amount }
        val selfOut = debits.filter { it.isSelfTransfer }.sumOf { -it.amount }

        val emiTxns = debits.filter { it.category == "Loan" || it.category == "Home Loan" }
        val ccPay = debits.filter { it.category == "Credit Card Payment" }.sumOf { -it.amount }
        val interestEarned = credits.filter { it.category == "Interest" }.sumOf { it.amount }
        val odIntServicing = debits.filter { it.category == "OD Interest / Servicing" }.sumOf { -it.amount }
        val revTxns = monthTxns.filter { it.category == "Reversal" }
        val chequeBounceTxns = monthTxns.filter { FuzzyMatcherAndCategorizer.isChequeBounce(it) }
        val emiReturnTxns = monthTxns.filter { FuzzyMatcherAndCategorizer.isEmiReturn(it) }

        // OD/CC Utilization Analytics
        var overdrawnDays = 0
        var highestUtilAmt = 0.0
        var highestUtilDate = "-"
        var totalUtilSum = 0.0

        for ((dateIso, eod) in monthDays) {
            val utilized = if (eod < 0.0) abs(eod) else 0.0
            if (utilized > 0.0) overdrawnDays++
            totalUtilSum += utilized
            if (utilized > highestUtilAmt) {
                highestUtilAmt = utilized
                highestUtilDate = formatIsoToDisplay(dateIso)
            }
        }

        val avgUtilAmt = if (monthDays.isNotEmpty()) totalUtilSum / monthDays.size else 0.0
        val avgUtilPct = if (dpLimit > 0.0) (avgUtilAmt / dpLimit) * 100.0 else 0.0
        val highestUtilPct = if (dpLimit > 0.0) (highestUtilAmt / dpLimit) * 100.0 else 0.0

        return MonthlyMetric(
            monthKey = monthKey,
            monthLabel = formatMonthLabel(monthKey),
            openingBalance = openingBal,
            closingBalance = closingBal,
            balAsOn2nd = bal2nd,
            balAsOn10th = bal10th,
            balAsOn20th = bal20th,
            balAsOnMonthEnd = balMonthEnd,
            averageEodBalance = avgEod,
            minEodBalance = minEod,
            maxEodBalance = maxEod,
            totalCredits = totalCredits,
            creditCount = credits.size,
            totalDebits = totalDebits,
            debitCount = debits.size,
            netCashFlow = totalCredits - totalDebits,
            cashDeposits = cashDepTxns.sumOf { it.amount },
            cashDepositCount = cashDepTxns.size,
            cashWithdrawals = cashWdlTxns.sumOf { -it.amount },
            cashWithdrawalCount = cashWdlTxns.size,
            selfTransferIn = selfIn,
            selfTransferOut = selfOut,
            businessCredits = (totalCredits - selfIn).coerceAtLeast(0.0),
            businessDebits = (totalDebits - selfOut).coerceAtLeast(0.0),
            emiLoanOutflow = emiTxns.sumOf { -it.amount },
            emiLoanCount = emiTxns.size,
            creditCardPayments = ccPay,
            interestEarned = interestEarned,
            odInterestServicing = odIntServicing,
            reversalsAndBounces = revTxns.sumOf { abs(it.amount) },
            reversalCount = revTxns.size,
            overdrawnDays = overdrawnDays,
            avgOdUtilizationAmt = avgUtilAmt,
            avgOdUtilizationPct = avgUtilPct,
            highestOdUtilizationAmt = highestUtilAmt,
            highestOdUtilizationPct = highestUtilPct,
            highestOdUtilizationDate = highestUtilDate,
            internalCredits = selfIn,
            internalCreditCount = internalCreditTxns.size,
            chequeBounceAmount = chequeBounceTxns.sumOf { abs(it.amount) },
            chequeBounceCount = chequeBounceTxns.size,
            emiReturnAmount = emiReturnTxns.sumOf { abs(it.amount) },
            emiReturnCount = emiReturnTxns.size
        )
    }

    private fun buildLoanTrack(transactions: List<BankTransaction>): List<LoanTrackRow> {
        val loanDebits = transactions.filter {
            it.amount < 0 && (it.category == "Loan" || it.category == "Home Loan")
        }
        if (loanDebits.isEmpty()) return emptyList()

        // Group by rounded EMI amount (nearest rupee)
        val grouped = loanDebits.groupBy { abs(it.amount).roundToLong() }
        return grouped.entries.map { (_, txns) ->
            val representative = txns.first()
            val emiAmt = abs(representative.amount)
            val monthMap = linkedMapOf<String, String>()
            for (t in txns) {
                if (t.dateIso.length < 7) continue
                val mLabel = formatMonthLabel(t.dateIso.substring(0, 7))
                monthMap[mLabel] = t.displayDate
            }
            LoanTrackRow(
                emiAmount = emiAmt,
                lenderHint = representative.partyName.ifBlank { representative.description.take(28) },
                category = representative.category,
                monthToPaymentDate = monthMap,
                totalInstallments = txns.size,
                totalAmountPaid = txns.sumOf { -it.amount }
            )
        }.sortedByDescending { it.emiAmount }
    }

    private fun buildGroupedParties(transactions: List<BankTransaction>): List<GroupedPartyRow> {
        return transactions
            .groupBy { t ->
                when {
                    t.isSelfTransfer -> "Self Transfers"
                    t.partyName.isNotBlank() && t.partyName != "Others" -> t.partyName
                    else -> t.category
                }
            }
            .map { (groupName, list) ->
                val cr = list.filter { it.amount > 0 }.sumOf { it.amount }
                val dr = list.filter { it.amount < 0 }.sumOf { -it.amount }
                GroupedPartyRow(
                    partyOrCategory = groupName,
                    txnCount = list.size,
                    totalCredits = cr,
                    totalDebits = dr,
                    netFlow = cr - dr
                )
            }
            .sortedByDescending { it.totalCredits + it.totalDebits }
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

    private fun calendarToIso(cal: Calendar): String {
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun formatMonthLabel(monthKey: String): String {
        val parts = monthKey.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 1
        return String.format(Locale.ROOT, "%s-%02d", MONTH_ABBR[m.coerceIn(1, 12)], y % 100)
    }

    fun formatIsoToDisplay(iso: String): String {
        val parts = iso.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val d = parts.getOrNull(2)?.toIntOrNull() ?: 1
        return String.format(Locale.ROOT, "%02d-%s-%02d", d, MONTH_ABBR[m.coerceIn(1, 12)], y % 100)
    }
}
