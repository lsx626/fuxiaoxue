package edu.fudan.elearning.sync.preview

import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.ui.*
import edu.fudan.elearning.sync.util.DocumentTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HtmlPreviewScreen(file: File) {
    val document by loadResource(file.absolutePath, 0) {
        withContext(Dispatchers.IO) {
            val raw = DocumentTexts.read(file, 4 * 1024 * 1024)
            val clean = raw.text.replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
            val spans = SpannableStringBuilder(HtmlCompat.fromHtml(clean, HtmlCompat.FROM_HTML_MODE_COMPACT))
            for (link in spans.getSpans(0, spans.length, URLSpan::class.java)) {
                val start = spans.getSpanStart(link); val end = spans.getSpanEnd(link)
                spans.removeSpan(link)
                val uri = runCatching { Uri.parse(link.url) }.getOrNull()
                if (uri?.scheme in listOf("http", "https")) spans.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        runCatching { widget.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            spans to raw.truncated
        }
    }
    when (val result = document) {
        Resource.Loading -> LoadingState("正在排版文档")
        Resource.Failed -> PreviewError("无法读取 HTML 文档，文件可能损坏或过大")
        is Resource.Ready -> Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.html_limitation_note), Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result.value.second) Text("文件较大，仅显示前 4 MB 内容", Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            val linkColor = MaterialTheme.colorScheme.primary.toArgb()
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                AndroidView(factory = { TextView(it).apply { textSize = 15f; setLineSpacing(4f, 1.2f); movementMethod = LinkMovementMethod.getInstance() } },
                    update = { view -> view.setTextColor(textColor); view.setLinkTextColor(linkColor); view.text = result.value.first },
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
