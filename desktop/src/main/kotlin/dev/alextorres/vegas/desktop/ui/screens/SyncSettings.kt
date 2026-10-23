/**
 * Vegas desktop: «Vegas en el móvil» in Settings — show a pairing code, list paired phones, last sync.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alextorres.vegas.desktop.sync.SyncService
import dev.alextorres.vegas.desktop.ui.Strings
import kotlinx.coroutines.delay

@Composable
fun SyncSettings(sync: SyncService) {
    val active by sync.pairing.active.collectAsState()
    val devices by sync.devices.devices.collectAsState()
    val lastSync by sync.lastSync.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Text(Strings.PHONE_SYNC, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val code = active?.takeIf { now < it.expiresAtMs }
            if (code != null) {
                Text(Strings.PAIR_INSTRUCTIONS, style = MaterialTheme.typography.bodyMedium)
                Text(
                    code.code.chunked(3).joinToString(" "),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Strings.expiresIn((code.expiresAtMs - now) / 1000), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { sync.pairing.cancel() }) { Text(Strings.CANCEL) }
                }
            } else {
                Text(Strings.PAIR_EXPLAIN, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { sync.pairing.start() }, modifier = Modifier.padding(top = 12.dp)) { Text(Strings.PAIR_PHONE) }
            }
            devices.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            Strings.lastSync((lastSync ?: d.lastSyncMs)?.let { (now - it) / 1000 }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { sync.devices.remove(d.deviceId) }) { Text(Strings.UNPAIR) }
                }
            }
        }
    }
}
