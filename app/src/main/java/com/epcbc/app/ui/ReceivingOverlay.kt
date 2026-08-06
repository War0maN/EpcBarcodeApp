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
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Upload
import com.epcbc.net.ReceivingApi

/**
 * Хүлээн авалтын overlay — Dialog БИШ, in-tree Surface (C5-ийн PTT товч
 * Activity-д хүрсэн хэвээр байхын тулд). Навигаци Тооллоготой ижил:
 * 2 карттай цэс (Нээлттэй ажлууд / Түүх), толгойд ГАНЦ товч — цэсэнд
 * "Хаах", дэд дэлгэцэд "Буцах" (нэг алхам).
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
    closed: List<ReceivingApi.Receipt>,
    closedLoading: Boolean,
    history: ReceivingApi.Receipt?,
    historyProgress: List<ReceivingApi.ProgressItem>,
    historyLoading: Boolean,
    onRefreshReceipts: () -> Unit,
    onSelect: (ReceivingApi.Receipt?) -> Unit,
    onRefreshProgress: () -> Unit,
    onSubmit: () -> Unit,
    onRefreshClosed: () -> Unit,
    onSelectHistory: (ReceivingApi.Receipt?) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("menu") }

    val goBack: () -> Unit = {
        when {
            activeReceipt != null -> onSelect(null)
            history != null -> onSelectHistory(null)
            mode != "menu" -> mode = "menu"
            else -> onDismiss()
        }
    }
    BackHandler(onBack = goBack)

    val atMenu = activeReceipt == null && history == null && mode == "menu"

    // Surface — Modifier.background() БИШ: background() нь зөвхөн будна,
    // LocalContentColor-ыг шинэчилдэггүй тул dark theme дээр текст уншигдахгүй
    // болдог. Dialog биш, in-tree хэвээр — C5-ийн trigger товч Activity-д хүрнэ.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            val title = when {
                activeReceipt != null -> "Ажил: ${activeReceipt.jobNumber}"
                history != null -> "${history.jobNumber} · ${history.branchName}"
                mode == "open" -> "Нээлттэй ажлууд"
                mode == "history" -> "Хүлээн авалтын түүх"
                else -> "Хүлээн авалт"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (atMenu) {
                    TextButton(onClick = onDismiss) { Text("Хаах") }
                } else {
                    TextButton(onClick = goBack) { Text("Буцах") }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            when {
                // ---------- Сонгосон ажлын явц + илгээх ----------
                activeReceipt != null -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onSubmit, enabled = !submitBusy && scannedCount > 0) {
                            if (!submitBusy) {
                                Icon(Lucide.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (submitBusy) "Илгээж байна…" else "Илгээх ($scannedCount)")
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onRefreshProgress, enabled = !progressLoading) { Icon(Lucide.RefreshCw, contentDescription = "Шинэчлэх", modifier = Modifier.size(18.dp)) }
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

                // ---------- Түүхийн дэлгэрэнгүй (хөлдсөн явц, зөвхөн харах) ----------
                history != null -> {
                    if (historyLoading) {
                        Text("Түүх татаж байна…", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        val totalExpected = historyProgress.sumOf { it.expected }
                        val totalScanned = historyProgress.sumOf { it.scanned }
                        val totalGenerated = historyProgress.sumOf { it.generated }
                        Text(
                            listOfNotNull(
                                history.jobs?.arrivalDate,
                                history.jobs?.supplier,
                                history.jobs?.note,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            StStat("Уншсан", "$totalScanned / $totalExpected",
                                if (totalScanned >= totalExpected) Color(0xFF059669)
                                else Color(0xFFD97706))
                            StStat("Дутуу", "${(totalExpected - totalScanned - totalGenerated).coerceAtLeast(0)}",
                                Color(0xFFDC2626))
                            StStat("Үүсгэсэн", "$totalGenerated", MaterialTheme.colorScheme.onBackground)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("БАРАА", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text("ХҮЛЭЭГДЭЖ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(80.dp))
                            Text("УНШСАН", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                        }
                        HorizontalDivider()
                        LazyColumn {
                            items(historyProgress, key = { it.productId }) { p ->
                                val done = p.scanned >= p.expected
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    Text(p.name, modifier = Modifier.weight(1f), maxLines = 2)
                                    Text("${p.expected}", modifier = Modifier.width(80.dp))
                                    Text(
                                        "${p.scanned}" + if (p.generated > 0) " (+${p.generated})" else "",
                                        modifier = Modifier.width(64.dp),
                                        color = if (done) Color(0xFF059669) else Color(0xFFDC2626),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }

                // ---------- Цэс ----------
                mode == "menu" -> {
                    Spacer(Modifier.height(4.dp))
                    MenuCard(Lucide.Package, "Нээлттэй ажлууд", "Ирсэн ачааг уншиж бүртгэнэ") {
                        mode = "open"
                        onRefreshReceipts()
                    }
                    MenuCard(Lucide.History, "Түүх", "Хаагдсан ажлын дүн — уншсан/дутуу") {
                        mode = "history"
                        onRefreshClosed()
                    }
                }

                // ---------- Нээлттэй ажлын жагсаалт ----------
                mode == "open" -> {
                    // Салбарын шүүлт — >1 салбар үед л (тооллоготой ижил хэв маяг).
                    var branchFilter by remember { mutableStateOf<String?>(null) }
                    var branchMenuOpen by remember { mutableStateOf(false) }
                    val branches = receipts.map { it.branchName }.distinct()
                    val shown = if (branchFilter == null) receipts
                                else receipts.filter { it.branchName == branchFilter }

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (branches.size > 1) {
                            Box {
                                OutlinedButton(onClick = { branchMenuOpen = true }) {
                                    Text("Салбар: ${branchFilter ?: "Бүгд"}")
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Lucide.ChevronDown, contentDescription = null, modifier = Modifier.size(14.dp))
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
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onRefreshReceipts, enabled = !receiptsLoading) { Icon(Lucide.RefreshCw, contentDescription = "Шинэчлэх", modifier = Modifier.size(18.dp)) }
                    }
                    if (receipts.isEmpty() && !receiptsLoading) {
                        Text(
                            "Нээлттэй хүлээн авалтын ажил алга. Вебээс Excel-ээр ажил үүсгэнэ.",
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.id }) { r ->
                            RcvCard(r) { onSelect(r) }
                        }
                    }
                }

                // ---------- Түүх (жагсаалт) ----------
                else -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onRefreshClosed, enabled = !closedLoading) { Icon(Lucide.RefreshCw, contentDescription = "Шинэчлэх", modifier = Modifier.size(18.dp)) }
                    }
                    if (closed.isEmpty() && !closedLoading) {
                        Text("Хаагдсан ажил алга.", modifier = Modifier.padding(top = 12.dp))
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(closed, key = { it.id }) { r ->
                            RcvCard(r) { onSelectHistory(r) }
                        }
                    }
                }
            }
        }
    }
}

/** Хүлээн авалтын ажлын карт (нээлттэй/түүх хоёул). */
@Composable
private fun RcvCard(r: ReceivingApi.Receipt, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
