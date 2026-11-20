package de.mirranet.ondura.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Events of the sync engine (`SyncEvent` in Ondura-Core). */
sealed interface CoreEvent {
    data class SyncStarted(val accountId: String) : CoreEvent
    data class FoldersChanged(val accountId: String) : CoreEvent
    data class FolderUpdated(val accountId: String, val folderId: String) : CoreEvent
    data class SyncFinished(val accountId: String) : CoreEvent
    data class Error(val accountId: String, val message: String) : CoreEvent
    data class Status(val status: AccountStatus) : CoreEvent
    data class NewMail(
        val accountId: String,
        val folderId: String,
        val messageId: String,
        val count: Int,
        val from: String,
        val subject: String,
    ) : CoreEvent
    data class MessageSent(val accountId: String, val subject: String) : CoreEvent
    data class SendFailed(val accountId: String, val subject: String, val message: String) : CoreEvent

    companion object {
        fun parse(json: String): CoreEvent? = runCatching {
            val o = OnduraJson.parseToJsonElement(json).jsonObject
            fun s(key: String) = o[key]?.jsonPrimitive?.content ?: ""
            when (s("type")) {
                "syncStarted" -> SyncStarted(s("accountId"))
                "foldersChanged" -> FoldersChanged(s("accountId"))
                "folderUpdated" -> FolderUpdated(s("accountId"), s("folderId"))
                "syncFinished" -> SyncFinished(s("accountId"))
                "error" -> Error(s("accountId"), s("message"))
                "status" -> Status(OnduraJson.decodeFromJsonElement(AccountStatus.serializer(), o["status"] as JsonObject))
                "newMail" -> NewMail(
                    s("accountId"), s("folderId"), s("messageId"),
                    o["count"]?.jsonPrimitive?.int ?: 1, s("from"), s("subject"),
                )
                "messageSent" -> MessageSent(s("accountId"), s("subject"))
                "sendFailed" -> SendFailed(s("accountId"), s("subject"), s("message"))
                else -> null
            }
        }.getOrNull()
    }
}
