package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange

/** The type vocabulary of Tera 2 and how a value's kind is matched against it (mirrors `Type::matches_value`). */
object TeraTypeRules {
    /** The only types a component parameter may be given. */
    val COMPONENT_TYPES = listOf("string", "bool", "integer", "float", "number", "array", "map", "bytes")

    private fun isOp(t: TeraTok?, s: String) = t != null && t.type == TeraTokenTypes.OPERATOR && t.text == s

    /** `string`, `integer`, `float`, `bool`, `array`, `map` or `none` for a literal expression; null if it is not one. */
    fun literalKind(tokens: List<TeraTok>): String? {
        val t = if (isOp(tokens.firstOrNull(), "-")) tokens.drop(1) else tokens
        val first = t.firstOrNull() ?: return null
        return when {
            first.type == TeraTokenTypes.STRING && t.size == 1 -> "string"
            first.type == TeraTokenTypes.NUMBER && t.size == 1 -> if ('.' in first.text) "float" else "integer"
            first.type == TeraTokenTypes.KEYWORD && t.size == 1 && first.text in setOf("true", "false") -> "bool"
            first.type == TeraTokenTypes.IDENTIFIER && t.size == 1 && first.text in setOf("True", "False") -> "bool"
            first.type == TeraTokenTypes.IDENTIFIER && t.size == 1 && first.text in setOf("none", "None", "null") -> "none"
            isOp(first, "[") && closesAtTheEnd(t, "[", "]") -> "array"
            isOp(first, "{") && closesAtTheEnd(t, "{", "}") -> "map"
            else -> null
        }
    }

    /** True when the bracket that opens the first token is the one closed by the last token: `[1, 2]` yes, `[1][0]` no. */
    private fun closesAtTheEnd(t: List<TeraTok>, open: String, close: String): Boolean {
        var depth = 0
        for ((i, tok) in t.withIndex()) {
            if (isOp(tok, open)) depth++
            if (isOp(tok, close)) { depth--; if (depth == 0) return i == t.size - 1 }
        }
        return false
    }

    /** Same, for the text of a default value such as `"x"`, `-1.5` or `[1, 2]`. */
    fun literalKindOfText(text: String): String? {
        val s = text.trim()
        return when {
            s.startsWith("\"") || s.startsWith("'") -> "string"
            Regex("""-?\d+""").matches(s) -> "integer"
            Regex("""-?\d+\.\d+""").matches(s) -> "float"
            s == "true" || s == "false" || s == "True" || s == "False" -> "bool"
            s.startsWith("[") -> "array"
            s.startsWith("{") -> "map"
            s == "none" || s == "None" || s == "null" -> "none"
            else -> null
        }
    }

    /** Does a value of [kind] satisfy the declared parameter type? `number` takes integers and floats, `float` only floats. */
    fun matches(declared: String, kind: String): Boolean = when (declared) {
        "string" -> kind == "string"
        "bool" -> kind == "bool"
        "integer" -> kind == "integer"
        "float" -> kind == "float"
        "number" -> kind == "integer" || kind == "float"
        "array" -> kind == "array"
        "map" -> kind == "map"
        "bytes" -> kind == "bytes"
        else -> true
    }

    /** The type Tera enforces for a parameter: the annotation, else the one inferred from the default value. */
    fun effectiveType(p: ComponentParam): String? {
        p.type?.lowercase()?.takeIf { it in COMPONENT_TYPES }?.let { return it }
        if (p.type != null) return null
        return p.defaultText?.let(::literalKindOfText)?.takeIf { it != "none" }
    }

    /** The kind of an inferred [TeraType], or null when nothing is known. */
    fun kindOf(t: TeraType): String? = when (t) {
        is TeraType.Scalar -> when (t.name) { "string" -> "string"; "number" -> "number"; "bool" -> "bool"; else -> null }
        is TeraType.ArrayOf, is TeraType.Tuple -> "array"
        is TeraType.MapOf, is TeraType.Struct -> "map"
        TeraType.Unknown -> null
    }

    /** An inferred `number` may be an integer or a float; everything else must match exactly. */
    fun matchesInferred(declared: String, kind: String): Boolean = when (declared) {
        "integer", "float", "number" -> kind == "number"
        "string", "bool", "array", "map" -> declared == kind
        else -> true
    }

    fun accepts(expected: List<String>, kind: String) = expected.isEmpty() || kind in expected
}

/**
 * Type problems that Tera 2 would only report while rendering: unknown or mismatched component parameter types, values of the
 * wrong type for a component attribute, and filters or tests applied to a value of a kind they do not accept.
 */
object TeraTypeChecker {

    private val BOUNDARY_OPS = setOf("(", "[", "{", ",", "=", "==", "!=", "<", ">", "<=", ">=", "+", "-", "*", "/", "//", "%", "**", "~", ":", "?", "!")
    private val BOUNDARY_KEYWORDS = setOf("and", "or", "not", "in", "is", "if", "else", "elif", "for", "set", "set_global")

    private fun msg(key: String, vararg p: Any) = TeraBundle.message(key, *p)
    private fun isOp(t: TeraTok?, s: String) = t != null && t.type == TeraTokenTypes.OPERATOR && t.text == s

    fun check(
        text: String,
        context: TeraContextResult?,
        components: List<TeraComponent>,
        custom: List<TeraCustomBuiltin> = emptyList(),
    ): List<TeraError> {
        val blocks = TeraSymbolScanner.scanBlocks(text)
        val snapshots = TeraScopes.snapshots(blocks, context, TeraVersion.V2, text)
        val errors = mutableListOf<TeraError>()
        componentDefinitions(text, blocks, errors)
        componentCalls(text, blocks, snapshots, context, components, errors)
        filtersAndTests(blocks, snapshots, context, custom, errors)
        return errors.sortedBy { it.range.startOffset }
    }

    // ── component definitions ────────────────────────────────────────────────

    private fun componentDefinitions(text: String, blocks: List<TeraBlock>, out: MutableList<TeraError>) {
        for (def in TeraComponentScanner.definitions(text, blocks)) for (p in def.params) {
            val written = p.type
            val declared = written?.lowercase()
            if (written != null && declared !in TeraTypeRules.COMPONENT_TYPES) {
                out.add(TeraError(msg("component.type.unknown", written), p.typeRange ?: p.range, HighlightSeverity.ERROR))
                continue
            }
            val default = p.defaultText ?: continue
            val kind = TeraTypeRules.literalKindOfText(default) ?: continue
            if (declared != null && kind != "none" && !TeraTypeRules.matches(declared, kind)) {
                out.add(TeraError(msg("component.default.type", p.name, kind, declared), p.defaultRange ?: p.range, HighlightSeverity.ERROR))
            }
        }
    }

    // ── component calls ──────────────────────────────────────────────────────

    private fun componentCalls(
        text: String, blocks: List<TeraBlock>, snapshots: List<TeraScopeSnapshot>, context: TeraContextResult?,
        components: List<TeraComponent>, out: MutableList<TeraError>,
    ) {
        val byName = components.groupBy { it.def.name }
        for (call in TeraComponentScanner.calls(blocks)) {
            val def = byName[call.name]?.first()?.def ?: continue
            val tokens = blocks.getOrNull(call.blockIndex)?.tokens.orEmpty()
            val snapshot = snapshots.getOrNull(call.blockIndex) ?: continue
            for (attr in call.attrs) {
                val name = attr.name ?: continue
                val param = def.params.firstOrNull { it.name == name && !it.rest } ?: continue
                val declared = TeraTypeRules.effectiveType(param) ?: continue
                val valueRange = attr.valueRange
                var certainKind: String? = null
                var inferred: TeraType = TeraType.Unknown
                val where: TextRange
                if (valueRange == null) {                                  // shorthand: `<c name/>` reads the variable `name`
                    val tok = tokens.firstOrNull { it.range == attr.range } ?: continue
                    inferred = TeraScopes.evalType(listOf(tok), snapshot, context)
                    where = attr.range
                } else {
                    val v = text.substring(valueRange.startOffset, valueRange.endOffset)
                    where = valueRange
                    if (v.startsWith("\"") || v.startsWith("'")) certainKind = "string"
                    else if (v.startsWith("{")) {
                        val inner = tokens.filter { it.range.startOffset > valueRange.startOffset && it.range.endOffset < valueRange.endOffset }
                        certainKind = TeraTypeRules.literalKind(inner)
                        if (certainKind == null) inferred = TeraScopes.evalType(inner, snapshot, context)
                    }
                }
                if (certainKind != null) {
                    if (certainKind != "none" && !TeraTypeRules.matches(declared, certainKind)) {
                        out.add(TeraError(msg("component.attr.type", name, certainKind, declared), where, HighlightSeverity.ERROR))
                    }
                } else {
                    val kind = TeraTypeRules.kindOf(inferred) ?: continue
                    if (!TeraTypeRules.matchesInferred(declared, kind)) {
                        out.add(TeraError(msg("component.attr.type", name, inferred.display(), declared), where, HighlightSeverity.WARNING))
                    }
                }
            }
        }
    }

    // ── filters and tests ────────────────────────────────────────────────────

    /** The tokens of the operand that ends right before the `|` / `is` at [barIndex]. */
    internal fun operandBefore(t: List<TeraTok>, barIndex: Int, from: Int): List<TeraTok> {
        var depth = 0
        var j = barIndex - 1
        while (j >= from) {
            val tok = t[j]
            if (tok.type == TeraTokenTypes.OPERATOR) {
                when {
                    tok.text in setOf(")", "]", "}") -> depth++
                    tok.text in setOf("(", "[", "{") -> if (depth == 0) break else depth--
                    depth == 0 && tok.text in BOUNDARY_OPS -> break
                }
            } else if (depth == 0 && tok.type == TeraTokenTypes.KEYWORD && tok.text in BOUNDARY_KEYWORDS) break
            j--
        }
        return t.subList(j + 1, barIndex)
    }

    private fun filtersAndTests(
        blocks: List<TeraBlock>, snapshots: List<TeraScopeSnapshot>, context: TeraContextResult?,
        custom: List<TeraCustomBuiltin>, out: MutableList<TeraError>,
    ) {
        val defs = listOf("filter", "test").associateWith { TeraBuiltins.byName(it, custom, TeraVersion.V2) }
        for (use in TeraCalls.scan(blocks)) {
            val def = defs[use.kind]?.get(use.name.text) ?: continue          // functions have no input
            if (def.source != null || def.input.isEmpty()) continue
            val block = blocks[use.blockIndex]
            val t = block.tokens
            val from = TeraCalls.expressionStart(block) ?: continue
            val bar = when {
                use.kind == "filter" -> use.index - 1
                t.getOrNull(use.index - 1)?.text == "not" -> use.index - 2
                else -> use.index - 1
            }
            if (bar < from || (use.kind == "filter" && !isOp(t[bar], "|"))) continue      // `{% filter upper %}`: no operand
            val operand = operandBefore(t, bar, from)
            if (operand.isEmpty()) continue
            val literal = TeraTypeRules.literalKind(operand)
            val type = TeraScopes.evalType(operand, snapshots[use.blockIndex], context)
            val kind = literal?.takeIf { it != "none" }?.let { if (it == "integer" || it == "float") "number" else it } ?: TeraTypeRules.kindOf(type) ?: continue
            if (TeraTypeRules.accepts(def.input, kind)) continue
            val shown = if (literal != null) literal else type.display()
            val certain = literal != null
            out.add(TeraError(
                msg(if (use.kind == "filter") "type.filter.input" else "type.test.input", def.name, def.input.joinToString(" or "), shown),
                use.name.range, if (certain) HighlightSeverity.ERROR else HighlightSeverity.WARNING,
                conversionFixes(def, kind, operand),
            ))
        }
    }

    /** `{{ count | upper }}` → `{{ count | str | upper }}`; `{{ "3" | round }}` → `{{ "3" | float | round }}`. */
    private fun conversionFixes(def: BuiltinDef, actual: String, operand: List<TeraTok>): List<TeraFix> {
        val convert = when {
            "string" in def.input && (actual == "number" || actual == "bool") -> "str"
            "number" in def.input && actual == "string" -> if (def.name == "pluralize") "int" else "float"
            else -> return emptyList()
        }
        val at = operand.last().range.endOffset
        return listOf(TeraFix(msg("type.fix.convert", convert), listOf(TeraEdit(TextRange(at, at), " | $convert"))))
    }
}
