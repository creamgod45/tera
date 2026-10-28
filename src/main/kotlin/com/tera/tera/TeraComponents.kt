package com.tera.tera

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * A parameter of a Tera 2 component: `name`, `name: type = default`, an implicit `@name` read from the caller's
 * scope, or the spread `...rest` that collects every other attribute into a map.
 */
data class ComponentParam(
    val name: String,
    val type: String?,
    val defaultText: String?,
    val implicit: Boolean,
    val rest: Boolean,
    val range: TextRange,
    /** Where the written type annotation and the default value are, for diagnostics. */
    val typeRange: TextRange? = null,
    val defaultRange: TextRange? = null,
) {
    val hasDefault get() = defaultText != null
    /** Must be given as an attribute: no default, not implicit, not the rest parameter. */
    val required get() = !hasDefault && !implicit && !rest
}

/** `{% component ui.button(label: string, variant = "primary", @lang, ...rest) %}` */
data class ComponentDef(
    val name: String,
    val params: List<ComponentParam>,
    val nameRange: TextRange,
    /** Offset of the `)` that ends the parameter list, where new parameters are inserted. */
    val closeParen: Int = -1,
) {
    val requiredParams get() = params.filter { it.required }
    val hasRest get() = params.any { it.rest }

    fun signature(): String = params.joinToString(", ", "$name(", ")") { p ->
        buildString {
            if (p.rest) append("...") else if (p.implicit) append("@")
            append(p.name)
            p.type?.let { append(": ").append(it) }
            p.defaultText?.let { append(" = ").append(it) }
        }
    }
}

/** One attribute written in a component call: `name`, `name="x"`, `name={expr}` or `{...spread}`. */
data class ComponentAttr(
    val name: String?,
    val spread: Boolean,
    val range: TextRange,
    val nameRange: TextRange?,
    /** The `"x"` or `{expr}` after the `=`; null for a shorthand attribute (`<c name/>`) or a spread. */
    val valueRange: TextRange? = null,
)

/** `<name attr="x" other={1}/>`, or the opening `<name ...>` of a call with a body. */
data class ComponentCall(
    val name: String,
    val nameRange: TextRange,
    val attrs: List<ComponentAttr>,
    val closed: Boolean,
    val range: TextRange,
    val blockIndex: Int = -1,
)

object TeraComponentScanner {
    private fun isOp(t: TeraTok?, s: String) = t != null && t.type == TeraTokenTypes.OPERATOR && t.text == s
    private fun isNameLike(t: TeraTok?) = t != null && (t.type == TeraTokenTypes.IDENTIFIER || t.type == TeraTokenTypes.KEYWORD)

    /** `a.b.c` starting at [i]; returns the joined name and the index after it. */
    private fun dottedName(t: List<TeraTok>, i: Int): Pair<String, Int>? {
        if (t.getOrNull(i)?.type != TeraTokenTypes.IDENTIFIER) return null
        val sb = StringBuilder(t[i].text)
        var j = i + 1
        while (isOp(t.getOrNull(j), ".") && t.getOrNull(j + 1)?.type == TeraTokenTypes.IDENTIFIER) {
            sb.append('.').append(t[j + 1].text)
            j += 2
        }
        return sb.toString() to j
    }

    // ── definitions ──────────────────────────────────────────────────────────

    fun definitions(text: String, blocks: List<TeraBlock> = TeraSymbolScanner.scanBlocks(text)): List<ComponentDef> {
        val out = mutableListOf<ComponentDef>()
        for (block in blocks) {
            val t = block.tokens
            if (block.kind != '%' || t.isEmpty() || t[0].text != "component") continue
            val (name, after) = dottedName(t, 1) ?: continue
            if (!isOp(t.getOrNull(after), "(")) continue
            val nameRange = TextRange(t[1].range.startOffset, t[after - 1].range.endOffset)
            val args = TeraSymbolScanner.splitArgs(t, after)
            val params = args.groups.mapNotNull { parseParam(text, it) }
            out.add(ComponentDef(name, params, nameRange, args.closeIndex.let { if (it >= 0) t[it].range.startOffset else -1 }))
        }
        return out
    }

    private fun parseParam(text: String, g: List<TeraTok>): ComponentParam? {
        var i = 0
        var implicit = false
        var rest = false
        if (isOp(g.getOrNull(0), ".") && isOp(g.getOrNull(1), ".") && isOp(g.getOrNull(2), ".")) { rest = true; i = 3 }
        else if (isOp(g.getOrNull(0), "@")) { implicit = true; i = 1 }
        val nameTok = g.getOrNull(i)?.takeIf { isNameLike(it) } ?: return null
        i++
        var type: String? = null
        var typeRange: TextRange? = null
        if (isOp(g.getOrNull(i), ":")) {
            val start = i + 1
            var end = start
            while (end < g.size && !isOp(g[end], "=")) end++
            if (end > start) {
                typeRange = TextRange(g[start].range.startOffset, g[end - 1].range.endOffset)
                type = text.substring(typeRange.startOffset, typeRange.endOffset).trim()
            }
            i = end
        }
        var default: String? = null
        var defaultRange: TextRange? = null
        if (isOp(g.getOrNull(i), "=") && i + 1 < g.size) {
            defaultRange = TextRange(g[i + 1].range.startOffset, g.last().range.endOffset)
            default = text.substring(defaultRange.startOffset, defaultRange.endOffset).trim()
        }
        return ComponentParam(nameTok.text, type, default, implicit, rest,
            TextRange(g.first().range.startOffset, g.last().range.endOffset), typeRange, defaultRange)
    }

    // ── calls ────────────────────────────────────────────────────────────────

    /** Every `<name .../>` call: in `{{ }}`, and the opening tag of `{% <name> %}`. Closing tags are not calls. */
    fun calls(blocks: List<TeraBlock>): List<ComponentCall> {
        val out = mutableListOf<ComponentCall>()
        for ((blockIndex, block) in blocks.withIndex()) {
            val t = block.tokens
            if (t.isEmpty()) continue
            if (block.kind == '%') {
                if (isOp(t[0], "<")) {
                    if (!isOp(t.getOrNull(1), "/")) parseCall(t, 0)?.let { out.add(it.first.copy(blockIndex = blockIndex)) }
                    continue
                }
            }
            // `{{ ... }}`, and the expression part of `{% if/elif/for/set/filter %}`.
            var i = TeraCalls.expressionStart(block) ?: continue
            while (i < t.size) {
                val prev = t.getOrNull(i - 1)
                val startsOperand = prev == null ||
                    (prev.type == TeraTokenTypes.OPERATOR && prev.text !in setOf(")", "]", "}")) ||
                    (prev.type == TeraTokenTypes.KEYWORD && prev.text in setOf("and", "or", "not", "in", "if", "elif", "else"))
                if (isOp(t[i], "<") && startsOperand && t.getOrNull(i + 1)?.type == TeraTokenTypes.IDENTIFIER &&
                    t[i + 1].range.startOffset == t[i].range.endOffset
                ) {
                    val parsed = parseCall(t, i)
                    if (parsed != null) { out.add(parsed.first.copy(blockIndex = blockIndex)); i = parsed.second; continue }
                }
                i++
            }
        }
        return out
    }

    private fun parseCall(t: List<TeraTok>, start: Int): Pair<ComponentCall, Int>? {
        val (name, afterName) = dottedName(t, start + 1) ?: return null
        val nameRange = TextRange(t[start + 1].range.startOffset, t[afterName - 1].range.endOffset)
        val attrs = mutableListOf<ComponentAttr>()
        var j = afterName
        var closed = false
        while (j < t.size) {
            val tok = t[j]
            when {
                isOp(tok, "/") && isOp(t.getOrNull(j + 1), ">") -> { closed = true; j += 2; break }
                isOp(tok, ">") -> { closed = true; j += 1; break }
                isOp(tok, "{") && isOp(t.getOrNull(j + 1), ".") && isOp(t.getOrNull(j + 2), ".") && isOp(t.getOrNull(j + 3), ".") -> {
                    val end = matchingBrace(t, j)
                    attrs.add(ComponentAttr(null, true, TextRange(tok.range.startOffset, t[end].range.endOffset), null))
                    j = end + 1
                }
                isNameLike(tok) -> {
                    if (isOp(t.getOrNull(j + 1), "=")) {
                        val v = t.getOrNull(j + 2) ?: break
                        val endIdx = if (isOp(v, "{")) matchingBrace(t, j + 2) else j + 2
                        attrs.add(ComponentAttr(tok.text, false, TextRange(tok.range.startOffset, t[endIdx].range.endOffset), tok.range,
                            TextRange(v.range.startOffset, t[endIdx].range.endOffset)))
                        j = endIdx + 1
                    } else {
                        attrs.add(ComponentAttr(tok.text, false, tok.range, tok.range))
                        j++
                    }
                }
                else -> break
            }
        }
        val endOffset = t.getOrNull(j - 1)?.range?.endOffset ?: nameRange.endOffset
        return ComponentCall(name, nameRange, attrs, closed, TextRange(t[start].range.startOffset, endOffset)) to j
    }

    private fun matchingBrace(t: List<TeraTok>, open: Int): Int {
        var depth = 0
        for (k in open until t.size) {
            if (isOp(t[k], "{")) depth++
            if (isOp(t[k], "}")) { depth--; if (depth == 0) return k }
        }
        return t.size - 1
    }
}

/** A component and where it is defined. [file] is null for the template being edited. */
class TeraComponent(val def: ComponentDef, val file: VirtualFile?)

/** Components are global in Tera 2: every `component` tag in any template of the project defines one. */
object TeraComponents {

    private fun textOf(f: VirtualFile): String = TeraFiles.textOf(f)

    /** All definitions in the project's templates, cached until any PSI changes. */
    fun project(project: Project): List<TeraComponent> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val found = TeraFiles.projectFiles(project, "tera")
                .filter { "/target/" !in it.path }
                .sortedBy { it.path }
                .flatMap { f ->
                    val text = textOf(f)
                    if (!text.contains("component")) emptyList()
                    else TeraComponentScanner.definitions(text).map { TeraComponent(it, f) }
                }
            CachedValueProvider.Result.create(found, PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** The components visible in [file], whose current (maybe unsaved) text replaces what the project index has. */
    fun visible(project: Project, file: VirtualFile?, currentText: String): List<TeraComponent> {
        val local = TeraComponentScanner.definitions(currentText).map { TeraComponent(it, null) }
        val others = project(project).filter { it.file != file }
        return local + others
    }
}
