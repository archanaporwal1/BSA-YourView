package com.example.bsa

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bsa.model.ThemeMode
import com.example.bsa.ui.AppDestination
import com.example.bsa.ui.AppUpdateUiState
import com.example.bsa.ui.BsaViewModel
import com.example.bsa.ui.components.BrandTopHeader
import com.example.bsa.ui.components.InAppUpdateDialog
import com.example.bsa.ui.components.PasswordPromptDialog
import com.example.bsa.ui.screens.DashboardScreen
import com.example.bsa.ui.screens.ExcelConfigScreen
import com.example.bsa.ui.screens.HistoryScreen
import com.example.bsa.ui.screens.IntakeScreen
import com.example.bsa.ui.screens.SettingsScreen
import com.example.ui.theme.BankAnalysierTheme
import java.io.File

@Composable
fun BankAnalysierApp(
    viewModel: BsaViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savedCases by viewModel.savedCases.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val systemDark = isSystemInDarkTheme()
    val isDarkTheme = when (uiState.settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    // Multi-PDF SAF Document Picker (zero storage permissions required)
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addSelectedUris(uris)
        }
    }

    // SAF CreateDocument for saving 11-Tab Excel Workbook (.xlsx)
    val excelSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri ->
        if (uri != null) {
            viewModel.exportActiveReportToUri(uri)
        }
    }

    // Android 8+ "Allow from this source" one-time permission launcher for trusted in-app APK updates
    var canInstallPackages by remember { mutableStateOf(viewModel.updaterService.canInstallPackagesInApp(context)) }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        canInstallPackages = viewModel.updaterService.canInstallPackagesInApp(context)
        val ready = uiState.updateState as? AppUpdateUiState.ReadyToInstall
        if (canInstallPackages && ready != null) {
            context.startActivity(viewModel.updaterService.buildInAppInstallIntent(context, ready.apkFile))
        }
    }

    // Trigger Android ShareSheet via FileProvider when share file is ready
    LaunchedEffect(uiState.pendingShareFile) {
        val file: File = uiState.pendingShareFile ?: return@LaunchedEffect
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "BSA-YourView Underwriting Report (yourview.in)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share 11-Tab Excel Report"))
        } finally {
            viewModel.consumePendingShareFile()
        }
    }

    // Show transient status messages in Snackbar
    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.clearTransientMessages()
    }

    // Handle system back press when on Report, History, or Settings sub-destinations
    BackHandler(enabled = uiState.activeDestination != AppDestination.ANALYZE && !uiState.isExcelConfigOpen) {
        viewModel.selectDestination(AppDestination.ANALYZE)
    }

    // Handle system back press when Excel Output Configuration overlay screen is open
    BackHandler(enabled = uiState.isExcelConfigOpen) {
        viewModel.closeExcelConfig()
    }

    BankAnalysierTheme(darkTheme = isDarkTheme) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 820.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    val hasUpdate = uiState.updateState is AppUpdateUiState.UpdateAvailable ||
                        uiState.updateState is AppUpdateUiState.ReadyToInstall
                    BrandTopHeader(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = { viewModel.toggleDarkLightMode(isDarkTheme) },
                        isOnlineBoostActive = uiState.isDeviceOnline && uiState.settings.onlineAccuracyBoostEnabled,
                        hasUpdateAvailable = hasUpdate,
                        onOpenUpdate = viewModel::reopenUpdateDialog
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!useNavRail) {
                        NavigationBar(
                            windowInsets = WindowInsets.navigationBars,
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            AppDestination.entries.forEach { dest ->
                                val selected = uiState.activeDestination == dest
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { viewModel.selectDestination(dest) },
                                    icon = { DestinationIcon(dest, selected) },
                                    label = { Text(dest.label) },
                                    modifier = Modifier.testTag("nav_${dest.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavRail) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            AppDestination.entries.forEach { dest ->
                                val selected = uiState.activeDestination == dest
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { viewModel.selectDestination(dest) },
                                    icon = { DestinationIcon(dest, selected) },
                                    label = { Text(dest.label) },
                                    modifier = Modifier.testTag("nav_${dest.name.lowercase()}")
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (uiState.isExcelConfigOpen) {
                            ExcelConfigScreen(
                                config = uiState.settings.excelExportConfig,
                                hasActiveReport = uiState.activeReport != null,
                                isExporting = uiState.isExporting,
                                onDateFormatChange = viewModel::updateExcelDateFormat,
                                onConfigChange = viewModel::updateExcelExportConfig,
                                onResetDefaults = viewModel::resetExcelExportConfig,
                                onBack = viewModel::closeExcelConfig,
                                onExportNow = {
                                    viewModel.closeExcelConfig()
                                    excelSaveLauncher.launch(viewModel.defaultExportFilename())
                                }
                            )
                        } else {
                            when (uiState.activeDestination) {
                                AppDestination.ANALYZE -> IntakeScreen(
                                    uiState = uiState,
                                    onPickPdfs = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                    onLoadSamplePdfs = viewModel::loadSampleStatements,
                                    onOpenPasswordPrompt = { fileId ->
                                        viewModel.openPasswordPrompt(fileId, resumeAnalysis = false)
                                    },
                                    onRemoveFile = viewModel::removeStagedFile,
                                    onApplicantIdChange = viewModel::updateApplicantId,
                                    onSourceNameChange = viewModel::updateSourceName,
                                    onProductTypeChange = viewModel::updateProductType,
                                    onFacilityTypeChange = viewModel::updateFacilityType,
                                    onSanctionedLimitChange = viewModel::updateSanctionedLimit,
                                    onDrawingPowerChange = viewModel::updateDrawingPower,
                                    onStartDateChange = viewModel::updateIntakeStartDate,
                                    onEndDateChange = viewModel::updateIntakeEndDate,
                                    onClearDates = viewModel::clearIntakeDateFilter,
                                    onRunAnalysis = viewModel::runForensicAnalysis
                                )

                                AppDestination.REPORT -> DashboardScreen(
                                    uiState = uiState,
                                    onSelectSubTab = viewModel::selectReportSubTab,
                                    onExportExcel = { excelSaveLauncher.launch(viewModel.defaultExportFilename()) },
                                    onShareExcel = viewModel::prepareShareExcel,
                                    onOpenExcelConfig = viewModel::openExcelConfig,
                                    onApplyDateFilter = viewModel::applyDashboardDateFilter,
                                    onLedgerSearchChange = viewModel::updateLedgerSearch,
                                    onLedgerCategoryChange = viewModel::updateLedgerCategoryFilter,
                                    onGoToAnalyze = { viewModel.selectDestination(AppDestination.ANALYZE) }
                                )

                                AppDestination.HISTORY -> HistoryScreen(
                                    cases = savedCases,
                                    onOpenCase = viewModel::openSavedCase,
                                    onDeleteCase = viewModel::deleteSavedCase
                                )

                                AppDestination.SETTINGS -> SettingsScreen(
                                    settings = uiState.settings,
                                    savedCaseCount = savedCases.size,
                                    isDeviceOnline = uiState.isDeviceOnline,
                                    currentAppVersion = viewModel.currentAppVersionDisplay(),
                                    effectiveUpdateRepo = viewModel.effectiveUpdateRepoDisplay(),
                                    updateState = uiState.updateState,
                                    onThemeModeChange = viewModel::updateThemeMode,
                                    onStrictForensicChange = viewModel::updateStrictForensicBlocking,
                                    onAutoSuppressOdChange = viewModel::updateAutoSuppressOdNegativeEod,
                                    onFuzzyThresholdChange = viewModel::updateFuzzyThreshold,
                                    onCompactCurrencyChange = viewModel::updateUseCompactCurrency,
                                    onOnlineBoostChange = viewModel::updateOnlineAccuracyBoost,
                                    onCloudAiAssistChange = viewModel::updateCloudAiAssist,
                                    onUpdateRepoChange = viewModel::updateGitHubRepoOverride,
                                    onCheckForUpdate = { viewModel.checkForAppUpdate(silent = false) },
                                    onOpenUpdateDialog = viewModel::reopenUpdateDialog,
                                    onOpenExcelConfig = viewModel::openExcelConfig,
                                    onClearAllCases = viewModel::clearAllSavedCases
                                )
                            }
                        }
                    }
                }
            }

            // Password-Protected PDF Entry Popup Dialog
            if (uiState.passwordPromptFileId != null) {
                PasswordPromptDialog(
                    fileName = uiState.passwordPromptFileName,
                    passwordInput = uiState.passwordPromptInput,
                    errorText = uiState.passwordPromptError,
                    isVerifying = uiState.isVerifyingPassword,
                    onPasswordChange = viewModel::updatePasswordPromptInput,
                    onSubmit = viewModel::submitPasswordPrompt,
                    onDismiss = viewModel::dismissPasswordPrompt
                )
            }

            // 100% In-App Auto-Updater Modal Dialog (zero browser redirect)
            if (uiState.showUpdateDialog && uiState.updateState !is AppUpdateUiState.Idle && uiState.updateState !is AppUpdateUiState.UpToDate) {
                InAppUpdateDialog(
                    updateState = uiState.updateState,
                    currentVersionLabel = viewModel.currentAppVersionDisplay(),
                    needsInstallPermission = !canInstallPackages,
                    onStartDownload = viewModel::startInAppUpdateDownload,
                    onInstallApk = { apkFile ->
                        canInstallPackages = viewModel.updaterService.canInstallPackagesInApp(context)
                        if (canInstallPackages) {
                            context.startActivity(viewModel.updaterService.buildInAppInstallIntent(context, apkFile))
                        } else {
                            installPermissionLauncher.launch(viewModel.updaterService.buildInstallPermissionIntent(context))
                        }
                    },
                    onRequestInstallPermission = {
                        installPermissionLauncher.launch(viewModel.updaterService.buildInstallPermissionIntent(context))
                    },
                    onDismiss = viewModel::dismissUpdateDialog
                )
            }
        }
    }
}

@Composable
private fun DestinationIcon(dest: AppDestination, selected: Boolean) {
    val icon = when (dest) {
        AppDestination.ANALYZE -> if (selected) Icons.Filled.UploadFile else Icons.Outlined.UploadFile
        AppDestination.REPORT -> if (selected) Icons.Filled.Assessment else Icons.Outlined.Assessment
        AppDestination.HISTORY -> if (selected) Icons.Filled.History else Icons.Outlined.History
        AppDestination.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
    }
    Icon(imageVector = icon, contentDescription = dest.label)
}
