package com.riffbuddy.ui.tone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * The Markdown the models answer in, as blocks. Only the parts they actually
 * use: headings, bullet and numbered lists, code blocks, tables, rules and
 * paragraphs. Everything else is a paragraph of plain text, never an error.
 *
 * Pure Kotlin, so the parsing is tested on the JVM (MarkdownTest); [Markdown]
 * draws the result.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String, val depth: Int) : MdBlock
    data class Numbered(val number: String, val text: String, val depth: Int) : MdBlock
    data class Code(val text: String) : MdBlock
    /** A table's rows, header first, cells as written. */
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

/** An inline run: plain, **bold**, *italic*, `code`, or a link. */
sealed interface MdSpan {
    data class Plain(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan
    data class Code(val text: String) : MdSpan
    data class Link(val text: String, val url: String) : MdSpan
}

object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*•+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_DIVIDER = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun blocks(text: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) out += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.appendLine(lines[i]); i++
                    }
                    out += MdBlock.Code(code.toString().trimEnd())
                }
                line.isBlank() -> flush()
                HEADING.matches(line.trim()) -> {
                    flush()
                    val m = HEADING.matchEntire(line.trim())!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim().trimEnd('#').trim())
                }
                RULE.matches(line) -> { flush(); out += MdBlock.Rule }
                line.trim().startsWith("|") && i + 1 < lines.size && TABLE_DIVIDER.matches(lines[i + 1]) -> {
                    flush()
                    val rows = mutableListOf(cells(line))
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i]); i++ }
                    out += MdBlock.Table(rows)
                    continue
                }
                BULLET.matches(line) -> {
                    flush()
                    val m = BULLET.matchEntire(line)!!
                    out += MdBlock.Bullet(m.groupValues[2], depth(m.groupValues[1]))
                }
                NUMBERED.matches(line) -> {
                    flush()
                    val m = NUMBERED.matchEntire(line)!!
                    out += MdBlock.Numbered(m.groupValues[2], m.groupValues[3], depth(m.groupValues[1]))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line.trim())
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun depth(indent: String) = (indent.replace("\t", "    ").length / 2).coerceAtMost(3)

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private val INLINE = Regex(
        "\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)|(?<![*\\w])\\*([^*\\s][^*]*?)\\*(?![*\\w])|(?<![\\w_])_([^_\\s][^_]*?)_(?![\\w_])|(https?://[^\\s)]+[^\\s).,;:!?])"
    )

    fun spans(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        var at = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > at) out += MdSpan.Plain(text.substring(at, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> MdSpan.Bold(g[1])
                g[2].isNotEmpty() -> MdSpan.Bold(g[2])
                g[3].isNotEmpty() -> MdSpan.Code(g[3])
                g[4].isNotEmpty() -> MdSpan.Link(g[4], g[5])
                g[6].isNotEmpty() -> MdSpan.Italic(g[6])
                g[7].isNotEmpty() -> MdSpan.Italic(g[7])
                else -> MdSpan.Link(g[8], g[8])
            }
            at = m.range.last + 1
        }
        if (at < text.length) out += MdSpan.Plain(text.substring(at))
        return out
    }
}

/** A Markdown answer, drawn. Links open in the browser. */
@Composable
fun Markdown(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = MarkdownParser.blocks(text)
    val link = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inline(block.text, link),
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = color,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Paragraph -> Text(inline(block.text, link), style = MaterialTheme.typography.bodyMedium, color = color)
                is MdBlock.Bullet -> ListRow("•", block.text, block.depth, color, link)
                is MdBlock.Numbered -> ListRow("${block.number}.", block.text, block.depth, color, link)
                is MdBlock.Code -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = color,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp),
                )
                is MdBlock.Table -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Rows as "Cell — cell — cell": a phone is too narrow for
                    // columns, and this still reads in order.
                    block.rows.forEachIndexed { r, row ->
                        Text(
                            inline(row.filter { it.isNotBlank() }.joinToString("  ·  "), link),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (r == 0) FontWeight.Bold else null,
                            color = color,
                        )
                    }
                }
                MdBlock.Rule -> Text("", modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun ListRow(mark: String, text: String, depth: Int, color: Color, link: Color) {
    Row(Modifier.padding(start = (depth * 14).dp)) {
        // A hanging indent, as the help sheet draws lists: a wrapped line sits
        // under its text, not under the bullet.
        Text(mark, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.width(22.dp))
        Text(inline(text, link), style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private fun inline(text: String, link: Color): AnnotatedString = buildAnnotatedString {
    MarkdownParser.spans(text).forEach { span ->
        when (span) {
            is MdSpan.Plain -> append(span.text)
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            is MdSpan.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(span.text) }
            is MdSpan.Link -> withLink(
                LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))
            ) { append(span.text) }
        }
    }
}
