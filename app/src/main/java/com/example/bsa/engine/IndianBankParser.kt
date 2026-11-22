package com.example.bsa.engine

import com.example.bsa.model.AccountMetadata
import com.example.bsa.model.FacilityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

data class RawParsedTransaction(
    val dateIso: String,
    val displayDate: String,
    val chequeNo: String,
    val description: String,
    val withdrawal: Double?,
    val deposit: Double?,
    val signedAmount: Double,
    val normalizedBalance: Double, // Negative for Dr (overdrawn), Positive for Cr
    val balancePolarityExplicit: String?, // "DR", "CR", or null
    val sourceFile: String
)

data class ParsedStatementFile(
    val fileName: String,
    val metadata: AccountMetadata,
    val transactions: List<RawParsedTransaction>
)

/**
 * Module B: Deterministic Indian Bank ePDF Table Parser (PDFBox-powered, Background-Thread Safe)
 * - Auto-detects Indian banks (ICICI, HDFC, SBI, Axis, Kotak Mahindra, Union Bank + major PSUs/private banks)
 * - Extracts header metadata (Holder Name, Address, Account No, IFSC, Statement Period)
 * - Primary strategy: Coordinate-aligned PDFBox table extraction (maps header X-columns for Date,
 *   Particulars, Cheque/Ref, Withdrawal/Debit, Deposit/Credit, Balance) with multi-line narration stitching
 * - Fallback strategy: Deterministic line-based regex parser when statements lack tabular headers
 * - Deduplicates overlapping transactions across sequential multi-PDF uploads
 * - Normalizes OD/CC Dr(-)/Cr(+) balance polarity & validates Prev_Balance + Signed_Amount == Current_Balance
 */
object IndianBankParser {

    private val MONTH_MAP = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "SEPT" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
    )
    private val MONTH_ABBR = arrayOf(
        "", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    private val DATE_START_REGEX = Regex(
        """^\s*(?:\d+\s+)?(\d{1,2}[-/\s](?:\d{1,2}|[A-Za-z]{3,4})[-/\s]\d{2,4})\b(.*)$"""
    )

    private val EXACT_DATE_REGEX = Regex(
        """^\s*(\d{1,2}[-/\s](?:\d{1,2}|[A-Za-z]{3,4})[-/\s]\d{2,4})\s*$"""
    )

    private val AMOUNT_TOKEN_REGEX = Regex(
        """(?<![A-Za-z0-9/])([+-]?(?:\d{1,3}(?:,\d{2,3})+|\d+)\.\d{2})(?:\s*\(?\s*(Dr|Cr|DR|CR|dr|cr)\.?\s*\)?)?(?![A-Za-z0-9.])"""
    )

    private val IFSC_REGEX = Regex("""\b([A-Z]{4}0[A-Z0-9]{6})\b""")
    private val ACCOUNT_NO_REGEX = Regex(
        """(?i)(?:Account\s*(?:No|Number|#)|A/c\s*(?:No|Number)?|Acct\s*No)[\s.:#-]*([X*\d]{8,20})"""
    )
    private val HOLDER_NAME_REGEX = Regex(
        """(?i)(?:Account\s*Holder(?:'s)?\s*Name|Customer\s*Name|Name\s*of\s*(?:Account\s*Holder|Customer)|A/c\s*Holder|Name)\s*[:\-]\s*([A-Z0-9 .&'/\-]{3,70})"""
    )
    private val ADDRESS_REGEX = Regex(
        """(?i)(?:Address|Communication\s*Address|Branch\s*Address)\s*[:\-]\s*(.{8,140})"""
    )
    private val PERIOD_REGEX = Regex(
        """(?i)(?:Statement\s*Period|Period|From)\s*[:\-]?\s*(\d{1,2}[-/\s](?:\d{1,2}|[A-Za-z]{3})[-/\s]\d{2,4})\s*(?:to|-|–)\s*(\d{1,2}[-/\s](?:\d{1,2}|[A-Za-z]{3})[-/\s]\d{2,4})"""
    )
    private val CHEQUE_REGEX = Regex("""\b(?:CHQ|CHEQUE|REF|INST)[\s.:/-]*(\d{6,10})\b""", RegexOption.IGNORE_CASE)

    private data class ColumnLayout(
        val dateMaxX: Float,
        val debitCenterX: Float?,
        val creditCenterX: Float?,
        val balanceMinX: Float,
        val chequeCenterX: Float?
    )

    /**
     * Background-thread entry point accepting PDFBox ForensicExtractionResult (both visualRows and fullText).
     */
    suspend fun parseStatementAsync(
        fileName: String,
        extraction: ForensicExtractionResult,
        facilityType: FacilityType
    ): ParsedStatementFile = withContext(Dispatchers.Default) {
        parseStatement(
            fileName = fileName,
            fullText = extraction.fullText,
            facilityType = facilityType,
            visualRows = extraction.visualRows
        )
    }

    fun parseStatement(
        fileName: String,
        fullText: String,
        facilityType: FacilityType,
        visualRows: List<PdfVisualRow> = emptyList()
    ): ParsedStatementFile {
        val bankName = detectBank(fullText)
        val lines = fullText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val metadata = extractMetadata(bankName, fullText, lines)

        // Strategy 1: Coordinate-aligned PDFBox table extraction (accurate even for blank Debit/Credit cells)
        val coordRows = if (visualRows.isNotEmpty()) {
            extractTransactionsFromVisualRows(fileName, visualRows, facilityType)
        } else {
            emptyList()
        }

        // Strategy 2: Line-based parser fallback if coordinate table detection yields fewer rows
        val lineRows = extractTransactionsFromLines(fileName, lines, facilityType)
        val chosenRows = if (coordRows.size >= lineRows.size && coordRows.isNotEmpty()) {
            coordRows
        } else {
            lineRows
        }

        val finalPeriod = if (metadata.statementPeriod == "-" && chosenRows.isNotEmpty()) {
            "${chosenRows.first().displayDate} to ${chosenRows.last().displayDate}"
        } else {
            metadata.statementPeriod
        }

        return ParsedStatementFile(
            fileName = fileName,
            metadata = metadata.copy(statementPeriod = finalPeriod),
            transactions = chosenRows
        )
    }

    fun detectBank(text: String): String {
        val preTableHeader = extractPreTableHeader(text)
        detectBankInChunk(preTableHeader)?.let { return it }
        return detectBankInChunk(text.uppercase(Locale.ROOT)) ?: "Indian Scheduled Bank"
    }

    private fun extractPreTableHeader(text: String): String {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").lines()
        val headerLines = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (DATE_START_REGEX.containsMatchIn(trimmed) && !trimmed.contains("Statement Period", ignoreCase = true)) {
                break
            }
            headerLines.add(trimmed)
            if (headerLines.size >= 15) break
        }
        return headerLines.joinToString("\n").uppercase(Locale.ROOT)
    }

    private fun detectBankInChunk(upper: String): String? {
        val bankNameTokens = listOf(
            "ICICI BANK" to "ICICI Bank",
            "HDFC BANK" to "HDFC Bank",
            "STATE BANK OF INDIA" to "SBI",
            "AXIS BANK" to "Axis Bank",
            "KOTAK MAHINDRA" to "Kotak Mahindra Bank",
            "UNION BANK OF INDIA" to "Union Bank",
            "BANK OF BARODA" to "Bank of Baroda",
            "CANARA BANK" to "Canara Bank",
            "PUNJAB NATIONAL BANK" to "Punjab National Bank",
            "INDUSIND BANK" to "IndusInd Bank",
            "IDFC FIRST" to "IDFC FIRST Bank",
            "YES BANK" to "Yes Bank",
            "FEDERAL BANK" to "Federal Bank",
            "INDIAN BANK" to "Indian Bank",
            "BANK OF INDIA" to "Bank of India",
            "CENTRAL BANK OF INDIA" to "Central Bank of India",
            "INDIAN OVERSEAS BANK" to "Indian Overseas Bank",
            "UCO BANK" to "UCO Bank",
            "BANK OF MAHARASHTRA" to "Bank of Maharashtra",
            "PUNJAB AND SIND BANK" to "Punjab & Sind Bank",
            "IDBI BANK" to "IDBI Bank",
            "RBL BANK" to "RBL Bank",
            "BANDHAN BANK" to "Bandhan Bank",
            "AU SMALL FINANCE" to "AU Small Finance Bank",
            "SOUTH INDIAN BANK" to "South Indian Bank",
            "KARUR VYSYA BANK" to "Karur Vysya Bank",
            "CITY UNION BANK" to "City Union Bank",
            "STANDARD CHARTERED" to "Standard Chartered Bank"
        )
        // 1. Prefer earliest explicit bank name appearance in the header
        val earliestName = bankNameTokens
            .mapNotNull { (token, name) ->
                val idx = upper.indexOf(token)
                if (idx >= 0) idx to name else null
            }
            .minByOrNull { it.first }
        if (earliestName != null) return earliestName.second

        // 2. Next check IFSC / short tokens by earliest occurrence
        val ifscTokens = listOf(
            "ICIC0" to "ICICI Bank",
            "HDFC0" to "HDFC Bank",
            "SBIN0" to "SBI",
            "UTIB0" to "Axis Bank",
            "KKBK0" to "Kotak Mahindra Bank",
            "UBIN0" to "Union Bank",
            "BARB0" to "Bank of Baroda",
            "CNRB0" to "Canara Bank",
            "PUNB0" to "Punjab National Bank",
            "INDB0" to "IndusInd Bank",
            "IDFB0" to "IDFC FIRST Bank",
            "YESB0" to "Yes Bank",
            "FDRL0" to "Federal Bank",
            "IDIB0" to "Indian Bank",
            "BKID0" to "Bank of India",
            "CBIN0" to "Central Bank of India",
            "IOBA0" to "Indian Overseas Bank",
            "UCBA0" to "UCO Bank",
            "MAHB0" to "Bank of Maharashtra",
            "PSIB0" to "Punjab & Sind Bank",
            "IBKL0" to "IDBI Bank",
            "RATN0" to "RBL Bank",
            "BDBL0" to "Bandhan Bank",
            "AUBL0" to "AU Small Finance Bank",
            "SIBL0" to "South Indian Bank",
            "KVBL0" to "Karur Vysya Bank",
            "CIUB0" to "City Union Bank",
            "SCBL0" to "Standard Chartered Bank"
        )
        val earliestIfsc = ifscTokens
            .mapNotNull { (token, name) ->
                val idx = upper.indexOf(token)
                if (idx >= 0) idx to name else null
            }
            .minByOrNull { it.first }
        if (earliestIfsc != null) return earliestIfsc.second

        if (Regex("""\bSBI\b""").containsMatchIn(upper)) return "SBI"
        return null
    }

    private fun extractMetadata(bankName: String, fullText: String, lines: List<String>): AccountMetadata {
        val headerSample = lines.take(45).joinToString("\n")
        val ifsc = IFSC_REGEX.find(headerSample)?.groupValues?.get(1)
            ?: IFSC_REGEX.find(fullText)?.groupValues?.get(1)
            ?: "-"
        val accountNo = ACCOUNT_NO_REGEX.find(headerSample)?.groupValues?.get(1)?.trim()
            ?: Regex("""\b\d{11,16}\b""").find(headerSample)?.value
            ?: "-"
        val holderName = HOLDER_NAME_REGEX.find(headerSample)?.groupValues?.get(1)
            ?.replace(Regex("""\s+(?:Account|IFSC|Branch|Customer|Cust).*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "Account Holder"
        val address = ADDRESS_REGEX.find(headerSample)?.groupValues?.get(1)?.trim() ?: "India"
        val periodMatch = PERIOD_REGEX.find(headerSample)
        val period = if (periodMatch != null) {
            "${periodMatch.groupValues[1].trim()} to ${periodMatch.groupValues[2].trim()}"
        } else {
            "-"
        }

        return AccountMetadata(
            bankName = bankName,
            holderName = holderName,
            address = address,
            accountNumber = accountNo,
            ifscCode = ifsc,
            statementPeriod = period
        )
    }

    /**
     * Coordinate-aware PDFBox table extractor:
     * Detects column header X-coordinates on each page and maps Debit / Credit / Balance / Narration
     * cells deterministically by horizontal geometry, stitching wrapped multi-line descriptions.
     */
    private fun extractTransactionsFromVisualRows(
        fileName: String,
        visualRows: List<PdfVisualRow>,
        facilityType: FacilityType
    ): List<RawParsedTransaction> {
        var layout: ColumnLayout? = null
        val results = mutableListOf<RawParsedTransaction>()
        var prevBalance: Double? = null

        for (row in visualRows) {
            val joined = row.joinedLine()
            if (joined.isBlank() || isNoiseLine(joined)) continue

            // Detect table column header row and calibrate column X-boundaries
            val detectedLayout = detectColumnLayout(row)
            if (detectedLayout != null) {
                layout = detectedLayout
                continue
            }

            if (isFooterOrSummaryLine(joined)) continue
            val activeLayout = layout ?: continue

            // Check whether the left-most cell begins with a valid Indian date
            val firstCell = row.cells.firstOrNull() ?: continue
            val dateExtraction = extractLeadingDate(firstCell.text)

            if (dateExtraction != null && firstCell.x <= activeLayout.dateMaxX + 45f) {
                val (parsedDate, remainderInFirstCell) = dateExtraction
                if (remainderInFirstCell.startsWith("to ", ignoreCase = true)) continue

                val descParts = mutableListOf<String>()
                if (remainderInFirstCell.isNotBlank()) descParts.add(remainderInFirstCell)

                var chequeNo = ""
                var debitVal: Double? = null
                var creditVal: Double? = null
                var balanceVal: Double? = null
                var balancePolarity: String? = null
                val unmappedAmounts = mutableListOf<Pair<Float, MatchResult>>()

                for (cellIdx in 1 until row.cells.size) {
                    val cell = row.cells[cellIdx]
                    val cellCenterX = (cell.x + cell.endX) / 2f
                    val trimmed = cell.text.trim()
                    if (trimmed.isEmpty()) continue

                    // Skip optional Value Date column right after Txn Date
                    if (cellIdx == 1 && EXACT_DATE_REGEX.matches(trimmed) && parseIndianDate(trimmed) != null) {
                        continue
                    }

                    // Check if cell lies in monetary columns (Debit / Credit / Balance)
                    val amtMatches = AMOUNT_TOKEN_REGEX.findAll(trimmed).toList()
                    val isRightSideNumeric = amtMatches.isNotEmpty() && cell.x >= (
                        (activeLayout.debitCenterX ?: activeLayout.creditCenterX ?: activeLayout.balanceMinX) - 65f
                    )

                    if (isRightSideNumeric) {
                        for (m in amtMatches) {
                            val numVal = parseIndianAmount(m.groupValues[1]) ?: continue
                            val pol = m.groupValues[2].uppercase(Locale.ROOT).ifBlank { null }

                            when {
                                cellCenterX >= activeLayout.balanceMinX - 28f -> {
                                    balanceVal = numVal
                                    balancePolarity = pol
                                }
                                activeLayout.debitCenterX != null && activeLayout.creditCenterX != null -> {
                                    val distDr = abs(cellCenterX - activeLayout.debitCenterX)
                                    val distCr = abs(cellCenterX - activeLayout.creditCenterX)
                                    if (distDr <= distCr) {
                                        if (numVal > 0.0 || debitVal == null) debitVal = numVal
                                    } else {
                                        if (numVal > 0.0 || creditVal == null) creditVal = numVal
                                    }
                                }
                                else -> unmappedAmounts.add(cellCenterX to m)
                            }
                        }
                    } else {
                        // Cheque/Ref column or Narration column
                        if (activeLayout.chequeCenterX != null &&
                            abs(cellCenterX - activeLayout.chequeCenterX) <= 35f &&
                            trimmed.matches(Regex("""^[A-Za-z0-9/-]{5,14}$"""))
                        ) {
                            chequeNo = trimmed
                        } else {
                            descParts.add(trimmed)
                        }
                    }
                }

                // If balance wasn't captured via exact column, fall back to rightmost unmapped monetary token
                if (balanceVal == null && unmappedAmounts.isNotEmpty()) {
                    val last = unmappedAmounts.removeAt(unmappedAmounts.lastIndex).second
                    balanceVal = parseIndianAmount(last.groupValues[1])
                    balancePolarity = last.groupValues[2].uppercase(Locale.ROOT).ifBlank { null }
                }
                val rawBal = balanceVal ?: continue

                val normalizedBalance = resolveBalancePolarity(
                    rawBalance = rawBal,
                    balPolarity = balancePolarity,
                    wasNegativeLiteral = false,
                    facilityType = facilityType,
                    prevBalance = prevBalance
                )

                val description = cleanDescription(descParts.joinToString(" "))
                if (description.isBlank()) continue

                val dr = debitVal?.takeIf { it > 0.0 }
                val cr = creditVal?.takeIf { it > 0.0 }

                val signedAmount = when {
                    dr != null && cr == null -> -abs(dr)
                    cr != null && dr == null -> abs(cr)
                    unmappedAmounts.isNotEmpty() -> {
                        val m = unmappedAmounts.last().second
                        val mag = abs(parseIndianAmount(m.groupValues[1]) ?: 0.0)
                        inferSign(mag, m.groupValues[2], description, prevBalance, normalizedBalance)
                    }
                    else -> continue
                }

                val finalBalance = verifyOrFlipUnlabelledOdBalance(
                    normalizedBalance = normalizedBalance,
                    balPolarity = balancePolarity,
                    prevBalance = prevBalance,
                    signedAmount = signedAmount
                )

                val resolvedCheque = chequeNo.ifBlank {
                    CHEQUE_REGEX.find(description)?.groupValues?.get(1) ?: ""
                }

                results.add(
                    RawParsedTransaction(
                        dateIso = parsedDate.iso,
                        displayDate = parsedDate.display,
                        chequeNo = resolvedCheque,
                        description = description,
                        withdrawal = if (signedAmount < 0) abs(signedAmount) else null,
                        deposit = if (signedAmount >= 0) abs(signedAmount) else null,
                        signedAmount = signedAmount,
                        normalizedBalance = finalBalance,
                        balancePolarityExplicit = balancePolarity,
                        sourceFile = fileName
                    )
                )
                prevBalance = finalBalance
            } else if (results.isNotEmpty()) {
                // Multi-line wrapped narration continuation: stitch text falling in the description area
                val leftText = row.cells
                    .filter { it.x < (activeLayout.debitCenterX ?: activeLayout.balanceMinX) - 50f }
                    .joinToString(" ") { it.text.trim() }
                    .trim()
                if (leftText.isNotBlank() && AMOUNT_TOKEN_REGEX.find(leftText) == null) {
                    val last = results.last()
                    results[results.lastIndex] = last.copy(
                        description = cleanDescription("${last.description} $leftText")
                    )
                }
            }
        }

        return results
    }

    private fun detectColumnLayout(row: PdfVisualRow): ColumnLayout? {
        val joinedUpper = row.joinedLine().uppercase(Locale.ROOT)
        if (!joinedUpper.contains("DATE") || !joinedUpper.contains("BALANCE")) return null
        if (!(joinedUpper.contains("PARTICULAR") || joinedUpper.contains("NARRATION") || joinedUpper.contains("DESCRIPTION") || joinedUpper.contains("REMARK"))) {
            return null
        }

        var dateMaxX = 95f
        var debitCenter: Float? = null
        var creditCenter: Float? = null
        var balanceMinX = 450f
        var chequeCenter: Float? = null

        for (cell in row.cells) {
            val u = cell.text.uppercase(Locale.ROOT)
            val cx = (cell.x + cell.endX) / 2f
            when {
                u.contains("DATE") && !u.contains("VALUE") -> dateMaxX = maxOf(dateMaxX, cell.endX)
                u.contains("CHQ") || u.contains("CHEQUE") || u.contains("REF") -> chequeCenter = cx
                u.contains("WITHDRAW") || u.contains("DEBIT") || u == "DR" || u.contains("DR.") -> debitCenter = cx
                u.contains("DEPOSIT") || u.contains("CREDIT") || u == "CR" || u.contains("CR.") -> creditCenter = cx
                u.contains("BALANCE") -> balanceMinX = cell.x
            }
        }

        return ColumnLayout(
            dateMaxX = dateMaxX,
            debitCenterX = debitCenter,
            creditCenterX = creditCenter,
            balanceMinX = balanceMinX,
            chequeCenterX = chequeCenter
        )
    }

    private fun extractLeadingDate(cellText: String): Pair<ParsedDate, String>? {
        val exact = EXACT_DATE_REGEX.find(cellText)
        if (exact != null) {
            val d = parseIndianDate(exact.groupValues[1]) ?: return null
            return d to ""
        }
        val m = DATE_START_REGEX.find(cellText) ?: return null
        val d = parseIndianDate(m.groupValues[1]) ?: return null
        return d to m.groupValues[2].trim()
    }

    private fun extractTransactionsFromLines(
        fileName: String,
        lines: List<String>,
        facilityType: FacilityType
    ): List<RawParsedTransaction> {
        val stitchedBlocks = mutableListOf<Pair<ParsedDate, StringBuilder>>()
        var insideTable = false

        for (line in lines) {
            if (isNoiseLine(line)) continue
            if (isHeaderLine(line)) {
                insideTable = true
                continue
            }

            val dateMatch = DATE_START_REGEX.find(line)
            if (dateMatch != null) {
                val parsedDate = parseIndianDate(dateMatch.groupValues[1])
                if (parsedDate != null) {
                    val rest = dateMatch.groupValues[2].trim()
                    if (!rest.startsWith("to ", ignoreCase = true) && !line.contains("Statement Period", ignoreCase = true)) {
                        insideTable = true
                        stitchedBlocks.add(parsedDate to StringBuilder(rest))
                        continue
                    }
                }
            }

            if (insideTable && stitchedBlocks.isNotEmpty() && !isFooterOrSummaryLine(line)) {
                stitchedBlocks.last().second.append(" ").append(line)
            }
        }

        val results = mutableListOf<RawParsedTransaction>()
        var prevBalance: Double? = null

        for ((parsedDate, bodyBuilder) in stitchedBlocks) {
            var body = bodyBuilder.toString().replace(Regex("""\s+"""), " ").trim()
            val valueDateMatch = Regex("""^(\d{1,2}[-/\s](?:\d{1,2}|[A-Za-z]{3,4})[-/\s]\d{2,4})\s+(.*)$""").find(body)
            if (valueDateMatch != null && parseIndianDate(valueDateMatch.groupValues[1]) != null) {
                body = valueDateMatch.groupValues[2].trim()
            }

            val amountMatches = AMOUNT_TOKEN_REGEX.findAll(body).toList()
            if (amountMatches.size < 2) continue

            val balanceMatch = amountMatches.last()
            val rawBalance = parseIndianAmount(balanceMatch.groupValues[1]) ?: continue
            val balPolarity = balanceMatch.groupValues[2].uppercase(Locale.ROOT).ifBlank { null }

            val normalizedBalance = resolveBalancePolarity(
                rawBalance = rawBalance,
                balPolarity = balPolarity,
                wasNegativeLiteral = balanceMatch.groupValues[1].startsWith("-"),
                facilityType = facilityType,
                prevBalance = prevBalance
            )

            val amountTokensBeforeBal = amountMatches.dropLast(1)
            val firstAmtStartIndex = amountTokensBeforeBal.first().range.first
            val description = cleanDescription(body.substring(0, firstAmtStartIndex).trim())
            if (description.isBlank()) continue

            var withdrawal: Double? = null
            var deposit: Double? = null
            val signedAmount: Double

            if (amountTokensBeforeBal.size >= 2) {
                val penultimate = amountTokensBeforeBal[amountTokensBeforeBal.size - 2]
                val lastAmt = amountTokensBeforeBal.last()
                val v1 = parseIndianAmount(penultimate.groupValues[1]) ?: 0.0
                val v2 = parseIndianAmount(lastAmt.groupValues[1]) ?: 0.0

                when {
                    v1 > 0.0 && v2 == 0.0 -> {
                        withdrawal = v1
                        signedAmount = -abs(v1)
                    }
                    v1 == 0.0 && v2 > 0.0 -> {
                        deposit = v2
                        signedAmount = abs(v2)
                    }
                    else -> {
                        val candidate = abs(v2)
                        signedAmount = inferSign(candidate, lastAmt.groupValues[2], description, prevBalance, normalizedBalance)
                        if (signedAmount >= 0) deposit = candidate else withdrawal = candidate
                    }
                }
            } else {
                val amtMatch = amountTokensBeforeBal.last()
                val rawAmt = parseIndianAmount(amtMatch.groupValues[1]) ?: continue
                val amtPolarity = amtMatch.groupValues[2].uppercase(Locale.ROOT).ifBlank { null }
                signedAmount = when {
                    amtMatch.groupValues[1].startsWith("-") -> -abs(rawAmt)
                    amtPolarity == "DR" -> -abs(rawAmt)
                    amtPolarity == "CR" -> abs(rawAmt)
                    else -> inferSign(abs(rawAmt), null, description, prevBalance, normalizedBalance)
                }
                if (signedAmount >= 0) deposit = abs(signedAmount) else withdrawal = abs(signedAmount)
            }

            val finalBalance = verifyOrFlipUnlabelledOdBalance(
                normalizedBalance = normalizedBalance,
                balPolarity = balPolarity,
                prevBalance = prevBalance,
                signedAmount = signedAmount
            )

            val chequeNo = CHEQUE_REGEX.find(description)?.groupValues?.get(1) ?: ""

            results.add(
                RawParsedTransaction(
                    dateIso = parsedDate.iso,
                    displayDate = parsedDate.display,
                    chequeNo = chequeNo,
                    description = description,
                    withdrawal = withdrawal,
                    deposit = deposit,
                    signedAmount = signedAmount,
                    normalizedBalance = finalBalance,
                    balancePolarityExplicit = balPolarity,
                    sourceFile = fileName
                )
            )
            prevBalance = finalBalance
        }

        return results
    }

    private fun resolveBalancePolarity(
        rawBalance: Double,
        balPolarity: String?,
        wasNegativeLiteral: Boolean,
        facilityType: FacilityType,
        prevBalance: Double?
    ): Double = when (balPolarity) {
        "DR" -> -abs(rawBalance)
        "CR" -> abs(rawBalance)
        else -> {
            if (wasNegativeLiteral) {
                -abs(rawBalance)
            } else if (facilityType != FacilityType.NONE && rawBalance > 0 && prevBalance != null && prevBalance < 0) {
                -abs(rawBalance)
            } else {
                rawBalance
            }
        }
    }

    private fun verifyOrFlipUnlabelledOdBalance(
        normalizedBalance: Double,
        balPolarity: String?,
        prevBalance: Double?,
        signedAmount: Double
    ): Double {
        if (balPolarity != null || prevBalance == null) return normalizedBalance
        val expected = prevBalance + signedAmount
        return when {
            abs(expected - normalizedBalance) <= 0.10 -> normalizedBalance
            abs(expected - (-abs(normalizedBalance))) <= 0.10 -> -abs(normalizedBalance)
            else -> normalizedBalance
        }
    }

    private fun inferSign(
        magnitude: Double,
        explicitDrCr: String?,
        description: String,
        prevBalance: Double?,
        currBalance: Double
    ): Double {
        when (explicitDrCr?.uppercase(Locale.ROOT)) {
            "DR" -> return -magnitude
            "CR" -> return magnitude
        }
        if (prevBalance != null) {
            val delta = currBalance - prevBalance
            if (abs(delta - magnitude) <= 0.15) return magnitude
            if (abs(delta + magnitude) <= 0.15) return -magnitude
        }
        val upper = description.uppercase(Locale.ROOT)
        return if (
            upper.contains("/CR/") ||
            upper.contains("BY TRANSFER") ||
            upper.contains("CASH DEP") ||
            upper.contains("REFUND") ||
            upper.contains("REVERSAL") ||
            upper.contains("NEFT CR") ||
            upper.contains("IMPS CR") ||
            upper.contains("UPI/CR") ||
            upper.contains("INT.PD")
        ) {
            magnitude
        } else {
            -magnitude
        }
    }

    fun mergeAndDeduplicate(
        parsedFiles: List<ParsedStatementFile>
    ): Pair<AccountMetadata, List<Pair<RawParsedTransaction, Boolean>>> {
        val primaryMeta = parsedFiles.firstOrNull()?.metadata ?: AccountMetadata(
            bankName = "Unknown Bank",
            holderName = "Account Holder",
            address = "-",
            accountNumber = "-",
            ifscCode = "-",
            statementPeriod = "-"
        )

        val accountKeyByFile = parsedFiles.associate { pf ->
            val normAcc = pf.metadata.accountNumber.replace(Regex("""[^A-Za-z0-9]"""), "").uppercase(Locale.ROOT)
            val normBank = pf.metadata.bankName.trim().uppercase(Locale.ROOT)
            pf.fileName to "$normBank|$normAcc"
        }

        val seenKeys = LinkedHashSet<String>()
        val deduped = mutableListOf<RawParsedTransaction>()
        val orderedFiles = parsedFiles.sortedBy { it.transactions.firstOrNull()?.dateIso ?: "9999-99-99" }

        for (file in orderedFiles) {
            val accKey = accountKeyByFile[file.fileName] ?: file.fileName
            for (txn in file.transactions) {
                val normalizedDesc = txn.description.uppercase(Locale.ROOT)
                    .replace(Regex("""[^A-Z0-9]"""), "")
                    .take(40)
                val key = String.format(
                    Locale.ROOT,
                    "%s|%s|%.2f|%.2f|%s",
                    accKey,
                    txn.dateIso,
                    txn.signedAmount,
                    txn.normalizedBalance,
                    normalizedDesc
                )
                if (seenKeys.add(key)) {
                    deduped.add(txn)
                }
            }
        }

        val sorted = deduped.withIndex()
            .sortedWith(compareBy<IndexedValue<RawParsedTransaction>> { it.value.dateIso }.thenBy { it.index })
            .map { it.value }

        // Check running-balance continuity independently per distinct bank account
        val verified = mutableListOf<Pair<RawParsedTransaction, Boolean>>()
        val prevBalByAccount = mutableMapOf<String, Double>()
        for (txn in sorted) {
            val accKey = accountKeyByFile[txn.sourceFile] ?: "DEFAULT"
            val prevBal = prevBalByAccount[accKey]
            val mismatch = if (prevBal != null) {
                val expected = prevBal + txn.signedAmount
                abs(expected - txn.normalizedBalance) > 0.05
            } else {
                false
            }
            verified.add(txn to mismatch)
            prevBalByAccount[accKey] = txn.normalizedBalance
        }

        val consolidatedPeriod = if (sorted.isNotEmpty()) {
            "${sorted.first().displayDate} to ${sorted.last().displayDate}"
        } else {
            primaryMeta.statementPeriod
        }

        val distinctAccounts = parsedFiles
            .map { pf -> pf.metadata.bankName.trim() to pf.metadata.accountNumber.trim() }
            .distinctBy { (b, a) -> "${b.uppercase(Locale.ROOT)}|${a.replace(Regex("""[^A-Za-z0-9]"""), "")}" }

        val consolidatedMeta = if (distinctAccounts.size >= 2) {
            val bankNames = distinctAccounts.map { it.first }.distinct().joinToString(" + ")
            primaryMeta.copy(
                bankName = "$bankNames (${distinctAccounts.size} Accounts)",
                accountNumber = "${primaryMeta.accountNumber} (+${distinctAccounts.size - 1} more)",
                statementPeriod = consolidatedPeriod
            )
        } else {
            primaryMeta.copy(statementPeriod = consolidatedPeriod)
        }

        return consolidatedMeta to verified
    }

    private fun isHeaderLine(line: String): Boolean {
        val u = line.uppercase(Locale.ROOT)
        return (u.contains("DATE") && (u.contains("PARTICULARS") || u.contains("NARRATION") || u.contains("DESCRIPTION")) && u.contains("BALANCE"))
    }

    private fun isNoiseLine(line: String): Boolean {
        val u = line.uppercase(Locale.ROOT)
        return u.startsWith("PAGE ") ||
            u.contains("END OF STATEMENT") ||
            u.contains("COMPUTER GENERATED STATEMENT") ||
            u.contains("THIS IS AN AUTHENTICATED") ||
            u.startsWith("ACCOUNT HOLDER") ||
            u.startsWith("ACCOUNT NUMBER") ||
            u.startsWith("STATEMENT PERIOD") ||
            u.startsWith("CUSTOMER NAME") ||
            u.startsWith("BRANCH:") ||
            u.startsWith("ADDRESS:") ||
            u.startsWith("IFSC:") ||
            u.matches(Regex("""^[-=_*]{5,}$"""))
    }

    private fun isFooterOrSummaryLine(line: String): Boolean {
        val u = line.uppercase(Locale.ROOT)
        return u.startsWith("TOTAL") ||
            u.startsWith("OPENING BALANCE") ||
            u.startsWith("CLOSING BALANCE") ||
            u.startsWith("STATEMENT SUMMARY") ||
            u.startsWith("LEGENDS") ||
            u.startsWith("IMPORTANT:")
    }

    private fun cleanDescription(raw: String): String {
        return raw
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""^(?:DR|CR)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun parseIndianAmount(token: String): Double? {
        return token.replace(",", "").trim().toDoubleOrNull()
    }

    data class ParsedDate(val year: Int, val month: Int, val day: Int) {
        val iso: String = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
        val display: String = String.format(
            Locale.ROOT,
            "%02d-%s-%02d",
            day,
            MONTH_ABBR[month.coerceIn(1, 12)],
            year % 100
        )
    }

    fun parseIndianDate(token: String): ParsedDate? {
        val parts = token.trim().split(Regex("""[-/\s]+"""))
        if (parts.size != 3) return null

        val day = parts[0].toIntOrNull() ?: return null
        val monthStr = parts[1].uppercase(Locale.ROOT)
        val month = monthStr.toIntOrNull() ?: MONTH_MAP[monthStr] ?: return null
        var year = parts[2].toIntOrNull() ?: return null
        if (year < 100) year += 2000

        if (year !in 2000..2099 || month !in 1..12 || day !in 1..31) return null
        return ParsedDate(year, month, day)
    }
}
