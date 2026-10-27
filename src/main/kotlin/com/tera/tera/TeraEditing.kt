package com.tera.tera

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/** Cmd+/ comments with `{# ... #}`, as Tera does. */
class TeraCommenter : Commenter {
    override fun getLineCommentPrefix(): String? = null
    override fun getBlockCommentPrefix() = "{# "
    override fun getBlockCommentSuffix() = " #}"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

/** `{{ }}`, `{% %}` and `{# #}` pair up: the caret next to one highlights the other. */
class TeraBraceMatcher : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(TeraTokenTypes.VARIABLE_START, TeraTokenTypes.VARIABLE_END, false),
        BracePair(TeraTokenTypes.TAG_START, TeraTokenTypes.TAG_END, false),
        BracePair(TeraTokenTypes.COMMENT_START, TeraTokenTypes.COMMENT_END, false),
    )
    override fun getPairs() = pairs
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset
}

/** Where a block starts and ends in the text, including the optional whitespace-trimming marker (`{%-`, `-%}`). */
object TeraBlockRanges {
    fun of(text: String, block: TeraBlock): TextRange {
        var start = block.contentStart
        while (start > 0 && text[start - 1] in "-+~") start--
        start = (start - 2).coerceAtLeast(0)
        var end = block.contentEnd
        while (end < text.length && text[end] in "-+~") end++
        end = minOf(text.length, end + 2)
        return TextRange(start, end)
    }
}

/**
 * Typing `{{`, `{%` or `{#` completes the pair and puts the caret inside, as the delimiters always come in twos.
 * An editor that already closed a `{` (CSS, JavaScript) only gets the missing part.
 */
class TeraTypedHandler : TypedHandlerDelegate() {
    private val closers = mapOf('{' to "}}", '%' to "%}", '#' to "#}")

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c !in closers || file.viewProvider.baseLanguage != TeraLanguage) return Result.CONTINUE
        val doc = editor.document
        val caret = editor.caretModel.offset
        if (caret < 2 || doc.charsSequence[caret - 2] != '{') return Result.CONTINUE
        // `{{` or `{%`/`{#`: not the middle of `{{{`.
        if (caret >= 3 && doc.charsSequence[caret - 3] == '{' && c == '{') return Result.CONTINUE
        val text = doc.charsSequence
        val closer = closers.getValue(c)
        val after = text.subSequence(caret, minOf(text.length, caret + 3)).toString()
        when {
            after.startsWith(" $closer") -> return Result.CONTINUE        // already complete
            after.startsWith(closer) -> {           // the editor already paired a `{`: add the spaces
                doc.insertString(caret, "  ")
                editor.caretModel.moveToOffset(caret + 1)
            }
            c == '{' && after.startsWith("}") -> {  // `{{` typed in front of a lone `}` from a pair the editor made
                doc.insertString(caret, "  }")
                editor.caretModel.moveToOffset(caret + 1)
            }
            else -> {
                doc.insertString(caret, "  $closer")
                editor.caretModel.moveToOffset(caret + 1)
            }
        }
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        return Result.STOP
    }
}

/** Backspace on the second delimiter of an empty `{{  }}` removes the closing half too. */
class TeraBackspaceHandler : BackspaceHandlerDelegate() {
    private var cleanupTo: IntRange? = null

    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
        cleanupTo = null
        if (c !in "{%#" || file.viewProvider.baseLanguage != TeraLanguage) return
        val text = editor.document.charsSequence
        val caret = editor.caretModel.offset               // after the char about to go
        if (caret < 2 || text[caret - 2] != '{') return
        val closer = when (c) { '{' -> "}}"; '%' -> "%}"; else -> "#}" }
        var end = caret
        while (end < text.length && text[end] == ' ') end++
        if (text.startsWith(closer, end) && (c != '{' || caret < 3 || text[caret - 3] != '{')) cleanupTo = caret until end + closer.length
    }

    override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean {
        val range = cleanupTo ?: return false
        cleanupTo = null
        // The deleted char took one position with it.
        val shifted = (range.first - 1) until (range.last)
        editor.document.deleteString(shifted.first, shifted.last + 1)
        return true
    }
}

/** Folds `block`, `for`, `if`, `macro`, `component`, `filter`, `raw` and capturing `set` down to their end tag. */
class TeraFoldingBuilder : FoldingBuilderEx(), DumbAware {

    private val openers = setOf("block", "for", "if", "macro", "component", "filter", "raw")
    private val closers = openers.associateBy { "end$it" } + ("endset" to "set")

    private class Open(val name: String, val range: TextRange)

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val text = document.text
        val out = mutableListOf<FoldingDescriptor>()
        val stack = ArrayDeque<Open>()
        for (block in TeraSymbolScanner.scanBlocks(text)) {
            if (block.kind != '%' || block.tokens.isEmpty()) continue
            val t = block.tokens
            val first = t[0].text
            val range = TeraBlockRanges.of(text, block)
            when {
                first in openers -> stack.addLast(Open(first, range))
                (first == "set" || first == "set_global") && t.none { it.type == TeraTokenTypes.OPERATOR && it.text == "=" } ->
                    stack.addLast(Open("set", range))
                first == "<" && t.getOrNull(1)?.text != "/" && t.getOrNull(1) != null -> stack.addLast(Open("<" + t[1].text, range))
                first in closers || (first == "<" && t.getOrNull(1)?.text == "/") -> {
                    val name = if (first == "<") "<" + (t.getOrNull(2)?.text ?: "") else closers.getValue(first)
                    val at = stack.indexOfLast { it.name == name }
                    if (at < 0) continue
                    val open = stack[at]
                    while (stack.size > at) stack.removeLast()
                    val whole = TextRange(open.range.startOffset, range.endOffset)
                    if (document.getLineNumber(whole.startOffset) != document.getLineNumber(whole.endOffset)) {
                        out.add(FoldingDescriptor(root.node, whole, null, placeholder(text, open.range)))
                    }
                }
            }
        }
        return out.toTypedArray()
    }

    private fun placeholder(text: String, tag: TextRange): String =
        text.substring(tag.startOffset, tag.endOffset).replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(57) + "..." else it } + "…"

    override fun getPlaceholderText(node: ASTNode): String = "{%…%}"
    override fun isCollapsedByDefault(node: ASTNode) = false
}

/** The live-template contexts: any Tera template, and the ones that only make sense in Tera 1 or only in Tera 2. */
class TeraTemplateContext : com.intellij.codeInsight.template.TemplateContextType("Tera") {
    override fun isInContext(context: com.intellij.codeInsight.template.TemplateActionContext) =
        context.file.viewProvider.baseLanguage == TeraLanguage
}

private fun versionContext(context: com.intellij.codeInsight.template.TemplateActionContext, version: TeraVersion): Boolean {
    val file = context.file
    if (file.viewProvider.baseLanguage != TeraLanguage) return false
    return TeraVersions.forFile(file.project, file.originalFile.virtualFile).version == version
}

class TeraV1TemplateContext : com.intellij.codeInsight.template.TemplateContextType("Tera 1") {
    override fun isInContext(context: com.intellij.codeInsight.template.TemplateActionContext) = versionContext(context, TeraVersion.V1)
}

class TeraV2TemplateContext : com.intellij.codeInsight.template.TemplateContextType("Tera 2") {
    override fun isInContext(context: com.intellij.codeInsight.template.TemplateActionContext) = versionContext(context, TeraVersion.V2)
}
