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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Geiger-style tag finder. The user types an EPC (full 24 chars or a prefix), taps
 * "Эхлэх", and the antenna is configured to physically ignore everything else. As the
 * target tag comes in range, RSSI gets converted to a 0-100% display and a colour bar.
 *
 * Stateless presentation — all the actual work (filter, scan, RSSI extraction) happens
 * in MainActivity via the callbacks.
 */
@Composable
fun FinderOverlay(
    targetEpc: String,
    onTargetChange: (String) -> Unit,
    /** Тооллогын дутуу хайх горимд: барааны нэр + үлдсэн тоо (амьд). */
    label: String? = null,
    remaining: Int? = null,
    active: Boolean,
    percent: Int,
    rssi: Int?,
    lastSeenMs: Long,
    message: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val fillColor = when {
        percent >= 80 -> Color(0xFF16A34A)  // green = very close
        percent >= 50 -> Color(0xFF65A30D)  // lime
        percent >= 25 -> Color(0xFFD97706)  // amber = warming up
        percent > 0   -> Color(0xFFDC2626)  // red = faint signal
        else          -> Color(0xFF6B7280)  // gray = no signal
    }
    val statusText = when {
        !active && targetEpc.isBlank() -> "EPC оруулаад 'Эхлэх' дар."
        !active -> "Бэлэн. 'Эхлэх' даран хайлт эхлүүл."
        rssi == null -> "Сигнал хүлээж байна... C5-аа эргэлдүүл."
        percent >= 80 -> "Маш ойр! Энд хайвал олдох ёстой."
        percent >= 50 -> "Ойртож байна."
        percent >= 25 -> "Чиглэл зөв шиг."
        else          -> "Сигнал сул. Чиглэлийг өөрчилж үз."
    }

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
                    onClick = {}
                )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (label != null) "Олох: $label" else "Tag олох",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF111827)
                )
                if (remaining != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (remaining > 0) "Дутуу үлдсэн: $remaining ширхэг — олдмогц шууд тоологдоно."
                               else "Бүгд олдлоо!",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (remaining > 0) Color(0xFFD97706) else Color(0xFF059669)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Хайх EPC-ээ оруулаад \"Эхлэх\" дар. C5-аа эргэлдүүлээд RSSI хувь дээшилж байгаа чиглэлд яв.",
                    fontSize = 11.sp,
                    color = Color(0xFF6B7280)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = targetEpc,
                    onValueChange = { onTargetChange(it.uppercase()) },
                    label = { Text("Хайх EPC (бүтэн эсвэл prefix)") },
                    placeholder = { Text("Жишээ: 303549A3C052DC4CCE41F3B0") },
                    singleLine = true,
                    enabled = !active,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color(0xFF111827)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (targetEpc.isNotEmpty() && !active) {
                            IconButton(onClick = { onTargetChange("") }) {
                                Icon(Lucide.X, contentDescription = "Хаах", modifier = Modifier.size(18.dp), tint = Color(0xFF6B7280))
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Big percentage display
                Text(
                    text = "$percent%",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = fillColor,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (rssi != null) "RSSI: $rssi dBm" else "Сигнал байхгүй",
                    fontSize = 12.sp,
                    color = Color(0xFF6B7280),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { percent / 100f },
                    color = fillColor,
                    trackColor = Color(0xFFE5E7EB),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                val displayText = message ?: statusText
                val isError = displayText.startsWith("АЛДАА")
                Surface(
                    color = if (isError) Color(0xFFFEE2E2) else Color(0xFFF3F4F6),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = displayText,
                        modifier = Modifier.padding(8.dp),
                        fontSize = 12.sp,
                        fontWeight = if (isError) FontWeight.Bold else FontWeight.Normal,
                        color = if (isError) Color(0xFFB91C1C) else Color(0xFF111827)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Хаах") }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (active) {
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) { Text("Зогсоох") }
                    } else {
                        Button(
                            onClick = onStart,
                            enabled = targetEpc.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                        ) { Text("Эхлэх") }
                    }
                }
            }
        }
    }
}
