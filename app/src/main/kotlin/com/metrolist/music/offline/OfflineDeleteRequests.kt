/**
 * Vegas: shows the system confirmation Android requires before deleting files that another
 * install of Vegas created, and forgets those downloads once the user accepts. It also asks once to
 * move the downloads the app made under its old name (Music/Vega) into Music/Vegas.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metrolist.music.playback.DownloadUtil

@Composable
fun OfflineDeleteRequests(downloadUtil: DownloadUtil) {
    var pending by remember { mutableStateOf<DownloadUtil.PendingDelete?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pending?.let { if (result.resultCode == Activity.RESULT_OK) downloadUtil.confirmDeleted(it.songIds) }
        pending = null
    }
    val moveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) downloadUtil.moveLegacyFolder()
    }
    LaunchedEffect(downloadUtil) {
        downloadUtil.moveRequests.collect { moveLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    LaunchedEffect(downloadUtil) {
        downloadUtil.deleteRequests.collect { request ->
            pending = request
            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }
}
