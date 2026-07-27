package com.epcbc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Нэвтрэх дэлгэц — вебтэй (Chipmo Inventory) ИЖИЛ акаунт.
 * Нэвтэрсний дараа RLS/эрхийн бүх дүрэм автоматаар үйлчилнэ.
 */
@Composable
fun LoginScreen(
    busy: Boolean,
    error: String?,
    notConfigured: Boolean,
    onLogin: (email: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Chipmo Inventory", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text("Серверт нэвтрэх", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        if (notConfigured) {
            Text(
                "Supabase тохиргоо алга — local.properties дотор supabase.url, " +
                    "supabase.anonKey утгуудыг тавиад дахин build хийнэ үү.",
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Имэйл") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Нууц үг") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = { onLogin(email, password) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Нэвтрэх")
            }
        }
    }
}
