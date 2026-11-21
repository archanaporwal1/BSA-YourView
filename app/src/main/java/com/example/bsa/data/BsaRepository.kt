package com.example.bsa.data

import android.content.Context
import android.net.Uri
import com.example.bsa.engine.*
import com.example.bsa.model.*
import com.example.bsa.service.BankStatementParser
import com.example.bsa.service.ExcelReportGenerator
import com.example.bsa.service.OnlineEnrichmentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class PipelineExecutionResult(
    val report: AnalysisReport,
    val baseMetadata: AccountMetadata,
    val fullUnfilteredTransactions: List<BankTransaction>,
    val verifiedHolidaysByIso: Map<String, String> = emptyMap()
)

class BsaRepository(
    private val context: Context,
    private val dao: SavedCaseDao,
    private val statementParser: BankStatementParser = BankStatementParser(),
    private val excelReportGenerator: ExcelReportGenerator = ExcelReportGenerator(),
    private val onlineEnrichmentService: OnlineEnrichmentService = OnlineEnrichmentService()
) {
    val savedCases: Flow<List<SavedCaseSummary>> = dao.observeAllSummaries()

    suspend fun probePasswordProtected(uriString: String): Boolean = withContext(Dispatchers.Default) {
        val bytes = readPdfBytes(uriString)
        ForensicGatekeeper.isPdfPasswordProtected(bytes)
    }

    suspend fun verifyFilePassword(uriString: String, password: String): Boolean = withContext(Dispatchers.Default) {
        val bytes = readPdfBytes(uriString)
        ForensicGatekeeper.verifyPdfPassword(bytes, password)
    }

    suspend fun runFullPipeline(
        stagedFiles: List<StagedPdfFile>,
        config: ApplicantConfig,
        settings: AppSettings = AppSettings(),
        isDeviceOnline: Boolean = false,
        onStageUpdate: (String) -> Unit
    ): PipelineExecutionResult = withContext(Dispatchers.Default) {
        if (stagedFiles.isEmpty()) {
            throw IllegalArgumentException("Attach at least one bank statement ePDF to run analysis.")
        }

        val parsedFiles = mutableListOf<ParsedStatementFile>()
        val audits = mutableListOf<FileForensicAudit>()
        var totalRecoveredRows = 0

        // Stage 1 & 2: PDFBox Forensic Gatekeeper & BankStatementParser (+ Cloud Layout Recovery if 0 rows online)
        stagedFiles.forEachIndexed { idx, file ->
            onStageUpdate("Stage 1/5: Forensic Audit (${idx + 1}/${stagedFiles.size}) — ${file.displayName}")
            val bytes = readPdfBytes(file.uriString)
            val extraction = ForensicGatekeeper.inspectAndExtract(
                fileName = file.displayName,
                pdfBytes = bytes,
                password = file.password
            )

            if (settings.strictForensicBlocking && extraction.audit.status == ForensicStatus.REJECTED) {
                throw PdfIntegrityException(
                    fileName = file.displayName,
                    message = "Strict Forensic Mode blocked ${file.displayName}: ${extraction.audit.findings.joinToString(" ")}"
                )
            }

            onStageUpdate("Stage 2/5: BankStatementParser (PDFTextStripper) — ${file.displayName}")
            var parsed = statementParser.parseRawStatementFile(
                fileName = file.displayName,
                extraction = extraction,
                facilityType = config.facilityType
            )

            // Online Hybrid Fallback: recover non-standard layouts if deterministic parser extracted 0 rows
            if (parsed.transactions.isEmpty() && isDeviceOnline && settings.onlineAccuracyBoostEnabled && settings.cloudAiAssistEnabled) {
                val (recoveredParsed, recoveredCount) = onlineEnrichmentService.attemptLayoutRecoveryIfNeeded(
                    parsed = parsed,
                    rawPdfText = extraction.fullText,
                    facilityType = config.facilityType,
                    isDeviceOnline = isDeviceOnline,
                    onlineBoostEnabled = settings.onlineAccuracyBoostEnabled,
                    cloudAiAssistEnabled = settings.cloudAiAssistEnabled,
                    onStageUpdate = onStageUpdate
                )
                parsed = recoveredParsed
                totalRecoveredRows += recoveredCount
            }

            parsedFiles.add(parsed)
            audits.add(
                extraction.audit.copy(
                    detectedBank = parsed.metadata.bankName,
                    accountNumber = parsed.metadata.accountNumber,
                    statementPeriod = parsed.metadata.statementPeriod
                )
            )
        }

        // Stage 3: Deduplicate overlapping dates, normalize polarity & categorize transactions
        onStageUpdate("Stage 3/5: Merging statements, fuzzy self-matching (${settings.fuzzySelfMatchThreshold}%) & categorizing...")
        val (rawMeta, rawCategorizedTxns) = statementParser.mergeAndNormalize(
            parsedFiles = parsedFiles,
            fuzzyThreshold = settings.fuzzySelfMatchThreshold
        )

        // Stage 4: Online Hybrid Accuracy Boost (RBI IFSC Verification, Counterparty Bank Enrichment, Cloud AI Narration Assist, State Bank Holidays)
        val enrichment = onlineEnrichmentService.enrichIfOnline(
            isDeviceOnline = isDeviceOnline,
            onlineBoostEnabled = settings.onlineAccuracyBoostEnabled,
            cloudAiAssistEnabled = settings.cloudAiAssistEnabled,
            metadata = rawMeta,
            transactions = rawCategorizedTxns,
            recoveredRowsCount = totalRecoveredRows,
            onStageUpdate = onStageUpdate
        )
        val consolidatedMeta = enrichment.metadata
        val categorizedTxns = enrichment.transactions

        // Stage 5: EOD Resampling, OD Utilization & 10-Point FraudScreeningService
        onStageUpdate("Stage 5/5: Running 10-point FraudScreeningService & exact-date OD analytics...")
        val report = UnderwritingAnalyticsEngine.analyze(
            config = config,
            metadata = consolidatedMeta,
            fileAudits = audits,
            transactions = categorizedTxns,
            autoSuppressOdNegativeEod = settings.autoSuppressOdNegativeEod,
            extraBankHolidaysByIso = enrichment.verifiedHolidaysByIso,
            onlineEnrichment = enrichment.summary
        )

        saveReport(report)
        PipelineExecutionResult(
            report = report,
            baseMetadata = consolidatedMeta,
            fullUnfilteredTransactions = categorizedTxns,
            verifiedHolidaysByIso = enrichment.verifiedHolidaysByIso
        )
    }

    suspend fun saveReport(report: AnalysisReport) = withContext(Dispatchers.IO) {
        val json = AnalysisReportSerializer.toJson(report)
        dao.upsertCase(
            SavedCaseEntity(
                reportId = report.reportId,
                applicantId = report.config.applicantId,
                holderName = report.metadata.holderName,
                bankName = report.metadata.bankName,
                accountNumber = report.metadata.accountNumber,
                statementPeriod = report.metadata.statementPeriod,
                productType = report.config.productType.displayName,
                facilityType = report.config.facilityType.code,
                forensicStatus = report.overallForensicStatus.name,
                flaggedCount = report.flaggedFraudCount,
                transactionCount = report.transactions.size,
                generatedAtMillis = report.generatedAtMillis,
                reportJson = json
            )
        )
    }

    suspend fun loadReportById(reportId: String): AnalysisReport? = withContext(Dispatchers.IO) {
        val entity = dao.getCaseById(reportId) ?: return@withContext null
        AnalysisReportSerializer.fromJson(entity.reportJson)
    }

    suspend fun deleteCase(reportId: String) = withContext(Dispatchers.IO) {
        dao.deleteById(reportId)
    }

    suspend fun clearAllCases() = withContext(Dispatchers.IO) {
        dao.deleteAll()
    }

    suspend fun exportExcelToUri(
        report: AnalysisReport,
        targetUri: Uri,
        excelConfig: ExcelExportConfig = ExcelExportConfig(),
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val os = context.contentResolver.openOutputStream(targetUri)
            ?: throw IllegalStateException("Could not open destination file for writing.")
        os.use { stream ->
            excelReportGenerator.generateToStream(report, stream, excelConfig, onProgress)
        }
    }

    suspend fun exportExcelToCacheFile(
        report: AnalysisReport,
        excelConfig: ExcelExportConfig = ExcelExportConfig(),
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeId = report.config.applicantId
            .replace(Regex("""[^A-Za-z0-9_-]"""), "_")
            .ifBlank { "Report" }
        val outFile = File(dir, "BSA_YourView_${safeId}.xlsx")
        excelReportGenerator.generateToFile(report, outFile, excelConfig, onProgress)
    }

    suspend fun generateSampleStatements(): List<StagedPdfFile> = withContext(Dispatchers.IO) {
        SamplePdfBuilder.generateSequentialSampleStatements(context)
    }

    private suspend fun readPdfBytes(uriString: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            file.readBytes()
        } else {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to read PDF bytes from selected file.")
        }
    }
}
