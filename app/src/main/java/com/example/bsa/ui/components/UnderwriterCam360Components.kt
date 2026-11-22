package com.example.bsa.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bsa.engine.CreditIntelligenceSummary
import com.example.bsa.engine.DeepUnderwritingSummary
import com.example.bsa.model.*
import com.example.ui.theme.JetBrainsMono
import java.util.Locale

/**
 * One-Stop Beyond-BSA Credit Appraisal Memo (CAM 360°) Workbench:
 * Unifies Credit Bureau (CIBIL/CRIF), GST & ITR Financial Ratios (DSCR, TOL/TNW, Current Ratio, DP),
 * Collateral LTV Calculator, KYC/FI/PD Verification Checklist, Auto Policy Deviations,
 * and Final Sanction Recommendation — all persisted per case and exported to Tab 18 of the Excel workbook.
 */
@Composable
fun UnderwriterCam360Workbench(
    report: AnalysisReport,
    intel: CreditIntelligenceSummary,
    deep: DeepUnderwritingSummary,
    camProfile: UnderwriterCamProfile,
    useCompactCurrency: Boolean,
    onUpdateCam: ((UnderwriterCamProfile) -> UnderwriterCamProfile) -> Unit,
    onCopyBrief: () -> Unit,
    onExportExcel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val eval = remember(report, intel, deep, camProfile) {
        UnderwriterCamEvaluator.evaluate(report, intel, deep, camProfile)
    }

    val scoreColor = when {
        eval.compositeCamScore >= 75 -> MaterialTheme.colorScheme.secondary
        eval.compositeCamScore >= 55 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Composite 360° CAM Score & Policy Deviations Banner
        LedgerCard(modifier = Modifier.testTag("cam_360_score_card")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = scoreColor,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Underwriter 360° CAM Score",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Bank (50%) + Bureau (25%) + Financials/LTV/PD (25%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = scoreColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = "${eval.compositeCamScore} / 100",
                        fontFamily = JetBrainsMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (eval.compositeCamScore / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = scoreColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamRatioTile(
                    label = "Consolidated FOIR",
                    value = BsaFormatters.formatPercent(eval.consolidatedFoirPct),
                    norm = "Bank+Bureau EMI",
                    color = if (eval.consolidatedFoirPct > 60.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                CamRatioTile(
                    label = "Policy Deviations",
                    value = "${eval.deviations.count { it.severity != "Info" }}",
                    norm = "${eval.checklistCompletedCount}/${eval.checklistTotalCount} checks",
                    color = if (eval.deviations.any { it.severity == "Critical" }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }

            if (eval.deviations.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    eval.deviations.forEach { dev ->
                        val devColor = when (dev.severity) {
                            "Critical" -> MaterialTheme.colorScheme.error
                            "Moderate" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = devColor.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = dev.category.uppercase(Locale.ROOT),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = devColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = dev.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 2. Credit Bureau Section (CIBIL / CRIF / Experian)
        LedgerCard {
            CamSectionHeader(Icons.Filled.CreditScore, "1. Credit Bureau (CIBIL / CRIF / Experian)")
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("CIBIL", "CRIF HighMark", "Experian", "Equifax").forEach { bureau ->
                    FilterChip(
                        selected = camProfile.bureauName == bureau,
                        onClick = { onUpdateCam { it.copy(bureauName = bureau) } },
                        label = { Text(bureau, maxLines = 1, softWrap = false) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamIntField(
                    label = "${camProfile.bureauName} Score",
                    value = camProfile.bureauScore,
                    onValueChange = { v -> onUpdateCam { it.copy(bureauScore = v.coerceIn(0, 900)) } },
                    modifier = Modifier.weight(1f)
                )
                CamIntField(
                    label = "Active Loans",
                    value = camProfile.activeTradelines,
                    onValueChange = { v -> onUpdateCam { it.copy(activeTradelines = v.coerceIn(0, 99)) } },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamIntField(
                    label = "30+ DPD (12m)",
                    value = camProfile.dpd30PlusCount12m,
                    onValueChange = { v -> onUpdateCam { it.copy(dpd30PlusCount12m = v.coerceIn(0, 99)) } },
                    modifier = Modifier.weight(1f)
                )
                CamIntField(
                    label = "90+ DPD",
                    value = camProfile.dpd90PlusCount,
                    onValueChange = { v -> onUpdateCam { it.copy(dpd90PlusCount = v.coerceIn(0, 99)) } },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamIntField(
                    label = "Enquiries (90d)",
                    value = camProfile.recentEnquiries90d,
                    onValueChange = { v -> onUpdateCam { it.copy(recentEnquiries90d = v.coerceIn(0, 99)) } },
                    modifier = Modifier.weight(1f)
                )
                CamCurrencyField(
                    label = "Overdue Amt (₹)",
                    value = camProfile.overdueAmount,
                    onValueChange = { v -> onUpdateCam { it.copy(overdueAmount = v) } },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            CamCurrencyField(
                label = "Other Bank Monthly EMI (₹)",
                value = camProfile.additionalBureauMonthlyEmi,
                onValueChange = { v -> onUpdateCam { it.copy(additionalBureauMonthlyEmi = v) } },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Written-Off / Settled / Suit-Filed Track",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = camProfile.writtenOffOrSettled,
                    onCheckedChange = { checked -> onUpdateCam { it.copy(writtenOffOrSettled = checked) } }
                )
            }
        }

        // 3. GST Reconciliation & ITR Financial Ratios
        LedgerCard {
            CamSectionHeader(Icons.Filled.Analytics, "2. GST & ITR Financial Ratios (Annual ₹)")
            Spacer(Modifier.height(8.dp))

            // Live Calculated Ratio Tiles
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CamRatioTile(
                        label = "Bank / GST Routing",
                        value = eval.bankToGstRoutingPct?.let { BsaFormatters.formatPercent(it) } ?: "—",
                        norm = "Norm 75–125%",
                        color = when {
                            eval.bankToGstRoutingPct == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            eval.bankToGstRoutingPct in 75.0..125.0 -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.tertiary
                        },
                        modifier = Modifier.weight(1f)
                    )
                    CamRatioTile(
                        label = "DSCR",
                        value = eval.dscrRatio?.let { String.format(Locale.ROOT, "%.2fx", it) } ?: "—",
                        norm = "Norm ≥ 1.25x",
                        color = when {
                            eval.dscrRatio == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            eval.dscrRatio >= 1.25 -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CamRatioTile(
                        label = "TOL / TNW Leverage",
                        value = eval.leverageTolTnwRatio?.let { String.format(Locale.ROOT, "%.2fx", it) } ?: "—",
                        norm = "Norm ≤ 3.00x",
                        color = when {
                            eval.leverageTolTnwRatio == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            eval.leverageTolTnwRatio <= 3.0 -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.tertiary
                        },
                        modifier = Modifier.weight(1f)
                    )
                    CamRatioTile(
                        label = "Current Ratio",
                        value = eval.currentRatio?.let { String.format(Locale.ROOT, "%.2fx", it) } ?: "—",
                        norm = "Tandon ≥ 1.33x",
                        color = when {
                            eval.currentRatio == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            eval.currentRatio >= 1.33 -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.tertiary
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                eval.computedDrawingPower?.let { dp ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Computed WC Drawing Power (75% Stock+Debtors − Cr)",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = BsaFormatters.formatCurrency(dp, useCompactCurrency),
                                fontFamily = JetBrainsMono,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamCurrencyField("GST Sales (₹)", camProfile.annualGstTurnover, { v -> onUpdateCam { it.copy(annualGstTurnover = v) } }, Modifier.weight(1f))
                CamCurrencyField("ITR Turnover (₹)", camProfile.itrGrossTurnover, { v -> onUpdateCam { it.copy(itrGrossTurnover = v) } }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamCurrencyField("PAT Profit (₹)", camProfile.itrNetProfitPat, { v -> onUpdateCam { it.copy(itrNetProfitPat = v) } }, Modifier.weight(1f))
                CamCurrencyField("Depreciation (₹)", camProfile.itrDepreciation, { v -> onUpdateCam { it.copy(itrDepreciation = v) } }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamCurrencyField("Interest Paid (₹)", camProfile.itrInterestPaid, { v -> onUpdateCam { it.copy(itrInterestPaid = v) } }, Modifier.weight(1f))
                CamCurrencyField("Net Worth TNW (₹)", camProfile.tangibleNetWorth, { v -> onUpdateCam { it.copy(tangibleNetWorth = v) } }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamCurrencyField("Total Liab TOL (₹)", camProfile.totalOutsideLiabilities, { v -> onUpdateCam { it.copy(totalOutsideLiabilities = v) } }, Modifier.weight(1f))
                CamCurrencyField("Current Assets (₹)", camProfile.currentAssets, { v -> onUpdateCam { it.copy(currentAssets = v) } }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamCurrencyField("Current Liab (₹)", camProfile.currentLiabilities, { v -> onUpdateCam { it.copy(currentLiabilities = v) } }, Modifier.weight(1f))
                CamCurrencyField("Stock Inventory (₹)", camProfile.stockInventoryValue, { v -> onUpdateCam { it.copy(stockInventoryValue = v) } }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamCurrencyField("Debtors <90d (₹)", camProfile.debtorsUnder90Days, { v -> onUpdateCam { it.copy(debtorsUnder90Days = v) } }, Modifier.weight(1f))
                CamCurrencyField("Trade Creditors (₹)", camProfile.tradeCreditors, { v -> onUpdateCam { it.copy(tradeCreditors = v) } }, Modifier.weight(1f))
            }
        }

        // 4. Collateral Security & LTV Calculator
        LedgerCard {
            CamSectionHeader(Icons.Filled.HomeWork, "3. Collateral Security & LTV Check")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CollateralType.entries.forEach { colType ->
                    FilterChip(
                        selected = camProfile.collateralType == colType,
                        onClick = { onUpdateCam { it.copy(collateralType = colType) } },
                        label = { Text(colType.label, maxLines = 1, softWrap = false) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamCurrencyField(
                    label = "Proposed Sanction (₹)",
                    value = camProfile.proposedSanctionAmount,
                    onValueChange = { v -> onUpdateCam { it.copy(proposedSanctionAmount = v) } },
                    modifier = Modifier.weight(1f)
                )
                if (camProfile.collateralType != CollateralType.UNSECURED) {
                    CamCurrencyField(
                        label = "Asset Value (₹)",
                        value = camProfile.collateralMarketValue,
                        onValueChange = { v -> onUpdateCam { it.copy(collateralMarketValue = v) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            eval.ltvPercent?.let { ltv ->
                val norm = camProfile.collateralType.maxNormLtvPct
                val ltvBreach = norm > 0.0 && ltv > norm
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Computed LTV (Norm ≤ ${norm.toInt()}%)", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = BsaFormatters.formatPercent(ltv),
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        color = if (ltvBreach) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (ltv / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (ltvBreach) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        // 5. KYC, FI, PD & Legal Verification Checklist
        LedgerCard {
            CamSectionHeader(Icons.Filled.Checklist, "4. KYC, FI, PD & Legal Checklist (${eval.checklistCompletedCount}/${eval.checklistTotalCount})")
            Spacer(Modifier.height(6.dp))
            CamCheckRow("KYC / PAN / Aadhaar Verified", camProfile.kycPanAadhaarVerified) { v -> onUpdateCam { it.copy(kycPanAadhaarVerified = v) } }
            CamCheckRow("GST Active & Filing Regular", camProfile.gstStatusActiveVerified) { v -> onUpdateCam { it.copy(gstStatusActiveVerified = v) } }
            CamCheckRow("Residence & Office FI Cleared", camProfile.residenceOfficeFiCleared) { v -> onUpdateCam { it.copy(residenceOfficeFiCleared = v) } }
            CamCheckRow("Credit Officer Personal Discussion (PD)", camProfile.personalDiscussionDone) { v -> onUpdateCam { it.copy(personalDiscussionDone = v) } }
            CamCheckRow("Legal & Technical Valuation Cleared", camProfile.legalTechnicalCleared) { v -> onUpdateCam { it.copy(legalTechnicalCleared = v) } }
            CamCheckRow("Positive Trade / Market Reference", camProfile.tradeReferencePositive) { v -> onUpdateCam { it.copy(tradeReferencePositive = v) } }
        }

        // 6. Final Underwriter Decision & Remarks
        LedgerCard {
            CamSectionHeader(Icons.Filled.Gavel, "5. Final Credit Officer Decision & Memo")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CamDecisionStatus.entries.forEach { st ->
                    FilterChip(
                        selected = camProfile.decisionStatus == st,
                        onClick = { onUpdateCam { it.copy(decisionStatus = st) } },
                        label = { Text(st.shortLabel, maxLines = 1, softWrap = false) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = camProfile.underwriterRemarks,
                onValueChange = { txt -> onUpdateCam { it.copy(underwriterRemarks = txt) } },
                label = { Text("Underwriter Sanction Conditions / Remarks") },
                placeholder = { Text("e.g., Subject to NOC of existing NBFC loan, GST GSTR-3B verification, and positive RCU...") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 360.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        YourViewSecondaryButton(
                            text = "Copy 360° Brief",
                            icon = Icons.Filled.ContentCopy,
                            onClick = onCopyBrief,
                            modifier = Modifier.weight(1f)
                        )
                        YourViewAccentButton(
                            text = "Export 18-Tab",
                            icon = Icons.Filled.TableChart,
                            onClick = onExportExcel,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        YourViewSecondaryButton(
                            text = "Copy 360° Brief",
                            icon = Icons.Filled.ContentCopy,
                            onClick = onCopyBrief,
                            modifier = Modifier.fillMaxWidth()
                        )
                        YourViewAccentButton(
                            text = "Export 18-Tab Excel",
                            icon = Icons.Filled.TableChart,
                            onClick = onExportExcel,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CamSectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CamRatioTile(
    label: String,
    value: String,
    norm: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontFamily = JetBrainsMono, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
            Text(norm, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CamIntField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(if (value == 0) "" else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(4)
            text = digits
            onValueChange(digits.toIntOrNull() ?: 0)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun CamCurrencyField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) {
        mutableStateOf(if (value == 0.0) "" else String.format(Locale.ROOT, "%.0f", value))
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || it == '.' }.take(12)
            text = cleaned
            onValueChange(cleaned.toDoubleOrNull() ?: 0.0)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

@Composable
private fun CamCheckRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable { onToggle(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
