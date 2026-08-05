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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epcbc.net.StocktakeApi
import com.epcbc.net.TxDraftApi

/** Нэг барааны амьд явц — дэлгэц таг биш БАРААГААР зурагдана (10k тагт ч хөнгөн). */
data class StRow(
    val productId: String,
    val name: String,
    val sku: String?,
    val expected: Int,
    val found: Int,
)

/**
 * Тооллогын overlay — Dialog БИШ, in-tree Surface (PTT товч Activity-д хүрнэ).
 * Бүтэц (2026-08-05 хэрэглэгчтэй тохирсон, вебтэй ижил логик):
 *   Цэс: [Тооллого эхлүүлэх] [Нээлттэй ажлууд] [Тооллогын түүх]
 *   Эхлүүлэх: салбар + тэмдэглэл (нэг салбарт нэг нээлттэй — найрсаг сануулга)
 *   Түүх: хаагдсан ажлууд — зөвхөн бараагаар нэгтгэсэн явц + Илүү тоо
 *   (snapshot-ын таг бүрийг ТАТАХГҮЙ тул хүнд биш).
 * Товчны дүрэм: буцах үйлдэл = "Буцах"; refresh = зөвхөн ↻ тэмдэг, мөрийн
 * ХАМГИЙН АРД; бүх товч нэг өндөртэй.
 */
@Composable
fun StocktakeOverlay(
    stocktakes: List<StocktakeApi.Stocktake>,
    stocktakesLoading: Boolean,
    active: StocktakeApi.Stocktake?,
    expectedLoading: Boolean,
    rows: List<StRow>,
    extraLocal: Int,
    pendingCount: Int,
    submitBusy: Boolean,
    progressLoading: Boolean,
    message: String?,
    error: String?,
    branches: List<TxDraftApi.Branch>,
    createBusy: Boolean,
    closed: List<StocktakeApi.Stocktake>,
    closedLoading: Boolean,
    history: StocktakeApi.Stocktake?,
    historyRows: List<StRow>,
    historyExtra: Long,
    historyLoading: Boolean,
    onRefreshList: () -> Unit,
    onSelect: (StocktakeApi.Stocktake?) -> Unit,
    onRefreshProgress: () -> Unit,
    onSubmit: () -> Unit,
    onFindMissing: (productId: String) -> Unit,
    onCreate: (branchId: String, note: String?) -> Unit,
    onRefreshClosed: () -> Unit,
    onSelectHistory: (StocktakeApi.Stocktake?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Дотоод дэлгэц: menu | create | open | history (идэвхтэй ажил/түүхийн
    // дэлгэрэнгүй нь active/history төлвөөр давамгайлна).
    var mode by rememberSaveable { mutableStateOf("menu") }

    BackHandler(onBack = {
        when {
            active != null -> onSelect(null)
            history != null -> onSelectHistory(null)
            mode != "menu" -> mode = "menu"
            else -> onDismiss()
        }
    })

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            val title = when {
                active != null -> "Тооллого: ${active.number} · ${active.branchName}"
                history != null -> "Түүх: ${history.number} · ${history.branchName}"
                mode == "create" -> "Тооллого эхлүүлэх"
                mode == "open" -> "Нээлттэй ажлууд"
                mode == "history" -> "Тооллогын түүх"
                else -> "Тооллого"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Хаах") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            when {
                // ---------- Идэвхтэй тооллого (амьд явц + илгээх) ----------
                active != null -> {
                    if (expectedLoading) {
                        Text("Тооллогын жагсаалт татаж байна…", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        ActiveStocktake(
                            rows = rows, extraLocal = extraLocal, pendingCount = pendingCount,
                            submitBusy = submitBusy, progressLoading = progressLoading,
                            onSubmit = onSubmit, onFindMissing = onFindMissing,
                            onBack = { onSelect(null) }, onRefreshProgress = onRefreshProgress,
                        )
                    }
                }

                // ---------- Түүхийн дэлгэрэнгүй (зөвхөн харах) ----------
                history != null -> {
                    if (historyLoading) {
                        Text("Түүх татаж байна…", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        HistoryDetail(
                            st = history, rows = historyRows, extra = historyExtra,
                            onBack = { onSelectHistory(null) },
                        )
                    }
                }

                // ---------- Цэс ----------
                mode == "menu" -> {
                    Spacer(Modifier.height(4.dp))
                    MenuCard("▶️", "Тооллого эхлүүлэх", "Салбар сонгоод шинэ тооллого нээнэ") {
                        mode = "create"
                        if (stocktakes.isEmpty()) onRefreshList()
                    }
                    MenuCard("📋", "Нээлттэй ажлууд", "Эхэлсэн тооллогоо үргэлжлүүлж тоолно") {
                        mode = "open"
                        if (stocktakes.isEmpty()) onRefreshList()
                    }
                    MenuCard("🕘", "Тооллогын түүх", "Хаагдсан тооллогын дүн — дутуу/илүү") {
                        mode = "history"
                        onRefreshClosed()
                    }
                }

                // ---------- Тооллого эхлүүлэх ----------
                mode == "create" -> {
                    var branchId by rememberSaveable { mutableStateOf<String?>(null) }
                    var note by rememberSaveable { mutableStateOf("") }
                    var menuOpen by remember { mutableStateOf(false) }
                    val openBranchNames = stocktakes.filter { it.status == "open" }.map { it.branchName }.toSet()

                    Spacer(Modifier.height(6.dp))
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }) {
                            Text("Салбар: ${branches.firstOrNull { it.id == branchId }?.name ?: "сонгоно уу"} ▾")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            branches.forEach { b ->
                                DropdownMenuItem(
                                    text = {
                                        Text(b.name + if (b.name in openBranchNames) "  (нээлттэй тооллоготой)" else "")
                                    },
                                    onClick = { branchId = b.id; menuOpen = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Тэмдэглэл (сонголт)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Эхлүүлэх мөчид салбарын бүртгэл хөлдөж (snapshot), тоолж дуустал " +
                            "өөрчлөгдөхгүй. Нэг салбарт нэг л нээлттэй тооллого байна.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                branchId?.let {
                                    onCreate(it, note.trim().ifEmpty { null })
                                    mode = "open"
                                }
                            },
                            enabled = !createBusy && branchId != null,
                        ) { Text(if (createBusy) "Эхлүүлж байна…" else "Эхлүүлэх") }
                        OutlinedButton(onClick = { mode = "menu" }) { Text("Буцах") }
                    }
                }

                // ---------- Нээлттэй ажлууд ----------
                mode == "open" -> {
                    var branchFilter by remember { mutableStateOf<String?>(null) }
                    var branchMenuOpen by remember { mutableStateOf(false) }
                    val listBranches = stocktakes.map { it.branchName }.distinct()
                    val shown = if (branchFilter == null) stocktakes
                                else stocktakes.filter { it.branchName == branchFilter }

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { mode = "menu" }) { Text("Буцах") }
                        if (listBranches.size > 1) {
                            Box {
                                OutlinedButton(onClick = { branchMenuOpen = true }) {
                                    Text("Салбар: ${branchFilter ?: "Бүгд"} ▾")
                                }
                                DropdownMenu(expanded = branchMenuOpen, onDismissRequest = { branchMenuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Бүх салбар") },
                                        onClick = { branchFilter = null; branchMenuOpen = false })
                                    listBranches.forEach { b ->
                                        DropdownMenuItem(text = { Text(b) },
                                            onClick = { branchFilter = b; branchMenuOpen = false })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onRefreshList, enabled = !stocktakesLoading) { Text("↻") }
                    }
                    if (stocktakes.isEmpty() && !stocktakesLoading) {
                        Text("Нээлттэй тооллого алга — цэснээс шинээр эхлүүлнэ.", modifier = Modifier.padding(top = 12.dp))
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.id }) { st ->
                            StCard(st, dateOf = { it.createdAt.take(10) }) { onSelect(st) }
                        }
                    }
                }

                // ---------- Түүх (хаагдсан ажлууд) ----------
                else -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { mode = "menu" }) { Text("Буцах") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onRefreshClosed, enabled = !closedLoading) { Text("↻") }
                    }
                    if (closed.isEmpty() && !closedLoading) {
                        Text("Хаагдсан тооллого алга.", modifier = Modifier.padding(top = 12.dp))
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(closed, key = { it.id }) { st ->
                            StCard(st, dateOf = { it.closedAt?.take(10) ?: it.createdAt.take(10) }) {
                                onSelectHistory(st)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Цэсний нэг мөр-карт. */
@Composable
private fun MenuCard(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable { onClick() },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Тооллогын жагсаалтын карт (нээлттэй/түүх хоёул). */
@Composable
private fun StCard(
    st: StocktakeApi.Stocktake,
    dateOf: (StocktakeApi.Stocktake) -> String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(st.number, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(dateOf(st), style = MaterialTheme.typography.bodySmall)
            }
            Text(st.branchName, style = MaterialTheme.typography.bodySmall)
            st.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Идэвхтэй тооллогын амьд явц + илгээх (өмнөх дэлгэц, товчнууд шинэ дүрмээр). */
@Composable
private fun ActiveStocktake(
    rows: List<StRow>,
    extraLocal: Int,
    pendingCount: Int,
    submitBusy: Boolean,
    progressLoading: Boolean,
    onSubmit: () -> Unit,
    onFindMissing: (String) -> Unit,
    onBack: () -> Unit,
    onRefreshProgress: () -> Unit,
) {
    val totalExpected = rows.sumOf { it.expected }
    val totalFound = rows.sumOf { it.found }
    val missing = (totalExpected - totalFound).coerceAtLeast(0)

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onSubmit, enabled = !submitBusy && pendingCount > 0) {
            Text(if (submitBusy) "Илгээж байна…" else "⬆ Илгээх ($pendingCount)")
        }
        OutlinedButton(onClick = onBack) { Text("Буцах") }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onRefreshProgress, enabled = !progressLoading) { Text("↻") }
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StStat("Тоологдсон", "$totalFound / $totalExpected",
            if (totalFound >= totalExpected) Color(0xFF059669) else MaterialTheme.colorScheme.onBackground)
        StStat("Дутуу", "$missing", if (missing > 0) Color(0xFFDC2626) else Color(0xFF059669))
        StStat("Жагсаалтад байхгүй", "$extraLocal",
            if (extraLocal > 0) Color(0xFFD97706) else MaterialTheme.colorScheme.onBackground)
    }
    Text(
        "Тулгалт төхөөрөмж дээр шууд. Илгээх хүртэл сервер лүү юу ч бичигдэхгүй; " +
            "давхардсан илгээлт аюулгүй (сервер алгасна).",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(6.dp))

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("БАРАА", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text("ТООЛОГДСОН", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(96.dp))
    }
    HorizontalDivider()
    LazyColumn {
        items(rows, key = { it.productId }) { r ->
            val done = r.found >= r.expected
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, maxLines = 2)
                    r.sku?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "${r.found} / ${r.expected}",
                    modifier = Modifier.width(72.dp),
                    color = when {
                        done -> Color(0xFF059669)
                        r.found > 0 -> Color(0xFFD97706)
                        else -> MaterialTheme.colorScheme.onBackground
                    },
                    fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
                )
                if (!done) {
                    TextButton(onClick = { onFindMissing(r.productId) }) { Text("🎯") }
                }
            }
            HorizontalDivider()
        }
    }
}

/** Хаагдсан тооллогын дүн — зөвхөн харах (хөлдсөн баримт). */
@Composable
private fun HistoryDetail(
    st: StocktakeApi.Stocktake,
    rows: List<StRow>,
    extra: Long,
    onBack: () -> Unit,
) {
    val totalExpected = rows.sumOf { it.expected }
    val totalFound = rows.sumOf { it.found }
    val missing = (totalExpected - totalFound).coerceAtLeast(0)

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("Буцах") }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StStat("Тоологдсон", "$totalFound / $totalExpected",
            if (totalFound >= totalExpected) Color(0xFF059669) else MaterialTheme.colorScheme.onBackground)
        StStat("Дутуу", "$missing", if (missing > 0) Color(0xFFDC2626) else Color(0xFF059669))
        StStat("Илүү", "$extra", if (extra > 0) Color(0xFFD97706) else MaterialTheme.colorScheme.onBackground)
    }
    st.note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("БАРАА", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text("ТООЛОГДСОН", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(96.dp))
    }
    HorizontalDivider()
    LazyColumn {
        items(rows, key = { it.productId }) { r ->
            val done = r.found >= r.expected
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, maxLines = 2)
                    r.sku?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "${r.found} / ${r.expected}",
                    modifier = Modifier.width(96.dp),
                    color = if (done) Color(0xFF059669) else Color(0xFFDC2626),
                    fontWeight = FontWeight.Bold,
                )
            }
            HorizontalDivider()
        }
    }
}

/** Нэг тоон статистик (Тооллого + Шилжүүлэг/Актлалтын overlay хуваалцана). */
@Composable
internal fun StStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
