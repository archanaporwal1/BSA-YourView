package com.example.bsa.engine

import com.example.bsa.model.BankTransaction
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Module C: Rule-Based Categorizer & Pure-Kotlin Fuzzy Self-Matcher
 * - Implements RapidFuzz-compatible token_set_ratio for Indian name spelling variations
 *   (e.g., "JARUPULA RAJENDER" vs "JARPULA RAJENDAR") -> "Transfer from Self" / "Transfer To Self"
 * - Classifies into all 25 deterministic banking & Indian merchant categories
 */
object FuzzyMatcherAndCategorizer {

    private val NOISE_WORDS = setOf(
        "MR", "MRS", "MS", "SHRI", "SMT", "DR", "PROP", "M/S", "MS.", "ENTERPRISES",
        "TRADERS", "PVT", "LTD", "PRIVATE", "LIMITED", "LLP", "AND", "CO", "INDIA"
    )

    val STANDARD_CATEGORIES: List<String> = listOf(
        "Salary / Payroll",
        "Home Loan",
        "Loan",
        "OD Interest / Servicing",
        "Credit Card Payment",
        "Reversal",
        "Bank Charges",
        "Cash Deposit",
        "Cash Withdrawal",
        "Interest",
        "Insurance",
        "Investment Expense",
        "Utilities",
        "Fuel",
        "Food",
        "Travel",
        "Medical",
        "Groceries",
        "Household",
        "Clothing",
        "Vehicle",
        "Personal Care",
        "Logistics",
        "Entertainment",
        "Alcohol",
        "Transfer from Self",
        "Transfer To Self",
        "Others"
    )

    private val CATEGORY_RULES: List<Pair<String, Regex>> = listOf(
        "Home Loan" to Regex("""\b(HOME\s*LOAN|HOUSING\s*LOAN|HDFC\s*LTD|LIC\s*HFL|LIC\s*HOUSING|PNB\s*HOUSING|GIC\s*HOUSING|ICICI\s*HFC|SBI\s*HOME|BAJAJ\s*HOUSING|AAVAS|HOMEFIRST|CAN\s*FIN\s*HOMES|APTUS\s*VALUE|INDIABULLS\s*HOUSING)\b""", RegexOption.IGNORE_CASE),
        "OD Interest / Servicing" to Regex("""\b(OD\s*INT|CC\s*INT|INT\.?\s*SERVIC|OVERDRAFT\s*INT|PENAL\s*INT|LIMIT\s*RENEWAL|PROCESSING\s*FEE|INTEREST\s*RECOVER|DEBIT\s*INTEREST|INT\s*ON\s*OD|INT\s*CHRG)\b""", RegexOption.IGNORE_CASE),
        "Reversal" to Regex("""\b(REVERSAL|REV[-/\s]|RETURN|BOUNCE|CHQ\s*RET|CHQ\s*RTN|I/W\s*CHQ\s*RET|O/W\s*CHQ\s*RET|CLG\s*RET|ECS\s*RET|ECS\s*RTN|NACH\s*RET|NACH\s*RTN|ACH\s*RET|ACH\s*RTN|EMI\s*RET|EMI\s*RTN|EMI\s*BOUNCE|MANDATE\s*FAIL|AUTO\s*DEBIT\s*FAIL|SI\s*FAIL|INSUFFICIENT\s*FUND|REFUND|FAILED\s*TXN|CHARGEBACK|DISHONOUR|DISHONOR)\b""", RegexOption.IGNORE_CASE),
        "Loan" to Regex("""\b(EMI|ACH[-/\s]?DR|NACH[-/\s]?DR|ECS[-/\s]?DR|BAJAJ\s*FIN|TATA\s*CAP|CHOLAMANDALAM|MAHINDRA\s*FIN|SHRIRAM\s*FIN|HDB\s*FIN|MUTHOOT|MANAPPURAM|L&T\s*FIN|FULLERTON|SMFG|AUTO\s*LOAN|CAR\s*LOAN|BUSINESS\s*LOAN|PERSONAL\s*LOAN|LOAN\s*REPAY|KOTAK\s*PRIME|IDFC\s*FIRST\s*LOAN|HERO\s*FINCORP|PIRAMAL\s*CAP|POONAWALLA|ADITYA\s*BIRLA\s*FIN|IIFL\s*FIN|SUNDARAM\s*FIN|NAVI\s*FIN|DMI\s*FIN|KREDITBEE|KISETSU)\b""", RegexOption.IGNORE_CASE),
        "Credit Card Payment" to Regex("""\b(CREDIT\s*CARD|CC\s*PAY|CRED\.?CLUB|CRED\s*PAY|BILLDESK.*CARD|AMEX|AMERICAN\s*EXPRESS|SBI\s*CARD|HDFC\s*CARD|ICICI\s*CARD|AXIS\s*CARD|AUTOPAY\s*CC|ONECARD|RBL\s*CARD)\b""", RegexOption.IGNORE_CASE),
        "Salary / Payroll" to Regex("""\b(SALARY|SAL\s*CR|PAYROLL|MONTHLY\s*SAL|STIPEND|WAGES|REMUNERATION)\b""", RegexOption.IGNORE_CASE),
        "Bank Charges" to Regex("""\b(MIN\s*BAL\s*CHG|AMB\s*CHG|SMS\s*CHG|SMS\s*ALERT\s*CHG|ANNUAL\s*FEE|DEBIT\s*CARD\s*FEE|AMC\s*CHG|RTGS\s*CHG|NEFT\s*CHG|IMPS\s*CHG|CHQ\s*BOOK\s*CHG|PENAL\s*CHG|PENALTY\s*CHG|RETURN\s*CHG|BOUNCE\s*CHG|ECS\s*RTN\s*CHG|NACH\s*RTN\s*CHG|FOLIO\s*CHG|CONSOLIDATED\s*CHG)\b""", RegexOption.IGNORE_CASE),
        "Cash Withdrawal" to Regex("""\b(ATM\s*WDL|ATM[-/\s]CASH|NFS[-/\s]CASH|CASH\s*WDL|CASH\s*WITHDRAW|SELF\s*CHQ\s*CASH|CWDR|ATW)\b""", RegexOption.IGNORE_CASE),
        "Cash Deposit" to Regex("""\b(CASH\s*DEP|CDM\s*DEP|BY\s*CASH|BNA\s*DEP|CASH\s*RECYCLER|CDEP)\b""", RegexOption.IGNORE_CASE),
        "Interest" to Regex("""\b(INT\.?\s*PD|CREDIT\s*INTEREST|SB\s*INT|FD\s*INT|INTEREST\s*PAID|INTEREST\s*CREDIT|QUARTERLY\s*INT)\b""", RegexOption.IGNORE_CASE),
        "Insurance" to Regex("""\b(LIC\s*OF\s*INDIA|HDFC\s*LIFE|ICICI\s*PRU|SBI\s*LIFE|MAX\s*LIFE|TATA\s*AIA|BAJAJ\s*ALLIANZ|STAR\s*HEALTH|NIVA\s*BUPA|CARE\s*HEALTH|NEW\s*INDIA\s*ASSUR|POLICYBAZAAR|INSURANCE\s*PREM)\b""", RegexOption.IGNORE_CASE),
        "Investment Expense" to Regex("""\b(ZERODHA|GROWW|UPSTOX|ANGEL\s*ONE|ICICI\s*DIRECT|HDFC\s*SEC|KOTAK\s*SEC|MUTUAL\s*FUND|SIP|CAMS|KFINTECH|PPF|NPS|SGB|BSE\s*STAR|NSE|INDIAN\s*CLEARING|ICCL|PAYTM\s*MONEY|KUVERA|COIN\s*BY)\b""", RegexOption.IGNORE_CASE),
        "Food" to Regex("""\b(SWIGGY|ZOMATO|DOMINOS|PIZZA\s*HUT|MCDONALDS|KFC|BURGER\s*KING|STARBUCKS|CAFE\s*COFFEE|HALDIRAM|BIKANERVALA|BARBEQUE|RESTAURANT|HOTEL\s*SARAVANA|BAKE|BAKERY|CHAI\s*POINT|EATSURE|FAASOS)\b""", RegexOption.IGNORE_CASE),
        "Fuel" to Regex("""\b(INDIAN\s*OIL|IOCL|HPCL|HINDUSTAN\s*PETRO|BPCL|BHARAT\s*PETRO|NAYARA|SHELL\s*INDIA|RELIANCE\s*BP|PETROL|DIESEL|FUEL|FILLING\s*STATION|HP\s*PAY|FASTAG)\b""", RegexOption.IGNORE_CASE),
        "Travel" to Regex("""\b(IRCTC|INDIGO|AIR\s*INDIA|VISTARA|AKASA|SPICEJET|MAKEMYTRIP|GOIBIBO|YATRA|CLEARTRIP|REDBUS|UBER|OLA\s*CABS|RAPIDO|METRO\s*RAIL|AIRPORT|TOLL|BOOKING\.COM|OYO|AGODA)\b""", RegexOption.IGNORE_CASE),
        "Utilities" to Regex("""\b(ELECTRICITY|BESCOM|TSSPDCL|TSNPDCL|MSEDCL|TATA\s*POWER|ADANI\s*ELEC|BSES|CESC|TORRENT\s*POWER|AIRTEL|JIO|VODAFONE|VI\s*POSTPAID|BSNL|ACT\s*FIBERNET|TATASKY|TATA\s*PLAY|BROADBAND|GAS\s*BILL|INDANE|HP\s*GAS|BHARAT\s*GAS|WATER\s*BILL|BBPS|MUNICIPAL\s*TAX|GST|CBDT|INCOME\s*TAX|CHALLAN)\b""", RegexOption.IGNORE_CASE),
        "Medical" to Regex("""\b(APOLLO\s*HOSP|APOLLO\s*PHARM|MEDPLUS|PHARMEASY|1MG|NETMEDS|FORTIS|MAX\s*HEALTH|YASHODA|KIMS\s*HOSP|MANIPAL\s*HOSP|DIAGNOSTIC|PATHLAB|LAL\s*PATH|CLINIC|PHARMACY|CHEMIST|HOSPITAL|DENTAL)\b""", RegexOption.IGNORE_CASE),
        "Entertainment" to Regex("""\b(NETFLIX|AMAZON\s*PRIME|HOTSTAR|JIO\s*CINEMA|SPOTIFY|BOOKMYSHOW|PVR|INOX|SONY\s*LIV|ZEE5|YOUTUBE|PLAYSTATION|STEAM|DREAM11|CLUB\s*MEMBER)\b""", RegexOption.IGNORE_CASE),
        "Alcohol" to Regex("""\b(WINE\s*SHOP|LIQUOR|TASMAC|BEVERAGES\s*CORP|LIVING\s*LIQUIDZ|TONIQUE|BREWERY|PUB|BAR\s*AND\s*REST|SPIRITS)\b""", RegexOption.IGNORE_CASE),
        "Groceries" to Regex("""\b(DMART|D-MART|RELIANCE\s*FRESH|RELIANCE\s*SMART|BIGBASKET|BLINKIT|ZEPTO|INSTAMART|MORE\s*RETAIL|SPENCERS|RATNADEEP|SUPERMARKET|KIRANA|GROCERY|FRESH\s*MART|STAR\s*BAZAAR|NATURE\s*BASKET)\b""", RegexOption.IGNORE_CASE),
        "Household" to Regex("""\b(URBAN\s*COMPANY|IKEA|PEPPERFRY|HOME\s*CENTRE|CROMA|RELIANCE\s*DIGITAL|VIJAY\s*SALES|FURNITURE|APPLIANCE|HARDWARE|PAINTS|ASIAN\s*PAINTS|MAID|SOCIETY\s*MAINT|RENT\s*PAY|NOBROKER|MYGATE)\b""", RegexOption.IGNORE_CASE),
        "Clothing" to Regex("""\b(MYNTRA|AJIO|ZARA|H&M|WESTSIDE|PANTALOONS|LIFESTYLE|SHOPPERS\s*STOP|MAX\s*FASHION|RAYMOND|ALLEN\s*SOLLY|PETER\s*ENGLAND|NIKE|ADIDAS|PUMA|BATA|TEXTILES|GARMENTS|SILKS|APPAREL)\b""", RegexOption.IGNORE_CASE),
        "Vehicle" to Regex("""\b(MARUTI|HYUNDAI|TATA\s*MOTORS|MAHINDRA\s*AUTO|HONDA\s*CARS|TOYOTA|ROYAL\s*ENFIELD|TVS\s*MOTOR|SERVICE\s*CENTRE|TYRES|MRF|APOLLO\s*TYRE|CEAT|CAR\s*WASH|AUTO\s*SPARES|MOTOR\s*INSURANCE|ACKO|DIGIT\s*INS)\b""", RegexOption.IGNORE_CASE),
        "Personal Care" to Regex("""\b(NYKAA|SEPHORA|SALON|SPA|LAKME|NATURALS\s*SALON|TONI\s*AND\s*GUY|GYM|CULT\.?FIT|FITNESS|COSMETICS|OPTICALS|LENSKART)\b""", RegexOption.IGNORE_CASE),
        "Logistics" to Regex("""\b(DELHIVERY|BLUE\s*DART|DTDC|PORTER|BLUEDART|SHIPROCKET|FEDEX|DHL|VRL\s*LOGISTICS|TCI\s*EXPRESS|SAFEEXPRESS|GATI|COURIER|FREIGHT|TRANSPORT|CARGO|PACKERS)\b""", RegexOption.IGNORE_CASE)
    )

    fun categorizeAll(
        holderName: String,
        verifiedRows: List<Pair<RawParsedTransaction, Boolean>>,
        fuzzyThreshold: Int = 82
    ): List<BankTransaction> {
        return verifiedRows.mapIndexed { idx, (raw, hasMismatch) ->
            val extractedParty = extractCounterparty(raw.description)
            val isSelf = isSelfTransferMatch(holderName, raw.description, extractedParty, fuzzyThreshold)

            val category: String
            val resolvedParty: String

            if (isSelf) {
                category = if (raw.signedAmount >= 0) "Transfer from Self" else "Transfer To Self"
                resolvedParty = "Self ($holderName)"
            } else {
                val matchedRule = CATEGORY_RULES.firstOrNull { (_, regex) ->
                    regex.containsMatchIn(raw.description)
                }?.first

                if (matchedRule != null) {
                    // Guard: Positive interest is "Interest", negative interest is "OD Interest / Servicing"
                    category = when {
                        matchedRule == "Interest" && raw.signedAmount < 0 -> "OD Interest / Servicing"
                        matchedRule == "OD Interest / Servicing" && raw.signedAmount > 0 -> "Interest"
                        else -> matchedRule
                    }
                    resolvedParty = extractedParty.ifBlank { category }
                } else if (extractedParty.isNotBlank()) {
                    category = if (raw.signedAmount >= 0) {
                        "Transfer from $extractedParty"
                    } else {
                        "Transfer to $extractedParty"
                    }
                    resolvedParty = extractedParty
                } else {
                    category = "Others"
                    resolvedParty = "Others"
                }
            }

            BankTransaction(
                slNo = idx + 1,
                dateIso = raw.dateIso,
                displayDate = raw.displayDate,
                chequeNo = raw.chequeNo,
                description = raw.description,
                amount = raw.signedAmount,
                category = category,
                partyName = resolvedParty,
                balance = raw.normalizedBalance,
                isSelfTransfer = isSelf,
                hasBalanceMismatch = hasMismatch,
                sourceFile = raw.sourceFile
            )
        }
    }

    /**
     * Evaluates whether a transaction narration or extracted counterparty refers to the account holder
     * using explicit self keywords OR RapidFuzz-equivalent token_set_ratio >= 82.
     */
    fun isSelfTransferMatch(
        holderName: String,
        description: String,
        party: String,
        fuzzyThreshold: Int = 82
    ): Boolean {
        val descUpper = description.uppercase(Locale.ROOT)
        // Explicit self / internal-account markers (excluding cash withdrawal by self cheque)
        if ((descUpper.contains("SELF TRANSFER") ||
                descUpper.contains("TRF TO SELF") ||
                descUpper.contains("TRF FROM SELF") ||
                descUpper.contains("OWN ACCOUNT") ||
                descUpper.contains("OWN A/C") ||
                descUpper.contains("INTERNAL CREDIT") ||
                descUpper.contains("INTERNAL TRF") ||
                descUpper.contains("INTER ACCOUNT") ||
                descUpper.contains("SELF CREDIT") ||
                descUpper.contains("SWEEP IN") ||
                descUpper.contains("AUTO SWEEP")) &&
            !descUpper.contains("CASH")
        ) {
            return true
        }

        val cleanHolder = normalizeForFuzzy(holderName)
        val holderTokens = cleanHolder.split(" ").filter { it.length >= 2 }
        if (holderTokens.isEmpty()) return false

        // Compare against extracted counterparty first
        if (party.isNotBlank()) {
            val score = tokenSetRatio(cleanHolder, normalizeForFuzzy(party))
            if (score >= fuzzyThreshold) return true
        }

        // Sliding window token check across narration segments (handles UPI/NEFT/IMPS slash-delimited chunks)
        val segments = description.split("/", "-", ":", "|")
            .map { normalizeForFuzzy(it) }
            .filter { it.length >= 4 }

        for (seg in segments) {
            val score = tokenSetRatio(cleanHolder, seg)
            if (score >= (fuzzyThreshold + 2).coerceAtMost(98)) return true
        }

        // Also check full description if holder has >= 2 distinct tokens (avoids false positives on 1-word names)
        if (holderTokens.size >= 2) {
            val descTokens = normalizeForFuzzy(description).split(" ").filter { it.length >= 3 }
            var matchedHolderTokens = 0
            for (hTok in holderTokens) {
                val bestTokScore = descTokens.maxOfOrNull { levenshteinRatio(hTok, it) } ?: 0
                if (bestTokScore >= fuzzyThreshold) matchedHolderTokens++
            }
            if (matchedHolderTokens == holderTokens.size) return true
        }

        return false
    }

    /**
     * Pure-Kotlin implementation of RapidFuzz `token_set_ratio` (0..100).
     * Handles Indian spelling variations like "JARUPULA RAJENDER" vs "JARPULA RAJENDAR".
     */
    fun tokenSetRatio(s1: String, s2: String): Int {
        val tokens1 = s1.split(" ").filter { it.isNotBlank() }.toSet()
        val tokens2 = s2.split(" ").filter { it.isNotBlank() }.toSet()
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0

        val intersection = tokens1.intersect(tokens2).sorted()
        val diff1 = (tokens1 - tokens2).sorted()
        val diff2 = (tokens2 - tokens1).sorted()

        val sortedSect = intersection.joinToString(" ")
        val combined1 = (intersection + diff1).joinToString(" ").trim()
        val combined2 = (intersection + diff2).joinToString(" ").trim()

        val scores = mutableListOf(
            levenshteinRatio(combined1, combined2)
        )
        if (sortedSect.isNotEmpty()) {
            scores.add(levenshteinRatio(sortedSect, combined1))
            scores.add(levenshteinRatio(sortedSect, combined2))
        }
        return scores.maxOrNull() ?: 0
    }

    fun levenshteinRatio(a: String, b: String): Int {
        if (a == b) return 100
        if (a.isEmpty() || b.isEmpty()) return 0
        val lenA = a.length
        val lenB = b.length
        var prev = IntArray(lenB + 1) { it }
        var curr = IntArray(lenB + 1)

        for (i in 1..lenA) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..lenB) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        val dist = prev[lenB]
        val maxLen = max(lenA, lenB)
        return (((maxLen - dist).toDouble() / maxLen.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun normalizeForFuzzy(input: String): String {
        return input.uppercase(Locale.ROOT)
            .replace(Regex("""[^A-Z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it !in NOISE_WORDS && !it.all { c -> c.isDigit() } }
            .joinToString(" ")
    }

    /**
     * Extracts clean counterparty name from Indian UPI, NEFT, RTGS, IMPS, and Cheque narrations.
     */
    fun extractCounterparty(description: String): String {
        val clean = description.trim()
        // Slash-separated formats: UPI/CR/123456/RAJESH TRADERS/HDFC..., NEFT/ICIC0001/SHREE BALAJI ENTERPRISES/...
        val slashParts = clean.split("/").map { it.trim() }.filter { it.isNotBlank() }
        if (slashParts.size >= 3) {
            val candidate = slashParts.drop(1).firstOrNull { seg ->
                val u = seg.uppercase(Locale.ROOT)
                seg.length >= 3 &&
                    !u.matches(Regex("""^(DR|CR|P2A|P2M|P2P|UPI|NEFT|RTGS|IMPS|INB|MOB|NACH|ACH|ECS|SBIN|HDFC|ICIC|UTIB|KKBK|UBIN|YESB|BARB|CNRB|PYTM|OKICICI|OKHDFCBANK|OKAXIS|OKSBI|YBL|IBL|AXL)$""")) &&
                    !u.matches(Regex("""^[A-Z]{4}0[A-Z0-9]{6}$""")) &&
                    !u.matches(Regex("""^[A-Z0-9]*\d{5,}[A-Z0-9]*$""")) &&
                    !u.contains("@") &&
                    u.count { it.isLetter() } >= 3
            }
            if (!candidate.isNullOrBlank()) {
                return toTitleCase(candidate.take(36))
            }
        }

        // Dash-separated formats: NEFT DR-SBIN0001234-VENKATESHWARA STEELS-INV12
        val dashParts = clean.split("-").map { it.trim() }.filter { it.isNotBlank() }
        if (dashParts.size >= 2) {
            val candidate = dashParts.drop(1).firstOrNull { seg ->
                val u = seg.uppercase(Locale.ROOT)
                seg.length >= 3 &&
                    !u.matches(Regex("""^[A-Z]{4}0[A-Z0-9]{6}$""")) &&
                    !u.matches(Regex("""^[A-Z0-9]*\d{5,}[A-Z0-9]*$""")) &&
                    u.count { it.isLetter() } >= 3
            }
            if (!candidate.isNullOrBlank()) {
                return toTitleCase(candidate.take(36))
            }
        }

        // Fallback: "TO <NAME>" or "FROM <NAME>" or "BY <NAME>"
        val regexMatch = Regex("""(?i)\b(?:TO|FROM|BY)\s+([A-Za-z][A-Za-z0-9 &.\-]{2,32})""").find(clean)
        if (regexMatch != null) {
            val cand = regexMatch.groupValues[1].trim()
            if (!cand.equals("SELF", ignoreCase = true) && !cand.equals("TRANSFER", ignoreCase = true)) {
                return toTitleCase(cand)
            }
        }
        return ""
    }

    private val INTERNAL_CREDIT_REGEX = Regex(
        """\b(SELF\s*TRANSFER|TRF\s*FROM\s*SELF|OWN\s*ACCOUNT|OWN\s*A/C|INTERNAL\s*CREDIT|INTERNAL\s*TRF|INTER\s*ACCOUNT|SELF\s*CREDIT|SWEEP\s*IN|AUTO\s*SWEEP|INTRA\s*BANK\s*SELF)\b""",
        RegexOption.IGNORE_CASE
    )

    private val CHEQUE_BOUNCE_REGEX = Regex(
        """\b(I/W\s*CHQ\s*RET|O/W\s*CHQ\s*RET|CHQ\s*RET|CHQ\s*RTN|CHQ\s*BOUNCE|CHEQUE\s*BOUNCE|CHEQUE\s*RET|CLG\s*RET|INWARD\s*RET|CHEQUE\s*DISHON|CHQ\s*DISHON|RETURN\s*CHQ)\b""",
        RegexOption.IGNORE_CASE
    )

    private val EMI_RETURN_REGEX = Regex(
        """\b(NACH\s*RET|NACH\s*RTN|NACH\s*BOUNCE|ECS\s*RET|ECS\s*RTN|ECS\s*BOUNCE|ACH\s*RET|ACH\s*RTN|ACH\s*BOUNCE|EMI\s*RET|EMI\s*RTN|EMI\s*BOUNCE|MANDATE\s*FAIL|AUTO\s*DEBIT\s*FAIL|SI\s*FAIL|LOAN\s*RETURN|EMI\s*UNPAID)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Identifies Internal / Self Credits (credits from own/linked accounts or internal sweeps). */
    fun isInternalCredit(txn: BankTransaction): Boolean {
        if (txn.amount <= 0.0) return false
        return txn.isSelfTransfer ||
            txn.category.equals("Transfer from Self", ignoreCase = true) ||
            INTERNAL_CREDIT_REGEX.containsMatchIn(txn.description)
    }

    /** Identifies Cheque Bounce / Inward or Outward Cheque Return events. */
    fun isChequeBounce(txn: BankTransaction): Boolean {
        if (CHEQUE_BOUNCE_REGEX.containsMatchIn(txn.description)) return true
        val u = txn.description.uppercase(Locale.ROOT)
        return (u.contains("CHQ") || u.contains("CHEQUE")) &&
            (u.contains("BOUNCE") || u.contains("INSUFFICIENT") || u.contains("DISHON") || u.contains("RETURN"))
    }

    /** Identifies EMI / NACH / ECS / ACH Mandate Return events. */
    fun isEmiReturn(txn: BankTransaction): Boolean {
        if (EMI_RETURN_REGEX.containsMatchIn(txn.description)) return true
        val u = txn.description.uppercase(Locale.ROOT)
        return (u.contains("NACH") || u.contains("ECS") || u.contains("ACH") || u.contains("EMI") || u.contains("LOAN")) &&
            (u.contains("RTN") || u.contains("RET") || u.contains("BOUNCE") || u.contains("FAIL") || u.contains("INSUFFICIENT"))
    }

    private fun toTitleCase(input: String): String {
        return input.trim()
            .replace(Regex("""\s+"""), " ")
            .split(" ")
            .joinToString(" ") { word ->
                if (word.length <= 2) word.uppercase(Locale.ROOT)
                else word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
    }
}
