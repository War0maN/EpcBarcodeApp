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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.CircleUserRound
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import androidx.compose.ui.unit.sp

/**
 * Профайл — Unitel-маягийн цэвэр жагсаалт (2026-08-03 хэрэглэгчийн загвар):
 * мөр бүр тусдаа хүрээтэй карт + › сум, дарахад дэд дэлгэц рүү орно.
 * Нууц үг солих нь ТУСДАА дэлгэц — хуучин нууц үгээ баталгаажуулж байж
 * солино (дундын төхөөрөмж дээр чухал). Дэлгэцийн горим = энгийн унтраалга.
 */
@Composable
fun ProfileScreen(
    email: String?,
    loggedIn: Boolean,
    isDark: Boolean,
    passwordBusy: Boolean,
    passwordMessage: String?,
    onToggleDark: (Boolean) -> Unit,
    onChangePassword: (oldPass: String, newPass: String) -> Unit,
    onLogout: () -> Unit,
    onLogin: () -> Unit,
    onBack: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf("menu") }
    BackHandler(onBack = { if (page == "menu") onBack() else page = "menu" })

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (page) {
            "password" -> PasswordPage(
                busy = passwordBusy,
                message = passwordMessage,
                onSubmit = onChangePassword,
                onBack = { page = "menu" },
            )
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) {
                Icon(Lucide.ArrowLeft, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Нүүр")
            }
                    Text(
                        "Профайл",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Акаунтын карт
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.CircleUserRound, contentDescription = null, modifier = Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                if (loggedIn) (email ?: "") else "Нэвтрээгүй",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (loggedIn) "Chipmo Inventory сервер" else "Офлайн горим",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                if (loggedIn) {
                    ProfileRow(Lucide.KeyRound, "Нууц үг солих") { page = "password" }
                } else {
                    ProfileRow(Lucide.KeyRound, "Нэвтрэх") { onLogin() }
                }
                ProfileRow(Lucide.Moon, "Бараан горим", trailing = {
                    Switch(checked = isDark, onCheckedChange = onToggleDark)
                })
                ProfileRow(Lucide.FileText, "Үйлчилгээний нөхцөл", enabled = false, note = "удахгүй")
                ProfileRow(Lucide.Bot, "AI туслах", enabled = false, note = "удахгүй")

                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))

                if (loggedIn) {
                    TextButton(onClick = onLogout, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Гарах", color = Color(0xFFDC2626))
                    }
                }
                Text(
                    "Chipmo Scanner v1.2.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                )
            }
        }
    }
}

/** Unitel-маягийн нэг мөр: тусдаа хүрээтэй карт, emoji + нэр + › (эсвэл өөр trailing). */
@Composable
private fun ProfileRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    note: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .let { m -> if (enabled && onClick != null) m.clickable { onClick() } else m },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            when {
                trailing != null -> trailing()
                enabled && onClick != null -> Text(
                    "›",
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Нууц үг солих дэд дэлгэц: хуучин + шинэ + давтах. */
@Composable
private fun PasswordPage(
    busy: Boolean,
    message: String?,
    onSubmit: (oldPass: String, newPass: String) -> Unit,
    onBack: () -> Unit,
) {
    var oldPass by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Icon(Lucide.ArrowLeft, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Буцах")
            }
            Text(
                "Нууц үг солих",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = oldPass,
            onValueChange = { oldPass = it; localError = null },
            label = { Text("Хуучин нууц үг") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = new1,
            onValueChange = { new1 = it; localError = null },
            label = { Text("Шинэ нууц үг") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = new2,
            onValueChange = { new2 = it; localError = null },
            label = { Text("Шинэ нууц үг (давтах)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        (localError ?: message)?.let {
            Text(
                it,
                color = if (it == "Нууц үг солигдлоо.") Color(0xFF059669)
                        else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                when {
                    new1.length < 6 -> localError = "Шинэ нууц үг дор хаяж 6 тэмдэгт байх ёстой."
                    new1 != new2 -> localError = "Хоёр шинэ нууц үг таарахгүй байна."
                    else -> {
                        onSubmit(oldPass, new1)
                        oldPass = ""; new1 = ""; new2 = ""
                    }
                }
            },
            enabled = !busy && oldPass.isNotBlank() && new1.isNotBlank() && new2.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Солиж байна…" else "Нууц үг солих") }
    }
}
