package com.example.bsa.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.bsa.data.BsaDatabase
import com.example.bsa.data.BsaRepository
import com.example.bsa.data.SavedCaseSummary
import com.example.bsa.data.SettingsStore
import com.example.bsa.engine.PdfPasswordRequiredException
import com.example.bsa.engine.UnderwritingAnalyticsEngine
import com.example.bsa.model.*
import com.example.bsa.service.AppReleaseInfo
import com.example.bsa.service.InAppUpdaterService
import com.example.bsa.service.NetworkConnectivityMonitor
import com.example.bsa.service.UpdateCheckOutcome
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AppDestination(val label: String) {
    ANALYZE("Analyze"),
    REPORT("Report"),
    HISTORY("Cases"),
    SETTINGS("Settings")
}

enum class ReportSubTab(val label: String) {
    OVERVIEW("Cash Flow & OD"),
    FRAUD("Fraud (10)"),
    LEDGER("Transactions"),
    PARTIES("Parties & Loans")
}

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data class UpToDate(val versionName: String, val versionCode: Int) : AppUpdateUiState
    data class UpdateAvailable(val release: AppReleaseInfo) : AppUpdateUiState
    data class Downloading(
        val release: AppReleaseInfo,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AppUpdateUiState
    data class ReadyToInstall(val release: AppReleaseInfo, val apkFile: File) : AppUpdateUiState
    data class Error(val message: String) : AppUpdateUiState
}

data class BsaUiState(
    val activeDestination: AppDestination = AppDestination.ANALYZE,
    val activeReportTab: ReportSubTab = ReportSubTab.OVERVIEW,
    val isExcelConfigOpen: Boolean = false,
    val isDeviceOnline: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val stagedFiles: List<StagedPdfFile> = emptyList(),
    val config: ApplicantConfig = ApplicantConfig(),
    val sanctionedLimitInput: String = "2500000",
    val drawingPowerInput: String = "2200000",
    val odLimitError: String? = null,
    // Password-protected PDF popup dialog state
    val passwordPromptFileId: String? = null,
    val passwordPromptFileName: String = "",
    val passwordPromptInput: String = "",
    val passwordPromptError: String? = null,
    val isVerifyingPassword: Boolean = false,
    val resumeAnalysisAfterUnlock: Boolean = false,
    // Analysis & Report state
    val isAnalyzing: Boolean = false,
    val analysisStageMessage: String = "",
    val errorBanner: String? = null,
    val statusMessage: String? = null,
    val activeReport: AnalysisReport? = null,
    // Full unfiltered backing transactions for instant live date-range re-filtering on Dashboard
    val fullReportTransactions: List<BankTransaction> = emptyList(),
    val baseReportMetadata: AccountMetadata? = null,
    val activeVerifiedHolidays: Map<String, String> = emptyMap(),
    val ledgerSearchQuery: String = "",
    val ledgerCategoryFilter: String = "All",
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportStageMessage: String = "",
    val pendingShareFile: File? = null,
    // In-App Auto-Updater state (zero browser redirect)
    val updateState: AppUpdateUiState = AppUpdateUiState.Idle,
    val showUpdateDialog: Boolean = false
)

class BsaViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: BsaRepository
    private val settingsStore: SettingsStore
    private val connectivityMonitor: NetworkConnectivityMonitor
    val updaterService: InAppUpdaterService = InAppUpdaterService()

    private val _uiState = MutableStateFlow(BsaUiState())
    val uiState: StateFlow<BsaUiState> = _uiState.asStateFlow()

    val savedCases: StateFlow<List<SavedCaseSummary>>

    init {
        PDFBoxResourceLoader.init(application.applicationContext)
        val db = BsaDatabase.getInstance(application.applicationContext)
        repository = BsaRepository(application.applicationContext, db.savedCaseDao())
        settingsStore = SettingsStore(application.applicationContext)
        connectivityMonitor = NetworkConnectivityMonitor(application.applicationContext)
        connectivityMonitor.start()

        val loadedSettings = settingsStore.load()
        val initialOnline = connectivityMonitor.checkCurrentlyOnline()
        _uiState.update { it.copy(settings = loadedSettings, isDeviceOnline = initialOnline) }

        viewModelScope.launch {
            connectivityMonitor.isOnline.collect { online ->
                _uiState.update { it.copy(isDeviceOnline = online) }
            }
        }

        // Silent automatic update check on launch when internet is active and GitHub update source is configured
        if (initialOnline && updaterService.resolveManifestUrl(loadedSettings.updateRepoOverride).isNotBlank()) {
            checkForAppUpdate(silent = true)
        }

        savedCases = repository.savedCases.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    override fun onCleared() {
        super.onCleared()
        connectivityMonitor.stop()
    }

    fun selectDestination(dest: AppDestination) {
        _uiState.update { it.copy(activeDestination = dest, isExcelConfigOpen = false) }
    }

    fun openExcelConfig() {
        _uiState.update { it.copy(isExcelConfigOpen = true) }
    }

    fun closeExcelConfig() {
        _uiState.update { it.copy(isExcelConfigOpen = false) }
    }

    fun selectReportSubTab(tab: ReportSubTab) {
        _uiState.update { it.copy(activeReportTab = tab) }
    }

    fun clearTransientMessages() {
        _uiState.update { it.copy(errorBanner = null, statusMessage = null) }
    }

    fun consumePendingShareFile() {
        _uiState.update { it.copy(pendingShareFile = null) }
    }

    // -------------------------------------------------------------------------
    // Theme Toggle & Settings Actions
    // -------------------------------------------------------------------------
    fun toggleDarkLightMode(currentlyDark: Boolean) {
        val next = if (currentlyDark) ThemeMode.LIGHT else ThemeMode.DARK
        updateSettings { it.copy(themeMode = next) }
    }

    fun updateThemeMode(mode: ThemeMode) {
        updateSettings { it.copy(themeMode = mode) }
    }

    fun updateStrictForensicBlocking(enabled: Boolean) {
        updateSettings { it.copy(strictForensicBlocking = enabled) }
    }

    fun updateAutoSuppressOdNegativeEod(enabled: Boolean) {
        updateSettings { it.copy(autoSuppressOdNegativeEod = enabled) }
    }

    fun updateFuzzyThreshold(value: Int) {
        updateSettings { it.copy(fuzzySelfMatchThreshold = value.coerceIn(70, 95)) }
    }

    fun updateUseCompactCurrency(compact: Boolean) {
        updateSettings { it.copy(useCompactCurrency = compact) }
    }

    fun updateOnlineAccuracyBoost(enabled: Boolean) {
        updateSettings { it.copy(onlineAccuracyBoostEnabled = enabled) }
    }

    fun updateCloudAiAssist(enabled: Boolean) {
        updateSettings { it.copy(cloudAiAssistEnabled = enabled) }
    }

    fun updateGitHubRepoOverride(repo: String) {
        updateSettings { it.copy(updateRepoOverride = repo.trim()) }
    }

    fun checkForAppUpdate(silent: Boolean = false) {
        val state = _uiState.value
        val onlineNow = connectivityMonitor.checkCurrentlyOnline()
        _uiState.update { it.copy(isDeviceOnline = onlineNow) }
        if (!onlineNow) {
            if (!silent) {
                _uiState.update {
                    it.copy(
                        updateState = AppUpdateUiState.Error("No active internet connection. Connect to check for updates."),
                        statusMessage = "Offline — connect to internet to check for updates."
                    )
                }
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(updateState = AppUpdateUiState.Checking) }
            when (val outcome = updaterService.checkForUpdate(state.settings.updateRepoOverride)) {
                is UpdateCheckOutcome.NotConfigured -> {
                    _uiState.update {
                        it.copy(
                            updateState = if (silent) AppUpdateUiState.Idle else AppUpdateUiState.Error(
                                "Set your GitHub repository (owner/repo) below or build via GitHub Actions (build-apk.yml) to enable automatic in-app updates."
                            )
                        )
                    }
                }
                is UpdateCheckOutcome.UpToDate -> {
                    _uiState.update {
                        it.copy(
                            updateState = AppUpdateUiState.UpToDate(outcome.currentVersionName, outcome.currentVersionCode),
                            statusMessage = if (silent) null else "BSA-YourView v${outcome.currentVersionName} (Build ${outcome.currentVersionCode}) is up to date."
                        )
                    }
                }
                is UpdateCheckOutcome.UpdateAvailable -> {
                    _uiState.update {
                        it.copy(
                            updateState = AppUpdateUiState.UpdateAvailable(outcome.release),
                            showUpdateDialog = true
                        )
                    }
                }
                is UpdateCheckOutcome.Error -> {
                    _uiState.update {
                        it.copy(
                            updateState = if (silent) AppUpdateUiState.Idle else AppUpdateUiState.Error(outcome.message)
                        )
                    }
                }
            }
        }
    }

    fun startInAppUpdateDownload() {
        val currentRelease = when (val u = _uiState.value.updateState) {
            is AppUpdateUiState.UpdateAvailable -> u.release
            is AppUpdateUiState.ReadyToInstall -> u.release
            else -> return
        }
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    updateState = AppUpdateUiState.Downloading(
                        release = currentRelease,
                        progress = 0f,
                        downloadedBytes = 0L,
                        totalBytes = currentRelease.fileSizeBytes
                    ),
                    showUpdateDialog = true
                )
            }
            try {
                val downloadedFile = updaterService.downloadApkInApp(ctx, currentRelease) { frac, done, total ->
                    _uiState.update {
                        it.copy(
                            updateState = AppUpdateUiState.Downloading(
                                release = currentRelease,
                                progress = frac,
                                downloadedBytes = done,
                                totalBytes = total
                            )
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        updateState = AppUpdateUiState.ReadyToInstall(currentRelease, downloadedFile),
                        showUpdateDialog = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        updateState = AppUpdateUiState.Error(
                            e.localizedMessage ?: "In-app update download failed."
                        ),
                        showUpdateDialog = true
                    )
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun reopenUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = true) }
    }

    fun effectiveUpdateRepoDisplay(): String =
        updaterService.resolveEffectiveRepo(_uiState.value.settings.updateRepoOverride)

    fun currentAppVersionDisplay(): String =
        "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

    fun updateExcelDateFormat(format: ExcelDateFormat) {
        updateSettings { it.copy(excelExportConfig = it.excelExportConfig.copy(dateFormat = format)) }
    }

    fun updateExcelExportConfig(transform: (ExcelExportConfig) -> ExcelExportConfig) {
        updateSettings { it.copy(excelExportConfig = transform(it.excelExportConfig)) }
    }

    fun resetExcelExportConfig() {
        updateSettings { it.copy(excelExportConfig = ExcelExportConfig()) }
        _uiState.update {
            it.copy(statusMessage = "Excel output configuration reset to defaults.")
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_uiState.value.settings)
        settingsStore.save(updated)
        _uiState.update { it.copy(settings = updated) }
    }

    fun clearAllSavedCases() {
        viewModelScope.launch {
            repository.clearAllCases()
            _uiState.update {
                it.copy(statusMessage = "All offline saved cases cleared from device.")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Multi-PDF Staging & Automatic Password-Protected PDF Popup
    // -------------------------------------------------------------------------
    fun addSelectedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>().applicationContext
        val newFiles = uris.map { uri ->
            val (name, size) = queryFileMeta(ctx, uri)
            StagedPdfFile(
                displayName = name,
                uriString = uri.toString(),
                fileSizeBytes = size
            )
        }

        _uiState.update { state ->
            val existingUris = state.stagedFiles.map { it.uriString }.toSet()
            val added = newFiles.filter { it.uriString !in existingUris }
            state.copy(
                stagedFiles = state.stagedFiles + added,
                errorBanner = null,
                statusMessage = "${added.size} statement ePDF(s) attached."
            )
        }

        // Probe newly added PDFs off main thread to auto-trigger Password Popup if encrypted
        viewModelScope.launch {
            var firstLockedFile: StagedPdfFile? = null
            for (file in newFiles) {
                val isProtected = runCatching { repository.probePasswordProtected(file.uriString) }
                    .getOrDefault(false)
                if (isProtected) {
                    _uiState.update { state ->
                        state.copy(
                            stagedFiles = state.stagedFiles.map { f ->
                                if (f.id == file.id) {
                                    f.copy(isPasswordProtected = true, isUnlocked = false)
                                } else f
                            }
                        )
                    }
                    if (firstLockedFile == null) {
                        firstLockedFile = file.copy(isPasswordProtected = true, isUnlocked = false)
                    }
                }
            }
            if (firstLockedFile != null) {
                openPasswordPrompt(firstLockedFile.id, resumeAnalysis = false)
            }
        }
    }

    fun openPasswordPrompt(fileId: String, resumeAnalysis: Boolean = false) {
        val target = _uiState.value.stagedFiles.firstOrNull { it.id == fileId } ?: return
        _uiState.update {
            it.copy(
                passwordPromptFileId = target.id,
                passwordPromptFileName = target.displayName,
                passwordPromptInput = target.password,
                passwordPromptError = target.passwordError,
                isVerifyingPassword = false,
                resumeAnalysisAfterUnlock = resumeAnalysis
            )
        }
    }

    fun updatePasswordPromptInput(value: String) {
        _uiState.update { it.copy(passwordPromptInput = value, passwordPromptError = null) }
    }

    fun dismissPasswordPrompt() {
        _uiState.update {
            it.copy(
                passwordPromptFileId = null,
                passwordPromptInput = "",
                passwordPromptError = null,
                isVerifyingPassword = false,
                resumeAnalysisAfterUnlock = false
            )
        }
    }

    fun submitPasswordPrompt() {
        val state = _uiState.value
        val fileId = state.passwordPromptFileId ?: return
        val file = state.stagedFiles.firstOrNull { it.id == fileId } ?: return
        val candidate = state.passwordPromptInput

        if (candidate.isBlank()) {
            _uiState.update { it.copy(passwordPromptError = "Please enter the PDF password.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isVerifyingPassword = true, passwordPromptError = null) }
            val unlocked = runCatching { repository.verifyFilePassword(file.uriString, candidate) }
                .getOrDefault(false)

            if (unlocked) {
                val shouldResume = _uiState.value.resumeAnalysisAfterUnlock
                _uiState.update { cur ->
                    val updatedList = cur.stagedFiles.map { f ->
                        if (f.id == fileId) {
                            f.copy(
                                password = candidate,
                                passwordError = null,
                                isPasswordProtected = true,
                                isUnlocked = true
                            )
                        } else f
                    }
                    cur.copy(
                        stagedFiles = updatedList,
                        passwordPromptFileId = null,
                        passwordPromptInput = "",
                        passwordPromptError = null,
                        isVerifyingPassword = false,
                        resumeAnalysisAfterUnlock = false,
                        statusMessage = "${file.displayName} unlocked in memory."
                    )
                }

                // If another queued PDF is still locked, prompt for it next; otherwise resume analysis if requested
                val nextLocked = _uiState.value.stagedFiles.firstOrNull { it.isPasswordProtected && !it.isUnlocked }
                if (nextLocked != null) {
                    openPasswordPrompt(nextLocked.id, resumeAnalysis = shouldResume)
                } else if (shouldResume) {
                    runForensicAnalysis()
                }
            } else {
                _uiState.update {
                    it.copy(
                        isVerifyingPassword = false,
                        passwordPromptError = "Incorrect password for ${file.displayName}. Please try again."
                    )
                }
            }
        }
    }

    fun loadSampleStatements() {
        viewModelScope.launch {
            try {
                val samples = repository.generateSampleStatements()
                _uiState.update { state ->
                    state.copy(
                        stagedFiles = samples,
                        config = ApplicantConfig(
                            applicantId = "YV-OD-2026-042",
                            sourceName = "YourView Credit Desk",
                            productType = ProductType.WORKING_CAPITAL,
                            facilityType = FacilityType.OD,
                            sanctionedOdLimit = 2500000.0,
                            drawingPowerLimit = 2200000.0,
                            filterStartDateIso = null,
                            filterEndDateIso = null
                        ),
                        sanctionedLimitInput = "2500000",
                        drawingPowerInput = "2200000",
                        odLimitError = null,
                        errorBanner = null,
                        statusMessage = "Loaded pdfrendition11.pdf & pdfrendition12.pdf (ICICI OD sample ePDFs)."
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorBanner = "Could not build sample ePDFs: ${e.localizedMessage}") }
            }
        }
    }

    fun updateFilePassword(fileId: String, password: String) {
        _uiState.update { state ->
            state.copy(
                stagedFiles = state.stagedFiles.map { f ->
                    if (f.id == fileId) f.copy(password = password, passwordError = null) else f
                }
            )
        }
    }

    fun removeStagedFile(fileId: String) {
        _uiState.update { state ->
            state.copy(stagedFiles = state.stagedFiles.filterNot { it.id == fileId })
        }
    }

    // -------------------------------------------------------------------------
    // Applicant, Facility (Mandatory OD Limit) & Exact Date Range Form State
    // -------------------------------------------------------------------------
    fun updateApplicantId(value: String) {
        _uiState.update { it.copy(config = it.config.copy(applicantId = value)) }
    }

    fun updateSourceName(value: String) {
        _uiState.update { it.copy(config = it.config.copy(sourceName = value)) }
    }

    fun updateProductType(type: ProductType) {
        _uiState.update { it.copy(config = it.config.copy(productType = type)) }
    }

    fun updateFacilityType(type: FacilityType) {
        _uiState.update { state ->
            if (type == FacilityType.NONE) {
                state.copy(
                    config = state.config.copy(
                        facilityType = type,
                        sanctionedOdLimit = 0.0,
                        drawingPowerLimit = 0.0
                    ),
                    odLimitError = null
                )
            } else {
                val restoredSanctioned = state.sanctionedLimitInput.toDoubleOrNull() ?: 0.0
                val restoredDp = state.drawingPowerInput.toDoubleOrNull() ?: restoredSanctioned
                state.copy(
                    config = state.config.copy(
                        facilityType = type,
                        sanctionedOdLimit = restoredSanctioned,
                        drawingPowerLimit = restoredDp
                    ),
                    odLimitError = if (restoredSanctioned <= 0.0) "Enter Sanctioned ${type.code} Limit (INR)" else null
                )
            }
        }
    }

    fun updateSanctionedLimit(raw: String) {
        val cleaned = raw.filter { it.isDigit() || it == '.' }
        val numeric = cleaned.toDoubleOrNull() ?: 0.0
        _uiState.update { state ->
            val dpNumeric = state.drawingPowerInput.toDoubleOrNull()
            state.copy(
                sanctionedLimitInput = cleaned,
                odLimitError = if (state.config.facilityType != FacilityType.NONE && numeric <= 0.0) {
                    "Sanctioned ${state.config.facilityType.code} Limit must be greater than ₹0"
                } else null,
                config = state.config.copy(
                    sanctionedOdLimit = numeric,
                    drawingPowerLimit = if (dpNumeric == null || dpNumeric <= 0.0) numeric else dpNumeric
                )
            )
        }
    }

    fun updateDrawingPower(raw: String) {
        val cleaned = raw.filter { it.isDigit() || it == '.' }
        val numeric = cleaned.toDoubleOrNull() ?: 0.0
        _uiState.update { state ->
            state.copy(
                drawingPowerInput = cleaned,
                config = state.config.copy(
                    drawingPowerLimit = if (numeric > 0.0) numeric else state.config.sanctionedOdLimit
                )
            )
        }
    }

    fun updateIntakeStartDate(isoDate: String?) {
        _uiState.update { state ->
            val end = state.config.filterEndDateIso
            val adjustedEnd = if (isoDate != null && end != null && isoDate > end) isoDate else end
            state.copy(
                config = state.config.copy(
                    filterStartDateIso = isoDate,
                    filterEndDateIso = adjustedEnd
                )
            )
        }
    }

    fun updateIntakeEndDate(isoDate: String?) {
        _uiState.update { state ->
            val start = state.config.filterStartDateIso
            val adjustedStart = if (isoDate != null && start != null && isoDate < start) isoDate else start
            state.copy(
                config = state.config.copy(
                    filterStartDateIso = adjustedStart,
                    filterEndDateIso = isoDate
                )
            )
        }
    }

    fun clearIntakeDateFilter() {
        _uiState.update { state ->
            state.copy(config = state.config.copy(filterStartDateIso = null, filterEndDateIso = null))
        }
    }

    /**
     * Live exact-date re-computation on Dashboard: recalculates KPIs, 35-row metrics, EOD matrix,
     * OD utilization, Loan Track, 10 Fraud Triggers, Transactions, and 11-tab Excel export
     * strictly for the selected date range [startIso..endIso].
     */
    fun applyDashboardDateFilter(startIso: String?, endIso: String?) {
        val current = _uiState.value
        val activeReport = current.activeReport ?: return
        val sourceTxns = current.fullReportTransactions.ifEmpty { activeReport.transactions }
        val baseMeta = current.baseReportMetadata ?: activeReport.metadata

        val normalizedStart = startIso?.takeIf { it.isNotBlank() }
        val normalizedEnd = endIso?.takeIf { it.isNotBlank() }
        val (safeStart, safeEnd) = if (normalizedStart != null && normalizedEnd != null && normalizedStart > normalizedEnd) {
            normalizedEnd to normalizedStart
        } else {
            normalizedStart to normalizedEnd
        }

        val updatedConfig = activeReport.config.copy(
            filterStartDateIso = safeStart,
            filterEndDateIso = safeEnd
        )

        viewModelScope.launch {
            val recomputed = withContext(Dispatchers.Default) {
                UnderwritingAnalyticsEngine.analyze(
                    config = updatedConfig,
                    metadata = baseMeta,
                    fileAudits = activeReport.fileAudits,
                    transactions = sourceTxns,
                    autoSuppressOdNegativeEod = current.settings.autoSuppressOdNegativeEod,
                    extraBankHolidaysByIso = current.activeVerifiedHolidays,
                    onlineEnrichment = activeReport.onlineEnrichment
                ).copy(
                    reportId = activeReport.reportId,
                    generatedAtMillis = activeReport.generatedAtMillis
                )
            }
            _uiState.update {
                it.copy(
                    activeReport = recomputed,
                    statusMessage = if (safeStart == null && safeEnd == null) {
                        "Showing all statement dates (${recomputed.transactions.size} txns)."
                    } else {
                        "Filtered strictly to ${recomputed.metadata.statementPeriod} (${recomputed.transactions.size} txns)."
                    }
                )
            }
        }
    }

    fun updateLedgerSearch(query: String) {
        _uiState.update { it.copy(ledgerSearchQuery = query) }
    }

    fun updateLedgerCategoryFilter(category: String) {
        _uiState.update { it.copy(ledgerCategoryFilter = category) }
    }

    // -------------------------------------------------------------------------
    // Forensic Pipeline Execution
    // -------------------------------------------------------------------------
    fun runForensicAnalysis() {
        val current = _uiState.value
        if (current.stagedFiles.isEmpty()) {
            _uiState.update { it.copy(errorBanner = "Please attach at least one ePDF statement or load sample ePDFs.") }
            return
        }
        if (current.config.applicantId.isBlank()) {
            _uiState.update { it.copy(errorBanner = "Applicant ID is required before running analysis.") }
            return
        }

        // Enforce mandatory OD / CC Limit entry when Facility Type is OD or CC
        if (current.config.facilityType != FacilityType.NONE && current.config.sanctionedOdLimit <= 0.0) {
            val code = current.config.facilityType.code
            _uiState.update {
                it.copy(
                    odLimitError = "Sanctioned $code Limit is required for ${current.config.facilityType.label}.",
                    errorBanner = "Please enter the Sanctioned $code Limit (INR) before running analysis."
                )
            }
            return
        }

        // If any attached PDF is still password-locked, pop up the Password Entry modal immediately
        val lockedFile = current.stagedFiles.firstOrNull { it.isPasswordProtected && !it.isUnlocked }
        if (lockedFile != null) {
            openPasswordPrompt(lockedFile.id, resumeAnalysis = true)
            return
        }

        // Default Drawing Power to Sanctioned OD Limit if user only entered OD Limit
        val effectiveConfig = if (current.config.facilityType != FacilityType.NONE && current.config.drawingPowerLimit <= 0.0) {
            current.config.copy(drawingPowerLimit = current.config.sanctionedOdLimit)
        } else {
            current.config
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAnalyzing = true,
                    analysisStageMessage = "Initializing offline BSA-YourView PDFBox engine...",
                    odLimitError = null,
                    errorBanner = null,
                    statusMessage = null
                )
            }
            try {
                val onlineNow = connectivityMonitor.checkCurrentlyOnline()
                _uiState.update { it.copy(isDeviceOnline = onlineNow) }
                val result = repository.runFullPipeline(
                    stagedFiles = current.stagedFiles,
                    config = effectiveConfig,
                    settings = current.settings,
                    isDeviceOnline = onlineNow,
                    onStageUpdate = { msg ->
                        _uiState.update { it.copy(analysisStageMessage = msg) }
                    }
                )
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        analysisStageMessage = "",
                        activeReport = result.report,
                        fullReportTransactions = result.fullUnfilteredTransactions,
                        baseReportMetadata = result.baseMetadata,
                        activeVerifiedHolidays = result.verifiedHolidaysByIso,
                        activeDestination = AppDestination.REPORT,
                        activeReportTab = ReportSubTab.OVERVIEW,
                        ledgerSearchQuery = "",
                        ledgerCategoryFilter = "All",
                        statusMessage = if (result.report.onlineEnrichment.usedOnlineBoost) {
                            "Online Hybrid Audit complete: ${result.report.transactions.size} txns (${result.report.onlineEnrichment.enrichedTransactionsCount} enriched)."
                        } else {
                            "Analysis complete: ${result.report.transactions.size} transactions audited."
                        }
                    )
                }
            } catch (pwEx: PdfPasswordRequiredException) {
                val matchedFile = _uiState.value.stagedFiles.firstOrNull { it.displayName == pwEx.fileName }
                _uiState.update { state ->
                    state.copy(
                        isAnalyzing = false,
                        analysisStageMessage = "",
                        stagedFiles = state.stagedFiles.map { f ->
                            if (f.displayName == pwEx.fileName) {
                                f.copy(
                                    isPasswordProtected = true,
                                    isUnlocked = false,
                                    passwordError = pwEx.message
                                )
                            } else f
                        }
                    )
                }
                if (matchedFile != null) {
                    openPasswordPrompt(matchedFile.id, resumeAnalysis = true)
                } else {
                    _uiState.update { it.copy(errorBanner = pwEx.message) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        analysisStageMessage = "",
                        errorBanner = e.localizedMessage ?: "Failed to parse statement PDF."
                    )
                }
            }
        }
    }

    fun openSavedCase(reportId: String) {
        viewModelScope.launch {
            val loaded = repository.loadReportById(reportId)
            if (loaded != null) {
                _uiState.update {
                    it.copy(
                        activeReport = loaded,
                        fullReportTransactions = loaded.transactions,
                        baseReportMetadata = loaded.metadata,
                        activeDestination = AppDestination.REPORT,
                        activeReportTab = ReportSubTab.OVERVIEW,
                        ledgerSearchQuery = "",
                        ledgerCategoryFilter = "All",
                        statusMessage = "Loaded saved report for ${loaded.config.applicantId}."
                    )
                }
            } else {
                _uiState.update { it.copy(errorBanner = "Saved case could not be loaded.") }
            }
        }
    }

    fun deleteSavedCase(reportId: String) {
        viewModelScope.launch {
            repository.deleteCase(reportId)
            _uiState.update { state ->
                val clearedActive = if (state.activeReport?.reportId == reportId) null else state.activeReport
                state.copy(
                    activeReport = clearedActive,
                    statusMessage = "Case removed from offline device storage."
                )
            }
        }
    }

    fun exportActiveReportToUri(uri: Uri) {
        val state = _uiState.value
        val report = state.activeReport ?: return
        val excelConfig = state.settings.excelExportConfig
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExporting = true,
                    exportProgress = 0.02f,
                    exportStageMessage = "Preparing 11-tab BSA-YourView workbook...",
                    errorBanner = null
                )
            }
            try {
                repository.exportExcelToUri(report, uri, excelConfig) { progress, stage ->
                    _uiState.update {
                        it.copy(
                            exportProgress = progress.coerceIn(0f, 1f),
                            exportStageMessage = stage
                        )
                    }
                    // Brief pacing on background I/O thread so each of the 11 tab stages renders visibly
                    try { Thread.sleep(22L) } catch (_: InterruptedException) {}
                }
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = 1f,
                        exportStageMessage = "",
                        statusMessage = "11-Tab Excel workbook saved successfully (.xlsx)."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = 0f,
                        exportStageMessage = "",
                        errorBanner = "Excel export failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun prepareShareExcel() {
        val state = _uiState.value
        val report = state.activeReport ?: return
        val excelConfig = state.settings.excelExportConfig
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExporting = true,
                    exportProgress = 0.02f,
                    exportStageMessage = "Preparing 11-tab BSA-YourView workbook...",
                    errorBanner = null
                )
            }
            try {
                val file = repository.exportExcelToCacheFile(report, excelConfig) { progress, stage ->
                    _uiState.update {
                        it.copy(
                            exportProgress = progress.coerceIn(0f, 1f),
                            exportStageMessage = stage
                        )
                    }
                    try { Thread.sleep(22L) } catch (_: InterruptedException) {}
                }
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = 1f,
                        exportStageMessage = "",
                        pendingShareFile = file
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = 0f,
                        exportStageMessage = "",
                        errorBanner = "Unable to generate shareable .xlsx: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun defaultExportFilename(): String {
        val report = _uiState.value.activeReport
        val id = report?.config?.applicantId
            ?.replace(Regex("""[^A-Za-z0-9_-]"""), "_")
            ?.ifBlank { "Report" } ?: "Report"
        val dateSuffix = if (report?.config?.filterStartDateIso != null || report?.config?.filterEndDateIso != null) {
            "_${report.config.filterStartDateIso ?: "Start"}_to_${report.config.filterEndDateIso ?: "End"}"
        } else ""
        return "BSA_YourView_${id}${dateSuffix}.xlsx"
    }

    private fun queryFileMeta(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "statement.pdf"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }
}
