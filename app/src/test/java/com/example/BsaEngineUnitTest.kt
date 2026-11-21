package com.example

import com.example.bsa.engine.*
import com.example.bsa.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class BsaEngineUnitTest {

    @Test
    fun fuzzySelfMatcherHandlesIndianSpellingVariations() {
        val score = FuzzyMatcherAndCategorizer.tokenSetRatio("JARUPULA RAJENDER", "JARPULA RAJENDAR")
        assertTrue("Expected high fuzzy score >= 82, got $score", score >= 82)
        assertTrue(
            FuzzyMatcherAndCategorizer.isSelfTransferMatch(
                holderName = "JARUPULA RAJENDER",
                description = "IMPS/CR/61002291/JARPULA RAJENDAR/SBIN0004412",
                party = "Jarpula Rajendar"
            )
        )
    }

    @Test
    fun fullDeterministicPipelineAnd11TabExcelExport() {
        val sampleText = """
            ICICI BANK LIMITED - BUSINESS OVERDRAFT STATEMENT
            Account Holder Name: JARUPULA RAJENDER
            Account Number: 000805019482   IFSC: ICIC0000008
            Address: Plot 42, Banjara Hills, Hyderabad
            Statement Period: 01-Apr-2026 to 30-Apr-2026
            Date         Particulars                                   Withdrawal      Deposit        Balance
            02-04-2026   NEFT/HDFC0001204/SRI VENKATESHWARA AGRO           0.00     485,000.00   1,415,000.00 Dr
            05-04-2026   ACH DR/HDFC LTD HOME LOAN EMI 998120         64,500.00           0.00   1,479,500.00 Dr
            10-04-2026   IMPS/CR/61002291/JARPULA RAJENDAR/SBIN            0.00     225,000.00   1,254,500.00 Dr
            15-04-2026   RTGS/UTIB0000112/SHREE BALAJI STEELS        150,000.00           0.00   1,404,500.00 Dr
            20-04-2026   NEFT/ICIC0000422/GST CHALLAN PAYMENT         50,000.00           0.00   1,454,500.00 Dr
            30-04-2026   OD INT SERVICING FOR APR 2026                14,820.00           0.00   1,469,320.00 Dr
        """.trimIndent()

        val config = ApplicantConfig(
            applicantId = "YV-TEST-01",
            sourceName = "YourView",
            productType = ProductType.WORKING_CAPITAL,
            facilityType = FacilityType.OD,
            sanctionedOdLimit = 2500000.0,
            drawingPowerLimit = 2000000.0
        )

        val parsed = IndianBankParser.parseStatement("pdfrendition11.pdf", sampleText, config.facilityType)
        assertEquals("ICICI Bank", parsed.metadata.bankName)
        assertEquals(6, parsed.transactions.size)

        val (meta, verified) = IndianBankParser.mergeAndDeduplicate(listOf(parsed))
        // Every row obeys Prev_Balance + Signed_Amount == Current_Balance with Dr normalized to negative
        assertTrue("Expected zero balance mismatches", verified.none { it.second })

        val txns = FuzzyMatcherAndCategorizer.categorizeAll(meta.holderName, verified)
        assertTrue(txns.any { it.category == "Home Loan" })
        assertTrue(txns.any { it.category == "Transfer from Self" && it.isSelfTransfer })
        assertTrue(txns.any { it.category == "OD Interest / Servicing" })

        val audit = FileForensicAudit(
            fileName = "pdfrendition11.pdf",
            detectedBank = meta.bankName,
            accountNumber = meta.accountNumber,
            statementPeriod = meta.statementPeriod,
            pageCount = 1,
            avgCharsPerPage = 780,
            producer = "ICICI Core Banking Engine",
            creator = "ICICI eStatement",
            eofMarkerCount = 1,
            status = ForensicStatus.VERIFIED,
            findings = listOf("Verified")
        )

        val report = UnderwritingAnalyticsEngine.analyze(config, meta, listOf(audit), txns)
        assertEquals(10, report.fraudIndicators.size)
        // Trigger #2: RTGS < 2L (150,000) must be flagged
        assertEquals(IndicatorStatus.FLAGGED, report.fraudIndicators.first { it.itemNo == 2 }.status)
        // Trigger #7: Negative EOD Balance must be auto-suppressed for OD
        assertEquals(IndicatorStatus.SUPPRESSED, report.fraudIndicators.first { it.itemNo == 7 }.status)
        // Trigger #9: Round figure GST payment (50,000) must be flagged
        assertEquals(IndicatorStatus.FLAGGED, report.fraudIndicators.first { it.itemNo == 9 }.status)

        // Export 11-tab Excel workbook and inspect zip entries & live formulas
        val out = ByteArrayOutputStream()
        ExcelWorkbookWriter.writeWorkbook(report, out)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }

        val workbookXml = entries["xl/workbook.xml"] ?: ""
        val expectedSheets = listOf(
            "Summary", "Derived_Sheet", "EOD_Balances", "Loan_Track",
            "Transactions", "Top_Credits", "Top_Debits", "Self_Transfers",
            "Grouped_Parties", "Fraud_Indicators", "Statement_Metadata"
        )
        expectedSheets.forEach { name ->
            assertTrue("Missing Excel tab: $name", workbookXml.contains("name=\"$name\""))
        }

        val sheet1Xml = entries["xl/worksheets/sheet1.xml"] ?: ""
        assertTrue("Summary sheet must include live SUM formula", sheet1Xml.contains("<f>SUM("))
        assertTrue("Summary sheet must include live AVERAGE formula", sheet1Xml.contains("<f>AVERAGE("))
        assertTrue("Summary sheet must include YourView branding", sheet1Xml.contains("yourview.in"))
        assertFalse("Must not contain third-party credits", sheet1Xml.contains("AI Studio", ignoreCase = true))
    }
}
