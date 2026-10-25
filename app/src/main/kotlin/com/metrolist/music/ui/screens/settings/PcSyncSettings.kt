/**
 * Vegas: Settings → «Vegas para PC». Pair with a PC found on the home wifi using the code it shows,
 * sync now, or unpair.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.sync.FoundPc
import com.metrolist.music.sync.PcSyncEntryPoint
import com.metrolist.music.sync.WrongCodeException
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcSyncSettings(navController: NavController) {
    val context = LocalContext.current
    val sync = remember { EntryPointAccessors.fromApplication(context.applicationContext, PcSyncEntryPoint::class.java).pcSync() }
    val scope = rememberCoroutineScope()
    val paired by sync.core.pairedPc.collectAsState()
    val lastSync by sync.core.lastSync.collectAsState()
    var pairingWith by remember { mutableStateOf<FoundPc?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.pc_sync)) },
            navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(painterResource(R.drawable.arrow_back), null) } },
        )
        Column(Modifier.padding(16.dp)) {
            val pc = paired
            if (pc != null) {
                Text(stringResource(R.string.pc_sync_paired_with, pc.name), style = MaterialTheme.typography.titleMedium)
                Text(
                    lastSync?.let { stringResource(R.string.pc_sync_last, android.text.format.DateUtils.getRelativeTimeSpanString(it).toString()) }
                        ?: stringResource(R.string.pc_sync_explain_paired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            message = if (sync.core.syncNow().isSuccess) context.getString(R.string.pc_sync_done) else context.getString(R.string.pc_sync_failed)
                            busy = false
                        }
                    }) { Text(stringResource(R.string.pc_sync_now)) }
                    Spacer(Modifier.size(12.dp))
                    OutlinedButton(onClick = { sync.unpair() }) { Text(stringResource(R.string.pc_sync_unpair)) }
                }
            } else {
                Text(stringResource(R.string.pc_sync_explain), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                val found by remember { sync.discover() }.collectAsState(emptyList())
                if (found.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.pc_sync_searching))
                    }
                }
                found.forEach { found ->
                    Text(
                        found.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { pairingWith = found }.padding(vertical = 14.dp),
                    )
                }
            }
            message?.let { Text(it, modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.primary) }
        }
    }

    pairingWith?.let { pc ->
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pairingWith = null },
            title = { Text(stringResource(R.string.pc_sync_enter_code, pc.name)) },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            },
            confirmButton = {
                TextButton(enabled = code.length == 6 && !busy, onClick = {
                    busy = true
                    scope.launch {
                        sync.pair(pc, code)
                            .onSuccess { message = context.getString(R.string.pc_sync_paired_with, it.name); pairingWith = null }
                            .onFailure { message = context.getString(if (it is WrongCodeException) R.string.pc_sync_wrong_code else R.string.pc_sync_failed) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.pc_sync_pair)) }
            },
            dismissButton = { TextButton(onClick = { pairingWith = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
