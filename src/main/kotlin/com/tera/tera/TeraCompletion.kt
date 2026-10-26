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
)

/** Context analysis for completion; pure text in, suggestions out. */
object TeraCompletionEngine {

    private val TAG_KEYWORDS = listOf(
        "for", "endfor", "if", "elif", "else", "endif", "block", "endblock", "macro", "endmacro",
        "raw", "endraw", "filter", "endfilter", "set", "set_global", "extends", "include", "import",
        "break", "continue",
    )
    private val PATH_TAGS = setOf("import", "extends", "include")
    private val NO_NAMESPACE_TAGS = setOf("import", "macro", "endmacro", "block", "endblock", "extends", "include")

    fun suggest(
        text: String,
        offset: Int,
        importedMacros: (String) -> List<MacroDef>?,
        templateNames: () -> List<String>,
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

        if (block.kind == '%' && prior.isEmpty()) return TAG_KEYWORDS.map { TeraSuggestion(it) }

        if (prior.lastOrNull()?.let { isOp(it, ".") || isOp(it, "|") || isOp(it, ":") } == true) return emptyList()
        if (block.kind == '%' && first in NO_NAMESPACE_TAGS) return emptyList()
        val namespaces = buildList {
            if (symbols.macros.isNotEmpty()) add("self")
            symbols.imports.forEach { add(it.alias) }
        }.distinct()
        return namespaces.map { TeraSuggestion(it, "macros", "", "::") }
    }

    private fun isOp(t: TeraTok, s: String) = t.type == TeraTokenTypes.OPERATOR && t.text == s

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
                )
                for (s in suggestions) {
                    var item = LookupElementBuilder.create(s.text)
                    if (s.tail.isNotEmpty()) item = item.withTailText(s.tail, true)
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
                    result.addElement(item)
                }
            }
        })
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun invokeAutoPopup(position: PsiElement, typeChar: Char): Boolean =
        typeChar == ':' && position.containingFile?.viewProvider?.baseLanguage == TeraLanguage
}
