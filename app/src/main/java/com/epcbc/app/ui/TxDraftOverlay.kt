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

/** "Эхний тагаар тогтооно" гэсэн ил сонголтын sentinel (Салбаргүй бараанд хэрэгтэй). */
private const val FROM_AUTO = "__auto__"

// Статистик/төлөвийн тогтмол өнгөнүүд (Тооллоготой ижил).
private val TX_GREEN = Color(0xFF059669)
private val TX_RED = Color(0xFFDC2626)
private val TX_ORANGE = Color(0xFFD97706)

/** Шилжүүлгийн төлөвийн Монгол нэр + өнгө. */
private fun txStatusLabel(status: String): Pair<String, Color> = when (status) {
    "pending" -> "Хүлээгдэж буй" to TX_ORANGE
    "cancelled" -> "Цуцлагдсан" to TX_RED
    else -> "Хүлээн авсан" to TX_GREEN
}

/**
 * Шилжүүлэг/Актлалт/Борлуулалт/Буцаалтын нүд — in-tree overlay (Dialog БИШ:
 * PTT товч Activity-д хүрнэ; Surface — dark theme-д текст зөв өнгөтэй).
 * Навигаци Тооллоготой ижил: 3 карттай цэс (Шинэ сагс / Нээлттэй сагсууд /
 * Түүх), толгойд ГАНЦ товч — цэсэнд "Хаах", дэд дэлгэцэд "Буцах" (нэг алхам).
 * Таг бүр СЕРВЕРТ хадгалагддаг тул апп унтарсан ч сагс алдагдахгүй.
 */
@Composable
fun TxDraftOverlay(
    type: String, // transfer | other | sale | return
    branches: List<TxDraftApi.Branch>,
    /** Эх салбараар сонгож болох жагсаалт (өөрийн хандах эрхтэй салбарууд). */
    myBranches: List<TxDraftApi.Branch>,
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
    history: List<TxDraftApi.TxRow>,
    historyLoading: Boolean,
    historyDetail: TxDraftApi.TxRow?,
    /** Түүхийн дэлгэрэнгүй — бараагаар бүлэглэсэн мөрүүд. */
    historyRows: List<TxCartRow>,
    historyItemsLoading: Boolean,
    /** Шат 4: ирж буй (pending) шилжүүлгүүд — зөвхөн transfer нүдэнд. */
    incoming: List<TxDraftApi.TxRow>,
    incomingLoading: Boolean,
    activeIncoming: TxDraftApi.TxRow?,
    /** Хүлээн авалтын явц — бараагаар (expected=нийт, picked=ирсэн). */
    incomingRows: List<TxJobRow>,
    incomingItemsLoading: Boolean,
    onRefreshList: () -> Unit,
    onCreate: (fromBranch: String?, toBranch: String?, note: String?) -> Unit,
    onSelect: (TxDraftApi.Draft?) -> Unit,
    onChangeDest: (String) -> Unit,
    onScanToCart: () -> Unit,
    onRemoveProduct: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancelDraft: () -> Unit,
    onRefreshHistory: () -> Unit,
    onSelectHistory: (TxDraftApi.TxRow?) -> Unit,
    onRefreshIncoming: () -> Unit,
    onSelectIncoming: (TxDraftApi.TxRow?) -> Unit,
    onReceiveScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isTransfer = type == "transfer"
    val title = when (type) {
        "transfer" -> "Шилжүүлэг"
        "other" -> "Актлалт"
        "sale" -> "Борлуулалт"
        else -> "Буцаалт"
    }

    var mode by rememberSaveable { mutableStateOf("menu") }
    // Баталгаажуулалт: submit/cancel хоёрт in-tree жижиг карт (Dialog биш).
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }

    val goBack: () -> Unit = {
        when {
            confirm != null -> confirm = null
            active != null -> onSelect(null)
            historyDetail != null -> onSelectHistory(null)
            activeIncoming != null -> onSelectIncoming(null)
            mode != "menu" -> mode = "menu"
            else -> onDismiss()
        }
    }
    BackHandler(onBack = goBack)

    val atMenu = active == null && historyDetail == null && activeIncoming == null && mode == "menu"

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
            val headerTitle = when {
                active != null -> "$title — сагс"
                historyDetail != null -> historyDetail.txNumber ?: "$title — түүх"
                activeIncoming != null -> "${activeIncoming.txNumber ?: "?"} хүлээн авах"
                mode == "create" -> "Шинэ сагс"
                mode == "open" -> "Нээлттэй сагсууд"
                mode == "history" -> "$title — түүх"
                mode == "incoming" -> "Ирж буй шилжүүлэг"
                else -> title
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    headerTitle,
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
                // ---------- Идэвхтэй сагс ----------
                active != null -> {
                    if (itemsLoading) {
                        Text("Сагс татаж байна…", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        ActiveCart(
                            type = type, isTransfer = isTransfer, active = active,
                            branches = branches, rows = rows, jobRows = jobRows,
                            extraLocal = extraLocal, cartCount = cartCount,
                            pendingCount = pendingCount, submitBusy = submitBusy,
                            confirm = confirm, setConfirm = { confirm = it },
                            branchName = branchName, onChangeDest = onChangeDest,
                            onScanToCart = onScanToCart, onRemoveProduct = onRemoveProduct,
                            onSubmit = onSubmit, onCancelDraft = onCancelDraft,
                        )
                    }
                }

                // ---------- Түүхийн дэлгэрэнгүй (бараа × ширхэг) ----------
                historyDetail != null -> {
                    if (historyItemsLoading) {
                        Text("Түүх татаж байна…", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        Text(
                            if (isTransfer)
                                "${branchName(historyDetail.fromBranch) ?: "Салбаргүй"} → ${branchName(historyDetail.toBranch) ?: "?"}"
                            else
                                "Эх: ${branchName(historyDetail.fromBranch) ?: "Салбаргүй"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            historyDetail.createdAt.take(10) +
                                (historyDetail.note?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isTransfer) {
                            val (label, color) = txStatusLabel(historyDetail.status)
                            Text(label, style = MaterialTheme.typography.bodySmall, color = color)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("БАРАА", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text("ШИРХЭГ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(72.dp))
                        }
                        HorizontalDivider()
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(historyRows, key = { it.productId }) { r ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.name, maxLines = 2)
                                        r.sku?.takeIf { it != r.name }?.let {
                                            Text(
                                                it, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Text(
                                        "${r.count}",
                                        modifier = Modifier.width(72.dp),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                        Text(
                            "Нийт: ${historyRows.sumOf { it.count }} ширхэг",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                // ---------- Шат 4: Ирж буй шилжүүлгийг хүлээн авах ----------
                activeIncoming != null -> {
                    if (incomingItemsLoading) {
                        Text("Явц татаж байна…", modifier = Modifier.padding(top = 16.dp))
                    } else {
                        val total = incomingRows.sumOf { it.expected }
                        val received = incomingRows.sumOf { it.picked }
                        Text(
                            "${branchName(activeIncoming.fromBranch) ?: "Салбаргүй"} → ${branchName(activeIncoming.toBranch) ?: "?"}" +
                                (activeIncoming.note?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = onReceiveScan, enabled = !submitBusy && pendingCount > 0) {
                                Text(if (submitBusy) "Илгээж байна…" else "📥 Хүлээн авах ($pendingCount)")
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            StStat(
                                "Ирсэн", "$received / $total",
                                if (received >= total) TX_GREEN else TX_ORANGE,
                            )
                            StStat(
                                "Илгээгээгүй буфер", "$pendingCount",
                                if (pendingCount > 0) TX_ORANGE else MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Text(
                            "Таг уншаад «Хүлээн авах» дарна — ирсэн нь энэ салбарт Идэвхтэй " +
                                "болно. Дутуу нь ЗАМД хэвээр үлдэж, дараа нэмж хүлээн авах эсвэл " +
                                "цуцлахад эх салбартаа буцна.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("БАРАА", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text("ИРСЭН", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(96.dp))
                        }
                        HorizontalDivider()
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(incomingRows, key = { it.productId }) { r ->
                                val done = r.picked >= r.expected
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.name, maxLines = 2)
                                        r.sku?.takeIf { it != r.name }?.let {
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
                                            done -> TX_GREEN
                                            r.picked > 0 -> TX_ORANGE
                                            else -> MaterialTheme.colorScheme.onBackground
                                        },
                                        fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
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
                    MenuCard(
                        "＋", "Шинэ сагс",
                        when (type) {
                            "transfer" -> "Эх/очих салбараа сонгоод сагс нээнэ"
                            "other" -> "Шалтгаанаа бичээд сагс нээнэ"
                            "return" -> "Борлуулсан/Актлагдсан тагийг буцаана"
                            else -> "Сагс нээгээд таг уншина"
                        },
                    ) { mode = "create" }
                    MenuCard("🧺", "Нээлттэй сагсууд", "Эхэлсэн сагсаа үргэлжлүүлж уншина") {
                        mode = "open"
                        onRefreshList()
                    }
                    if (isTransfer) {
                        // Шат 4: очих салбар дээр ирсэн ачааг уншиж хүлээн авна.
                        MenuCard("📥", "Ирж буй шилжүүлэг", "Ирсэн ачааг уншиж хүлээн авна") {
                            mode = "incoming"
                            onRefreshIncoming()
                        }
                    }
                    MenuCard("🕘", "Түүх", "Хийгдсэн гүйлгээнүүд (сүүлийн 30)") {
                        mode = "history"
                        onRefreshHistory()
                    }
                }

                // ---------- Шинэ сагс ----------
                mode == "create" -> {
                    var toBranch by rememberSaveable { mutableStateOf<String?>(null) }
                    var note by rememberSaveable { mutableStateOf("") }
                    var destMenuOpen by remember { mutableStateOf(false) }
                    var fromMenuOpen by remember { mutableStateOf(false) }

                    // Эх салбар: ганц хандах салбартай бол автоматаар түгжинэ (алхам
                    // нэмэгдэхгүй); олон бол ЗААВАЛ сонгуулна — өөр салбарын төөрсөн
                    // таг анх уншигдаад сагсыг буруу түгжих эрсдэлээс сэргийлнэ.
                    // "Эхний тагаар" гэдэг ил сонголт Салбаргүй барааны замыг хадгална.
                    var fromSel by rememberSaveable { mutableStateOf<String?>(null) }
                    val effFrom = fromSel ?: myBranches.singleOrNull()?.id
                    val fromChosen = effFrom != null || myBranches.isEmpty()
                    val fromLabel = when (effFrom) {
                        null -> "сонгоно уу"
                        FROM_AUTO -> "эхний тагаар"
                        else -> branchName(effFrom) ?: "сонгоно уу"
                    }

                    Spacer(Modifier.height(6.dp))
                    Box {
                        OutlinedButton(onClick = { fromMenuOpen = true }) {
                            Text("Эх салбар: $fromLabel ▾")
                        }
                        DropdownMenu(expanded = fromMenuOpen, onDismissRequest = { fromMenuOpen = false }) {
                            myBranches.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text(b.name) },
                                    onClick = {
                                        fromSel = b.id
                                        // Очих = эх байж болохгүй — давхцвал арилгана.
                                        if (toBranch == b.id) toBranch = null
                                        fromMenuOpen = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Эхний тагаар тогтооно") },
                                onClick = { fromSel = FROM_AUTO; fromMenuOpen = false },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (isTransfer) {
                        Box {
                            OutlinedButton(onClick = { destMenuOpen = true }) {
                                Text("Очих салбар: ${branchName(toBranch) ?: "сонгоно уу"} ▾")
                            }
                            DropdownMenu(expanded = destMenuOpen, onDismissRequest = { destMenuOpen = false }) {
                                branches.filter { it.id != effFrom }.forEach { b ->
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
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            onCreate(
                                effFrom?.takeIf { it != FROM_AUTO },
                                toBranch,
                                note.trim().ifEmpty { null },
                            )
                            // Сагснаас Буцахад "Нээлттэй сагсууд" руу буцна.
                            mode = "open"
                        },
                        enabled = !submitBusy && fromChosen && when (type) {
                            "transfer" -> toBranch != null
                            "other" -> note.trim().isNotEmpty()
                            else -> true
                        },
                    ) { Text(if (submitBusy) "Нээж байна…" else "＋ Сагс нээх") }
                }

                // ---------- Нээлттэй сагсууд ----------
                mode == "open" -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onRefreshList, enabled = !draftsLoading) { Text("↻") }
                    }
                    if (drafts.isEmpty() && !draftsLoading) {
                        Text("Нээлттэй сагс алга — цэснээс шинээр нээнэ.", modifier = Modifier.padding(top = 12.dp))
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }

                // ---------- Ирж буй шилжүүлгийн жагсаалт ----------
                mode == "incoming" -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onRefreshIncoming, enabled = !incomingLoading) { Text("↻") }
                    }
                    if (incoming.isEmpty() && !incomingLoading) {
                        Text("Хүлээгдэж буй шилжүүлэг алга.", modifier = Modifier.padding(top = 12.dp))
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(incoming, key = { it.id }) { t ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectIncoming(t) },
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row {
                                        Text(
                                            t.txNumber ?: "—",
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(t.createdAt.take(10), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        "${branchName(t.fromBranch) ?: "Салбаргүй"} → ${branchName(t.toBranch) ?: "?"}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    t.note?.let {
                                        Text(
                                            it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
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
                        OutlinedButton(onClick = onRefreshHistory, enabled = !historyLoading) { Text("↻") }
                    }
                    if (history.isEmpty() && !historyLoading) {
                        Text("Гүйлгээ алга.", modifier = Modifier.padding(top = 12.dp))
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(history, key = { it.id }) { t ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectHistory(t) },
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row {
                                        Text(
                                            t.txNumber ?: "—",
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(t.createdAt.take(10), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        if (isTransfer)
                                            "${branchName(t.fromBranch) ?: "Салбаргүй"} → ${branchName(t.toBranch) ?: "?"}"
                                        else
                                            "Эх: ${branchName(t.fromBranch) ?: "Салбаргүй"}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (isTransfer) {
                                        val (label, color) = txStatusLabel(t.status)
                                        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
                                    }
                                    t.note?.let {
                                        Text(
                                            it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Идэвхтэй сагсны дэлгэц: даалгаврын амьд явц эсвэл чөлөөт сагс + доод
 * үйлдлүүд (Гүйлгээ болгох / Цуцлах, баталгаажуулалттай). "Буцах" нь
 * толгойд байгаа тул энд давхар товч байхгүй.
 */
@Composable
private fun ActiveCart(
    type: String,
    isTransfer: Boolean,
    active: TxDraftApi.Draft,
    branches: List<TxDraftApi.Branch>,
    rows: List<TxCartRow>,
    jobRows: List<TxJobRow>,
    extraLocal: Int,
    cartCount: Int,
    pendingCount: Int,
    submitBusy: Boolean,
    confirm: String?,
    setConfirm: (String?) -> Unit,
    branchName: (String?) -> String?,
    onChangeDest: (String) -> Unit,
    onScanToCart: () -> Unit,
    onRemoveProduct: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancelDraft: () -> Unit,
) {
    Column {
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
                    if (picked >= planned) TX_GREEN else MaterialTheme.colorScheme.onBackground,
                )
                StStat(
                    "Илгээгээгүй буфер", "$pendingCount",
                    if (pendingCount > 0) TX_ORANGE else MaterialTheme.colorScheme.onBackground,
                )
                StStat(
                    "Тохирохгүй", "$extraLocal",
                    if (extraLocal > 0) TX_ORANGE else MaterialTheme.colorScheme.onBackground,
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
                                done -> TX_GREEN
                                r.picked > 0 -> TX_ORANGE
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
                    if (pendingCount > 0) TX_ORANGE else MaterialTheme.colorScheme.onBackground,
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
                    onClick = { setConfirm("submit") },
                    enabled = !submitBusy && cartCount > 0 &&
                        (!isTransfer || active.toBranch != null),
                ) { Text("✅ Гүйлгээ болгох ($cartCount)") }
                OutlinedButton(onClick = { setConfirm("cancel") }, enabled = !submitBusy) {
                    Text("🗑 Цуцлах")
                }
            }
            if (isTransfer && active.toBranch == null) {
                Text(
                    "Гүйлгээ болгохын өмнө очих салбараа сонгоно уу.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TX_ORANGE,
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
                                setConfirm(null)
                            },
                            enabled = !submitBusy,
                        ) { Text("Тийм") }
                        OutlinedButton(onClick = { setConfirm(null) }) { Text("Болих") }
                    }
                }
            }
        }
    }
}
