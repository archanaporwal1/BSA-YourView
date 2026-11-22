package com.example.bsa.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.bsa.engine.IndianBankRegistry
import com.example.bsa.model.QueueFileStatus
import com.example.bsa.model.StagedPdfFile
import com.example.ui.theme.JetBrainsMono

/**
 * Complete Multi-Account Statement Queue Section:
 * - Target Account Queue Slot selector with live file counts, + Add Account, Rename & Remove
 * - Dashed Drop/Pick Zone + YourView Branded Actions (Attach PDFs, Multi-Account Demo, Reference Case)
 * - Live Queue Readiness Bar (Ready / Locked / Failed / Total Size + Unlock Next / Retry / Clear All)
 * - Account-Grouped Statement Queue with explicit Move-to-Account dropdown, Reorder Up/Down,
 *   Per-File Status Badges (Probing, Ready, Locked, Parsing, Done · N txns, Failed), & Password Unlock.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatementQueueSection(
    stagedFiles: List<StagedPdfFile>,
    activeAccountSlot: String,
    availableAccountSlots: List<String>,
    isAnalyzing: Boolean,
    onSelectAccountSlot: (String) -> Unit,
    onAddAccountSlot: () -> Unit,
    onRenameAccountSlot: (String, String) -> Unit,
    onRemoveAccountSlot: (String) -> Unit,
    onClearAccountSlot: (String) -> Unit,
    onClearQueue: () -> Unit,
    onAssignFileToSlot: (String, String) -> Unit,
    onMoveFileInQueue: (String, Int) -> Unit,
    onUnlockNextLocked: () -> Unit,
    onRetryFailed: () -> Unit,
    onPickPdfsForSlot: (String) -> Unit,
    onLoadSamplePdfs: () -> Unit,
    onLoadMultiAccountPortfolio: () -> Unit,
    onOpenPasswordPrompt: (String) -> Unit,
    onRemoveFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var slotToRename by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var showBankDirectory by remember { mutableStateOf(false) }

    val activeAccountsUsed = remember(stagedFiles) {
        stagedFiles.map { it.accountGroupLabel }.distinct().size
    }
    val readyCount = remember(stagedFiles) {
        stagedFiles.count { it.queueStatus == QueueFileStatus.READY || it.queueStatus == QueueFileStatus.COMPLETED }
    }
    val lockedCount = remember(stagedFiles) {
        stagedFiles.count { (it.isPasswordProtected && !it.isUnlocked) || it.queueStatus == QueueFileStatus.LOCKED }
    }
    val failedCount = remember(stagedFiles) {
        stagedFiles.count { it.queueStatus == QueueFileStatus.FAILED }
    }
    val totalQueueBytes = remember(stagedFiles) {
        stagedFiles.sumOf { it.fileSizeBytes }
    }

    LedgerCard(modifier = modifier.testTag("statement_queue_section")) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Filled.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "1. Multi-Account ePDF Queue",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (stagedFiles.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${stagedFiles.size} PDF${if (stagedFiles.size == 1) "" else "s"} • $activeAccountsUsed A/c",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Account Slot Selector Header + Rename active slot shortcut
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Target Account Queue:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    slotToRename = activeAccountSlot
                    renameDraft = activeAccountSlot
                },
                enabled = !isAnalyzing,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.testTag("rename_account_slot_button")
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Rename Slot", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            availableAccountSlots.forEach { slot ->
                val countInSlot = stagedFiles.count { it.accountGroupLabel == slot }
                val isSelected = activeAccountSlot == slot
                FilterChip(
                    selected = isSelected,
                    enabled = !isAnalyzing,
                    onClick = { onSelectAccountSlot(slot) },
                    label = {
                        Text(
                            text = if (countInSlot > 0) "$slot ($countInSlot)" else slot,
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    leadingIcon = if (isSelected) {
                        { Icon(Icons.Filled.AccountBalance, contentDescription = null, modifier = Modifier.size(15.dp)) }
                    } else null,
                    trailingIcon = if (availableAccountSlots.size > 1 && countInSlot == 0 && isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove empty $slot",
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { onRemoveAccountSlot(slot) }
                            )
                        }
                    } else null,
                    modifier = Modifier.testTag("account_slot_${slot.lowercase().replace(" ", "_")}")
                )
            }
            AssistChip(
                onClick = onAddAccountSlot,
                enabled = !isAnalyzing,
                label = { Text("+ Add Account", maxLines = 1, softWrap = false) },
                modifier = Modifier.testTag("add_account_slot_button")
            )
        }

        Spacer(Modifier.height(12.dp))

        // Interactive Dashed Drop / Pick Zone
        val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !isAnalyzing) { onPickPdfsForSlot(activeAccountSlot) }
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
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                ) {
                    Box(
                        modifier = Modifier.size(46.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.UploadFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.upload_zone_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Queuing directly into $activeAccountSlot • Tap to attach 1 or multiple ePDFs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Cleanly stacked YourView Branded Action Buttons (zero overlap, 48.dp+ touch targets)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            YourViewPrimaryButton(
                text = "${stringResource(R.string.btn_add_pdfs)} ($activeAccountSlot)",
                icon = Icons.Filled.Add,
                enabled = !isAnalyzing,
                onClick = { onPickPdfsForSlot(activeAccountSlot) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("attach_pdfs_button")
            )
            YourViewSecondaryButton(
                text = stringResource(R.string.btn_load_multi_account),
                icon = Icons.Filled.AccountTree,
                enabled = !isAnalyzing,
                onClick = onLoadMultiAccountPortfolio,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("load_multi_account_portfolio_button")
            )
            YourViewOutlinedButton(
                text = stringResource(R.string.btn_load_sample),
                icon = Icons.Filled.AutoAwesome,
                enabled = !isAnalyzing,
                onClick = onLoadSamplePdfs,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("load_sample_pdfs_button")
            )
        }

        // Queue Readiness & Management Strip + Grouped Queue Cards
        AnimatedVisibility(visible = stagedFiles.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QueueReadinessSummaryBar(
                    totalFiles = stagedFiles.size,
                    readyCount = readyCount,
                    lockedCount = lockedCount,
                    failedCount = failedCount,
                    totalBytes = totalQueueBytes,
                    isAnalyzing = isAnalyzing,
                    onUnlockNext = onUnlockNextLocked,
                    onRetryFailed = onRetryFailed,
                    onClearQueue = onClearQueue
                )

                // Group files by their assigned account slot preserving slot order
                val orderedSlotsWithFiles = remember(stagedFiles, availableAccountSlots) {
                    val present = stagedFiles.map { it.accountGroupLabel }.distinct()
                    val ordered = availableAccountSlots.filter { it in present }
                    val extras = present.filter { it !in ordered }
                    ordered + extras
                }

                orderedSlotsWithFiles.forEach { slotName ->
                    val slotFiles = stagedFiles.filter { it.accountGroupLabel == slotName }
                    val slotBytes = slotFiles.sumOf { it.fileSizeBytes }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Account Group Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AccountBalance,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = slotName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "(${slotFiles.size} • ${BsaFormatters.formatFileSize(slotBytes)})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    TextButton(
                                        onClick = { onPickPdfsForSlot(slotName) },
                                        enabled = !isAnalyzing,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("Add", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                                    }
                                    TextButton(
                                        onClick = { onClearAccountSlot(slotName) },
                                        enabled = !isAnalyzing,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "Clear",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            // Queued PDF Items inside this Account Group
                            slotFiles.forEach { file ->
                                val globalIdx = stagedFiles.indexOfFirst { it.id == file.id }
                                QueuedPdfItemCard(
                                    file = file,
                                    availableSlots = availableAccountSlots,
                                    canMoveUp = globalIdx > 0 && !isAnalyzing,
                                    canMoveDown = globalIdx in 0 until stagedFiles.lastIndex && !isAnalyzing,
                                    isAnalyzing = isAnalyzing,
                                    onAssignSlot = { newSlot -> onAssignFileToSlot(file.id, newSlot) },
                                    onMoveUp = { onMoveFileInQueue(file.id, -1) },
                                    onMoveDown = { onMoveFileInQueue(file.id, +1) },
                                    onOpenPasswordPrompt = { onOpenPasswordPrompt(file.id) },
                                    onRemove = { onRemoveFile(file.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Supported Banks: ${IndianBankRegistry.totalSupportedCount}+ Indian Banks + Universal Parser",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "All 12 PSUs, 21 Private, 11 Small Finance, 6 Payments, 35 Co-op, 30 RRBs, 20 Foreign & any unlisted bank ePDF • Auto-detects encrypted PDFs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { showBankDirectory = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("view_supported_banks_button")
                ) {
                    Text(
                        text = "View All",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }

    if (showBankDirectory) {
        SupportedBanksDirectoryDialog(onDismiss = { showBankDirectory = false })
    }

    // Rename Account Slot Dialog
    val editingSlot = slotToRename
    if (editingSlot != null) {
        AlertDialog(
            onDismissRequest = { slotToRename = null },
            title = { Text("Rename Account Queue Slot", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Give this account a clear label (e.g., \"ICICI OD\", \"HDFC Current\", \"SBI CC\") so it shows up clearly in the Portfolio Risk view.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it.take(28) },
                        label = { Text("Account Label") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rename_slot_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameAccountSlot(editingSlot, renameDraft)
                        slotToRename = null
                    },
                    enabled = renameDraft.trim().isNotBlank(),
                    modifier = Modifier.testTag("confirm_rename_slot_button")
                ) {
                    Text("Save Label")
                }
            },
            dismissButton = {
                TextButton(onClick = { slotToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueReadinessSummaryBar(
    totalFiles: Int,
    readyCount: Int,
    lockedCount: Int,
    failedCount: Int,
    totalBytes: Long,
    isAnalyzing: Boolean,
    onUnlockNext: () -> Unit,
    onRetryFailed: () -> Unit,
    onClearQueue: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("queue_readiness_bar")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Queue Readiness ($totalFiles file${if (totalFiles == 1) "" else "s"} • ${BsaFormatters.formatFileSize(totalBytes)})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onClearQueue,
                    enabled = !isAnalyzing,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("clear_queue_button")
                ) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Clear All",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QueueCountPill(
                    label = "Ready: $readyCount",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer
                )
                if (lockedCount > 0) {
                    QueueCountPill(
                        label = "Locked: $lockedCount",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    AssistChip(
                        onClick = onUnlockNext,
                        enabled = !isAnalyzing,
                        leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        label = { Text("Unlock Next", maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("unlock_next_locked_button")
                    )
                }
                if (failedCount > 0) {
                    QueueCountPill(
                        label = "Failed: $failedCount",
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer
                    )
                    AssistChip(
                        onClick = onRetryFailed,
                        enabled = !isAnalyzing,
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        label = { Text("Retry Failed", maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("retry_failed_queue_button")
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueCountPill(
    label: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun QueuedPdfItemCard(
    file: StagedPdfFile,
    availableSlots: List<String>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isAnalyzing: Boolean,
    onAssignSlot: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onOpenPasswordPrompt: () -> Unit,
    onRemove: () -> Unit
) {
    val isLocked = (file.isPasswordProtected && !file.isUnlocked) || file.queueStatus == QueueFileStatus.LOCKED
    val isUnlocked = file.isPasswordProtected && file.isUnlocked
    var showSlotMenu by remember { mutableStateOf(false) }

    val borderColor = when {
        file.passwordError != null || file.queueStatus == QueueFileStatus.FAILED -> MaterialTheme.colorScheme.error
        file.queueStatus == QueueFileStatus.PARSING -> MaterialTheme.colorScheme.primary
        isLocked -> MaterialTheme.colorScheme.tertiary
        file.queueStatus == QueueFileStatus.COMPLETED || isUnlocked -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Row: Status Icon, File Name, Reorder & Remove controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (file.queueStatus == QueueFileStatus.PARSING || file.queueStatus == QueueFileStatus.PROBING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = when {
                                file.queueStatus == QueueFileStatus.FAILED -> Icons.Filled.ErrorOutline
                                file.queueStatus == QueueFileStatus.COMPLETED -> Icons.Filled.CheckCircle
                                isLocked -> Icons.Filled.Lock
                                isUnlocked -> Icons.Filled.LockOpen
                                else -> Icons.Filled.PictureAsPdf
                            },
                            contentDescription = null,
                            tint = when {
                                file.queueStatus == QueueFileStatus.FAILED -> MaterialTheme.colorScheme.error
                                file.queueStatus == QueueFileStatus.COMPLETED || isUnlocked -> MaterialTheme.colorScheme.secondary
                                isLocked -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = file.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(BsaFormatters.formatFileSize(file.fileSizeBytes))
                                if (file.detectedBankHint.isNotBlank()) {
                                    append(" • ")
                                    append(file.detectedBankHint)
                                }
                                if (file.isSample) {
                                    append(" • Reference")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Reorder & Remove actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canMoveUp || canMoveDown) {
                        IconButton(
                            onClick = onMoveUp,
                            enabled = canMoveUp,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Move up in queue",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onMoveDown,
                            enabled = canMoveDown,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Move down in queue",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onRemove,
                        enabled = !isAnalyzing,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_remove_file),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Middle Row: Live Status Pill + Account Reassignment Dropdown + Password Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Live Queue Status Pill
                val (pillBg, pillFg, pillText) = when {
                    file.queueStatus == QueueFileStatus.FAILED -> Triple(
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer,
                        "Failed"
                    )
                    file.queueStatus == QueueFileStatus.PARSING -> Triple(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                        "Parsing…"
                    )
                    file.queueStatus == QueueFileStatus.PROBING -> Triple(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        "Checking…"
                    )
                    file.queueStatus == QueueFileStatus.COMPLETED -> Triple(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        if (file.extractedTxnCount > 0) "Done • ${file.extractedTxnCount} txns" else "Done"
                    )
                    isLocked -> Triple(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer,
                        "Password Required"
                    )
                    isUnlocked -> Triple(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        "Unlocked • Ready"
                    )
                    else -> Triple(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        "Ready"
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = pillBg
                ) {
                    Text(
                        text = pillText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = pillFg,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Explicit Move-to-Account Dropdown Trigger
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isAnalyzing) { showSlotMenu = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = file.accountGroupLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = "Move file to another account",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showSlotMenu,
                        onDismissRequest = { showSlotMenu = false }
                    ) {
                        availableSlots.forEach { slot ->
                            DropdownMenuItem(
                                text = { Text(slot) },
                                leadingIcon = {
                                    if (slot == file.accountGroupLabel) {
                                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    } else {
                                        Icon(Icons.Filled.AccountBalance, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                },
                                onClick = {
                                    showSlotMenu = false
                                    onAssignSlot(slot)
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                if (isLocked) {
                    Button(
                        onClick = onOpenPasswordPrompt,
                        enabled = !isAnalyzing,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .testTag("enter_password_button")
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Unlock", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                    }
                } else {
                    TextButton(
                        onClick = onOpenPasswordPrompt,
                        enabled = !isAnalyzing,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("password_popup_trigger")
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = if (isUnlocked) "Change Pass" else "Password",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Processing Note or Password Error
            val detailMsg = file.passwordError ?: file.processingNote
            if (!detailMsg.isNullOrBlank()) {
                Text(
                    text = detailMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (file.passwordError != null || file.queueStatus == QueueFileStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
