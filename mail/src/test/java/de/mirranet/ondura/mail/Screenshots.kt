package de.mirranet.ondura.mail

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mirranet.ondura.core.Account
import de.mirranet.ondura.core.AccountStatus
import de.mirranet.ondura.core.Address
import de.mirranet.ondura.core.AttachmentInfo
import de.mirranet.ondura.core.Folder
import de.mirranet.ondura.core.FolderRole
import de.mirranet.ondura.core.MessageBody
import de.mirranet.ondura.core.MessageDetail
import de.mirranet.ondura.core.MessageSummary
import de.mirranet.ondura.mail.ui.ComposeActions
import de.mirranet.ondura.mail.ui.ComposeContent
import de.mirranet.ondura.mail.ui.ComposeMode
import de.mirranet.ondura.mail.ui.ComposeState
import de.mirranet.ondura.mail.ui.FolderEntry
import de.mirranet.ondura.mail.ui.InboxActions
import de.mirranet.ondura.mail.ui.InboxContent
import de.mirranet.ondura.mail.ui.MailState
import de.mirranet.ondura.mail.ui.MailView
import de.mirranet.ondura.mail.ui.PendingAttachment
import de.mirranet.ondura.mail.ui.ReaderActions
import de.mirranet.ondura.mail.ui.ReaderContent
import de.mirranet.ondura.mail.ui.RecipientField
import de.mirranet.ondura.mail.ui.SetupWelcome
import de.mirranet.ondura.ui.OnduraTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Renders the main screens with sample data into `mail/build/screenshots`
 * (used for the README and the website). Run with
 * `./gradlew :mail:testDebugUnitTest --tests '*Screenshots*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS-w400dp-h860dp-xxhdpi")
class Screenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(name: String, theme: String = "light", content: @Composable () -> Unit) {
        compose.setContent {
            OnduraTheme(theme = theme) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
        // Draw the window ourselves (captureToImage waits for PixelCopy,
        // which Robolectric does not deliver).
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun inbox() = shoot("inbox") { InboxContent(state = Sample.inbox(), actions = Sample.NoInbox) }

    @Test
    fun inboxDark() = shoot("inbox-dark", theme = "dark") { InboxContent(state = Sample.inbox(), actions = Sample.NoInbox) }

    @Test
    fun folders() = shoot("folders") {
        InboxContent(state = Sample.inbox(), actions = Sample.NoInbox, drawer = rememberDrawerState(DrawerValue.Open))
    }

    @Test
    fun message() = shoot("message") {
        val detail = Sample.detail()
        ReaderContent(detail = detail, error = null, live = detail.summary, remoteBlocked = true, actions = Sample.NoReader) {
            Text(
                Sample.BODY,
                color = Color(0xFF1B1C22),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }

    @Test
    fun composeMail() = shoot("compose") {
        ComposeContent(mode = ComposeMode.REPLY, state = Sample.compose(), actions = Sample.NoCompose)
    }

    @Test
    fun setup() = shoot("setup") { SetupWelcome() }
}

private object Sample {
    private val now: Instant = Instant.now()
    private fun ago(minutes: Long) = now.minus(minutes, ChronoUnit.MINUTES).toString()

    val work = Account(
        id = "a1",
        displayName = "Alex Berger",
        email = "alex.berger@northwind.example",
        kind = buildJsonObject { put("type", "graph") },
        color = "#4f54d4",
    )
    private val home = Account(
        id = "a2",
        displayName = "Alex Berger",
        email = "alex@berger-family.example",
        kind = buildJsonObject { put("type", "imap") },
        color = "#107c41",
    )

    private fun folder(id: String, account: String, name: String, role: String, unread: Int = 0, parent: String? = null, total: Int = unread + 40) =
        Folder(id = id, accountId = account, remoteId = id, name = name, parentId = parent, role = role, unreadCount = unread, totalCount = total)

    private val workFolders = listOf(
        FolderEntry(folder("inbox", "a1", "Inbox", FolderRole.INBOX, 4), 0),
        FolderEntry(folder("drafts", "a1", "Drafts", FolderRole.DRAFTS, total = 1), 0),
        FolderEntry(folder("sent", "a1", "Sent Items", FolderRole.SENT), 0),
        FolderEntry(folder("archive", "a1", "Archive", FolderRole.ARCHIVE), 0),
        FolderEntry(folder("junk", "a1", "Junk Email", FolderRole.JUNK), 0),
        FolderEntry(folder("trash", "a1", "Deleted Items", FolderRole.TRASH), 0),
        FolderEntry(folder("projects", "a1", "Projects", FolderRole.OTHER, 2), 0),
        FolderEntry(folder("launch", "a1", "Spring launch", FolderRole.OTHER, 2, parent = "projects"), 1),
    )
    private val homeFolders = listOf(
        FolderEntry(folder("h-inbox", "a2", "INBOX", FolderRole.INBOX, 1), 0),
        FolderEntry(folder("h-sent", "a2", "Sent", FolderRole.SENT), 0),
    )

    private fun msg(
        id: String,
        name: String,
        email: String,
        subject: String,
        preview: String,
        minutesAgo: Long,
        read: Boolean = true,
        flagged: Boolean = false,
        attachments: Boolean = false,
        pinned: Boolean = false,
        high: Boolean = false,
    ) = MessageSummary(
        id = id,
        accountId = "a1",
        folderId = "inbox",
        subject = subject,
        from = Address(name, email),
        to = listOf(Address(work.displayName, work.email)),
        date = ago(minutesAgo),
        preview = preview,
        isRead = read,
        isFlagged = flagged,
        hasAttachments = attachments,
        pinned = pinned,
        importance = if (high) "high" else "normal",
    )

    val messages = listOf(
        msg("m1", "Lena Hoffmann", "lena.hoffmann@northwind.example", "Spring launch – final schedule",
            "Hi Alex, attached is the final schedule for the launch week. Could you check the press slots on Tuesday…",
            minutesAgo = 12, read = false, attachments = true, pinned = true),
        msg("m2", "Jonas Weber", "jonas.weber@northwind.example", "Re: Budget 2027",
            "Thanks, that works for me. Let's go through the open points in Thursday's meeting.",
            minutesAgo = 48, read = false, high = true),
        msg("m3", "Sofia Marino", "sofia@marino-design.example", "New logo drafts",
            "Here are three directions for the new packaging. I like the second one best, but have a look…",
            minutesAgo = 95, read = false, attachments = true, flagged = true),
        msg("m4", "IT Service Desk", "servicedesk@northwind.example", "Planned maintenance on Saturday",
            "The VPN will be unavailable on Saturday from 6:00 to 9:00 while we update the firewalls.",
            minutesAgo = 60 * 5),
        msg("m5", "Daniel Krüger", "daniel.krueger@northwind.example", "Lunch on Friday?",
            "The new Italian place next to the office opens this week – shall we try it?",
            minutesAgo = 60 * 26, read = false),
        msg("m6", "Clara Neumann", "clara.neumann@contoso.example", "Contract draft v3",
            "Please find the revised contract attached. We changed clause 7 as discussed on the phone.",
            minutesAgo = 60 * 50, attachments = true),
        msg("m7", "Travel Team", "travel@northwind.example", "Your trip to Lyon is confirmed",
            "Flight, hotel and train are booked. You'll find all documents in the attached itinerary.",
            minutesAgo = 60 * 24 * 4),
    )

    fun inbox() = MailState(
        loaded = true,
        accounts = listOf(work, home),
        statuses = mapOf("a1" to AccountStatus("a1"), "a2" to AccountStatus("a2")),
        folders = mapOf("a1" to workFolders, "a2" to homeFolders),
        view = MailView.InFolder("a1", "inbox"),
        messages = messages,
        focusedTab = true,
        focusCounts = 3 to 5,
    )

    const val BODY = "Hi Alex,\n\n" +
        "attached is the final schedule for the launch week. The keynote stays on Monday at 10:00, " +
        "the press slots are now on Tuesday afternoon.\n\n" +
        "Could you check whether 14:00–16:00 works for you and the product team? The venue needs our " +
        "final numbers by Friday.\n\n" +
        "Thanks and best regards,\nLena"

    fun detail() = MessageDetail(
        summary = messages[0].copy(isRead = true, cc = listOf(Address("Jonas Weber", "jonas.weber@northwind.example"))),
        body = MessageBody(
            text = BODY,
            attachments = listOf(
                AttachmentInfo(id = "x1", name = "Launch-schedule.pdf", contentType = "application/pdf", size = 482_000),
                AttachmentInfo(id = "x2", name = "Venue-plan.png", contentType = "image/png", size = 1_240_000),
            ),
        ),
    )

    fun compose() = ComposeState(
        ready = true,
        accounts = listOf(work, home),
        accountId = "a1",
        to = listOf(Address("Lena Hoffmann", "lena.hoffmann@northwind.example")),
        cc = listOf(Address("Jonas Weber", "jonas.weber@northwind.example")),
        showCc = true,
        subject = "RE: Spring launch – final schedule",
        body = "Hi Lena,\n\n14:00–16:00 on Tuesday works for us. I'll send the final numbers to the venue tomorrow.\n\nBest,\nAlex",
        quotedPreview = "Hi Alex,\nattached is the final schedule for the launch week.",
        attachments = listOf(
            PendingAttachment("Team-list.xlsx", 38_000, de.mirranet.ondura.core.OutgoingAttachment.File("/tmp/Team-list.xlsx")),
        ),
    )

    object NoInbox : InboxActions {
        override fun select(view: MailView) = Unit
        override fun selectTab(focused: Boolean) = Unit
        override fun refresh() = Unit
        override fun loadMore() = Unit
        override fun archive(message: MessageSummary) = Unit
        override fun delete(message: MessageSummary) = Unit
    }

    object NoReader : ReaderActions {
        override fun back() = Unit
        override fun reply(id: String) = Unit
        override fun replyAll(id: String) = Unit
        override fun forward(id: String) = Unit
        override fun retry() = Unit
        override fun allowRemote() = Unit
        override fun archive(message: MessageSummary) = Unit
        override fun delete(message: MessageSummary) = Unit
        override fun toggleFlag(message: MessageSummary) = Unit
        override fun markUnread(message: MessageSummary) = Unit
        override fun move(message: MessageSummary) = Unit
        override fun openAttachment(attachment: AttachmentInfo) = Unit
    }

    object NoCompose : ComposeActions {
        override fun setAccount(id: String) = Unit
        override fun setSubject(v: String) = Unit
        override fun setBody(v: String) = Unit
        override fun showCc() = Unit
        override fun toggleImportance() = Unit
        override fun send() = Unit
        override fun addRecipient(field: RecipientField, address: Address) = Unit
        override fun removeRecipient(field: RecipientField, address: Address) = Unit
        override fun suggest(query: String) = Unit
        override fun clearSuggestions() = Unit
        override fun removeAttachment(a: PendingAttachment) = Unit
    }
}
