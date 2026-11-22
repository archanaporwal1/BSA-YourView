package com.example.bsa.engine

import java.util.Locale

enum class BankCategory(val label: String, val shortLabel: String) {
    PUBLIC_SECTOR("Public Sector Banks", "PSU"),
    PRIVATE_SECTOR("Private Sector Banks", "Private"),
    SMALL_FINANCE("Small Finance Banks", "Small Finance"),
    PAYMENTS("Payments Banks", "Payments"),
    COOPERATIVE("Co-operative Banks", "Co-op"),
    REGIONAL_RURAL("Regional Rural Banks", "RRB"),
    FOREIGN("Foreign Banks (India)", "Foreign")
}

data class IndianBankSpec(
    val canonicalName: String,
    val category: BankCategory,
    val ifscPrefixes: List<String>,
    val headerTokens: List<String>,
    val filenameTokens: List<String> = emptyList(),
    val hasSeparateValueDate: Boolean = false,
    val headerKeywords: List<String> = DEFAULT_HEADER_KEYWORDS,
    val typicalColumns: List<String> = DEFAULT_COLUMNS
) {
    companion object {
        val DEFAULT_HEADER_KEYWORDS = listOf("DATE", "PARTICULARS", "NARRATION", "DESCRIPTION", "DEBIT", "CREDIT", "BALANCE")
        val DEFAULT_COLUMNS = listOf("Date", "Particulars", "Chq/Ref No", "Debit", "Credit", "Balance")
    }
}

/**
 * Comprehensive Registry of 130+ RBI-Regulated Indian Banks + Universal Dynamic Any-Bank Fallback.
 * Covers all 12 PSUs, 21 Private Sector Banks, 11 Small Finance Banks, 6 Payments Banks,
 * 20 Foreign Banks operating in India, 35 Major Scheduled Urban/State Co-operative Banks,
 * 30 Regional Rural Banks (RRBs), plus legacy merged bank IFSC aliases (Andhra, Syndicate,
 * Corporation, Allahabad, Oriental, Vijaya, Dena, LVB, Fincare, PMC, SBI Associates).
 */
object IndianBankRegistry {

    private fun spec(
        name: String,
        cat: BankCategory,
        ifsc: List<String>,
        tokens: List<String>,
        files: List<String> = emptyList(),
        valueDate: Boolean = false,
        keywords: List<String> = IndianBankSpec.DEFAULT_HEADER_KEYWORDS,
        columns: List<String> = IndianBankSpec.DEFAULT_COLUMNS
    ) = IndianBankSpec(
        canonicalName = name,
        category = cat,
        ifscPrefixes = ifsc,
        headerTokens = tokens,
        filenameTokens = files,
        hasSeparateValueDate = valueDate,
        headerKeywords = keywords,
        typicalColumns = columns
    )

    val ALL_BANKS: List<IndianBankSpec> = listOf(
        // ==================== 12 PUBLIC SECTOR BANKS (ALL PSUs) ====================
        spec(
            name = "SBI",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("SBIN0", "SBHY0", "SBMY0", "STBP0", "SBTR0", "SBBJ0", "BMBL0"),
            tokens = listOf(
                "STATE BANK OF INDIA", "STATE BANK OF HYDERABAD", "STATE BANK OF MYSORE",
                "STATE BANK OF PATIALA", "STATE BANK OF TRAVANCORE", "STATE BANK OF BIKANER",
                "BHARATIYA MAHILA BANK"
            ),
            files = listOf("SBI", "STATE_BANK", "SBIN"),
            valueDate = true,
            keywords = listOf("TXN DATE", "VALUE DATE", "DESCRIPTION", "REF NO./CHEQUE NO.", "DEBIT", "CREDIT", "BALANCE"),
            columns = listOf("Txn Date", "Value Date", "Description", "Ref No./Cheque No.", "Debit", "Credit", "Balance")
        ),
        spec(
            name = "Bank of Baroda",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("BARB0", "VIJB0", "BKDN0"),
            tokens = listOf("BANK OF BARODA", "VIJAYA BANK", "DENA BANK"),
            files = listOf("BARODA", "BOB", "BARB", "VIJAYA", "DENA"),
            valueDate = true
        ),
        spec(
            name = "Punjab National Bank",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("PUNB0", "ORBC0", "UTBI0"),
            tokens = listOf("PUNJAB NATIONAL BANK", "ORIENTAL BANK OF COMMERCE", "UNITED BANK OF INDIA"),
            files = listOf("PNB", "PUNJAB_NATIONAL", "PUNB", "OBC")
        ),
        spec(
            name = "Canara Bank",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("CNRB0", "SYNB0"),
            tokens = listOf("CANARA BANK", "SYNDICATE BANK"),
            files = listOf("CANARA", "CNRB", "SYNDICATE")
        ),
        spec(
            name = "Union Bank",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("UBIN0", "ANDB0", "CORP0"),
            tokens = listOf("UNION BANK OF INDIA", "ANDHRA BANK", "CORPORATION BANK"),
            files = listOf("UNION_BANK", "UNION", "UBIN", "ANDHRA_BANK", "CORP_BANK"),
            keywords = listOf("DATE", "PARTICULARS", "CHQ NO", "DEBIT", "CREDIT", "BALANCE")
        ),
        spec(
            name = "Bank of India",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("BKID0"),
            tokens = listOf("BANK OF INDIA"),
            files = listOf("BANK_OF_INDIA", "BOI", "BKID")
        ),
        spec(
            name = "Indian Bank",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("IDIB0", "ALLA0"),
            tokens = listOf("INDIAN BANK", "ALLAHABAD BANK"),
            files = listOf("INDIAN_BANK", "IDIB", "ALLAHABAD")
        ),
        spec(
            name = "Central Bank of India",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("CBIN0"),
            tokens = listOf("CENTRAL BANK OF INDIA"),
            files = listOf("CENTRAL_BANK", "CBIN")
        ),
        spec(
            name = "Indian Overseas Bank",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("IOBA0"),
            tokens = listOf("INDIAN OVERSEAS BANK"),
            files = listOf("IOB", "INDIAN_OVERSEAS", "IOBA")
        ),
        spec(
            name = "UCO Bank",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("UCBA0"),
            tokens = listOf("UCO BANK"),
            files = listOf("UCO", "UCBA")
        ),
        spec(
            name = "Bank of Maharashtra",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("MAHB0"),
            tokens = listOf("BANK OF MAHARASHTRA", "MAHABANK"),
            files = listOf("MAHARASHTRA", "MAHB", "BOM")
        ),
        spec(
            name = "Punjab & Sind Bank",
            cat = BankCategory.PUBLIC_SECTOR,
            ifsc = listOf("PSIB0"),
            tokens = listOf("PUNJAB AND SIND BANK", "PUNJAB & SIND BANK"),
            files = listOf("PUNJAB_SIND", "PSIB")
        ),

        // ==================== 21 PRIVATE SECTOR BANKS (ALL SCHEDULED PRIVATE) ====================
        spec(
            name = "HDFC Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("HDFC0"),
            tokens = listOf("HDFC BANK"),
            files = listOf("HDFC"),
            valueDate = true,
            keywords = listOf("DATE", "NARRATION", "CHQ./REF.NO.", "VALUE DT", "WITHDRAWAL AMT", "DEPOSIT AMT", "CLOSING BALANCE"),
            columns = listOf("Date", "Narration", "Chq./Ref.No.", "Value Dt", "Withdrawal Amt.", "Deposit Amt.", "Closing Balance")
        ),
        spec(
            name = "ICICI Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("ICIC0"),
            tokens = listOf("ICICI BANK"),
            files = listOf("ICICI", "ICIC"),
            valueDate = true,
            keywords = listOf("VALUE DATE", "TRANSACTION DATE", "TRANSACTION REMARKS", "WITHDRAWAL AMOUNT", "DEPOSIT AMOUNT", "BALANCE"),
            columns = listOf("S No.", "Value Date", "Transaction Date", "Cheque Number", "Transaction Remarks", "Withdrawal Amount (INR)", "Deposit Amount (INR)", "Balance (INR)")
        ),
        spec(
            name = "Axis Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("UTIB0"),
            tokens = listOf("AXIS BANK"),
            files = listOf("AXIS", "UTIB"),
            keywords = listOf("TRAN DATE", "CHQ NO", "PARTICULARS", "DEBIT", "CREDIT", "BALANCE"),
            columns = listOf("Tran Date", "Chq No", "Particulars", "Debit", "Credit", "Balance")
        ),
        spec(
            name = "Kotak Mahindra Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("KKBK0", "INGV0"),
            tokens = listOf("KOTAK MAHINDRA BANK", "KOTAK MAHINDRA", "ING VYSYA BANK"),
            files = listOf("KOTAK", "KKBK"),
            keywords = listOf("DATE", "NARRATION", "CHQ/REF NO", "WITHDRAWAL(DR)", "DEPOSIT(CR)", "BALANCE"),
            columns = listOf("Date", "Narration", "Chq/Ref No", "Withdrawal(Dr)", "Deposit(Cr)", "Balance")
        ),
        spec(
            name = "IndusInd Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("INDB0"),
            tokens = listOf("INDUSIND BANK", "INDUSIND"),
            files = listOf("INDUSIND", "INDB")
        ),
        spec(
            name = "IDFC FIRST Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("IDFB0"),
            tokens = listOf("IDFC FIRST BANK", "IDFC FIRST", "IDFC BANK"),
            files = listOf("IDFC", "IDFB"),
            valueDate = true
        ),
        spec(
            name = "Yes Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("YESB0"),
            tokens = listOf("YES BANK"),
            files = listOf("YES_BANK", "YESBANK", "YESB"),
            valueDate = true
        ),
        spec(
            name = "Federal Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("FDRL0"),
            tokens = listOf("FEDERAL BANK"),
            files = listOf("FEDERAL", "FDRL")
        ),
        spec(
            name = "IDBI Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("IBKL0"),
            tokens = listOf("IDBI BANK"),
            files = listOf("IDBI", "IBKL"),
            valueDate = true
        ),
        spec(
            name = "RBL Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("RATN0"),
            tokens = listOf("RBL BANK", "RATNAKAR BANK"),
            files = listOf("RBL", "RATN")
        ),
        spec(
            name = "Bandhan Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("BDBL0"),
            tokens = listOf("BANDHAN BANK"),
            files = listOf("BANDHAN", "BDBL")
        ),
        spec(
            name = "South Indian Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("SIBL0"),
            tokens = listOf("SOUTH INDIAN BANK"),
            files = listOf("SOUTH_INDIAN", "SIBL")
        ),
        spec(
            name = "Karur Vysya Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("KVBL0"),
            tokens = listOf("KARUR VYSYA BANK"),
            files = listOf("KVB", "KARUR", "KVBL")
        ),
        spec(
            name = "City Union Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("CIUB0"),
            tokens = listOf("CITY UNION BANK"),
            files = listOf("CITY_UNION", "CIUB", "CUB")
        ),
        spec(
            name = "Karnataka Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("KARB0"),
            tokens = listOf("KARNATAKA BANK"),
            files = listOf("KARNATAKA_BANK", "KARB", "KBL")
        ),
        spec(
            name = "Tamilnad Mercantile Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("TMBL0"),
            tokens = listOf("TAMILNAD MERCANTILE BANK", "TAMILNADU MERCANTILE BANK"),
            files = listOf("TMB", "TAMILNAD", "TMBL")
        ),
        spec(
            name = "CSB Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("CSBK0"),
            tokens = listOf("CSB BANK", "CATHOLIC SYRIAN BANK"),
            files = listOf("CSB", "CSBK")
        ),
        spec(
            name = "DCB Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("DCBL0"),
            tokens = listOf("DCB BANK", "DEVELOPMENT CREDIT BANK"),
            files = listOf("DCB", "DCBL")
        ),
        spec(
            name = "Dhanlaxmi Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("DLXB0"),
            tokens = listOf("DHANLAXMI BANK", "DHANALAKSHMI BANK"),
            files = listOf("DHANLAXMI", "DLXB")
        ),
        spec(
            name = "Jammu & Kashmir Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("JAKA0"),
            tokens = listOf("JAMMU AND KASHMIR BANK", "JAMMU & KASHMIR BANK", "J&K BANK"),
            files = listOf("JKBANK", "JAMMU", "JAKA")
        ),
        spec(
            name = "Nainital Bank",
            cat = BankCategory.PRIVATE_SECTOR,
            ifsc = listOf("NTBL0"),
            tokens = listOf("NAINITAL BANK"),
            files = listOf("NAINITAL", "NTBL")
        ),

        // ==================== 11 SMALL FINANCE BANKS (ALL ACTIVE SFBs) ====================
        spec(
            name = "AU Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("AUBL0", "FSFB0"),
            tokens = listOf("AU SMALL FINANCE BANK", "AU SMALL FINANCE", "FINCARE SMALL FINANCE BANK"),
            files = listOf("AUSFB", "AU_BANK", "AUBL", "FINCARE", "FSFB")
        ),
        spec(
            name = "Equitas Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("ESFB0"),
            tokens = listOf("EQUITAS SMALL FINANCE BANK", "EQUITAS SMALL FINANCE", "EQUITAS BANK"),
            files = listOf("EQUITAS", "ESFB")
        ),
        spec(
            name = "Ujjivan Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("UJVN0"),
            tokens = listOf("UJJIVAN SMALL FINANCE BANK", "UJJIVAN SMALL FINANCE", "UJJIVAN BANK"),
            files = listOf("UJJIVAN", "UJVN")
        ),
        spec(
            name = "Jana Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("JSFB0"),
            tokens = listOf("JANA SMALL FINANCE BANK", "JANA SMALL FINANCE", "JANA BANK"),
            files = listOf("JANA", "JSFB")
        ),
        spec(
            name = "ESAF Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("ESMF0"),
            tokens = listOf("ESAF SMALL FINANCE BANK", "ESAF SMALL FINANCE", "ESAF BANK"),
            files = listOf("ESAF", "ESMF")
        ),
        spec(
            name = "Suryoday Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("SURY0"),
            tokens = listOf("SURYODAY SMALL FINANCE BANK", "SURYODAY SMALL FINANCE", "SURYODAY BANK"),
            files = listOf("SURYODAY", "SURY")
        ),
        spec(
            name = "Utkarsh Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("UTKS0"),
            tokens = listOf("UTKARSH SMALL FINANCE BANK", "UTKARSH SMALL FINANCE", "UTKARSH BANK"),
            files = listOf("UTKARSH", "UTKS")
        ),
        spec(
            name = "Capital Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("CLBL0"),
            tokens = listOf("CAPITAL SMALL FINANCE BANK", "CAPITAL SMALL FINANCE", "CAPITAL LOCAL AREA BANK"),
            files = listOf("CAPITAL_SFB", "CLBL")
        ),
        spec(
            name = "North East Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("NESF0"),
            tokens = listOf("NORTH EAST SMALL FINANCE BANK", "NORTH EAST SMALL FINANCE", "SLICE SMALL FINANCE BANK"),
            files = listOf("NESFB", "NORTH_EAST", "NESF")
        ),
        spec(
            name = "Shivalik Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("SMCB0"),
            tokens = listOf("SHIVALIK SMALL FINANCE BANK", "SHIVALIK SMALL FINANCE", "SHIVALIK MERCANTILE"),
            files = listOf("SHIVALIK", "SMCB")
        ),
        spec(
            name = "Unity Small Finance Bank",
            cat = BankCategory.SMALL_FINANCE,
            ifsc = listOf("UNBA0", "PMCB0"),
            tokens = listOf("UNITY SMALL FINANCE BANK", "UNITY SMALL FINANCE", "PUNJAB AND MAHARASHTRA CO-OPERATIVE"),
            files = listOf("UNITY_SFB", "UNBA")
        ),

        // ==================== 6 PAYMENTS BANKS (ALL ACTIVE) ====================
        spec(
            name = "Airtel Payments Bank",
            cat = BankCategory.PAYMENTS,
            ifsc = listOf("AIRP0"),
            tokens = listOf("AIRTEL PAYMENTS BANK"),
            files = listOf("AIRTEL", "AIRP")
        ),
        spec(
            name = "India Post Payments Bank",
            cat = BankCategory.PAYMENTS,
            ifsc = listOf("IPOS0"),
            tokens = listOf("INDIA POST PAYMENTS BANK", "IPPB"),
            files = listOf("IPPB", "INDIA_POST", "IPOS")
        ),
        spec(
            name = "Fino Payments Bank",
            cat = BankCategory.PAYMENTS,
            ifsc = listOf("FINO0"),
            tokens = listOf("FINO PAYMENTS BANK"),
            files = listOf("FINO")
        ),
        spec(
            name = "Jio Payments Bank",
            cat = BankCategory.PAYMENTS,
            ifsc = listOf("JIOP0"),
            tokens = listOf("JIO PAYMENTS BANK"),
            files = listOf("JIO_PAYMENTS", "JIOP")
        ),
        spec(
            name = "NSDL Payments Bank",
            cat = BankCategory.PAYMENTS,
            ifsc = listOf("NSPB0"),
            tokens = listOf("NSDL PAYMENTS BANK"),
            files = listOf("NSDL", "NSPB")
        ),
        spec(
            name = "Paytm Payments Bank",
            cat = BankCategory.PAYMENTS,
            ifsc = listOf("PYTM0"),
            tokens = listOf("PAYTM PAYMENTS BANK"),
            files = listOf("PAYTM", "PYTM")
        ),

        // ==================== 20 FOREIGN BANKS IN INDIA ====================
        spec(
            name = "Standard Chartered Bank",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("SCBL0"),
            tokens = listOf("STANDARD CHARTERED BANK", "STANDARD CHARTERED"),
            files = listOf("STANCHART", "SCBL", "STANDARD_CHARTERED"),
            valueDate = true
        ),
        spec(
            name = "HSBC Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("HSBC0"),
            tokens = listOf("HSBC BANK", "HONGKONG AND SHANGHAI BANKING", "HSBC INDIA"),
            files = listOf("HSBC"),
            valueDate = true
        ),
        spec(
            name = "Citibank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("CITI0"),
            tokens = listOf("CITIBANK", "CITI BANK"),
            files = listOf("CITIBANK", "CITI")
        ),
        spec(
            name = "DBS Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("DBSS0", "LAVB0"),
            tokens = listOf("DBS BANK INDIA", "DBS BANK", "LAKSHMI VILAS BANK"),
            files = listOf("DBS", "DBSS", "LVB", "LAKSHMI_VILAS")
        ),
        spec(
            name = "Deutsche Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("DEUT0"),
            tokens = listOf("DEUTSCHE BANK"),
            files = listOf("DEUTSCHE", "DEUT")
        ),
        spec(
            name = "Barclays Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("BARC0"),
            tokens = listOf("BARCLAYS BANK"),
            files = listOf("BARCLAYS", "BARC")
        ),
        spec(
            name = "Bank of America India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("BOFA0"),
            tokens = listOf("BANK OF AMERICA"),
            files = listOf("BOFA", "BANK_OF_AMERICA")
        ),
        spec(
            name = "JPMorgan Chase Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("CHAS0"),
            tokens = listOf("JPMORGAN CHASE BANK", "JP MORGAN CHASE", "JPMORGAN"),
            files = listOf("JPMORGAN", "CHAS")
        ),
        spec(
            name = "BNP Paribas India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("BNPA0"),
            tokens = listOf("BNP PARIBAS"),
            files = listOf("BNP", "BNPA")
        ),
        spec(
            name = "MUFG Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("BOTM0"),
            tokens = listOf("MUFG BANK", "BANK OF TOKYO-MITSUBISHI"),
            files = listOf("MUFG", "BOTM")
        ),
        spec(
            name = "Sumitomo Mitsui Banking Corp",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("SMBC0"),
            tokens = listOf("SUMITOMO MITSUI BANKING"),
            files = listOf("SMBC")
        ),
        spec(
            name = "Mizuho Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("MHCB0"),
            tokens = listOf("MIZUHO BANK"),
            files = listOf("MIZUHO", "MHCB")
        ),
        spec(
            name = "SBM Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("STCB0"),
            tokens = listOf("SBM BANK INDIA", "SBM BANK", "STATE BANK OF MAURITIUS"),
            files = listOf("SBM_BANK", "STCB")
        ),
        spec(
            name = "Doha Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("DOHB0"),
            tokens = listOf("DOHA BANK"),
            files = listOf("DOHA", "DOHB")
        ),
        spec(
            name = "Mashreq Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("MSHQ0"),
            tokens = listOf("MASHREQ BANK", "MASHREQBANK"),
            files = listOf("MASHREQ", "MSHQ")
        ),
        spec(
            name = "Emirates NBD Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("EBIL0"),
            tokens = listOf("EMIRATES NBD BANK", "EMIRATES NBD"),
            files = listOf("EMIRATES_NBD", "EBIL")
        ),
        spec(
            name = "First Abu Dhabi Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("NBAD0"),
            tokens = listOf("FIRST ABU DHABI BANK", "NATIONAL BANK OF ABU DHABI"),
            files = listOf("FAB_INDIA", "NBAD")
        ),
        spec(
            name = "Qatar National Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("QNBA0"),
            tokens = listOf("QATAR NATIONAL BANK"),
            files = listOf("QNB", "QNBA")
        ),
        spec(
            name = "Rabobank International India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("RABO0"),
            tokens = listOf("RABOBANK", "COOPERATIEVE RABOBANK"),
            files = listOf("RABOBANK", "RABO")
        ),
        spec(
            name = "Shinhan Bank India",
            cat = BankCategory.FOREIGN,
            ifsc = listOf("SHBK0"),
            tokens = listOf("SHINHAN BANK"),
            files = listOf("SHINHAN", "SHBK")
        ),

        // ==================== 35 SCHEDULED URBAN & STATE CO-OPERATIVE BANKS ====================
        spec(
            name = "Saraswat Co-operative Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("SRCB0"),
            tokens = listOf("SARASWAT CO-OPERATIVE BANK", "SARASWAT COOPERATIVE BANK", "SARASWAT BANK"),
            files = listOf("SARASWAT", "SRCB")
        ),
        spec(
            name = "Cosmos Co-operative Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("COSB0"),
            tokens = listOf("COSMOS CO-OPERATIVE BANK", "COSMOS COOPERATIVE BANK", "COSMOS BANK"),
            files = listOf("COSMOS", "COSB")
        ),
        spec(
            name = "SVC Co-operative Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("SVCB0"),
            tokens = listOf("SVC CO-OPERATIVE BANK", "SVC COOPERATIVE BANK", "SHAMRAO VITHAL CO-OPERATIVE", "SVC BANK"),
            files = listOf("SVCB", "SHAMRAO")
        ),
        spec(
            name = "Abhyudaya Co-operative Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("ABHY0"),
            tokens = listOf("ABHYUDAYA CO-OPERATIVE BANK", "ABHYUDAYA COOPERATIVE BANK", "ABHYUDAYA BANK"),
            files = listOf("ABHYUDAYA", "ABHY")
        ),
        spec(
            name = "TJSB Sahakari Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("TJSB0"),
            tokens = listOf("TJSB SAHAKARI BANK", "THANE JANATA SAHAKARI BANK"),
            files = listOf("TJSB", "THANE_JANATA")
        ),
        spec(
            name = "Bharat Co-operative Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("BCBM0"),
            tokens = listOf("BHARAT CO-OPERATIVE BANK", "BHARAT COOPERATIVE BANK"),
            files = listOf("BHARAT_COOP", "BCBM")
        ),
        spec(
            name = "NKGSB Co-operative Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("NKGS0"),
            tokens = listOf("NKGSB CO-OPERATIVE BANK", "NKGSB COOPERATIVE BANK", "NKGSB BANK"),
            files = listOf("NKGSB", "NKGS")
        ),
        spec(
            name = "Janata Sahakari Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("JSBP0"),
            tokens = listOf("JANATA SAHAKARI BANK"),
            files = listOf("JANATA_SAHAKARI", "JSBP")
        ),
        spec(
            name = "Apna Sahakari Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("ASBL0"),
            tokens = listOf("APNA SAHAKARI BANK"),
            files = listOf("APNA_SAHAKARI", "ASBL")
        ),
        spec(
            name = "Kalupur Commercial Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("KCCB0"),
            tokens = listOf("KALUPUR COMMERCIAL CO-OPERATIVE", "KALUPUR COMMERCIAL COOPERATIVE", "KALUPUR BANK"),
            files = listOf("KALUPUR", "KCCB")
        ),
        spec(
            name = "Mehsana Urban Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("MSNU0"),
            tokens = listOf("MEHSANA URBAN CO-OPERATIVE", "MEHSANA URBAN COOPERATIVE", "MEHSANA URBAN BANK"),
            files = listOf("MEHSANA", "MSNU")
        ),
        spec(
            name = "GP Parsik Sahakari Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("PJSB0"),
            tokens = listOf("G. P. PARSIK SAHAKARI BANK", "GP PARSIK SAHAKARI BANK", "PARSIK JANATA SAHAKARI"),
            files = listOf("PARSIK", "PJSB")
        ),
        spec(
            name = "Bassein Catholic Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("BACB0"),
            tokens = listOf("BASSEIN CATHOLIC CO-OPERATIVE", "BASSEIN CATHOLIC COOPERATIVE", "BASSEIN CATHOLIC BANK"),
            files = listOf("BASSEIN", "BACB")
        ),
        spec(
            name = "DNS Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("DNSB0"),
            tokens = listOf("DOMBIVLI NAGARI SAHAKARI BANK", "DNS BANK"),
            files = listOf("DNSB", "DOMBIVLI")
        ),
        spec(
            name = "Kalyan Janata Sahakari Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("KJSB0"),
            tokens = listOf("KALYAN JANATA SAHAKARI BANK"),
            files = listOf("KJSB", "KALYAN_JANATA")
        ),
        spec(
            name = "Karad Urban Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("KUCB0"),
            tokens = listOf("KARAD URBAN CO-OPERATIVE", "KARAD URBAN COOPERATIVE"),
            files = listOf("KARAD", "KUCB")
        ),
        spec(
            name = "Sangli Urban Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("SUCB0"),
            tokens = listOf("SANGLI URBAN CO-OPERATIVE", "SANGLI URBAN COOPERATIVE"),
            files = listOf("SANGLI", "SUCB")
        ),
        spec(
            name = "AP Mahesh Co-op Urban Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("APMC0"),
            tokens = listOf("A.P. MAHESH CO-OPERATIVE", "AP MAHESH CO-OPERATIVE", "MAHESH BANK"),
            files = listOf("MAHESH_BANK", "APMC")
        ),
        spec(
            name = "Nutan Nagarik Sahakari Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("NNSB0"),
            tokens = listOf("NUTAN NAGARIK SAHAKARI BANK"),
            files = listOf("NUTAN_NAGARIK", "NNSB")
        ),
        spec(
            name = "Rajkot Nagarik Sahakari Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("RNSB0"),
            tokens = listOf("RAJKOT NAGARIK SAHAKARI BANK"),
            files = listOf("RAJKOT_NAGARIK", "RNSB")
        ),
        spec(
            name = "Surat People's Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("SPCB0"),
            tokens = listOf("SURAT PEOPLE'S CO-OPERATIVE", "SURAT PEOPLES CO-OPERATIVE", "SURAT NATIONAL CO-OPERATIVE"),
            files = listOf("SURAT_COOP", "SPCB")
        ),
        spec(
            name = "Ahmedabad Mercantile Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("AMCB0"),
            tokens = listOf("AHMEDABAD MERCANTILE CO-OPERATIVE", "AMCO BANK"),
            files = listOf("AMCO", "AMCB")
        ),
        spec(
            name = "Prime Co-operative Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("PMEC0"),
            tokens = listOf("PRIME CO-OPERATIVE BANK", "PRIME COOPERATIVE BANK"),
            files = listOf("PRIME_COOP", "PMEC")
        ),
        spec(
            name = "Zoroastrian Co-operative Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("ZCBL0"),
            tokens = listOf("ZOROASTRIAN CO-OPERATIVE BANK", "ZOROASTRIAN COOPERATIVE BANK"),
            files = listOf("ZOROASTRIAN", "ZCBL")
        ),
        spec(
            name = "Maharashtra State Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("MSCI0"),
            tokens = listOf("MAHARASHTRA STATE CO-OPERATIVE BANK", "MAHARASHTRA STATE COOPERATIVE BANK"),
            files = listOf("MSCB", "MSCI")
        ),
        spec(
            name = "Gujarat State Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("GSCB0"),
            tokens = listOf("GUJARAT STATE CO-OPERATIVE BANK", "GUJARAT STATE COOPERATIVE BANK"),
            files = listOf("GSCB")
        ),
        spec(
            name = "Tamil Nadu State Apex Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("TNSC0"),
            tokens = listOf("TAMILNADU STATE APEX CO-OPERATIVE", "TAMIL NADU STATE APEX CO-OPERATIVE", "TNSC BANK"),
            files = listOf("TNSC")
        ),
        spec(
            name = "Karnataka State Co-op Apex Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("KSCB0"),
            tokens = listOf("KARNATAKA STATE CO-OPERATIVE APEX BANK", "KARNATAKA STATE COOPERATIVE APEX"),
            files = listOf("KSCB")
        ),
        spec(
            name = "Kerala State Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("KSBK0"),
            tokens = listOf("KERALA STATE CO-OPERATIVE BANK", "KERALA BANK"),
            files = listOf("KERALA_BANK", "KSBK")
        ),
        spec(
            name = "Telangana State Co-op Apex Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("TSAB0"),
            tokens = listOf("TELANGANA STATE CO-OPERATIVE APEX", "TSCAB"),
            files = listOf("TSCAB", "TSAB")
        ),
        spec(
            name = "Andhra Pradesh State Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("APBL0"),
            tokens = listOf("ANDHRA PRADESH STATE CO-OPERATIVE", "APCOB"),
            files = listOf("APCOB", "APBL")
        ),
        spec(
            name = "Delhi State Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("DLSC0"),
            tokens = listOf("DELHI STATE CO-OPERATIVE BANK", "DELHI STATE COOPERATIVE BANK"),
            files = listOf("DLSC")
        ),
        spec(
            name = "West Bengal State Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("WBSC0"),
            tokens = listOf("WEST BENGAL STATE CO-OPERATIVE", "WEST BENGAL STATE COOPERATIVE"),
            files = listOf("WBSC")
        ),
        spec(
            name = "Punjab State Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("PSCB0"),
            tokens = listOf("PUNJAB STATE CO-OPERATIVE BANK", "PUNJAB STATE COOPERATIVE BANK"),
            files = listOf("PSCB")
        ),
        spec(
            name = "Rajasthan State Co-op Bank",
            cat = BankCategory.COOPERATIVE,
            ifsc = listOf("RSCB0"),
            tokens = listOf("RAJASTHAN STATE CO-OPERATIVE BANK", "RAJASTHAN STATE COOPERATIVE BANK"),
            files = listOf("RSCB")
        ),

        // ==================== 30 REGIONAL RURAL BANKS (RRBs ACROSS INDIA) ====================
        spec(
            name = "Andhra Pragathi Grameena Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("APGB0"),
            tokens = listOf("ANDHRA PRAGATHI GRAMEENA BANK"),
            files = listOf("APGB")
        ),
        spec(
            name = "Andhra Pradesh Grameena Vikas Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("APGV0"),
            tokens = listOf("ANDHRA PRADESH GRAMEENA VIKAS BANK", "APGVB"),
            files = listOf("APGVB", "APGV")
        ),
        spec(
            name = "Aryavart Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("ARYB0"),
            tokens = listOf("ARYAVART BANK", "ARYAVART GRAMIN BANK"),
            files = listOf("ARYAVART", "ARYB")
        ),
        spec(
            name = "Assam Gramin Vikash Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("AGVX0"),
            tokens = listOf("ASSAM GRAMIN VIKASH BANK", "AGVB"),
            files = listOf("AGVB", "AGVX")
        ),
        spec(
            name = "Bangiya Gramin Vikash Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("BGVX0"),
            tokens = listOf("BANGIYA GRAMIN VIKASH BANK", "BGVB"),
            files = listOf("BGVB", "BGVX")
        ),
        spec(
            name = "Baroda Gujarat Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("BGGB0"),
            tokens = listOf("BARODA GUJARAT GRAMIN BANK"),
            files = listOf("BGGB")
        ),
        spec(
            name = "Baroda Rajasthan Kshetriya Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("BRGX0"),
            tokens = listOf("BARODA RAJASTHAN KSHETRIYA GRAMIN BANK", "BRKGB"),
            files = listOf("BRKGB", "BRGX")
        ),
        spec(
            name = "Baroda UP Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("BUPB0", "BARB0BUP"),
            tokens = listOf("BARODA U.P. BANK", "BARODA UP BANK", "BARODA UTTAR PRADESH GRAMIN"),
            files = listOf("BARODA_UP", "BUPB")
        ),
        spec(
            name = "Chaitanya Godavari Grameena Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("CGGB0"),
            tokens = listOf("CHAITANYA GODAVARI GRAMEENA BANK"),
            files = listOf("CGGB")
        ),
        spec(
            name = "Chhattisgarh Rajya Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("CRGB0"),
            tokens = listOf("CHHATTISGARH RAJYA GRAMIN BANK"),
            files = listOf("CRGB")
        ),
        spec(
            name = "Dakshin Bihar Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("DBGB0"),
            tokens = listOf("DAKSHIN BIHAR GRAMIN BANK", "MADHYA BIHAR GRAMIN BANK"),
            files = listOf("DBGB")
        ),
        spec(
            name = "Himachal Pradesh Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("HPGB0"),
            tokens = listOf("HIMACHAL PRADESH GRAMIN BANK"),
            files = listOf("HPGB")
        ),
        spec(
            name = "Jharkhand Rajya Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("JRGB0"),
            tokens = listOf("JHARKHAND RAJYA GRAMIN BANK"),
            files = listOf("JRGB")
        ),
        spec(
            name = "Karnataka Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("PKGB0", "KGBX0"),
            tokens = listOf("KARNATAKA GRAMIN BANK", "PRAGATHI KRISHNA GRAMIN BANK"),
            files = listOf("PKGB", "KARNATAKA_GRAMIN")
        ),
        spec(
            name = "Karnataka Vikas Grameena Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("KVGB0"),
            tokens = listOf("KARNATAKA VIKAS GRAMEENA BANK"),
            files = listOf("KVGB")
        ),
        spec(
            name = "Kerala Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("KLGB0"),
            tokens = listOf("KERALA GRAMIN BANK"),
            files = listOf("KLGB", "KERALA_GRAMIN")
        ),
        spec(
            name = "Madhya Pradesh Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("MPGB0"),
            tokens = listOf("MADHYA PRADESH GRAMIN BANK", "NARMADA JHABUA GRAMIN BANK"),
            files = listOf("MPGB")
        ),
        spec(
            name = "Maharashtra Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("MAHG0"),
            tokens = listOf("MAHARASHTRA GRAMIN BANK"),
            files = listOf("MAHG", "MAHARASHTRA_GRAMIN")
        ),
        spec(
            name = "Odisha Gramya Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("ODGB0"),
            tokens = listOf("ODISHA GRAMYA BANK"),
            files = listOf("ODGB")
        ),
        spec(
            name = "Prathama UP Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("PUPB0"),
            tokens = listOf("PRATHAMA U.P. GRAMIN BANK", "PRATHAMA UP GRAMIN BANK", "PRATHAMA BANK"),
            files = listOf("PRATHAMA", "PUPB")
        ),
        spec(
            name = "Punjab Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("PGBX0"),
            tokens = listOf("PUNJAB GRAMIN BANK"),
            files = listOf("PUNJAB_GRAMIN", "PGBX")
        ),
        spec(
            name = "Rajasthan Marudhara Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("RMGB0"),
            tokens = listOf("RAJASTHAN MARUDHARA GRAMIN BANK"),
            files = listOf("RMGB")
        ),
        spec(
            name = "Saptagiri Grameena Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("SPGB0"),
            tokens = listOf("SAPTAGIRI GRAMEENA BANK"),
            files = listOf("SPGB")
        ),
        spec(
            name = "Sarva Haryana Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("SHGB0"),
            tokens = listOf("SARVA HARYANA GRAMIN BANK"),
            files = listOf("SHGB")
        ),
        spec(
            name = "Saurashtra Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("SGBA0"),
            tokens = listOf("SAURASHTRA GRAMIN BANK"),
            files = listOf("SGBA")
        ),
        spec(
            name = "Tamil Nadu Grama Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("TNGB0"),
            tokens = listOf("TAMIL NADU GRAMA BANK", "TAMILNADU GRAMA BANK", "PANDYAN GRAMA BANK"),
            files = listOf("TNGB")
        ),
        spec(
            name = "Telangana Grameena Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("TGBX0", "SBIN0RRBTGB"),
            tokens = listOf("TELANGANA GRAMEENA BANK", "DECCAN GRAMEENA BANK"),
            files = listOf("TGBX", "TELANGANA_GRAMEENA")
        ),
        spec(
            name = "Uttar Bihar Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("UBGB0", "CBIN0R10"),
            tokens = listOf("UTTAR BIHAR GRAMIN BANK"),
            files = listOf("UBGB")
        ),
        spec(
            name = "Uttarakhand Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("UTGB0"),
            tokens = listOf("UTTARAKHAND GRAMIN BANK"),
            files = listOf("UTGB")
        ),
        spec(
            name = "Vidharbha Konkan Gramin Bank",
            cat = BankCategory.REGIONAL_RURAL,
            ifsc = listOf("VKGB0"),
            tokens = listOf("VIDHARBHA KONKAN GRAMIN BANK", "VIDARBHA KONKAN GRAMIN BANK"),
            files = listOf("VKGB")
        )
    )

    val totalSupportedCount: Int = ALL_BANKS.size

    fun countsByCategory(): Map<BankCategory, Int> =
        BankCategory.entries.associateWith { cat -> ALL_BANKS.count { it.category == cat } }

    private val HEADER_TOKEN_INDEX: List<Pair<String, IndianBankSpec>> by lazy {
        ALL_BANKS.flatMap { b -> b.headerTokens.map { it.uppercase(Locale.ROOT) to b } }
    }

    private val IFSC_PREFIX_INDEX: List<Pair<String, IndianBankSpec>> by lazy {
        ALL_BANKS
            .flatMap { b -> b.ifscPrefixes.map { it.uppercase(Locale.ROOT) to b } }
            // Longer prefixes (e.g. BARB0BUP / SBIN0RRBTGB) must win over 5-char sponsor prefixes
            .sortedByDescending { it.first.length }
    }

    private val DYNAMIC_BANK_HEADER_REGEX = Regex(
        """(?i)\b((?:THE\s+)?[A-Z][A-Za-z&.'\-]{1,22}(?:\s+[A-Z][A-Za-z&.'\-]{1,22}){0,5}\s+(?:CO-?OPERATIVE\s+BANK|SAHAKARI\s+BANK|GRAMIN\s+BANK|GRAMEENA\s+BANK|GRAMA\s+BANK|SMALL\s+FINANCE\s+BANK|PAYMENTS\s+BANK|URBAN\s+BANK|MERCANTILE\s+BANK|NAGARIK\s+BANK|BANK(?:\s+(?:LTD\.?|LIMITED|INDIA))?))\b"""
    )

    private val DYNAMIC_BANK_NOISE = listOf(
        "ACCOUNT STATEMENT", "BANK STATEMENT", "STATEMENT OF", "INTERNET BANK",
        "MOBILE BANK", "ONLINE BANK", "CORE BANK", "BRANCH BANK", "BENEFICIARY BANK"
    )

    fun findByIfsc(ifsc: String): IndianBankSpec? {
        val clean = ifsc.trim().uppercase(Locale.ROOT)
        if (clean.length < 5) return null
        return IFSC_PREFIX_INDEX.firstOrNull { (prefix, _) -> clean.startsWith(prefix) }?.second
    }

    /**
     * Detects a known RBI-regulated Indian bank from pre-table header first, then full text.
     * Uses earliest occurrence + longest token tie-break so e.g. "STATE BANK OF INDIA" beats "BANK OF INDIA".
     */
    fun detectBankInText(preTableHeaderUpper: String, fullText: String): IndianBankSpec? {
        detectSpecInChunk(preTableHeaderUpper)?.let { return it }
        return detectSpecInChunk(fullText.uppercase(Locale.ROOT))
    }

    /**
     * Returns canonical bank name for 130+ known banks, or dynamically extracts any unlisted
     * local/urban co-operative bank name from the PDF header before falling back to "Indian Scheduled Bank".
     */
    fun detectBankName(preTableHeaderRaw: String, fullText: String): String {
        val preUpper = preTableHeaderRaw.uppercase(Locale.ROOT)
        detectSpecInChunk(preUpper)?.let { return it.canonicalName }
        extractDynamicBankName(preTableHeaderRaw)?.let { return it }
        detectSpecInChunk(fullText.uppercase(Locale.ROOT))?.let { return it.canonicalName }
        return "Indian Scheduled Bank"
    }

    fun extractDynamicBankName(preTableHeaderRaw: String): String? {
        val lines = preTableHeaderRaw.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(8)
        for (line in lines) {
            val upper = line.uppercase(Locale.ROOT)
            if (DYNAMIC_BANK_NOISE.any { upper.contains(it) }) continue
            val match = DYNAMIC_BANK_HEADER_REGEX.find(line) ?: continue
            val rawCandidate = match.groupValues[1]
                .replace(Regex("""\s+"""), " ")
                .trim()
                .removeSuffix(".")
            val upperCand = rawCandidate.uppercase(Locale.ROOT)
            if (rawCandidate.length < 8 || upperCand == "THE BANK" || upperCand.startsWith("STATEMENT")) {
                continue
            }
            return toDisplayBankTitle(rawCandidate)
        }
        return null
    }

    fun normalizeBankName(officialBank: String): String {
        val trimmed = officialBank.trim()
        if (trimmed.isBlank()) return "Indian Scheduled Bank"
        val upper = trimmed.uppercase(Locale.ROOT)
        detectSpecInChunk(upper)?.let { return it.canonicalName }
        val cleaned = trimmed
            .replace(Regex("""\s+LIMITED\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+LTD\.?\b""", RegexOption.IGNORE_CASE), "")
            .trim()
        return cleaned.split(Regex("""\s+""")).take(4).joinToString(" ").ifBlank { trimmed }
    }

    fun inferBankFromFilename(fileName: String): String {
        val upper = fileName.uppercase(Locale.ROOT)
        // Check explicit filename tokens first (prefer longer token matches)
        val bestFileToken = ALL_BANKS
            .flatMap { bank -> bank.filenameTokens.map { tok -> tok to bank } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (tok, _) ->
                if (tok.length <= 3) {
                    Regex("""(?<![A-Z0-9])${Regex.escape(tok)}(?![A-Z0-9])""").containsMatchIn(upper)
                } else {
                    upper.contains(tok)
                }
            }
        if (bestFileToken != null) return bestFileToken.second.canonicalName

        // Also check 5-char IFSC prefix embedded in filename (e.g. "SRCB0000123_stmt.pdf")
        val ifscHit = IFSC_PREFIX_INDEX.firstOrNull { (prefix, _) -> upper.contains(prefix) }
        if (ifscHit != null) return ifscHit.second.canonicalName

        return ""
    }

    private fun detectSpecInChunk(upper: String): IndianBankSpec? {
        if (upper.isBlank()) return null

        // 1. Prefer earliest explicit bank name appearance (tie-break by longest token)
        val bestName = HEADER_TOKEN_INDEX
            .mapNotNull { (token, spec) ->
                val idx = upper.indexOf(token)
                if (idx >= 0) Triple(idx, -token.length, spec) else null
            }
            .minWithOrNull(compareBy<Triple<Int, Int, IndianBankSpec>> { it.first }.thenBy { it.second })
        if (bestName != null) return bestName.third

        // 2. Next check RBI IFSC prefix by earliest occurrence (longest prefix wins at same index)
        val bestIfsc = IFSC_PREFIX_INDEX
            .mapNotNull { (prefix, spec) ->
                val idx = upper.indexOf(prefix)
                if (idx >= 0) Triple(idx, -prefix.length, spec) else null
            }
            .minWithOrNull(compareBy<Triple<Int, Int, IndianBankSpec>> { it.first }.thenBy { it.second })
        if (bestIfsc != null) return bestIfsc.third

        // 3. Whole-word SBI fallback
        if (Regex("""\bSBI\b""").containsMatchIn(upper)) {
            return ALL_BANKS.first { it.canonicalName == "SBI" }
        }
        return null
    }

    private fun toDisplayBankTitle(raw: String): String {
        val acronyms = setOf("SBI", "HDFC", "ICICI", "IDFC", "IDBI", "RBL", "DCB", "CSB", "UCO", "TJSB", "SVC", "NKGSB", "AP", "UP", "MP", "HP", "AU", "ESAF", "NSDL", "IPPB", "HSBC", "DBS", "SBM", "MUFG", "SMBC", "BNP", "DNS", "GP", "LTD")
        return raw.split(Regex("""\s+""")).joinToString(" ") { word ->
            val u = word.uppercase(Locale.ROOT)
            when {
                u in acronyms -> u
                u == "OF" || u == "AND" -> u.lowercase(Locale.ROOT)
                u == "CO-OPERATIVE" -> "Co-operative"
                u == "COOPERATIVE" -> "Co-operative"
                else -> word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }
    }
}
