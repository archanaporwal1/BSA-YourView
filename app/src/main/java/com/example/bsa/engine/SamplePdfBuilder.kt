package com.example.bsa.engine

import android.content.Context
import android.net.Uri
import com.example.bsa.model.StagedPdfFile
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.Locale

/**
 * Creates 2 authentic sequential multi-month Indian bank statement ePDFs on-device
 * (pdfrendition11.pdf & pdfrendition12.pdf) using pdfbox-android so underwriters can
 * verify the complete forensic gatekeeper -> parser -> categorizer -> 11-tab Excel
 * workflow end-to-end immediately with real PDF bytes.
 */
object SamplePdfBuilder {

    fun generateSequentialSampleStatements(context: Context): List<StagedPdfFile> {
        val sampleDir = File(context.cacheDir, "samples").apply { mkdirs() }
        val file1 = File(sampleDir, "pdfrendition11.pdf")
        val file2 = File(sampleDir, "pdfrendition12.pdf")

        // File 1: Apr 2026 - May 2026 (ICICI Bank OD Account)
        val rows1 = listOf(
            Row("02-04-2026", "NEFT/HDFC0001204/SRI VENKATESHWARA AGRO TRADERS", 0.0, 485000.0, 1415000.0, "Dr"),
            Row("05-04-2026", "ACH DR/HDFC LTD HOME LOAN EMI 998120", 64500.0, 0.0, 1479500.0, "Dr"),
            Row("08-04-2026", "UPI/DR/60981122/HPCL PETROL BUNK HYDERABAD", 6850.0, 0.0, 1486350.0, "Dr"),
            Row("10-04-2026", "IMPS/CR/61002291/JARPULA RAJENDAR/SBIN0004412", 0.0, 225000.0, 1261350.0, "Dr"),
            Row("12-04-2026", "ACH DR/BAJAJ FIN AUTO LOAN EMI 44210", 28400.0, 0.0, 1289750.0, "Dr"),
            Row("15-04-2026", "RTGS/UTIB0000112/SHREE BALAJI STEELS AND CEMENT", 340000.0, 0.0, 1629750.0, "Dr"),
            Row("18-04-2026", "UPI/DR/61087712/DMART SUPERMARKET HYDERABAD", 9420.0, 0.0, 1639170.0, "Dr"),
            Row("20-04-2026", "NEFT/ICIC0000422/GST CHALLAN PAYMENT APRIL", 50000.0, 0.0, 1689170.0, "Dr"),
            Row("22-04-2026", "ATM WDL/NFS CASH/JUBILEE HILLS HYD", 20000.0, 0.0, 1709170.0, "Dr"),
            Row("25-04-2026", "NEFT/SBIN0003311/KAVERI INFRA PROJECTS", 0.0, 610000.0, 1099170.0, "Dr"),
            Row("28-04-2026", "UPI/DR/61189932/CRED PAY CREDIT CARD BILL", 42500.0, 0.0, 1141670.0, "Dr"),
            Row("30-04-2026", "OD INT SERVICING FOR APR 2026", 14820.0, 0.0, 1156490.0, "Dr"),
            Row("02-05-2026", "NEFT/HDFC0004410/DECCAN LOGISTICS CARGO", 48000.0, 0.0, 1204490.0, "Dr"),
            Row("05-05-2026", "ACH DR/HDFC LTD HOME LOAN EMI 998120", 64500.0, 0.0, 1268990.0, "Dr"),
            Row("08-05-2026", "NEFT/KKBK0000901/TELANGANA BUILDTECH PVT LTD", 0.0, 540000.0, 728990.0, "Dr"),
            Row("12-05-2026", "ACH DR/BAJAJ FIN AUTO LOAN EMI 44210", 28400.0, 0.0, 757390.0, "Dr"),
            Row("16-05-2026", "UPI/DR/61361100/APOLLO PHARMACY MEDICAL", 4180.0, 0.0, 761570.0, "Dr"),
            Row("19-05-2026", "RTGS/SBIN0008123/SHREE BALAJI STEELS AND CEMENT", 680000.0, 0.0, 1441570.0, "Dr"),
            Row("22-05-2026", "IMPS/DR/61428812/JARUPULA RAJENDER/HDFC00012", 180000.0, 0.0, 1621570.0, "Dr"),
            Row("26-05-2026", "NEFT/ICIC0000811/KAVERI INFRA PROJECTS", 0.0, 495000.0, 1126570.0, "Dr"),
            Row("28-05-2026", "UPI/DR/61489921/TSSPDCL ELECTRICITY BILL", 11250.0, 0.0, 1137820.0, "Dr"),
            Row("31-05-2026", "OD INT SERVICING FOR MAY 2026", 13640.0, 0.0, 1151460.0, "Dr")
        )

        // File 2: Overlaps 28-May & 31-May intentionally to test multi-PDF deduplication + June 2026
        val rows2 = listOf(
            Row("28-05-2026", "UPI/DR/61489921/TSSPDCL ELECTRICITY BILL", 11250.0, 0.0, 1137820.0, "Dr"),
            Row("31-05-2026", "OD INT SERVICING FOR MAY 2026", 13640.0, 0.0, 1151460.0, "Dr"),
            Row("02-06-2026", "NEFT/HDFC0001204/SRI VENKATESHWARA AGRO TRADERS", 0.0, 520000.0, 631460.0, "Dr"),
            Row("05-06-2026", "ACH DR/HDFC LTD HOME LOAN EMI 998120", 64500.0, 0.0, 695960.0, "Dr"),
            Row("09-06-2026", "UPI/DR/61602231/SWIGGY FOOD ORDER HYD", 1450.0, 0.0, 697410.0, "Dr"),
            Row("12-06-2026", "ACH DR/BAJAJ FIN AUTO LOAN EMI 44210", 28400.0, 0.0, 725810.0, "Dr"),
            Row("15-06-2026", "RTGS/UTIB0000112/SHREE BALAJI STEELS AND CEMENT", 1180000.0, 0.0, 1905810.0, "Dr"),
            Row("18-06-2026", "UPI/DR/61693312/ZERODHA BROKING SIP INVESTMENT", 25000.0, 0.0, 1930810.0, "Dr"),
            Row("20-06-2026", "NEFT/ICIC0000422/GST CHALLAN PAYMENT JUNE", 60000.0, 0.0, 1990810.0, "Dr"),
            Row("22-06-2026", "NEFT/SBIN0003311/KAVERI INFRA PROJECTS", 0.0, 875000.0, 1115810.0, "Dr"),
            Row("24-06-2026", "IMPS/CR/61751011/JARPULA RAJENDAR/SBIN0004412", 0.0, 150000.0, 965810.0, "Dr"),
            Row("26-06-2026", "UPI/DR/61771122/CRED PAY CREDIT CARD BILL", 38900.0, 0.0, 1004710.0, "Dr"),
            Row("28-06-2026", "ATM WDL/NFS CASH/BANJARA HILLS HYD", 15000.0, 0.0, 1019710.0, "Dr"),
            Row("30-06-2026", "OD INT SERVICING FOR JUN 2026", 15490.0, 0.0, 1035200.0, "Dr")
        )

        writeBankStatementPdf(
            outputFile = file1,
            periodLabel = "01-Apr-2026 to 31-May-2026",
            rows = rows1
        )
        writeBankStatementPdf(
            outputFile = file2,
            periodLabel = "28-May-2026 to 30-Jun-2026",
            rows = rows2
        )

        return listOf(
            StagedPdfFile(
                displayName = file1.name,
                uriString = Uri.fromFile(file1).toString(),
                fileSizeBytes = file1.length(),
                isSample = true
            ),
            StagedPdfFile(
                displayName = file2.name,
                uriString = Uri.fromFile(file2).toString(),
                fileSizeBytes = file2.length(),
                isSample = true
            )
        )
    }

    private data class Row(
        val date: String,
        val narration: String,
        val debit: Double,
        val credit: Double,
        val balanceAbs: Double,
        val balDrCr: String
    )

    private fun writeBankStatementPdf(
        outputFile: File,
        periodLabel: String,
        rows: List<Row>
    ) {
        val doc = PDDocument()
        doc.documentInformation.apply {
            producer = "ICICI Core Banking eStatement Engine 4.2"
            creator = "ICICI Bank Statement Generator"
            title = "Account Statement"
        }

        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)

        PDPageContentStream(doc, page).use { cs ->
            var y = 795f

            fun drawLine(text: String, bold: Boolean = false, size: Float = 8.5f, x: Float = 32f) {
                cs.beginText()
                cs.setFont(if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.COURIER, size)
                cs.newLineAtOffset(x, y)
                cs.showText(text)
                cs.endText()
                y -= (size + 5.5f)
            }

            fun drawCell(text: String, x: Float, yPos: Float, bold: Boolean = false, size: Float = 7.8f) {
                cs.beginText()
                cs.setFont(if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.COURIER, size)
                cs.newLineAtOffset(x, yPos)
                cs.showText(text)
                cs.endText()
            }

            drawLine("ICICI BANK LIMITED - CORPORATE & BUSINESS OVERDRAFT STATEMENT", bold = true, size = 11f)
            drawLine("Account Holder Name: JARUPULA RAJENDER", bold = true, size = 9f)
            drawLine("Account Number: 000805019482   IFSC: ICIC0000008   Branch: Jubilee Hills, Hyderabad", size = 8.5f)
            drawLine("Address: Plot 42, Road No 12, Banjara Hills, Hyderabad, Telangana 500034", size = 8.5f)
            drawLine("Statement Period: $periodLabel   Currency: INR   Facility: Overdraft (OD)", size = 8.5f)
            y -= 6f

            // Explicit tabular header column coordinates for PDFBox coordinate-aligned extraction
            drawCell("Date", 32f, y, bold = true, size = 8.2f)
            drawCell("Particulars", 102f, y, bold = true, size = 8.2f)
            drawCell("Withdrawal", 342f, y, bold = true, size = 8.2f)
            drawCell("Deposit", 418f, y, bold = true, size = 8.2f)
            drawCell("Balance", 492f, y, bold = true, size = 8.2f)
            y -= 13.5f
            drawLine("--------------------------------------------------------------------------------------------------------", size = 7.5f)

            for (r in rows) {
                drawCell(r.date, 32f, y)
                drawCell(r.narration.take(44), 102f, y)
                if (r.debit > 0.0) {
                    drawCell(String.format(Locale.ROOT, "%,11.2f", r.debit), 336f, y)
                }
                if (r.credit > 0.0) {
                    drawCell(String.format(Locale.ROOT, "%,11.2f", r.credit), 412f, y)
                }
                drawCell(String.format(Locale.ROOT, "%,12.2f %s", r.balanceAbs, r.balDrCr), 486f, y)
                y -= 13.5f
            }

            y -= 8f
            drawLine("--------------------------------------------------------------------------------------------------------", size = 7.5f)
            drawLine("End of Statement - Sample generated locally inside BSA-YourView (yourview.in) for offline verification.", size = 7.5f)
        }

        doc.save(outputFile)
        doc.close()
    }
}
