package com.example.bsa.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bsa.engine.*
import com.example.ui.theme.JetBrainsMono
import java.util.Locale
import kotlin.math.abs

/**
 * 1. Internal Credits, True Business Turnover Reconciliation & Contra Rotation Card
 */
@Composable
fun InternalCreditsContraCard(
    data: InternalTransferDeepAnalysis,
    useCompactCurrency: Boolean,
    modifier: Modifier = Modifier
) {
    var showContraDetails by remember(data) { mutableStateOf(data.contraRotationPairs.isNotEmpty()) }

    val inflationColor = when {
        data.creditInflationPercent >= 25.0 -> MaterialTheme.colorScheme.error
        data.creditInflationPercent >= 12.0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    LedgerCard(modifier = modifier.testTag("internal_credits_contra_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Internal Credits & Contra Audit",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Excludes self-transfers & reversals to isolate True BTO",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = inflationColor.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "${String.format(Locale.ROOT, "%.1f%%", data.creditInflationPercent)} Infl.",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = inflationColor,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 2x2 Reconciliation Grid
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeepMetricBox(
                    label = "Gross Credits",
                    value = BsaFormatters.formatCurrency(data.grossTotalCredits, useCompactCurrency),
                    subtext = "All statement inflows",
                    accent = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                DeepMetricBox(
                    label = "Net True BTO",
                    value = BsaFormatters.formatCurrency(data.netTrueBusinessCredits, useCompactCurrency),
                    subtext = "Ann: ${BsaFormatters.formatCurrency(data.annualizedTrueBusinessCredits, useCompactCurrency)}",
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeepMetricBox(
                    label = "Internal Credits",
                    value = BsaFormatters.formatCurrency(data.internalSelfCredits, useCompactCurrency),
                    subtext = "${data.internalSelfCreditCount} self inflow(s)",
                    accent = inflationColor,
                    modifier = Modifier.weight(1f)
                )
                DeepMetricBox(
                    label = "Internal Debits",
                    value = BsaFormatters.formatCurrency(data.internalSelfDebits, useCompactCurrency),
                    subtext = "${data.internalSelfDebitCount} self outflow(s)",
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Contra / Circular Rotation Pairs Section
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showContraDetails = !showContraDetails }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Contra / Circular Rotation Pairs (${data.contraRotationPairs.size})",
                style = MaterialTheme.typography.labelLarge,
                color = if (data.contraRotationPairs.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
            )
            Icon(
                imageVector = if (showContraDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = "Toggle contra pairs",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = showContraDetails) {
            if (data.contraRotationPairs.isEmpty()) {
                Text(
                    text = "No same-amount (±3 days, ≥ ₹10,000) contra or circular rotation pairs detected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    data.contraRotationPairs.take(8).forEach { pair ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(9.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${pair.creditDate} ↔ ${pair.debitDate}",
                                        fontFamily = JetBrainsMono,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = BsaFormatters.formatCurrency(pair.amount, useCompactCurrency),
                                        fontFamily = JetBrainsMono,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                Text(
                                    text = "${pair.creditCounterparty} → ${pair.debitCounterparty}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pair.rotationNote,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 2. Bounces, Returns & EMI Servicing Cushion Deep-Dive Card
 */
@Composable
fun BouncesEmiDeepDiveCard(
    data: BounceEmiDeepAnalysis,
    useCompactCurrency: Boolean,
    modifier: Modifier = Modifier
) {
    var showBounceLog by remember(data) { mutableStateOf(data.bounceEvents.isNotEmpty()) }

    val ratingColor = when {
        data.totalBounceCount >= 3 -> MaterialTheme.colorScheme.error
        data.totalBounceCount >= 1 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    LedgerCard(modifier = modifier.testTag("bounces_emi_deep_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.GppMaybe,
                contentDescription = null,
                tint = ratingColor,
                modifier = Modifier.size(21.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Bounces, Returns & EMI Health",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Bounce Rate ${BsaFormatters.formatPercent(data.bounceRatePercent)} • Penalties ${BsaFormatters.formatCurrency(data.totalBounceAndPenaltyAmount, useCompactCurrency)}",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ratingColor.copy(alpha = 0.16f)
            ) {
                Text(
                    text = data.emiTrackRating,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ratingColor,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 2x2 Bounce Classification Breakdown
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeepMetricBox(
                    label = "Inward Chq Bounce",
                    value = "${data.inwardChequeBounces}",
                    subtext = "Cheque dishonour",
                    accent = if (data.inwardChequeBounces > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                DeepMetricBox(
                    label = "NACH / ECS Bounce",
                    value = "${data.nachEcsBounces}",
                    subtext = "EMI / Mandate fail",
                    accent = if (data.nachEcsBounces > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeepMetricBox(
                    label = "Outward Chq Return",
                    value = "${data.outwardChequeReturns}",
                    subtext = "Buyer chq return",
                    accent = if (data.outwardChequeReturns > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                DeepMetricBox(
                    label = "Penal / AMB Charges",
                    value = "${data.penalChargeEvents}",
                    subtext = "Fee / Penalty hits",
                    accent = if (data.penalChargeEvents > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // EMI Servicing Health Rows
        if (data.emiHealthRecords.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "EMI Repayment Cushion (${data.emiHealthRecords.size} Detected Loan${if (data.emiHealthRecords.size == 1) "" else "s"})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                data.emiHealthRecords.forEach { rec ->
                    val statusTint = when {
                        rec.relatedBounceCount > 0 -> MaterialTheme.colorScheme.error
                        rec.repaymentStatus.contains("Watchlist") -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.secondary
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(9.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = rec.lenderHint,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${BsaFormatters.formatCurrency(rec.monthlyEmi, useCompactCurrency)}/mo",
                                    fontFamily = JetBrainsMono,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${rec.installmentsObserved}x paid • Post-EMI Cushion: ${BsaFormatters.formatCurrency(rec.avgBalanceCushionAfterEmi, useCompactCurrency)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = rec.repaymentStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = statusTint,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bounce Event Register
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showBounceLog = !showBounceLog }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Bounce & Penalty Register (${data.bounceEvents.size})",
                style = MaterialTheme.typography.labelLarge
            )
            Icon(
                imageVector = if (showBounceLog) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = "Toggle bounce register",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = showBounceLog) {
            if (data.bounceEvents.isEmpty()) {
                Text(
                    text = "Zero cheque bounces, NACH returns, or penal charges found in this period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    data.bounceEvents.take(10).forEach { ev ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(9.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${ev.displayDate} • ${ev.bounceType.label}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = BsaFormatters.formatCurrency(ev.amount, useCompactCurrency),
                                        fontFamily = JetBrainsMono,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                    text = ev.description,
                                    fontFamily = JetBrainsMono,
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 3. Payment Channel Mix & High-Value Cash (Sec 269ST) Card
 */
@Composable
fun ChannelAndCashMixCard(
    data: ChannelAndCashDeepAnalysis,
    useCompactCurrency: Boolean,
    modifier: Modifier = Modifier
) {
    LedgerCard(modifier = modifier.testTag("channel_cash_mix_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.AccountTree,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Payment Channels & Cash Mix",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "NEFT, RTGS, IMPS, UPI, Cheque, NACH & Branch/ATM Cash",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = "${String.format(Locale.ROOT, "%.0f%%", data.digitalBankingRatioPct)} Digital",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Channel Breakdown Bars
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            data.channels.take(7).forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${row.channelName} (${row.creditCount + row.debitCount})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${BsaFormatters.formatCurrency(row.totalVolume, useCompactCurrency)} (${String.format(Locale.ROOT, "%.1f%%", row.volumeSharePct)})",
                            fontFamily = JetBrainsMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (row.volumeSharePct / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (row.channelName.startsWith("Cash")) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        // Sec 269ST & High-Value Cash Alerts
        if (data.highValueCashTxns.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (data.sec269StAlertCount > 0) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (data.sec269StAlertCount > 0) {
                            "⚠ ${data.sec269StAlertCount} Sec 269ST Alert(s) (Cash Deposit ≥ ₹2L) • ${data.highValueCashTxns.size} High-Value Cash Txns"
                        } else {
                            "${data.highValueCashTxns.size} High-Value Cash Transaction(s) ≥ ₹50,000"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (data.sec269StAlertCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    )
                    data.highValueCashTxns.take(4).forEach { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${t.displayDate} • ${if (t.amount > 0) "Cash Dep" else "Cash Wdl"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = BsaFormatters.formatCurrency(abs(t.amount), useCompactCurrency),
                                fontFamily = JetBrainsMono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 4. Counterparty Concentration & Statutory Compliance Card
 */
@Composable
fun ConcentrationAndStatutoryCard(
    concentration: CounterpartyConcentrationAnalysis,
    statutory: StatutoryComplianceSnapshot,
    useCompactCurrency: Boolean,
    modifier: Modifier = Modifier
) {
    val riskColor = when {
        concentration.top3CustomerSharePct >= 75.0 -> MaterialTheme.colorScheme.error
        concentration.top3CustomerSharePct >= 50.0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    LedgerCard(modifier = modifier.testTag("concentration_statutory_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.DonutLarge,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Concentration & Statutory Audit",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Buyer/Supplier dependency & tax/operating signals",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = riskColor.copy(alpha = 0.16f)
            ) {
                Text(
                    text = concentration.concentrationRiskLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = riskColor,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeepMetricBox(
                label = "Top-3 Buyers Share",
                value = BsaFormatters.formatPercent(concentration.top3CustomerSharePct),
                subtext = "Top-5: ${BsaFormatters.formatPercent(concentration.top5CustomerSharePct)}",
                accent = riskColor,
                modifier = Modifier.weight(1f)
            )
            DeepMetricBox(
                label = "Top-3 Suppliers",
                value = BsaFormatters.formatPercent(concentration.top3SupplierSharePct),
                subtext = "Top-5: ${BsaFormatters.formatPercent(concentration.top5SupplierSharePct)}",
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }

        if (concentration.topCustomers.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Top Customers (Non-Self Inflows)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(4.dp))
            concentration.topCustomers.take(4).forEach { buyer ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${buyer.partyName} (${buyer.txnCount}x)",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${BsaFormatters.formatCurrency(buyer.totalAmount, useCompactCurrency)} • ${String.format(Locale.ROOT, "%.0f%%", buyer.sharePercent)}",
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

        Text("Statutory & Operating Signals", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatutorySignalRow("GST Challan / CBIC", statutory.gstPaymentCount, statutory.gstPaymentTotal, useCompactCurrency)
            StatutorySignalRow("Income Tax / TDS", statutory.incomeTaxTdsCount, statutory.incomeTaxTdsTotal, useCompactCurrency)
            StatutorySignalRow("Salary / Payroll", statutory.salaryPayrollCount, statutory.salaryPayrollTotal, useCompactCurrency)
            StatutorySignalRow("Premises / Office Rent", statutory.rentOutflowCount, statutory.rentOutflowTotal, useCompactCurrency)
            StatutorySignalRow("Power & Utilities", statutory.utilityBillCount, statutory.utilityBillTotal, useCompactCurrency)
        }
    }
}

@Composable
private fun StatutorySignalRow(label: String, count: Int, total: Double, useCompactCurrency: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (count > 0) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (count > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (count > 0) "$count× • ${BsaFormatters.formatCurrency(total, useCompactCurrency)}" else "Not observed",
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            fontWeight = if (count > 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (count > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeepMetricBox(
    label: String,
    value: String,
    subtext: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
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
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
