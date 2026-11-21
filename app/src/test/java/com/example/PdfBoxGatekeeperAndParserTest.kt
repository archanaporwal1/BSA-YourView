package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.bsa.engine.ForensicGatekeeper
import com.example.bsa.engine.IndianBankParser
import com.example.bsa.engine.PdfIntegrityException
import com.example.bsa.engine.PdfPasswordRequiredException
import com.example.bsa.model.FacilityType
import com.example.bsa.model.ForensicStatus
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfBoxGatekeeperAndParserTest {

    @Before
    fun initPdfBox() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(ctx)
    }

    @Test
    fun encryptedBankStatementUnlocksAndParsesCoordinateTable() = runBlocking {
        val pdfBytes = buildRealBankPdf(
            producer = "HDFC Core Statement Engine",
            creator = "HDFC NetBanking",
            userPassword = "secret123",
            includeFullTransactions = true
        )

        // 1. Missing password must fail cleanly
        try {
            ForensicGatekeeper.inspectAndExtract("hdfc_locked.pdf", pdfBytes, "")
            fail("Expected PdfPasswordRequiredException when password is empty")
        } catch (expected: PdfPasswordRequiredException) {
            assertEquals("hdfc_locked.pdf", expected.fileName)
        }

        // 2. Correct password unlocks in memory and verifies integrity
        val extracted = ForensicGatekeeper.inspectAndExtract("hdfc_locked.pdf", pdfBytes, "secret123")
        assertEquals(ForensicStatus.VERIFIED, extracted.audit.status)
        assertTrue(extracted.audit.isPasswordProtected)
        assertTrue(extracted.visualRows.isNotEmpty())

        // 3. Coordinate-aligned PDFBox parser identifies HDFC Bank & extracts blank-column Debit/Credit rows
        val parsed = IndianBankParser.parseStatementAsync("hdfc_locked.pdf", extracted, FacilityType.OD)
        assertEquals("HDFC Bank", parsed.metadata.bankName)
        assertEquals("HDFC0001204", parsed.metadata.ifscCode)
        assertEquals(3, parsed.transactions.size)
        // Row 1: Credit +500,000 -> -1,000,000 Dr
        assertEquals(500000.0, parsed.transactions[0].signedAmount, 0.01)
        assertEquals(-1000000.0, parsed.transactions[0].normalizedBalance, 0.01)
        // Row 2: Debit -75,000 -> -1,075,000 Dr
        assertEquals(-75000.0, parsed.transactions[1].signedAmount, 0.01)
        assertEquals(-1075000.0, parsed.transactions[1].normalizedBalance, 0.01)
        // Row 3: Credit +250,000 -> -825,000 Dr
        assertEquals(250000.0, parsed.transactions[2].signedAmount, 0.01)
        assertEquals(-825000.0, parsed.transactions[2].normalizedBalance, 0.01)

        val (_, verified) = IndianBankParser.mergeAndDeduplicate(listOf(parsed))
        assertTrue("All coordinate-parsed rows must reconcile exactly", verified.none { it.second })
    }

    @Test
    fun tamperedProducerMetadataIsRejected() = runBlocking {
        val pdfBytes = buildRealBankPdf(
            producer = "iLovePDF Online Editor 3.1",
            creator = "Chrome Print to PDF",
            userPassword = null,
            includeFullTransactions = true
        )

        val extracted = ForensicGatekeeper.inspectAndExtract("tampered.pdf", pdfBytes, "")
        assertEquals(ForensicStatus.REJECTED, extracted.audit.status)
        assertTrue(extracted.audit.findings.any { it.contains("ilovepdf", ignoreCase = true) })
    }

    @Test
    fun scannedOrLowTextPdfIsRejected() = runBlocking {
        val pdfBytes = buildRealBankPdf(
            producer = "Bank Scanner Export",
            creator = "Scanner",
            userPassword = null,
            includeFullTransactions = false
        )

        val extracted = ForensicGatekeeper.inspectAndExtract("scanned.pdf", pdfBytes, "")
        assertEquals(ForensicStatus.REJECTED, extracted.audit.status)
        assertTrue(extracted.audit.avgCharsPerPage < 150)
    }

    @Test(expected = PdfIntegrityException::class)
    fun nonPdfBytesThrowIntegrityException() {
        ForensicGatekeeper.inspectAndExtractBlocking(
            fileName = "corrupt.pdf",
            pdfBytes = "NOT_A_VALID_PDF_FILE_CONTENT_1234567890".toByteArray(),
            password = ""
        )
    }

    private fun buildRealBankPdf(
        producer: String,
        creator: String,
        userPassword: String?,
        includeFullTransactions: Boolean
    ): ByteArray {
        val doc = PDDocument()
        doc.documentInformation.producer = producer
        doc.documentInformation.creator = creator

        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)

        PDPageContentStream(doc, page).use { cs ->
            fun drawCell(text: String, x: Float, yPos: Float, bold: Boolean = false) {
                cs.beginText()
                cs.setFont(if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.COURIER, 8.5f)
                cs.newLineAtOffset(x, yPos)
                cs.showText(text)
                cs.endText()
            }

            if (includeFullTransactions) {
                drawCell("HDFC BANK LIMITED - BUSINESS OD ACCOUNT STATEMENT", 32f, 790f, bold = true)
                drawCell("Account Holder Name: RAJESH KUMAR SHARMA", 32f, 774f, bold = true)
                drawCell("Account Number: 50200019482711   IFSC: HDFC0001204", 32f, 758f)
                drawCell("Address: 18 MG Road, Bengaluru, Karnataka 560001", 32f, 742f)
                drawCell("Statement Period: 01-Jun-2026 to 30-Jun-2026", 32f, 726f)

                drawCell("Date", 32f, 700f, bold = true)
                drawCell("Particulars", 102f, 700f, bold = true)
                drawCell("Withdrawal", 342f, 700f, bold = true)
                drawCell("Deposit", 418f, 700f, bold = true)
                drawCell("Balance", 492f, 700f, bold = true)

                drawCell("02-06-2026", 32f, 682f)
                drawCell("NEFT/SBIN0004411/DECCAN TRADERS", 102f, 682f)
                drawCell("500,000.00", 418f, 682f)
                drawCell("1,000,000.00 Dr", 490f, 682f)

                drawCell("08-06-2026", 32f, 666f)
                drawCell("ACH DR/BAJAJ FIN BUSINESS LOAN EMI", 102f, 666f)
                drawCell("75,000.00", 342f, 666f)
                drawCell("1,075,000.00 Dr", 490f, 666f)

                drawCell("15-06-2026", 32f, 650f)
                drawCell("IMPS/CR/881200/RAJESH SHARMA/ICIC", 102f, 650f)
                drawCell("250,000.00", 418f, 650f)
                drawCell("825,000.00 Dr", 490f, 650f)
            } else {
                drawCell("Scanned Image Only", 32f, 780f)
            }
        }

        if (!userPassword.isNullOrEmpty()) {
            val policy = StandardProtectionPolicy("ownerPass", userPassword, AccessPermission()).apply {
                encryptionKeyLength = 128
            }
            doc.protect(policy)
        }

        val baos = ByteArrayOutputStream()
        doc.save(baos)
        doc.close()
        return baos.toByteArray()
    }
}
