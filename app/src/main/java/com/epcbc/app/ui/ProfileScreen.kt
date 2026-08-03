package com.epcbc.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Профайл — нүүрний 👤 товчоор нээгдэнэ (2026-08-03 хүсэлт: имэйл/Гарах
 * нүүрнээс энд нүүсэн). Нэвтэрсэн хүний мэдээлэл, нууц үг солих, дэлгэцийн
 * горим; ирээдүйн байр: үйлчилгээний нөхцөл, AI туслах.
 */
@Composable
fun ProfileScreen(
    email: String?,
    loggedIn: Boolean,
    themeMode: String,
    passwordBusy: Boolean,
    passwordMessage: String?,
    onThemeChange: (String) -> Unit,
    onChangePassword: (String) -> Unit,
    onLogout: () -> Unit,
    onLogin: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Нүүр") }
                Text(
                    "Профайл",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }

            // Акаунт
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("👤", fontSize = 28.sp)
                    Spacer(Modifier.height(0.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            if (loggedIn) (email ?: "") else "Нэвтрээгүй (офлайн горим)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (loggedIn) "Chipmo Inventory сервер" else "Серверийн функцууд хаалттай",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (loggedIn) OutlinedButton(onClick = onLogout) { Text("Гарах") }
                    else Button(onClick = onLogin) { Text("Нэвтрэх") }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Нууц үг солих
            if (loggedIn) {
                Text("Нууц үг солих", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = pass1,
                    onValueChange = { pass1 = it; localError = null },
                    label = { Text("Шинэ нууц үг") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = pass2,
                    onValueChange = { pass2 = it; localError = null },
                    label = { Text("Шинэ нууц үг (давтах)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                (localError ?: passwordMessage)?.let {
                    Text(
                        it,
                        color = if (it == "Нууц үг солигдлоо.") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (pass1 != pass2) localError = "Хоёр нууц үг таарахгүй байна."
                        else {
                            onChangePassword(pass1)
                            pass1 = ""; pass2 = ""
                        }
                    },
                    enabled = !passwordBusy && pass1.isNotBlank(),
                ) { Text(if (passwordBusy) "Солиж байна…" else "Нууц үг солих") }

                Spacer(Modifier.height(16.dp))
            }

            // Дэлгэцийн горим
            Text("Дэлгэцийн горим", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row {
                listOf("system" to "Систем", "light" to "Цайвар", "dark" to "Бараан").forEach { (mode, label) ->
                    val selected = themeMode == mode
                    if (selected) {
                        Button(onClick = {}, modifier = Modifier.padding(end = 8.dp)) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { onThemeChange(mode) },
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text(label) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // Ирээдүйн байрууд
            listOf(
                "📄 Үйлчилгээний нөхцөл",
                "🤖 AI туслах",
            ).forEach { label ->
                Text(
                    "$label — удахгүй",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}
