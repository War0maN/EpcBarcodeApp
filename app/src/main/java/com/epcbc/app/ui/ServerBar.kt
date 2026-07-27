package com.epcbc.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Серверийн төлвийн нарийхан мөр (скан дэлгэцийн дээр):
 * нэвтэрсэн имэйл + идэвхтэй ажил + Хүлээн авалт/Гарах товчнууд.
 * Офлайн горимд "Нэвтрэх" товч л харагдана.
 */
@Composable
fun ServerBar(
    loggedIn: Boolean,
    email: String?,
    activeJobNumber: String?,
    onOpenReceiving: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (loggedIn) {
            Text(
                email ?: "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            activeJobNumber?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onOpenReceiving) { Text("Хүлээн авалт") }
            TextButton(onClick = onLogout) { Text("Гарах") }
        } else {
            Text(
                "Офлайн горим",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onLogin) { Text("Нэвтрэх") }
        }
    }
}
