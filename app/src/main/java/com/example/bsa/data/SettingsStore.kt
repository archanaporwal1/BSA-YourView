package com.example.bsa.data

import android.content.Context
import com.example.bsa.model.AppSettings
import com.example.bsa.model.ExcelDateFormat
import com.example.bsa.model.ExcelExportConfig
import com.example.bsa.model.ThemeMode

/**
 * 100% offline SharedPreferences store for BSA-YourView appearance, underwriting, and Excel export preferences.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        "bsa_yourview_settings",
        Context.MODE_PRIVATE
    )

    fun load(): AppSettings {
        val themeName = prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val theme = runCatching { ThemeMode.valueOf(themeName) }.getOrDefault(ThemeMode.SYSTEM)

        val dateFmtName = prefs.getString(KEY_EXCEL_DATE_FMT, ExcelDateFormat.DD_MMM_YY.name)
            ?: ExcelDateFormat.DD_MMM_YY.name
        val dateFmt = runCatching { ExcelDateFormat.valueOf(dateFmtName) }.getOrDefault(ExcelDateFormat.DD_MMM_YY)

        val excelConfig = ExcelExportConfig(
            dateFormat = dateFmt,
            includeChequeNoColumn = prefs.getBoolean(KEY_EXCEL_COL_CHEQUE, true),
            includeCategoryColumn = prefs.getBoolean(KEY_EXCEL_COL_CATEGORY, true),
            includePartyColumn = prefs.getBoolean(KEY_EXCEL_COL_PARTY, false),
            includeSourceFileColumn = prefs.getBoolean(KEY_EXCEL_COL_SOURCE, false),
            includeSelfTransferFlagColumn = prefs.getBoolean(KEY_EXCEL_COL_SELF, false),
            splitDebitCreditColumns = prefs.getBoolean(KEY_EXCEL_SPLIT_DR_CR, false),
            includeFormulas = prefs.getBoolean(KEY_EXCEL_FORMULAS, true),
            freezeHeaderRows = prefs.getBoolean(KEY_EXCEL_FREEZE, true),
            includeForensicAuditDetails = prefs.getBoolean(KEY_EXCEL_FORENSIC_DETAILS, true),
            topTransactionsLimit = prefs.getInt(KEY_EXCEL_TOP_LIMIT, 30).coerceIn(10, 50)
        )

        return AppSettings(
            themeMode = theme,
            strictForensicBlocking = prefs.getBoolean(KEY_STRICT_FORENSIC, false),
            autoSuppressOdNegativeEod = prefs.getBoolean(KEY_AUTO_SUPPRESS_OD, true),
            fuzzySelfMatchThreshold = prefs.getInt(KEY_FUZZY_THRESHOLD, 82).coerceIn(70, 95),
            useCompactCurrency = prefs.getBoolean(KEY_COMPACT_CURRENCY, true),
            onlineAccuracyBoostEnabled = prefs.getBoolean(KEY_ONLINE_BOOST, true),
            cloudAiAssistEnabled = prefs.getBoolean(KEY_CLOUD_AI_ASSIST, true),
            updateRepoOverride = prefs.getString(KEY_UPDATE_REPO, "") ?: "",
            excelExportConfig = excelConfig
        )
    }

    fun save(settings: AppSettings) {
        val ex = settings.excelExportConfig
        prefs.edit()
            .putString(KEY_THEME, settings.themeMode.name)
            .putBoolean(KEY_STRICT_FORENSIC, settings.strictForensicBlocking)
            .putBoolean(KEY_AUTO_SUPPRESS_OD, settings.autoSuppressOdNegativeEod)
            .putInt(KEY_FUZZY_THRESHOLD, settings.fuzzySelfMatchThreshold)
            .putBoolean(KEY_COMPACT_CURRENCY, settings.useCompactCurrency)
            .putBoolean(KEY_ONLINE_BOOST, settings.onlineAccuracyBoostEnabled)
            .putBoolean(KEY_CLOUD_AI_ASSIST, settings.cloudAiAssistEnabled)
            .putString(KEY_UPDATE_REPO, settings.updateRepoOverride)
            .putString(KEY_EXCEL_DATE_FMT, ex.dateFormat.name)
            .putBoolean(KEY_EXCEL_COL_CHEQUE, ex.includeChequeNoColumn)
            .putBoolean(KEY_EXCEL_COL_CATEGORY, ex.includeCategoryColumn)
            .putBoolean(KEY_EXCEL_COL_PARTY, ex.includePartyColumn)
            .putBoolean(KEY_EXCEL_COL_SOURCE, ex.includeSourceFileColumn)
            .putBoolean(KEY_EXCEL_COL_SELF, ex.includeSelfTransferFlagColumn)
            .putBoolean(KEY_EXCEL_SPLIT_DR_CR, ex.splitDebitCreditColumns)
            .putBoolean(KEY_EXCEL_FORMULAS, ex.includeFormulas)
            .putBoolean(KEY_EXCEL_FREEZE, ex.freezeHeaderRows)
            .putBoolean(KEY_EXCEL_FORENSIC_DETAILS, ex.includeForensicAuditDetails)
            .putInt(KEY_EXCEL_TOP_LIMIT, ex.topTransactionsLimit)
            .apply()
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_STRICT_FORENSIC = "strict_forensic_blocking"
        private const val KEY_AUTO_SUPPRESS_OD = "auto_suppress_od_negative_eod"
        private const val KEY_FUZZY_THRESHOLD = "fuzzy_self_match_threshold"
        private const val KEY_COMPACT_CURRENCY = "use_compact_currency"
        private const val KEY_ONLINE_BOOST = "online_accuracy_boost_enabled"
        private const val KEY_CLOUD_AI_ASSIST = "cloud_ai_assist_enabled"
        private const val KEY_UPDATE_REPO = "update_github_repo_override"

        private const val KEY_EXCEL_DATE_FMT = "excel_date_format"
        private const val KEY_EXCEL_COL_CHEQUE = "excel_col_cheque"
        private const val KEY_EXCEL_COL_CATEGORY = "excel_col_category"
        private const val KEY_EXCEL_COL_PARTY = "excel_col_party"
        private const val KEY_EXCEL_COL_SOURCE = "excel_col_source_file"
        private const val KEY_EXCEL_COL_SELF = "excel_col_self_transfer"
        private const val KEY_EXCEL_SPLIT_DR_CR = "excel_split_dr_cr"
        private const val KEY_EXCEL_FORMULAS = "excel_include_formulas"
        private const val KEY_EXCEL_FREEZE = "excel_freeze_headers"
        private const val KEY_EXCEL_FORENSIC_DETAILS = "excel_forensic_details"
        private const val KEY_EXCEL_TOP_LIMIT = "excel_top_limit"
    }
}
