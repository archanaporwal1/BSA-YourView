package com.example.bsa.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.bsa.model.FacilityType
import com.example.bsa.model.ProductType
import com.example.bsa.ui.BsaUiState
import com.example.bsa.ui.components.DateRangePickerRow
import com.example.bsa.ui.components.LedgerCard
import com.example.bsa.ui.components.StatementQueueSection
import com.example.bsa.ui.components.YourViewAccentButton
import com.example.bsa.ui.components.YvBrandEndorsementFooter
import com.example.ui.theme.JetBrainsMono

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntakeScreen(
    uiState: BsaUiState,
    onPickPdfsForSlot: (String) -> Unit,
    onLoadSamplePdfs: () -> Unit,
    onLoadMultiAccountPortfolio: () -> Unit = {},
    onSelectAccountSlot: (String) -> Unit = {},
    onAddAccountSlot: () -> Unit = {},
    onRenameAccountSlot: (String, String) -> Unit = { _, _ -> },
    onRemoveAccountSlot: (String) -> Unit = {},
    onClearAccountSlot: (String) -> Unit = {},
    onClearQueue: () -> Unit = {},
    onAssignFileToSlot: (String, String) -> Unit = { _, _ -> },
    onMoveFileInQueue: (String, Int) -> Unit = { _, _ -> },
    onUnlockNextLocked: () -> Unit = {},
    onRetryFailed: () -> Unit = {},
    onOpenPasswordPrompt: (String) -> Unit,
    onRemoveFile: (String) -> Unit,
    onApplicantIdChange: (String) -> Unit,
    onSourceNameChange: (String) -> Unit,
    onProductTypeChange: (ProductType) -> Unit,
    onFacilityTypeChange: (FacilityType) -> Unit,
    onSanctionedLimitChange: (String) -> Unit,
    onDrawingPowerChange: (String) -> Unit,
    onStartDateChange: (String?) -> Unit,
    onEndDateChange: (String?) -> Unit,
    onClearDates: () -> Unit,
    onRunAnalysis: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 820.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = if (isWide) 1120.dp else 680.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Error Banner
                AnimatedVisibility(visible = !uiState.errorBanner.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = uiState.errorBanner.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // Analyzing Staged Progress Card with Determinate Percentage
                AnimatedVisibility(visible = uiState.isAnalyzing) {
                    val pct = (uiState.analysisProgress * 100f).toInt().coerceIn(0, 100)
                    LedgerCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val isHybridActive = uiState.isDeviceOnline && uiState.settings.onlineAccuracyBoostEnabled
                                Text(
                                    text = if (isHybridActive) {
                                        "Running Online Hybrid Engine"
                                    } else {
                                        "Running Deterministic Offline Engine"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = uiState.analysisStageMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "$pct%",
                                fontFamily = JetBrainsMono,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { uiState.analysisProgress.coerceIn(0.03f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }

                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        StatementQueueSection(
                            stagedFiles = uiState.stagedFiles,
                            activeAccountSlot = uiState.activeAccountSlot,
                            availableAccountSlots = uiState.availableAccountSlots,
                            isAnalyzing = uiState.isAnalyzing,
                            onSelectAccountSlot = onSelectAccountSlot,
                            onAddAccountSlot = onAddAccountSlot,
                            onRenameAccountSlot = onRenameAccountSlot,
                            onRemoveAccountSlot = onRemoveAccountSlot,
                            onClearAccountSlot = onClearAccountSlot,
                            onClearQueue = onClearQueue,
                            onAssignFileToSlot = onAssignFileToSlot,
                            onMoveFileInQueue = onMoveFileInQueue,
                            onUnlockNextLocked = onUnlockNextLocked,
                            onRetryFailed = onRetryFailed,
                            onPickPdfsForSlot = onPickPdfsForSlot,
                            onLoadSamplePdfs = onLoadSamplePdfs,
                            onLoadMultiAccountPortfolio = onLoadMultiAccountPortfolio,
                            onOpenPasswordPrompt = onOpenPasswordPrompt,
                            onRemoveFile = onRemoveFile,
                            modifier = Modifier.weight(1f)
                        )
                        ApplicantFacilityFormSection(
                            uiState = uiState,
                            onApplicantIdChange = onApplicantIdChange,
                            onSourceNameChange = onSourceNameChange,
                            onProductTypeChange = onProductTypeChange,
                            onFacilityTypeChange = onFacilityTypeChange,
                            onSanctionedLimitChange = onSanctionedLimitChange,
                            onDrawingPowerChange = onDrawingPowerChange,
                            onStartDateChange = onStartDateChange,
                            onEndDateChange = onEndDateChange,
                            onClearDates = onClearDates,
                            onRunAnalysis = onRunAnalysis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    StatementQueueSection(
                        stagedFiles = uiState.stagedFiles,
                        activeAccountSlot = uiState.activeAccountSlot,
                        availableAccountSlots = uiState.availableAccountSlots,
                        isAnalyzing = uiState.isAnalyzing,
                        onSelectAccountSlot = onSelectAccountSlot,
                        onAddAccountSlot = onAddAccountSlot,
                        onRenameAccountSlot = onRenameAccountSlot,
                        onRemoveAccountSlot = onRemoveAccountSlot,
                        onClearAccountSlot = onClearAccountSlot,
                        onClearQueue = onClearQueue,
                        onAssignFileToSlot = onAssignFileToSlot,
                        onMoveFileInQueue = onMoveFileInQueue,
                        onUnlockNextLocked = onUnlockNextLocked,
                        onRetryFailed = onRetryFailed,
                        onPickPdfsForSlot = onPickPdfsForSlot,
                        onLoadSamplePdfs = onLoadSamplePdfs,
                        onLoadMultiAccountPortfolio = onLoadMultiAccountPortfolio,
                        onOpenPasswordPrompt = onOpenPasswordPrompt,
                        onRemoveFile = onRemoveFile
                    )
                    ApplicantFacilityFormSection(
                        uiState = uiState,
                        onApplicantIdChange = onApplicantIdChange,
                        onSourceNameChange = onSourceNameChange,
                        onProductTypeChange = onProductTypeChange,
                        onFacilityTypeChange = onFacilityTypeChange,
                        onSanctionedLimitChange = onSanctionedLimitChange,
                        onDrawingPowerChange = onDrawingPowerChange,
                        onStartDateChange = onStartDateChange,
                        onEndDateChange = onEndDateChange,
                        onClearDates = onClearDates,
                        onRunAnalysis = onRunAnalysis
                    )
                }

                // Exclusive YourView Brand Footer with YV Emblem
                YvBrandEndorsementFooter()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApplicantFacilityFormSection(
    uiState: BsaUiState,
    onApplicantIdChange: (String) -> Unit,
    onSourceNameChange: (String) -> Unit,
    onProductTypeChange: (ProductType) -> Unit,
    onFacilityTypeChange: (FacilityType) -> Unit,
    onSanctionedLimitChange: (String) -> Unit,
    onDrawingPowerChange: (String) -> Unit,
    onStartDateChange: (String?) -> Unit,
    onEndDateChange: (String?) -> Unit,
    onClearDates: () -> Unit,
    onRunAnalysis: () -> Unit,
    modifier: Modifier = Modifier
) {
    LedgerCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.AccountBalance,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "2. ${stringResource(R.string.section_applicant_facility)}",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= 420.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.config.applicantId,
                        onValueChange = onApplicantIdChange,
                        label = { Text(stringResource(R.string.label_applicant_id), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("applicant_id_input")
                    )
                    OutlinedTextField(
                        value = uiState.config.sourceName,
                        onValueChange = onSourceNameChange,
                        label = { Text(stringResource(R.string.label_source_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("source_name_input")
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.config.applicantId,
                        onValueChange = onApplicantIdChange,
                        label = { Text(stringResource(R.string.label_applicant_id), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("applicant_id_input")
                    )
                    OutlinedTextField(
                        value = uiState.config.sourceName,
                        onValueChange = onSourceNameChange,
                        label = { Text(stringResource(R.string.label_source_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("source_name_input")
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.label_product_type),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ProductType.entries.forEach { pt ->
                FilterChip(
                    selected = uiState.config.productType == pt,
                    onClick = { onProductTypeChange(pt) },
                    label = { Text(pt.displayName) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.label_facility_type),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FacilityType.entries.forEach { ft ->
                FilterChip(
                    selected = uiState.config.facilityType == ft,
                    onClick = { onFacilityTypeChange(ft) },
                    label = { Text("${ft.code} (${ft.label})") },
                    leadingIcon = if (uiState.config.facilityType == ft) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.testTag("facility_chip_${ft.code.lowercase()}")
                )
            }
        }

        // Mandatory OD / CC Limit Entry Section (only shown for OD or CC facilities)
        val isOdOrCc = uiState.config.facilityType != FacilityType.NONE
        AnimatedVisibility(visible = isOdOrCc) {
            Column {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                    border = BorderStroke(
                        1.dp,
                        if (uiState.odLimitError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.AccountBalanceWallet,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${uiState.config.facilityType.code} Facility Limit Configuration (Required)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = uiState.sanctionedLimitInput,
                                onValueChange = onSanctionedLimitChange,
                                label = { Text("Sanctioned ${uiState.config.facilityType.code} Limit *", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                prefix = { Text("₹", fontFamily = JetBrainsMono) },
                                singleLine = true,
                                isError = uiState.odLimitError != null,
                                supportingText = uiState.odLimitError?.let { err -> { Text(err) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sanctioned_limit_input")
                            )
                            OutlinedTextField(
                                value = uiState.drawingPowerInput,
                                onValueChange = onDrawingPowerChange,
                                label = { Text(stringResource(R.string.label_drawing_power), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                prefix = { Text("₹", fontFamily = JetBrainsMono) },
                                singleLine = true,
                                supportingText = { Text("Optional — defaults to Sanctioned ${uiState.config.facilityType.code} Limit") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("drawing_power_input")
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Dr balances normalize to negative, daily OD utilization tracks against Drawing Power, and Trigger #7 auto-suppresses.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))

        // 3. Exact Date Range Filter Section
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.DateRange,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    text = "3. ${stringResource(R.string.section_date_range)} (Optional)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Pick exact From / To dates to restrict all KPIs, fraud checks, and 18-tab Excel workbook strictly to those dates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        DateRangePickerRow(
            startDateIso = uiState.config.filterStartDateIso,
            endDateIso = uiState.config.filterEndDateIso,
            onStartDateSelected = onStartDateChange,
            onEndDateSelected = onEndDateChange,
            onClearDates = onClearDates
        )

        Spacer(Modifier.height(18.dp))

        YourViewAccentButton(
            text = if (uiState.isAnalyzing) "Analyzing Statements..." else stringResource(R.string.btn_run_analysis),
            icon = Icons.Filled.Analytics,
            onClick = onRunAnalysis,
            enabled = !uiState.isAnalyzing,
            isLoading = uiState.isAnalyzing,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("run_analysis_button")
        )
    }
}
