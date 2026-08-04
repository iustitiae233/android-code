package com.example.clauderemote.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** 轻量 Markdown 渲染（零依赖）：代码块/标题/列表/引用 + 行内 粗体/斜体/行内代码/链接。 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val codeBg = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val codeFg = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(markdown) { parseBlocks(markdown) }

    Column(modifier) {
        var ordered = 0
        blocks.forEachIndexed { idx, block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.code)

                is MdBlock.Heading -> Text(
                    text = buildInline(block.text, codeBg, codeFg, linkColor),
                    style = when (block.level) {
                        1 -> style.copy(fontWeight = FontWeight.Bold, fontSize = style.fontSize * 1.55f)
                        2 -> style.copy(fontWeight = FontWeight.Bold, fontSize = style.fontSize * 1.32f)
                        else -> style.copy(fontWeight = FontWeight.SemiBold, fontSize = style.fontSize * 1.15f)
                    },
                )

                is MdBlock.ListItem -> {
                    if (!block.ordered) ordered = 0
                    ordered++
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            if (block.ordered) "$ordered. " else "•  ",
                            style = style,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(buildInline(block.text, codeBg, codeFg, linkColor), style = style)
                    }
                }

                is MdBlock.Quote -> Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 1.dp),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    Text(
                        buildInline(block.text, codeBg, codeFg, linkColor),
                        modifier = Modifier.padding(start = 8.dp),
                        style = style.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                        ),
                    )
                }

                is MdBlock.Paragraph -> Text(
                    buildInline(block.text, codeBg, codeFg, linkColor),
                    style = style,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                code,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                modifier = Modifier.padding(2.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "复制代码",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

// ── 块级解析 ──
private sealed interface MdBlock {
    data class Code(val code: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListItem(val ordered: Boolean, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private val HEADING_RE = Regex("""^(#{1,3})\s+(.*)""")
private val UL_RE = Regex("""^\s*[-*]\s+(.*)""")
private val OL_RE = Regex("""^\s*\d+\.\s+(.*)""")
private val LINK_RE = Regex("""\[([^\]]+)\]\(([^)]+)\)""")

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.lines()
    var i = 0
    var para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para = StringBuilder()
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                flushPara()
                i++
                val sb = StringBuilder()
                while (i < lines.size && !lines[i].startsWith("```")) {
                    sb.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // 跳过闭合围栏
                out += MdBlock.Code(sb.toString().trimEnd())
            }
            HEADING_RE.find(line) != null -> {
                flushPara()
                val m = HEADING_RE.find(line)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
            }
            UL_RE.find(line) != null -> {
                flushPara()
                out += MdBlock.ListItem(false, UL_RE.find(line)!!.groupValues[1].trim())
            }
            OL_RE.find(line) != null -> {
                flushPara()
                out += MdBlock.ListItem(true, OL_RE.find(line)!!.groupValues[2].trim())
            }
            line.startsWith(">") -> {
                flushPara()
                out += MdBlock.Quote(line.removePrefix(">").trim())
            }
            line.isBlank() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(line.trim())
            }
        }
        i++
    }
    flushPara()
    return out
}

// ── 行内解析 ──
private val INLINE_RE = Regex(
    """(`[^`]+`)|(\*\*[^*]+?\*\*)|(\*[^*\s][^*]*?\*|_[^_\s][^_]*?_)|(\[[^\]]+\]\([^)]+\))""",
)

private fun buildInline(
    text: String,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE_RE.findAll(text)) {
        if (m.range.first > last) append(text.substring(last, m.range.first))
        val g = m.value
        when {
            g.startsWith("`") -> {
                val code = g.trim('`')
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeFg)) {
                    append(" $code ")
                }
            }
            g.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(g.removePrefix("**").removeSuffix("**"))
            }
            g.startsWith("*") || g.startsWith("_") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(g.drop(1).dropLast(1))
            }
            g.startsWith("[") -> {
                val lm = LINK_RE.find(g)
                if (lm != null) withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)) {
                    append(lm.groupValues[1])
                } else append(g)
            }
            else -> append(g)
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}
