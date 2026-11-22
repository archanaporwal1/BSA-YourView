package com.example.bsa.service

import com.example.bsa.engine.ExcelWorkbookWriter
import com.example.bsa.model.AnalysisReport
import com.example.bsa.model.ExcelExportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Dedicated 18-Tab Institutional Underwriting Excel Report Generator Service (BSA-YourView | YourView)
 * Exports analyzed underwriting reports into an OOXML (.xlsx) spreadsheet containing all 18 tabs
 * with configurable columns, preferred date formats, live =SUM() and =AVERAGE() formulas,
 * INR formatting, frozen headers, deep transaction analytics, 4-segment eligibility, and 360° CAM.
 */
class ExcelReportGenerator {

    companion object {
        val WORKBOOK_TABS: List<String> = listOf(
            "Summary",
            "Derived_Sheet",
            "EOD_Balances",
            "Loan_Track",
            "Transactions",
            "Top_Credits",
            "Top_Debits",
            "Self_Transfers",
            "Grouped_Parties",
            "Fraud_Indicators",
            "Statement_Metadata",
            "Segment_Eligibility",
            "Internal_Credits_Contra",
            "Bounces_And_EMI_DeepDive",
            "Channel_And_Cash_Analysis",
            "Category_Spend_Analytics",
            "Counterparty_Concentration",
            "Underwriter_CAM_360"
        )
    }

    suspend fun generateToStream(
        report: AnalysisReport,
        outputStream: OutputStream,
        excelConfig: ExcelExportConfig = ExcelExportConfig(),
        camProfile: com.example.bsa.model.UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        ExcelWorkbookWriter.writeWorkbook(report, outputStream, excelConfig, camProfile) { step, total, label ->
            val fraction = (step.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            onProgress(fraction, label)
        }
    }

    suspend fun generateToBytes(
        report: AnalysisReport,
        excelConfig: ExcelExportConfig = ExcelExportConfig(),
        camProfile: com.example.bsa.model.UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): ByteArray = withContext(Dispatchers.IO) {
        ByteArrayOutputStream().use { baos ->
            ExcelWorkbookWriter.writeWorkbook(report, baos, excelConfig, camProfile) { step, total, label ->
                val fraction = (step.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                onProgress(fraction, label)
            }
            baos.toByteArray()
        }
    }

    private val pdfSummaryGenerator = PdfSummaryReportGenerator()

    suspend fun generateToFile(
        report: AnalysisReport,
        targetFile: File,
        excelConfig: ExcelExportConfig = ExcelExportConfig(),
        camProfile: com.example.bsa.model.UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            ExcelWorkbookWriter.writeWorkbook(report, fos, excelConfig, camProfile) { step, total, label ->
                val fraction = (step.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                onProgress(fraction, label)
            }
        }
        targetFile
    }

    /**
     * Generates a branded, client-ready 2-page BSA-YourView PDF summary presenting
     * 4-Segment Eligibility status, 10-Point Risk Score, Credit Health, and Portfolio Risk.
     */
    suspend fun generatePdfSummaryToStream(
        report: AnalysisReport,
        outputStream: OutputStream,
        camProfile: com.example.bsa.model.UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = pdfSummaryGenerator.generateToStream(report, outputStream, camProfile, onProgress)

    suspend fun generatePdfSummaryToBytes(
        report: AnalysisReport,
        camProfile: com.example.bsa.model.UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): ByteArray = pdfSummaryGenerator.generateToBytes(report, camProfile, onProgress)

    suspend fun generatePdfSummaryToFile(
        report: AnalysisReport,
        targetFile: File,
        camProfile: com.example.bsa.model.UnderwriterCamProfile? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File = pdfSummaryGenerator.generateToFile(report, targetFile, camProfile, onProgress)
}
