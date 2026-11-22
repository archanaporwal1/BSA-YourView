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
            },
            defaultRepo = "",
            defaultManifestUrl = ""
        )

        // Not configured when repo override & default BuildConfig repo/manifest are empty
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

        // Regression test: GitHub Releases API minor bump v1.1.1 must be newer than v1.0.2 (build 2)
        val minorBumpJson = ghReleaseJson.replace("v1.3.5", "v1.1.1").replace("1.3.5", "1.1.1")
        val minorUpdater = com.example.bsa.service.InAppUpdaterService(
            httpFetcher = com.example.bsa.service.HttpTextFetcher { url ->
                if (url.contains("api.github.com")) minorBumpJson else null
            }
        )
        val minorOutcome = minorUpdater.checkForUpdate(
            repoOverride = "yourview/bsa-app",
            currentVersionCode = 2,
            currentVersionName = "1.0.2"
        )
        assertTrue("Expected v1.1.1 to be detected as newer than v1.0.2 (code 2)", minorOutcome is com.example.bsa.service.UpdateCheckOutcome.UpdateAvailable)
    }

    @Test
    fun creditIntelligenceEngineComputesFoirAbbEligibilityVerdictAndBrief() {
        val txns = listOf(
            BankTransaction(1, "2026-08-02", "02-Aug-26", "", "NEFT/CLIENT ALPHA RECEIPT", 400000.0, "Transfer from Client Alpha", "Client Alpha", 500000.0, false, false, "s.pdf"),
            BankTransaction(2, "2026-08-05", "05-Aug-26", "", "ACH-DR/BAJAJ FIN EMI", -45000.0, "Loan", "Bajaj Fin", 455000.0, false, false, "s.pdf"),
            BankTransaction(3, "2026-08-10", "10-Aug-26", "", "NEFT/CLIENT BETA RECEIPT", 200000.0, "Transfer from Client Beta", "Client Beta", 655000.0, false, false, "s.pdf"),
            BankTransaction(4, "2026-08-18", "18-Aug-26", "", "UPI/VENDOR SUPPLIES", -120000.0, "Transfer to Vendor Supplies", "Vendor Supplies", 535000.0, false, false, "s.pdf"),
            BankTransaction(5, "2026-08-25", "25-Aug-26", "", "RTGS CHG + GST", -29.50, "Bank Charges", "Bank Charges", 534970.50, false, false, "s.pdf")
        )
        val verifiedAudit = listOf(
            FileForensicAudit("s.pdf", "HDFC Bank", "50100123456", "Aug-26", 1, 1400, "HDFC Engine", "HDFC", 1, ForensicStatus.VERIFIED, listOf("Verified"))
        )
        val report = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(
                applicantId = "YV-INTEL-01",
                productType = ProductType.BUSINESS_LOAN,
                facilityType = FacilityType.NONE
            ),
            metadata = AccountMetadata("HDFC Bank", "RAJESH KUMAR", "Hyderabad", "50100123456", "HDFC0000123", "Aug-26"),
            fileAudits = verifiedAudit,
            transactions = txns
        )

        // Regression check: RTGS CHG + GST ₹29.50 must NOT trigger Suspicious RTGS < 2L fraud flag
        val rtgsIndicator = report.fraudIndicators.first { it.itemNo == 2 }
        assertEquals(IndicatorStatus.CLEAN, rtgsIndicator.status)

        val intel = com.example.bsa.engine.CreditIntelligenceEngine.evaluate(report)
        assertEquals(com.example.bsa.engine.UnderwritingVerdict.STRONG_APPROVE, intel.verdict)
        assertTrue("Expected Health Score >= 80, got ${intel.creditHealthScore}", intel.creditHealthScore >= 80)
        assertEquals(600000.0, intel.avgMonthlyBusinessCredits, 0.01)
        assertEquals(45000.0, intel.avgMonthlyEmiObligation, 0.01)
        assertEquals(7.5, intel.foirPercent, 0.2)
        assertEquals(0, intel.bounceAndReturnCount)
        assertTrue("Expected positive ABB", intel.avgBankBalanceAbb > 400000.0)
        assertTrue("Expected positive estimated loan eligibility", intel.estimatedEligibleLoan > 1000000.0)

        // Verify reducing-balance EMI & inverse max-loan consistency
        val emi = com.example.bsa.engine.CreditIntelligenceEngine.calculateMonthlyEmi(1000000.0, 12.0, 36)
        assertEquals(33214.31, emi, 1.0)
        val inverseLoan = com.example.bsa.engine.CreditIntelligenceEngine.calculateMaxLoanFromEmi(emi, 12.0, 36)
        assertEquals(1000000.0, inverseLoan, 10.0)

        // Verify Executive Brief text contains YourView branding and key KPIs
        val brief = com.example.bsa.engine.CreditIntelligenceEngine.buildExecutiveBriefText(report, intel)
        assertTrue(brief.contains("BSA-YourView"))
        assertTrue(brief.contains("yourview.in"))
        assertTrue(brief.contains("YV-INTEL-01"))
        assertTrue(brief.contains("FOIR:"))
    }

    @Test
    fun top50LimitAndExpandedBankAndBounceCategorizationWorkAccurately() {
        // 55 credits should retain up to 50 in report.topCredits (fixing old 30 cap)
        val manyCredits = (1..55).map { idx ->
            BankTransaction(
                slNo = idx,
                dateIso = "2026-08-${String.format("%02d", (idx % 28) + 1)}",
                displayDate = "${String.format("%02d", (idx % 28) + 1)}-Aug-26",
                chequeNo = "",
                description = "NEFT/CLIENT $idx RECEIPT",
                amount = 10000.0 + idx * 1000.0,
                category = "Transfer from Client $idx",
                partyName = "Client $idx",
                balance = idx * 15000.0,
                isSelfTransfer = false,
                hasBalanceMismatch = false,
                sourceFile = "s.pdf"
            )
        }
        val report = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-TOP50", facilityType = FacilityType.NONE),
            metadata = AccountMetadata("Federal Bank", "MEERA NAIR", "Kochi", "112233445566", "FDRL0001234", "Aug-26"),
            fileAudits = emptyList(),
            transactions = manyCredits
        )
        assertEquals(50, report.topCredits.size)

        // Expanded Indian bank detection
        assertEquals("Federal Bank", com.example.bsa.engine.IndianBankParser.detectBank("FEDERAL BANK LTD\nIFSC: FDRL0001234"))
        assertEquals("AU Small Finance Bank", com.example.bsa.engine.IndianBankParser.detectBank("AU SMALL FINANCE BANK\nIFSC: AUBL0002211"))

        // Bounced NACH EMI must classify as Reversal (not Loan) so it is caught as a bounce
        val rawBounce = com.example.bsa.engine.RawParsedTransaction(
            dateIso = "2026-08-07",
            displayDate = "07-Aug-26",
            chequeNo = "",
            description = "NACH RTN BAJAJ FIN INSUFFICIENT FUNDS",
            withdrawal = 590.0,
            deposit = null,
            signedAmount = -590.0,
            normalizedBalance = 12000.0,
            balancePolarityExplicit = "CR",
            sourceFile = "s.pdf"
        )
        val categorized = com.example.bsa.engine.FuzzyMatcherAndCategorizer.categorizeAll(
            holderName = "MEERA NAIR",
            verifiedRows = listOf(rawBounce to false)
        )
        assertEquals("Reversal", categorized.first().category)
    }

    @Test
    fun fourSegmentEligibilityComputesDistinctProgramsForBusinessSalariedOdAndRiskProfiles() {
        val verifiedAudit = listOf(
            FileForensicAudit("stmt.pdf", "HDFC Bank", "50100123456", "Aug-26", 1, 1500, "HDFC Engine", "HDFC", 1, ForensicStatus.VERIFIED, listOf("Verified"))
        )

        // 1. Self-Employed Business Profile (₹6L monthly non-self business inflow, ₹40k EMI, ~₹4.5L ABB)
        val bizTxns = listOf(
            BankTransaction(1, "2026-08-02", "02-Aug-26", "", "NEFT/BUYER ALPHA", 350000.0, "Transfer from Buyer Alpha", "Buyer Alpha", 450000.0, false, false, "stmt.pdf"),
            BankTransaction(2, "2026-08-08", "08-Aug-26", "", "ACH-DR/HDFC EMI", -40000.0, "Loan", "HDFC Bank", 410000.0, false, false, "stmt.pdf"),
            BankTransaction(3, "2026-08-14", "14-Aug-26", "", "RTGS/BUYER BETA", 250000.0, "Transfer from Buyer Beta", "Buyer Beta", 660000.0, false, false, "stmt.pdf"),
            BankTransaction(4, "2026-08-22", "22-Aug-26", "", "NEFT/SUPPLIER GAMMA", -210000.0, "Transfer to Supplier Gamma", "Supplier Gamma", 450000.0, false, false, "stmt.pdf")
        )
        val bizReport = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-SEG-BIZ", productType = ProductType.BUSINESS_LOAN, facilityType = FacilityType.NONE),
            metadata = AccountMetadata("HDFC Bank", "VIKRAM TRADERS", "Mumbai", "50100123456", "HDFC0000123", "Aug-26"),
            fileAudits = verifiedAudit,
            transactions = bizTxns
        )
        val bizIntel = com.example.bsa.engine.CreditIntelligenceEngine.evaluate(bizReport)
        assertEquals(com.example.bsa.engine.BorrowerProfileType.SELF_EMPLOYED_BUSINESS, bizIntel.borrowerProfile)
        assertEquals(4, bizIntel.segmentEligibilities.size)

        val auto = bizIntel.segmentEligibilities.first { it.productType == ProductType.AUTO_LOAN }
        val bl = bizIntel.segmentEligibilities.first { it.productType == ProductType.BUSINESS_LOAN }
        val wc = bizIntel.segmentEligibilities.first { it.productType == ProductType.WORKING_CAPITAL }
        val hl = bizIntel.segmentEligibilities.first { it.productType == ProductType.HOME_LOAN }

        assertEquals(com.example.bsa.engine.EligibilityStatus.ELIGIBLE, auto.status)
        assertEquals(com.example.bsa.engine.EligibilityStatus.ELIGIBLE, bl.status)
        assertEquals(com.example.bsa.engine.EligibilityStatus.ELIGIBLE, wc.status)
        assertEquals(com.example.bsa.engine.EligibilityStatus.ELIGIBLE, hl.status)
        assertTrue(wc.isRevolvingFacility)
        assertFalse(bl.isRevolvingFacility)
        // RBI Nayak Committee 20% of Annualized BTO (₹6L * 12 = ₹72L -> 20% = ₹14.4L)
        assertEquals(1440000.0, wc.recommendedAmount, 10000.0)
        // Home Loan (240m @ 8.75%) must be meaningfully higher than Unsecured Business Loan (36m @ 14.50%)
        assertTrue("Expected Home Loan (${hl.recommendedAmount}) > Unsecured BL (${bl.recommendedAmount})", hl.recommendedAmount > bl.recommendedAmount)
        // Distinct benchmark rates across all 4 segments
        assertEquals(9.50, auto.benchmarkRatePct, 0.01)
        assertEquals(14.50, bl.benchmarkRatePct, 0.01)
        assertEquals(11.50, wc.benchmarkRatePct, 0.01)
        assertEquals(8.75, hl.benchmarkRatePct, 0.01)

        // 2. Salaried Individual Profile auto-detection
        val salTxns = listOf(
            BankTransaction(1, "2026-08-01", "01-Aug-26", "", "NEFT/INFOSYS SALARY AUG", 120000.0, "Salary / Payroll", "Infosys", 180000.0, false, false, "stmt.pdf"),
            BankTransaction(2, "2026-08-05", "05-Aug-26", "", "NACH/ICICI CAR EMI", -15000.0, "Loan", "ICICI Bank", 165000.0, false, false, "stmt.pdf"),
            BankTransaction(3, "2026-08-18", "18-Aug-26", "", "UPI/LIVING EXPENSES", -35000.0, "Others", "Living", 130000.0, false, false, "stmt.pdf")
        )
        val salReport = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-SEG-SAL", productType = ProductType.HOME_LOAN, facilityType = FacilityType.NONE),
            metadata = AccountMetadata("ICICI Bank", "PRIYA SHARMA", "Bengaluru", "000101234567", "ICIC0000001", "Aug-26"),
            fileAudits = verifiedAudit,
            transactions = salTxns
        )
        val salIntel = com.example.bsa.engine.CreditIntelligenceEngine.evaluate(salReport)
        assertEquals(com.example.bsa.engine.BorrowerProfileType.SALARIED, salIntel.borrowerProfile)
        assertEquals(120000.0, salIntel.estimatedNetMonthlyIncome, 0.01)
        val salHome = salIntel.primarySegmentEligibility
        assertEquals(ProductType.HOME_LOAN, salHome.productType)
        assertEquals(60.0, salHome.maxAllowedFoirPct, 0.01) // >= ₹1L net salary gets 60% FOIR
        assertTrue("Expected Salaried Home Loan >= ₹50L, got ${salHome.recommendedAmount}", salHome.recommendedAmount >= 5000000.0)

        // 3. Existing OD/CC Account Enhancement Gap calculation
        val odReport = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(
                applicantId = "YV-SEG-OD",
                productType = ProductType.WORKING_CAPITAL,
                facilityType = FacilityType.OD,
                sanctionedOdLimit = 1000000.0,
                drawingPowerLimit = 1000000.0
            ),
            metadata = AccountMetadata("HDFC Bank", "VIKRAM TRADERS", "Mumbai", "50100123456", "HDFC0000123", "Aug-26"),
            fileAudits = verifiedAudit,
            transactions = bizTxns.map { it.copy(balance = -400000.0) }
        )
        val odIntel = com.example.bsa.engine.CreditIntelligenceEngine.evaluate(odReport)
        val odWc = odIntel.primarySegmentEligibility
        assertNotNull("Existing OD facility should compute incremental enhancement gap", odWc.existingFacilityGap)
        assertEquals(440000.0, odWc.existingFacilityGap ?: 0.0, 10000.0)

        // 4. Forensic REJECTED + 3+ Bounces must force INELIGIBLE
        val rejectedAudit = listOf(
            FileForensicAudit("fake.pdf", "HDFC Bank", "50100123456", "Aug-26", 1, 900, "iLovePDF", "Web", 2, ForensicStatus.REJECTED, listOf("Tampered"))
        )
        val badTxns = bizTxns + listOf(
            BankTransaction(5, "2026-08-10", "10-Aug-26", "", "NACH RTN INSUFFICIENT FUNDS", -590.0, "Reversal", "Reversal", 400000.0, false, false, "fake.pdf"),
            BankTransaction(6, "2026-08-12", "12-Aug-26", "", "I/W CHQ RET BOUNCE", -590.0, "Reversal", "Reversal", 399410.0, false, false, "fake.pdf"),
            BankTransaction(7, "2026-08-15", "15-Aug-26", "", "ECS RTN BOUNCE CHG", -590.0, "Reversal", "Reversal", 398820.0, false, false, "fake.pdf")
        )
        val riskReport = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-SEG-RISK", productType = ProductType.BUSINESS_LOAN, facilityType = FacilityType.NONE),
            metadata = AccountMetadata("HDFC Bank", "VIKRAM TRADERS", "Mumbai", "50100123456", "HDFC0000123", "Aug-26"),
            fileAudits = rejectedAudit,
            transactions = badTxns
        )
        val riskIntel = com.example.bsa.engine.CreditIntelligenceEngine.evaluate(riskReport)
        assertEquals(com.example.bsa.engine.UnderwritingVerdict.HIGH_RISK_DECLINE, riskIntel.verdict)
        riskIntel.segmentEligibilities.forEach { seg ->
            assertEquals("Expected ${seg.productType} INELIGIBLE on tampered ePDF", com.example.bsa.engine.EligibilityStatus.INELIGIBLE, seg.status)
            assertEquals(0.0, seg.recommendedAmount, 0.01)
        }
    }

    @Test
    fun deepAnalyticsAndUnderwriterCam360EvaluateAndExportAll18ExcelTabs() = runBlocking {
        val verifiedAudit = listOf(
            FileForensicAudit("stmt.pdf", "ICICI Bank", "000905001122", "Aug-26", 2, 1600, "ICICI Core", "ICICI", 1, ForensicStatus.VERIFIED, listOf("Verified"))
        )
        val txns = listOf(
            // True business credits across 3 buyers
            BankTransaction(1, "2026-08-02", "02-Aug-26", "", "RTGS/ANCHOR BUYER LTD", 500000.0, "Transfer from Anchor Buyer", "Anchor Buyer Ltd", 600000.0, false, false, "stmt.pdf"),
            BankTransaction(2, "2026-08-05", "05-Aug-26", "", "NEFT/SECOND BUYER CO", 150000.0, "Transfer from Second Buyer", "Second Buyer Co", 750000.0, false, false, "stmt.pdf"),
            // Internal / Self Credit (inflates gross credits)
            BankTransaction(3, "2026-08-06", "06-Aug-26", "", "IMPS/SELF ACCOUNT XFER IN", 200000.0, "Transfer From Self", "VIKRAM TRADERS", 950000.0, true, false, "stmt.pdf"),
            // Matching Contra Debit within 1 day (₹2,00,000 circular / internal contra)
            BankTransaction(4, "2026-08-07", "07-Aug-26", "", "IMPS/SELF ACCOUNT XFER OUT", -200000.0, "Transfer To Self", "VIKRAM TRADERS", 750000.0, true, false, "stmt.pdf"),
            // EMI + related NACH bounce + Inward Cheque Bounce
            BankTransaction(5, "2026-08-08", "08-Aug-26", "", "NACH-DR/BAJAJ FINSERV EMI", -35000.0, "Loan", "Bajaj Finserv", 715000.0, false, false, "stmt.pdf"),
            BankTransaction(6, "2026-08-09", "09-Aug-26", "", "NACH RTN BAJAJ FINSERV INSUFF", -590.0, "Reversal", "Bajaj Finserv", 714410.0, false, false, "stmt.pdf"),
            BankTransaction(7, "2026-08-11", "11-Aug-26", "440123", "I/W CHQ RET 440123 INSUFFICIENT FUNDS", -15000.0, "Reversal", "Vendor X", 699410.0, false, false, "stmt.pdf"),
            // Sec 269ST high-value cash deposit >= ₹2,00,000
            BankTransaction(8, "2026-08-14", "14-Aug-26", "", "BY CASH DEPOSIT BRANCH MUMBAI", 250000.0, "Cash Deposit", "Cash Deposit", 949410.0, false, false, "stmt.pdf"),
            // Statutory payments (GST, Income Tax, Office Rent, Electricity Utility)
            BankTransaction(9, "2026-08-18", "18-Aug-26", "", "NEFT/GSTN CGST CHALLAN AUG", -48000.0, "Utilities", "GST", 901410.0, false, false, "stmt.pdf"),
            BankTransaction(10, "2026-08-20", "20-Aug-26", "", "NEFT/OLTAS INCOME TAX TDS", -22000.0, "Others", "Income Tax", 879410.0, false, false, "stmt.pdf"),
            BankTransaction(11, "2026-08-22", "22-Aug-26", "", "UPI/OFFICE RENT LANDLORD", -30000.0, "Others", "Landlord", 849410.0, false, false, "stmt.pdf"),
            BankTransaction(12, "2026-08-25", "25-Aug-26", "", "UPI/TATA POWER ELECTRICITY BILL", -12000.0, "Utilities", "Tata Power", 837410.0, false, false, "stmt.pdf")
        )

        val report = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-CAM-360", productType = ProductType.WORKING_CAPITAL, facilityType = FacilityType.NONE),
            metadata = AccountMetadata("ICICI Bank", "VIKRAM TRADERS", "Mumbai", "000905001122", "ICIC0000009", "Aug-26"),
            fileAudits = verifiedAudit,
            transactions = txns
        )

        // 1. Deep Transaction Analytics verification
        val deep = com.example.bsa.engine.DeepUnderwritingAnalytics.analyze(report)
        assertEquals(1100000.0, deep.internalTransfers.grossTotalCredits, 0.01)
        assertEquals(200000.0, deep.internalTransfers.internalSelfCredits, 0.01)
        assertEquals(900000.0, deep.internalTransfers.netTrueBusinessCredits, 0.01)
        assertTrue("Expected credit inflation ~18.18%", deep.internalTransfers.creditInflationPercent in 18.0..19.0)
        assertTrue("Expected >= 1 Contra/Circular pair", deep.internalTransfers.contraRotationPairs.isNotEmpty())
        assertEquals(200000.0, deep.internalTransfers.contraRotationPairs.first().amount, 0.01)

        assertEquals(2, deep.bounces.totalBounceCount)
        assertEquals(1, deep.bounces.nachEcsBounces)
        assertEquals(1, deep.bounces.inwardChequeBounces)
        assertEquals("Monitor / Clarify", deep.bounces.emiTrackRating)

        assertEquals(1, deep.channelsAndCash.sec269StAlertCount)
        assertEquals(1, deep.channelsAndCash.highValueCashTxns.size)
        assertTrue("Expected digital ratio > 70%", deep.channelsAndCash.digitalBankingRatioPct > 70.0)

        assertEquals(1, deep.statutory.gstPaymentCount)
        assertEquals(48000.0, deep.statutory.gstPaymentTotal, 0.01)
        assertEquals(1, deep.statutory.incomeTaxTdsCount)
        assertEquals(1, deep.statutory.rentOutflowCount)
        assertTrue(deep.statutory.utilityBillCount >= 1)
        assertTrue("Expected top buyer Anchor Buyer Ltd", deep.concentration.topCustomers.first().partyName == "Anchor Buyer Ltd")

        // 2. Underwriter 360° CAM Evaluation & JSON Round-Trip
        val intel = com.example.bsa.engine.CreditIntelligenceEngine.evaluate(report)
        val camProfile = UnderwriterCamProfile(
            bureauName = "CIBIL",
            bureauScore = 762,
            activeTradelines = 5,
            overdueAmount = 0.0,
            dpd30PlusCount12m = 0,
            dpd90PlusCount = 0,
            additionalBureauMonthlyEmi = 15000.0,
            annualGstTurnover = 12000000.0,
            itrGrossTurnover = 11500000.0,
            itrNetProfitPat = 1150000.0,
            itrDepreciation = 300000.0,
            itrInterestPaid = 250000.0,
            tangibleNetWorth = 4000000.0,
            totalOutsideLiabilities = 8000000.0,
            currentAssets = 6000000.0,
            currentLiabilities = 4000000.0,
            stockInventoryValue = 3200000.0,
            debtorsUnder90Days = 2000000.0,
            tradeCreditors = 1100000.0,
            collateralType = CollateralType.RESIDENTIAL_PROPERTY,
            collateralMarketValue = 2500000.0,
            proposedSanctionAmount = 2200000.0, // 88% LTV > 80% Residential cap -> must trigger Critical deviation
            kycPanAadhaarVerified = true,
            gstStatusActiveVerified = true,
            residenceOfficeFiCleared = true,
            personalDiscussionDone = true,
            legalTechnicalCleared = true,
            tradeReferencePositive = true,
            decisionStatus = CamDecisionStatus.SANCTION_WITH_CONDITIONS,
            underwriterRemarks = "Reduce sanction to 80% LTV or add secondary collateral."
        )

        // JSON persistence round-trip
        val restored = UnderwriterCamProfile.fromJson(camProfile.toJson())
        assertNotNull(restored)
        assertEquals(camProfile, restored)

        val camEval = UnderwriterCamEvaluator.evaluate(report, intel, deep, camProfile)
        assertEquals(50000.0, camEval.consolidatedMonthlyEmi, 0.01) // 35k bank + 15k bureau
        assertEquals(90.0, camEval.bankToGstRoutingPct ?: 0.0, 0.5) // 1.08 Cr / 1.20 Cr = 90%
        assertEquals(10.0, camEval.netProfitMarginPct ?: 0.0, 0.1)  // 11.5L / 115L = 10%
        assertEquals(2.0, camEval.leverageTolTnwRatio ?: 0.0, 0.01) // 80L / 40L = 2.0x
        assertEquals(1.5, camEval.currentRatio ?: 0.0, 0.01)        // 60L / 40L = 1.5x
        assertEquals(2800000.0, camEval.computedDrawingPower ?: 0.0, 1.0) // 0.75*(32L+20L)-11L = 28L
        assertEquals(88.0, camEval.ltvPercent ?: 0.0, 0.1)
        assertEquals(6, camEval.checklistCompletedCount)
        assertTrue(
            "Expected Critical Collateral LTV breach deviation",
            camEval.deviations.any { it.category == "Collateral" && it.severity == "Critical" }
        )
        assertTrue("Expected DSCR > 1.8x, got ${camEval.dscrRatio}", (camEval.dscrRatio ?: 0.0) > 1.8)

        // Executive brief with CAM profile includes 4-segment matrix & 360 CAM lines
        val brief = com.example.bsa.engine.CreditIntelligenceEngine.buildExecutiveBriefText(report, intel, camProfile)
        assertTrue(brief.contains("4-SEGMENT LOAN ELIGIBILITY MATRIX"))
        assertTrue(brief.contains("BEYOND-BSA UNDERWRITER 360"))
        assertTrue(brief.contains("CIBIL"))
        assertTrue(brief.contains("Collateral LTV"))

        // 3. Full 18-Tab Excel Export Verification
        assertEquals(18, ExcelReportGenerator.WORKBOOK_TABS.size)
        val xlsxBytes = excelGenerator.generateToBytes(report, camProfile = camProfile)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(xlsxBytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                e = zis.nextEntry
            }
        }
        val wbXml = entries["xl/workbook.xml"] ?: ""
        ExcelReportGenerator.WORKBOOK_TABS.forEachIndexed { idx, tab ->
            assertTrue("Missing tab $tab in workbook.xml", wbXml.contains("name=\"$tab\""))
            assertNotNull("Missing worksheet sheet${idx + 1}.xml for $tab", entries["xl/worksheets/sheet${idx + 1}.xml"])
        }

        // Verify Tab 12..18 rich content & live Excel formulas
        assertTrue((entries["xl/worksheets/sheet12.xml"] ?: "").contains("4-SEGMENT LOAN ELIGIBILITY ENGINE"))
        assertTrue((entries["xl/worksheets/sheet13.xml"] ?: "").contains("INTERNAL CREDITS, TRUE BTO"))
        assertTrue((entries["xl/worksheets/sheet14.xml"] ?: "").contains("BOUNCES, RETURNS"))
        assertTrue((entries["xl/worksheets/sheet15.xml"] ?: "").contains("<f>SUM("))
        assertTrue((entries["xl/worksheets/sheet16.xml"] ?: "").contains("<f>SUM("))
        assertTrue((entries["xl/worksheets/sheet17.xml"] ?: "").contains("Anchor Buyer Ltd"))
        val camSheet = entries["xl/worksheets/sheet18.xml"] ?: ""
        assertTrue(camSheet.contains("ONE-STOP CREDIT APPRAISAL MEMO (CAM 360"))
        assertTrue(camSheet.contains("Sanction with Conditions"))
        assertTrue(camSheet.contains("Reduce sanction to 80% LTV"))
    }

    @Test
    fun eligibilityCalculatorServiceComputesAllFourSegmentsAndRiskHaircuts() {
        val cleanInput = com.example.bsa.service.SummarizedMetricsInput(
            totalInflow = 12_000_000.0,
            totalOutflow = 9_600_000.0,
            averageBalance = 650_000.0,
            odUtilizationPct = 42.0,
            sanctionedOdLimit = 1_500_000.0,
            monthlyEmiObligation = 40_000.0,
            monthCount = 6,
            internalCreditsTotal = 1_200_000.0, // Must be deducted before annualized turnover
            chequeBounceCount = 0,
            emiReturnCount = 0,
            isSalaried = false
        )
        val cleanResult = com.example.bsa.service.EligibilityCalculatorService.calculateFromMetrics(cleanInput)
        assertEquals(10_800_000.0, cleanResult.adjustedTotalInflow, 0.01)
        assertEquals(21_600_000.0, cleanResult.annualizedTurnover, 0.01)
        assertEquals(0, cleanResult.riskHaircutPct)
        assertEquals(4, cleanResult.segments.size)

        val autoClean = cleanResult.segmentFor(ProductType.AUTO_LOAN)!!
        val bizClean = cleanResult.segmentFor(ProductType.BUSINESS_LOAN)!!
        val wcClean = cleanResult.segmentFor(ProductType.WORKING_CAPITAL)!!
        val homeClean = cleanResult.segmentFor(ProductType.HOME_LOAN)!!
        assertTrue(autoClean.recommendedAmount > 0.0)
        assertTrue(bizClean.recommendedAmount > 0.0)
        assertTrue(wcClean.recommendedAmount > 0.0)
        assertTrue(homeClean.recommendedAmount > 0.0)
        assertTrue(wcClean.isRevolvingFacility)

        // Applying Cheque Bounces, EMI Returns, and high OD Utilization must reduce eligibility
        val stressedResult = com.example.bsa.service.EligibilityCalculatorService.calculateFromMetrics(
            cleanInput.copy(odUtilizationPct = 96.0, chequeBounceCount = 2, emiReturnCount = 1)
        )
        assertTrue("Expected positive risk haircut, got ${stressedResult.riskHaircutPct}", stressedResult.riskHaircutPct >= 40)
        assertTrue(
            stressedResult.segmentFor(ProductType.WORKING_CAPITAL)!!.recommendedAmount < wcClean.recommendedAmount
        )
        assertTrue(
            stressedResult.segmentFor(ProductType.BUSINESS_LOAN)!!.recommendedAmount < bizClean.recommendedAmount
        )
    }

    @Test
    fun extendedParsingTracksInternalCreditsChequeBouncesAndEmiReturnsInDashboardAndCoreTabs() = runBlocking {
        val rawRows = listOf(
            com.example.bsa.engine.RawParsedTransaction(
                dateIso = "2026-08-02", displayDate = "02-Aug-26", chequeNo = "",
                description = "NEFT/CR/ALPHA INFRA TRADERS", withdrawal = null, deposit = 800_000.0,
                signedAmount = 800_000.0, normalizedBalance = 900_000.0, balancePolarityExplicit = "CR", sourceFile = "stmt.pdf"
            ) to false,
            com.example.bsa.engine.RawParsedTransaction(
                dateIso = "2026-08-05", displayDate = "05-Aug-26", chequeNo = "",
                description = "INTERNAL CREDIT OWN A/C SWEEP IN", withdrawal = null, deposit = 250_000.0,
                signedAmount = 250_000.0, normalizedBalance = 1_150_000.0, balancePolarityExplicit = "CR", sourceFile = "stmt.pdf"
            ) to false,
            com.example.bsa.engine.RawParsedTransaction(
                dateIso = "2026-08-10", displayDate = "10-Aug-26", chequeNo = "551021",
                description = "I/W CHQ RET 551021 INSUFFICIENT FUNDS", withdrawal = 120_000.0, deposit = null,
                signedAmount = -120_000.0, normalizedBalance = 1_030_000.0, balancePolarityExplicit = "CR", sourceFile = "stmt.pdf"
            ) to false,
            com.example.bsa.engine.RawParsedTransaction(
                dateIso = "2026-08-14", displayDate = "14-Aug-26", chequeNo = "",
                description = "NACH RTN EMI BOUNCE BAJAJ FINANCE", withdrawal = 45_000.0, deposit = null,
                signedAmount = -45_000.0, normalizedBalance = 985_000.0, balancePolarityExplicit = "CR", sourceFile = "stmt.pdf"
            ) to false,
            com.example.bsa.engine.RawParsedTransaction(
                dateIso = "2026-08-20", displayDate = "20-Aug-26", chequeNo = "",
                description = "ACH-DR HDFC HOME LOAN EMI", withdrawal = 65_000.0, deposit = null,
                signedAmount = -65_000.0, normalizedBalance = 920_000.0, balancePolarityExplicit = "CR", sourceFile = "stmt.pdf"
            ) to false
        )
        val categorized = com.example.bsa.engine.FuzzyMatcherAndCategorizer.categorizeAll("RAJENDER ENTERPRISES", rawRows)
        val report = com.example.bsa.engine.UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(
                applicantId = "YV-EXT-01",
                productType = ProductType.BUSINESS_LOAN,
                facilityType = FacilityType.NONE,
                sanctionedOdLimit = 0.0,
                drawingPowerLimit = 0.0
            ),
            metadata = AccountMetadata("HDFC Bank", "RAJENDER ENTERPRISES", "Hyderabad", "50200011223344", "HDFC0001234", "01-Aug-26 to 31-Aug-26"),
            fileAudits = emptyList(),
            transactions = categorized
        )

        assertEquals(250_000.0, report.totalInternalCredits, 0.01)
        assertEquals(1, report.totalInternalCreditCount)
        assertEquals(1, report.totalChequeBounceCount)
        assertEquals(120_000.0, report.totalChequeBounceAmount, 0.01)
        assertEquals(1, report.totalEmiReturnCount)
        assertEquals(45_000.0, report.totalEmiReturnAmount, 0.01)

        // Serializer round-trip preserves all 6 new monthly metric fields
        val restored = AnalysisReportSerializer.fromJson(AnalysisReportSerializer.toJson(report))
        assertEquals(report.totalInternalCredits, restored.totalInternalCredits, 0.01)
        assertEquals(report.totalInternalCreditCount, restored.totalInternalCreditCount)
        assertEquals(report.totalChequeBounceCount, restored.totalChequeBounceCount)
        assertEquals(report.totalEmiReturnCount, restored.totalEmiReturnCount)

        // EligibilityCalculatorService from report works cleanly
        val fromReport = com.example.bsa.service.EligibilityCalculatorService.calculateFromReport(report)
        assertEquals(1, fromReport.input.chequeBounceCount)
        assertEquals(1, fromReport.input.emiReturnCount)
        assertTrue(fromReport.riskHaircutPct >= 32)

        // Verify Excel Tab 1 (Summary) and Tab 2 (Derived_Sheet) contain the new metric rows
        val xlsxBytes = ExcelReportGenerator().generateToBytes(report)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(xlsxBytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                e = zis.nextEntry
            }
        }
        val tab1 = entries["xl/worksheets/sheet1.xml"] ?: ""
        val tab2 = entries["xl/worksheets/sheet2.xml"] ?: ""
        assertTrue(tab1.contains("36. Internal Credits Amount"))
        assertTrue(tab1.contains("38. Cheque Bounce Amount"))
        assertTrue(tab1.contains("40. EMI Return / NACH Bounce Amount"))
        assertTrue(tab2.contains("36. Internal Credits Amount"))
        assertTrue(tab2.contains("39. Cheque Bounce Count"))
        assertTrue(tab2.contains("41. EMI Return / NACH Bounce Count"))
    }

    @Test
    fun portfolioRiskEngineConsolidatesMultipleAccountsAndDeductsCrossAccountContras() {
        val audits = listOf(
            FileForensicAudit("icici_od.pdf", "ICICI Bank", "000805019482", "Apr-26 to Jun-26", 1, 1400, "ICICI Core", "ICICI", 1, ForensicStatus.VERIFIED, listOf("Verified")),
            FileForensicAudit("hdfc_ca.pdf", "HDFC Bank", "50200041982214", "Apr-26 to Jun-26", 1, 1350, "HDFC Core", "HDFC", 1, ForensicStatus.VERIFIED, listOf("Verified"))
        )
        val multiTxns = listOf(
            // Account 1 (ICICI Bank)
            BankTransaction(1, "2026-06-02", "02-Jun-26", "", "NEFT/CR/CLIENT ALPHA", 600_000.0, "Transfer from Client Alpha", "Client Alpha", 750_000.0, false, false, "icici_od.pdf"),
            BankTransaction(2, "2026-06-05", "05-Jun-26", "", "ACH DR/HDFC HOME LOAN EMI", -50_000.0, "Loan / EMI", "HDFC Ltd", 700_000.0, false, false, "icici_od.pdf"),
            BankTransaction(3, "2026-06-24", "24-Jun-26", "", "IMPS/CR/INTER-BANK OWN TRANSFER", 150_000.0, "Transfer from Own", "Own", 850_000.0, false, false, "icici_od.pdf"),
            // Account 2 (HDFC Bank) with matching Rs.1,50,000 debit on same day + 1 cheque bounce
            BankTransaction(4, "2026-06-04", "04-Jun-26", "", "RTGS/CR/CLIENT BETA", 400_000.0, "Transfer from Client Beta", "Client Beta", 550_000.0, false, false, "hdfc_ca.pdf"),
            BankTransaction(5, "2026-06-12", "12-Jun-26", "401122", "CHQ RTN INWARD 401122 INSUFFICIENT FUNDS", -32_000.0, "Reversal", "Vendor", 518_000.0, false, false, "hdfc_ca.pdf"),
            BankTransaction(6, "2026-06-24", "24-Jun-26", "", "IMPS/DR/ICICI A/C TRANSFER", -150_000.0, "Transfer to Own", "Own", 368_000.0, false, false, "hdfc_ca.pdf")
        )

        val report = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-PORT-01", productType = ProductType.BUSINESS_LOAN, facilityType = FacilityType.NONE),
            metadata = AccountMetadata("ICICI Bank + HDFC Bank (2 Accounts)", "RAJENDER ENTERPRISES", "Hyderabad", "000805019482 (+1 more)", "ICIC0000008", "02-Jun-26 to 24-Jun-26"),
            fileAudits = audits,
            transactions = multiTxns
        )

        val portfolio = com.example.bsa.engine.PortfolioRiskEngine.evaluate(report)
        assertEquals(2, portfolio.accountCount)
        assertTrue(portfolio.isMultiAccount)
        assertEquals(1, portfolio.crossAccountTransfers.size)
        assertEquals(150_000.0, portfolio.crossAccountContraTotal, 0.01)
        // Gross = 6L + 1.5L + 4L = 11.5L; True after stripping 1.5L cross-account contra = 10L
        assertEquals(1_150_000.0, portfolio.consolidatedGrossInflow, 0.01)
        assertEquals(1_000_000.0, portfolio.consolidatedTrueInflow, 0.01)
        assertFalse("65%/35% split should not trigger >=80% concentration alert", portfolio.hasConcentrationRisk)
        assertTrue("Expected portfolio risk score in 0..100", portfolio.portfolioRiskScore in 0..100)
        assertTrue("Expected positive ABB-to-EMI coverage ratio", portfolio.emiToAbbCoverageRatio > 0.0)
    }

    @Test
    fun pdfSummaryReportGeneratorProducesValidBrandedPdfWithEligibilityAndRiskScore() = runBlocking {
        val audits = listOf(
            FileForensicAudit("stmt1.pdf", "ICICI Bank", "000805019482", "Aug-26", 1, 1500, "ICICI Core", "ICICI", 1, ForensicStatus.VERIFIED, listOf("Verified"))
        )
        val txns = listOf(
            BankTransaction(1, "2026-08-02", "02-Aug-26", "", "NEFT/CR/BUYER ALPHA", 550_000.0, "Transfer from Buyer Alpha", "Buyer Alpha", 650_000.0, false, false, "stmt1.pdf"),
            BankTransaction(2, "2026-08-05", "05-Aug-26", "", "ACH-DR/HDFC EMI", -35_000.0, "Loan", "HDFC Bank", 615_000.0, false, false, "stmt1.pdf"),
            BankTransaction(3, "2026-08-18", "18-Aug-26", "", "RTGS/CR/BUYER BETA", 450_000.0, "Transfer from Buyer Beta", "Buyer Beta", 1_065_000.0, false, false, "stmt1.pdf")
        )
        val report = UnderwritingAnalyticsEngine.analyze(
            config = ApplicantConfig(applicantId = "YV-PDF-SUM-01", productType = ProductType.WORKING_CAPITAL, facilityType = FacilityType.NONE),
            metadata = AccountMetadata("ICICI Bank", "VIKRAM TRADERS", "Mumbai", "000805019482", "ICIC0000008", "01-Aug-26 to 31-Aug-26"),
            fileAudits = audits,
            transactions = txns
        )

        val pdfBytes = excelGenerator.generatePdfSummaryToBytes(report)
        assertTrue("PDF output must start with %PDF- header", String(pdfBytes.copyOfRange(0, 5), Charsets.US_ASCII) == "%PDF-")

        com.tom_roush.pdfbox.pdmodel.PDDocument.load(ByteArrayInputStream(pdfBytes)).use { doc ->
            assertEquals("Branded summary should render 2 crisp A4 pages", 2, doc.numberOfPages)
            val extracted = com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
            assertTrue(extracted.contains("BSA-YourView"))
            assertTrue(extracted.contains("yourview.in"))
            assertTrue(extracted.contains("YV-PDF-SUM-01"))
            assertTrue(extracted.contains("10-POINT RISK SCORE"))
            assertTrue(extracted.contains("CREDIT HEALTH SCORE"))
            assertTrue(extracted.contains("4-SEGMENT LOAN ELIGIBILITY MATRIX"))
            assertTrue(extracted.contains("Auto Loan"))
            assertTrue(extracted.contains("Business Loan"))
            assertTrue(extracted.contains("Working Capital"))
            assertTrue(extracted.contains("Home Loan"))
            assertTrue(extracted.contains("CONSOLIDATED PORTFOLIO RISK BREAKDOWN"))
        }
    }
}



