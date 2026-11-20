package de.mirranet.ondura.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Failure reported by the core, with an already translated message. */
class CoreException(message: String, val needsAuth: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Kotlin facade over the Rust core of Ondura Mail: typed models instead of
 * JSON, coroutines everywhere, and one event stream for the whole app.
 *
 * Create it once per process ([MailCore.create]) – the core owns the SQLite
 * store, the sync engine and its background tasks.
 */
class MailCore private constructor(private val native: OnduraMail) {

    private val _events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 256)

    /** Every event of the sync engine (new mail, folder updates, errors …). */
    val events: SharedFlow<CoreEvent> = _events.asSharedFlow()

    /** Starts the background sync. Safe to call more than once. */
    fun start() {
        native.start(object : EventListener {
            override fun onEvent(eventJson: String) {
                CoreEvent.parse(eventJson)?.let { _events.tryEmit(it) }
            }
        })
    }

    fun setLanguage(code: String) = native.setLanguage(code)

    val hasBuiltinGraphClientId: Boolean get() = native.hasBuiltinGraphClientId()

    // ------------------------------------------------------------ accounts

    suspend fun accounts(): List<Account> = io { decodeList(Account.serializer(), native.listAccounts()) }

    suspend fun statuses(): List<AccountStatus> = io { decodeList(AccountStatus.serializer(), native.accountStatuses()) }

    suspend fun discover(email: String): Discovery =
        call { OnduraJson.decodeFromString(Discovery.serializer(), native.discoverSettings(email)) }

    suspend fun addImapAccount(input: NewImapAccount): Account = call {
        val json = native.addImapAccount(OnduraJson.encodeToString(NewImapAccount.serializer(), input))
        OnduraJson.decodeFromString(Account.serializer(), json)
    }

    suspend fun addEwsAccount(input: NewEwsAccount): Account = call {
        val json = native.addEwsAccount(OnduraJson.encodeToString(NewEwsAccount.serializer(), input))
        OnduraJson.decodeFromString(Account.serializer(), json)
    }

    /** Microsoft sign-in in the browser; `openUrl` shows the sign-in page. */
    suspend fun addMicrosoftAccount(tenant: String = "common", openUrl: (String) -> Boolean): Account = call {
        val json = native.addGraphAccount(tenant, opener(openUrl))
        OnduraJson.decodeFromString(Account.serializer(), json)
    }

    suspend fun reauthMicrosoft(accountId: String, openUrl: (String) -> Boolean) =
        call { native.reauthGraph(accountId, opener(openUrl)) }

    suspend fun updatePassword(accountId: String, password: String) = call { native.updatePassword(accountId, password) }

    suspend fun updateAccount(accountId: String, update: AccountUpdate): Account = io {
        val json = native.updateAccount(accountId, OnduraJson.encodeToString(AccountUpdate.serializer(), update))
        OnduraJson.decodeFromString(Account.serializer(), json)
    }

    suspend fun removeAccount(accountId: String) = io { native.removeAccount(accountId) }

    suspend fun probeCertificate(host: String, port: Int, mode: String): CertificateInfo = call {
        OnduraJson.decodeFromString(CertificateInfo.serializer(), native.probeCertificate(host, port.toUShort(), mode))
    }

    suspend fun setCertificateTrust(fingerprint: String, trusted: Boolean) = io { native.setCertificateTrust(fingerprint, trusted) }

    // ------------------------------------------------------------ folders and messages

    suspend fun folders(accountId: String): List<Folder> = io { decodeList(Folder.serializer(), native.listFolders(accountId)) }

    suspend fun messages(folderId: String, offset: Int, limit: Int, focused: Boolean?): List<MessageSummary> = io {
        decodeList(MessageSummary.serializer(), native.listMessages(folderId, offset.toUInt(), limit.toUInt(), focused))
    }

    /** Unread counts of "Focused" and "Other". */
    suspend fun focusUnread(folderId: String): Pair<Int, Int> = io {
        val counts = native.focusUnread(folderId)
        (counts.getOrNull(0)?.toInt() ?: 0) to (counts.getOrNull(1)?.toInt() ?: 0)
    }

    suspend fun setFocused(messageId: String, focused: Boolean, always: Boolean) = io { native.setFocused(messageId, focused, always) }

    suspend fun flagged(limit: Int = 200): List<MessageSummary> = io { decodeList(MessageSummary.serializer(), native.flaggedMessages(limit.toUInt())) }

    suspend fun conversation(messageId: String): List<MessageSummary> = io { decodeList(MessageSummary.serializer(), native.conversation(messageId)) }

    /** Loads older messages from the server; returns how many were added. */
    suspend fun loadOlder(folderId: String, limit: Int = 50): Int = call { native.loadOlder(folderId, limit.toUInt()).toInt() }

    suspend fun searchLocal(query: String, accountId: String? = null, limit: Int = 100): List<MessageSummary> = io {
        decodeList(MessageSummary.serializer(), native.searchLocal(query, accountId, limit.toUInt()))
    }

    suspend fun searchServer(query: String, limit: Int = 50): List<MessageSummary> = call {
        decodeList(MessageSummary.serializer(), native.searchServer(query, limit.toUInt()))
    }

    suspend fun message(messageId: String): MessageDetail = call {
        OnduraJson.decodeFromString(MessageDetail.serializer(), native.getMessage(messageId))
    }

    suspend fun saveAttachment(messageId: String, attachmentId: String, target: File) =
        call { native.saveAttachment(messageId, attachmentId, target.absolutePath) }

    suspend fun setRead(messageId: String, read: Boolean) = call { native.setRead(messageId, read) }
    suspend fun setFlagged(messageId: String, flagged: Boolean) = call { native.setFlagged(messageId, flagged) }
    suspend fun setPinned(messageId: String, pinned: Boolean) = io { native.setPinned(messageId, pinned) }
    suspend fun move(messageId: String, targetFolderId: String) = call { native.moveMessage(messageId, targetFolderId) }
    suspend fun delete(messageId: String) = call { native.deleteMessage(messageId) }
    suspend fun archive(messageId: String) = call { native.archiveMessage(messageId) }
    suspend fun snooze(messageId: String, untilEpochSeconds: Long?) = call { native.snoozeMessage(messageId, untilEpochSeconds) }

    // ------------------------------------------------------------ sending

    suspend fun send(accountId: String, message: OutgoingMessage) =
        call { native.sendMessage(accountId, OnduraJson.encodeToString(OutgoingMessage.serializer(), message)) }

    suspend fun saveDraft(accountId: String, message: OutgoingMessage) =
        call { native.saveDraft(accountId, OnduraJson.encodeToString(OutgoingMessage.serializer(), message)) }

    suspend fun outbox(accountId: String): List<OutboxItem> = io { decodeList(OutboxItem.serializer(), native.outbox(accountId)) }

    suspend fun retryPending(accountId: String) = io { native.retryPending(accountId) }

    suspend fun discardPending(accountId: String, opId: Long) = io { native.discardPending(accountId, opId) }

    suspend fun suggestRecipients(query: String, limit: Int = 8): List<Address> = call {
        decodeList(Address.serializer(), native.suggestRecipients(query, limit.toUInt()))
    }

    // ------------------------------------------------------------ sync

    fun syncAccount(accountId: String) = native.syncAccount(accountId)

    fun syncFolder(folderId: String) = native.syncFolder(folderId)

    suspend fun syncAllNow(timeoutSeconds: Int) = call { native.syncAllNow(timeoutSeconds.toUInt()) }

    // ------------------------------------------------------------ settings

    suspend fun settings(): MobileSettings = io { OnduraJson.decodeFromString(MobileSettings.serializer(), native.getSettings()) }

    suspend fun saveSettings(settings: MobileSettings) = io { native.saveSettings(OnduraJson.encodeToString(MobileSettings.serializer(), settings)) }

    // ------------------------------------------------------------ helpers

    private fun opener(open: (String) -> Boolean) = object : UrlOpener {
        override fun openUrl(url: String): Boolean = open(url)
    }

    private fun <T> decodeList(serializer: kotlinx.serialization.KSerializer<T>, json: String): List<T> =
        OnduraJson.decodeFromString(ListSerializer(serializer), json)

    /** Blocking store access off the main thread. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { translate { block() } }

    /** Async core call (runs on the core's own runtime). */
    private suspend fun <T> call(block: suspend () -> T): T = translate { block() }

    private inline fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: OnduraException.Auth) {
        throw CoreException(e.reason, needsAuth = true, cause = e)
    } catch (e: OnduraException.Failed) {
        throw CoreException(e.reason, cause = e)
    }

    companion object {
        /**
         * Opens the store in the app's private files directory. `language`:
         * "de", "en" or "fr" for messages produced by the core.
         */
        fun create(context: Context, language: String): MailCore {
            val dir = File(context.filesDir, "ondura")
            val native = try {
                OnduraMail(dir.absolutePath, language, KeystoreVault(context.applicationContext))
            } catch (e: OnduraException.Auth) {
                throw CoreException(e.reason, true, e)
            } catch (e: OnduraException.Failed) {
                throw CoreException(e.reason, false, e)
            }
            return MailCore(native)
        }
    }
}
