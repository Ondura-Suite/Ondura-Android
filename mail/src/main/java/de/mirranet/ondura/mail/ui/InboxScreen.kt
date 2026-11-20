@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.mirranet.ondura.mail.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.mirranet.ondura.core.Account
import de.mirranet.ondura.core.Folder
import de.mirranet.ondura.core.FolderRole
import de.mirranet.ondura.core.MessageSummary
import de.mirranet.ondura.mail.Format
import de.mirranet.ondura.mail.R
import de.mirranet.ondura.ui.Avatar
import de.mirranet.ondura.ui.OnduraColors
import de.mirranet.ondura.ui.parseHexColor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun InboxScreen(
    vm: MailViewModel,
    onOpenMessage: (MessageSummary, isDraft: Boolean) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onAddAccount: () -> Unit,
    onAccountSettings: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var actionsFor by remember { mutableStateOf<MessageSummary?>(null) }
    var moveTarget by remember { mutableStateOf<MessageSummary?>(null) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            MailDrawer(
                state = state,
                onSelect = { view ->
                    vm.select(view)
                    scope.launch { drawer.close() }
                },
                onSettings = {
                    scope.launch { drawer.close() }
                    onSettings()
                },
                onAddAccount = {
                    scope.launch { drawer.close() }
                    onAddAccount()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                InboxTopBar(
                    state = state,
                    onMenu = { scope.launch { drawer.open() } },
                    onSearch = onSearch,
                )
            },
            floatingActionButton = {
                if (state.accounts.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = onCompose,
                        icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        text = { Text(stringResource(R.string.new_mail)) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                val account = state.currentAccount
                val status = account?.let { state.statuses[it.id] }
                if (account != null && status?.needsAuth == true) {
                    AuthBanner(account) { onAccountSettings(account.id) }
                }
                state.focusedTab?.let { focused ->
                    FocusedSwitch(focused = focused, otherUnread = state.focusCounts.second, onSelect = vm::selectTab)
                }
                if (status?.syncing == true && state.messages.isEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    MessageList(
                        state = state,
                        onOpen = { m -> onOpenMessage(m, state.folder(m.folderId)?.role == FolderRole.DRAFTS) },
                        onLongPress = { actionsFor = it },
                        vm = vm,
                    )
                }
            }
        }
    }

    actionsFor?.let { message ->
        MessageActionsSheet(
            message = message,
            inInbox = state.focusedTab != null,
            focusedTab = state.focusedTab,
            onDismiss = { actionsFor = null },
            vm = vm,
            onMove = { moveTarget = message },
        )
    }
    moveTarget?.let { message ->
        FolderPickerDialog(
            folders = state.folders[message.accountId].orEmpty().filter { it.folder.id != message.folderId },
            onDismiss = { moveTarget = null },
            onPick = { folder ->
                moveTarget = null
                vm.move(message, folder)
            },
        )
    }
}

@Composable
private fun InboxTopBar(state: MailState, onMenu: () -> Unit, onSearch: () -> Unit) {
    val account = state.currentAccount
    val title = when (state.view) {
        MailView.Flagged -> stringResource(R.string.flagged)
        else -> state.currentFolder?.let { folderName(it) } ?: stringResource(R.string.inbox)
    }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onMenu) {
                if (account != null) {
                    Avatar(
                        name = account.displayName,
                        key = account.email,
                        size = 32.dp,
                        color = parseHexColor(account.color, MaterialTheme.colorScheme.primary),
                    )
                } else {
                    Icon(Icons.Outlined.Inbox, contentDescription = stringResource(R.string.folders))
                }
            }
        },
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                if (account != null) {
                    Text(
                        account.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun AuthBanner(account: Account, onFix: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.needs_sign_in, account.email),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onFix) { Text(stringResource(R.string.sign_in)) }
        }
    }
}

/** "Focused | Other" switch of the inbox. */
@Composable
private fun FocusedSwitch(focused: Boolean, otherUnread: Int, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(3.dp),
        ) {
            SwitchPill(stringResource(R.string.focused), selected = focused) { onSelect(true) }
            SwitchPill(
                if (otherUnread > 0) stringResource(R.string.other_count, otherUnread) else stringResource(R.string.other),
                selected = !focused,
            ) { onSelect(false) }
        }
    }
}

@Composable
private fun SwitchPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent, label = "pill")
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MessageList(
    state: MailState,
    onOpen: (MessageSummary) -> Unit,
    onLongPress: (MessageSummary) -> Unit,
    vm: MailViewModel,
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 10
        }
    }
    LaunchedEffect(nearEnd, state.messages.size) {
        if (nearEnd && state.messages.isNotEmpty()) vm.loadMore()
    }
    LaunchedEffect(state.view, state.focusedTab) {
        listState.scrollToItem(0)
    }
    if (state.messages.isEmpty()) {
        // Scrollable so pull-to-refresh also works on an empty folder.
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (state.view == MailView.Flagged) R.string.no_flagged else R.string.empty_folder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(state.messages, key = { it.id }) { message ->
            SwipeableRow(
                message = message,
                onArchive = { vm.archive(message) },
                onDelete = { vm.delete(message) },
            ) {
                MessageRow(
                    message = message,
                    showAccount = state.view == MailView.Flagged && state.accounts.size > 1,
                    accountEmail = state.account(message.accountId)?.email,
                    onClick = { onOpen(message) },
                    onLongClick = { onLongPress(message) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(start = 72.dp))
        }
        if (state.loadingMore) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    }
}

@Composable
private fun SwipeableRow(
    message: MessageSummary,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onArchive(); true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete(); true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = dismiss,
        backgroundContent = {
            val direction = dismiss.dismissDirection
            val (color, icon, align) = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(OnduraColors.Success, Icons.Outlined.Archive, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(OnduraColors.Danger, Icons.Outlined.Delete, Alignment.CenterEnd)
                else -> Triple(Color.Transparent, null, Alignment.Center)
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp), contentAlignment = align) {
                if (icon != null) Icon(icon, contentDescription = null, tint = Color.White)
            }
        },
    ) {
        content()
    }
}

@Composable
fun MessageRow(
    message: MessageSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showAccount: Boolean = false,
    accountEmail: String? = null,
) {
    val context = LocalContext.current
    val unread = !message.isRead
    val sender = message.from?.display ?: stringResource(R.string.no_sender)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 0.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            // Unread marker, as in Outlook.
            Box(
                Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(if (unread) MaterialTheme.colorScheme.primary else Color.Transparent),
            )
            Spacer(Modifier.width(12.dp))
            Avatar(name = sender, key = message.from?.email ?: sender, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sender,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (message.pinned) {
                        Icon(Icons.Outlined.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        Format.listDate(context, message.instant),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.importance == "high") {
                        Icon(Icons.Outlined.PriorityHigh, null, Modifier.size(14.dp), tint = OnduraColors.Danger)
                    }
                    Text(
                        message.subject.ifBlank { stringResource(R.string.no_subject) },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (message.hasAttachments) {
                        Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (message.isFlagged) {
                        Icon(Icons.Filled.Flag, null, Modifier.size(16.dp), tint = OnduraColors.Flag)
                    }
                }
                if (message.preview.isNotBlank()) {
                    Text(
                        message.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showAccount && accountEmail != null) {
                    Text(accountEmail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- drawer

@Composable
private fun MailDrawer(
    state: MailState,
    onSelect: (MailView) -> Unit,
    onSettings: () -> Unit,
    onAddAccount: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            item {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.flagged)) },
                    icon = { Icon(Icons.Outlined.Flag, null) },
                    selected = state.view == MailView.Flagged,
                    onClick = { onSelect(MailView.Flagged) },
                )
            }
            state.accounts.forEach { account ->
                item(key = "account-" + account.id) {
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        AccountHeader(account, state.statuses[account.id]?.error != null)
                    }
                }
                val entries = state.folders[account.id].orEmpty()
                items(entries, key = { "folder-" + it.folder.id }) { entry ->
                    val f = entry.folder
                    val selected = (state.view as? MailView.InFolder)?.folderId == f.id
                    NavigationDrawerItem(
                        label = {
                            Text(folderName(f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        icon = { Icon(folderIcon(f.role), null) },
                        badge = {
                            val count = if (f.role == FolderRole.DRAFTS) f.totalCount else f.unreadCount
                            if (count > 0 && f.role != FolderRole.SENT && f.role != FolderRole.TRASH) Text(count.toString())
                        },
                        selected = selected,
                        onClick = { onSelect(MailView.InFolder(account.id, f.id)) },
                        modifier = Modifier.padding(start = (entry.depth * 16).dp).height(48.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
            item {
                Column {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.add_account)) },
                    icon = { Icon(Icons.Outlined.PersonAdd, null) },
                    selected = false,
                    onClick = onAddAccount,
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings)) },
                    icon = { Icon(Icons.Outlined.Settings, null) },
                    selected = false,
                    onClick = onSettings,
                )
                Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun AccountHeader(account: Account, hasError: Boolean) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(account.displayName, account.email, size = 32.dp, color = parseHexColor(account.color, MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(account.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                account.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasError) Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
    }
}

fun folderIcon(role: String): ImageVector = when (role) {
    FolderRole.INBOX -> Icons.Outlined.Inbox
    FolderRole.DRAFTS -> Icons.Outlined.Drafts
    FolderRole.SENT -> Icons.AutoMirrored.Outlined.Send
    FolderRole.ARCHIVE -> Icons.Outlined.Archive
    FolderRole.JUNK -> Icons.Outlined.Report
    FolderRole.TRASH -> Icons.Outlined.Delete
    else -> Icons.Outlined.Folder
}

/** Well-known folders get the translated name; the rest keep the server's. */
@Composable
fun folderName(folder: Folder): String = when (folder.role) {
    FolderRole.INBOX -> stringResource(R.string.inbox)
    FolderRole.DRAFTS -> stringResource(R.string.drafts)
    FolderRole.SENT -> stringResource(R.string.sent)
    FolderRole.ARCHIVE -> stringResource(R.string.archive)
    FolderRole.JUNK -> stringResource(R.string.junk)
    FolderRole.TRASH -> stringResource(R.string.trash)
    else -> folder.name
}

// ---------------------------------------------------------------- sheets and dialogs

@Composable
private fun MessageActionsSheet(
    message: MessageSummary,
    inInbox: Boolean,
    focusedTab: Boolean?,
    onDismiss: () -> Unit,
    vm: MailViewModel,
    onMove: () -> Unit,
) {
    var snoozing by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                message.subject.ifBlank { stringResource(R.string.no_subject) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (!snoozing) {
                SheetAction(
                    if (message.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
                    stringResource(if (message.isRead) R.string.mark_unread else R.string.mark_read),
                ) { vm.setRead(message, !message.isRead); onDismiss() }
                SheetAction(Icons.Outlined.Flag, stringResource(if (message.isFlagged) R.string.unflag else R.string.flag)) {
                    vm.setFlagged(message, !message.isFlagged); onDismiss()
                }
                SheetAction(Icons.Outlined.PushPin, stringResource(if (message.pinned) R.string.unpin else R.string.pin)) {
                    vm.setPinned(message, !message.pinned); onDismiss()
                }
                SheetAction(Icons.Outlined.AccessTime, stringResource(R.string.snooze)) { snoozing = true }
                SheetAction(Icons.Outlined.Archive, stringResource(R.string.archive_action)) { vm.archive(message); onDismiss() }
                SheetAction(Icons.Outlined.DriveFileMove, stringResource(R.string.move)) { onDismiss(); onMove() }
                if (inInbox && focusedTab != null) {
                    SheetAction(Icons.Outlined.SwapVert, stringResource(if (focusedTab) R.string.move_to_other else R.string.move_to_focused)) {
                        vm.setFocused(message, !focusedTab); onDismiss()
                    }
                }
                SheetAction(Icons.Outlined.Delete, stringResource(R.string.delete)) { vm.delete(message); onDismiss() }
            } else {
                snoozeOptions().forEach { (label, until) ->
                    SheetAction(Icons.Outlined.AccessTime, stringResource(label)) {
                        vm.snooze(message, until); onDismiss()
                    }
                }
            }
        }
    }
}

/** Later today (in 3 h), tomorrow 8:00, next Monday 8:00. */
private fun snoozeOptions(): List<Pair<Int, Long>> {
    val zone = ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    val tomorrow = LocalDate.now(zone).plusDays(1).atTime(LocalTime.of(8, 0)).atZone(zone)
    var monday = LocalDate.now(zone).plusDays(1)
    while (monday.dayOfWeek != java.time.DayOfWeek.MONDAY) monday = monday.plusDays(1)
    return listOf(
        R.string.snooze_later to now.plusHours(3).toEpochSecond(),
        R.string.snooze_tomorrow to tomorrow.toEpochSecond(),
        R.string.snooze_next_week to monday.atTime(LocalTime.of(8, 0)).atZone(zone).toEpochSecond(),
    )
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun FolderPickerDialog(folders: List<FolderEntry>, onDismiss: () -> Unit, onPick: (Folder) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to)) },
        text = {
            LazyColumn {
                items(folders, key = { it.folder.id }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(entry.folder) }
                            .padding(start = (8 + entry.depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(folderIcon(entry.folder.role), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(folderName(entry.folder))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
