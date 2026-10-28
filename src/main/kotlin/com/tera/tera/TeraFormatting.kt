package com.tera.tera

import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CodeStyleSettings

/**
 * Re-indents a template by the nesting of its HTML tags and Tera tags, and changes nothing else: no line is joined or
 * split, no space inside a line moves. Content that is whitespace-sensitive (`pre`, `textarea`, `script` and `style`
 * bodies, `{% raw %}`, comments) and the continuation lines of a tag that spans lines keep their indentation.
 */
object TeraIndenter {

    /** One line that has content: [start] is the first non-space character, [depth] null when the line is left alone. */
    class Line(val start: Int, val end: Int, val depth: Int?)

    private enum class Kind { OPEN, CLOSE, MID }
    private class Event(val start: Int, val end: Int, val kind: Kind, val name: String)

    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val RAW_TEXT = setOf("pre", "textarea", "script", "style")
    private val TERA_OPEN = setOf("for", "if", "block", "macro", "component", "filter", "raw")

    fun lines(text: String): List<Line> {
        val events = mutableListOf<Event>()
        val frozen = mutableListOf<IntRange>()                 // (start, end) offsets whose lines start inside are left alone
        val n = text.length
        var i = 0
        fun skipTo(from: Int, marker: String): Int = text.indexOf(marker, from).let { if (it < 0) n else it + marker.length }
        while (i < n) {
            when {
                text.startsWith("{#", i) -> { val e = skipTo(i + 2, "#}"); frozen.add(i..e); i = e }
                text.startsWith("{{", i) -> { val e = skipTo(i + 2, "}}"); frozen.add(i..e); i = e }
                text.startsWith("{%", i) -> {
                    val e = skipTo(i + 2, "%}")
                    val inner = text.substring(i + 2, (e - 2).coerceAtLeast(i + 2)).trim { it == '-' || it == '+' || it == '~' || it.isWhitespace() }
                    val word = inner.takeWhile { it.isLetterOrDigit() || it == '_' }
                    val body = inner.removePrefix(word).trim()
                    when {
                        word in TERA_OPEN -> {
                            events.add(Event(i, e, Kind.OPEN, "tera:$word"))
                            if (word == "raw") {                       // everything up to `endraw` is literal
                                val close = Regex("""\{%[-+~]?\s*endraw""").find(text, e)?.range?.first ?: n
                                frozen.add(e..close); i = close; continue
                            }
                        }
                        (word == "set" || word == "set_global") && !body.contains('=') && word == "set" -> events.add(Event(i, e, Kind.OPEN, "tera:set"))
                        word.startsWith("end") && word.length > 3 -> events.add(Event(i, e, Kind.CLOSE, "tera:${word.removePrefix("end")}"))
                        word == "else" || word == "elif" -> events.add(Event(i, e, Kind.MID, "tera:else"))
                        inner.startsWith("</") -> events.add(Event(i, e, Kind.CLOSE, "tera:<" + inner.removePrefix("</").takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' }))
                        inner.startsWith("<") && !inner.endsWith("/>") -> events.add(Event(i, e, Kind.OPEN, "tera:<" + inner.removePrefix("<").takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' }))
                    }
                    frozen.add(i..e); i = e
                }
                text.startsWith("<!--", i) -> { val e = skipTo(i + 4, "-->"); frozen.add(i..e); i = e }
                text[i] == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '/') -> {
                    val closing = text[i + 1] == '/'
                    val nameStart = if (closing) i + 2 else i + 1
                    val name = text.substring(nameStart).takeWhile { it.isLetterOrDigit() || it == '-' || it == ':' }.lowercase()
                    // The end of the tag: the first `>` outside quotes and outside Tera tags.
                    var j = nameStart + name.length
                    var quote = '\u0000'
                    while (j < n) {
                        val c = text[j]
                        if (quote != '\u0000') { if (c == quote) quote = '\u0000'; j++; continue }
                        when {
                            text.startsWith("{{", j) -> j = skipTo(j + 2, "}}")
                            text.startsWith("{%", j) -> j = skipTo(j + 2, "%}")
                            c == '"' || c == '\'' -> { quote = c; j++ }
                            c == '>' -> break
                            else -> j++
                        }
                    }
                    val end = (j + 1).coerceAtMost(n)
                    val selfClosing = j > 0 && j <= n && text.getOrNull(j - 1) == '/'
                    if (name.isNotEmpty() && name !in VOID && !selfClosing) {
                        events.add(Event(i, end, if (closing) Kind.CLOSE else Kind.OPEN, "html:$name"))
                    }
                    frozen.add(i..end)
                    if (!closing && name in RAW_TEXT && !selfClosing) {
                        val close = Regex("</$name\\b", RegexOption.IGNORE_CASE).find(text, end)
                        val bodyEnd = close?.range?.first ?: n
                        // pre/textarea: the closing tag's line belongs to the content; script/style: it is re-indented.
                        frozen.add(end..(if (name == "pre" || name == "textarea") (close?.let { skipTo(it.range.first, ">") } ?: n) else bodyEnd))
                        i = bodyEnd
                    } else i = end
                }
                else -> i++
            }
        }

        // Lines.
        val sorted = events.sortedBy { it.start }
        val byStart = sorted.associateBy { it.start }
        val stack = ArrayList<String>()
        fun apply(e: Event) {
            when (e.kind) {
                Kind.OPEN -> stack.add(e.name)
                Kind.CLOSE -> stack.lastIndexOf(e.name).let { if (it >= 0) while (stack.size > it) stack.removeAt(stack.size - 1) }
                Kind.MID -> Unit
            }
        }
        val out = mutableListOf<Line>()
        var pos = 0
        var eventIndex = 0
        while (pos <= n) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) n else it }
            val lineEnd = if (nl > pos && text[nl - 1] == '\r') nl - 1 else nl
            var cs = pos
            while (cs < lineEnd && (text[cs] == ' ' || text[cs] == '\t')) cs++
            if (cs < lineEnd) {
                val isFrozen = frozen.any { cs > it.first && cs < it.last }
                // The indent of this line: apply what its leading closers and `else`s do, on a copy.
                var depth: Int? = null
                if (!isFrozen) {
                    val copy = ArrayList(stack)
                    var cursor = cs
                    var dedent = 0
                    while (true) {
                        val ev = byStart[cursor] ?: break
                        if (ev.kind == Kind.OPEN) break
                        if (ev.kind == Kind.MID) { dedent++; break }
                        val at = copy.lastIndexOf(ev.name)
                        if (at >= 0) while (copy.size > at) copy.removeAt(copy.size - 1)
                        cursor = ev.end
                        while (cursor < lineEnd && text[cursor] == ' ') cursor++
                    }
                    depth = (copy.size - dedent).coerceAtLeast(0)
                }
                out.add(Line(cs, lineEnd, depth))
            }
            while (eventIndex < sorted.size && sorted[eventIndex].start < nl + 1 && sorted[eventIndex].start >= pos) apply(sorted[eventIndex++])
            if (nl >= n) break
            pos = nl + 1
        }
        return out
    }

    /** The template with every line that may be re-indented indented to its depth. */
    fun format(text: String, indent: String): String {
        val sb = StringBuilder()
        var last = 0
        for (line in lines(text)) {
            val depth = line.depth ?: continue
            val lineStart = text.lastIndexOf('\n', line.start - 1) + 1
            sb.append(text, last, lineStart).append(indent.repeat(depth))
            last = line.start
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }
}

/**
 * Reformat Code on a template: the indentation of [TeraIndenter], nothing else. It runs after the platform's formatting
 * (which has no model for a template and leaves it alone) and edits the text of the lines it re-indents, so
 * whitespace-sensitive content is never touched.
 */
class TeraPostFormatProcessor : com.intellij.psi.impl.source.codeStyle.PostFormatProcessor {

    override fun processElement(source: com.intellij.psi.PsiElement, settings: CodeStyleSettings): com.intellij.psi.PsiElement = source

    override fun processText(source: com.intellij.psi.PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        if (source.viewProvider.baseLanguage != TeraLanguage) return rangeToReformat
        val project = source.project
        val documents = com.intellij.psi.PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(source) ?: return rangeToReformat
        documents.commitDocument(document)
        val options = settings.getLanguageIndentOptions(HTMLLanguage.INSTANCE)
        val size = options.INDENT_SIZE.takeIf { it > 0 } ?: 2
        val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(size)
        val text = document.text
        var delta = 0
        for (line in TeraIndenter.lines(text).reversed()) {
            val depth = line.depth ?: continue
            val lineStart = text.lastIndexOf('\n', line.start - 1) + 1
            if (line.start < rangeToReformat.startOffset && line.end < rangeToReformat.startOffset) continue
            if (lineStart > rangeToReformat.endOffset) continue
            val wanted = unit.repeat(depth)
            if (text.substring(lineStart, line.start) == wanted) continue
            document.replaceString(lineStart, line.start, wanted)
            delta += wanted.length - (line.start - lineStart)
        }
        documents.commitDocument(document)
        return TextRange(rangeToReformat.startOffset, (rangeToReformat.endOffset + delta).coerceIn(0, document.textLength))
    }
}
