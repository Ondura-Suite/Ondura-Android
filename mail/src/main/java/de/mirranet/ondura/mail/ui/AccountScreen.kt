@file:OptIn(ExperimentalMaterial3Api::class)

package de.mirranet.ondura.mail.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.mirranet.ondura.core.AccountUpdate
import de.mirranet.ondura.core.CoreException
import de.mirranet.ondura.core.OutboxItem
import de.mirranet.ondura.mail.Browser
import de.mirranet.ondura.mail.Format
import de.mirranet.ondura.mail.OnduraMailApp
import de.mirranet.ondura.mail.R
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(accountId: String, mail: MailViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OnduraMailApp
    val state by mail.state.collectAsStateWithLifecycle()
    val account = state.account(accountId)
    val status = state.statuses[accountId]
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var name by remember(account?.id) { mutableStateOf(account?.displayName ?: "") }
    var signature by remember(account?.id) { mutableStateOf(account?.signature?.let(::html2text) ?: "") }
    var askPassword by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var outbox by remember { mutableStateOf<List<OutboxItem>>(emptyList()) }

    LaunchedEffect(accountId, status?.outbox, status?.pendingOps) {
        outbox = runCatching { app.core.outbox(accountId) }.getOrDefault(emptyList())
    }

    if (account == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    fun perform(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
            } catch (e: CoreException) {
                snackbar.showSnackbar(e.message ?: "")
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account.email) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(account.typeLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val last = status?.lastSync
            Text(
                if (last != null) {
                    stringResource(R.string.last_sync, DateUtils.getRelativeTimeSpanString(last * 1000).toString())
                } else {
                    stringResource(R.string.never_synced)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (account.isMicrosoft365) {
                OutlinedButton(onClick = {
                    perform {
                        try {
                            app.core.reauthMicrosoft(account.id) { url -> Browser.open(app, url) }
                        } finally {
                            Browser.returnToApp(app)
                        }
                        snackbar.showSnackbar(app.getString(R.string.signed_in))
                    }
                }, enabled = !busy) { Text(stringResource(R.string.sign_in_again)) }
            } else if (account.usesPassword) {
                OutlinedButton(onClick = { askPassword = true }, enabled = !busy) { Text(stringResource(R.string.update_password)) }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.setup_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = signature,
                onValueChange = { signature = it },
                label = { Text(stringResource(R.string.signature)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
            Button(onClick = {
                perform {
                    app.core.updateAccount(
                        account.id,
                        AccountUpdate(
                            displayName = name.ifBlank { account.displayName },
                            signature = signature.takeIf { it.isNotBlank() }?.let(Format::textToHtml),
                            color = account.color,
                        ),
                    )
                    mail.reloadAccounts()
                    snackbar.showSnackbar(app.getString(R.string.saved))
                }
            }, enabled = !busy) { Text(stringResource(R.string.save)) }

            if (outbox.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.outbox), style = MaterialTheme.typography.titleSmall)
                outbox.forEach { item ->
                    Column {
                        Text(item.message.subject.ifBlank { stringResource(R.string.no_subject) }, style = MaterialTheme.typography.bodyMedium)
                        item.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { perform { app.core.retryPending(account.id) } }) { Text(stringResource(R.string.retry)) }
                            TextButton(onClick = {
                                perform {
                                    app.core.discardPending(account.id, item.opId)
                                    outbox = app.core.outbox(account.id)
                                }
                            }) { Text(stringResource(R.string.discard)) }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.remove_account)) }
        }
    }

    if (askPassword) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text(stringResource(R.string.update_password)) },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.setup_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askPassword = false
                    perform {
                        app.core.updatePassword(account.id, password)
                        snackbar.showSnackbar(app.getString(R.string.signed_in))
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { askPassword = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_account)) },
            text = { Text(stringResource(R.string.remove_account_confirm, account.email)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        app.core.removeAccount(account.id)
                        mail.reloadAccounts()
                        onBack()
                    }
                }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
