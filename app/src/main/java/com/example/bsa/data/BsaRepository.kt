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
    val verifiedHolidaysByIso: Map<String, String> = emptyMap(),
    val skippedFailedFiles: List<Pair<String, String>> = emptyList()
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
        onStageUpdate: (String) -> Unit,
        onProgressUpdate: (Float) -> Unit = {},
        onFileStatusUpdate: (fileId: String, status: QueueFileStatus, note: String?, detectedBank: String?, txnCount: Int?) -> Unit = { _, _, _, _, _ -> }
    ): PipelineExecutionResult = withContext(Dispatchers.Default) {
        if (stagedFiles.isEmpty()) {
            throw IllegalArgumentException("Attach at least one bank statement ePDF to run analysis.")
        }

        val parsedFiles = mutableListOf<ParsedStatementFile>()
        val audits = mutableListOf<FileForensicAudit>()
        val skippedFailed = mutableListOf<Pair<String, String>>()
        var totalRecoveredRows = 0
        val totalFiles = stagedFiles.size.coerceAtLeast(1)

        // Stage 1 & 2: PDFBox Forensic Gatekeeper & BankStatementParser (+ Cloud Layout Recovery if 0 rows online)
        stagedFiles.forEachIndexed { idx, file ->
            val baseProgress = (idx.toFloat() / totalFiles.toFloat()) * 0.55f
            onProgressUpdate(baseProgress.coerceIn(0.02f, 0.55f))
            onFileStatusUpdate(file.id, QueueFileStatus.PARSING, "Forensic audit & extracting ledger…", null, null)
            onStageUpdate("Stage 1/5: Forensic Audit (${idx + 1}/${stagedFiles.size}) — ${file.displayName}")

            try {
                val bytes = readPdfBytes(file.uriString)
                val extraction = ForensicGatekeeper.inspectAndExtract(
                    fileName = file.displayName,
                    pdfBytes = bytes,
                    password = file.password
                )

                if (settings.strictForensicBlocking && extraction.audit.status == ForensicStatus.REJECTED) {
                    val reason = "Strict Forensic Mode blocked ${file.displayName}: ${extraction.audit.findings.joinToString(" ")}"
                    onFileStatusUpdate(file.id, QueueFileStatus.FAILED, reason, null, 0)
                    throw PdfIntegrityException(
                        fileName = file.displayName,
                        message = reason
                    )
                }

                onStageUpdate("Stage 2/5: BankStatementParser (${idx + 1}/${stagedFiles.size}) — ${file.displayName}")
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
                        statementPeriod = parsed.metadata.statementPeriod,
                        accountGroupLabel = file.accountGroupLabel
                    )
                )
                onFileStatusUpdate(
                    file.id,
                    QueueFileStatus.COMPLETED,
                    "${parsed.transactions.size} txns • ${parsed.metadata.statementPeriod}",
                    parsed.metadata.bankName,
                    parsed.transactions.size
                )
            } catch (pwEx: PdfPasswordRequiredException) {
                onFileStatusUpdate(file.id, QueueFileStatus.LOCKED, pwEx.message, null, 0)
                throw pwEx
            } catch (integrityEx: PdfIntegrityException) {
                onFileStatusUpdate(file.id, QueueFileStatus.FAILED, integrityEx.message, null, 0)
                throw integrityEx
            } catch (ex: Exception) {
                val reason = ex.localizedMessage ?: "Unreadable or unsupported PDF"
                onFileStatusUpdate(file.id, QueueFileStatus.FAILED, reason, null, 0)
                if (stagedFiles.size == 1) {
                    throw ex
                }
                skippedFailed.add(file.displayName to reason)
            }
        }

        if (parsedFiles.isEmpty()) {
            val detail = skippedFailed.joinToString("; ") { "${it.first}: ${it.second}" }
            throw IllegalStateException("All queued statement PDF(s) failed to parse. $detail")
        }

        // Stage 3: Deduplicate overlapping dates, normalize polarity & categorize transactions
        onProgressUpdate(0.65f)
        onStageUpdate("Stage 3/5: Merging ${parsedFiles.size} statement(s), fuzzy self-matching (${settings.fuzzySelfMatchThreshold}%) & categorizing...")
        val (rawMeta, rawCategorizedTxns) = statementParser.mergeAndNormalize(
            parsedFiles = parsedFiles,
            fuzzyThreshold = settings.fuzzySelfMatchThreshold
        )
        if (rawCategorizedTxns.isEmpty()) {
            val hint = if (!isDeviceOnline || !settings.onlineAccuracyBoostEnabled) {
                "Enable Online Accuracy Boost (Settings) to recover non-standard statement layouts, or upload an original text-based bank ePDF."
            } else {
                "Verify the uploaded file is a genuine text-based Indian bank statement ePDF (not a photo/image scan)."
            }
            throw IllegalStateException("No transactions could be extracted from the attached PDF(s). $hint")
        }

        // Stage 4: Online Hybrid Accuracy Boost (RBI IFSC Verification, Counterparty Bank Enrichment, Cloud AI Narration Assist, State Bank Holidays)
        onProgressUpdate(0.78f)
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
        onProgressUpdate(0.90f)
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
        onProgressUpdate(1.0f)
        PipelineExecutionResult(
            report = report,
            baseMetadata = consolidatedMeta,
            fullUnfilteredTransactions = categorizedTxns,
            verifiedHolidaysByIso = enrichment.verifiedHolidaysByIso,
            skippedFailedFiles = skippedFailed
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
        camProfile: UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val os = context.contentResolver.openOutputStream(targetUri)
            ?: throw IllegalStateException("Could not open destination file for writing.")
        os.use { stream ->
            excelReportGenerator.generateToStream(report, stream, excelConfig, camProfile, onProgress)
        }
    }

    suspend fun exportExcelToCacheFile(
        report: AnalysisReport,
        excelConfig: ExcelExportConfig = ExcelExportConfig(),
        camProfile: UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeId = report.config.applicantId
            .replace(Regex("""[^A-Za-z0-9_-]"""), "_")
            .ifBlank { "Report" }
        val outFile = File(dir, "BSA_YourView_${safeId}.xlsx")
        excelReportGenerator.generateToFile(report, outFile, excelConfig, camProfile, onProgress)
    }

    suspend fun exportPdfSummaryToUri(
        report: AnalysisReport,
        targetUri: Uri,
        camProfile: UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val os = context.contentResolver.openOutputStream(targetUri)
            ?: throw IllegalStateException("Could not open destination PDF file for writing.")
        os.use { stream ->
            excelReportGenerator.generatePdfSummaryToStream(report, stream, camProfile, onProgress)
        }
    }

    suspend fun exportPdfSummaryToCacheFile(
        report: AnalysisReport,
        camProfile: UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeId = report.config.applicantId
            .replace(Regex("""[^A-Za-z0-9_-]"""), "_")
            .ifBlank { "Report" }
        val outFile = File(dir, "BSA_YourView_${safeId}_Summary.pdf")
        excelReportGenerator.generatePdfSummaryToFile(report, outFile, camProfile, onProgress)
    }

    suspend fun generateSampleStatements(): List<StagedPdfFile> = withContext(Dispatchers.IO) {
        SamplePdfBuilder.generateSequentialSampleStatements(context)
    }

    suspend fun generateMultiAccountPortfolioStatements(): List<StagedPdfFile> = withContext(Dispatchers.IO) {
        SamplePdfBuilder.generateMultiAccountPortfolioStatements(context)
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
