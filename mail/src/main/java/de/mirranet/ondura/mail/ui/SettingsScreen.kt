@file:OptIn(ExperimentalMaterial3Api::class)

package de.mirranet.ondura.mail.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.mirranet.ondura.core.MobileSettings
import de.mirranet.ondura.mail.BuildConfig
import de.mirranet.ondura.mail.OnduraMailApp
import de.mirranet.ondura.mail.R
import de.mirranet.ondura.ui.Avatar
import de.mirranet.ondura.ui.parseHexColor
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    mail: MailViewModel,
    onBack: () -> Unit,
    onAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OnduraMailApp
    val settings by app.settings.collectAsStateWithLifecycle()
    val state by mail.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun change(transform: (MobileSettings) -> MobileSettings) {
        scope.launch { runCatching { app.updateSettings(transform) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.accounts))
            state.accounts.forEach { account ->
                val status = state.statuses[account.id]
                Row(
                    Modifier.fillMaxWidth().clickable { onAccount(account.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(account.displayName, account.email, size = 40.dp, color = parseHexColor(account.color, MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account.email, style = MaterialTheme.typography.bodyLarge)
                        Text(account.typeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (status?.error != null || status?.needsAuth == true) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onAddAccount).padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PersonAdd, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.add_account), color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionTitle(stringResource(R.string.mail))
            SwitchRow(stringResource(R.string.setting_focused), stringResource(R.string.setting_focused_hint), settings.focusedInbox) { v ->
                change { it.copy(focusedInbox = v) }
            }
            SwitchRow(stringResource(R.string.setting_images), stringResource(R.string.setting_images_hint), settings.loadRemoteImages) { v ->
                change { it.copy(loadRemoteImages = v) }
            }
            SwitchRow(stringResource(R.string.setting_notifications), null, settings.notifications) { v ->
                change { it.copy(notifications = v) }
            }
            ChoiceRow(
                title = stringResource(R.string.setting_sync),
                options = listOf(15L, 30L, 60L, 120L, 240L).map { it to stringResource(R.string.minutes, it) },
                selected = settings.syncIntervalMinutes,
            ) { v -> change { it.copy(syncIntervalMinutes = v) } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionTitle(stringResource(R.string.appearance))
            ChoiceRow(
                title = stringResource(R.string.setting_theme),
                options = listOf(
                    "system" to stringResource(R.string.theme_system),
                    "light" to stringResource(R.string.theme_light),
                    "dark" to stringResource(R.string.theme_dark),
                ),
                selected = settings.theme,
            ) { v -> change { it.copy(theme = v) } }
            ChoiceRow(
                title = stringResource(R.string.setting_language),
                options = listOf(
                    "" to stringResource(R.string.language_system),
                    "de" to "Deutsch",
                    "en" to "English",
                    "fr" to "Français",
                ),
                selected = settings.language,
            ) { v -> change { it.copy(language = v) } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionTitle(stringResource(R.string.about))
            InfoRow(stringResource(R.string.version), BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
            InfoRow(stringResource(R.string.website), "ondura.mirranet.de") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ondura.mirranet.de")))
            }
            InfoRow(stringResource(R.string.license), stringResource(R.string.license_text)) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Ondura-Suite/Ondura-Android")))
            }
            Spacer(Modifier.padding(16.dp))
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                options.firstOrNull { it.first == selected }?.second ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onSelect(value)
                })
            }
        }
    }
}

@Composable
fun InfoRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth()
    Column((if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
