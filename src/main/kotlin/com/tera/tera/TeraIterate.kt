package com.tera.tera

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Turning an array or map that is printed as it is (`{{ categories }}` shows `["Travel", "Equipment"]`) into what a
 * page wants: a loop, a loop with a fallback for an empty list, or a joined list. The Rust type of the variable
 * (a `Vec<String>`, a `HashMap<..>`, a slice) decides whether these are offered, and the element type what goes in the loop.
 */
enum class TeraSnippetKind { FOR, FOR_ELSE, JOIN }

/** [text] replaces the whole `{{ .. }}`; the caret is put [caret] characters into it. */
data class TeraSnippet(val kind: TeraSnippetKind, val title: String, val text: String, val caret: Int)

object TeraIterate {

    /** `categories` → `category`, `entries` → `entry`, `items` → `item`, `data` → `item`. */
    fun itemName(expr: String): String {
        val last = expr.substringAfterLast('.').substringAfterLast('[').trimEnd(']').takeIf { it.isNotBlank() && it.first().isLetter() } ?: return "item"
        val singular = when {
            last.endsWith("ies") && last.length > 3 -> last.dropLast(3) + "y"
            last.endsWith("ses") || last.endsWith("xes") || last.endsWith("ches") || last.endsWith("shes") -> last.dropLast(2)
            last.endsWith("s") && !last.endsWith("ss") && last.length > 1 -> last.dropLast(1)
            else -> "item"
        }
        return if (singular == last) "${last}_item" else singular
    }

    /**
     * [expr] is what goes after `in` (`categories`, `counts | values`); [name] the variable it is built on, which names
     * the loop variable, unless [item] says better (`| values` gives `value`).
     */
    fun snippets(expr: String, type: TeraType, name: String = expr, item: String? = null): List<TeraSnippet> = when (type) {
        is TeraType.ArrayOf -> arraySnippets(expr, type.element, item ?: itemName(name))
        is TeraType.Tuple -> arraySnippets(expr, type.elements.firstOrNull() ?: TeraType.Unknown, item ?: itemName(name))
        is TeraType.MapOf -> {
            val body = "{{ key }}: {{ value }}"
            val text = "{% for key, value in $expr %}$body{% endfor %}"
            listOf(TeraSnippet(TeraSnippetKind.FOR, "for key, value in $expr", text, text.indexOf("{% endfor") ))
        }
        else -> emptyList()
    }

    private fun arraySnippets(expr: String, element: TeraType, item: String): List<TeraSnippet> {
        val shown = (element as? TeraType.Struct)?.fields?.let { fs -> fs.firstOrNull { it.type == TeraType.Scalar("string") } ?: fs.firstOrNull { it.type is TeraType.Scalar } }?.let { "$item.${it.name}" } ?: item
        val open = "{% for $item in $expr %}"
        val body = "{{ $shown }}"
        val loop = "$open$body"
        val out = mutableListOf(
            TeraSnippet(TeraSnippetKind.FOR, "for $item in $expr", "$loop{% endfor %}", loop.length - 3),
            TeraSnippet(TeraSnippetKind.FOR_ELSE, "for $item in $expr … else (when empty)",
                "$loop{% else %}Nothing to show.{% endfor %}", loop.length - 3),
        )
        if (element is TeraType.Scalar) {
            val join = "{{ $expr | join(sep=\", \") }}"
            out.add(TeraSnippet(TeraSnippetKind.JOIN, "$expr | join(sep=\", \")", join, join.length - 3))
        }
        return out
    }

    /**
     * What a filter does to the type of a list or map, for the filters that keep or produce one; `Unknown` for the rest
     * (`length`, `join`, ... give a plain value, and a filter the plugin does not know is not guessed).
     */
    fun afterFilter(name: String, input: TeraType): TeraType {
        val list = (input as? TeraType.ArrayOf)?.element ?: (input as? TeraType.Tuple)?.elements?.firstOrNull()
        return when (name) {
            "sort", "reverse", "unique", "slice", "concat", "filter" ->
                if (input is TeraType.Scalar && name in setOf("reverse", "slice")) input else if (list != null || input is TeraType.ArrayOf) TeraType.ArrayOf(list ?: TeraType.Unknown) else TeraType.Unknown
            "default", "safe" -> input
            "values" -> (input as? TeraType.MapOf)?.let { TeraType.ArrayOf(it.value) } ?: TeraType.Unknown
            "keys" -> if (input is TeraType.MapOf) TeraType.ArrayOf(TeraType.Scalar("string")) else TeraType.Unknown
            "group_by" -> if (input is TeraType.ArrayOf) TeraType.MapOf(input) else TeraType.Unknown
            "map" -> if (input is TeraType.ArrayOf) TeraType.ArrayOf(TeraType.Unknown) else TeraType.Unknown
            "split" -> if (input is TeraType.Scalar) TeraType.ArrayOf(TeraType.Scalar("string")) else TeraType.Unknown
            else -> TeraType.Unknown
        }
    }

    /**
     * The `{{ path | filter | ... }}` around [offset]: [expr] is all of it (a trailing, unfinished `|` is left out),
     * [name] the variable at the start, [type] what the chain produces, [item] a better name for the loop variable.
     */
    class Target(val blockStart: Int, val blockEnd: Int, val expr: String, val type: TeraType, val name: String, val item: String?)

    fun targetAt(text: String, offset: Int, context: TeraContextResult?, version: TeraVersion): Target? {
        val blocks = TeraSymbolScanner.scanBlocks(text)
        val block = blocks.firstOrNull { it.kind == '{' && it.closed && offset in (it.contentStart - 2)..(it.contentEnd + 2) } ?: return null
        val t = block.tokens
        // The variable path: names and numbers joined by dots.
        var i = 0
        while (i < t.size && (if (i % 2 == 0) t[i].type == TeraTokenTypes.IDENTIFIER || t[i].type == TeraTokenTypes.NUMBER
                else t[i].type == TeraTokenTypes.OPERATOR && t[i].text == ".")) i++
        if (i == 0) return null
        if (i % 2 == 0) i--          // a trailing dot belongs to nothing
        val path = t.subList(0, i)
        val ref = TeraVariables.refAt(text, path.last().range.startOffset, context, version) ?: return null
        if (ref.index != ref.occurrence.segs.size - 1) return null

        // `| name` or `| name(...)`, repeated; an unfinished tail is ignored.
        var type = ref.result.type
        var end = path.last().range.endOffset
        var last: String? = null
        while (i < t.size) {
            val bar = t[i]
            val nameTok = t.getOrNull(i + 1)
            if (!(bar.type == TeraTokenTypes.OPERATOR && bar.text == "|") || nameTok == null || nameTok.type != TeraTokenTypes.IDENTIFIER) break
            var j = i + 2
            if (t.getOrNull(j)?.let { it.type == TeraTokenTypes.OPERATOR && it.text == "(" } == true) {
                var depth = 0
                while (j < t.size) {
                    val x = t[j]
                    if (x.type == TeraTokenTypes.OPERATOR && x.text == "(") depth++
                    if (x.type == TeraTokenTypes.OPERATOR && x.text == ")") { depth--; if (depth == 0) break }
                    j++
                }
                if (j >= t.size) break           // the call is not closed yet
            } else j = i + 1
            type = afterFilter(nameTok.text, type)
            last = nameTok.text
            end = t[j].range.endOffset
            i = j + 1
        }
        // Anything else in the block (an operator, a test, a second expression) means it is not just a chain.
        if (i < t.size && !(t.size - i == 1 && t[i].type == TeraTokenTypes.OPERATOR && t[i].text == "|")) return null
        val expr = text.substring(path.first().range.startOffset, end)
        val name = text.substring(path.first().range.startOffset, path.last().range.endOffset)
        val item = when (last) { "values" -> "value"; "keys" -> "key"; else -> null }
        return Target(block.contentStart - 2, block.contentEnd + 2, expr, type, name, item)
    }
}

abstract class TeraIterateIntention(private val kind: TeraSnippetKind) : IntentionAction {
    override fun getFamilyName() = "Tera"
    override fun startInWriteAction() = true
    private var title = "Iterate"
    override fun getText() = title

    private fun snippet(project: Project, editor: Editor?, file: PsiFile?): Pair<TeraIterate.Target, TeraSnippet>? {
        if (file !is TeraFile || editor == null) return null
        val vFile = file.originalFile.virtualFile
        val target = TeraIterate.targetAt(file.text, editor.caretModel.offset, TeraContexts.forFile(file), TeraVersions.forFile(project, vFile).version)
            ?: return null
        val snippet = TeraIterate.snippets(target.expr, target.type, target.name, target.item).firstOrNull { it.kind == kind } ?: return null
        return target to snippet
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val (_, snippet) = snippet(project, editor, file) ?: return false
        title = "Tera: ${snippet.title}"
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val (target, snippet) = snippet(project, editor, file) ?: return
        editor!!.document.replaceString(target.blockStart, target.blockEnd, snippet.text)
        editor.caretModel.moveToOffset(target.blockStart + snippet.caret)
    }
}

class TeraIterateForIntention : TeraIterateIntention(TeraSnippetKind.FOR)
class TeraIterateForElseIntention : TeraIterateIntention(TeraSnippetKind.FOR_ELSE)
class TeraIterateJoinIntention : TeraIterateIntention(TeraSnippetKind.JOIN)
