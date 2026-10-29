package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange

/**
 * One variable path (`a.b[0].c`) used in an expression.
 * [tokens] holds one token per segment (`[` for an index); [optional] is true when the path is
 * allowed to be undefined (`x is defined`, `x | default(...)`).
 */
class VarOccurrence(
    val snapshot: TeraScopeSnapshot,
    val tokens: List<TeraTok>,
    val segs: List<PathSeg>,
    val optional: Boolean,
    val blockIndex: Int = -1,
) {
    fun resolve(upTo: Int, context: TeraContextResult?): PathResult =
        TeraScopes.resolvePath(segs.take(upTo + 1), snapshot, context)

    /** Index of the segment whose token touches [offset], or -1. */
    fun segmentAt(offset: Int): Int =
        tokens.indexOfFirst { it.type != TeraTokenTypes.OPERATOR && offset in it.range.startOffset..it.range.endOffset }
}

object TeraVariables {

    private fun isOp(t: TeraTok?, s: String) = t != null && t.type == TeraTokenTypes.OPERATOR && t.text == s
    private fun isName(t: TeraTok?) =
        t != null && (t.type == TeraTokenTypes.IDENTIFIER || (t.type == TeraTokenTypes.KEYWORD && t.text == "loop"))

    /** Every variable path in `{{ }}` blocks and in the expression parts of `if`/`elif`/`for`/`set`. */
    fun collect(blocks: List<TeraBlock>, snapshots: List<TeraScopeSnapshot>): List<VarOccurrence> {
        val result = mutableListOf<VarOccurrence>()
        // `<name .../>`: the component name is not a variable.
        val componentNames = TeraComponentScanner.calls(blocks).map { it.nameRange }
        for ((bi, block) in blocks.withIndex()) {
            val t = block.tokens
            val from = when {
                block.kind == '{' -> 0
                t.isEmpty() || t[0].type != TeraTokenTypes.KEYWORD -> continue
                t[0].text == "if" || t[0].text == "elif" -> 1
                t[0].text == "for" -> t.indexOfFirst { it.type == TeraTokenTypes.KEYWORD && it.text == "in" }
                    .let { if (it < 0) continue else it + 1 }
                t[0].text == "set" || t[0].text == "set_global" -> t.indexOfFirst { isOp(it, "=") }
                    .let { if (it < 0) continue else it + 1 }
                else -> continue
            }
            val bound = comprehensionScopes(t)
            var i = from
            while (i < t.size) {
                val tok = t[i]
                if (!isName(tok) || !startsPath(t, i) || bound.any { tok.text in it.names && i in it.range } || componentNames.any { tok.range.startOffset >= it.startOffset && tok.range.endOffset <= it.endOffset }) { i++; continue }
                val segs = mutableListOf(PathSeg(tok.text))
                val toks = mutableListOf(tok)
                var j = i + 1
                while (j < t.size) {
                    if (isOp(t[j], ".") && (isName(t.getOrNull(j + 1)) || t.getOrNull(j + 1)?.type == TeraTokenTypes.NUMBER)) {
                        segs.add(PathSeg(t[j + 1].text)); toks.add(t[j + 1]); j += 2
                    } else if (isOp(t[j], "[")) {
                        segs.add(PathSeg("[]", true)); toks.add(t[j])
                        var depth = 0
                        while (j < t.size) {
                            if (t[j].type == TeraTokenTypes.OPERATOR && t[j].text in setOf("[", "(", "{")) depth++
                            if (t[j].type == TeraTokenTypes.OPERATOR && t[j].text in setOf("]", ")", "}")) { depth--; if (depth == 0) { j++; break } }
                            j++
                        }
                    } else break
                }
                val next = t.getOrNull(j)
                val after = t.getOrNull(j + 1)
                val optional = (next != null && next.type == TeraTokenTypes.KEYWORD && next.text == "is") ||
                    (isOp(next, "|") && after?.text == "default")
                if (!isOp(next, "(") && !isOp(next, "=") && !isOp(next, ":")) {
                    result.add(VarOccurrence(snapshots[bi], toks, segs, optional, bi))
                }
                i = j
            }
        }
        return result
    }

    /**
     * A Tera 2 comprehension (`[x.a for x in xs if x.ok]`, `{k: v for k, v in m}`) inside one block.
     * [vars] are the names it binds, valid between [open] and [close] only; [tokens] is the block's list
     * and [range] the indexes of [open]..[close] in it.
     */
    class Comprehension(
        val tokens: List<TeraTok>,
        val range: IntRange,
        val forIndex: Int,
        val inIndex: Int,
        val vars: List<TeraTok>,
    ) {
        val open: TeraTok get() = tokens[range.first]
        val forTok: TeraTok get() = tokens[forIndex]
        val inTok: TeraTok get() = tokens[inIndex]
        val isMap: Boolean get() = open.text == "{"
        val names: Set<String> get() = vars.map { it.text }.toSet()

        fun element(text: String): String =
            text.substring(open.range.endOffset, forTok.range.startOffset).trim()

        /** What is looped over: from after `in` up to a trailing `if` or the closing bracket. */
        fun source(text: String): String {
            val end = (inIndex + 1..range.last).firstOrNull { it == range.last || (tokens[it].type == TeraTokenTypes.KEYWORD && tokens[it].text == "if") }
                ?: range.last
            val to = if (end >= tokens.size) text.length else tokens[end].range.startOffset
            return text.substring(inTok.range.endOffset, to).trim()
        }
    }

    private fun isOpen(t: TeraTok) = t.type == TeraTokenTypes.OPERATOR && t.text in setOf("[", "(", "{")
    private fun isClose(t: TeraTok) = t.type == TeraTokenTypes.OPERATOR && t.text in setOf("]", ")", "}")

    fun comprehensions(t: List<TeraTok>): List<Comprehension> {
        val closeOf = mutableMapOf<Int, Int>()
        val open = ArrayDeque<Int>()
        for ((i, tok) in t.withIndex()) {
            if (isOpen(tok)) open.addLast(i) else if (isClose(tok)) open.removeLastOrNull()?.let { closeOf[it] = i }
        }
        val result = mutableListOf<Comprehension>()
        val stack = ArrayDeque<Int>()
        for ((i, tok) in t.withIndex()) {
            if (isOpen(tok)) stack.addLast(i)
            else if (isClose(tok)) stack.removeLastOrNull()
            else if (tok.type == TeraTokenTypes.KEYWORD && tok.text == "for" && stack.isNotEmpty()) {
                val inAt = (i + 1 until t.size).firstOrNull { t[it].type == TeraTokenTypes.KEYWORD && t[it].text == "in" } ?: continue
                val vars = (i + 1 until inAt).filter { isName(t[it]) }.map { t[it] }
                val start = stack.last()
                if (t[start].text == "(") continue
                result.add(Comprehension(t, start..(closeOf[start] ?: (t.size - 1)), i, inAt, vars))
            }
        }
        return result
    }

    private fun comprehensionScopes(t: List<TeraTok>) = comprehensions(t)

    /** True when the name at [i] is a variable reference, not a filter/test/kwarg/namespace/member name. */
    private fun startsPath(t: List<TeraTok>, i: Int): Boolean {
        val prev = t.getOrNull(i - 1)
        if (isOp(prev, ".") || isOp(prev, "|") || isOp(prev, ":")) return false
        if (prev != null && prev.type == TeraTokenTypes.KEYWORD && prev.text == "is") return false
        if (prev != null && prev.type == TeraTokenTypes.KEYWORD && prev.text == "not" &&
            t.getOrNull(i - 2)?.text == "is"
        ) return false
        if (prev != null && prev.text == "as") return false
        return true
    }

    // ── diagnostics ──────────────────────────────────────────────────────────

    /**
     * Reports variables that do not exist anywhere (weak warning) and fields that a known struct
     * lacks. Only runs when [context] is [TeraContextResult.complete], so partial knowledge never
     * produces noise.
     */
    fun check(text: String, context: TeraContextResult?, version: TeraVersion = TeraVersion.V1): List<TeraError> {
        if (context == null || !context.complete) return emptyList()
        val blocks = TeraSymbolScanner.scanBlocks(text)
        val snapshots = TeraScopes.snapshots(blocks, context, version, text)
        val errors = mutableListOf<TeraError>()
        for (occ in collect(blocks, snapshots)) {
            if (occ.optional) continue
            val r = occ.resolve(occ.segs.size - 1, context)
            if (r.failedAt < 0) continue
            val tok = occ.tokens[r.failedAt]
            if (r.failedAt == 0) {
                // Undefined names inside a Tera 2 component are reported by the version check, with a fix.
                if (occ.snapshot.hidesContext) continue
                errors.add(TeraError(TeraBundle.message("semantic.var.undefined", tok.text), tok.range, HighlightSeverity.WEAK_WARNING))
            } else {
                errors.add(TeraError(
                    TeraBundle.message("semantic.field.missing", tok.text, r.failedOn.orEmpty()),
                    tok.range, HighlightSeverity.WARNING,
                ))
            }
        }
        return errors
    }

    /** The occurrence segment under [offset], with what it refers to. */
    class Ref(val occurrence: VarOccurrence, val index: Int, val result: PathResult, val range: TextRange)

    fun refAt(text: String, offset: Int, context: TeraContextResult?, version: TeraVersion = TeraVersion.V1): Ref? {
        val blocks = TeraSymbolScanner.scanBlocks(text)
        val snapshots = TeraScopes.snapshots(blocks, context, version, text)
        for (occ in collect(blocks, snapshots)) {
            val idx = occ.segmentAt(offset)
            if (idx < 0) continue
            val r = occ.resolve(idx, context)
            if (r.failedAt >= 0) return null
            return Ref(occ, idx, r, occ.tokens[idx].range)
        }
        return null
    }

    /** What hovering part of a comprehension refers to; [part] is "keyword", "variable" or "bracket". */
    class ComprehensionRef(
        val comp: Comprehension,
        val part: String,
        val token: TeraTok,
        val element: String,
        val source: String,
        val sourceType: TeraType?,
        /** Type of [token] when it is one of the bound variables and the source type is known. */
        val varType: TeraType?,
    )

    fun comprehensionAt(text: String, offset: Int, context: TeraContextResult?, version: TeraVersion): ComprehensionRef? {
        if (version != TeraVersion.V2) return null
        val blocks = TeraSymbolScanner.scanBlocks(text)
        for ((bi, block) in blocks.withIndex()) {
            val t = block.tokens
            val idx = t.indexOfFirst { offset in it.range.startOffset..it.range.endOffset && it.type != TeraTokenTypes.OPERATOR || (it.type == TeraTokenTypes.OPERATOR && offset in it.range.startOffset until it.range.endOffset) }
            if (idx < 0) continue
            val comp = comprehensions(t).filter { idx in it.range }.minByOrNull { it.range.last - it.range.first } ?: continue
            val tok = t[idx]
            val part = when {
                idx == comp.forIndex || idx == comp.inIndex -> "keyword"
                idx == comp.range.first -> "bracket"
                isName(tok) && tok.text in comp.names -> "variable"
                else -> continue
            }
            var sourceType: TeraType? = null
            if (context != null) {
                val occ = collect(blocks, TeraScopes.snapshots(blocks, context, version, text))
                    .firstOrNull { it.blockIndex == bi && it.tokens[0].range == t.getOrNull(comp.inIndex + 1)?.range }
                occ?.resolve(occ.segs.size - 1, context)?.takeIf { it.failedAt < 0 }?.let { sourceType = it.type }
            }
            val varType = if (part != "variable") null else {
                val n = comp.vars.indexOfFirst { it.text == tok.text }
                val st = sourceType
                when {
                    st is TeraType.ArrayOf && comp.vars.size == 1 -> st.element
                    st is TeraType.MapOf && comp.vars.size == 2 -> if (n == 0) TeraType.Scalar("string") else st.value
                    else -> null
                }
            }
            return ComprehensionRef(comp, part, tok, comp.element(text), comp.source(text), sourceType, varType)
        }
        return null
    }
}
