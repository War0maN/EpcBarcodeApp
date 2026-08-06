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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Нүүрний нэг нүд. enabled=false → бүдэг, дарагдахгүй ("удахгүй"). */
data class HomeTile(
    val key: String,
    val emoji: String,
    val title: String,
    val subtitle: String? = null,
    val enabled: Boolean = true,
)

/**
 * Нүүр дэлгэц — үйлдлийн grid (2026-08-02 хэрэглэгчтэй тохирсон дизайн):
 * ажилтан "би юу хийх гэж байна" гэдгээ л сонгоно. Уншигч энд ТОВЧГҮЙ —
 * апп өөрөө ар талд эхлүүлдэг, зөвхөн төлвийн мөр харагдана (бэлэн/холбож
 * байна/алдаа — алдаатай үед дарахад дахин оролдоно).
 */
@Composable
fun HomeScreen(
    loggedIn: Boolean,
    readerReady: Boolean,
    readerStatus: String,
    outputPower: Int,
    /** Офлайн буферт хүлээж буй багцын тоо (0 = индикатор нуугдана). */
    offlineCount: Int,
    tiles: List<HomeTile>,
    onRetryReader: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            // Толгой: нэр + профайл (имэйл/Гарах нь Профайл дотор — 2026-08-03).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Chipmo Inventory",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (!loggedIn) {
                    Text(
                        "Офлайн",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenProfile) { Text("👤", fontSize = 20.sp) }
            }

            // Уншигчийн төлвийн мөр — бэлэн биш үед дарахад дахин оролдоно.
            val stripColor = if (readerReady) Color(0xFF059669) else Color(0xFFD97706)
            Surface(
                color = stripColor.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .let { m -> if (!readerReady) m.clickable { onRetryReader() } else m },
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📡", fontSize = 14.sp)
                    Spacer(Modifier.height(0.dp))
                    Text(
                        if (readerReady) "  Уншигч: бэлэн · $outputPower dBm"
                        else "  Уншигч: $readerStatus (дарж дахин оролдоно)",
                        style = MaterialTheme.typography.bodySmall,
                        color = stripColor,
                        maxLines = 2,
                    )
                }
            }
            // Офлайн буферийн индикатор — дараалалд багц байгаа үед л.
            if (offlineCount > 0) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = Color(0xFFD97706).copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("📴", fontSize = 14.sp)
                        Text(
                            "  Офлайн буфер: $offlineCount багц — сүлжээ сэргэхэд автоматаар илгээгдэнэ",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD97706),
                            maxLines = 2,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tiles, key = { it.key }) { tile ->
                    // Тогтмол өндөр — subtitle-тэй/гүй нүднүүд яг ижил хэмжээтэй.
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .alpha(if (tile.enabled) 1f else 0.45f)
                            .let { m -> if (tile.enabled) m.clickable { onOpen(tile.key) } else m },
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(tile.emoji, fontSize = 30.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(tile.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            tile.subtitle?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
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
