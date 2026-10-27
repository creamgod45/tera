package com.tera.tera

import com.intellij.openapi.util.TextRange

/** A name introduced inside the template: `set`, a `for` variable, a macro parameter, or `loop`. */
data class TeraBinding(
    val name: String,
    val type: TeraType,
    val kind: String,
    val nameRange: TextRange?,
    val detail: String? = null,
)

/**
 * What can be referenced at one point of a template. Inside a Tera 2 component ([hidesContext]) the render context is
 * not visible; a Tera 1 macro sees the context but not the `set` and loop variables around it.
 */
class TeraScopeSnapshot(val bindings: List<TeraBinding>, val inLoop: Boolean, val hidesContext: Boolean)

data class PathSeg(val name: String, val index: Boolean = false)

/**
 * Result of walking `a.b[0].c`. [failedAt] is the index of the segment that does not exist
 * (-1 when everything resolved or the type became unknown); [failedOn] names the struct that lacks
 * the field, or is null when the root variable itself is undefined.
 */
class PathResult(
    val type: TeraType,
    val field: TeraField?,
    val binding: TeraBinding?,
    val failedAt: Int = -1,
    val failedOn: String? = null,
)

object TeraScopes {

    private class Frame(val kind: String) {
        val bindings = mutableListOf<TeraBinding>()
    }

    private val CLOSERS = mapOf(
        "endfor" to "for", "endif" to "if", "endblock" to "block",
        "endmacro" to "macro", "endraw" to "raw", "endfilter" to "filter",
        "endcomponent" to "component", "endset" to "set",
    )
    private val OPENERS = CLOSERS.values.toSet()

    val LOOP_TYPE = TeraType.Struct("loop", listOf(
        TeraField("index", TeraType.Scalar("number"), doc = "1-based index of the current iteration"),
        TeraField("index0", TeraType.Scalar("number"), doc = "0-based index of the current iteration"),
        TeraField("first", TeraType.Scalar("bool"), doc = "true on the first iteration"),
        TeraField("last", TeraType.Scalar("bool"), doc = "true on the last iteration"),
    ))

    val LOOP_TYPE_V2 = TeraType.Struct("loop", LOOP_TYPE.fields + TeraField(
        "length", TeraType.Scalar("number"), doc = "number of items in the loop"))

    private fun isOp(t: TeraTok?, s: String) = t != null && t.type == TeraTokenTypes.OPERATOR && t.text == s

    /** An identifier, or one of the keywords that also behaves like a variable (`loop`). */
    private fun isName(t: TeraTok?) =
        t != null && (t.type == TeraTokenTypes.IDENTIFIER || (t.type == TeraTokenTypes.KEYWORD && t.text == "loop"))

    // ── scope walk ───────────────────────────────────────────────────────────

    /** One snapshot per block, describing what is visible at the start of that block. */
    fun snapshots(
        blocks: List<TeraBlock>,
        context: TeraContextResult?,
        version: TeraVersion = TeraVersion.V1,
        text: String = "",
    ): List<TeraScopeSnapshot> {
        val frames = mutableListOf(Frame("root"))
        val result = ArrayList<TeraScopeSnapshot>(blocks.size)
        for (block in blocks) {
            result.add(visible(frames))
            val t = block.tokens
            if (block.kind != '%' || t.isEmpty() || t[0].type != TeraTokenTypes.KEYWORD) continue
            when (val kw = t[0].text) {
                "for" -> frames.add(forFrame(t, visible(frames), context, version))
                "macro" -> frames.add(macroFrame(t))
                "component" -> frames.add(componentFrame(text, block))
                "if", "block", "filter", "raw" -> frames.add(Frame(kw))
                "set", "set_global" -> {
                    val name = t.getOrNull(1)?.takeIf { it.type == TeraTokenTypes.IDENTIFIER } ?: continue
                    val target = if (kw == "set_global") frames[0] else frames.last { it.kind in HOLDS_SET }
                    if (!isOp(t.getOrNull(2), "=")) {
                        // Tera 2 block form: `{% set x %}...{% endset %}` captures its body as a string.
                        target.bindings.add(TeraBinding(name.text, TeraType.Scalar("string"), "set", name.range, "set in template"))
                        frames.add(Frame("set"))
                        continue
                    }
                    val type = evalType(t.drop(3), visible(frames), context)
                    target.bindings.add(TeraBinding(name.text, type, "set", name.range, "set in template"))
                }
                else -> CLOSERS[kw]?.let { opener ->
                    val idx = frames.indexOfLast { it.kind == opener }
                    if (idx > 0) while (frames.size > idx) frames.removeAt(frames.size - 1)
                }
            }
        }
        return result
    }

    private val HOLDS_SET = setOf("root", "for", "macro", "component", "block")

    private fun visible(frames: List<Frame>): TeraScopeSnapshot {
        val out = mutableListOf<TeraBinding>()
        var inLoop = false
        var hidesContext = false
        for (i in frames.indices.reversed()) {
            val f = frames[i]
            out.addAll(f.bindings.asReversed())
            if (f.kind == "for") inLoop = true
            if (f.kind == "macro") break                                  // sees the context, not the frames around it
            if (f.kind == "component") { hidesContext = true; break }     // sees neither
        }
        return TeraScopeSnapshot(out, inLoop, hidesContext)
    }

    private fun forFrame(t: List<TeraTok>, snap: TeraScopeSnapshot, context: TeraContextResult?, version: TeraVersion): Frame {
        val frame = Frame("for")
        val inIdx = t.indexOfFirst { it.type == TeraTokenTypes.KEYWORD && it.text == "in" }
        if (inIdx < 0) return frame
        val vars = t.subList(1, inIdx).filter { it.type == TeraTokenTypes.IDENTIFIER }
        val iterated = evalType(t.drop(inIdx + 1), snap, context)
        val (keyType, valueType) = when (iterated) {
            is TeraType.ArrayOf -> TeraType.Scalar("number") to iterated.element
            is TeraType.MapOf -> TeraType.Scalar("string") to iterated.value
            else -> TeraType.Unknown to TeraType.Unknown
        }
        when (vars.size) {
            1 -> frame.bindings.add(TeraBinding(vars[0].text, valueType, "for", vars[0].range, "loop variable"))
            2 -> {
                frame.bindings.add(TeraBinding(vars[0].text, keyType, "for", vars[0].range, "loop key"))
                frame.bindings.add(TeraBinding(vars[1].text, valueType, "for", vars[1].range, "loop value"))
            }
        }
        frame.bindings.add(TeraBinding("loop", if (version == TeraVersion.V2) LOOP_TYPE_V2 else LOOP_TYPE, "loop", null, "loop state"))
        return frame
    }

    private fun macroFrame(t: List<TeraTok>): Frame {
        val frame = Frame("macro")
        val macro = TeraSymbolScanner.macroOf(t) ?: return frame
        for (p in macro.params) {
            val type = when (TeraDocumentation.typeOf(p, null)) {
                "string" -> TeraType.Scalar("string")
                "integer", "float" -> TeraType.Scalar("number")
                "boolean" -> TeraType.Scalar("bool")
                "array" -> TeraType.ArrayOf(TeraType.Unknown)
                else -> TeraType.Unknown
            }
            frame.bindings.add(TeraBinding(p.name, type, "param", p.range, "macro parameter"))
        }
        return frame
    }

    private fun typeOfAnnotation(annotation0: String?, default: String?): TeraType = typeOfAnnotationLower(annotation0?.lowercase(), default)

    private fun typeOfAnnotationLower(annotation: String?, default: String?): TeraType = when {
        annotation == "bytes" -> TeraType.ArrayOf(TeraType.Scalar("number"))
        annotation == "string" || default?.startsWith("\"") == true -> TeraType.Scalar("string")
        annotation in setOf("integer", "float", "number") || default?.matches(Regex("-?\\d+(\\.\\d+)?")) == true -> TeraType.Scalar("number")
        annotation == "bool" || default == "true" || default == "false" -> TeraType.Scalar("bool")
        annotation == "array" || default?.startsWith("[") == true -> TeraType.ArrayOf(TeraType.Unknown)
        annotation == "map" || default?.startsWith("{") == true -> TeraType.MapOf(TeraType.Unknown)
        else -> TeraType.Unknown
    }

    /** A Tera 2 component sees its parameters (explicit, implicit `@x`, the `...rest` map) and its `body`. */
    private fun componentFrame(text: String, block: TeraBlock): Frame {
        val frame = Frame("component")
        val def = TeraComponentScanner.definitions(text, listOf(block)).firstOrNull() ?: return frame
        for (p in def.params) {
            val type = if (p.rest) TeraType.MapOf(TeraType.Unknown) else typeOfAnnotation(p.type, p.defaultText)
            frame.bindings.add(TeraBinding(p.name, type, if (p.implicit) "implicit" else "param", p.range,
                if (p.implicit) "implicit parameter" else if (p.rest) "rest parameter" else "component parameter"))
        }
        frame.bindings.add(TeraBinding("body", TeraType.Scalar("string"), "param", null, "component body"))
        return frame
    }

    // ── expression types ─────────────────────────────────────────────────────

    /** Best-effort type of an expression: literals, paths such as `a.b[0]`, and simple filters. */
    fun evalType(tokens: List<TeraTok>, snap: TeraScopeSnapshot, context: TeraContextResult?): TeraType {
        val first = tokens.firstOrNull() ?: return TeraType.Unknown
        var i = 1
        var type: TeraType = TeraType.Unknown
        when {
            first.type == TeraTokenTypes.STRING -> type = TeraType.Scalar("string")
            first.type == TeraTokenTypes.NUMBER -> type = TeraType.Scalar("number")
            first.type == TeraTokenTypes.KEYWORD && (first.text == "true" || first.text == "false") ->
                type = TeraType.Scalar("bool")
            isOp(first, "[") -> { i = skipBalanced(tokens, 0); type = TeraType.ArrayOf(TeraType.Unknown) }
            isName(first) && isOp(tokens.getOrNull(1), "(") -> {
                i = skipBalanced(tokens, 1)
                type = if (first.text == "range") TeraType.ArrayOf(TeraType.Scalar("number")) else TeraType.Unknown
            }
            isName(first) -> {
                val segs = mutableListOf(PathSeg(first.text))
                while (i < tokens.size) {
                    val t = tokens[i]
                    if (isOp(t, ".") && tokens.getOrNull(i + 1).let {
                            isName(it) || it?.type == TeraTokenTypes.NUMBER
                        }) {
                        segs.add(PathSeg(tokens[i + 1].text)); i += 2
                    } else if (isOp(t, "[")) {
                        segs.add(PathSeg("[]", true)); i = skipBalanced(tokens, i)
                    } else break
                }
                type = resolvePath(segs, snap, context).type
            }
        }
        while (i < tokens.size && isOp(tokens[i], "|")) {
            val name = tokens.getOrNull(i + 1)?.takeIf { it.type == TeraTokenTypes.IDENTIFIER } ?: return TeraType.Unknown
            i += 2
            if (isOp(tokens.getOrNull(i), "(")) i = skipBalanced(tokens, i)
            type = applyFilter(name.text, type)
        }
        return if (i >= tokens.size) type else TeraType.Unknown
    }

    private fun skipBalanced(tokens: List<TeraTok>, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TeraTokenTypes.OPERATOR) {
                if (t.text in setOf("(", "[", "{")) depth++
                else if (t.text in setOf(")", "]", "}")) { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return tokens.size
    }

    private fun applyFilter(name: String, input: TeraType): TeraType = when (name) {
        in TeraBuiltins.TYPE_PRESERVING -> input
        "first", "last", "nth" -> (input as? TeraType.ArrayOf)?.element ?: TeraType.Unknown
        in TeraBuiltins.STRING_RESULT -> TeraType.Scalar("string")
        in TeraBuiltins.NUMBER_RESULT -> TeraType.Scalar("number")
        else -> TeraType.Unknown
    }

    // ── paths ────────────────────────────────────────────────────────────────

    fun resolvePath(segs: List<PathSeg>, snap: TeraScopeSnapshot, context: TeraContextResult?): PathResult {
        val root = segs.first()
        var type: TeraType
        var field: TeraField? = null
        var binding: TeraBinding? = null
        val local = snap.bindings.firstOrNull { it.name == root.name }
        if (local != null) {
            type = local.type; binding = local
        } else {
            val ctx = if (snap.hidesContext) null else context?.variables?.firstOrNull { it.name == root.name }
            if (ctx == null) return PathResult(TeraType.Unknown, null, null, 0, null)
            type = ctx.type; field = ctx
        }
        for (idx in 1 until segs.size) {
            val seg = segs[idx]
            when (val ty = type) {
                is TeraType.Struct -> {
                    if (seg.index) { type = TeraType.Unknown; field = null; binding = null }
                    else {
                        val f = ty.fields.firstOrNull { it.name == seg.name }
                            ?: return PathResult(TeraType.Unknown, null, null, idx, ty.name)
                        type = f.type; field = f; binding = null
                    }
                }
                is TeraType.ArrayOf -> { type = ty.element; field = null; binding = null }
                is TeraType.MapOf -> { type = ty.value; field = null; binding = null }
                is TeraType.Tuple -> {
                    val n = seg.name.toIntOrNull()
                    if (seg.index) { type = TeraType.Unknown; field = null; binding = null }
                    else if (n == null || n !in ty.elements.indices) {
                        return PathResult(TeraType.Unknown, null, null, idx, ty.display())
                    } else { type = ty.elements[n]; field = null; binding = null }
                }
                else -> return PathResult(TeraType.Unknown, null, null)
            }
        }
        return PathResult(type, field, binding)
    }

    /** The path written before a `.` at the end of [prior], e.g. `a.b[0]` for `a.b[0].|`. */
    fun pathBefore(prior: List<TeraTok>): List<PathSeg>? {
        var i = prior.size - 2
        val segs = ArrayDeque<PathSeg>()
        while (i >= 0) {
            val t = prior[i]
            var wasIndex = false
            when {
                isName(t) || t.type == TeraTokenTypes.NUMBER -> { segs.addFirst(PathSeg(t.text)); i-- }
                isOp(t, "]") -> {
                    var depth = 0
                    var j = i
                    while (j >= 0) {
                        if (isOp(prior[j], "]")) depth++
                        if (isOp(prior[j], "[")) { depth--; if (depth == 0) break }
                        j--
                    }
                    if (j < 0) return null
                    segs.addFirst(PathSeg("[]", true)); i = j - 1; wasIndex = true
                }
                else -> return null
            }
            if (wasIndex) continue
            if (i >= 0 && isOp(prior[i], ".")) i-- else break
        }
        return segs.toList().takeIf { it.isNotEmpty() && !it.first().index }
    }
}
