package de.mirranet.ondura.mail.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import de.mirranet.ondura.core.AttachmentInfo
import de.mirranet.ondura.core.MailCore
import de.mirranet.ondura.mail.Format
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File

private const val INLINE_HOST = "inline.ondura.invalid"
private const val BASE_URL = "https://message.ondura.invalid/"

/**
 * Turns a message body into a self-contained page: inline images (`cid:`)
 * point to a virtual host served from the attachments, and the page gets a
 * mobile viewport and readable defaults.
 */
fun buildMailPage(html: String?, text: String?, attachments: List<AttachmentInfo>): String {
    var body = html ?: "<div style=\"white-space:pre-wrap\">${Format.escapeHtml(text ?: "")}</div>"
    for (a in attachments) {
        val cid = a.contentId?.trim('<', '>', ' ') ?: continue
        body = body.replace("cid:$cid", "https://$INLINE_HOST/${Uri.encode(a.id)}", ignoreCase = true)
    }
    val style = """
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body { margin: 0; background: #ffffff; color: #1b1c22; }
          body { padding: 4px 16px 16px; font-family: sans-serif; font-size: 15px; line-height: 1.45; overflow-wrap: anywhere; }
          img { max-width: 100% !important; height: auto !important; }
          table { max-width: 100% !important; }
          pre { white-space: pre-wrap; }
          blockquote { margin: 0 0 0 8px; padding-left: 10px; border-left: 3px solid #c5c8d3; }
          a { color: #3b5bdb; }
        </style>
    """.trimIndent()
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">$style</head><body>$body</body></html>"
}

/**
 * Read-only WebView for one message. No JavaScript, no file access; remote
 * content (tracking pixels) is only loaded when [allowRemote] is set, else
 * [onRemoteBlocked] is called once.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MailWebView(
    page: String,
    messageId: String,
    core: MailCore,
    allowRemote: Boolean,
    onRemoteBlocked: () -> Unit,
    onLink: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(Color.WHITE)
                isVerticalScrollBarEnabled = false
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.textZoom = 100
            }
        },
        update = { web ->
            val cacheDir = File(web.context.cacheDir, "inline").apply { mkdirs() }
            var reported = false
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    onLink(request.url)
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.host == INLINE_HOST) {
                        val attachmentId = url.lastPathSegment ?: return empty()
                        return try {
                            val file = File(cacheDir, "${messageId.hashCode()}-${attachmentId.hashCode()}")
                            if (!file.exists()) runBlocking { core.saveAttachment(messageId, attachmentId, file) }
                            WebResourceResponse("application/octet-stream", null, file.inputStream())
                        } catch (e: Exception) {
                            empty()
                        }
                    }
                    if (url.toString().startsWith(BASE_URL)) return empty()
                    if (!allowRemote && (url.scheme == "http" || url.scheme == "https")) {
                        if (!reported) {
                            reported = true
                            main.post { onRemoteBlocked() }
                        }
                        return empty()
                    }
                    return null
                }
            }
            val key = "$allowRemote:${page.hashCode()}"
            if (web.tag != key) {
                web.tag = key
                web.loadDataWithBaseURL(BASE_URL, page, "text/html", "utf-8", null)
            }
        },
    )
}

private fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

/** Opens a link from a message: mailto in Ondura, everything else outside. */
fun linkIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
