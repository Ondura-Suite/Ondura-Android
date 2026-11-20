@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.mirranet.ondura.mail.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Shortcut
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mirranet.ondura.core.AttachmentInfo
import de.mirranet.ondura.core.CoreException
import de.mirranet.ondura.core.MessageDetail
import de.mirranet.ondura.mail.Format
import de.mirranet.ondura.mail.OnduraMailApp
import de.mirranet.ondura.mail.R
import de.mirranet.ondura.ui.Avatar
import de.mirranet.ondura.ui.OnduraColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ReaderState(
    val detail: MessageDetail? = null,
    val error: String? = null,
    val allowRemote: Boolean = false,
    val remoteBlocked: Boolean = false,
    val page: String = "",
)

class ReaderViewModel(application: Application, private val messageId: String) : AndroidViewModel(application) {
    private val app = application as OnduraMailApp
    val core = app.core
    private val _state = MutableStateFlow(ReaderState(allowRemote = app.settings.value.loadRemoteImages))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                val detail = core.message(messageId)
                val page = buildMailPage(detail.body.html, detail.body.text, detail.body.attachments)
                _state.value = _state.value.copy(detail = detail, page = page, error = null)
                if (!detail.summary.isRead) {
                    runCatching { core.setRead(messageId, true) }
                }
            } catch (e: CoreException) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun allowRemote() {
        _state.value = _state.value.copy(allowRemote = true, remoteBlocked = false)
    }

    fun remoteBlocked() {
        if (!_state.value.allowRemote) _state.value = _state.value.copy(remoteBlocked = true)
    }

    /** Saves an attachment to the cache and returns the file to open or share. */
    suspend fun attachmentFile(attachment: AttachmentInfo): File {
        val dir = File(app.cacheDir, "attachments/${messageId.hashCode()}").apply { mkdirs() }
        val name = attachment.name.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.').ifBlank { "attachment" }
        val file = File(dir, name)
        if (!file.exists() || file.length() == 0L) core.saveAttachment(messageId, attachment.id, file)
        return file
    }
}

@Composable
fun ReaderScreen(
    messageId: String,
    mail: MailViewModel,
    onBack: () -> Unit,
    onReply: (String) -> Unit,
    onReplyAll: (String) -> Unit,
    onForward: (String) -> Unit,
    onMailto: (android.net.Uri) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ReaderViewModel = viewModel(key = "reader-$messageId") { ReaderViewModel(app, messageId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val mailState by mail.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val summary = state.detail?.summary
    // Flag/read state as currently in the list (changes made here show at once).
    val live = summary?.let { s -> mailState.messages.firstOrNull { it.id == s.id } } ?: summary

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (live != null) {
                        IconButton(onClick = { mail.archive(live); onBack() }) {
                            Icon(Icons.Outlined.Archive, stringResource(R.string.archive_action))
                        }
                        IconButton(onClick = { mail.delete(live); onBack() }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (live.isFlagged) R.string.unflag else R.string.flag)) },
                                leadingIcon = { Icon(Icons.Outlined.Flag, null) },
                                onClick = { menu = false; mail.setFlagged(live, !live.isFlagged) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mark_unread)) },
                                leadingIcon = { Icon(Icons.Outlined.MarkEmailUnread, null) },
                                onClick = { menu = false; mail.setRead(live, false); onBack() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.move)) },
                                leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) },
                                onClick = { menu = false; moving = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (summary != null) {
                BottomAppBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { onReply(summary.id) }) {
                            Icon(Icons.AutoMirrored.Outlined.Reply, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.reply))
                        }
                        TextButton(onClick = { onReplyAll(summary.id) }) {
                            Icon(Icons.AutoMirrored.Outlined.ReplyAll, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.reply_all))
                        }
                        TextButton(onClick = { onForward(summary.id) }) {
                            Icon(Icons.Outlined.Shortcut, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.forward))
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val detail = state.detail
        when {
            detail == null && state.error != null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                    TextButton(onClick = vm::load) { Text(stringResource(R.string.retry)) }
                }
            }
            detail == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                val s = detail.summary
                Text(
                    s.subject.ifBlank { stringResource(R.string.no_subject) },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    val sender = s.from?.display ?: stringResource(R.string.no_sender)
                    Avatar(sender, s.from?.email ?: sender, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sender, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (live?.isFlagged == true) Icon(Icons.Filled.Flag, null, tint = OnduraColors.Flag, modifier = Modifier.size(18.dp))
                        }
                        s.from?.email?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            stringResource(R.string.to_line, Format.addresses(s.to + s.cc)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(Format.fullDate(s.instant), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val files = detail.body.attachments.filter { !it.isInline || it.contentId == null }
                if (files.isNotEmpty()) {
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        files.forEach { a ->
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        try {
                                            openFile(context, vm.attachmentFile(a), a.contentType)
                                        } catch (e: CoreException) {
                                            snackbar.showSnackbar(e.message ?: "")
                                        } catch (e: ActivityNotFoundException) {
                                            snackbar.showSnackbar(context.getString(R.string.no_app_for_file))
                                        }
                                    }
                                },
                                label = { Text("${a.name} · ${Format.size(a.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
                if (state.remoteBlocked) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.allowRemote() },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.remote_blocked), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.load_images), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                MailWebView(
                    page = state.page,
                    messageId = s.id,
                    core = vm.core,
                    allowRemote = state.allowRemote,
                    onRemoteBlocked = vm::remoteBlocked,
                    onLink = { uri ->
                        if (uri.scheme == "mailto") onMailto(uri) else runCatching { context.startActivity(linkIntent(uri)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (moving && live != null) {
        FolderPickerDialog(
            folders = mailState.folders[live.accountId].orEmpty().filter { it.folder.id != live.folderId },
            onDismiss = { moving = false },
            onPick = { folder ->
                moving = false
                mail.move(live, folder)
                onBack()
            },
        )
    }
}

fun openFile(context: android.content.Context, file: File, contentType: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val type = contentType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: "application/octet-stream"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
