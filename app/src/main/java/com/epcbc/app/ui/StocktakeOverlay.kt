package com.epcbc.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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

/** Илүү/Дутуугийн нэг мөр (status/branch null бол баруун багана нуугдана). */
data class StExtraRow(
    val epcHex: String,
    val name: String,
    val sku: String?,
    val status: String?,
    val branchName: String?,
)

/** EPC төлөвийн Монгол нэр (вебийн epcStatus-тай ижил). */
internal val ST_STATUS_LABEL = mapOf(
    "unprinted" to "Хэвлээгүй",
    "active" to "Идэвхтэй",
    "sold" to "Борлуулсан",
    "transferring" to "Шилжүүлж буй",
    "other" to "Бусад гүйлгээ",
)

// Статистикийн тогтмол өнгөнүүд (нэр нь тоотойгоо ижил өнгөтэй — 2026-08-05).
private val GREEN = Color(0xFF059669)
private val RED = Color(0xFFDC2626)
private val ORANGE = Color(0xFFD97706)

/**
 * Тооллогын overlay — Dialog БИШ, in-tree Surface (PTT товч Activity-д хүрнэ).
 * Навигаци: толгойд ГАНЦ товч — цэсэнд "Хаах", дэд дэлгэцүүдэд "Буцах"
 * (зөвхөн НЭГ алхам). Түүх/Илүү/Дутуу: таг бүрийн snapshot ТАТАХГҮЙ,
 * зөвхөн нэгтгэл/жагсаалтын дэлгэрэнгүй (гацахгүй). Түүхийн Илүү нь
 * СКАН ХИЙХ ҮЕИЙН хөлдсөн төлөв/салбараа харуулна (вебтэй ижил).
 */
@Composable
fun StocktakeOverlay(
    stocktakes: List<StocktakeApi.Stocktake>,
    stocktakesLoading: Boolean,
    active: StocktakeApi.Stocktake?,
    expectedLoading: Boolean,
    rows: List<StRow>,
    extraServer: Int,
    extras: List<StExtraRow>,
    extrasLoading: Boolean,
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
    onLoadExtras: (stocktakeId: String) -> Unit,
    onCreate: (branchId: String, note: String?) -> Unit,
    onRefreshClosed: () -> Unit,
    onSelectHistory: (StocktakeApi.Stocktake?) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("menu") }
    // Дэлгэрэнгүй дэд дэлгэцүүд: null | "extras" | "missing"
    var detail by rememberSaveable { mutableStateOf<String?>(null) }

    val goBack: () -> Unit = {
        when {
            detail != null -> detail = null
            active != null -> onSelect(null)
            history != null -> onSelectHistory(null)
            mode != "menu" -> mode = "menu"
            else -> onDismiss()
        }
    }
    BackHandler(onBack = goBack)

    val atMenu = active == null && history == null && mode == "menu"
    val missingTotal = historyRows.sumOf { (it.expected - it.found).coerceAtLeast(0) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            val title = when {
                detail == "extras" -> "Илүү — ${if (active != null) extraServer else historyExtra}"
                detail == "missing" -> "Дутуу — $missingTotal"
                active != null -> "${active.number} · ${active.branchName}"
                history != null -> "${history.number} · ${history.branchName}"
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
                if (atMenu) {
                    TextButton(onClick = onDismiss) { Text("Хаах") }
                } else {
                    TextButton(onClick = goBack) { Text("Буцах") }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            when {
                // ---------- Илүүгийн дэлгэрэнгүй (идэвхтэй + түүх хоёул) ----------
                detail == "extras" -> {
                    if (extrasLoading) {
                        Text("Илүүгийн жагсаалт татаж байна…", modifier = Modifier.padding(top = 16.dp))
                    } else if (extras.isEmpty()) {
                        Text("Илүү уншилт алга.", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        Text(
                            "Бүртгэлтэй ч жагсаалтад байгаагүй таг — СКАН ХИЙХ ҮЕИЙН төлөв/салбар нь шалтгааныг хэлнэ.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        ExtraList(extras)
                    }
                }

                // ---------- Дутуугийн дэлгэрэнгүй (түүх): бараа × ширхэг ----------
                // EPC бүрээр биш — вебээс жагсаалтыг нь хардаг тул энд зөвхөн
                // ямар бараа хэдэн ширхэг дутуу гэдэг тоог харуулна (2026-08-05).
                detail == "missing" -> {
                    val missingRows = historyRows
                        .filter { it.found < it.expected }
                        .sortedByDescending { it.expected - it.found }
                    if (missingRows.isEmpty()) {
                        Text("Дутуу алга — бүрэн тоологдсон.", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        Spacer(Modifier.height(4.dp))
                        LazyColumn {
                            items(missingRows, key = { it.productId }) { r ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.name, maxLines = 2)
                                        r.sku?.takeIf { it != r.name }?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Text(
                                        "${r.expected - r.found} ш",
                                        color = RED,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }

                // ---------- Идэвхтэй тооллого ----------
                active != null -> {
                    if (expectedLoading) {
                        Text("Тооллогын жагсаалт татаж байна…", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        ActiveStocktake(
                            st = active, rows = rows, extraServer = extraServer,
                            pendingCount = pendingCount, submitBusy = submitBusy,
                            progressLoading = progressLoading,
                            onSubmit = onSubmit, onFindMissing = onFindMissing,
                            onRefreshProgress = onRefreshProgress,
                            onExtrasClick = {
                                detail = "extras"
                                onLoadExtras(active.id)
                            },
                        )
                    }
                }

                // ---------- Түүхийн дэлгэрэнгүй ----------
                history != null -> {
                    if (historyLoading) {
                        Text("Түүх татаж байна…", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        HistoryDetail(
                            st = history, rows = historyRows, extra = historyExtra,
                            onMissingClick = { detail = "missing" },
                            onExtrasClick = {
                                detail = "extras"
                                onLoadExtras(history.id)
                            },
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            branchId?.let {
                                onCreate(it, note.trim().ifEmpty { null })
                                mode = "open"
                            }
                        },
                        enabled = !createBusy && branchId != null,
                    ) { Text(if (createBusy) "Эхлүүлж байна…" else "Эхлүүлэх") }
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

                // ---------- Түүх ----------
                else -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
            st.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Хүрээтэй мөр (статистик + огноо/тэмдэглэлийн мөрөнд). */
@Composable
private fun FramedRow(content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Огноо (урд) + тэмдэглэл (ард) — хүрээтэй. Тэмдэглэл хоосон бол хоосон. */
@Composable
private fun InfoRow(date: String, note: String?) {
    FramedRow {
        Text(date, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            note ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Илүү/Дутуугийн жагсаалт — нэр, SKU, hex сүүл (+ төлөв/салбар байвал). */
@Composable
private fun ExtraList(rows: List<StExtraRow>) {
    LazyColumn {
        items(rows, key = { it.epcHex }) { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, maxLines = 2)
                    Text(
                        // Нэргүй бараанд SKU нэр болоод орсон бол давхардуулахгүй.
                        listOfNotNull(e.sku?.takeIf { it != e.name }, "…" + e.epcHex.takeLast(6))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (e.status != null || e.branchName != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        e.status?.let {
                            Text(
                                ST_STATUS_LABEL[it] ?: it,
                                color = ORANGE,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        e.branchName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

/** Идэвхтэй тооллогын амьд явц + илгээх. */
@Composable
private fun ActiveStocktake(
    st: StocktakeApi.Stocktake,
    rows: List<StRow>,
    extraServer: Int,
    pendingCount: Int,
    submitBusy: Boolean,
    progressLoading: Boolean,
    onSubmit: () -> Unit,
    onFindMissing: (String) -> Unit,
    onRefreshProgress: () -> Unit,
    onExtrasClick: () -> Unit,
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
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onRefreshProgress, enabled = !progressLoading) { Text("↻") }
    }

    FramedRow {
        StStat("Тоологдсон", "$totalFound / $totalExpected", GREEN)
        StStat("Дутуу", "$missing", RED)
        StStat("Илүү", "$extraServer", ORANGE, onClick = onExtrasClick)
    }
    InfoRow(date = st.createdAt.take(10), note = st.note)
    Spacer(Modifier.height(4.dp))

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
                        done -> GREEN
                        r.found > 0 -> ORANGE
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

/** Хаагдсан тооллогын дүн — зөвхөн харах; Дутуу/Илүү дарж дэлгэрэнгүй. */
@Composable
private fun HistoryDetail(
    st: StocktakeApi.Stocktake,
    rows: List<StRow>,
    extra: Long,
    onMissingClick: () -> Unit,
    onExtrasClick: () -> Unit,
) {
    val totalExpected = rows.sumOf { it.expected }
    val totalFound = rows.sumOf { it.found }
    val missing = (totalExpected - totalFound).coerceAtLeast(0)

    FramedRow {
        StStat("Тоологдсон", "$totalFound / $totalExpected", GREEN)
        StStat("Дутуу", "$missing", RED, onClick = onMissingClick)
        StStat("Илүү", "$extra", ORANGE, onClick = onExtrasClick)
    }
    InfoRow(date = st.closedAt?.take(10) ?: st.createdAt.take(10), note = st.note)
    Spacer(Modifier.height(4.dp))

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
                    color = if (done) GREEN else RED,
                    fontWeight = FontWeight.Bold,
                )
            }
            HorizontalDivider()
        }
    }
}

/**
 * Нэг тоон статистик — тоо том, нэр нь тоотойгоо ИЖИЛ өнгөтэй (2026-08-05).
 * onClick өгвөл дарж дэлгэрэнгүй нээнэ (▸ тэмдэгтэй).
 */
@Composable
internal fun StStat(label: String, value: String, color: Color, onClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(
            label + if (onClick != null) " ▸" else "",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
