package com.epcbc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epcbc.core.MatchEngine
import com.epcbc.data.PackingListReader

/**
 * The "scan + packing list" view: top stats (matched/missing/over/orphan piece counts),
 * progress summary, and a scrollable list of packing rows that the user can tap to open
 * a SKU-detail overlay.
 *
 * State is derived from `scannedEpcs` and `packingList` on every recomposition — backed
 * by Compose snapshot observation, so new tags arriving in MainActivity flow through here
 * automatically.
 */
@Composable
fun MatchView(
    packingList: List<PackingListReader.PackingItem>,
    scannedEpcs: List<String>,
    prefixLength: Int,
    onSelectItem: (PackingListReader.PackingItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // One canonical match computation, recomputed when the scan list or packing list changes.
    val summary = remember(packingList, scannedEpcs.toList()) {
        MatchEngine.summarize(packingList, scannedEpcs)
    }
    val rows = summary.rows
    val matchedPieces = summary.matchedPieces
    val missingPieces = summary.missingPieces
    val overPieces = summary.overPieces
    val totalExpected = summary.totalExpected
    val orphanCount = summary.orphanCount

    Column(modifier = modifier) {
        // Progress summary
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Уншсан / Нийт:",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                text = "$matchedPieces / $totalExpected",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = if (matchedPieces == totalExpected) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary
            )
        }

        // Top stats — by piece count.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            StatBox(value = matchedPieces, label = "Бүрэн", color = Color(0xFF16A34A))
            StatBox(value = missingPieces, label = "Дутуу", color = Color(0xFFDC2626))
            StatBox(value = overPieces, label = "Илүү", color = Color(0xFFD97706))
            StatBox(value = orphanCount, label = "Орхигдсон", color = Color(0xFF6B7280))
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { it.item.barcode }) { row ->
                PackingRow(
                    item = row.item,
                    read = row.read,
                    qty = row.qty,
                    onClick = { onSelectItem(row.item) }
                )
                HorizontalDivider()
            }
        }
    }
}

/** A single labelled metric card (used in the top stats row of MatchView). */
@Composable
fun StatBox(value: Int, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = color
        )
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
