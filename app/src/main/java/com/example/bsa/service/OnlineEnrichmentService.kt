package com.example.bsa.service

import com.example.bsa.engine.ParsedStatementFile
import com.example.bsa.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for optional Cloud AI-assisted layout recovery & narration classification.
 * Implemented in production by [FirebaseSmartAssistProvider] (Firebase AI Logic, zero API keys).
 */
interface OnlineSmartAssistProvider {
    val isAvailable: Boolean
    val statusNotice: String

    suspend fun classifyAmbiguousTransactions(
        holderName: String,
        ambiguousTxns: List<BankTransaction>
    ): SmartClassificationOutcome

    suspend fun recoverStatementLayout(
        fileName: String,
        rawPdfText: String,
        facilityType: FacilityType
    ): SmartLayoutRecoveryResult
}

/**
 * Minimal HTTP GET abstraction so [OnlineEnrichmentService] performs real network calls in
 * production and remains cleanly testable in local JVM/Robolectric unit tests.
 */
fun interface HttpTextFetcher {
    fun get(url: String): String?
}

object DefaultHttpTextFetcher : HttpTextFetcher {
    override fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "BSA-YourView/1.0")
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

data class OnlineEnrichmentOutcome(
    val metadata: AccountMetadata,
    val transactions: List<BankTransaction>,
    val verifiedHolidaysByIso: Map<String, String>, // yyyy-MM-dd -> Holiday Name
    val summary: OnlineEnrichmentSummary
)

/**
 * Connectivity-Aware Combined Hybrid Online Accuracy Engine for BSA-YourView:
 *   1. Live RBI IFSC Master Resolution (public https://ifsc.razorpay.com/{IFSC}, zero API key):
 *      - Verifies issuing account bank, branch, city, state, MICR & RTGS/NEFT/IMPS/UPI capabilities.
 *      - Resolves counterparty IFSCs found in NEFT/RTGS/IMPS narrations to enrich uncategorized
 *        "Others" transactions with verified bank/branch names.
 *   2. Live Year & State-Aware Indian Bank Holiday Resolution:
 *      - Fetches official India public holidays for statement year(s) via public calendar endpoint
 *        and merges state-specific RBI bank holidays matching the verified branch state to improve
 *        Fraud Trigger #3 precision.
 *   3. Cloud Smart Assist (Firebase AI Logic, zero API keys, opt-in when online):
 *      - Recovers non-standard PDF layouts when deterministic parsing yields 0 rows.
 *      - Refines ambiguous "Others" narrations and catches semantic self-transfers.
 * Always degrades gracefully to 100% offline processing without ever failing the pipeline.
 */
class OnlineEnrichmentService(
    private val httpFetcher: HttpTextFetcher = DefaultHttpTextFetcher,
    private val smartAssistProvider: OnlineSmartAssistProvider = FirebaseSmartAssistProvider()
) {
    private val ifscCache = ConcurrentHashMap<String, IfscVerificationInfo>()
    private val yearlyHolidayCache = ConcurrentHashMap<Int, Map<String, String>>()

    companion object {
        private val IFSC_REGEX = Regex("""\b([A-Z]{4}0[A-Z0-9]{6})\b""")

        // State-specific Indian RBI / Negotiable Instruments Act bank holidays (MM-dd -> Name)
        private val STATE_BANK_HOLIDAYS: Map<String, Map<String, String>> = mapOf(
            "MAHARASHTRA" to mapOf(
                "02-19" to "Chhatrapati Shivaji Maharaj Jayanti",
                "05-01" to "Maharashtra Day"
            ),
            "TELANGANA" to mapOf(
                "06-02" to "Telangana Formation Day"
            ),
            "ANDHRA PRADESH" to mapOf(
                "04-05" to "Babu Jagjivan Ram Jayanti"
            ),
            "KARNATAKA" to mapOf(
                "11-01" to "Karnataka Rajyotsava"
            ),
            "TAMIL NADU" to mapOf(
                "01-15" to "Thiruvalluvar Day",
                "04-14" to "Tamil New Year"
            ),
            "GUJARAT" to mapOf(
                "05-01" to "Gujarat Foundation Day",
                "10-31" to "Sardar Vallabhbhai Patel Jayanti"
            ),
            "WEST BENGAL" to mapOf(
                "01-23" to "Netaji Subhas Chandra Bose Jayanti",
                "04-15" to "Bengali New Year (Poila Boishakh)"
            ),
            "KERALA" to mapOf(
                "11-01" to "Kerala Piravi"
            ),
            "BIHAR" to mapOf(
                "03-22" to "Bihar Diwas"
            ),
            "ODISHA" to mapOf(
                "04-01" to "Utkal Divas"
            ),
            "ASSAM" to mapOf(
                "04-14" to "Bohag Bihu"
            )
        )
    }

    /**
     * Invoked only when deterministic PDFBox table parsing extracted zero rows from a non-standard
     * bank statement layout and the device is online with both Online Boost and Cloud Assist enabled.
     */
    suspend fun attemptLayoutRecoveryIfNeeded(
        parsed: ParsedStatementFile,
        rawPdfText: String,
        facilityType: FacilityType,
        isDeviceOnline: Boolean,
        onlineBoostEnabled: Boolean,
        cloudAiAssistEnabled: Boolean,
        onStageUpdate: (String) -> Unit = {}
    ): Pair<ParsedStatementFile, Int> = withContext(Dispatchers.IO) {
        if (parsed.transactions.isNotEmpty() ||
            !isDeviceOnline ||
            !onlineBoostEnabled ||
            !cloudAiAssistEnabled ||
            !smartAssistProvider.isAvailable
        ) {
            return@withContext parsed to 0
        }

        onStageUpdate("Online Hybrid Boost: Recovering non-standard PDF layout (${parsed.fileName})...")
        val recovery = smartAssistProvider.recoverStatementLayout(
            fileName = parsed.fileName,
            rawPdfText = rawPdfText,
            facilityType = facilityType
        )
        if (recovery.transactions.isEmpty()) {
            return@withContext parsed to 0
        }

        val mergedMeta = parsed.metadata.copy(
            bankName = recovery.metadata?.bankName?.takeIf { it.isNotBlank() && !it.contains("Generic", true) }
                ?: parsed.metadata.bankName,
            holderName = recovery.metadata?.holderName?.takeIf { it.isNotBlank() && it != "Account Holder" }
                ?: parsed.metadata.holderName,
            accountNumber = recovery.metadata?.accountNumber?.takeIf { it.isNotBlank() && it != "Not Detected" }
                ?: parsed.metadata.accountNumber,
            ifscCode = recovery.metadata?.ifscCode?.takeIf { it.isNotBlank() && it != "Not Detected" }
                ?: parsed.metadata.ifscCode,
            statementPeriod = recovery.metadata?.statementPeriod?.takeIf { it.isNotBlank() }
                ?: parsed.metadata.statementPeriod
        )
        parsed.copy(metadata = mergedMeta, transactions = recovery.transactions) to recovery.transactions.size
    }

    suspend fun enrichIfOnline(
        isDeviceOnline: Boolean,
        onlineBoostEnabled: Boolean,
        cloudAiAssistEnabled: Boolean,
        metadata: AccountMetadata,
        transactions: List<BankTransaction>,
        recoveredRowsCount: Int = 0,
        onStageUpdate: (String) -> Unit = {}
    ): OnlineEnrichmentOutcome = withContext(Dispatchers.IO) {
        if (!onlineBoostEnabled) {
            return@withContext OnlineEnrichmentOutcome(
                metadata = metadata,
                transactions = transactions,
                verifiedHolidaysByIso = emptyMap(),
                summary = OnlineEnrichmentSummary(
                    usedOnlineBoost = false,
                    modeLabel = "Offline Mode (Online Boost Disabled)",
                    cloudAiStatusNote = "Enable Online Accuracy Boost in Settings to activate RBI IFSC, Bank Holidays & Smart Assist."
                )
            )
        }
        if (!isDeviceOnline) {
            return@withContext OnlineEnrichmentOutcome(
                metadata = metadata,
                transactions = transactions,
                verifiedHolidaysByIso = emptyMap(),
                summary = OnlineEnrichmentSummary(
                    usedOnlineBoost = false,
                    modeLabel = "Offline Fallback (No Active Internet)",
                    cloudAiStatusNote = "Device offline — processed 100% locally with deterministic offline engine."
                )
            )
        }

        // 1. Live RBI IFSC Verification for primary account IFSC
        var updatedMeta = metadata
        val accountIfsc = extractValidIfsc(metadata.ifscCode)
        var verifiedAccountIfsc: IfscVerificationInfo? = null
        if (accountIfsc != null) {
            onStageUpdate("Online Boost: Verifying Account IFSC ($accountIfsc) via RBI master...")
            verifiedAccountIfsc = lookupIfsc(accountIfsc)
            if (verifiedAccountIfsc != null) {
                val shouldUpgradeBank = updatedMeta.bankName.contains("Generic", ignoreCase = true) ||
                    updatedMeta.bankName.isBlank()
                val upgradedBank = if (shouldUpgradeBank) verifiedAccountIfsc.bank else updatedMeta.bankName
                val enrichedAddress = when {
                    updatedMeta.address.isBlank() || updatedMeta.address.contains("extracted from", ignoreCase = true) ->
                        listOf(verifiedAccountIfsc.branch, verifiedAccountIfsc.city, verifiedAccountIfsc.state)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                    else -> updatedMeta.address
                }
                updatedMeta = updatedMeta.copy(
                    bankName = upgradedBank,
                    address = enrichedAddress
                )
            }
        }

        // 2. Live Counterparty IFSC Resolution from transaction narrations (bounded to 12 unique IFSCs)
        val counterpartyIfscs = transactions
            .mapNotNull { extractValidIfsc(it.description) }
            .filter { it != accountIfsc }
            .distinct()
            .take(12)

        val resolvedCounterparties = mutableMapOf<String, IfscVerificationInfo>()
        if (counterpartyIfscs.isNotEmpty()) {
            onStageUpdate("Online Boost: Resolving ${counterpartyIfscs.size} counterparty IFSC(s) via RBI directory...")
            for (code in counterpartyIfscs) {
                lookupIfsc(code)?.let { resolvedCounterparties[code] = it }
            }
        }

        var ifscEnrichedCount = 0
        var updatedTxns = transactions.map { txn ->
            val cpIfsc = extractValidIfsc(txn.description)
            val cpInfo = cpIfsc?.let { resolvedCounterparties[it] }
            if (cpInfo != null) {
                val cleanBank = shortBankName(cpInfo.bank)
                val needsPartyUpgrade = txn.partyName.isBlank() ||
                    txn.partyName.equals("Others", ignoreCase = true) ||
                    txn.partyName.equals("Counterparty", ignoreCase = true) ||
                    txn.partyName.equals(cpIfsc, ignoreCase = true)
                val needsCatUpgrade = txn.category == "Others"
                if (needsPartyUpgrade || needsCatUpgrade) {
                    ifscEnrichedCount++
                    val newParty = if (needsPartyUpgrade) {
                        val loc = cpInfo.city.ifBlank { cpInfo.branch }
                        if (loc.isNotBlank()) "$cleanBank ($loc)" else cleanBank
                    } else txn.partyName
                    val newCat = if (needsCatUpgrade) {
                        if (txn.amount >= 0) "Transfer from $cleanBank" else "Transfer to $cleanBank"
                    } else txn.category
                    txn.copy(category = newCat, partyName = newParty)
                } else txn
            } else txn
        }

        // 3. Cloud Smart Assist for remaining ambiguous "Others" narrations & semantic self-transfers
        var aiReclassifiedCount = 0
        var cloudNote = if (!cloudAiAssistEnabled) {
            "Cloud Smart Assist disabled in Settings (deterministic RBI IFSC & Holiday boost only)."
        } else {
            "All transactions matched deterministically — no ambiguous narrations needed cloud review."
        }

        if (cloudAiAssistEnabled && smartAssistProvider.isAvailable) {
            val ambiguous = updatedTxns.filter { it.category == "Others" }.take(35)
            if (ambiguous.isNotEmpty()) {
                onStageUpdate("Online Hybrid Boost: Refining ${ambiguous.size} ambiguous narration(s)...")
                val aiOutcome = smartAssistProvider.classifyAmbiguousTransactions(
                    holderName = updatedMeta.holderName,
                    ambiguousTxns = ambiguous
                )
                cloudNote = aiOutcome.statusNote
                if (aiOutcome.patches.isNotEmpty()) {
                    updatedTxns = updatedTxns.map { t ->
                        val patch = aiOutcome.patches[t.slNo]
                        if (patch != null) {
                            aiReclassifiedCount++
                            t.copy(
                                category = patch.category,
                                partyName = patch.partyName,
                                isSelfTransfer = t.isSelfTransfer || patch.isSelfTransfer
                            )
                        } else t
                    }
                }
            }
        }

        // 4. Live Year & State-Aware Indian Bank Holiday Resolution
        val years = updatedTxns.mapNotNull { it.dateIso.take(4).toIntOrNull() }.distinct().sorted()
        val holidayMap = linkedMapOf<String, String>()
        if (years.isNotEmpty()) {
            onStageUpdate("Online Boost: Syncing Indian bank holiday calendar (${years.joinToString(", ")})...")
            for (year in years) {
                holidayMap.putAll(lookupIndianPublicHolidays(year))
                val branchState = verifiedAccountIfsc?.state?.uppercase(Locale.ROOT).orEmpty()
                STATE_BANK_HOLIDAYS[branchState]?.forEach { (mmDd, name) ->
                    holidayMap["$year-$mmDd"] = "$name ($branchState)"
                }
            }
        }

        val anyLiveHit = verifiedAccountIfsc != null ||
            resolvedCounterparties.isNotEmpty() ||
            holidayMap.isNotEmpty() ||
            aiReclassifiedCount > 0 ||
            recoveredRowsCount > 0

        val modeLabel = when {
            anyLiveHit && (aiReclassifiedCount > 0 || recoveredRowsCount > 0) ->
                "Online Hybrid Boost (RBI IFSC + Holidays + Cloud Smart Assist)"
            anyLiveHit ->
                "Online Accuracy Boost (RBI IFSC + Bank Holidays)"
            else ->
                "Online Attempted (Fell Back to Offline Engine)"
        }

        OnlineEnrichmentOutcome(
            metadata = updatedMeta,
            transactions = updatedTxns,
            verifiedHolidaysByIso = holidayMap,
            summary = OnlineEnrichmentSummary(
                usedOnlineBoost = anyLiveHit,
                modeLabel = modeLabel,
                accountIfscInfo = verifiedAccountIfsc,
                resolvedCounterpartyIfscCount = resolvedCounterparties.size,
                enrichedTransactionsCount = ifscEnrichedCount + aiReclassifiedCount,
                aiRecoveredRowsCount = recoveredRowsCount,
                aiReclassifiedCount = aiReclassifiedCount,
                verifiedHolidayCount = holidayMap.size,
                verifiedBranchState = verifiedAccountIfsc?.state ?: "",
                cloudAiStatusNote = cloudNote
            )
        )
    }

    fun lookupIfsc(ifscCode: String): IfscVerificationInfo? {
        val clean = ifscCode.trim().uppercase(Locale.ROOT)
        if (!IFSC_REGEX.matches(clean)) return null
        ifscCache[clean]?.let { return it }

        val raw = httpFetcher.get("https://ifsc.razorpay.com/$clean") ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val info = IfscVerificationInfo(
                ifsc = obj.optString("IFSC", clean),
                bank = obj.optString("BANK", "").trim(),
                branch = obj.optString("BRANCH", "").trim(),
                city = obj.optString("CITY", "").trim(),
                state = obj.optString("STATE", "").trim(),
                micr = obj.optString("MICR", "").trim(),
                rtgs = obj.optBoolean("RTGS", true),
                neft = obj.optBoolean("NEFT", true),
                imps = obj.optBoolean("IMPS", true),
                upi = obj.optBoolean("UPI", true)
            )
            if (info.bank.isNotBlank()) {
                ifscCache[clean] = info
                info
            } else null
        }.getOrNull()
    }

    fun lookupIndianPublicHolidays(year: Int): Map<String, String> {
        yearlyHolidayCache[year]?.let { return it }
        val raw = httpFetcher.get("https://date.nager.at/api/v3/PublicHolidays/$year/IN") ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            val map = linkedMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val date = obj.optString("date", "").trim()
                val name = obj.optString("localName", obj.optString("name", "Public Holiday")).trim()
                if (date.length == 10 && name.isNotBlank()) {
                    map[date] = name
                }
            }
            if (map.isNotEmpty()) {
                yearlyHolidayCache[year] = map
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun extractValidIfsc(text: String): String? {
        if (text.isBlank()) return null
        return IFSC_REGEX.find(text.uppercase(Locale.ROOT))?.groupValues?.getOrNull(1)
    }

    private fun shortBankName(officialBank: String): String {
        val u = officialBank.uppercase(Locale.ROOT)
        return when {
            u.contains("HDFC") -> "HDFC Bank"
            u.contains("ICICI") -> "ICICI Bank"
            u.contains("STATE BANK OF INDIA") -> "SBI"
            u.contains("AXIS") -> "Axis Bank"
            u.contains("KOTAK") -> "Kotak Mahindra Bank"
            u.contains("UNION BANK") -> "Union Bank"
            u.contains("PUNJAB NATIONAL") -> "PNB"
            u.contains("BANK OF BARODA") -> "Bank of Baroda"
            u.contains("CANARA") -> "Canara Bank"
            u.contains("INDUSIND") -> "IndusInd Bank"
            u.contains("YES BANK") -> "Yes Bank"
            u.contains("IDFC") -> "IDFC First Bank"
            u.contains("FEDERAL") -> "Federal Bank"
            else -> officialBank.split(" ").take(3).joinToString(" ")
        }
    }
}
