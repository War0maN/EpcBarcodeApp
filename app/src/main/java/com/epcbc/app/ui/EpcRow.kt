package com.epcbc.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.epcbc.core.EpcDecoder

/**
 * One row in the raw EPC list (shown when no packing list is loaded):
 * the raw hex on top, the decoded GTIN-13 below in green (SGTIN-96) or gray (raw HEX).
 */
@Composable
fun EpcRow(epc: String) {
    val decoded: EpcDecoder.DecodeResult? = remember(epc) {
        try {
            EpcDecoder.decode(epc, allowRawHexFallback = true)
        } catch (_: Exception) {
            null
        }
    }
    Column(modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth()) {
        Text(
            text = epc,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (decoded != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val barcode = decoded.barcode ?: "—"
                val isSgtin = decoded.format == "SGTIN-96"
                Text(
                    text = "→ $barcode",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSgtin) Color(0xFF16A34A) else Color(0xFF6B7280)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isSgtin) "SGTIN-96 P${decoded.partition}" else decoded.format,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
