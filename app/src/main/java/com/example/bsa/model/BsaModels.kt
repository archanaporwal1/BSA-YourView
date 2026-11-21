package com.example.bsa.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ProductType(val displayName: String) {
    AUTO_LOAN("AutoLoan"),
    BUSINESS_LOAN("BusinessLoan"),
    WORKING_CAPITAL("WorkingCapital"),
    HOME_LOAN("HomeLoan")
}

enum class FacilityType(val code: String, val label: String) {
    NONE("NONE", "Savings / Current"),
    OD("OD", "Overdraft (OD)"),
    CC("CC", "Cash Credit (CC)")
}

enum class ForensicStatus(val label: String) {
    VERIFIED("VERIFIED"),
    WARNING("WARNING"),
    REJECTED("REJECTED")
}

enum class IndicatorGroup(val label: String) {
    FRAUD("Fraud Indicators (1–5)"),
    BEHAVIOURAL("Behavioural Indicators (6–10)")
}

enum class IndicatorStatus(val label: String) {
    CLEAN("Clean"),
    FLAGGED("Triggered"),
    SUPPRESSED("Suppressed (OD/CC)")
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class ExcelDateFormat(val pattern: String, val sampleDisplay: String) {
    DD_MMM_YY("dd-MMM-yy", "15-Aug-26"),
    DD_MM_YYYY("dd/MM/yyyy", "15/08/2026"),
    DD_DASH_MM_YYYY("dd-MM-yyyy", "15-08-2026"),
    YYYY_MM_DD("yyyy-MM-dd", "2026-08-15"),
    MM_DD_YYYY("MM/dd/yyyy", "08/15/2026");

    fun formatIso(isoDate: String): String {
        val parts = isoDate.split("-")
        if (parts.size != 3) return isoDate
        val y = parts[0].toIntOrNull() ?: return isoDate
        val m = parts[1].toIntOrNull()?.coerceIn(1, 12) ?: return isoDate
        val d = parts[2].toIntOrNull()?.coerceIn(1, 31) ?: return isoDate
        val monthAbbr = arrayOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return when (this) {
            DD_MMM_YY -> String.format(java.util.Locale.ROOT, "%02d-%s-%02d", d, monthAbbr[m], y % 100)
            DD_MM_YYYY -> String.format(java.util.Locale.ROOT, "%02d/%02d/%04d", d, m, y)
            DD_DASH_MM_YYYY -> String.format(java.util.Locale.ROOT, "%02d-%02d-%04d", d, m, y)
            YYYY_MM_DD -> String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m, d)
            MM_DD_YYYY -> String.format(java.util.Locale.ROOT, "%02d/%02d/%04d", m, d, y)
        }
    }
}

data class ExcelExportConfig(
    val dateFormat: ExcelDateFormat = ExcelDateFormat.DD_MMM_YY,
    val includeChequeNoColumn: Boolean = true,
    val includeCategoryColumn: Boolean = true,
    val includePartyColumn: Boolean = false,
    val includeSourceFileColumn: Boolean = false,
    val includeSelfTransferFlagColumn: Boolean = false,
    val splitDebitCreditColumns: Boolean = false,
    val includeFormulas: Boolean = true,
    val freezeHeaderRows: Boolean = true,
    val includeForensicAuditDetails: Boolean = true,
    val topTransactionsLimit: Int = 30
) {
    fun activeLedgerColumnHeaders(isRanked: Boolean = false): List<String> {
        val cols = mutableListOf(if (isRanked) "Rank" else "Sl. No.", "Date")
        if (includeChequeNoColumn) cols.add("Cheque No.")
        cols.add("Description")
        if (splitDebitCreditColumns) {
            cols.add("Withdrawal (Dr)")
            cols.add("Deposit (Cr)")
        } else {
            cols.add("Amount")
        }
        if (includeCategoryColumn) cols.add("Category")
        if (includePartyColumn) cols.add("Counterparty")
        if (includeSelfTransferFlagColumn) cols.add("Self Transfer")
        cols.add("Balance")
        if (includeSourceFileColumn) cols.add("Source ePDF")
        return cols
    }
}

data class IfscVerificationInfo(
    val ifsc: String = "",
    val bank: String = "",
    val branch: String = "",
    val city: String = "",
    val state: String = "",
    val micr: String = "",
    val rtgs: Boolean = true,
    val neft: Boolean = true,
    val imps: Boolean = true,
    val upi: Boolean = true
)

data class OnlineEnrichmentSummary(
    val usedOnlineBoost: Boolean = false,
    val modeLabel: String = "Offline Deterministic Engine",
    val accountIfscInfo: IfscVerificationInfo? = null,
    val resolvedCounterpartyIfscCount: Int = 0,
    val enrichedTransactionsCount: Int = 0,
    val aiRecoveredRowsCount: Int = 0,
    val aiReclassifiedCount: Int = 0,
    val verifiedHolidayCount: Int = 0,
    val verifiedBranchState: String = "",
    val cloudAiStatusNote: String = ""
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val strictForensicBlocking: Boolean = false,
    val autoSuppressOdNegativeEod: Boolean = true,
    val fuzzySelfMatchThreshold: Int = 82,
    val useCompactCurrency: Boolean = true,
    val onlineAccuracyBoostEnabled: Boolean = true,
    val cloudAiAssistEnabled: Boolean = true,
    val updateRepoOverride: String = "",
    val excelExportConfig: ExcelExportConfig = ExcelExportConfig()
)

data class StagedPdfFile(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val uriString: String,
    val fileSizeBytes: Long,
    val password: String = "",
    val passwordError: String? = null,
    val isPasswordProtected: Boolean = false,
    val isUnlocked: Boolean = true,
    val isSample: Boolean = false
)

data class ApplicantConfig(
    val applicantId: String = "YV-2026-001",
    val sourceName: String = "YourView Credit Desk",
    val productType: ProductType = ProductType.WORKING_CAPITAL,
    val facilityType: FacilityType = FacilityType.OD,
    val sanctionedOdLimit: Double = 2500000.0,
    val drawingPowerLimit: Double = 2200000.0,
    val filterStartDateIso: String? = null, // yyyy-MM-dd inclusive
    val filterEndDateIso: String? = null    // yyyy-MM-dd inclusive
)

data class FileForensicAudit(
    val fileName: String,
    val detectedBank: String,
    val accountNumber: String,
    val statementPeriod: String,
    val pageCount: Int,
    val avgCharsPerPage: Int,
    val producer: String,
    val creator: String,
    val eofMarkerCount: Int,
    val status: ForensicStatus,
    val findings: List<String>,
    val isPasswordProtected: Boolean = false,
    val pdfVersion: String = "1.4",
    val sha256Short: String = "-",
    val creationDate: String = "-",
    val modDate: String = "-",
    val imageObjectCount: Int = 0
)

data class AccountMetadata(
    val bankName: String,
    val holderName: String,
    val address: String,
    val accountNumber: String,
    val ifscCode: String,
    val statementPeriod: String
)

data class BankTransaction(
    val slNo: Int,
    val dateIso: String,         // yyyy-MM-dd
    val displayDate: String,     // dd-MMM-yy
    val chequeNo: String,
    val description: String,
    val amount: Double,          // Positive = Credit, Negative = Debit
    val category: String,
    val partyName: String,
    val balance: Double,         // OD/CC normalized: Negative = Dr (overdrawn), Positive = Cr
    val isSelfTransfer: Boolean,
    val hasBalanceMismatch: Boolean,
    val sourceFile: String
)

data class MonthlyMetric(
    val monthKey: String,        // yyyy-MM
    val monthLabel: String,      // MMM-yy
    val openingBalance: Double,
    val closingBalance: Double,
    val balAsOn2nd: Double,
    val balAsOn10th: Double,
    val balAsOn20th: Double,
    val balAsOnMonthEnd: Double,
    val averageEodBalance: Double,
    val minEodBalance: Double,
    val maxEodBalance: Double,
    val totalCredits: Double,
    val creditCount: Int,
    val totalDebits: Double,
    val debitCount: Int,
    val netCashFlow: Double,
    val cashDeposits: Double,
    val cashDepositCount: Int,
    val cashWithdrawals: Double,
    val cashWithdrawalCount: Int,
    val selfTransferIn: Double,
    val selfTransferOut: Double,
    val businessCredits: Double,
    val businessDebits: Double,
    val emiLoanOutflow: Double,
    val emiLoanCount: Int,
    val creditCardPayments: Double,
    val interestEarned: Double,
    val odInterestServicing: Double,
    val reversalsAndBounces: Double,
    val reversalCount: Int,
    val overdrawnDays: Int,
    val avgOdUtilizationAmt: Double,
    val avgOdUtilizationPct: Double,
    val highestOdUtilizationAmt: Double,
    val highestOdUtilizationPct: Double,
    val highestOdUtilizationDate: String
)

data class LoanTrackRow(
    val emiAmount: Double,
    val lenderHint: String,
    val category: String,
    val monthToPaymentDate: Map<String, String>, // monthLabel -> dd-MMM-yy
    val totalInstallments: Int,
    val totalAmountPaid: Double
)

data class GroupedPartyRow(
    val partyOrCategory: String,
    val txnCount: Int,
    val totalCredits: Double,
    val totalDebits: Double,
    val netFlow: Double
)

data class FraudIndicatorResult(
    val itemNo: Int,
    val group: IndicatorGroup,
    val title: String,
    val status: IndicatorStatus,
    val triggeredCount: Int,
    val severity: String, // "Low", "Medium", "High", "Info"
    val summary: String,
    val evidence: List<String>
)

data class AnalysisReport(
    val reportId: String = UUID.randomUUID().toString(),
    val generatedAtMillis: Long = System.currentTimeMillis(),
    val config: ApplicantConfig,
    val metadata: AccountMetadata,
    val overallForensicStatus: ForensicStatus,
    val fileAudits: List<FileForensicAudit>,
    val transactions: List<BankTransaction>,
    val monthlyMetrics: List<MonthlyMetric>, // Reverse chronological (latest month first)
    val eodMatrixMonths: List<String>,       // Chronological month labels
    val eodMatrixByDay: Map<Int, Map<String, Double?>>, // Day 1..31 -> (monthLabel -> balance)
    val loanTrack: List<LoanTrackRow>,
    val topCredits: List<BankTransaction>,
    val topDebits: List<BankTransaction>,
    val selfTransfers: List<BankTransaction>,
    val groupedParties: List<GroupedPartyRow>,
    val fraudIndicators: List<FraudIndicatorResult>,
    val onlineEnrichment: OnlineEnrichmentSummary = OnlineEnrichmentSummary()
) {
    val flaggedFraudCount: Int
        get() = fraudIndicators.count { it.status == IndicatorStatus.FLAGGED }

    val totalInflow: Double
        get() = transactions.filter { it.amount > 0 }.sumOf { it.amount }

    val totalOutflow: Double
        get() = transactions.filter { it.amount < 0 }.sumOf { -it.amount }

    val averageMonthlyBalance: Double
        get() = if (monthlyMetrics.isEmpty()) 0.0 else monthlyMetrics.map { it.averageEodBalance }.average()

    val peakOdUtilizationPct: Double
        get() = monthlyMetrics.maxOfOrNull { it.highestOdUtilizationPct } ?: 0.0

    val peakOdUtilizationAmt: Double
        get() = monthlyMetrics.maxOfOrNull { it.highestOdUtilizationAmt } ?: 0.0

    val peakOdUtilizationDate: String
        get() = monthlyMetrics.maxByOrNull { it.highestOdUtilizationAmt }?.highestOdUtilizationDate ?: "-"

    val totalEmiOutflow: Double
        get() = monthlyMetrics.sumOf { it.emiLoanOutflow }
}

/**
 * Pure Android org.json serializer/deserializer for offline Room persistence of AnalysisReport.
 */
object AnalysisReportSerializer {
    fun toJson(report: AnalysisReport): String {
        val root = JSONObject()
        root.put("reportId", report.reportId)
        root.put("generatedAtMillis", report.generatedAtMillis)
        root.put("overallForensicStatus", report.overallForensicStatus.name)

        root.put("config", JSONObject().apply {
            put("applicantId", report.config.applicantId)
            put("sourceName", report.config.sourceName)
            put("productType", report.config.productType.name)
            put("facilityType", report.config.facilityType.name)
            put("sanctionedOdLimit", report.config.sanctionedOdLimit)
            put("drawingPowerLimit", report.config.drawingPowerLimit)
            if (report.config.filterStartDateIso != null) put("filterStartDateIso", report.config.filterStartDateIso)
            if (report.config.filterEndDateIso != null) put("filterEndDateIso", report.config.filterEndDateIso)
        })

        root.put("metadata", JSONObject().apply {
            put("bankName", report.metadata.bankName)
            put("holderName", report.metadata.holderName)
            put("address", report.metadata.address)
            put("accountNumber", report.metadata.accountNumber)
            put("ifscCode", report.metadata.ifscCode)
            put("statementPeriod", report.metadata.statementPeriod)
        })

        root.put("fileAudits", JSONArray().apply {
            report.fileAudits.forEach { a ->
                put(JSONObject().apply {
                    put("fileName", a.fileName)
                    put("detectedBank", a.detectedBank)
                    put("accountNumber", a.accountNumber)
                    put("statementPeriod", a.statementPeriod)
                    put("pageCount", a.pageCount)
                    put("avgCharsPerPage", a.avgCharsPerPage)
                    put("producer", a.producer)
                    put("creator", a.creator)
                    put("eofMarkerCount", a.eofMarkerCount)
                    put("status", a.status.name)
                    put("findings", JSONArray(a.findings))
                    put("isPasswordProtected", a.isPasswordProtected)
                    put("pdfVersion", a.pdfVersion)
                    put("sha256Short", a.sha256Short)
                    put("creationDate", a.creationDate)
                    put("modDate", a.modDate)
                    put("imageObjectCount", a.imageObjectCount)
                })
            }
        })

        root.put("transactions", JSONArray().apply {
            report.transactions.forEach { t -> put(txnToJson(t)) }
        })

        root.put("monthlyMetrics", JSONArray().apply {
            report.monthlyMetrics.forEach { m ->
                put(JSONObject().apply {
                    put("monthKey", m.monthKey)
                    put("monthLabel", m.monthLabel)
                    put("openingBalance", m.openingBalance)
                    put("closingBalance", m.closingBalance)
                    put("balAsOn2nd", m.balAsOn2nd)
                    put("balAsOn10th", m.balAsOn10th)
                    put("balAsOn20th", m.balAsOn20th)
                    put("balAsOnMonthEnd", m.balAsOnMonthEnd)
                    put("averageEodBalance", m.averageEodBalance)
                    put("minEodBalance", m.minEodBalance)
                    put("maxEodBalance", m.maxEodBalance)
                    put("totalCredits", m.totalCredits)
                    put("creditCount", m.creditCount)
                    put("totalDebits", m.totalDebits)
                    put("debitCount", m.debitCount)
                    put("netCashFlow", m.netCashFlow)
                    put("cashDeposits", m.cashDeposits)
                    put("cashDepositCount", m.cashDepositCount)
                    put("cashWithdrawals", m.cashWithdrawals)
                    put("cashWithdrawalCount", m.cashWithdrawalCount)
                    put("selfTransferIn", m.selfTransferIn)
                    put("selfTransferOut", m.selfTransferOut)
                    put("businessCredits", m.businessCredits)
                    put("businessDebits", m.businessDebits)
                    put("emiLoanOutflow", m.emiLoanOutflow)
                    put("emiLoanCount", m.emiLoanCount)
                    put("creditCardPayments", m.creditCardPayments)
                    put("interestEarned", m.interestEarned)
                    put("odInterestServicing", m.odInterestServicing)
                    put("reversalsAndBounces", m.reversalsAndBounces)
                    put("reversalCount", m.reversalCount)
                    put("overdrawnDays", m.overdrawnDays)
                    put("avgOdUtilizationAmt", m.avgOdUtilizationAmt)
                    put("avgOdUtilizationPct", m.avgOdUtilizationPct)
                    put("highestOdUtilizationAmt", m.highestOdUtilizationAmt)
                    put("highestOdUtilizationPct", m.highestOdUtilizationPct)
                    put("highestOdUtilizationDate", m.highestOdUtilizationDate)
                })
            }
        })

        root.put("eodMatrixMonths", JSONArray(report.eodMatrixMonths))
        root.put("eodMatrixByDay", JSONObject().apply {
            report.eodMatrixByDay.forEach { (day, mMap) ->
                put(day.toString(), JSONObject().apply {
                    mMap.forEach { (mLabel, value) ->
                        if (value != null) put(mLabel, value) else put(mLabel, JSONObject.NULL)
                    }
                })
            }
        })

        root.put("loanTrack", JSONArray().apply {
            report.loanTrack.forEach { l ->
                put(JSONObject().apply {
                    put("emiAmount", l.emiAmount)
                    put("lenderHint", l.lenderHint)
                    put("category", l.category)
                    put("totalInstallments", l.totalInstallments)
                    put("totalAmountPaid", l.totalAmountPaid)
                    put("monthToPaymentDate", JSONObject(l.monthToPaymentDate))
                })
            }
        })

        root.put("groupedParties", JSONArray().apply {
            report.groupedParties.forEach { g ->
                put(JSONObject().apply {
                    put("partyOrCategory", g.partyOrCategory)
                    put("txnCount", g.txnCount)
                    put("totalCredits", g.totalCredits)
                    put("totalDebits", g.totalDebits)
                    put("netFlow", g.netFlow)
                })
            }
        })

        root.put("fraudIndicators", JSONArray().apply {
            report.fraudIndicators.forEach { f ->
                put(JSONObject().apply {
                    put("itemNo", f.itemNo)
                    put("group", f.group.name)
                    put("title", f.title)
                    put("status", f.status.name)
                    put("triggeredCount", f.triggeredCount)
                    put("severity", f.severity)
                    put("summary", f.summary)
                    put("evidence", JSONArray(f.evidence))
                })
            }
        })

        root.put("onlineEnrichment", JSONObject().apply {
            val oe = report.onlineEnrichment
            put("usedOnlineBoost", oe.usedOnlineBoost)
            put("modeLabel", oe.modeLabel)
            put("resolvedCounterpartyIfscCount", oe.resolvedCounterpartyIfscCount)
            put("enrichedTransactionsCount", oe.enrichedTransactionsCount)
            put("aiRecoveredRowsCount", oe.aiRecoveredRowsCount)
            put("aiReclassifiedCount", oe.aiReclassifiedCount)
            put("verifiedHolidayCount", oe.verifiedHolidayCount)
            put("verifiedBranchState", oe.verifiedBranchState)
            put("cloudAiStatusNote", oe.cloudAiStatusNote)
            oe.accountIfscInfo?.let { info ->
                put("accountIfscInfo", JSONObject().apply {
                    put("ifsc", info.ifsc)
                    put("bank", info.bank)
                    put("branch", info.branch)
                    put("city", info.city)
                    put("state", info.state)
                    put("micr", info.micr)
                    put("rtgs", info.rtgs)
                    put("neft", info.neft)
                    put("imps", info.imps)
                    put("upi", info.upi)
                })
            }
        })

        return root.toString()
    }

    fun fromJson(json: String): AnalysisReport {
        val root = JSONObject(json)
        val cfgObj = root.getJSONObject("config")
        val config = ApplicantConfig(
            applicantId = cfgObj.optString("applicantId"),
            sourceName = cfgObj.optString("sourceName"),
            productType = ProductType.valueOf(cfgObj.optString("productType", ProductType.WORKING_CAPITAL.name)),
            facilityType = FacilityType.valueOf(cfgObj.optString("facilityType", FacilityType.NONE.name)),
            sanctionedOdLimit = cfgObj.optDouble("sanctionedOdLimit", 0.0),
            drawingPowerLimit = cfgObj.optDouble("drawingPowerLimit", 0.0),
            filterStartDateIso = cfgObj.optString("filterStartDateIso", "").ifBlank { null },
            filterEndDateIso = cfgObj.optString("filterEndDateIso", "").ifBlank { null }
        )

        val metaObj = root.getJSONObject("metadata")
        val metadata = AccountMetadata(
            bankName = metaObj.optString("bankName"),
            holderName = metaObj.optString("holderName"),
            address = metaObj.optString("address"),
            accountNumber = metaObj.optString("accountNumber"),
            ifscCode = metaObj.optString("ifscCode"),
            statementPeriod = metaObj.optString("statementPeriod")
        )

        val auditsArr = root.getJSONArray("fileAudits")
        val fileAudits = (0 until auditsArr.length()).map { i ->
            val a = auditsArr.getJSONObject(i)
            val fArr = a.getJSONArray("findings")
            FileForensicAudit(
                fileName = a.optString("fileName"),
                detectedBank = a.optString("detectedBank"),
                accountNumber = a.optString("accountNumber"),
                statementPeriod = a.optString("statementPeriod"),
                pageCount = a.optInt("pageCount"),
                avgCharsPerPage = a.optInt("avgCharsPerPage"),
                producer = a.optString("producer"),
                creator = a.optString("creator"),
                eofMarkerCount = a.optInt("eofMarkerCount"),
                status = ForensicStatus.valueOf(a.optString("status", ForensicStatus.VERIFIED.name)),
                findings = (0 until fArr.length()).map { idx -> fArr.optString(idx) },
                isPasswordProtected = a.optBoolean("isPasswordProtected", false),
                pdfVersion = a.optString("pdfVersion", "1.4"),
                sha256Short = a.optString("sha256Short", "-"),
                creationDate = a.optString("creationDate", "-"),
                modDate = a.optString("modDate", "-"),
                imageObjectCount = a.optInt("imageObjectCount", 0)
            )
        }

        val txnsArr = root.getJSONArray("transactions")
        val transactions = (0 until txnsArr.length()).map { i -> txnFromJson(txnsArr.getJSONObject(i)) }

        val metricsArr = root.getJSONArray("monthlyMetrics")
        val monthlyMetrics = (0 until metricsArr.length()).map { i ->
            val m = metricsArr.getJSONObject(i)
            MonthlyMetric(
                monthKey = m.optString("monthKey"),
                monthLabel = m.optString("monthLabel"),
                openingBalance = m.optDouble("openingBalance"),
                closingBalance = m.optDouble("closingBalance"),
                balAsOn2nd = m.optDouble("balAsOn2nd"),
                balAsOn10th = m.optDouble("balAsOn10th"),
                balAsOn20th = m.optDouble("balAsOn20th"),
                balAsOnMonthEnd = m.optDouble("balAsOnMonthEnd"),
                averageEodBalance = m.optDouble("averageEodBalance"),
                minEodBalance = m.optDouble("minEodBalance"),
                maxEodBalance = m.optDouble("maxEodBalance"),
                totalCredits = m.optDouble("totalCredits"),
                creditCount = m.optInt("creditCount"),
                totalDebits = m.optDouble("totalDebits"),
                debitCount = m.optInt("debitCount"),
                netCashFlow = m.optDouble("netCashFlow"),
                cashDeposits = m.optDouble("cashDeposits"),
                cashDepositCount = m.optInt("cashDepositCount"),
                cashWithdrawals = m.optDouble("cashWithdrawals"),
                cashWithdrawalCount = m.optInt("cashWithdrawalCount"),
                selfTransferIn = m.optDouble("selfTransferIn"),
                selfTransferOut = m.optDouble("selfTransferOut"),
                businessCredits = m.optDouble("businessCredits"),
                businessDebits = m.optDouble("businessDebits"),
                emiLoanOutflow = m.optDouble("emiLoanOutflow"),
                emiLoanCount = m.optInt("emiLoanCount"),
                creditCardPayments = m.optDouble("creditCardPayments"),
                interestEarned = m.optDouble("interestEarned"),
                odInterestServicing = m.optDouble("odInterestServicing"),
                reversalsAndBounces = m.optDouble("reversalsAndBounces"),
                reversalCount = m.optInt("reversalCount"),
                overdrawnDays = m.optInt("overdrawnDays"),
                avgOdUtilizationAmt = m.optDouble("avgOdUtilizationAmt"),
                avgOdUtilizationPct = m.optDouble("avgOdUtilizationPct"),
                highestOdUtilizationAmt = m.optDouble("highestOdUtilizationAmt"),
                highestOdUtilizationPct = m.optDouble("highestOdUtilizationPct"),
                highestOdUtilizationDate = m.optString("highestOdUtilizationDate", "-")
            )
        }

        val eodMonthsArr = root.getJSONArray("eodMatrixMonths")
        val eodMatrixMonths = (0 until eodMonthsArr.length()).map { eodMonthsArr.optString(it) }
        val eodObj = root.getJSONObject("eodMatrixByDay")
        val eodMatrixByDay = mutableMapOf<Int, Map<String, Double?>>()
        for (day in 1..31) {
            val dayObj = eodObj.optJSONObject(day.toString())
            val map = linkedMapOf<String, Double?>()
            if (dayObj != null) {
                eodMatrixMonths.forEach { mLabel ->
                    map[mLabel] = if (dayObj.isNull(mLabel)) null else dayObj.optDouble(mLabel)
                }
            }
            eodMatrixByDay[day] = map
        }

        val loanArr = root.getJSONArray("loanTrack")
        val loanTrack = (0 until loanArr.length()).map { i ->
            val l = loanArr.getJSONObject(i)
            val mObj = l.getJSONObject("monthToPaymentDate")
            val mMap = linkedMapOf<String, String>()
            val keys = mObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                mMap[k] = mObj.optString(k)
            }
            LoanTrackRow(
                emiAmount = l.optDouble("emiAmount"),
                lenderHint = l.optString("lenderHint"),
                category = l.optString("category"),
                monthToPaymentDate = mMap,
                totalInstallments = l.optInt("totalInstallments"),
                totalAmountPaid = l.optDouble("totalAmountPaid")
            )
        }

        val partiesArr = root.getJSONArray("groupedParties")
        val groupedParties = (0 until partiesArr.length()).map { i ->
            val g = partiesArr.getJSONObject(i)
            GroupedPartyRow(
                partyOrCategory = g.optString("partyOrCategory"),
                txnCount = g.optInt("txnCount"),
                totalCredits = g.optDouble("totalCredits"),
                totalDebits = g.optDouble("totalDebits"),
                netFlow = g.optDouble("netFlow")
            )
        }

        val fraudArr = root.getJSONArray("fraudIndicators")
        val fraudIndicators = (0 until fraudArr.length()).map { i ->
            val f = fraudArr.getJSONObject(i)
            val eArr = f.getJSONArray("evidence")
            FraudIndicatorResult(
                itemNo = f.optInt("itemNo"),
                group = IndicatorGroup.valueOf(f.optString("group", IndicatorGroup.FRAUD.name)),
                title = f.optString("title"),
                status = IndicatorStatus.valueOf(f.optString("status", IndicatorStatus.CLEAN.name)),
                triggeredCount = f.optInt("triggeredCount"),
                severity = f.optString("severity", "Low"),
                summary = f.optString("summary"),
                evidence = (0 until eArr.length()).map { idx -> eArr.optString(idx) }
            )
        }

        val oeObj = root.optJSONObject("onlineEnrichment")
        val onlineEnrichment = if (oeObj != null) {
            val ifscObj = oeObj.optJSONObject("accountIfscInfo")
            val ifscInfo = ifscObj?.let {
                IfscVerificationInfo(
                    ifsc = it.optString("ifsc"),
                    bank = it.optString("bank"),
                    branch = it.optString("branch"),
                    city = it.optString("city"),
                    state = it.optString("state"),
                    micr = it.optString("micr"),
                    rtgs = it.optBoolean("rtgs", true),
                    neft = it.optBoolean("neft", true),
                    imps = it.optBoolean("imps", true),
                    upi = it.optBoolean("upi", true)
                )
            }
            OnlineEnrichmentSummary(
                usedOnlineBoost = oeObj.optBoolean("usedOnlineBoost", false),
                modeLabel = oeObj.optString("modeLabel", "Offline Deterministic Engine"),
                accountIfscInfo = ifscInfo,
                resolvedCounterpartyIfscCount = oeObj.optInt("resolvedCounterpartyIfscCount", 0),
                enrichedTransactionsCount = oeObj.optInt("enrichedTransactionsCount", 0),
                aiRecoveredRowsCount = oeObj.optInt("aiRecoveredRowsCount", 0),
                aiReclassifiedCount = oeObj.optInt("aiReclassifiedCount", 0),
                verifiedHolidayCount = oeObj.optInt("verifiedHolidayCount", 0),
                verifiedBranchState = oeObj.optString("verifiedBranchState", ""),
                cloudAiStatusNote = oeObj.optString("cloudAiStatusNote", "")
            )
        } else {
            OnlineEnrichmentSummary()
        }

        return AnalysisReport(
            reportId = root.optString("reportId"),
            generatedAtMillis = root.optLong("generatedAtMillis"),
            config = config,
            metadata = metadata,
            overallForensicStatus = ForensicStatus.valueOf(
                root.optString("overallForensicStatus", ForensicStatus.VERIFIED.name)
            ),
            fileAudits = fileAudits,
            transactions = transactions,
            monthlyMetrics = monthlyMetrics,
            eodMatrixMonths = eodMatrixMonths,
            eodMatrixByDay = eodMatrixByDay,
            loanTrack = loanTrack,
            topCredits = transactions.filter { it.amount > 0 }.sortedByDescending { it.amount }.take(30),
            topDebits = transactions.filter { it.amount < 0 }.sortedBy { it.amount }.take(30),
            selfTransfers = transactions.filter { it.isSelfTransfer },
            groupedParties = groupedParties,
            fraudIndicators = fraudIndicators,
            onlineEnrichment = onlineEnrichment
        )
    }

    private fun txnToJson(t: BankTransaction) = JSONObject().apply {
        put("slNo", t.slNo)
        put("dateIso", t.dateIso)
        put("displayDate", t.displayDate)
        put("chequeNo", t.chequeNo)
        put("description", t.description)
        put("amount", t.amount)
        put("category", t.category)
        put("partyName", t.partyName)
        put("balance", t.balance)
        put("isSelfTransfer", t.isSelfTransfer)
        put("hasBalanceMismatch", t.hasBalanceMismatch)
        put("sourceFile", t.sourceFile)
    }

    private fun txnFromJson(o: JSONObject) = BankTransaction(
        slNo = o.optInt("slNo"),
        dateIso = o.optString("dateIso"),
        displayDate = o.optString("displayDate"),
        chequeNo = o.optString("chequeNo"),
        description = o.optString("description"),
        amount = o.optDouble("amount"),
        category = o.optString("category"),
        partyName = o.optString("partyName"),
        balance = o.optDouble("balance"),
        isSelfTransfer = o.optBoolean("isSelfTransfer"),
        hasBalanceMismatch = o.optBoolean("hasBalanceMismatch"),
        sourceFile = o.optString("sourceFile")
    )
}
