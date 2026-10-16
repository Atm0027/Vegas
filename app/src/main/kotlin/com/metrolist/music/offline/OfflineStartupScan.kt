/**
 * Vega: after a backup restore, asks once for the audio read permission and recognises the files
 * already in Music/Vega, so restored downloaded playlists play offline without downloading again.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.metrolist.music.R
import com.metrolist.music.playback.DownloadUtil
import java.io.File

@Composable
fun OfflineStartupScan(downloadUtil: DownloadUtil) {
    val context = LocalContext.current
    val marker = File(context.filesDir, OfflineScanner.PENDING_SCAN_FILE)
    val scan = {
        marker.delete()
        downloadUtil.scanFolder { report ->
            if (report.linked > 0) {
                Toast.makeText(context, context.getString(R.string.scan_result, report.linked), Toast.LENGTH_LONG).show()
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else marker.delete()
    }
    LaunchedEffect(Unit) {
        if (!marker.exists()) return@LaunchedEffect
        val permission = downloadUtil.readPermission()
        if (permission == null || downloadUtil.hasReadPermission()) scan() else launcher.launch(permission)
    }
}
