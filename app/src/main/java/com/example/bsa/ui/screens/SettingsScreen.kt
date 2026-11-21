package com.example.bsa.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bsa.model.AppSettings
import com.example.bsa.model.ThemeMode
import com.example.bsa.ui.AppUpdateUiState
import com.example.bsa.ui.components.LedgerCard
import com.example.ui.theme.JetBrainsMono
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    savedCaseCount: Int,
    isDeviceOnline: Boolean,
    currentAppVersion: String,
    effectiveUpdateRepo: String,
    updateState: AppUpdateUiState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onStrictForensicChange: (Boolean) -> Unit,
    onAutoSuppressOdChange: (Boolean) -> Unit,
    onFuzzyThresholdChange: (Int) -> Unit,
    onCompactCurrencyChange: (Boolean) -> Unit,
    onOnlineBoostChange: (Boolean) -> Unit,
    onCloudAiAssistChange: (Boolean) -> Unit,
    onUpdateRepoChange: (String) -> Unit,
    onCheckForUpdate: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
    onOpenExcelConfig: () -> Unit,
    onClearAllCases: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Appearance & Display Mode
            LedgerCard {
                SectionHeading(
                    icon = Icons.Filled.Palette,
                    title = "Appearance & Display"
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Color Theme Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = settings.themeMode == mode
                        val icon = when (mode) {
                            ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Filled.LightMode
                            ThemeMode.DARK -> Icons.Filled.DarkMode
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(mode.label) },
                            leadingIcon = {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("theme_mode_${mode.name.lowercase()}")
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                SettingSwitchRow(
                    title = "Compact INR Display",
                    description = "Format KPI totals as ₹12.45 L / ₹1.20 Cr instead of full ₹12,45,000.00 figures.",
                    checked = settings.useCompactCurrency,
                    onCheckedChange = onCompactCurrencyChange,
                    testTag = "setting_compact_currency"
                )
            }

            // 1b. 11-Tab Excel Output Configuration Card
            LedgerCard {
                val excelCfg = settings.excelExportConfig
                val activeCols = excelCfg.activeLedgerColumnHeaders().size
                SectionHeading(
                    icon = Icons.Filled.TableChart,
                    title = "11-Tab Excel Output Configuration"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Customize preferred date formats, ledger columns, split Debit/Credit layout, live Excel formulas, and header freeze panes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = "Date: ${excelCfg.dateFormat.sampleDisplay} (${excelCfg.dateFormat.pattern}) • $activeCols Ledger Cols • ${if (excelCfg.includeFormulas) "=SUM() Live" else "Static Values"} • Top ${excelCfg.topTransactionsLimit}",
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onOpenExcelConfig,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_open_excel_config")
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Configure Excel Columns & Date Format")
                }
            }

            // 1c. Connectivity-Aware Online Hybrid Accuracy Boost
            LedgerCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SectionHeading(
                        icon = Icons.Filled.CloudSync,
                        title = "Online Accuracy Boost"
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (isDeviceOnline) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = if (isDeviceOnline) "INTERNET READY" else "OFFLINE",
                            fontFamily = JetBrainsMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDeviceOnline) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SettingSwitchRow(
                    title = "Live RBI IFSC & Bank Holiday Boost",
                    description = "When internet is active, verify account & counterparty IFSCs against RBI master data and sync state-specific Indian bank holidays. Falls back 100% offline automatically.",
                    checked = settings.onlineAccuracyBoostEnabled,
                    onCheckedChange = onOnlineBoostChange,
                    testTag = "setting_online_boost"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                SettingSwitchRow(
                    title = "Cloud Smart Layout & Narration Assist",
                    description = "When online, automatically recover non-standard bank PDF layouts and classify ambiguous 'Others' narrations & semantic self-transfers (zero API keys required).",
                    checked = settings.cloudAiAssistEnabled && settings.onlineAccuracyBoostEnabled,
                    onCheckedChange = onCloudAiAssistChange,
                    testTag = "setting_cloud_ai_assist"
                )
            }

            // 1d. In-App Auto-Updater (GitHub CI/CD Releases — Zero Browser)
            LedgerCard {
                SectionHeading(
                    icon = Icons.Filled.SystemUpdate,
                    title = "In-App Auto-Updater"
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Installed Version: $currentAppVersion • Downloads & installs verified APK updates directly inside BSA-YourView without opening any browser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.updateRepoOverride,
                    onValueChange = onUpdateRepoChange,
                    label = { Text("GitHub Release Repo (owner/repo)") },
                    placeholder = {
                        Text(
                            effectiveUpdateRepo.ifBlank { "Auto-injected by build-apk.yml (or enter owner/repo)" }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setting_update_repo_input")
                )
                Spacer(Modifier.height(10.dp))
                when (updateState) {
                    is AppUpdateUiState.UpdateAvailable -> {
                        Button(
                            onClick = onOpenUpdateDialog,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("settings_install_available_update")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Update Available: v${updateState.release.versionName} — Download In-App")
                        }
                    }
                    is AppUpdateUiState.ReadyToInstall -> {
                        Button(
                            onClick = onOpenUpdateDialog,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(Icons.Filled.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Install Downloaded Update v${updateState.release.versionName}")
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = onCheckForUpdate,
                            enabled = updateState !is AppUpdateUiState.Checking && updateState !is AppUpdateUiState.Downloading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("check_updates_button")
                        ) {
                            if (updateState is AppUpdateUiState.Checking) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Checking GitHub Releases...")
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Check for In-App Updates")
                            }
                        }
                    }
                }
                if (updateState is AppUpdateUiState.UpToDate) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Up to date: v${updateState.versionName} (Build ${updateState.versionCode})",
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                } else if (updateState is AppUpdateUiState.Error) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = updateState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 2. Forensic Gatekeeper & Underwriting Engine Rules
            LedgerCard {
                SectionHeading(
                    icon = Icons.Filled.Security,
                    title = "Forensic & Underwriting Engine"
                )
                Spacer(Modifier.height(12.dp))

                SettingSwitchRow(
                    title = "Strict Forensic Gatekeeper",
                    description = "Block analysis if an uploaded PDF is scanned/image-only or contains tampered editing metadata (Photoshop, iLovePDF, Print-to-PDF).",
                    checked = settings.strictForensicBlocking,
                    onCheckedChange = onStrictForensicChange,
                    testTag = "setting_strict_forensic"
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                SettingSwitchRow(
                    title = "Auto-Suppress Negative EOD for OD/CC",
                    description = "Automatically suppress Fraud Trigger #7 (Negative EOD Balance) when analyzing Overdraft or Cash Credit facilities.",
                    checked = settings.autoSuppressOdNegativeEod,
                    onCheckedChange = onAutoSuppressOdChange,
                    testTag = "setting_auto_suppress_od"
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Fuzzy Self-Transfer Match Sensitivity",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Token-set similarity threshold against Account Holder Name for tagging Transfer To/From Self.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${settings.fuzzySelfMatchThreshold}%",
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Slider(
                    value = settings.fuzzySelfMatchThreshold.toFloat(),
                    onValueChange = { onFuzzyThresholdChange(it.roundToInt()) },
                    valueRange = 70f..95f,
                    steps = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setting_fuzzy_slider")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("70% (Broad)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("82% (Balanced)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("95% (Strict)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 3. Offline Device Storage & Data Controls
            LedgerCard {
                SectionHeading(
                    icon = Icons.Filled.Storage,
                    title = "Offline Case Storage"
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "All parsed statements, fraud audits, and 11-tab Excel workbooks stay 100% on your device. Currently $savedCaseCount saved case(s) stored offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    enabled = savedCaseCount > 0,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (savedCaseCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("clear_all_cases_button")
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clear All Offline Saved Cases ($savedCaseCount)")
                }
            }

            // 4. Exclusive Brand Card (BSA-YourView / YourView / yourview.in)
            LedgerCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    com.example.bsa.ui.components.YvBrandLogoBadge(size = 48.dp, cornerRadius = 12.dp)
                    Column {
                        Text(
                            text = "BSA-YourView",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "YourView • yourview.in • in.bsa.yourview",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Exclusively built for YourView (yourview.in). Runs a 100% standalone, offline deterministic Indian Bank Statement Analysis engine — zero cloud uploads, zero external APIs, zero API keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("Clear Offline Case History?") },
            text = {
                Text("This will permanently remove all $savedCaseCount saved analysis reports stored locally on this device.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearAllCases()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SectionHeading(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text = title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
