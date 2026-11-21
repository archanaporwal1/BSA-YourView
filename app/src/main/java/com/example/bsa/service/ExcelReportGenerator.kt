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
 * Dedicated 11-Tab Excel Report Generator Service (BSA-YourView | YourView)
 * Exports analyzed underwriting reports into an OOXML (.xlsx) spreadsheet containing all 11 tabs
 * with configurable columns, preferred date formats, live =SUM() and =AVERAGE() formulas,
 * INR formatting, frozen headers, and forensic audit logs.
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
            "Statement_Metadata"
        )
    }

    suspend fun generateToStream(
        report: AnalysisReport,
        outputStream: OutputStream,
        excelConfig: ExcelExportConfig = ExcelExportConfig(),
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        ExcelWorkbookWriter.writeWorkbook(report, outputStream, excelConfig) { step, total, label ->
            val fraction = (step.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            onProgress(fraction, label)
        }
    }

    suspend fun generateToBytes(
        report: AnalysisReport,
        excelConfig: ExcelExportConfig = ExcelExportConfig(),
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): ByteArray = withContext(Dispatchers.IO) {
        ByteArrayOutputStream().use { baos ->
            ExcelWorkbookWriter.writeWorkbook(report, baos, excelConfig) { step, total, label ->
                val fraction = (step.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                onProgress(fraction, label)
            }
            baos.toByteArray()
        }
    }

    suspend fun generateToFile(
        report: AnalysisReport,
        targetFile: File,
        excelConfig: ExcelExportConfig = ExcelExportConfig(),
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { fos ->
            ExcelWorkbookWriter.writeWorkbook(report, fos, excelConfig) { step, total, label ->
                val fraction = (step.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                onProgress(fraction, label)
            }
        }
        targetFile
    }
}
