/**
 * Vegas desktop: the settings that make sense on a PC (the ones phase 3 will sync with the phone).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.alextorres.vegas.desktop.settings.AudioQualitySetting
import dev.alextorres.vegas.desktop.ui.Strings

@Composable
fun SettingsScreen() {
    val settings = LocalVegas.current.settings
    val prefs by settings.flow.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp).widthIn(max = 720.dp)) {
        Text(Strings.SETTINGS, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(Strings.AUDIO_QUALITY, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AudioQualitySetting.HIGH to Strings.QUALITY_HIGH, AudioQualitySetting.AUTO to Strings.QUALITY_AUTO, AudioQualitySetting.LOW to Strings.QUALITY_LOW)
                .forEach { (q, label) ->
                    FilterChip(selected = prefs.audioQuality == q, onClick = { settings.update { it.copy(audioQuality = q) } }, label = { Text(label) })
                }
        }
        Toggle(Strings.DYNAMIC_COLOR, prefs.dynamicColor) { v -> settings.update { it.copy(dynamicColor = v) } }
        Toggle(Strings.PURE_BLACK, prefs.pureBlack) { v -> settings.update { it.copy(pureBlack = v) } }
        Toggle(Strings.HIDE_EXPLICIT, prefs.hideExplicit) { v -> settings.update { it.copy(hideExplicit = v) } }
        Toggle(Strings.HIDE_VIDEO_SONGS, prefs.hideVideoSongs) { v -> settings.update { it.copy(hideVideoSongs = v) } }
        LocalVegas.current.sync?.let { SyncSettings(it) }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
