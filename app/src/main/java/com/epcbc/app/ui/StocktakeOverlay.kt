package com.epcbc.app.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.epcbc.net.StocktakeApi

/** Нэг барааны амьд явц — дэлгэц таг биш БАРААГААР зурагдана (10k тагт ч хөнгөн). */
data class StRow(
    val productId: String,
    val name: String,
    val sku: String?,
    val expected: Int,
    val found: Int,
)

/**
 * Тооллогын overlay — Dialog БИШ, in-tree Surface (C5-ийн PTT товч Activity-д
 * хүрсэн хэвээр; Surface нь dark theme-д контентын өнгийг зөв өгнө).
 * Тооллого сонгоогүй үед: нээлттэй жагсаалт. Сонгосон үед: гар дээрх амьд
 * явц (Тоологдсон x/N, Дутуу, Жагсаалтад байхгүй) + Илгээх/Явц.
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
    onRefreshList: () -> Unit,
    onSelect: (StocktakeApi.Stocktake?) -> Unit,
    onRefreshProgress: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Толгой
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (active == null) "Тооллого — нээлттэй ажлууд"
                    else "Тооллого: ${active.number} · ${active.branchName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Хаах") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            if (active == null) {
                // ---------- Тооллогын жагсаалт ----------
                Row(Modifier.padding(vertical = 4.dp)) {
                    OutlinedButton(onClick = onRefreshList, enabled = !stocktakesLoading) {
                        Text(if (stocktakesLoading) "Татаж байна…" else "↻ Сэргээх")
                    }
                }
                if (stocktakes.isEmpty() && !stocktakesLoading) {
                    Text(
                        "Нээлттэй тооллого алга. Вебээс (Тооллого таб) шинээр үүсгэнэ.",
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(stocktakes, key = { it.id }) { st ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(st) },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row {
                                    Text(st.number, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(st.createdAt.take(10), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(st.branchName, style = MaterialTheme.typography.bodySmall)
                                st.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            } else if (expectedLoading) {
                Text("Тооллогын жагсаалт татаж байна…", modifier = Modifier.padding(top = 16.dp))
            } else {
                // ---------- Амьд явц + илгээх ----------
                val totalExpected = rows.sumOf { it.expected }
                val totalFound = rows.sumOf { it.found }
                val missing = (totalExpected - totalFound).coerceAtLeast(0)

                Row(
                    Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onSubmit, enabled = !submitBusy && pendingCount > 0) {
                        Text(if (submitBusy) "Илгээж байна…" else "⬆ Илгээх ($pendingCount)")
                    }
                    OutlinedButton(onClick = onRefreshProgress, enabled = !progressLoading) {
                        Text("↻ Явц")
                    }
                    OutlinedButton(onClick = { onSelect(null) }) {
                        Text("← Ажлууд")
                    }
                }

                // Гар дээрх амьд тоонууд — сүлжээгүй ч ажиллана (тулгалт локал).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
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
                                modifier = Modifier.width(96.dp),
                                color = when {
                                    done -> Color(0xFF059669)
                                    r.found > 0 -> Color(0xFFD97706)
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
    }
}

@Composable
private fun StStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
