@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.mirranet.ondura.mail.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mirranet.ondura.core.Address
import de.mirranet.ondura.mail.Format
import de.mirranet.ondura.mail.R
import de.mirranet.ondura.ui.Avatar
import de.mirranet.ondura.ui.OnduraColors

@Composable
fun ComposeScreen(
    mode: ComposeMode,
    refId: String?,
    preferredAccountId: String?,
    request: ComposeRequest?,
    onClose: (draftSaved: Boolean) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ComposeViewModel = viewModel(key = "compose-$mode-$refId-${request.hashCode()}") {
        ComposeViewModel(app, mode, refId, preferredAccountId, request)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(vm::addAttachment)
    }
    val close = { vm.saveDraftIfNeeded { saved -> onClose(saved) } }

    BackHandler { close() }
    LaunchedEffect(state.closed) {
        if (state.closed) onClose(false)
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }

    ComposeContent(
        mode = mode,
        state = state,
        actions = vm,
        snackbar = snackbar,
        onClose = close,
        onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
    )
}

/** What the compose screen can ask of its owner ([ComposeViewModel] in the app). */
interface ComposeActions {
    fun setAccount(id: String)
    fun setSubject(v: String)
    fun setBody(v: String)
    fun showCc()
    fun toggleImportance()
    fun send()
    fun addRecipient(field: RecipientField, address: Address)
    fun removeRecipient(field: RecipientField, address: Address)
    fun suggest(query: String)
    fun clearSuggestions()
    fun removeAttachment(a: PendingAttachment)
}

/** The compose screen without its view model (also used for the screenshots). */
@Composable
fun ComposeContent(
    mode: ComposeMode,
    state: ComposeState,
    actions: ComposeActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onClose: () -> Unit = {},
    onPickFiles: () -> Unit = {},
) {
    val vm = actions
    val close = onClose
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                },
                title = {
                    Text(
                        stringResource(
                            when (mode) {
                                ComposeMode.REPLY, ComposeMode.REPLY_ALL -> R.string.reply
                                ComposeMode.FORWARD -> R.string.forward
                                else -> R.string.new_mail
                            },
                        ),
                    )
                },
                actions = {
                    IconButton(onClick = vm::toggleImportance) {
                        Icon(
                            Icons.Outlined.PriorityHigh,
                            stringResource(R.string.high_importance),
                            tint = if (state.highImportance) OnduraColors.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onPickFiles) {
                        Icon(Icons.Outlined.AttachFile, stringResource(R.string.attach))
                    }
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::send, enabled = state.ready) {
                            Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.ready) {
            CircularProgressIndicator(Modifier.padding(padding).padding(32.dp))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.accounts.size > 1) {
                FromRow(state, vm::setAccount)
                Divider()
            }
            RecipientRow(
                label = stringResource(R.string.field_to),
                field = RecipientField.TO,
                recipients = state.to,
                suggestions = state.suggestions,
                vm = vm,
                trailing = if (!state.showCc) {
                    { TextButton(onClick = vm::showCc) { Text(stringResource(R.string.cc_bcc)) } }
                } else {
                    null
                },
            )
            Divider()
            if (state.showCc) {
                RecipientRow(stringResource(R.string.field_cc), RecipientField.CC, state.cc, state.suggestions, vm)
                Divider()
                RecipientRow(stringResource(R.string.field_bcc), RecipientField.BCC, state.bcc, state.suggestions, vm)
                Divider()
            }
            PlainField(
                value = state.subject,
                onValueChange = vm::setSubject,
                placeholder = stringResource(R.string.subject),
                singleLine = true,
            )
            Divider()
            if (state.attachments.isNotEmpty()) {
                FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.attachments.forEach { a ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text("${a.name} · ${Format.size(a.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp)) },
                            trailingIcon = {
                                Icon(Icons.Outlined.Close, stringResource(R.string.remove), Modifier.size(18.dp).clickable { vm.removeAttachment(a) })
                            },
                        )
                    }
                }
                Divider()
            }
            PlainField(
                value = state.body,
                onValueChange = vm::setBody,
                placeholder = stringResource(R.string.body_hint),
                singleLine = false,
                modifier = Modifier.heightIn(min = 240.dp),
            )
            state.quotedPreview?.let { preview ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.original_message), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

@Composable
private fun FromRow(state: ComposeState, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val account = state.accounts.firstOrNull { it.id == state.accountId }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.field_from), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 48.dp))
        Text(account?.email ?: "", modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ExpandMore, null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.accounts.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.email) },
                    onClick = {
                        open = false
                        onPick(a.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun RecipientRow(
    label: String,
    field: RecipientField,
    recipients: List<Address>,
    suggestions: List<Address>,
    vm: ComposeActions,
    trailing: (@Composable () -> Unit)? = null,
) {
    var input by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }

    fun commit(): Boolean {
        val address = parseAddress(input) ?: return false
        vm.addRecipient(field, address)
        input = ""
        return true
    }

    Column {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 48.dp))
            FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                recipients.forEach { a ->
                    InputChip(
                        selected = false,
                        onClick = { vm.removeRecipient(field, a) },
                        label = { Text(a.display, maxLines = 1) },
                        avatar = { Avatar(a.display, a.email, size = 22.dp) },
                        trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.remove), Modifier.size(16.dp)) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                TextField(
                    value = input,
                    onValueChange = { v ->
                        if (v.endsWith(",") || v.endsWith(";") || (v.endsWith(" ") && parseAddress(v) != null)) {
                            input = v.dropLast(1)
                            if (!commit()) input = v
                        } else {
                            input = v
                            vm.suggest(v)
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { commit() }),
                    colors = transparentFieldColors(),
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        .onFocusChanged {
                            if (focused && !it.isFocused) {
                                commit()
                                vm.clearSuggestions()
                            }
                            focused = it.isFocused
                        },
                )
            }
            trailing?.invoke()
        }
        if (focused && input.isNotBlank() && suggestions.isNotEmpty()) {
            Column(Modifier.padding(start = 64.dp, end = 16.dp, bottom = 8.dp)) {
                suggestions.forEach { a ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.addRecipient(field, a)
                                input = ""
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(a.display, a.email, size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(a.display, style = MaterialTheme.typography.bodyMedium)
                            if (a.name != null) {
                                Text(a.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        colors = transparentFieldColors(),
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
