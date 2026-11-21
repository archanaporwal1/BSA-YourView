package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.bsa.engine.UnderwritingAnalyticsEngine
import com.example.bsa.model.*
import com.example.bsa.service.BankStatementParser
import com.example.bsa.service.ExcelReportGenerator
import com.example.bsa.service.FraudScreeningService
import com.example.bsa.service.ScreeningRiskTier
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BsaServicesUnitTest {

    private val parser = BankStatementParser()
    private val fraudService = FraudScreeningService()
    private val excelGenerator = ExcelReportGenerator()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun bankStatementParserIdentifiesBankHeadersAndMergesMultilineNarrations() = runBlocking {
        // Test bank-specific header profile detection across major Indian banks
        assertEquals("SBI", parser.identifyBankHeaderProfile("STATE BANK OF INDIA\nTxn Date Value Date Description Debit Credit Balance").bankName)
        assertEquals("Axis Bank", parser.identifyBankHeaderProfile("AXIS BANK\nTran Date Chq No Particulars Debit Credit Balance").bankName)
        assertEquals("Kotak Mahindra Bank", parser.identifyBankHeaderProfile("KOTAK MAHINDRA BANK\nDate Narration Chq/Ref No Withdrawal(Dr) Deposit(Cr) Balance").bankName)

        // Build real PDFBox SBI statement with wrapped 2-line narration
        val doc = PDDocument()
        doc.documentInformation.producer = "SBI Core eStatement Engine"
        doc.documentInformation.creator = "SBI Online"
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)

        PDPageContentStream(doc, page).use { cs ->
            fun cell(text: String, x: Float, y: Float, bold: Boolean = false) {
                cs.beginText()
                cs.setFont(if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.COURIER, 8.5f)
                cs.newLineAtOffset(x, y)
                cs.showText(text)
                cs.endText()
            }
            cell("STATE BANK OF INDIA - ACCOUNT STATEMENT", 32f, 790f, true)
            cell("Account Holder Name: ANITA DESHMUKH", 32f, 774f, true)
            cell("Account Number: 30981234567   IFSC: SBIN0004412", 32f, 758f)
            cell("Statement Period: 01-Aug-2026 to 31-Aug-2026", 32f, 742f)

            cell("Txn Date", 32f, 716f, true)
            cell("Description", 108f, 716f, true)
            cell("Debit", 345f, 716f, true)
            cell("Credit", 418f, 716f, true)
            cell("Balance", 492f, 716f, true)

            // Row 1 with wrapped 2-line narration directly beneath
            cell("05-08-2026", 32f, 698f)
            cell("NEFT/HDFC0000123/MAHARASHTRA", 108f, 698f)
            cell("300,000.00", 418f, 698f)
            cell("450,000.00 Cr", 490f, 698f)
            cell("INFRA PROJECTS INV 88421", 108f, 686f) // Wrapped continuation line

            // Row 2
            cell("15-08-2026", 32f, 668f)
            cell("CHQ 441298 BY CASH DEPOSIT", 108f, 668f)
            cell("50,000.00", 418f, 668f)
            cell("500,000.00 Cr", 490f, 668f)
        }

        val baos = ByteArrayOutputStream()
        doc.save(baos)
        doc.close()

        val result = parser.parsePdfBytes("sbi_multiline.pdf", baos.toByteArray(), "", FacilityType.NONE)
        assertEquals("SBI", result.detectedProfile.bankName)
        assertEquals("SBI", result.metadata.bankName)
        assertEquals(2, result.transactions.size)

        // Multi-line narration must be merged cleanly into Row 1 description
        val firstDesc = result.transactions[0].description
        assertTrue("Expected wrapped line merged, got: $firstDesc", firstDesc.contains("MAHARASHTRA") && firstDesc.contains("INFRA PROJECTS INV 88421"))
        assertEquals(300000.0, result.transactions[0].amount, 0.01)
        assertEquals(450000.0, result.transactions[0].balance, 0.01)
    }

    @Test
    fun fraudScreeningServiceEvaluatesAll10TriggersAndExports11TabExcel() = runBlocking {
        val txns = listOf(
            // #1 Suspicious ATM (non-100 denomination ₹12,350)
            BankTransaction(1, "2026-08-03", "03-Aug-26", "", "ATM WDL/NFS CASH HYD", -12350.0, "Cash Withdrawal", "Cash Withdrawal", 100000.0, false, false, "s.pdf"),
            // #2 Suspicious RTGS < 2L (₹1,25,000)
            BankTransaction(2, "2026-08-05", "05-Aug-26", "", "RTGS/ICIC0001/ALPHA TRADERS", -125000.0, "Transfer to Alpha Traders", "Alpha Traders", -25000.0, false, false, "s.pdf"),
            // #3 Bank Holiday Transaction (Aug 15 Independence Day cheque)
            BankTransaction(3, "2026-08-15", "15-Aug-26", "441298", "CHQ 441298 DEPOSIT", 80000.0, "Transfer from Beta Corp", "Beta Corp", 55000.0, false, false, "s.pdf"),
            // #4 Balance Mismatch + #6 Equal Credit Debit (matched ₹80,000 outflow next day)
            BankTransaction(4, "2026-08-16", "16-Aug-26", "", "NEFT/GAMMA ENTERPRISES", -80000.0, "Transfer to Gamma Enterprises", "Gamma Enterprises", -10000.0, false, true, "s.pdf"),
            // #8 Mid-month Irregular Interest Charge (Aug 14)
            BankTransaction(5, "2026-08-14", "14-Aug-26", "", "OD INT SERVICING MID MONTH", -4500.0, "OD Interest / Servicing", "OD Interest / Servicing", -14500.0, false, false, "s.pdf"),
            // #9 Round-figure Tax Payment (₹40,000 GST)
            BankTransaction(6, "2026-08-18", "18-Aug-26", "", "NEFT/GST CHALLAN AUG", -40000.0, "Utilities", "GST", -54500.0, false, false, "s.pdf"),
            // #10 Irregular Transfers to same party (>=3 round >= ₹25,000)
            BankTransaction(7, "2026-08-19", "19-Aug-26", "", "IMPS/DR/DELTA SUPPLIERS", -30000.0, "Transfer to Delta Suppliers", "Delta Suppliers", -84500.0, false, false, "s.pdf"),
            BankTransaction(8, "2026-08-21", "21-Aug-26", "", "IMPS/DR/DELTA SUPPLIERS", -35000.0, "Transfer to Delta Suppliers", "Delta Suppliers", -119500.0, false, false, "s.pdf"),
            BankTransaction(9, "2026-08-24", "24-Aug-26", "", "IMPS/DR/DELTA SUPPLIERS", -45000.0, "Transfer to Delta Suppliers", "Delta Suppliers", -164500.0, false, false, "s.pdf")
        )

        val eodMap = linkedMapOf("2026-08-03" to 100000.0, "2026-08-05" to -25000.0, "2026-08-24" to -164500.0)
        val suspectAudit = listOf(
            FileForensicAudit("s.pdf", "SBI", "30981234567", "Aug-26", 1, 900, "iLovePDF", "Chrome", 2, ForensicStatus.REJECTED, listOf("Tampered"))
        )

        // Savings/Current (NONE): all 10 triggers including #7 Negative EOD must fire
        val savingsReport = fraudService.screenAsync(
            config = ApplicantConfig(facilityType = FacilityType.NONE),
            fileAudits = suspectAudit,
            transactions = txns,
            dailyEodMap = eodMap
        )
        assertEquals(10, savingsReport.indicators.size)
        assertEquals(10, savingsReport.flaggedCount)
        assertEquals(ScreeningRiskTier.HIGH, savingsReport.riskTier)

        // OD Facility: #7 Negative EOD Balance must auto-suppress
        val odReport = fraudService.screenAsync(
            config = ApplicantConfig(facilityType = FacilityType.OD, sanctionedOdLimit = 500000.0, drawingPowerLimit = 400000.0),
            fileAudits = suspectAudit,
            transactions = txns,
            dailyEodMap = eodMap
        )
        assertEquals(IndicatorStatus.SUPPRESSED, odReport.indicators.first { it.itemNo == 7 }.status)
        assertEquals(9, odReport.flaggedCount)

        // Verify ExcelReportGenerator exports all 11 exact sheets with live formulas
        val fullReport = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-SVC-01", facilityType = FacilityType.OD, sanctionedOdLimit = 500000.0, drawingPowerLimit = 400000.0),
            metadata = AccountMetadata("SBI", "ANITA DESHMUKH", "Pune", "30981234567", "SBIN0004412", "Aug-26"),
            fileAudits = suspectAudit,
            transactions = txns
        )
        val xlsxBytes = excelGenerator.generateToBytes(fullReport)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(xlsxBytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                e = zis.nextEntry
            }
        }
        val wbXml = entries["xl/workbook.xml"] ?: ""
        ExcelReportGenerator.WORKBOOK_TABS.forEach { tab ->
            assertTrue("Missing tab $tab", wbXml.contains("name=\"$tab\""))
        }
        assertTrue((entries["xl/worksheets/sheet1.xml"] ?: "").contains("BSA-YourView"))
        assertTrue((entries["xl/worksheets/sheet10.xml"] ?: "").contains("<f>SUM("))
    }

    @Test
    fun exactDateRangeFilterRestrictsOutputAndCarriesOpeningBalance() {
        val txns = listOf(
            BankTransaction(1, "2026-08-02", "02-Aug-26", "", "NEFT/OPENING CREDIT", 100000.0, "Transfer from Client A", "Client A", 100000.0, false, false, "s.pdf"),
            BankTransaction(2, "2026-08-10", "10-Aug-26", "", "NEFT/MID CREDIT", 50000.0, "Transfer from Client B", "Client B", 150000.0, false, false, "s.pdf"),
            BankTransaction(3, "2026-08-12", "12-Aug-26", "", "UPI/VENDOR PAYMENT", -20000.0, "Transfer to Vendor X", "Vendor X", 130000.0, false, false, "s.pdf"),
            BankTransaction(4, "2026-08-25", "25-Aug-26", "", "NEFT/LATE DEBIT", -30000.0, "Transfer to Vendor Y", "Vendor Y", 100000.0, false, false, "s.pdf")
        )
        val report = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(
                applicantId = "YV-DATE-01",
                facilityType = FacilityType.NONE,
                filterStartDateIso = "2026-08-08",
                filterEndDateIso = "2026-08-15"
            ),
            metadata = AccountMetadata("SBI", "ANITA DESHMUKH", "Pune", "30981234567", "SBIN0004412", "01-Aug-2026 to 31-Aug-2026"),
            fileAudits = emptyList(),
            transactions = txns
        )

        // Strictly only Aug 10 & Aug 12 transactions should remain
        assertEquals(2, report.transactions.size)
        assertEquals("2026-08-10", report.transactions[0].dateIso)
        assertEquals("2026-08-12", report.transactions[1].dateIso)
        assertEquals(50000.0, report.totalInflow, 0.01)
        assertEquals(20000.0, report.totalOutflow, 0.01)

        // Daily EOD matrix must strictly populate Days 8..15 (Aug-26) and keep Days 1..7 & 16..31 null
        assertEquals(listOf("Aug-26"), report.eodMatrixMonths)
        assertNull(report.eodMatrixByDay[7]?.get("Aug-26"))
        assertNull(report.eodMatrixByDay[16]?.get("Aug-26"))
        // Opening balance carried forward from Aug 02 (100,000) into Aug 08 & Aug 09
        assertEquals(100000.0, report.eodMatrixByDay[8]?.get("Aug-26") ?: Double.NaN, 0.01)
        assertEquals(100000.0, report.eodMatrixByDay[9]?.get("Aug-26") ?: Double.NaN, 0.01)
        // Closing EOD on Aug 15 reflects Aug 12 balance (130,000), unaffected by Aug 25 debit
        assertEquals(130000.0, report.eodMatrixByDay[15]?.get("Aug-26") ?: Double.NaN, 0.01)
        assertTrue(report.metadata.statementPeriod.contains("08-Aug-26"))
        assertTrue(report.metadata.statementPeriod.contains("15-Aug-26"))
    }

    @Test
    fun forensicGatekeeperDetectsAndVerifiesPasswordProtectedPdf() = runBlocking {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 10f)
            cs.newLineAtOffset(40f, 760f)
            cs.showText("ICICI BANK ENCRYPTED STATEMENT SAMPLE")
            cs.endText()
        }
        val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
            "ownerPass99",
            "userPass42",
            com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()
        )
        policy.encryptionKeyLength = 128
        doc.protect(policy)

        val baos = ByteArrayOutputStream()
        doc.save(baos)
        doc.close()
        val encryptedBytes = baos.toByteArray()

        assertTrue(com.example.bsa.engine.ForensicGatekeeper.isPdfPasswordProtected(encryptedBytes))
        assertFalse(com.example.bsa.engine.ForensicGatekeeper.verifyPdfPassword(encryptedBytes, "wrongPass"))
        assertTrue(com.example.bsa.engine.ForensicGatekeeper.verifyPdfPassword(encryptedBytes, "userPass42"))
    }

    @Test
    fun excelExportEmitsRealtimeProgressAcrossAll11TabsAndRiskScoreTiersMatch() = runBlocking {
        val cleanIndicators = (1..10).map { i ->
            FraudIndicatorResult(
                itemNo = i,
                group = if (i <= 5) IndicatorGroup.FRAUD else IndicatorGroup.BEHAVIOURAL,
                title = "Check $i",
                status = IndicatorStatus.CLEAN,
                triggeredCount = 0,
                severity = "Low",
                summary = "Clean",
                evidence = emptyList()
            )
        }
        val low = FraudScreeningService.computeRiskSummary(cleanIndicators)
        assertEquals(0, low.riskScore)
        assertEquals(ScreeningRiskTier.LOW, low.riskTier)

        val moderate = FraudScreeningService.computeRiskSummary(
            cleanIndicators.map { if (it.itemNo == 1) it.copy(status = IndicatorStatus.FLAGGED) else it }
        )
        assertEquals(18, moderate.riskScore)
        assertEquals(ScreeningRiskTier.MODERATE, moderate.riskTier)

        val high = FraudScreeningService.computeRiskSummary(
            cleanIndicators.map { if (it.itemNo in listOf(1, 2, 6)) it.copy(status = IndicatorStatus.FLAGGED) else it }
        )
        assertEquals(43, high.riskScore)
        assertEquals(ScreeningRiskTier.HIGH, high.riskTier)

        // Verify ExcelReportGenerator reports monotonic progress through all 11 tabs up to 1.0f
        val txns = listOf(
            BankTransaction(1, "2026-08-10", "10-Aug-26", "", "NEFT/SAMPLE CREDIT", 125000.0, "Transfer from Client", "Client", 125000.0, false, false, "s.pdf")
        )
        val report = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-PROG-01", facilityType = FacilityType.NONE),
            metadata = AccountMetadata("SBI", "ANITA DESHMUKH", "Pune", "30981234567", "SBIN0004412", "Aug-26"),
            fileAudits = emptyList(),
            transactions = txns
        )
        val updates = mutableListOf<Pair<Float, String>>()
        val bytes = excelGenerator.generateToBytes(report) { fraction, label ->
            updates.add(fraction to label)
        }
        assertTrue(bytes.isNotEmpty())
        assertTrue("Expected >= 12 progress updates across 11 tabs + packaging, got ${updates.size}", updates.size >= 12)
        assertEquals(1.0f, updates.last().first, 0.001f)
        ExcelReportGenerator.WORKBOOK_TABS.forEach { tab ->
            assertTrue("Expected progress stage mentioning tab $tab", updates.any { it.second.contains(tab) })
        }
    }

    @Test
    fun customExcelConfigControlsDateFormatsColumnsFormulasAndFreezePanes() = runBlocking {
        val txns = listOf(
            BankTransaction(1, "2026-08-10", "10-Aug-26", "441298", "NEFT/ALPHA CORP RECEIPT", 250000.0, "Transfer from Alpha Corp", "Alpha Corp", 250000.0, false, false, "aug_stmt.pdf"),
            BankTransaction(2, "2026-08-15", "15-Aug-26", "", "IMPS/SELF ACCOUNT XFER", -50000.0, "Transfer To Self", "ANITA DESHMUKH", 200000.0, true, false, "aug_stmt.pdf")
        )
        val audits = listOf(
            FileForensicAudit("aug_stmt.pdf", "SBI", "30981234567", "Aug-26", 2, 1200, "SBI Engine", "SBI Online", 1, ForensicStatus.VERIFIED, listOf("Authentic"), false, "1.4", "a1b2c3d4", "2026-09-01", "2026-09-01", 0)
        )
        val report = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-CFG-01", facilityType = FacilityType.NONE),
            metadata = AccountMetadata("SBI", "ANITA DESHMUKH", "Pune", "30981234567", "SBIN0004412", "Aug-26"),
            fileAudits = audits,
            transactions = txns
        )

        val customConfig = ExcelExportConfig(
            dateFormat = ExcelDateFormat.YYYY_MM_DD,
            includeChequeNoColumn = false,
            includeCategoryColumn = false,
            includePartyColumn = true,
            includeSourceFileColumn = true,
            includeSelfTransferFlagColumn = true,
            splitDebitCreditColumns = true,
            includeFormulas = false,
            freezeHeaderRows = false,
            includeForensicAuditDetails = false,
            topTransactionsLimit = 10
        )

        val xlsxBytes = excelGenerator.generateToBytes(report, customConfig)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(xlsxBytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                e = zis.nextEntry
            }
        }

        val txnSheet = entries["xl/worksheets/sheet5.xml"] ?: ""
        // Custom headers present, disabled columns omitted
        assertTrue("Expected Withdrawal (Dr)", txnSheet.contains("Withdrawal (Dr)"))
        assertTrue("Expected Deposit (Cr)", txnSheet.contains("Deposit (Cr)"))
        assertTrue("Expected Counterparty", txnSheet.contains("Counterparty"))
        assertTrue("Expected Self Transfer", txnSheet.contains("Self Transfer"))
        assertTrue("Expected Source ePDF", txnSheet.contains("Source ePDF"))
        assertFalse("Cheque No. should be omitted", txnSheet.contains("Cheque No."))
        assertFalse("Category column should be omitted", txnSheet.contains(">Category<"))
        // Custom ISO date format applied
        assertTrue("Expected YYYY-MM-DD formatted date", txnSheet.contains("2026-08-10"))
        assertTrue("Expected YYYY-MM-DD formatted date 2", txnSheet.contains("2026-08-15"))
        // Formulas & freeze panes disabled across worksheets
        assertFalse("Formulas should be omitted when includeFormulas=false", txnSheet.contains("<f>"))
        assertFalse("Freeze panes should be omitted when freezeHeaderRows=false", txnSheet.contains("<pane "))

        // Tab 6 Top Credits & Tab 7 Top Debits respect Top 10 limit & disabled formulas
        val topCreditsSheet = entries["xl/worksheets/sheet6.xml"] ?: ""
        assertTrue("Expected Top 10 Credit Inflows title", topCreditsSheet.contains("Top 10 Credit Inflows"))
        assertFalse("Formulas should be disabled in Top Credits", topCreditsSheet.contains("<f>"))
        val topDebitsSheet = entries["xl/worksheets/sheet7.xml"] ?: ""
        assertTrue("Expected Top 10 Debit Outflows title", topDebitsSheet.contains("Top 10 Debit Outflows"))

        // Tab 11 Statement Metadata switches to compact 7-column mode
        val metaSheet = entries["xl/worksheets/sheet11.xml"] ?: ""
        assertFalse("SHA-256 column should be hidden when includeForensicAuditDetails=false", metaSheet.contains("SHA-256"))
    }

    @Test
    fun onlineEnrichmentServiceEnrichesIfscHolidaysAndSmartAssistWithOfflineFallback() = runBlocking {
        val fakeHttp = com.example.bsa.service.HttpTextFetcher { url ->
            when {
                url.endsWith("/SBIN0004412") ->
                    """{"IFSC":"SBIN0004412","BANK":"State Bank of India","BRANCH":"PUNE MAIN","CITY":"PUNE","STATE":"MAHARASHTRA","MICR":"411002002","RTGS":true,"NEFT":true,"IMPS":true,"UPI":true}"""
                url.endsWith("/HDFC0000123") ->
                    """{"IFSC":"HDFC0000123","BANK":"HDFC Bank","BRANCH":"FORT MUMBAI","CITY":"MUMBAI","STATE":"MAHARASHTRA","MICR":"400240002","RTGS":true,"NEFT":true,"IMPS":true,"UPI":true}"""
                url.contains("/PublicHolidays/2026/IN") ->
                    """[{"date":"2026-08-15","localName":"Independence Day","name":"Independence Day"}]"""
                else -> null
            }
        }
        val fakeSmartAssist = object : com.example.bsa.service.OnlineSmartAssistProvider {
            override val isAvailable: Boolean = true
            override val statusNotice: String = "Test Smart Assist"
            override suspend fun classifyAmbiguousTransactions(
                holderName: String,
                ambiguousTxns: List<BankTransaction>
            ): com.example.bsa.service.SmartClassificationOutcome {
                val patches = ambiguousTxns.associate { txn ->
                    txn.slNo to com.example.bsa.service.SmartClassificationPatch(
                        slNo = txn.slNo,
                        category = "Transfer To Self",
                        partyName = "ANITA DESHMUKH (SELF)",
                        isSelfTransfer = true
                    )
                }
                return com.example.bsa.service.SmartClassificationOutcome(patches, "Refined ${patches.size} item(s)")
            }

            override suspend fun recoverStatementLayout(
                fileName: String,
                rawPdfText: String,
                facilityType: FacilityType
            ): com.example.bsa.service.SmartLayoutRecoveryResult {
                return com.example.bsa.service.SmartLayoutRecoveryResult(null, emptyList(), "Not needed")
            }
        }

        val service = com.example.bsa.service.OnlineEnrichmentService(
            httpFetcher = fakeHttp,
            smartAssistProvider = fakeSmartAssist
        )
        val baseMeta = AccountMetadata(
            bankName = "Generic Indian Bank",
            holderName = "ANITA DESHMUKH",
            address = "",
            accountNumber = "30981234567",
            ifscCode = "SBIN0004412",
            statementPeriod = "May-26 to Aug-26"
        )
        val baseTxns = listOf(
            // Uncategorized counterparty IFSC txn on Maharashtra Day (May 1)
            BankTransaction(1, "2026-05-01", "01-May-26", "550011", "CHQ 550011 NEFT/HDFC0000123", 120000.0, "Others", "Others", 220000.0, false, false, "s.pdf"),
            // Ambiguous narration that Cloud Smart Assist reclassifies as self-transfer
            BankTransaction(2, "2026-05-04", "04-May-26", "", "IMPS/XFER/ANITA D PERSONAL", -40000.0, "Others", "Others", 180000.0, false, false, "s.pdf")
        )

        // 1. Offline fallback must leave data completely untouched and report usedOnlineBoost=false
        val offlineOutcome = service.enrichIfOnline(
            isDeviceOnline = false,
            onlineBoostEnabled = true,
            cloudAiAssistEnabled = true,
            metadata = baseMeta,
            transactions = baseTxns
        )
        assertFalse(offlineOutcome.summary.usedOnlineBoost)
        assertEquals("Generic Indian Bank", offlineOutcome.metadata.bankName)
        assertEquals("Others", offlineOutcome.transactions[0].category)
        assertTrue(offlineOutcome.verifiedHolidaysByIso.isEmpty())

        // 2. Online hybrid mode resolves RBI IFSC, counterparty bank, state holiday (Maharashtra Day), and AI patch
        val onlineOutcome = service.enrichIfOnline(
            isDeviceOnline = true,
            onlineBoostEnabled = true,
            cloudAiAssistEnabled = true,
            metadata = baseMeta,
            transactions = baseTxns
        )
        assertTrue(onlineOutcome.summary.usedOnlineBoost)
        assertEquals("State Bank of India", onlineOutcome.metadata.bankName)
        assertTrue(onlineOutcome.metadata.address.contains("PUNE"))
        assertEquals("MAHARASHTRA", onlineOutcome.summary.verifiedBranchState)
        assertEquals(1, onlineOutcome.summary.resolvedCounterpartyIfscCount)
        assertEquals("Transfer from HDFC Bank", onlineOutcome.transactions[0].category)
        assertTrue(onlineOutcome.transactions[0].partyName.contains("HDFC Bank"))
        assertEquals("Transfer To Self", onlineOutcome.transactions[1].category)
        assertTrue(onlineOutcome.transactions[1].isSelfTransfer)
        assertEquals(1, onlineOutcome.summary.aiReclassifiedCount)
        assertTrue(onlineOutcome.verifiedHolidaysByIso.containsKey("2026-05-01"))
        assertTrue(onlineOutcome.verifiedHolidaysByIso["2026-05-01"]!!.contains("Maharashtra Day"))

        // 3. Fraud Trigger #3 must cite the exact online state holiday (Maharashtra Day) for the May 1 cheque
        val report = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-ONL-01", facilityType = FacilityType.NONE),
            metadata = onlineOutcome.metadata,
            fileAudits = emptyList(),
            transactions = onlineOutcome.transactions,
            extraBankHolidaysByIso = onlineOutcome.verifiedHolidaysByIso,
            onlineEnrichment = onlineOutcome.summary
        )
        val holidayTrigger = report.fraudIndicators.first { it.itemNo == 3 }
        assertEquals(IndicatorStatus.FLAGGED, holidayTrigger.status)
        assertTrue(
            "Expected Maharashtra Day in Trigger #3 evidence, got: ${holidayTrigger.evidence}",
            holidayTrigger.evidence.any { it.contains("Maharashtra Day") }
        )
    }

    @Test
    fun inAppUpdaterServiceParsesManifestAndGitHubReleasesAndComparesVersions() = runBlocking {
        val manifestJson = """
            {
              "appName": "BSA-YourView",
              "packageName": "in.bsa.yourview",
              "versionCode": 12,
              "versionName": "1.2.0",
              "apkFileName": "BSA-YourView-v1.2.0.apk",
              "apkUrl": "https://github.com/yourview/bsa-app/releases/download/v1.2.0/BSA-YourView-v1.2.0.apk",
              "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "fileSizeBytes": 18452000,
              "releaseNotes": "Hybrid RBI IFSC & In-App Auto-Updater release.",
              "publishedAtUtc": "2026-11-20T12:00:00Z"
            }
        """.trimIndent()

        val updater = com.example.bsa.service.InAppUpdaterService(
            httpFetcher = com.example.bsa.service.HttpTextFetcher { url ->
                if (url.endsWith("update-manifest.json")) manifestJson else null
            }
        )

        // Not configured when repo override & BuildConfig are empty
        val unconfigured = updater.checkForUpdate(repoOverride = "", currentVersionCode = 1, currentVersionName = "1.0.0")
        assertTrue(unconfigured is com.example.bsa.service.UpdateCheckOutcome.NotConfigured)

        // Detects newer release when remote versionCode > local versionCode
        val available = updater.checkForUpdate(repoOverride = "yourview/bsa-app", currentVersionCode = 1, currentVersionName = "1.0.0")
        assertTrue(available is com.example.bsa.service.UpdateCheckOutcome.UpdateAvailable)
        val release = (available as com.example.bsa.service.UpdateCheckOutcome.UpdateAvailable).release
        assertEquals(12, release.versionCode)
        assertEquals("1.2.0", release.versionName)
        assertEquals("BSA-YourView-v1.2.0.apk", release.apkFileName)
        assertEquals(64, release.sha256.length)

        // Up-to-date when already on versionCode 12 / 1.2.0
        val upToDate = updater.checkForUpdate(repoOverride = "yourview/bsa-app", currentVersionCode = 12, currentVersionName = "1.2.0")
        assertTrue(upToDate is com.example.bsa.service.UpdateCheckOutcome.UpToDate)

        // Fallback to GitHub Releases API JSON when update-manifest.json is absent
        val ghReleaseJson = """
            {
              "tag_name": "v1.3.5",
              "body": "Performance & OCR improvements",
              "published_at": "2026-11-21T08:30:00Z",
              "assets": [
                {
                  "name": "BSA-YourView-v1.3.5.apk",
                  "size": 19200000,
                  "browser_download_url": "https://github.com/yourview/bsa-app/releases/download/v1.3.5/BSA-YourView-v1.3.5.apk"
                }
              ]
            }
        """.trimIndent()
        val fallbackUpdater = com.example.bsa.service.InAppUpdaterService(
            httpFetcher = com.example.bsa.service.HttpTextFetcher { url ->
                if (url.contains("api.github.com")) ghReleaseJson else null
            }
        )
        val ghOutcome = fallbackUpdater.checkForUpdate(
            repoOverride = "https://github.com/yourview/bsa-app/",
            currentVersionCode = 1,
            currentVersionName = "1.0.0"
        )
        assertTrue(ghOutcome is com.example.bsa.service.UpdateCheckOutcome.UpdateAvailable)
        assertEquals("1.3.5", (ghOutcome as com.example.bsa.service.UpdateCheckOutcome.UpdateAvailable).release.versionName)
    }
}
