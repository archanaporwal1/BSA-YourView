package com.example.bsa.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.bsa.data.SavedCaseSummary
import com.example.bsa.model.ForensicStatus
import com.example.bsa.ui.components.BsaFormatters
import com.example.bsa.ui.components.ForensicStatusPill
import com.example.bsa.ui.components.LedgerCard
import com.example.bsa.ui.components.YvBrandEndorsementFooter
import com.example.bsa.ui.components.YvBrandLogoBadge

@Composable
fun HistoryScreen(
    cases: List<SavedCaseSummary>,
    onOpenCase: (String) -> Unit,
    onDeleteCase: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (cases.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            LedgerCard(modifier = Modifier.widthIn(max = 460.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    YvBrandLogoBadge(size = 56.dp, cornerRadius = 14.dp)
                    Text(
                        text = stringResource(R.string.empty_history_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.empty_history_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    YvBrandEndorsementFooter()
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Offline Underwriting Cases (${cases.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(cases, key = { it.reportId }) { item ->
            val status = runCatching { ForensicStatus.valueOf(item.forensicStatus) }
                .getOrDefault(ForensicStatus.VERIFIED)

            LedgerCard(
                modifier = Modifier.clickable { onOpenCase(item.reportId) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${item.applicantId} • ${item.holderName}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.bankName} • ${item.accountNumber}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    ForensicStatusPill(status)
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${item.productType} (${item.facilityType}) • ${item.statementPeriod}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${item.transactionCount} txns • ${item.flaggedCount} flag(s) • ${BsaFormatters.formatTimestamp(item.generatedAtMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onOpenCase(item.reportId) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = "Open case report",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { onDeleteCase(item.reportId) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = stringResource(R.string.cd_delete_case),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
        item {
            YvBrandEndorsementFooter()
        }
    }
}
