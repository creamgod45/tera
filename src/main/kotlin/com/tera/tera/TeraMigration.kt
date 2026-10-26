package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange

/**
 * What changed for one filter, test or function between Tera 1 and Tera 2.
 * [rename] is the Tera 2 name when the item only changed name; otherwise it is gone and [note] says what to do instead.
 */
class Migration(val kind: String, val name: String, val rename: String?, val note: String)

/** An argument that still exists on a Tera 2 filter but under another name, or no longer exists. */
class ArgChange(val filter: String, val arg: String, val newName: String?, val note: String)

/** Findings about a template for a given Tera version, with fixes. Shared by diagnostics and "migrate this file". */
object TeraMigration {

    private val LIST = listOf(
        // renamed
        Migration("filter", "as_str", "str", "renamed"),
        Migration("filter", "linebreaksbr", "newlines_to_br", "renamed"),
        Migration("filter", "trim_start_matches", "trim_start", "merged into trim_start(pat=…)"),
        Migration("filter", "trim_end_matches", "trim_end", "merged into trim_end(pat=…)"),
        Migration("test", "divisibleby", "divisible_by", "renamed, and it takes divisor=…"),
        Migration("test", "object", "map", "renamed"),
        // gone from the core crate
        Migration("filter", "addslashes", null, "no longer built in; register your own filter"),
        Migration("filter", "concat", null, "use array spread: [...a, ...b]"),
        Migration("filter", "slice", null, "use slicing: value[start:stop:step]"),
        Migration("filter", "filter", null, "use a list comprehension: [x for x in xs if …]"),
        Migration("filter", "map", null, "use a list comprehension: [x.attribute for x in xs]"),
        Migration("filter", "spaceless", null, "moved to tera-contrib (feature `regex`): tera_contrib::regex::spaceless"),
        Migration("filter", "striptags", null, "moved to tera-contrib (feature `regex`): tera_contrib::regex::striptags"),
        Migration("filter", "slugify", null, "moved to tera-contrib (feature `slug`) as `slug`: tera_contrib::slug::slug"),
        Migration("filter", "urlencode", null, "moved to tera-contrib (feature `urlencode`): tera_contrib::urlencode::urlencode"),
        Migration("filter", "urlencode_strict", null, "moved to tera-contrib (feature `urlencode`): tera_contrib::urlencode::urlencode_strict"),
        Migration("filter", "date", null, "moved to tera-contrib (feature `date`): tera_contrib::dates::date"),
        Migration("filter", "filesizeformat", null, "moved to tera-contrib (feature `filesize_format`) as `filesize_format`"),
        Migration("filter", "json_encode", null, "moved to tera-contrib (feature `json`): tera_contrib::json::json_encode"),
        Migration("test", "matching", null, "moved to tera-contrib (feature `regex`): Matching, with pat=…"),
        Migration("function", "now", null, "moved to tera-contrib (feature `date`): tera_contrib::dates::now, only `timezone` remains"),
        Migration("function", "get_random", null, "moved to tera-contrib (feature `rand`): start and end are both required"),
        Migration("function", "get_env", null, "removed; pass the value in the context or register your own function"),
    )
    val CHANGES: Map<Pair<String, String>, Migration> = LIST.associateBy { it.kind to it.name }

    private val ARGS = listOf(
        ArgChange("indent", "prefix", "indentation", "renamed to `indentation` (and `width` sets a count of spaces)"),
        ArgChange("int", "default", null, "removed"),
        ArgChange("float", "default", null, "removed"),
        ArgChange("unique", "attribute", null, "removed, unique takes no arguments"),
        ArgChange("unique", "case_sensitive", null, "removed, unique takes no arguments"),
    )
    private val ARG_CHANGES = ARGS.associateBy { it.filter to it.arg }

    private val LOOP_FIELDS_V1 = listOf("index", "index0", "first", "last")
    private val LOOP_FIELDS_V2 = LOOP_FIELDS_V1 + "length"
    private val RESERVED = setOf("true", "True", "false", "False", "loop", "self", "and", "or", "not", "is", "in", "continue",
        "break", "none", "None", "null")

    private fun isOp(t: TeraTok?, s: String) = t != null && t.type == TeraTokenTypes.OPERATOR && t.text == s
    private fun isKw(t: TeraTok?, s: String) = t != null && t.type == TeraTokenTypes.KEYWORD && t.text == s
    private fun msg(key: String, vararg p: Any) = TeraBundle.message(key, *p)

    // ── the checks ───────────────────────────────────────────────────────────

    /**
     * @param hints in a 1.x project, mark what Tera 2 removed or renamed (weak warnings, struck through)
     */
    fun check(
        text: String,
        version: TeraVersion,
        custom: List<TeraCustomBuiltin> = emptyList(),
        hints: Boolean = true,
    ): List<TeraError> {
        val blocks = TeraSymbolScanner.scanBlocks(text)
        val errors = mutableListOf<TeraError>()
        val customNames = custom.map { it.kind to it.def.name }.toSet()
        val v2 = version == TeraVersion.V2

        tagChecks(text, blocks, version, hints, errors)
        if (!v2) v2OnlySyntax(blocks, errors)
        loopFields(blocks, v2, errors)

        val catalogs = listOf("filter", "test", "function").associateWith { TeraBuiltins.byName(it, custom, version) }
        for (use in TeraCalls.scan(blocks)) {
            val key = use.kind to use.name.text
            val change = CHANGES[key]?.takeUnless { key in customNames }
            val def = catalogs.getValue(use.kind)[use.name.text]
            when {
                v2 && change != null -> errors.add(removedError(use, change, text))
                !v2 && change != null -> if (hints) errors.add(hintError(use, change, text))
                v2 && def == null && key !in customNames ->
                    errors.add(TeraError(msg("version.unknown", use.kind, use.name.text, version.label), use.name.range, HighlightSeverity.WEAK_WARNING))
                def != null && def.source == null -> argumentChecks(use, def, version, hints, text, errors)
            }
        }
        if (v2 || hints) macroCalls(text, v2, errors)
        return errors.sortedBy { it.range.startOffset }
    }

    // ── tags ─────────────────────────────────────────────────────────────────

    /** Range of the whole `{% ... %}` of [block], including delimiters. */
    fun tagRange(text: String, block: TeraBlock): TextRange? {
        val start = text.lastIndexOf("{%", block.contentStart)
        val close = text.indexOf("%}", block.contentEnd)
        if (start < 0 || close < 0) return null
        return TextRange(start, close + 2)
    }

    private fun tagChecks(text: String, blocks: List<TeraBlock>, version: TeraVersion, hints: Boolean, out: MutableList<TeraError>) {
        val v2 = version == TeraVersion.V2
        for (block in blocks) {
            if (block.kind != '%' || block.tokens.isEmpty()) continue
            val t = block.tokens
            val first = t[0]
            if (isOp(first, "<")) {
                val closing = isOp(t.getOrNull(1), "/")
                if (!v2 && !closing) out.add(TeraError(msg("version.v2only", "Component call", version.label), first.range, HighlightSeverity.ERROR))
                continue
            }
            if (first.type != TeraTokenTypes.KEYWORD) continue
            when (first.text) {
                "macro", "endmacro" -> {
                    val to = if (first.text == "macro") "component" else "endcomponent"
                    val fix = TeraFix(msg("version.fix.rename.tag", first.text, to), listOf(TeraEdit(first.range, to)))
                    if (v2) out.add(TeraError(msg("version.removed.tag", first.text, msg("version.note.macro")), first.range, HighlightSeverity.ERROR, listOf(fix)))
                    else if (hints) out.add(TeraError(msg("version.hint", "tag", first.text, msg("version.note.macro")), first.range,
                        HighlightSeverity.WEAK_WARNING, listOf(fix), deprecated = true))
                }
                "import", "from" -> {
                    val range = tagRange(text, block)
                    val fixes = range?.let {
                        val end = if (text.getOrNull(it.endOffset) == '\n') it.endOffset + 1 else it.endOffset
                        listOf(TeraFix(msg("version.fix.remove.tag"), listOf(TeraEdit(TextRange(it.startOffset, end), ""))))
                    }.orEmpty()
                    if (v2) out.add(TeraError(msg("version.removed.tag", first.text, msg("version.note.import")), first.range, HighlightSeverity.ERROR, fixes))
                    else if (hints) out.add(TeraError(msg("version.hint", "tag", first.text, msg("version.note.import")), first.range,
                        HighlightSeverity.WEAK_WARNING, fixes, deprecated = true))
                }
                "include" -> {
                    val unsupported = t.drop(1).firstOrNull {
                        isOp(it, "[") || (it.type == TeraTokenTypes.IDENTIFIER && it.text in setOf("ignore", "missing", "with", "context", "only"))
                    }
                    if (unsupported != null) {
                        if (v2) out.add(TeraError(msg("version.include"), unsupported.range, HighlightSeverity.ERROR))
                        else if (hints) out.add(TeraError(msg("version.hint", "tag", "include", msg("version.include")), unsupported.range,
                            HighlightSeverity.WEAK_WARNING, deprecated = true))
                    }
                }
                "component", "endcomponent", "endset" ->
                    if (!v2) out.add(TeraError(msg("version.v2only", "`${first.text}`", version.label), first.range, HighlightSeverity.ERROR))
                "set", "set_global" -> {
                    val name = t.getOrNull(1)
                    if (name != null && name.text in RESERVED && v2) {
                        out.add(TeraError(msg("version.reserved", name.text), name.range, HighlightSeverity.ERROR))
                    }
                    if (!v2 && t.size >= 2 && t.none { isOp(it, "=") }) {
                        out.add(TeraError(msg("version.v2only", "Set block", version.label), first.range, HighlightSeverity.ERROR))
                    }
                }
            }
        }
    }

    // ── syntax that only Tera 2 has ──────────────────────────────────────────

    private fun v2OnlySyntax(blocks: List<TeraBlock>, out: MutableList<TeraError>) {
        for (block in blocks) {
            val from = TeraCalls.expressionStart(block) ?: continue
            val t = block.tokens
            val stack = ArrayDeque<String>()
            fun report(what: String, range: TextRange) =
                out.add(TeraError(msg("version.v2only", what, TeraVersion.V1.label), range, HighlightSeverity.ERROR))
            for (i in from until t.size) {
                val tok = t[i]
                val next = t.getOrNull(i + 1)
                val prev = t.getOrNull(i - 1)
                if (tok.type == TeraTokenTypes.OPERATOR) {
                    // `<name ...>` where an operand can start; after an operand `<` is the comparison operator.
                    if (tok.text == "<" && next != null && next.type == TeraTokenTypes.IDENTIFIER &&
                        next.range.startOffset == tok.range.endOffset &&
                        (prev == null || (prev.type == TeraTokenTypes.OPERATOR && prev.text !in setOf(")", "]", "}")) ||
                            (prev.type == TeraTokenTypes.KEYWORD && prev.text in setOf("and", "or", "not", "in", "if", "else")))
                    ) report("Component call", TextRange(tok.range.startOffset, next.range.endOffset))
                    when (tok.text) {
                        "[", "(", "{" -> stack.addLast(tok.text)
                        "]", ")", "}" -> if (stack.isNotEmpty()) stack.removeLast()
                        "?" -> if ((isOp(next, ".") || isOp(next, "[")) && next!!.range.startOffset == tok.range.endOffset)
                            report("Optional chaining", TextRange(tok.range.startOffset, next.range.endOffset))
                        "." -> if (isOp(next, ".") && next!!.range.startOffset == tok.range.endOffset &&
                            isOp(t.getOrNull(i + 2), ".") && t[i + 2].range.startOffset == next.range.endOffset &&
                            !(isOp(prev, ".") && prev!!.range.endOffset == tok.range.startOffset)
                        ) report("Spread", TextRange(tok.range.startOffset, t[i + 2].range.endOffset))
                        ":" -> if (stack.lastOrNull() == "[") {
                            // `[a::2]`/`[::2]` slice with an empty part; `ns::name` inside a list is a macro call.
                            val secondOfPair = isOp(prev, ":") && prev!!.range.endOffset == tok.range.startOffset
                            val pair = isOp(next, ":") && next!!.range.startOffset == tok.range.endOffset
                            if (!secondOfPair && !(pair && prev?.type == TeraTokenTypes.IDENTIFIER)) report("Slicing", tok.range)
                        }
                    }
                }
                if (tok.type == TeraTokenTypes.KEYWORD) {
                    if (tok.text == "for" && stack.lastOrNull() == "[") report("List comprehension", tok.range)
                    if (tok.text == "if" && block.kind == '{' && stack.lastOrNull() != "[") report("Ternary expression", tok.range)
                }
            }
        }
    }

    // ── loop ─────────────────────────────────────────────────────────────────

    private fun loopFields(blocks: List<TeraBlock>, v2: Boolean, out: MutableList<TeraError>) {
        val allowed = if (v2) LOOP_FIELDS_V2 else LOOP_FIELDS_V1
        for (block in blocks) {
            val t = block.tokens
            for (i in 0 until t.size - 2) {
                if (isKw(t[i], "loop") && isOp(t[i + 1], ".") && t[i + 2].type == TeraTokenTypes.IDENTIFIER &&
                    t[i + 2].text !in allowed
                ) out.add(TeraError(msg("version.loop.field", t[i + 2].text, allowed.joinToString(", ")), t[i + 2].range, HighlightSeverity.ERROR))
            }
        }
    }

    // ── filters, tests and functions ─────────────────────────────────────────

    private fun removedError(use: TeraUse, change: Migration, text: String): TeraError {
        val fixes = fixesFor(use, change, text)
        return TeraError(msg("version.removed.${use.kind}", use.name.text, change.rename?.let { "renamed to `$it`" } ?: change.note),
            use.name.range, HighlightSeverity.ERROR, fixes)
    }

    private fun hintError(use: TeraUse, change: Migration, text: String): TeraError =
        TeraError(msg("version.hint", use.kind, use.name.text, change.rename?.let { "renamed to `$it`" } ?: change.note),
            use.name.range, HighlightSeverity.WEAK_WARNING, fixesFor(use, change, text), deprecated = true)

    private fun fixesFor(use: TeraUse, change: Migration, text: String): List<TeraFix> {
        val newName = change.rename ?: return emptyList()
        val edits = mutableListOf(TeraEdit(use.name.range, newName))
        var label = msg("version.fix.rename", newName)
        if (use.kind == "test" && use.isPositional) {
            val params = TeraBuiltins.TEST_DEFS_V2.firstOrNull { it.name == newName }?.params.orEmpty()
            if (use.args.size <= params.size) {
                use.args.forEachIndexed { i, g -> edits.add(TeraEdit(TextRange(g.first().range.startOffset, g.first().range.startOffset), "${params[i].name}=")) }
                label = msg("version.fix.rename.kwargs", newName)
            }
        }
        return listOf(TeraFix(label, edits))
    }

    private fun argumentChecks(use: TeraUse, def: BuiltinDef, version: TeraVersion, hints: Boolean, text: String, out: MutableList<TeraError>) {
        val v2 = version == TeraVersion.V2
        if (use.hasParens && !use.closed) return
        // Tests take positional arguments in Tera 1 and keyword arguments in Tera 2.
        if (use.kind == "test" && v2 && use.isPositional) {
            val params = def.params
            val edits = if (use.args.size <= params.size)
                use.args.mapIndexed { i, g -> TeraEdit(TextRange(g.first().range.startOffset, g.first().range.startOffset), "${params[i].name}=") } else emptyList()
            val example = params.take(use.args.size).joinToString(", ") { "${it.name}=…" }
            out.add(TeraError(msg("version.test.positional", "${use.name.text}($example)"),
                TextRange(use.args.first().first().range.startOffset, use.args.last().last().range.endOffset), HighlightSeverity.ERROR,
                if (edits.isEmpty()) emptyList() else listOf(TeraFix(msg("version.fix.kwargs"), edits))))
            return
        }
        if (def.positional) return
        val given = mutableSetOf<String>()
        val known = def.params.map { it.name }.toSet()
        for (group in use.args) {
            val name = use.argName(group) ?: continue
            given.add(name)
            if (name in known) continue
            val change = if (use.kind == "filter") ARG_CHANGES[use.name.text to name] else null
            val nameTok = group.first()
            when {
                change != null && v2 -> out.add(TeraError(msg("version.arg.changed", use.name.text, name, change.note), nameTok.range,
                    HighlightSeverity.ERROR, argFix(use, group, change)))
                else -> out.add(TeraError(msg("version.arg.unknown", use.kind, use.name.text, name), nameTok.range, HighlightSeverity.WARNING))
            }
        }
        if (!v2 && hints && use.kind == "filter") {
            for (group in use.args) {
                val change = use.argName(group)?.let { ARG_CHANGES[use.name.text to it] } ?: continue
                out.add(TeraError(msg("version.hint.arg", use.name.text, use.argName(group)!!, change.note), group.first().range,
                    HighlightSeverity.WEAK_WARNING, argFix(use, group, change), deprecated = true))
            }
        }
        val missing = def.required.map { it.name }.filter { it !in given }
        if (missing.isNotEmpty() && !(use.kind == "test" && use.args.isNotEmpty())) {
            out.add(TeraError(msg("version.arg.missing", use.kind, use.name.text, missing.joinToString(", ")), use.name.range, HighlightSeverity.ERROR))
        }
    }

    private fun argFix(use: TeraUse, group: List<TeraTok>, change: ArgChange): List<TeraFix> {
        if (change.newName != null) {
            return listOf(TeraFix(msg("version.fix.arg.rename", change.newName), listOf(TeraEdit(group.first().range, change.newName))))
        }
        val i = use.args.indexOf(group)
        val start: Int
        val end = group.last().range.endOffset
        start = when {
            i > 0 -> use.args[i - 1].last().range.endOffset
            use.args.size > 1 -> group.first().range.startOffset
            else -> group.first().range.startOffset
        }
        val to = if (i == 0 && use.args.size > 1) use.args[1].first().range.startOffset else end
        return listOf(TeraFix(msg("version.fix.arg.remove", change.arg), listOf(TeraEdit(TextRange(start, to), ""))))
    }

    // ── macro calls ──────────────────────────────────────────────────────────

    /** `ns::name(a=1)` becomes the component call `<name a={1}/>`. */
    fun componentCall(text: String, call: MacroCall): String? {
        if (!call.closed) return null
        val attrs = call.args.map { arg ->
            val name = arg.name ?: return null
            val value = text.substring(arg.range.startOffset, arg.range.endOffset).substringAfter('=').trim()
            when {
                value == name -> name
                value.length >= 2 && value.first() == '"' && value.last() == '"' && value.substring(1, value.length - 1).none { it == '"' || it == '\\' } ->
                    "$name=$value"
                else -> "$name={$value}"
            }
        }
        return "<" + (listOf(call.name) + attrs).joinToString(" ") + "/>"
    }

    private fun macroCalls(text: String, v2: Boolean, out: MutableList<TeraError>) {
        for (call in TeraSymbolScanner.scan(text).calls) {
            val replacement = componentCall(text, call)
            val range = TextRange(call.namespaceRange.startOffset, if (call.closed) call.argsEnd + 1 else call.nameRange.endOffset)
            val fixes = replacement?.let { listOf(TeraFix(msg("version.fix.component.call", call.name), listOf(TeraEdit(range, it)))) }.orEmpty()
            if (v2) out.add(TeraError(msg("version.macro.call", call.name), range, HighlightSeverity.ERROR, fixes))
            else out.add(TeraError(msg("version.hint", "macro call", "${call.namespace}::${call.name}", msg("version.macro.call", call.name)), range,
                HighlightSeverity.WEAK_WARNING, fixes, deprecated = true))
        }
    }

    // ── whole file ───────────────────────────────────────────────────────────

    class Result(val text: String, val applied: Int, val remaining: List<TeraError>)

    /**
     * Rewrites [text] for Tera 2: applies the first fix of every problem a 2.x project would have, then reports
     * what still needs a human (removed filters without an equivalent, semantic changes).
     */
    fun migrate(text: String, custom: List<TeraCustomBuiltin> = emptyList()): Result {
        var current = text
        var applied = 0
        repeat(3) {
            val errors = (check(current, TeraVersion.V2, custom, hints = false) + TeraComponentChecker.bodyErrors(current))
                .filter { it.fixes.isNotEmpty() }
            val edits = mutableListOf<TeraEdit>()
            val seen = mutableSetOf<TeraFix>()
            var count = 0
            var lastStart = Int.MAX_VALUE
            // Take non-overlapping fixes, last position first, so every edit is independent of the others.
            for (e in errors.sortedByDescending { it.range.startOffset }) {
                val fix = e.fixes.first()
                if (!seen.add(fix)) continue            // several errors can share one fix (e.g. one implicit parameter list)
                val fixStart = fix.edits.minOf { it.range.startOffset }
                val fixEnd = fix.edits.maxOf { it.range.endOffset }
                if (fixEnd > lastStart) continue
                edits.addAll(fix.edits)
                lastStart = fixStart
                count++
            }
            if (edits.isEmpty()) return@repeat
            current = TeraFix.applyEdits(current, edits)
            applied += count
        }
        return Result(current, applied, check(current, TeraVersion.V2, custom, hints = false) + TeraComponentChecker.bodyErrors(current))
    }
}
