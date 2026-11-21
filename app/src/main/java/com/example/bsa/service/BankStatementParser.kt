package com.example.bsa.service

import com.example.bsa.engine.ForensicExtractionResult
import com.example.bsa.engine.ForensicGatekeeper
import com.example.bsa.engine.FuzzyMatcherAndCategorizer
import com.example.bsa.engine.IndianBankParser
import com.example.bsa.engine.ParsedStatementFile
import com.example.bsa.engine.PdfVisualRow
import com.example.bsa.engine.RawParsedTransaction
import com.example.bsa.model.AccountMetadata
import com.example.bsa.model.BankTransaction
import com.example.bsa.model.FacilityType
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Bank-specific header & column fingerprint profile for deterministic Indian bank ePDF parsing.
 */
data class BankHeaderProfile(
    val bankName: String,
    val detectionTokens: List<String>,
    val ifscPrefix: String,
    val headerKeywords: List<String>,
    val typicalColumns: List<String>,
    val hasSeparateValueDate: Boolean
)

data class NormalizedStatementResult(
    val detectedProfile: BankHeaderProfile,
    val metadata: AccountMetadata,
    val transactions: List<BankTransaction>
)

/**
 * Dedicated BankStatementParser service using PDFBox's PDFTextStripper to:
 * 1. Identify bank-specific header formats (ICICI, HDFC, SBI, Axis, Kotak Mahindra, Union Bank, etc.)
 * 2. Extract transaction rows via coordinate-aligned PDFTextStripper + multi-line narration merging
 * 3. Normalize date, amount, and OD/CC balance columns into categorized List<BankTransaction> objects
 */
class BankStatementParser {

    companion object {
        val BANK_PROFILES: List<BankHeaderProfile> = listOf(
            BankHeaderProfile(
                bankName = "ICICI Bank",
                detectionTokens = listOf("ICICI BANK", "ICIC0", "TRANSACTION REMARKS"),
                ifscPrefix = "ICIC0",
                headerKeywords = listOf("VALUE DATE", "TRANSACTION DATE", "TRANSACTION REMARKS", "WITHDRAWAL AMOUNT", "DEPOSIT AMOUNT", "BALANCE"),
                typicalColumns = listOf("S No.", "Value Date", "Transaction Date", "Cheque Number", "Transaction Remarks", "Withdrawal Amount (INR)", "Deposit Amount (INR)", "Balance (INR)"),
                hasSeparateValueDate = true
            ),
            BankHeaderProfile(
                bankName = "HDFC Bank",
                detectionTokens = listOf("HDFC BANK", "HDFC0", "CHQ./REF.NO."),
                ifscPrefix = "HDFC0",
                headerKeywords = listOf("DATE", "NARRATION", "CHQ./REF.NO.", "VALUE DT", "WITHDRAWAL AMT", "DEPOSIT AMT", "CLOSING BALANCE"),
                typicalColumns = listOf("Date", "Narration", "Chq./Ref.No.", "Value Dt", "Withdrawal Amt.", "Deposit Amt.", "Closing Balance"),
                hasSeparateValueDate = true
            ),
            BankHeaderProfile(
                bankName = "SBI",
                detectionTokens = listOf("STATE BANK OF INDIA", "SBIN0", "TXN DATE"),
                ifscPrefix = "SBIN0",
                headerKeywords = listOf("TXN DATE", "VALUE DATE", "DESCRIPTION", "REF NO./CHEQUE NO.", "DEBIT", "CREDIT", "BALANCE"),
                typicalColumns = listOf("Txn Date", "Value Date", "Description", "Ref No./Cheque No.", "Debit", "Credit", "Balance"),
                hasSeparateValueDate = true
            ),
            BankHeaderProfile(
                bankName = "Axis Bank",
                detectionTokens = listOf("AXIS BANK", "UTIB0", "TRAN DATE"),
                ifscPrefix = "UTIB0",
                headerKeywords = listOf("TRAN DATE", "CHQ NO", "PARTICULARS", "DEBIT", "CREDIT", "BALANCE"),
                typicalColumns = listOf("Tran Date", "Chq No", "Particulars", "Debit", "Credit", "Balance"),
                hasSeparateValueDate = false
            ),
            BankHeaderProfile(
                bankName = "Kotak Mahindra Bank",
                detectionTokens = listOf("KOTAK MAHINDRA", "KKBK0", "WITHDRAWAL(DR)"),
                ifscPrefix = "KKBK0",
                headerKeywords = listOf("DATE", "NARRATION", "CHQ/REF NO", "WITHDRAWAL(DR)", "DEPOSIT(CR)", "BALANCE"),
                typicalColumns = listOf("Date", "Narration", "Chq/Ref No", "Withdrawal(Dr)", "Deposit(Cr)", "Balance"),
                hasSeparateValueDate = false
            ),
            BankHeaderProfile(
                bankName = "Union Bank",
                detectionTokens = listOf("UNION BANK OF INDIA", "UBIN0"),
                ifscPrefix = "UBIN0",
                headerKeywords = listOf("DATE", "PARTICULARS", "CHQ NO", "DEBIT", "CREDIT", "BALANCE"),
                typicalColumns = listOf("Date", "Particulars", "Chq No", "Debit", "Credit", "Balance"),
                hasSeparateValueDate = false
            )
        )

        val GENERIC_PROFILE = BankHeaderProfile(
            bankName = "Indian Scheduled Bank",
            detectionTokens = emptyList(),
            ifscPrefix = "",
            headerKeywords = listOf("DATE", "PARTICULARS", "NARRATION", "DESCRIPTION", "BALANCE"),
            typicalColumns = listOf("Date", "Description", "Debit", "Credit", "Balance"),
            hasSeparateValueDate = false
        )
    }

    /**
     * Identifies the matching bank-specific header format profile from extracted PDF text.
     */
    fun identifyBankHeaderProfile(fullText: String): BankHeaderProfile {
        val dateRowRegex = Regex("""^\s*(?:\d+\s+)?\d{1,2}[-/\s](?:\d{1,2}|[A-Za-z]{3,4})[-/\s]\d{2,4}\b""")
        val headerBlock = fullText.replace("\r\n", "\n").replace("\r", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeWhile { line ->
                !(dateRowRegex.containsMatchIn(line) && !line.contains("Statement Period", ignoreCase = true))
            }
            .take(15)
            .joinToString("\n")
            .uppercase(Locale.ROOT)

        // 1. Match earliest primary bank name token (index 0 of detectionTokens) in pre-table header
        val earliestNameMatch = BANK_PROFILES
            .mapNotNull { profile ->
                val primaryToken = profile.detectionTokens.firstOrNull() ?: return@mapNotNull null
                val idx = headerBlock.indexOf(primaryToken)
                if (idx >= 0) idx to profile else null
            }
            .minByOrNull { it.first }
        if (earliestNameMatch != null) return earliestNameMatch.second

        // 2. Match earliest IFSC / secondary token in pre-table header
        val earliestSecondaryMatch = BANK_PROFILES
            .mapNotNull { profile ->
                val idx = profile.detectionTokens.mapNotNull { t ->
                    val i = headerBlock.indexOf(t)
                    if (i >= 0) i else null
                }.minOrNull()
                if (idx != null) idx to profile else null
            }
            .minByOrNull { it.first }
        if (earliestSecondaryMatch != null) return earliestSecondaryMatch.second

        // 3. Fallback: match bank-specific column header combination in header block
        val bestHeaderMatch = BANK_PROFILES.maxByOrNull { profile ->
            profile.headerKeywords.count { kw -> headerBlock.contains(kw) }
        }
        val bestHeaderScore = bestHeaderMatch?.headerKeywords?.count { kw -> headerBlock.contains(kw) } ?: 0
        if (bestHeaderMatch != null && bestHeaderScore >= 4) return bestHeaderMatch

        // 4. Final fallback across full text by earliest occurrence
        val upper = fullText.uppercase(Locale.ROOT)
        val fullMatch = BANK_PROFILES
            .mapNotNull { profile ->
                val idx = profile.detectionTokens.mapNotNull { t ->
                    val i = upper.indexOf(t)
                    if (i >= 0) i else null
                }.minOrNull()
                if (idx != null) idx to profile else null
            }
            .minByOrNull { it.first }
        return fullMatch?.second ?: GENERIC_PROFILE
    }

    /**
     * Parses raw PDF bytes directly using PDFBox's PDFTextStripper on a background thread,
     * handling password unlocking, bank header identification, multi-line narration merging,
     * OD/CC polarity normalization, and transaction categorization.
     */
    suspend fun parsePdfBytes(
        fileName: String,
        pdfBytes: ByteArray,
        password: String = "",
        facilityType: FacilityType = FacilityType.NONE
    ): NormalizedStatementResult = withContext(Dispatchers.Default) {
        val extraction = ForensicGatekeeper.inspectAndExtract(
            fileName = fileName,
            pdfBytes = pdfBytes,
            password = password
        )
        parseFromExtraction(
            fileName = fileName,
            extraction = extraction,
            facilityType = facilityType
        )
    }

    /**
     * Directly extracts text using PDFBox's PDFTextStripper from an already-loaded PDDocument
     * on a background thread and parses normalized transactions.
     */
    suspend fun parseFromDocument(
        fileName: String,
        document: PDDocument,
        facilityType: FacilityType = FacilityType.NONE
    ): NormalizedStatementResult = withContext(Dispatchers.Default) {
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
            lineSeparator = "\n"
        }
        val extractedText = stripper.getText(document).orEmpty()
        parseFromTextAndRows(
            fileName = fileName,
            fullText = extractedText,
            visualRows = emptyList(),
            facilityType = facilityType
        )
    }

    /**
     * Parses from an existing ForensicExtractionResult (reusing the single-pass PDFBox
     * coordinate rows and fullText without reopening the PDF).
     */
    suspend fun parseFromExtraction(
        fileName: String,
        extraction: ForensicExtractionResult,
        facilityType: FacilityType
    ): NormalizedStatementResult = withContext(Dispatchers.Default) {
        parseFromTextAndRows(
            fileName = fileName,
            fullText = extraction.fullText,
            visualRows = extraction.visualRows,
            facilityType = facilityType
        )
    }

    /**
     * Parses raw parsed statement file for multi-PDF sequential merging workflows.
     */
    suspend fun parseRawStatementFile(
        fileName: String,
        extraction: ForensicExtractionResult,
        facilityType: FacilityType
    ): ParsedStatementFile = withContext(Dispatchers.Default) {
        val profile = identifyBankHeaderProfile(extraction.fullText)
        val parsed = IndianBankParser.parseStatement(
            fileName = fileName,
            fullText = extraction.fullText,
            facilityType = facilityType,
            visualRows = extraction.visualRows
        )
        val resolvedBank = if (parsed.metadata.bankName == "Indian Scheduled Bank" && profile.bankName != GENERIC_PROFILE.bankName) {
            profile.bankName
        } else {
            parsed.metadata.bankName
        }
        parsed.copy(metadata = parsed.metadata.copy(bankName = resolvedBank))
    }

    /**
     * Merges multiple sequential parsed files, deduplicates overlapping boundary dates,
     * reconciles Prev_Balance + Signed_Amount == Current_Balance, and categorizes all rows.
     */
    suspend fun mergeAndNormalize(
        parsedFiles: List<ParsedStatementFile>,
        fuzzyThreshold: Int = 82
    ): Pair<AccountMetadata, List<BankTransaction>> = withContext(Dispatchers.Default) {
        val (consolidatedMeta, verifiedRows) = IndianBankParser.mergeAndDeduplicate(parsedFiles)
        val categorized = FuzzyMatcherAndCategorizer.categorizeAll(
            holderName = consolidatedMeta.holderName,
            verifiedRows = verifiedRows,
            fuzzyThreshold = fuzzyThreshold
        )
        consolidatedMeta to categorized
    }

    private fun parseFromTextAndRows(
        fileName: String,
        fullText: String,
        visualRows: List<PdfVisualRow>,
        facilityType: FacilityType
    ): NormalizedStatementResult {
        val profile = identifyBankHeaderProfile(fullText)
        val parsed = IndianBankParser.parseStatement(
            fileName = fileName,
            fullText = fullText,
            facilityType = facilityType,
            visualRows = visualRows
        )
        val resolvedBank = if (parsed.metadata.bankName == "Indian Scheduled Bank" && profile.bankName != GENERIC_PROFILE.bankName) {
            profile.bankName
        } else {
            parsed.metadata.bankName
        }
        val fixedParsed = parsed.copy(metadata = parsed.metadata.copy(bankName = resolvedBank))
        val (meta, verified) = IndianBankParser.mergeAndDeduplicate(listOf(fixedParsed))
        val normalizedTxns = FuzzyMatcherAndCategorizer.categorizeAll(meta.holderName, verified)
        return NormalizedStatementResult(
            detectedProfile = profile,
            metadata = meta,
            transactions = normalizedTxns
        )
    }

    /**
     * Helper to parse directly from an unencrypted PDF ByteArray using standalone PDFTextStripper.
     */
    suspend fun extractPlainTextWithStripper(
        pdfBytes: ByteArray,
        password: String = ""
    ): String = withContext(Dispatchers.Default) {
        PDDocument.load(ByteArrayInputStream(pdfBytes), password).use { doc ->
            PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
            }.getText(doc).orEmpty()
        }
    }
}
