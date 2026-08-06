package com.epcbc.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleDot
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.X
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epcbc.core.EpcDecoder
import com.epcbc.core.MatchEngine
import com.epcbc.data.PackingListReader
import kotlinx.coroutines.delay

/**
 * Box-based overlay (not a Dialog) that shows a single SKU's read state and lets the
 * user actively look for missing tags. While this overlay is on screen:
 *
 *   - A hardware Gen2 Select filter is applied so only tags matching `filterPrefix`
 *     are physically picked up by the antenna (set via `onFilterChange`, debounced 300ms).
 *   - The C5 trigger still routes through the Activity (dispatchKeyEvent), so pressing
 *     it adds new matching tags live to the list below.
 *
 * Using a Box rather than a Dialog keeps key events in the Activity's main window —
 * Compose's Dialog creates a separate window which swallows trigger key events.
 */
@Composable
fun SkuDetailOverlay(
    item: PackingListReader.PackingItem,
    allScannedEpcs: List<String>,
    isScanning: Boolean,
    onToggleScan: () -> Unit,
    onFilterChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Live-derive EPCs for this SKU from the master scanned list (shared matcher).
    val epcs: List<String> = remember(allScannedEpcs.toList(), item.barcode) {
        MatchEngine.groupByBarcode(allScannedEpcs)[MatchEngine.key(item.barcode)]?.toList() ?: emptyList()
    }

    // Auto-derive a prefix from the first matched EPC.
    // Угтварыг EPC-ийн БҮТЦЭЭР (серийн өмнөх хэсэг) автоматаар — гар
    // тохиргооны урт серийн битүүдийг давхар шүүж зарим тагийг алгасдаг
    // байсан (2026-08-03 зассан). Гараар засах боломж хэвээр.
    val autoPrefix: String = remember(epcs) {
        epcs.firstOrNull()?.let { EpcDecoder.productPrefix(it) } ?: ""
    }

    var filterPrefix by remember(autoPrefix) { mutableStateOf(autoPrefix) }

    // Debounce filter changes 300ms before applying to the hardware antenna.
    LaunchedEffect(filterPrefix) {
        delay(300)
        onFilterChange(filterPrefix)
    }

    // What's actually visible.
    val displayed: List<String> = remember(allScannedEpcs.toList(), filterPrefix, epcs) {
        if (filterPrefix.isBlank()) {
            epcs
        } else {
            allScannedEpcs.filter { it.uppercase().startsWith(filterPrefix.uppercase()) }
        }
    }

    val read = epcs.size
    val qty = item.qty
    val fgColor = when {
        read == 0    -> Color(0xFF6B7280)
        read == qty  -> Color(0xFF14532D)
        read > qty   -> Color(0xFFC2410C)
        else         -> Color(0xFFC2410C)
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* swallow backdrop click */ }
                )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Title
                item.sku?.let {
                    Text(text = it, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF111827))
                }
                Text(
                    text = item.barcode,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color(0xFF111827)
                )
                item.name?.let {
                    Text(text = it, fontSize = 12.sp, color = Color(0xFF111827))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$read / $qty",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = fgColor
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = filterPrefix,
                    onValueChange = { filterPrefix = it.uppercase() },
                    label = { Text("Хайх prefix") },
                    placeholder = { Text("EPC-ийн эхний хэсэг...") },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color(0xFF111827)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (filterPrefix.isNotEmpty()) {
                            IconButton(onClick = { filterPrefix = "" }) {
                                Icon(Lucide.X, contentDescription = "Хаах", modifier = Modifier.size(18.dp), tint = Color(0xFF6B7280))
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Scanning state badge + filter explanation.
                val (badgeBg, badgeFg, badgeText) = when {
                    filterPrefix.isBlank() -> Triple(Color(0xFFF3F4F6), Color(0xFF6B7280), "Хязгаарлалтгүй")
                    isScanning -> Triple(Color(0xFFDCFCE7), Color(0xFF166534), "УНШИЖ БАЙНА")
                    else -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "ЗОГССОН")
                }
                val badgeIcon = when {
                    filterPrefix.isBlank() -> Lucide.Circle
                    isScanning -> Lucide.CircleDot
                    else -> Lucide.Pause
                }
                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(badgeIcon, contentDescription = null,
                                modifier = Modifier.size(14.dp), tint = badgeFg)
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = badgeText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeFg
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when {
                                filterPrefix.isBlank() ->
                                    "Prefix хоосон. Энэ SKU-д таарсан tag-уудыг харуулна."
                                isScanning ->
                                    "C5-аа эргэлдүүл. Зөвхөн энэ prefix-тэй tag-ууд орно."
                                else ->
                                    "Доорх Эхлүүлэх товч даран эсвэл гох даран хайлт эхлүүл."
                            },
                            fontSize = 11.sp,
                            color = Color(0xFF111827)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (filterPrefix.isBlank())
                        "Энэ SKU-н tag-ууд (${displayed.size}/$qty):"
                    else
                        "Prefix-тэй олдсон (${displayed.size}):",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF111827)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 240.dp).fillMaxWidth()) {
                    if (displayed.isEmpty()) {
                        item {
                            Text(
                                text = if (filterPrefix.isBlank())
                                    "Энэ барааны нэг ч tag уншигдаагүй"
                                else
                                    "Энэ prefix-тэй tag олдоогүй. Хайх товчоо даран дахин уншуул.",
                                color = Color(0xFF6B7280),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    } else {
                        items(displayed, key = { it }) { epc ->
                            val isBarcodeMatch = epc in epcs
                            Text(
                                text = epc,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (isBarcodeMatch) Color(0xFF111827) else Color(0xFFC2410C),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Хаах") }
                    Button(
                        onClick = onToggleScan,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isScanning) Color(0xFFDC2626) else Color(0xFF16A34A)
                        )
                    ) {
                        Icon(
                            if (isScanning) Lucide.Pause else Lucide.Play,
                            contentDescription = null, modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isScanning) "Зогсоох" else "Эхлүүлэх",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
