package com.example.bsa.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bsa.model.OnlineEnrichmentSummary
import com.example.bsa.ui.AppUpdateUiState
import com.example.ui.theme.JetBrainsMono
import java.io.File
import java.util.Locale

/**
 * Dashboard card showing Connectivity-Aware Hybrid Online Accuracy enrichment results:
 * Verified RBI IFSC Bank/Branch/State/MICR, counterparty IFSCs resolved, Cloud Smart Assist
 * layout/narration refinement counts, and synced Indian bank holidays for Trigger #3.
 */
@Composable
fun OnlineEnrichmentSummaryCard(
    enrichment: OnlineEnrichmentSummary,
    modifier: Modifier = Modifier
) {
    val active = enrichment.usedOnlineBoost
    val accent = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
    val badgeBg = if (active) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val badgeFg = if (active) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    LedgerCard(modifier = modifier.testTag("online_enrichment_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (active) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Processing Accuracy Engine",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = enrichment.modeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = badgeBg) {
                Text(
                    text = if (active) "HYBRID" else "OFFLINE",
                    fontFamily = JetBrainsMono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    color = badgeFg,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        enrichment.accountIfscInfo?.let { info ->
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RBI Verified IFSC: ${info.ifsc}",
                            fontFamily = JetBrainsMono,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (info.micr.isNotBlank()) {
                            Text(
                                text = "MICR ${info.micr}",
                                fontFamily = JetBrainsMono,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                softWrap = false,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "${info.bank} — ${listOf(info.branch, info.city, info.state).filter { it.isNotBlank() }.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val rails = buildList {
                        if (info.rtgs) add("RTGS")
                        if (info.neft) add("NEFT")
                        if (info.imps) add("IMPS")
                        if (info.upi) add("UPI")
                    }
                    if (rails.isNotEmpty()) {
                        Text(
                            text = "Verified Rails: ${rails.joinToString(" • ")}",
                            fontFamily = JetBrainsMono,
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        if (active) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniStatPill(
                    label = "IFSCs Resolved",
                    value = "${enrichment.resolvedCounterpartyIfscCount}",
                    modifier = Modifier.weight(1f)
                )
                MiniStatPill(
                    label = "Txns Enriched",
                    value = "${enrichment.enrichedTransactionsCount}",
                    modifier = Modifier.weight(1f)
                )
                MiniStatPill(
                    label = "Holidays Synced",
                    value = "${enrichment.verifiedHolidayCount}",
                    modifier = Modifier.weight(1f)
                )
            }
            if (enrichment.aiRecoveredRowsCount > 0 || enrichment.aiReclassifiedCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Smart Assist: ${enrichment.aiRecoveredRowsCount} layout row(s) recovered • ${enrichment.aiReclassifiedCount} ambiguous narration(s) refined",
                    fontFamily = JetBrainsMono,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        if (enrichment.cloudAiStatusNote.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = enrichment.cloudAiStatusNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MiniStatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                fontFamily = JetBrainsMono,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

/**
 * 100% In-App Auto-Updater Modal Dialog (zero browser redirect).
 * Downloads the new APK directly inside BSA-YourView with real-time progress + SHA-256 verification,
 * then hands off to Android's native Package Installer via FileProvider.
 */
@Composable
fun InAppUpdateDialog(
    updateState: AppUpdateUiState,
    currentVersionLabel: String,
    needsInstallPermission: Boolean,
    onStartDownload: () -> Unit,
    onInstallApk: (File) -> Unit,
    onRequestInstallPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val canDismiss = updateState !is AppUpdateUiState.Downloading && updateState !is AppUpdateUiState.Installing

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        modifier = Modifier.testTag("in_app_update_dialog"),
        icon = {
            Icon(
                imageVector = when (updateState) {
                    is AppUpdateUiState.ReadyToInstall -> Icons.Filled.Verified
                    is AppUpdateUiState.Downloading -> Icons.Filled.Downloading
                    is AppUpdateUiState.Installing -> Icons.Filled.InstallMobile
                    is AppUpdateUiState.Error -> Icons.Filled.ErrorOutline
                    else -> Icons.Filled.SystemUpdate
                },
                contentDescription = null,
                tint = when (updateState) {
                    is AppUpdateUiState.ReadyToInstall, is AppUpdateUiState.Installing -> MaterialTheme.colorScheme.secondary
                    is AppUpdateUiState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
            )
        },
        title = {
            Text(
                text = when (updateState) {
                    is AppUpdateUiState.UpdateAvailable -> "New BSA-YourView Update"
                    is AppUpdateUiState.Downloading -> "Downloading Update In-App"
                    is AppUpdateUiState.ReadyToInstall -> "Verified & Ready to Install"
                    is AppUpdateUiState.Installing -> "Installing Update In-App"
                    is AppUpdateUiState.Error -> "Update Check Notice"
                    else -> "Software Update"
                },
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (updateState) {
                    is AppUpdateUiState.UpdateAvailable -> {
                        val rel = updateState.release
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Installed: $currentVersionLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = "New: v${rel.versionName} (Build ${rel.versionCode})",
                                        fontFamily = JetBrainsMono,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (rel.fileSizeBytes > 0L) {
                                    Text(
                                        text = formatMb(rel.fileSizeBytes),
                                        fontFamily = JetBrainsMono,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        if (rel.releaseNotes.isNotBlank()) {
                            Text(
                                text = rel.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Downloads and installs directly inside BSA-YourView with SHA-256 verification, then automatically reopens the app.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    is AppUpdateUiState.Downloading -> {
                        val pct = (updateState.progress * 100).toInt().coerceIn(0, 100)
                        Text(
                            text = "Downloading ${updateState.release.apkFileName}...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(
                            progress = { updateState.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .testTag("update_download_progress"),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (updateState.totalBytes > 0L) {
                                    "${formatMb(updateState.downloadedBytes)} / ${formatMb(updateState.totalBytes)}"
                                } else {
                                    formatMb(updateState.downloadedBytes)
                                },
                                fontFamily = JetBrainsMono,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$pct%",
                                fontFamily = JetBrainsMono,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    is AppUpdateUiState.ReadyToInstall -> {
                        Text(
                            text = "BSA-YourView v${updateState.release.versionName} (Build ${updateState.release.versionCode}) was downloaded and verified inside the app.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (updateState.release.sha256.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = "SHA-256 Verified: ${updateState.release.sha256.take(16)}...",
                                    fontFamily = JetBrainsMono,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                        if (needsInstallPermission) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "One-time Android security step: enable \"Allow from this source\" for BSA-YourView so updates install in-app and reopen automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }

                    is AppUpdateUiState.Installing -> {
                        Text(
                            text = updateState.statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        ) {
                            Text(
                                text = "BSA-YourView v${updateState.release.versionName} will automatically reopen as soon as installation completes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    is AppUpdateUiState.Error -> {
                        Text(
                            text = updateState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (updateState) {
                is AppUpdateUiState.UpdateAvailable -> {
                    Button(
                        onClick = onStartDownload,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("update_download_now_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download & Install")
                    }
                }
                is AppUpdateUiState.ReadyToInstall -> {
                    if (needsInstallPermission) {
                        Button(
                            onClick = onRequestInstallPermission,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("update_grant_install_perm_button")
                        ) {
                            Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Allow In-App Install")
                        }
                    } else {
                        Button(
                            onClick = { onInstallApk(updateState.apkFile) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("update_install_now_button")
                        ) {
                            Icon(Icons.Filled.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Install Update Now")
                        }
                    }
                }
                is AppUpdateUiState.Installing -> {
                    TextButton(onClick = onDismiss) {
                        Text("Run in Background")
                    }
                }
                else -> {
                    TextButton(onClick = onDismiss, enabled = canDismiss) {
                        Text("Close")
                    }
                }
            }
        },
        dismissButton = {
            if (updateState is AppUpdateUiState.UpdateAvailable || updateState is AppUpdateUiState.ReadyToInstall) {
                TextButton(onClick = onDismiss) {
                    Text("Later")
                }
            }
        }
    )
}

private fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.ROOT, "%.2f MB", mb)
}
