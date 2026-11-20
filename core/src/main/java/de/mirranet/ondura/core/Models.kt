package de.mirranet.ondura.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime

// Kotlin mirror of `ondura_mail_core::model` (see Ondura-Core,
// crates/mail-core/src/model.rs). The Rust side serializes with serde in
// camelCase; unknown fields are ignored so the core can grow independently.

@OptIn(ExperimentalSerializationApi::class)
val OnduraJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
data class Address(
    val name: String? = null,
    val email: String,
) {
    /** Name if there is one, else the address. */
    val display: String get() = name?.takeIf { it.isNotBlank() } ?: email
}

@Serializable
data class Account(
    val id: String,
    val displayName: String,
    val email: String,
    /** `{ "type": "imap" | "graph" | "ews" | "dav", ...config }` */
    val kind: JsonObject,
    val color: String? = null,
    val signature: String? = null,
) {
    val type: String get() = kind["type"]?.jsonPrimitive?.content ?: ""
    val isMicrosoft365: Boolean get() = type == "graph"
    val usesPassword: Boolean get() = type == "imap" || type == "ews"
    val typeLabel: String
        get() = when (type) {
            "graph" -> "Microsoft 365"
            "ews" -> "Exchange"
            "imap" -> "IMAP"
            else -> type
        }
}

object FolderRole {
    const val INBOX = "inbox"
    const val DRAFTS = "drafts"
    const val SENT = "sent"
    const val ARCHIVE = "archive"
    const val JUNK = "junk"
    const val TRASH = "trash"
    const val OTHER = "other"

    fun sortKey(role: String): Int = when (role) {
        INBOX -> 0
        DRAFTS -> 1
        SENT -> 2
        ARCHIVE -> 3
        JUNK -> 4
        TRASH -> 5
        else -> 9
    }
}

@Serializable
data class Folder(
    val id: String,
    val accountId: String,
    val remoteId: String,
    val name: String,
    val parentId: String? = null,
    val role: String = FolderRole.OTHER,
    val unreadCount: Int = 0,
    val totalCount: Int = 0,
)

@Serializable
data class MessageSummary(
    val id: String,
    val threadKey: String = "",
    val accountId: String,
    val folderId: String,
    val remoteId: String = "",
    val subject: String = "",
    val from: Address? = null,
    val to: List<Address> = emptyList(),
    val cc: List<Address> = emptyList(),
    val date: String,
    val preview: String = "",
    val isRead: Boolean = true,
    val isFlagged: Boolean = false,
    val hasAttachments: Boolean = false,
    val importance: String = "normal",
    val internetMessageId: String? = null,
    val categories: List<String> = emptyList(),
    val pinned: Boolean = false,
    val snoozedUntil: String? = null,
    val focused: Boolean? = null,
) {
    val instant: Instant get() = parseInstant(date)
}

@Serializable
data class AttachmentInfo(
    val id: String,
    val name: String,
    val contentType: String = "application/octet-stream",
    val size: Long = 0,
    val isInline: Boolean = false,
    val contentId: String? = null,
)

@Serializable
data class MessageBody(
    val html: String? = null,
    val text: String? = null,
    val attachments: List<AttachmentInfo> = emptyList(),
    /** S/MIME status (`SecurityInfo`), shown as-is. */
    val security: JsonElement? = null,
)

@Serializable
data class MessageDetail(
    val summary: MessageSummary,
    val body: MessageBody,
)

/** `OutgoingAttachment` (serde tag "source"). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("source")
sealed class OutgoingAttachment {
    @Serializable
    @SerialName("file")
    data class File(val path: String) : OutgoingAttachment()

    @Serializable
    @SerialName("message")
    data class FromMessage(
        val messageId: String,
        val attachmentId: String,
        val name: String,
        val contentType: String,
    ) : OutgoingAttachment()
}

@Serializable
data class OutgoingMessage(
    val to: List<Address>,
    val cc: List<Address> = emptyList(),
    val bcc: List<Address> = emptyList(),
    val subject: String,
    val bodyHtml: String,
    val inReplyTo: String? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val draftId: String? = null,
    val importance: String = "normal",
    val from: Address? = null,
)

@Serializable
data class AccountStatus(
    val accountId: String,
    val syncing: Boolean = false,
    val lastSync: Long? = null,
    val error: String? = null,
    val needsAuth: Boolean = false,
    val pendingOps: Int = 0,
    val outbox: Int = 0,
)

@Serializable
data class OutboxItem(
    val opId: Long,
    val message: OutgoingMessage,
    val error: String? = null,
    val sendAt: Long? = null,
)

/** `Security` of a server connection. */
object Security {
    const val TLS = "tls"
    const val STARTTLS = "startTls"
}

@Serializable
data class ServerSetting(
    val host: String,
    val port: Int,
    val security: String = Security.TLS,
)

/** Result of the server autodiscovery. */
@Serializable
data class Discovery(
    val imap: ServerSetting? = null,
    val smtp: ServerSetting? = null,
    val username: String? = null,
    val source: String? = null,
    val provider: String? = null,
    /** "microsoft365" or "exchange" when that account type fits better. */
    val suggest: String? = null,
    val ewsUrl: String? = null,
)

@Serializable
data class ImapConfig(
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: String,
    val username: String,
)

@Serializable
data class EwsConfig(
    val url: String,
    val username: String,
)

@Serializable
data class NewImapAccount(
    val displayName: String,
    val email: String,
    val password: String,
    val config: ImapConfig,
)

@Serializable
data class NewEwsAccount(
    val displayName: String,
    val email: String,
    val password: String,
    val config: EwsConfig,
)

@Serializable
data class AccountUpdate(
    val displayName: String,
    val signature: String? = null,
    val color: String? = null,
)

@Serializable
data class CertificateInfo(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val problem: String? = null,
    val trusted: Boolean = false,
)

/** App settings of the Android apps (stored by the core). */
@Serializable
data class MobileSettings(
    val syncIntervalMinutes: Long = 15,
    val notifications: Boolean = true,
    val loadRemoteImages: Boolean = false,
    val focusedInbox: Boolean = true,
    /** "system", "light" or "dark". */
    val theme: String = "system",
    /** "" (system), "de", "en" or "fr". */
    val language: String = "",
)

fun parseInstant(text: String): Instant =
    runCatching { Instant.parse(text) }
        .recoverCatching { OffsetDateTime.parse(text).toInstant() }
        .getOrDefault(Instant.EPOCH)
