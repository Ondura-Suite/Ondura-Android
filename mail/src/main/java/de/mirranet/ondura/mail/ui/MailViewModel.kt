package de.mirranet.ondura.mail.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.mirranet.ondura.core.Account
import de.mirranet.ondura.core.AccountStatus
import de.mirranet.ondura.core.CoreEvent
import de.mirranet.ondura.core.CoreException
import de.mirranet.ondura.core.Folder
import de.mirranet.ondura.core.FolderRole
import de.mirranet.ondura.core.MessageSummary
import de.mirranet.ondura.mail.OnduraMailApp
import de.mirranet.ondura.mail.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the message list shows: a folder, or all flagged messages. */
sealed interface MailView {
    data class InFolder(val accountId: String, val folderId: String) : MailView
    data object Flagged : MailView
}

/** A folder with its depth in the tree, for the drawer. */
data class FolderEntry(val folder: Folder, val depth: Int)

data class MailState(
    val loaded: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val statuses: Map<String, AccountStatus> = emptyMap(),
    val folders: Map<String, List<FolderEntry>> = emptyMap(),
    val view: MailView? = null,
    val messages: List<MessageSummary> = emptyList(),
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    /** Inbox split into "Focused" / "Other"; `null` = not split. */
    val focusedTab: Boolean? = null,
    val focusCounts: Pair<Int, Int> = 0 to 0,
) {
    val currentFolder: Folder?
        get() = (view as? MailView.InFolder)?.let { v -> folders[v.accountId]?.firstOrNull { it.folder.id == v.folderId }?.folder }

    val currentAccount: Account?
        get() = (view as? MailView.InFolder)?.let { v -> accounts.firstOrNull { it.id == v.accountId } }

    fun folder(id: String): Folder? = folders.values.flatten().firstOrNull { it.folder.id == id }?.folder

    fun account(id: String): Account? = accounts.firstOrNull { it.id == id }
}

/**
 * State of the main screen (accounts, folders, message list), shared by the
 * screens of the activity so the list is still there when coming back.
 */
class MailViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OnduraMailApp
    private val core = app.core

    private val _state = MutableStateFlow(MailState())
    val state: StateFlow<MailState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Short messages for the snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var refreshTimeout: Job? = null

    init {
        viewModelScope.launch { reloadAccounts() }
        viewModelScope.launch {
            core.events.collect { onEvent(it) }
        }
        viewModelScope.launch {
            app.settings.collect { reloadMessages() }
        }
    }

    private suspend fun onEvent(event: CoreEvent) {
        when (event) {
            is CoreEvent.Status -> _state.update { it.copy(statuses = it.statuses + (event.status.accountId to event.status)) }
            is CoreEvent.FoldersChanged -> reloadFolders(event.accountId)
            is CoreEvent.FolderUpdated -> {
                val view = _state.value.view
                if (view is MailView.InFolder && view.folderId == event.folderId) {
                    reloadMessages()
                    stopRefreshing()
                } else if (view == MailView.Flagged) {
                    reloadMessages()
                }
                reloadFolders(event.accountId)
            }
            is CoreEvent.SyncFinished -> {
                reloadFolders(event.accountId)
                if ((_state.value.view as? MailView.InFolder)?.accountId == event.accountId) stopRefreshing()
            }
            is CoreEvent.Error -> {
                if ((_state.value.view as? MailView.InFolder)?.accountId == event.accountId && _state.value.refreshing) {
                    stopRefreshing()
                    _messages.tryEmit(event.message)
                }
            }
            is CoreEvent.MessageSent -> _messages.tryEmit(app.getString(R.string.message_sent))
            is CoreEvent.SendFailed -> _messages.tryEmit(app.getString(R.string.notification_send_failed, event.subject))
            else -> Unit
        }
    }

    suspend fun reloadAccounts() {
        val accounts = runCatching { core.accounts() }.getOrDefault(emptyList())
        val statuses = runCatching { core.statuses() }.getOrDefault(emptyList()).associateBy { it.accountId }
        val folders = accounts.associate { it.id to tree(runCatching { core.folders(it.id) }.getOrDefault(emptyList())) }
        _state.update { s ->
            val view = s.view?.takeIf { v -> v == MailView.Flagged || accounts.any { it.id == (v as MailView.InFolder).accountId } }
            s.copy(loaded = true, accounts = accounts, statuses = statuses, folders = folders, view = view)
        }
        if (_state.value.view == null) {
            accounts.firstOrNull()?.let { openInbox(it.id) }
        }
        if (accounts.isEmpty()) _state.update { it.copy(view = null, messages = emptyList()) }
    }

    private suspend fun reloadFolders(accountId: String) {
        val folders = runCatching { core.folders(accountId) }.getOrNull() ?: return
        _state.update { it.copy(folders = it.folders + (accountId to tree(folders))) }
        // First sync of a new account: the inbox exists only now.
        val view = _state.value.view
        if (view == null || (view is MailView.InFolder && view.accountId == accountId && view.folderId.isEmpty())) {
            openInbox(accountId)
        } else if (view is MailView.InFolder && view.folderId == _state.value.currentFolder?.id) {
            refreshFocusCounts()
        }
    }

    /** Folders in tree order: well-known folders first, then the rest by name. */
    private fun tree(folders: List<Folder>): List<FolderEntry> {
        val byParent = folders.groupBy { f -> f.parentId?.takeIf { p -> folders.any { it.id == p } } }
        val out = mutableListOf<FolderEntry>()
        fun walk(parent: String?, depth: Int) {
            val children = byParent[parent].orEmpty().sortedWith(
                compareBy<Folder>({ FolderRole.sortKey(it.role) }, { it.name.lowercase() }),
            )
            for (f in children) {
                out += FolderEntry(f, depth)
                walk(f.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }

    fun openInbox(accountId: String) {
        val inbox = _state.value.folders[accountId]?.firstOrNull { it.folder.role == FolderRole.INBOX }?.folder
        select(MailView.InFolder(accountId, inbox?.id ?: ""))
    }

    fun select(view: MailView) {
        val split = view is MailView.InFolder && app.settings.value.focusedInbox &&
            _state.value.folder(view.folderId)?.role == FolderRole.INBOX
        _state.update {
            it.copy(view = view, messages = emptyList(), endReached = false, focusedTab = if (split) true else null)
        }
        viewModelScope.launch {
            reloadMessages()
            if (view is MailView.InFolder && view.folderId.isNotEmpty()) core.syncFolder(view.folderId)
        }
    }

    fun selectTab(focused: Boolean) {
        _state.update { it.copy(focusedTab = focused, messages = emptyList(), endReached = false) }
        viewModelScope.launch { reloadMessages() }
    }

    private suspend fun refreshFocusCounts() {
        val view = _state.value.view as? MailView.InFolder ?: return
        if (_state.value.focusedTab == null) return
        val counts = runCatching { core.focusUnread(view.folderId) }.getOrNull() ?: return
        _state.update { it.copy(focusCounts = counts) }
    }

    suspend fun reloadMessages() {
        val s = _state.value
        val list = when (val view = s.view) {
            null -> emptyList()
            MailView.Flagged -> runCatching { core.flagged() }.getOrDefault(emptyList())
            is MailView.InFolder -> if (view.folderId.isEmpty()) emptyList() else {
                val count = maxOf(PAGE, s.messages.size)
                runCatching { core.messages(view.folderId, 0, count, s.focusedTab) }.getOrDefault(emptyList())
            }
        }
        _state.update { if (it.view == s.view && it.focusedTab == s.focusedTab) it.copy(messages = list) else it }
        refreshFocusCounts()
    }

    /** Next page from the store, or older messages from the server. */
    fun loadMore() {
        val s = _state.value
        val view = s.view as? MailView.InFolder ?: return
        if (s.loadingMore || s.endReached || view.folderId.isEmpty()) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            var more = runCatching { core.messages(view.folderId, s.messages.size, PAGE, s.focusedTab) }.getOrDefault(emptyList())
            var end = false
            if (more.isEmpty()) {
                val added = try {
                    core.loadOlder(view.folderId, PAGE)
                } catch (e: CoreException) {
                    _messages.tryEmit(e.message ?: "")
                    0
                }
                if (added == 0) end = true
                more = runCatching { core.messages(view.folderId, s.messages.size, PAGE, s.focusedTab) }.getOrDefault(emptyList())
            }
            _state.update {
                if (it.view != view) it.copy(loadingMore = false) else {
                    val known = it.messages.map { m -> m.id }.toHashSet()
                    it.copy(messages = it.messages + more.filter { m -> m.id !in known }, loadingMore = false, endReached = end)
                }
            }
        }
    }

    fun refresh() {
        when (val view = _state.value.view) {
            is MailView.InFolder -> {
                _state.update { it.copy(refreshing = true) }
                if (view.folderId.isEmpty()) core.syncAccount(view.accountId) else core.syncFolder(view.folderId)
                refreshTimeout?.cancel()
                refreshTimeout = viewModelScope.launch {
                    delay(30_000)
                    stopRefreshing()
                }
            }
            MailView.Flagged -> {
                _state.value.accounts.forEach { core.syncAccount(it.id) }
                viewModelScope.launch { reloadMessages() }
            }
            null -> Unit
        }
    }

    private fun stopRefreshing() {
        refreshTimeout?.cancel()
        _state.update { it.copy(refreshing = false) }
    }

    // ------------------------------------------------------------ message actions

    private fun patch(id: String, change: (MessageSummary) -> MessageSummary) {
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) change(it) else it }) }
    }

    private fun drop(id: String) {
        _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == id }) }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CoreException) {
                _messages.tryEmit(e.message ?: "")
                reloadMessages()
            }
        }
    }

    fun setRead(message: MessageSummary, read: Boolean) {
        patch(message.id) { it.copy(isRead = read) }
        act { core.setRead(message.id, read) }
    }

    fun setFlagged(message: MessageSummary, flagged: Boolean) {
        patch(message.id) { it.copy(isFlagged = flagged) }
        act {
            core.setFlagged(message.id, flagged)
            if (_state.value.view == MailView.Flagged) reloadMessages()
        }
    }

    fun setPinned(message: MessageSummary, pinned: Boolean) {
        act {
            core.setPinned(message.id, pinned)
            reloadMessages()
        }
    }

    fun delete(message: MessageSummary) {
        drop(message.id)
        act {
            core.delete(message.id)
            _messages.tryEmit(app.getString(R.string.deleted))
        }
    }

    fun archive(message: MessageSummary) {
        drop(message.id)
        act {
            core.archive(message.id)
            _messages.tryEmit(app.getString(R.string.archived))
        }
    }

    fun move(message: MessageSummary, target: Folder) {
        drop(message.id)
        act {
            core.move(message.id, target.id)
            _messages.tryEmit(app.getString(R.string.moved_to, target.name))
        }
    }

    /** Moves between "Focused" and "Other" (and keeps doing so for the sender). */
    fun setFocused(message: MessageSummary, focused: Boolean) {
        drop(message.id)
        act {
            core.setFocused(message.id, focused, always = true)
            refreshFocusCounts()
        }
    }

    fun snooze(message: MessageSummary, untilEpochSeconds: Long) {
        drop(message.id)
        act { core.snooze(message.id, untilEpochSeconds) }
    }

    fun notify(text: String) {
        _messages.tryEmit(text)
    }

    companion object {
        const val PAGE = 50
    }
}
