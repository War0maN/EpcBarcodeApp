package com.epcbc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Modal settings dialog. Edits are local until the user taps "Хадгалах", which fires
 * `onApply(power, continuous, prefixLen, sound)` so MainActivity can persist them
 * via SharedPreferences and apply the new power to the reader.
 *
 * Uses Compose's `Dialog` (separate window) — that's fine here because settings don't
 * need the hardware trigger to keep flowing through.
 */
@Composable
fun SettingsDialog(
    currentPower: Int,
    currentMode: Boolean,
    currentPrefix: Int,
    currentSound: Boolean,
    onApply: (power: Int, continuous: Boolean, prefixLen: Int, sound: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var power by remember { mutableStateOf(currentPower) }
    var continuous by remember { mutableStateOf(currentMode) }
    var prefix by remember { mutableStateOf(currentPrefix) }
    var sound by remember { mutableStateOf(currentSound) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Тохиргоо", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(16.dp))

                // Output power slider
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Output power (dBm):", fontWeight = FontWeight.Bold)
                    Text(text = "$power dBm", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Text(text = "1 (ойр) — 30 (хол)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = power.toFloat(),
                    onValueChange = { power = it.toInt() },
                    valueRange = 1f..30f,
                    steps = 28
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Scan mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Scan горим", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (continuous) "Auto — товч даран нэг удаа эхэлж, дахин дартал уншина"
                                   else "Single — товч дарвал зөвхөн 1 ширхэг уншина",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = continuous, onCheckedChange = { continuous = it })
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Sound feedback toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Дуу feedback", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Шинэ tag уншигдах бүрд богино бипп гарна",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = sound, onCheckedChange = { sound = it })
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Prefix length slider
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "EPC prefix урт:", fontWeight = FontWeight.Bold)
                    Text(text = "$prefix орон", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = "Бараан дээр дарж дэлгэрэнгүйг харахад \"яг энэ бүтээгдэхүүн\" гэж танихад EPC-ийн эхний хэдэн орон ижил байх ёстойг тогтооно. Жишээ нь Levi's-д ихэвчлэн 20 орон ажилладаг. Багасгахад илүү олон tag нийтлэгт орно, ихэсгэхэд илүү нарийн хайна.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = prefix.toFloat(),
                    onValueChange = { prefix = it.toInt() },
                    valueRange = 8f..23f,
                    steps = 14
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text("Цуцлах") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onApply(power, continuous, prefix, sound) }) { Text("Хадгалах") }
                }
            }
        }
    }
}
