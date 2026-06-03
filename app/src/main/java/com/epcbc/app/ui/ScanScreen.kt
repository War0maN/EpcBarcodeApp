package com.epcbc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epcbc.app.BuildConfig
import com.epcbc.data.PackingListReader

/**
 * Top-level scan screen. Hosts the header (title + version + settings/finder buttons),
 * status text, packing list import card, primary scan toggle, and either the match
 * view (when a packing list is loaded) or a raw EPC list (when there isn't one).
 */
@Composable
fun ScanScreen(
    modifier: Modifier = Modifier,
    scannedEpcs: List<String>,
    readerReady: Boolean,
    isScanning: Boolean,
    status: String,
    packingList: List<PackingListReader.PackingItem>,
    packingListName: String?,
    packingListStatus: String?,
    outputPower: Int,
    continuousMode: Boolean,
    prefixLength: Int,
    onInitReader: () -> Unit,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onImportPackingList: () -> Unit,
    onClearPackingList: () -> Unit,
    onExport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFinder: () -> Unit,
    onSelectItem: (PackingListReader.PackingItem) -> Unit,
) {
    val packingListSize = packingList.size
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // ── Header ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "EPC → Barcode",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${outputPower}dBm · ${if (continuousMode) "Auto" else "Single"}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                onClick = onOpenFinder,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("🎯 Олох", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("⚙", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                status.startsWith("АЛДАА") -> Color.Red
                status.startsWith("АНХААР") -> Color(0xFFD97706)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── Packing list import card ─────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (packingListSize > 0) "Packing list: $packingListSize мөр" else "Packing list оруулаагүй",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                packingListName?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                packingListStatus?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("АЛДАА")) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(onClick = onImportPackingList) {
                Text(if (packingListSize > 0) "Дахин сонгох" else "Файл сонгох")
            }
            if (packingListSize > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                OutlinedButton(
                    onClick = onClearPackingList,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("✕", color = Color(0xFFDC2626))
                }
            }
        }

        if (packingListSize > 0 || scannedEpcs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2E75B6))
            ) {
                Text("📥 Excel рүү гаргах (CSV)", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Primary action button ────────────────────────────
        if (!readerReady) {
            Button(
                onClick = onInitReader,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E75B6))
            ) {
                Text("УНШИГЧ ЭХЛҮҮЛЭХ", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) Color(0xFFDC2626) else Color(0xFF16A34A)
                    )
                ) {
                    Text(
                        text = if (isScanning) "ЗОГСООХ" else "ЭХЛҮҮЛЭХ",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onClear) {
                    Text("Цэвэрлэх")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Match view (with packing list) or raw EPC list ───
        if (packingListSize > 0) {
            MatchView(
                packingList = packingList,
                scannedEpcs = scannedEpcs,
                prefixLength = prefixLength,
                onSelectItem = onSelectItem,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Уншсан EPC:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = scannedEpcs.size.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(scannedEpcs, key = { it }) { epc ->
                    EpcRow(epc = epc)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}
