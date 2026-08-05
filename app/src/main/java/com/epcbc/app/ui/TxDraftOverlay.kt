package com.epcbc.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.epcbc.net.TxDraftApi

/** Сагсны нэг мөр — бараагаар бүлэглэсэн (нэр × ширхэг). */
data class TxCartRow(
    val productId: String,
    val name: String,
    val sku: String?,
    val count: Int,
)

/** Даалгаврын нэг мөр — амьд явцтай (уншсан/даалгасан). */
data class TxJobRow(
    val productId: String,
    val name: String,
    val sku: String?,
    val expected: Int,
    val picked: Int,
)

/**
 * Шилжүүлэг/Актлалтын ноорог-сагс — in-tree overlay (Dialog БИШ: PTT товч
 * Activity-д хүрнэ; Surface — dark theme-д текст зөв өнгөтэй).
 * Ноорог сонгоогүй үед: өөрийн нээлттэй ноорогууд + шинэ сагс үүсгэх.
 * Сонгосон үед: сагсны байдал (бараагаар) + Сагслах / Гүйлгээ болгох / Цуцлах.
 * Таг бүр СЕРВЕРТ хадгалагддаг тул апп унтарсан ч сагс алдагдахгүй.
 */
@Composable
fun TxDraftOverlay(
    type: String, // transfer | other
    branches: List<TxDraftApi.Branch>,
    drafts: List<TxDraftApi.Draft>,
    draftsLoading: Boolean,
    active: TxDraftApi.Draft?,
    itemsLoading: Boolean,
    rows: List<TxCartRow>,
    /** Даалгаврын мөрүүд (амьд явцтай). Хоосон = чөлөөт сагс. */
    jobRows: List<TxJobRow>,
    /** Жагсаалтад тохироогүй уншилтын тоолуур (зөвлөмж). */
    extraLocal: Int,
    cartCount: Int,
    pendingCount: Int,
    submitBusy: Boolean,
    message: String?,
    error: String?,
    branchName: (String?) -> String?,
    onRefreshList: () -> Unit,
    onCreate: (toBranch: String?, note: String?) -> Unit,
    onSelect: (TxDraftApi.Draft?) -> Unit,
    onChangeDest: (String) -> Unit,
    onScanToCart: () -> Unit,
    onRemoveProduct: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancelDraft: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isTransfer = type == "transfer"
    val title = when (type) {
        "transfer" -> "Шилжүүлэг"
        "other" -> "Актлалт"
        "sale" -> "Борлуулалт"
        else -> "Буцаалт"
    }

    // Баталгаажуулалт: submit/cancel хоёрт in-tree жижиг карт (Dialog биш).
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(onBack = {
        when {
            confirm != null -> confirm = null
            active != null -> onSelect(null)
            else -> onDismiss()
        }
    })

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // Доод үйлдлийн мөр (Гүйлгээ болгох/Цуцлах) системийн
                // навигацийн доор дарагдахгүй (C5 дээр илэрсэн).
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (active == null) "$title — ноорогууд" else "$title — сагс",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Хаах") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            if (active == null) {
                // ---------- Ноорогийн жагсаалт + шинэ сагс ----------
                var toBranch by rememberSaveable { mutableStateOf<String?>(null) }
                var note by rememberSaveable { mutableStateOf("") }
                var destMenuOpen by remember { mutableStateOf(false) }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Шинэ сагс", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        if (isTransfer) {
                            Box {
                                OutlinedButton(onClick = { destMenuOpen = true }) {
                                    Text("Очих салбар: ${branchName(toBranch) ?: "сонгоно уу"} ▾")
                                }
                                DropdownMenu(expanded = destMenuOpen, onDismissRequest = { destMenuOpen = false }) {
                                    branches.forEach { b ->
                                        DropdownMenuItem(
                                            text = { Text(b.name) },
                                            onClick = { toBranch = b.id; destMenuOpen = false },
                                        )
                                    }
                                }
                            }
                        } else if (type == "other") {
                            // Актлалтад шалтгаан заавал — тайлан шалтгаанаар бүлэглэдэг
                            // (сервер шаарддаггүй ч энд шаардаж хэвшүүлнэ).
                            OutlinedTextField(
                                value = note,
                                onValueChange = { note = it },
                                label = { Text("Шалтгаан (заавал)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        } else {
                            // Борлуулалт/Буцаалт: тэмдэглэл сонголтоор.
                            OutlinedTextField(
                                value = note,
                                onValueChange = { note = it },
                                label = { Text("Тэмдэглэл (сонголт)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        if (type == "return") {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Буцаалтын сагсанд Борлуулсан/Актлагдсан таг л орно.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onCreate(toBranch, note.trim().ifEmpty { null }) },
                            enabled = !submitBusy && when (type) {
                                "transfer" -> toBranch != null
                                "other" -> note.trim().isNotEmpty()
                                else -> true
                            },
                        ) { Text("＋ Сагс нээх") }
                    }
                }
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onRefreshList, enabled = !draftsLoading) { Text("↻") }
                }
                if (drafts.isEmpty() && !draftsLoading) {
                    Text("Нээлттэй ноорог алга.", modifier = Modifier.padding(top = 12.dp))
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    items(drafts, key = { it.id }) { d ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(d) },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row {
                                    Text(
                                        branchName(d.fromBranch)?.let { "Эх: $it" } ?: "Хоосон сагс",
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(d.createdAt.take(10), style = MaterialTheme.typography.bodySmall)
                                }
                                if (isTransfer) {
                                    Text(
                                        "Очих: ${branchName(d.toBranch) ?: "сонгоогүй"}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                d.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            } else if (itemsLoading) {
                Text("Сагс татаж байна…", modifier = Modifier.padding(top = 16.dp))
            } else {
                // ---------- Идэвхтэй сагс ----------
                var destMenuOpen by remember { mutableStateOf(false) }

                if (isTransfer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Эх: ${branchName(active.fromBranch) ?: "эхний тагаар тогтоно"}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            OutlinedButton(onClick = { destMenuOpen = true }) {
                                Text("Очих: ${branchName(active.toBranch) ?: "сонгоно уу"} ▾")
                            }
                            DropdownMenu(expanded = destMenuOpen, onDismissRequest = { destMenuOpen = false }) {
                                branches.forEach { b ->
                                    DropdownMenuItem(
                                        text = { Text(b.name) },
                                        onClick = { onChangeDest(b.id); destMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "Эх: ${branchName(active.fromBranch) ?: "эхний тагаар тогтоно"}" +
                            (active.note?.let { " · Шалтгаан: $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onScanToCart, enabled = !submitBusy && pendingCount > 0) {
                        Text(if (submitBusy) "Илгээж байна…" else "🧺 Сагслах ($pendingCount)")
                    }
                    OutlinedButton(onClick = { onSelect(null) }) { Text("Буцах") }
                }

                val isJob = jobRows.isNotEmpty()
                if (isJob) {
                    // ---------- Даалгаврын амьд явц (жагсаалттай) ----------
                    val planned = jobRows.sumOf { it.expected }
                    val picked = jobRows.sumOf { it.picked }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StStat(
                            "Уншсан", "$picked / $planned",
                            if (picked >= planned) Color(0xFF059669) else MaterialTheme.colorScheme.onBackground,
                        )
                        StStat(
                            "Илгээгээгүй буфер", "$pendingCount",
                            if (pendingCount > 0) Color(0xFFD97706) else MaterialTheme.colorScheme.onBackground,
                        )
                        StStat(
                            "Тохирохгүй", "$extraLocal",
                            if (extraLocal > 0) Color(0xFFD97706) else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        "Тулгалт төхөөрөмж дээр шууд. Жагсаалтад байхгүй / тоо гүйцсэн таг " +
                            "сагсанд ОРОХГҮЙ — Сагслахад сервер давхар шалгана.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))

                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("БАРАА", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("УНШСАН", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(96.dp))
                    }
                    HorizontalDivider()
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(jobRows, key = { it.productId }) { r ->
                            val done = r.picked >= r.expected
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, maxLines = 2)
                                    r.sku?.let {
                                        Text(
                                            it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    "${r.picked} / ${r.expected}",
                                    modifier = Modifier.width(96.dp),
                                    color = when {
                                        done -> Color(0xFF059669)
                                        r.picked > 0 -> Color(0xFFD97706)
                                        else -> MaterialTheme.colorScheme.onBackground
                                    },
                                    fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    // ---------- Чөлөөт сагс (жагсаалтгүй) ----------
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StStat("Сагсанд", "$cartCount", MaterialTheme.colorScheme.onBackground)
                    StStat(
                        "Илгээгээгүй буфер", "$pendingCount",
                        if (pendingCount > 0) Color(0xFFD97706) else MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    "Сагсалсан таг серверт хадгалагдана — апп хаагдсан ч алдагдахгүй. " +
                        "Гүйлгээ болгох хүртэл бараанд юу ч өөрчлөгдөхгүй.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("БАРАА", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("ШИРХЭГ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(88.dp))
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(rows, key = { it.productId }) { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, maxLines = 2)
                                r.sku?.let {
                                    Text(
                                        it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text("${r.count}", modifier = Modifier.width(48.dp), fontWeight = FontWeight.Bold)
                            TextButton(onClick = { onRemoveProduct(r.productId) }) { Text("✕") }
                        }
                        HorizontalDivider()
                    }
                }
                }

                // Доод үйлдлүүд: гүйлгээ болгох / цуцлах — хоёул баталгаажуулалттай.
                if (confirm == null) {
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { confirm = "submit" },
                            enabled = !submitBusy && cartCount > 0 &&
                                (!isTransfer || active.toBranch != null),
                        ) { Text("✅ Гүйлгээ болгох ($cartCount)") }
                        OutlinedButton(onClick = { confirm = "cancel" }, enabled = !submitBusy) {
                            Text("🗑 Цуцлах")
                        }
                    }
                    if (isTransfer && active.toBranch == null) {
                        Text(
                            "Гүйлгээ болгохын өмнө очих салбараа сонгоно уу.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD97706),
                        )
                    }
                } else {
                    // Даалгаснаас дутуу байвал ил анхааруулаад л зөвшөөрнө
                    // (2026-08-05 тохирсон: бараа олдоогүй байх нь бодит).
                    val jobPlanned = jobRows.sumOf { it.expected }
                    val shortfall = (jobPlanned - cartCount).coerceAtLeast(0)
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                if (confirm == "submit")
                                    "$cartCount ширхгийг ${
                                        when (type) {
                                            "transfer" -> "«${branchName(active.toBranch)}» руу шилжүүлэх"
                                            "other" -> "актлах"
                                            "sale" -> "борлуулах"
                                            else -> "буцаан авах"
                                        }
                                    } уу?" +
                                        (if (jobRows.isNotEmpty() && shortfall > 0)
                                            "\n⚠ Даалгаснаас $shortfall ширхэг ДУТУУ байна."
                                        else "")
                                else
                                    "Сагсыг цуцлах уу? (Гүйлгээ үүсэхгүй, бараанд юу ч болохгүй.)",
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (confirm == "submit") onSubmit() else onCancelDraft()
                                        confirm = null
                                    },
                                    enabled = !submitBusy,
                                ) { Text("Тийм") }
                                OutlinedButton(onClick = { confirm = null }) { Text("Болих") }
                            }
                        }
                    }
                }
            }
        }
    }
}
