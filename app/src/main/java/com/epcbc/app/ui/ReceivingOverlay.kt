package com.epcbc.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.epcbc.net.ReceivingApi

/**
 * Хүлээн авалтын overlay — Dialog БИШ, in-tree Surface (C5-ийн PTT товч
 * Activity-д хүрсэн хэвээр байхын тулд — SkuDetailOverlay-тай ижил хэв маяг).
 * Ажил сонгоогүй үед: нээлттэй ажлын жагсаалт. Сонгосон үед: явц + илгээх.
 */
@Composable
fun ReceivingOverlay(
    receipts: List<ReceivingApi.Receipt>,
    receiptsLoading: Boolean,
    activeReceipt: ReceivingApi.Receipt?,
    progress: List<ReceivingApi.ProgressItem>,
    progressLoading: Boolean,
    submitBusy: Boolean,
    scannedCount: Int,
    message: String?,
    error: String?,
    onRefreshReceipts: () -> Unit,
    onSelect: (ReceivingApi.Receipt?) -> Unit,
    onRefreshProgress: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Back товч: ажил сонгосон бол жагсаалт руу, эс бөгөөс overlay хаана —
    // өмнө нь бүтэн аппыг хаадаг байсан (2026-08-03 илэрсэн).
    BackHandler(onBack = { if (activeReceipt != null) onSelect(null) else onDismiss() })

    // Surface — Modifier.background() БИШ: background() нь зөвхөн будна,
    // LocalContentColor-ыг шинэчилдэггүй тул өнгө нь ил зааагүй бүх Text
    // үлдэгдэл ХАР өнгөөр гарч, dark theme дээр огт уншигдахгүй болдог.
    // Surface нь дэвсгэрт тохирсон контентын өнгийг (onBackground) өгнө.
    // Dialog биш, in-tree хэвээр — C5-ийн trigger товч Activity-д хүрнэ.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // statusBarsPadding — толгой нүүрний толгойтой ижил түвшинд (2026-08-03).
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            // Толгой
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (activeReceipt == null) "Хүлээн авалт — нээлттэй ажлууд"
                    else "Ажил: ${activeReceipt.jobNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Хаах") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            if (activeReceipt == null) {
                // ---------- Ажлын жагсаалт ----------
                // Салбарын шүүлт — >1 салбар үед л (тооллоготой ижил хэв маяг).
                var branchFilter by remember { mutableStateOf<String?>(null) }
                var branchMenuOpen by remember { mutableStateOf(false) }
                val branches = receipts.map { it.branchName }.distinct()
                val shown = if (branchFilter == null) receipts
                            else receipts.filter { it.branchName == branchFilter }

                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshReceipts, enabled = !receiptsLoading) {
                        Text(if (receiptsLoading) "Татаж байна…" else "↻ Сэргээх")
                    }
                    if (branches.size > 1) {
                        Box {
                            OutlinedButton(onClick = { branchMenuOpen = true }) {
                                Text("Салбар: ${branchFilter ?: "Бүгд"} ▾")
                            }
                            DropdownMenu(expanded = branchMenuOpen, onDismissRequest = { branchMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("Бүх салбар") },
                                    onClick = { branchFilter = null; branchMenuOpen = false })
                                branches.forEach { b ->
                                    DropdownMenuItem(text = { Text(b) },
                                        onClick = { branchFilter = b; branchMenuOpen = false })
                                }
                            }
                        }
                    }
                }
                if (receipts.isEmpty() && !receiptsLoading) {
                    Text(
                        "Нээлттэй хүлээн авалтын ажил алга. Вебээс Excel-ээр ажил үүсгэнэ.",
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { r ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(r) },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row {
                                    Text(r.jobNumber, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(r.jobs?.arrivalDate ?: "", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    listOfNotNull(r.branchName, r.jobs?.supplier).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                r.jobs?.note?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            } else {
                // ---------- Сонгосон ажлын явц + илгээх ----------
                Row(
                    Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onSubmit, enabled = !submitBusy && scannedCount > 0) {
                        Text(if (submitBusy) "Илгээж байна…" else "⬆ Илгээх ($scannedCount)")
                    }
                    OutlinedButton(onClick = onRefreshProgress, enabled = !progressLoading) {
                        Text("↻ Явц")
                    }
                    OutlinedButton(onClick = { onSelect(null) }) {
                        Text("← Ажлууд")
                    }
                }
                Text(
                    "Скан хийгээд \"Илгээх\" дарна — давхардсан илгээлт аюулгүй (сервер алгасна).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))

                // Явцын хүснэгт: Бараа | Хүлээгдэж буй | Уншсан
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("БАРАА", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("ХҮЛЭЭГДЭЖ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(80.dp))
                    Text("УНШСАН", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                }
                HorizontalDivider()
                if (progressLoading && progress.isEmpty()) {
                    Text("Татаж байна…", modifier = Modifier.padding(top = 12.dp))
                }
                LazyColumn {
                    items(progress, key = { it.productId }) { p ->
                        val done = p.scanned >= p.expected
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(p.name, modifier = Modifier.weight(1f), maxLines = 2)
                            Text("${p.expected}", modifier = Modifier.width(80.dp))
                            Text(
                                "${p.scanned}" + if (p.generated > 0) " (+${p.generated})" else "",
                                modifier = Modifier.width(64.dp),
                                color = if (done) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onBackground,
                                fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
