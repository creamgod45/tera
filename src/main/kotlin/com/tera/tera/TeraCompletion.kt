package com.tera.tera

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext

/** One completion item: [text] is inserted, then [suffix] (if not already present) with the caret at [caretInSuffix]. */
data class TeraSuggestion(
    val text: String,
    val tail: String = "",
    val type: String = "",
    val suffix: String = "",
    val caretInSuffix: Int = suffix.length,
    /** Offered higher (positive) or lower (negative) in the list; used to put fitting filters first. */
    val priority: Double = 0.0,
    /** An iterable variable offered as a whole loop: selecting it replaces the `{{ .. }}` with this snippet. */
    val snippet: TeraSnippet? = null,
)

/** Context analysis for completion; pure text in, suggestions out. */
object TeraCompletionEngine {

    private val TAG_KEYWORDS = listOf(
        "for", "endfor", "if", "elif", "else", "endif", "block", "endblock", "macro", "endmacro",
        "raw", "endraw", "filter", "endfilter", "set", "set_global", "extends", "include", "import",
        "break", "continue",
    )
    private val TAG_KEYWORDS_V2 = listOf(
        "for", "endfor", "if", "elif", "else", "endif", "block", "endblock", "component", "endcomponent",
        "raw", "endraw", "filter", "endfilter", "set", "set_global", "endset", "extends", "include",
        "break", "continue",
    )
    private val PATH_TAGS = setOf("import", "extends", "include")
    private val NO_NAMESPACE_TAGS = setOf("import", "macro", "endmacro", "block", "endblock", "extends", "include")

    fun suggest(
        text: String,
        offset: Int,
        importedMacros: (String) -> List<MacroDef>?,
        templateNames: () -> List<String>,
        context: TeraContextResult? = null,
        custom: List<TeraCustomBuiltin> = emptyList(),
        version: TeraVersion = TeraVersion.V1,
        components: List<TeraComponent> = emptyList(),
    ): List<TeraSuggestion> {
        val blocks = TeraSymbolScanner.scanBlocks(text)
        val block = blocks.firstOrNull { offset >= it.contentStart && offset <= it.contentEnd } ?: return emptyList()
        val tokens = block.tokens
        val first = tokens.firstOrNull()?.text

        // import/extends/include "<caret>"
        val string = tokens.firstOrNull {
            val terminated = it.text.length >= 2 && it.text.last() == it.text.first()
            it.type == TeraTokenTypes.STRING && it.range.startOffset < offset &&
                (offset < it.range.endOffset || (offset == it.range.endOffset && !terminated))
        }
        if (string != null) {
            return if (block.kind == '%' && first in PATH_TAGS) templateNames().map { TeraSuggestion(it) } else emptyList()
        }

        val typed = tokens.firstOrNull {
            (it.type == TeraTokenTypes.IDENTIFIER || it.type == TeraTokenTypes.KEYWORD) &&
                it.range.startOffset < offset && offset <= it.range.endOffset
        }
        val limit = typed?.range?.startOffset ?: offset
        val prior = tokens.filter { it.range.endOffset <= limit }
        val symbols = TeraSymbolScanner.symbols(blocks)

        // `<` + name: the components to call; inside a call tag: the attributes still to give.
        if (version == TeraVersion.V2) {
            componentSuggestions(block, prior, typed != null, components)?.let { return it }
        }

        fun macrosOf(namespace: String): List<MacroDef>? = when (namespace) {
            "self" -> symbols.macros
            else -> symbols.imports.firstOrNull { it.alias == namespace }?.let { importedMacros(it.path) }
        }

        // ns::<caret>
        if (prior.size >= 3 && isOp(prior[prior.size - 1], ":") && isOp(prior[prior.size - 2], ":") &&
            prior[prior.size - 3].type == TeraTokenTypes.IDENTIFIER
        ) {
            val macros = macrosOf(prior[prior.size - 3].text) ?: return emptyList()
            return macros.distinctBy { it.name }.map(::macroSuggestion)
        }

        // ns::macro(<caret>) → remaining parameter names
        val call = symbols.calls
            .filter { offset >= it.argsStart && offset <= it.argsEnd }
            .maxByOrNull { it.argsStart }
        if (call != null && prior.lastOrNull()?.let { isOp(it, "(") || isOp(it, ",") } == true &&
            nestingDepth(tokens, call.argsStart, limit) == 0
        ) {
            val macro = macrosOf(call.namespace)?.firstOrNull { it.name == call.name } ?: return emptyList()
            val used = call.args.mapNotNull { it.name }.toSet()
            return macro.params.filter { it.name !in used }.map {
                TeraSuggestion(it.name, if (it.hasDefault) "optional" else "required", "", "=")
            }
        }

        // range(<caret>) / x | truncate(<caret>): the remaining keyword arguments of a built-in.
        enclosingBuiltinCall(prior, custom, version)?.let { (def, used) ->
            if (prior.last().let { isOp(it, "(") || isOp(it, ",") }) {
                return def.params.filter { !def.positional && it.name !in used }.map {
                    TeraSuggestion(it.name, if (it.required) "required" else "default ${it.default ?: "none"}", "param", "=")
                }
            }
        }

        if (block.kind == '%' && prior.isEmpty()) return (if (version == TeraVersion.V2) TAG_KEYWORDS_V2 else TAG_KEYWORDS).map { TeraSuggestion(it) }

        val snapshot = TeraScopes.snapshots(blocks, context, version, text)[blocks.indexOf(block)]
        val last = prior.lastOrNull()
        if (last != null && isOp(last, ".")) return fieldSuggestions(prior, snapshot, context)
        if (last != null && isOp(last, "|")) {
            return TeraBuiltins.defs("filter", custom, version).map { withFit(builtinSuggestion(it, it.let { "filter" }, alwaysParens = false), it, blocks, block, prior, prior.size - 1, context, snapshot, version) }
        }
        if (last != null && last.type == TeraTokenTypes.KEYWORD && (last.text == "is" ||
                (last.text == "not" && prior.getOrNull(prior.size - 2)?.text == "is"))
        ) {
            return TeraBuiltins.defs("test", custom, version).map { withFit(builtinSuggestion(it, "test", alwaysParens = false), it, blocks, block, prior, prior.indexOfLast { t -> t.text == "is" }, context, snapshot, version) }
        }
        if (last != null && isOp(last, ":")) return emptyList()
        if (block.kind == '%' && first in NO_NAMESPACE_TAGS) return emptyList()
        // Names being declared, not referenced.
        if (block.kind == '%' && (first == "set" || first == "set_global") && prior.none { isOp(it, "=") }) return emptyList()
        if (block.kind == '%' && first == "for" &&
            prior.none { it.type == TeraTokenTypes.KEYWORD && it.text == "in" }
        ) return emptyList()

        val result = mutableListOf<TeraSuggestion>()
        val seen = mutableSetOf<String>()
        for (b in snapshot.bindings) {
            if (seen.add(b.name)) result.add(TeraSuggestion(b.name, ": ${b.type.display()}", b.detail.orEmpty()))
        }
        if (!snapshot.hidesContext) {
            for (v in context?.variables.orEmpty()) {
                if (seen.add(v.name)) result.add(TeraSuggestion(v.name, ": ${v.rustType ?: v.type.display()}", "context"))
            }
        }
        // `{{ categories` where `categories` is a list or map: also offer the loop that shows it properly.
        if (block.kind == '{' && prior.isEmpty()) {
            val iterable = snapshot.bindings.map { it.name to it.type } +
                (if (snapshot.hidesContext) emptyList() else context?.variables.orEmpty().map { it.name to it.type })
            for ((name, type) in iterable.distinctBy { it.first }) {
                for (snip in TeraIterate.snippets(name, type)) result.add(TeraSuggestion(name, "", "", priority = -1.0, snippet = snip))
            }
        }
        TeraBuiltins.defs("function", custom, version).forEach { result.add(builtinSuggestion(it, "function", alwaysParens = true)) }
        // Macro namespaces exist only in Tera 1; Tera 2 calls components instead.
        val namespaces = if (version == TeraVersion.V2) emptyList() else buildList {
            if (symbols.macros.isNotEmpty()) add("self")
            symbols.imports.forEach { add(it.alias) }
        }.distinct()
        namespaces.forEach { result.add(TeraSuggestion(it, "macros", "", "::")) }
        return result
    }

    private fun startsOperand(prior: List<TeraTok>, index: Int): Boolean {
        val prev = prior.getOrNull(index - 1) ?: return true
        return (prev.type == TeraTokenTypes.OPERATOR && prev.text !in setOf(")", "]", "}")) ||
            (prev.type == TeraTokenTypes.KEYWORD && prev.text in setOf("and", "or", "not", "in", "if", "else"))
    }

    /** Component names right after `<`, or attribute names inside the open call tag the caret is in; null if neither. */
    private fun componentSuggestions(
        block: TeraBlock, prior: List<TeraTok>, hasPrefix: Boolean, components: List<TeraComponent>,
    ): List<TeraSuggestion>? {
        val last = prior.lastOrNull() ?: return null
        // Component name: `<` at the start of an operand (or first in a `{% %}` tag), nothing but the prefix after it.
        if (isOp(last, "<") && (if (block.kind == '%') prior.size == 1 else startsOperand(prior, prior.size - 1))) {
            return components.distinctBy { it.def.name }.map {
                TeraSuggestion(it.def.name, it.def.signature().removePrefix(it.def.name), "component")
            }
        }
        // Attribute name: walk back to the `<name` of a call tag that is still open.
        var depth = 0
        var i = prior.size - 1
        while (i >= 0) {
            val t = prior[i]
            if (isOp(t, "}")) depth++
            else if (isOp(t, "{")) { if (depth == 0) return null; depth-- }       // inside a {value}: an expression
            else if (depth == 0 && (isOp(t, ">") )) return null                     // the tag is already closed
            else if (depth == 0 && isOp(t, "<") && prior.getOrNull(i + 1)?.type == TeraTokenTypes.IDENTIFIER &&
                (if (block.kind == '%') i == 0 else startsOperand(prior, i))) break
            i--
        }
        if (i < 0) return null
        var j = i + 1
        val name = StringBuilder(prior[j].text)
        j++
        while (isOp(prior.getOrNull(j), ".") && prior.getOrNull(j + 1)?.type == TeraTokenTypes.IDENTIFIER) { name.append('.').append(prior[j + 1].text); j += 2 }
        // Only where an attribute can start: right after the name, after another attribute, or after a `{value}`.
        val lastTok = prior.last()
        val atAttributeStart = prior.size == j ||
            lastTok.type == TeraTokenTypes.STRING || lastTok.type == TeraTokenTypes.IDENTIFIER || lastTok.type == TeraTokenTypes.KEYWORD ||
            lastTok.type == TeraTokenTypes.NUMBER || isOp(lastTok, "}")
        if (!atAttributeStart || (isOp(prior.getOrNull(prior.size - 1), "=") )) return emptyList()
        // A bare word right before the prefix still counts as the previous attribute; `name=` means a value is being typed.
        if (prior.size >= 2 && isOp(prior[prior.size - 1], "=")) return emptyList()
        val def = components.firstOrNull { it.def.name == name.toString() }?.def ?: return emptyList()
        val given = mutableSetOf<String>()
        var k = j
        while (k < prior.size) {
            val t = prior[k]
            if ((t.type == TeraTokenTypes.IDENTIFIER || t.type == TeraTokenTypes.KEYWORD) && !isOp(prior.getOrNull(k - 1), ".")) given.add(t.text)
            if (isOp(t, "{")) { var d = 0; while (k < prior.size) { if (isOp(prior[k], "{")) d++; if (isOp(prior[k], "}")) { d--; if (d == 0) break }; k++ } }
            k++
        }
        return def.params.filter { !it.rest && it.name !in given }.map { p ->
            val stringy = p.type == null || p.type == "string"
            TeraSuggestion(p.name,
                when { p.implicit -> "implicit"; p.required -> "required"; else -> "default ${p.defaultText}" },
                "attribute", if (stringy) "=\"\"" else "={}", if (stringy) 2 else 2)
        }
    }

    /** Puts filters and tests whose input kind fits the operand before the caret first, and the ones that do not last. */
    private fun withFit(
        s: TeraSuggestion, def: BuiltinDef, blocks: List<TeraBlock>, block: TeraBlock, prior: List<TeraTok>, bar: Int,
        context: TeraContextResult?, snapshot: TeraScopeSnapshot, version: TeraVersion,
    ): TeraSuggestion {
        if (version != TeraVersion.V2 || def.input.isEmpty() || bar < 0) return s
        val operand = TeraTypeChecker.operandBefore(prior, bar, 0)
        if (operand.isEmpty()) return s
        val literal = TeraTypeRules.literalKind(operand)?.takeIf { it != "none" }?.let { if (it == "integer" || it == "float") "number" else it }
        val kind = literal ?: TeraTypeRules.kindOf(TeraScopes.evalType(operand, snapshot, context)) ?: return s
        return s.copy(priority = if (TeraTypeRules.accepts(def.input, kind)) 1.0 else -1.0)
    }

    private fun builtinSuggestion(def: BuiltinDef, kind: String, alwaysParens: Boolean): TeraSuggestion {
        val call = def.callSuffix(alwaysParens) ?: return TeraSuggestion(def.name, def.description, kind)
        return TeraSuggestion(def.name, def.description, kind, call.first, call.second)
    }

    private val OPEN = setOf("(", "[", "{")
    private val CLOSE = setOf(")", "]", "}")

    /** The built-in function or filter whose argument list contains the caret, with the argument names already given. */
    private fun enclosingBuiltinCall(prior: List<TeraTok>, custom: List<TeraCustomBuiltin>, version: TeraVersion): Pair<BuiltinDef, Set<String>>? {
        var depth = 0
        for (i in prior.indices.reversed()) {
            val t = prior[i]
            if (t.type != TeraTokenTypes.OPERATOR) continue
            if (t.text in CLOSE) { depth++; continue }
            if (t.text !in OPEN) continue
            if (depth > 0) { depth--; continue }
            if (t.text != "(") return null
            val callee = prior.getOrNull(i - 1)?.takeIf { it.type == TeraTokenTypes.IDENTIFIER } ?: return null
            val before = prior.getOrNull(i - 2)
            if (before != null && isOp(before, ":")) return null        // a macro call, handled elsewhere
            fun kw(t: TeraTok?, text: String) = t != null && t.type == TeraTokenTypes.KEYWORD && t.text == text
            val kind = when {
                before != null && isOp(before, "|") -> "filter"
                kw(before, "is") || (kw(before, "not") && kw(prior.getOrNull(i - 3), "is")) -> "test"
                else -> "function"
            }
            val def = TeraBuiltins.byName(kind, custom, version)[callee.text] ?: return null
            val used = mutableSetOf<String>()
            var d = 0
            for (j in i + 1 until prior.size) {
                val u = prior[j]
                if (u.type == TeraTokenTypes.OPERATOR && u.text in OPEN) d++
                else if (u.type == TeraTokenTypes.OPERATOR && u.text in CLOSE) d--
                else if (d == 0 && (u.type == TeraTokenTypes.IDENTIFIER || u.type == TeraTokenTypes.KEYWORD) &&
                    isOp(prior.getOrNull(j + 1), "=") &&
                    prior.getOrNull(j - 1)?.let { isOp(it, "(") || isOp(it, ",") } == true
                ) used.add(u.text)
            }
            return def to used
        }
        return null
    }

    private fun fieldSuggestions(
        prior: List<TeraTok>, snapshot: TeraScopeSnapshot, context: TeraContextResult?,
    ): List<TeraSuggestion> {
        val path = TeraScopes.pathBefore(prior) ?: return emptyList()
        val type = TeraScopes.resolvePath(path, snapshot, context).type
        return (type as? TeraType.Struct)?.fields.orEmpty()
            .map { TeraSuggestion(it.name, ": ${it.rustType ?: it.type.display()}", "field") }
    }

    private fun isOp(t: TeraTok?, s: String) = t != null && t.type == TeraTokenTypes.OPERATOR && t.text == s

    private fun nestingDepth(tokens: List<TeraTok>, from: Int, to: Int): Int {
        var depth = 0
        for (t in tokens) {
            if (t.range.startOffset < from || t.range.endOffset > to || t.type != TeraTokenTypes.OPERATOR) continue
            if (t.text in setOf("(", "[", "{")) depth++ else if (t.text in setOf(")", "]", "}")) depth--
        }
        return depth
    }

    private fun macroSuggestion(m: MacroDef): TeraSuggestion {
        val signature = m.params.joinToString(", ") { if (it.hasDefault) "${it.name}=…" else it.name }
        val required = m.required
        val suffix = if (required.isEmpty()) "()" else "(" + required.joinToString(", ") { "${it.name}=" } + ")"
        val caret = if (required.isEmpty()) 1 else 1 + required[0].name.length + 1
        return TeraSuggestion(m.name, "($signature)", "macro", suffix, caret)
    }
}

/** Tail text that starts like a signature (`: Type`, `(args)`) hugs the name; descriptions get a gap. */
internal fun displayTail(tail: String): String =
    if (tail.startsWith(":") || tail.startsWith("(") || tail.startsWith(" ")) tail else "  $tail"

class TeraCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(
                parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet,
            ) {
                val file = parameters.originalFile
                if (file.viewProvider.baseLanguage != TeraLanguage) return
                val vFile = file.originalFile.virtualFile
                val suggestions = TeraCompletionEngine.suggest(
                    file.text, parameters.offset,
                    importedMacros = { path -> vFile?.let { TeraImports.macrosOf(it, path) } },
                    templateNames = { vFile?.let(TeraTemplateResolver::templateNames).orEmpty() },
                    context = TeraContexts.forFile(file),
                    custom = TeraContexts.customBuiltins(file.project),
                    version = TeraVersions.forFile(file.project, vFile).version,
                    components = TeraComponents.visible(file.project, vFile, file.text),
                )
                for (s in suggestions) {
                    s.snippet?.let { snip ->
                        val loop = LookupElementBuilder.create(snip, s.text).withPresentableText(snip.title)
                            .withIcon(com.intellij.icons.AllIcons.Actions.Play_forward)
                            .withTypeText(if (snip.kind == TeraSnippetKind.JOIN) "join" else "loop", true)
                            .withInsertHandler { ctx, _ ->
                                // Replace the whole `{{ .. }}` the name was typed in, not just the typed name.
                                val doc = ctx.document
                                val text = doc.charsSequence
                                val start = text.lastIndexOf("{{", ctx.startOffset)
                                var end = ctx.tailOffset
                                while (end < text.length && text[end] == ' ') end++
                                end = if (text.startsWith("}}", end)) end + 2 else ctx.tailOffset
                                if (start >= 0) {
                                    doc.replaceString(start, end, snip.text)
                                    ctx.editor.caretModel.moveToOffset(start + snip.caret)
                                }
                            }
                        result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(loop, s.priority))
                        return@let
                    } ?: run {
                    var item = LookupElementBuilder.create(s.text)
                    if (s.tail.isNotEmpty()) item = item.withTailText(displayTail(s.tail), true)
                    if (s.type.isNotEmpty()) item = item.withTypeText(s.type, true)
                    if (s.suffix.isNotEmpty()) {
                        item = item.withInsertHandler { ctx, _ ->
                            val doc = ctx.document
                            val at = ctx.tailOffset
                            val next = doc.charsSequence.getOrNull(at)
                            if (next != s.suffix[0]) {
                                doc.insertString(at, s.suffix)
                                ctx.editor.caretModel.moveToOffset(at + s.caretInSuffix)
                                if (s.suffix == "::" || s.suffix.length > 1) {
                                    AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
                                }
                            }
                        }
                    }
                    result.addElement(if (s.priority != 0.0) com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(item, s.priority) else item)
                    }
                }
            }
        })
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun invokeAutoPopup(position: PsiElement, typeChar: Char): Boolean =
        position.containingFile?.viewProvider?.baseLanguage == TeraLanguage &&
            (typeChar == ':' || ((typeChar == '.' || typeChar == '|') && insideTeraBlock(position)))

    private fun insideTeraBlock(position: PsiElement): Boolean {
        val offset = position.textRange.startOffset
        return TeraSymbolScanner.scanBlocks(position.containingFile.text)
            .any { it.kind != '#' && offset >= it.contentStart && offset <= it.contentEnd }
    }
}
