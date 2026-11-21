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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.bsa.model.FacilityType
import com.example.bsa.model.ProductType
import com.example.bsa.model.StagedPdfFile
import com.example.bsa.ui.BsaUiState
import com.example.bsa.ui.components.BsaFormatters
import com.example.bsa.ui.components.DateRangePickerRow
import com.example.bsa.ui.components.LedgerCard
import com.example.bsa.ui.components.YvBrandEndorsementFooter
import com.example.ui.theme.JetBrainsMono

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntakeScreen(
    uiState: BsaUiState,
    onPickPdfs: () -> Unit,
    onLoadSamplePdfs: () -> Unit,
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

                // Analyzing Staged Progress Card
                AnimatedVisibility(visible = uiState.isAnalyzing) {
                    LedgerCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Running Deterministic Offline Engine",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = uiState.analysisStageMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        PdfUploadSection(
                            stagedFiles = uiState.stagedFiles,
                            onPickPdfs = onPickPdfs,
                            onLoadSamplePdfs = onLoadSamplePdfs,
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
                    PdfUploadSection(
                        stagedFiles = uiState.stagedFiles,
                        onPickPdfs = onPickPdfs,
                        onLoadSamplePdfs = onLoadSamplePdfs,
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

@Composable
private fun PdfUploadSection(
    stagedFiles: List<StagedPdfFile>,
    onPickPdfs: () -> Unit,
    onLoadSamplePdfs: () -> Unit,
    onOpenPasswordPrompt: (String) -> Unit,
    onRemoveFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LedgerCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "1. Statement ePDF Queue",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (stagedFiles.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${stagedFiles.size} file(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Interactive Dashed Drop / Pick Zone
        val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPickPdfs)
                .testTag("pdf_upload_zone")
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                    )
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.UploadFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = stringResource(R.string.upload_zone_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.upload_zone_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onPickPdfs,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("attach_pdfs_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_add_pdfs))
                    }
                    OutlinedButton(
                        onClick = onLoadSamplePdfs,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("load_sample_pdfs_button")
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_load_sample))
                    }
                }
            }
        }

        if (stagedFiles.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stagedFiles.forEach { file ->
                    StagedPdfRowCard(
                        file = file,
                        onOpenPasswordPrompt = { onOpenPasswordPrompt(file.id) },
                        onRemove = { onRemoveFile(file.id) }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Supported Indian Banks: ICICI, HDFC, SBI, Axis, Kotak Mahindra, Union Bank • Auto-detects encrypted ePDFs with instant password popup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StagedPdfRowCard(
    file: StagedPdfFile,
    onOpenPasswordPrompt: () -> Unit,
    onRemove: () -> Unit
) {
    val isLocked = file.isPasswordProtected && !file.isUnlocked
    val isUnlocked = file.isPasswordProtected && file.isUnlocked

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                file.passwordError != null -> MaterialTheme.colorScheme.error
                isLocked -> MaterialTheme.colorScheme.tertiary
                isUnlocked -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when {
                            isLocked -> Icons.Filled.Lock
                            isUnlocked -> Icons.Filled.LockOpen
                            else -> Icons.Filled.PictureAsPdf
                        },
                        contentDescription = null,
                        tint = when {
                            isLocked -> MaterialTheme.colorScheme.tertiary
                            isUnlocked -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = file.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${BsaFormatters.formatFileSize(file.fileSizeBytes)}${if (file.isSample) " • Sample ePDF" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_remove_file),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Password Status & Popup Trigger Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                when {
                    isLocked -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Password Required",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    isUnlocked -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Unlocked in Memory",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = "Unencrypted ePDF",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isLocked) {
                    Button(
                        onClick = onOpenPasswordPrompt,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .testTag("enter_password_button")
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Enter Password", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    TextButton(
                        onClick = onOpenPasswordPrompt,
                        modifier = Modifier.testTag("password_popup_trigger")
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isUnlocked) "Change Password" else "Set Password")
                    }
                }
            }

            if (file.passwordError != null) {
                Text(
                    text = file.passwordError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = uiState.config.applicantId,
                onValueChange = onApplicantIdChange,
                label = { Text(stringResource(R.string.label_applicant_id)) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("applicant_id_input")
            )
            OutlinedTextField(
                value = uiState.config.sourceName,
                onValueChange = onSourceNameChange,
                label = { Text(stringResource(R.string.label_source_name)) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("source_name_input")
            )
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = uiState.sanctionedLimitInput,
                                onValueChange = onSanctionedLimitChange,
                                label = { Text("Sanctioned ${uiState.config.facilityType.code} Limit *") },
                                prefix = { Text("₹", fontFamily = JetBrainsMono) },
                                singleLine = true,
                                isError = uiState.odLimitError != null,
                                supportingText = uiState.odLimitError?.let { err -> { Text(err) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sanctioned_limit_input")
                            )
                            OutlinedTextField(
                                value = uiState.drawingPowerInput,
                                onValueChange = onDrawingPowerChange,
                                label = { Text(stringResource(R.string.label_drawing_power)) },
                                prefix = { Text("₹", fontFamily = JetBrainsMono) },
                                singleLine = true,
                                supportingText = { Text("Defaults to OD limit") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
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
                    text = "Pick exact From / To dates to restrict all KPIs, fraud checks, and 11-tab Excel output strictly to those dates.",
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

        Button(
            onClick = onRunAnalysis,
            enabled = !uiState.isAnalyzing,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("run_analysis_button"),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(Icons.Filled.Analytics, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (uiState.isAnalyzing) "Analyzing Statements..." else stringResource(R.string.btn_run_analysis),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
