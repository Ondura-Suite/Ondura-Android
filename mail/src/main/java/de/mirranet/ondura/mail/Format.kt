package de.mirranet.ondura.mail

import android.content.Context
import android.text.format.DateUtils
import de.mirranet.ondura.core.Address
import de.mirranet.ondura.core.MessageDetail
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

object Format {
    /** List style: time today, weekday this week, date otherwise (like Outlook). */
    fun listDate(context: Context, instant: Instant): String {
        val zone = ZoneId.systemDefault()
        val date = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val millis = instant.toEpochMilli()
        return when {
            date == today -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
            ChronoUnit.DAYS.between(date, today) < 7 -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
            date.year == today.year -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
            else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
        }
    }

    fun fullDate(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
            .format(instant)

    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    fun escapeHtml(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    /** Plain text from the editor → HTML body (paragraphs and line breaks kept). */
    fun textToHtml(text: String): String =
        escapeHtml(text).replace("\r\n", "\n").replace("\n", "<br>")

    fun addresses(list: List<Address>): String = list.joinToString(", ") { it.display }

    /** Header block above a quoted message (reply / forward). */
    fun quoteHeader(context: Context, original: MessageDetail): String {
        val s = original.summary
        val rows = buildList {
            add(context.getString(R.string.quote_from) to (s.from?.let { "${escapeHtml(it.display)} &lt;${escapeHtml(it.email)}&gt;" } ?: ""))
            add(context.getString(R.string.quote_sent) to escapeHtml(fullDate(s.instant)))
            add(context.getString(R.string.quote_to) to escapeHtml(addresses(s.to)))
            if (s.cc.isNotEmpty()) add(context.getString(R.string.quote_cc) to escapeHtml(addresses(s.cc)))
            add(context.getString(R.string.quote_subject) to escapeHtml(s.subject))
        }
        return rows.joinToString("<br>") { (k, v) -> "<b>$k:</b> $v" }
    }

    /** Original message below a reply / forward, Outlook style. */
    fun quotedBody(context: Context, original: MessageDetail): String {
        val body = original.body.html ?: original.body.text?.let { textToHtml(it) } ?: ""
        return "<div id=\"ondura-quote\"><hr style=\"border:none;border-top:1px solid #c5c8d3\">" +
            "<div style=\"font-size:0.92em\">${quoteHeader(context, original)}</div><br>" +
            "<div>$body</div></div>"
    }
}
