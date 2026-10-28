package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange

/**
 * Mistakes in what `{{ }}` prints: a list or map written as it is (Tera 1 prints a list as `[a, b]` and an object as
 * `[object]`, Tera 2 prints JSON; either is almost never what a page wants), and a filter chain that stops after `|`.
 */
object TeraPrintChecks {

    /** `{{ x | }}`: a `|` with no filter name after it. */
    fun dangling(text: String): List<TeraError> {
        val errors = mutableListOf<TeraError>()
        for (block in TeraSymbolScanner.scanBlocks(text)) {
            val t = block.tokens
            for ((i, tok) in t.withIndex()) {
                if (tok.type != TeraTokenTypes.OPERATOR || tok.text != "|") continue
                val next = t.getOrNull(i + 1)
                if (next != null && next.type == TeraTokenTypes.IDENTIFIER) continue
                val before = t.getOrNull(i - 1)
                val fix = if (next == null && before != null)
                    listOf(TeraFix("Remove the trailing |", listOf(TeraEdit(TextRange(before.range.endOffset, tok.range.endOffset), ""))))
                else emptyList()
                errors.add(TeraError("A filter name is expected after '|'", tok.range, HighlightSeverity.ERROR, fix))
            }
        }
        return errors
    }

    fun printed(
        text: String, context: TeraContextResult?, version: TeraVersion, custom: List<TeraCustomBuiltin> = emptyList(),
    ): List<TeraError> {
        if (context == null) return emptyList()
        val blocks = TeraSymbolScanner.scanBlocks(text)
        val occurrences = TeraVariables.collect(blocks, TeraScopes.snapshots(blocks, context, version, text))
        val errors = mutableListOf<TeraError>()
        for (block in blocks) {
            if (block.kind != '{' || !block.closed || block.tokens.isEmpty()) continue
            val target = TeraIterate.targetIn(text, blocks, block, context, version, custom, occurrences) ?: continue
            val (message, severity) = when (val type = target.type) {
                is TeraType.ArrayOf, is TeraType.Tuple ->
                    (if (version == TeraVersion.V2) "'${target.expr}' is a list: it is printed as JSON"
                    else "'${target.expr}' is a list: it is printed as [a, b], without quotes or structure") to HighlightSeverity.WEAK_WARNING
                is TeraType.MapOf, is TeraType.Struct ->
                    (if (version == TeraVersion.V2) "'${target.expr}' is an object: it is printed as JSON"
                    else "'${target.expr}' is an object: Tera 1 prints it as [object]") to
                        (if (version == TeraVersion.V2) HighlightSeverity.WEAK_WARNING else HighlightSeverity.WARNING)
                else -> continue
            }
            val whole = TextRange(target.blockStart, target.blockEnd)
            val range = TextRange(block.tokens.first().range.startOffset, block.tokens.last().range.endOffset)
            errors.add(TeraError(message, range, severity, fixes(target, whole)))
        }
        return errors
    }

    private fun fixes(target: TeraIterate.Target, whole: TextRange): List<TeraFix> {
        val type = target.type
        if (type is TeraType.Struct) {
            return type.fields.filter { it.type is TeraType.Scalar }.take(3).map {
                TeraFix("Print ${target.expr}.${it.name}", listOf(TeraEdit(whole, "{{ ${target.expr}.${it.name} }}")))
            }
        }
        return TeraIterate.snippets(target.expr, type, target.name, target.item).map {
            TeraFix(it.title, listOf(TeraEdit(whole, it.text)))
        }
    }
}
