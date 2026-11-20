package de.mirranet.ondura.mail.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.text.HtmlCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.mirranet.ondura.core.Account
import de.mirranet.ondura.core.Address
import de.mirranet.ondura.core.CoreException
import de.mirranet.ondura.core.MessageDetail
import de.mirranet.ondura.core.OutgoingAttachment
import de.mirranet.ondura.core.OutgoingMessage
import de.mirranet.ondura.mail.Format
import de.mirranet.ondura.mail.OnduraMailApp
import de.mirranet.ondura.mail.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD, DRAFT }

/** Content handed to Ondura by another app (share sheet, mailto link). */
data class ComposeRequest(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val attachments: List<Uri> = emptyList(),
) {
    companion object {
        /** `mailto:a@b.de?cc=c@d.de&subject=Hallo&body=...` */
        fun fromMailto(uri: Uri): ComposeRequest {
            val raw = uri.toString().removePrefix("mailto:")
            val addressPart = raw.substringBefore('?')
            val query = raw.substringAfter('?', "")
            val params = query.split('&').filter { it.contains('=') }.associate {
                it.substringBefore('=').lowercase() to Uri.decode(it.substringAfter('='))
            }
            fun list(s: String?) = s.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            return ComposeRequest(
                to = list(Uri.decode(addressPart)) + list(params["to"]),
                cc = list(params["cc"]),
                bcc = list(params["bcc"]),
                subject = params["subject"].orEmpty(),
                body = params["body"].orEmpty(),
            )
        }
    }
}

data class PendingAttachment(val name: String, val size: Long, val attachment: OutgoingAttachment)

data class ComposeState(
    val ready: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val accountId: String? = null,
    val to: List<Address> = emptyList(),
    val cc: List<Address> = emptyList(),
    val bcc: List<Address> = emptyList(),
    val showCc: Boolean = false,
    val subject: String = "",
    val body: String = "",
    val quotedHtml: String? = null,
    val quotedPreview: String? = null,
    val attachments: List<PendingAttachment> = emptyList(),
    val suggestions: List<Address> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val closed: Boolean = false,
    val highImportance: Boolean = false,
)

class ComposeViewModel(
    application: Application,
    private val mode: ComposeMode,
    private val refId: String?,
    private val preferredAccountId: String?,
    private val request: ComposeRequest?,
) : AndroidViewModel(application), ComposeActions {
    private val app = application as OnduraMailApp
    private val core = app.core
    private val _state = MutableStateFlow(ComposeState())
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    private var original: MessageDetail? = null
    private var draftId: String? = null
    private var dirty = false
    private var suggestJob: Job? = null

    init {
        viewModelScope.launch { prepare() }
    }

    private suspend fun prepare() {
        val accounts = runCatching { core.accounts() }.getOrDefault(emptyList())
        val ref = refId?.let { runCatching { core.message(it) }.getOrNull() }
        original = ref
        val accountId = ref?.summary?.accountId ?: preferredAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id
        val account = accounts.firstOrNull { it.id == accountId }
        val me = accounts.map { it.email.lowercase() }.toSet()
        val signature = account?.signature?.let { "\n\n" + html2text(it) } ?: ""

        var s = ComposeState(ready = true, accounts = accounts, accountId = accountId)
        when (mode) {
            ComposeMode.NEW -> {
                s = s.copy(body = (request?.body ?: "") + signature, subject = request?.subject ?: "")
                if (request != null) {
                    s = s.copy(
                        to = request.to.mapNotNull(::parseAddress),
                        cc = request.cc.mapNotNull(::parseAddress),
                        bcc = request.bcc.mapNotNull(::parseAddress),
                        showCc = request.cc.isNotEmpty() || request.bcc.isNotEmpty(),
                    )
                }
            }
            ComposeMode.REPLY, ComposeMode.REPLY_ALL -> if (ref != null) {
                val sum = ref.summary
                val fromMe = sum.from?.email?.lowercase() in me
                val to = when {
                    fromMe -> sum.to
                    mode == ComposeMode.REPLY_ALL -> listOfNotNull(sum.from) + sum.to.filter { it.email.lowercase() !in me }
                    else -> listOfNotNull(sum.from)
                }
                val cc = if (mode == ComposeMode.REPLY_ALL) sum.cc.filter { it.email.lowercase() !in me } else emptyList()
                s = s.copy(
                    to = to.distinctBy { it.email.lowercase() },
                    cc = cc,
                    showCc = cc.isNotEmpty(),
                    subject = prefixed(sum.subject, app.getString(R.string.prefix_reply)),
                    body = signature,
                    quotedHtml = Format.quotedBody(app, ref),
                    quotedPreview = preview(ref),
                )
            }
            ComposeMode.FORWARD -> if (ref != null) {
                s = s.copy(
                    subject = prefixed(ref.summary.subject, app.getString(R.string.prefix_forward)),
                    body = signature,
                    quotedHtml = Format.quotedBody(app, ref),
                    quotedPreview = preview(ref),
                    attachments = ref.body.attachments.filter { !it.isInline || it.contentId == null }.map {
                        PendingAttachment(it.name, it.size, OutgoingAttachment.FromMessage(ref.summary.id, it.id, it.name, it.contentType))
                    },
                )
            }
            ComposeMode.DRAFT -> if (ref != null) {
                draftId = ref.summary.id
                s = s.copy(
                    to = ref.summary.to,
                    cc = ref.summary.cc,
                    showCc = ref.summary.cc.isNotEmpty(),
                    subject = ref.summary.subject,
                    body = ref.body.html?.let(::html2text) ?: ref.body.text.orEmpty(),
                    attachments = ref.body.attachments.map {
                        PendingAttachment(it.name, it.size, OutgoingAttachment.FromMessage(ref.summary.id, it.id, it.name, it.contentType))
                    },
                )
            }
        }
        _state.value = s
        request?.attachments?.forEach { addAttachment(it) }
    }

    private fun preview(detail: MessageDetail): String {
        val text = detail.body.text ?: detail.body.html?.let(::html2text) ?: ""
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(4).joinToString("\n")
    }

    /** Adds "AW: " / "WG: " (per language) unless the subject has such a prefix already. */
    private fun prefixed(subject: String, prefix: String): String {
        val forward = mode == ComposeMode.FORWARD
        val known = if (forward) listOf("fw:", "fwd:", "wg:", "tr:") else listOf("re:", "aw:", "rép:", "rep:", "antw:")
        val lower = subject.trim().lowercase()
        return if (known.any { lower.startsWith(it) }) subject.trim() else "$prefix${subject.trim()}"
    }

    // ------------------------------------------------------------ editing

    override fun setAccount(id: String) {
        dirty = true
        _state.update { it.copy(accountId = id) }
    }

    override fun setSubject(v: String) {
        dirty = true
        _state.update { it.copy(subject = v) }
    }

    override fun setBody(v: String) {
        dirty = true
        _state.update { it.copy(body = v) }
    }

    override fun showCc() = _state.update { it.copy(showCc = true) }

    override fun toggleImportance() = _state.update { it.copy(highImportance = !it.highImportance) }

    override fun addRecipient(field: RecipientField, address: Address) {
        dirty = true
        _state.update {
            when (field) {
                RecipientField.TO -> it.copy(to = (it.to + address).distinctBy { a -> a.email.lowercase() }, suggestions = emptyList())
                RecipientField.CC -> it.copy(cc = (it.cc + address).distinctBy { a -> a.email.lowercase() }, suggestions = emptyList())
                RecipientField.BCC -> it.copy(bcc = (it.bcc + address).distinctBy { a -> a.email.lowercase() }, suggestions = emptyList())
            }
        }
    }

    override fun removeRecipient(field: RecipientField, address: Address) {
        dirty = true
        _state.update {
            when (field) {
                RecipientField.TO -> it.copy(to = it.to - address)
                RecipientField.CC -> it.copy(cc = it.cc - address)
                RecipientField.BCC -> it.copy(bcc = it.bcc - address)
            }
        }
    }

    override fun suggest(query: String) {
        suggestJob?.cancel()
        if (query.trim().length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            val found = runCatching { core.suggestRecipients(query.trim()) }.getOrDefault(emptyList())
            _state.update { it.copy(suggestions = found) }
        }
    }

    override fun clearSuggestions() = _state.update { it.copy(suggestions = emptyList()) }

    fun addAttachment(uri: Uri) {
        dirty = true
        viewModelScope.launch {
            try {
                val pending = withContext(Dispatchers.IO) { copyToCache(uri) }
                _state.update { it.copy(attachments = it.attachments + pending) }
            } catch (e: Exception) {
                _state.update { it.copy(error = app.getString(R.string.attachment_failed)) }
            }
        }
    }

    override fun removeAttachment(a: PendingAttachment) {
        dirty = true
        _state.update { it.copy(attachments = it.attachments - a) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun copyToCache(uri: Uri): PendingAttachment {
        val resolver = app.contentResolver
        var name = "attachment"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val safe = name.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").ifBlank { "attachment" }
        val dir = File(app.cacheDir, "outgoing/${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, safe)
        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("cannot read $uri")
        return PendingAttachment(safe, if (size >= 0) size else file.length(), OutgoingAttachment.File(file.absolutePath))
    }

    // ------------------------------------------------------------ sending

    private fun build(): OutgoingMessage? {
        val s = _state.value
        val account = s.accounts.firstOrNull { it.id == s.accountId } ?: return null
        val html = Format.textToHtml(s.body) + (s.quotedHtml?.let { "<br><br>$it" } ?: "")
        val inReplyTo = if (mode == ComposeMode.REPLY || mode == ComposeMode.REPLY_ALL) original?.summary?.internetMessageId else null
        return OutgoingMessage(
            to = s.to,
            cc = s.cc,
            bcc = s.bcc,
            subject = s.subject,
            bodyHtml = html,
            inReplyTo = inReplyTo,
            attachments = s.attachments.map { it.attachment },
            draftId = draftId,
            importance = if (s.highImportance) "high" else "normal",
            from = Address(account.displayName, account.email),
        )
    }

    override fun send() {
        val s = _state.value
        if (s.to.isEmpty() && s.cc.isEmpty() && s.bcc.isEmpty()) {
            _state.update { it.copy(error = app.getString(R.string.need_recipient)) }
            return
        }
        val message = build() ?: return
        val accountId = s.accountId ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                core.send(accountId, message)
                dirty = false
                _state.update { it.copy(busy = false, closed = true) }
            } catch (e: CoreException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    /** Saves a draft when something was written; returns whether one was saved. */
    fun saveDraftIfNeeded(onDone: (Boolean) -> Unit) {
        val s = _state.value
        val hasContent = s.to.isNotEmpty() || s.subject.isNotBlank() || s.body.isNotBlank() && s.body.trim() != signatureOnly()
        if (!dirty || !hasContent) {
            onDone(false)
            return
        }
        val message = build()
        val accountId = s.accountId
        if (message == null || accountId == null) {
            onDone(false)
            return
        }
        // The app scope: the screen is closing.
        app.appScope.launch {
            val ok = runCatching { core.saveDraft(accountId, message) }.isSuccess
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    private fun signatureOnly(): String {
        val s = _state.value
        return s.accounts.firstOrNull { it.id == s.accountId }?.signature?.let { html2text(it).trim() } ?: ""
    }
}

enum class RecipientField { TO, CC, BCC }

fun html2text(html: String): String =
    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

/** "Name <a@b.de>", "a@b.de" → Address; `null` if there is no valid address. */
fun parseAddress(text: String): Address? {
    val t = text.trim().trim(',', ';').trim()
    val match = Regex("^(.*?)<([^<>\\s]+@[^<>\\s]+)>$").find(t)
    val (name, email) = if (match != null) {
        match.groupValues[1].trim().trim('"').ifBlank { null } to match.groupValues[2]
    } else {
        null to t
    }
    return if (Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) Address(name, email) else null
}
