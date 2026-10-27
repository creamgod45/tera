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

    fun snippets(expr: String, type: TeraType): List<TeraSnippet> = when (type) {
        is TeraType.ArrayOf -> arraySnippets(expr, type.element)
        is TeraType.Tuple -> arraySnippets(expr, type.elements.firstOrNull() ?: TeraType.Unknown)
        is TeraType.MapOf -> {
            val body = "{{ key }}: {{ value }}"
            val text = "{% for key, value in $expr %}$body{% endfor %}"
            listOf(TeraSnippet(TeraSnippetKind.FOR, "for key, value in $expr", text, text.indexOf("{% endfor") ))
        }
        else -> emptyList()
    }

    private fun arraySnippets(expr: String, element: TeraType): List<TeraSnippet> {
        val item = itemName(expr)
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

    /** The `{{ path }}` around [offset] when it is nothing but a variable path, with that variable's type. */
    class Target(val blockStart: Int, val blockEnd: Int, val expr: String, val type: TeraType)

    fun targetAt(text: String, offset: Int, context: TeraContextResult?, version: TeraVersion): Target? {
        val blocks = TeraSymbolScanner.scanBlocks(text)
        val block = blocks.firstOrNull { it.kind == '{' && it.closed && offset in (it.contentStart - 2)..(it.contentEnd + 2) } ?: return null
        val t = block.tokens
        if (t.isEmpty() || t.any { it.type == TeraTokenTypes.OPERATOR && it.text != "." }) return null
        if (t.withIndex().any { (i, tok) -> (i % 2 == 0) != (tok.type == TeraTokenTypes.IDENTIFIER || tok.type == TeraTokenTypes.NUMBER) }) return null
        val ref = TeraVariables.refAt(text, t.last().range.startOffset, context, version) ?: return null
        if (ref.index != ref.occurrence.segs.size - 1) return null
        val expr = text.substring(t.first().range.startOffset, t.last().range.endOffset)
        return Target(block.contentStart - 2, block.contentEnd + 2, expr, ref.result.type)
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
        val snippet = TeraIterate.snippets(target.expr, target.type).firstOrNull { it.kind == kind } ?: return null
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
