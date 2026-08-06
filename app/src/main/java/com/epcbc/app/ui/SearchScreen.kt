package com.epcbc.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Crosshair
import com.composables.icons.lucide.Lucide
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.epcbc.net.ProductsApi
import kotlinx.coroutines.launch

/**
 * Хайлт — grid-ийн бие даасан цонх (2026-08-02 тохирсон дизайн).
 * Хоёр зам, хоёулаа нэг Geiger хөдөлгүүрт (FinderOverlay) очно:
 *   1) Бараагаар: нэр/SKU/баркодоор серверээс хайж сонгоход барааны аль нэг
 *      EPC-ээс угтвар бүтээгээд Олох горим асна — тэр барааны БҮХ таг сонсогдоно.
 *   2) EPC/угтвараар: hex-ээ (бүтэн эсвэл хэсэгчлэн) шууд оруулна —
 *      хуучин "дуртай оронгоороо хайдаг" функц.
 * Уншсан таг ерөнхий урсгалдаа ордог тул идэвхтэй ажил байвал тоонд нь нэмэгдэнэ.
 */
@Composable
fun SearchScreen(
    loggedIn: Boolean,
    onBack: () -> Unit,
    onFind: (prefixHex: String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    val results = remember { mutableStateOf(listOf<ProductsApi.Product>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var epcInput by rememberSaveable { mutableStateOf("") }

    fun runSearch() {
        val q = query.trim()
        if (q.length < 2) {
            message = "Хайлтад дор хаяж 2 тэмдэгт."
            return
        }
        loading = true
        message = null
        scope.launch {
            try {
                results.value = ProductsApi.search(q)
                if (results.value.isEmpty()) message = "Олдсонгүй."
            } catch (e: Exception) {
                message = "Хайлт амжилтгүй: ${e.message ?: "холболт"}"
            } finally {
                loading = false
            }
        }
    }

    fun findProduct(p: ProductsApi.Product) {
        loading = true
        message = null
        scope.launch {
            try {
                val hex = ProductsApi.anyEpcHex(p.id)
                if (hex == null) {
                    message = "«${p.display}» бараанд бүртгэлтэй EPC алга — угтвар бүтээх боломжгүй."
                } else {
                    onFind(ProductsApi.productPrefix(hex))
                }
            } catch (e: Exception) {
                message = "EPC татахад алдаа: ${e.message ?: "холболт"}"
            } finally {
                loading = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Icon(Lucide.ArrowLeft, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Нүүр")
                }
                Text(
                    "Хайлт",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            // ---- 1. Бараагаар олох ----
            Text("Бараагаар олох", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            if (!loggedIn) {
                Text(
                    "Нэвтэрсэн үед ажиллана (серверээс хайдаг).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Нэр / SKU / баркод") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.height(0.dp))
                    Button(onClick = { runSearch() }, enabled = !loading, modifier = Modifier.padding(start = 8.dp)) {
                        Text(if (loading) "…" else "Хайх")
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(results.value, key = { it.id }) { p ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(enabled = !loading) { findProduct(p) },
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.display, fontWeight = FontWeight.Bold)
                                    Text(
                                        listOfNotNull(p.sku, p.gtin).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Lucide.Crosshair, contentDescription = null,
                                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Олох", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // ---- 2. EPC/угтвараар олох (хуучин чөлөөт функц) ----
            Text("EPC / угтвараар олох", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Бүтэн 24 орон = нэг ширхэг; богино угтвар = тохирох бүх таг сонсогдоно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = epcInput,
                    onValueChange = { epcInput = it },
                    label = { Text("hex (жишээ: 3035EBB7…)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        val clean = epcInput.trim().uppercase().replace(Regex("[^0-9A-F]"), "")
                        if (clean.length < 4) message = "Дор хаяж 4 hex орон оруулна уу."
                        else onFind(clean)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Lucide.Crosshair, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Олох")
                }
            }
        }
    }
}
