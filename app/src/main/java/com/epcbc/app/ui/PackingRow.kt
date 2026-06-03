package com.epcbc.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epcbc.data.PackingListReader

/**
 * One row in the packing-list match view: SKU + barcode + name on the left,
 * `read/qty` chip on the right. Background colour encodes status:
 *
 *   - gray   →  not yet scanned (0/N)
 *   - peach  →  partial         (X/N where X < N)
 *   - green  →  complete        (N/N)
 *   - yellow →  over-read       (X/N where X > N)
 *
 * Tapping the row opens the SKU-detail overlay.
 */
@Composable
fun PackingRow(
    item: PackingListReader.PackingItem,
    read: Int,
    qty: Int,
    onClick: () -> Unit
) {
    val (bgColor, fgColor, label) = when {
        read == 0      -> Triple(Color(0xFFF3F4F6), Color(0xFF6B7280), "0/$qty")           // soft gray
        read == qty    -> Triple(Color(0xFFBBF7D0), Color(0xFF14532D), "$read/$qty")       // mint green
        read > qty     -> Triple(Color(0xFFFEF08A), Color(0xFF713F12), "$read/$qty")       // soft yellow
        else           -> Triple(Color(0xFFFFEDD5), Color(0xFFC2410C), "$read/$qty")       // peach
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            item.sku?.let {
                Text(
                    text = it,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF111827)
                )
            }
            Text(
                text = item.barcode,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF111827)
            )
            item.name?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = Color(0xFF111827)
                )
            }
        }
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = fgColor
        )
    }
}
