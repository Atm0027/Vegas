/**
 * Vegas: Settings → «Cuenta para reproducir». A Google session used only to play songs: YouTube
 * streams age-restricted tracks only to signed-in accounts. It never syncs playlists, likes or
 * history, and search, home and library keep working without it.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.ui.screens.settings

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.R
import com.metrolist.music.constants.PlaybackAccountAlwaysKey
import com.metrolist.music.constants.PlaybackAccountPremiumKey
import com.metrolist.music.utils.PlaybackAccountStore
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackAccountSettings(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by PlaybackAccountStore.session.collectAsState()
    val (premium, onPremiumChange) = rememberPreference(PlaybackAccountPremiumKey, false)
    val (always, onAlwaysChange) = rememberPreference(PlaybackAccountAlwaysKey, false)
    val signedIn = session != null

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.playback_account)) },
            navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(painterResource(R.drawable.arrow_back), null) } },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                stringResource(if (signedIn) R.string.playback_account_signed_in else R.string.playback_account_explain),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.playback_account_no_sync),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (signedIn) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.playback_account_always)) },
                    supportingContent = { Text(stringResource(R.string.playback_account_always_desc)) },
                    trailingContent = { Switch(checked = always, onCheckedChange = onAlwaysChange) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.playback_account_premium)) },
                    supportingContent = { Text(stringResource(R.string.playback_account_premium_desc)) },
                    trailingContent = { Switch(checked = premium, onCheckedChange = onPremiumChange) },
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        PlaybackAccountStore.clear(context)
                        context.safeDataStoreEdit { prefs ->
                            prefs.remove(PlaybackAccountPremiumKey)
                            prefs.remove(PlaybackAccountAlwaysKey)
                        }
                    }
                }) { Text(stringResource(R.string.playback_account_sign_out)) }
            } else {
                Button(onClick = { navController.navigate("settings/playback_account/login") }) {
                    Text(stringResource(R.string.playback_account_sign_in))
                }
            }
        }
    }
}

/** Google sign-in in a WebView; keeps only the YouTube Music cookie and visitorData, then clears the WebView. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackAccountLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    suspend fun capture(view: WebView): Boolean {
        repeat(60) {
            val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
            val hasAuth = runCatching { "SAPISID" in parseCookieString(cookie) }.getOrDefault(false)
            if (hasAuth) {
                val visitorData = CompletableDeferred<String?>()
                view.evaluateJavascript("window.yt&&window.yt.config_?window.yt.config_.VISITOR_DATA:null") { value ->
                    visitorData.complete(value?.trim('"')?.takeIf { it.isNotBlank() && it != "null" })
                }
                val pageVisitorData = withTimeoutOrNull(2_000) { visitorData.await() }
                PlaybackAccountStore.save(context, cookie, pageVisitorData)
                return true
            }
            delay(500)
        }
        return false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.playback_account_sign_in)) },
            navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(painterResource(R.drawable.arrow_back), null) } },
        )
        if (failed) {
            Text(
                stringResource(R.string.playback_account_sign_in_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (checking) CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webViewContext ->
                WebView(webViewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(
                                view: WebView,
                                url: String?,
                            ) {
                                val uri = url?.let(Uri::parse)
                                if (checking || uri?.scheme != "https" || uri.host != "music.youtube.com") return
                                checking = true
                                scope.launch {
                                    val ok = capture(view)
                                    // The session now lives only in Vegas' playback settings.
                                    CookieManager.getInstance().removeAllCookies(null)
                                    view.clearCache(true)
                                    if (ok) {
                                        navController.navigateUp()
                                    } else {
                                        failed = true
                                        checking = false
                                    }
                                }
                            }
                        }
                    loadUrl(LOGIN_URL)
                }
            },
        )
    }
}
