package com.tera.tera

import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType

// ── Token / block model ──────────────────────────────────────────────────────

data class TeraTok(val type: IElementType, val text: String, val range: TextRange)

/**
 * One `{{ ... }}` (kind `{`) or `{% ... %}` (kind `%`) region.
 * [contentStart]..[contentEnd] excludes the delimiters; [closed] is false at EOF.
 */
class TeraBlock(
    val kind: Char,
    val contentStart: Int,
    val contentEnd: Int,
    val closed: Boolean,
    val tokens: List<TeraTok>,
)

// ── Symbols ──────────────────────────────────────────────────────────────────

data class MacroParam(val name: String, val hasDefault: Boolean, val range: TextRange, val defaultText: String? = null)

data class MacroDef(val name: String, val params: List<MacroParam>, val nameRange: TextRange) {
    val required: List<MacroParam> get() = params.filter { !it.hasDefault }
}

/** `{% import "path" as alias %}` */
data class ImportDecl(val path: String, val alias: String, val pathRange: TextRange, val aliasRange: TextRange)

data class CallArg(val name: String?, val range: TextRange) {
    /** Range of just the `name` in `name=value`. */
    val nameRange: TextRange? get() = name?.let { TextRange(range.startOffset, range.startOffset + it.length) }
}

/** `namespace::name(args)`; [argsStart]..[argsEnd] is the region between the parentheses. */
data class MacroCall(
    val namespace: String,
    val namespaceRange: TextRange,
    val name: String,
    val nameRange: TextRange,
    val args: List<CallArg>,
    val closed: Boolean,
    val openParen: Int,
    val argsStart: Int,
    val argsEnd: Int,
)

data class TeraSymbols(
    val macros: List<MacroDef>,
    val imports: List<ImportDecl>,
    val calls: List<MacroCall>,
)

// ── Scanner ──────────────────────────────────────────────────────────────────

object TeraSymbolScanner {

    fun scanBlocks(text: String): List<TeraBlock> {
        val blocks = mutableListOf<TeraBlock>()
        var kind = ' '
        var contentStart = 0
        var tokens = mutableListOf<TeraTok>()
        var open = false

        val lexer = TeraLexer()
        lexer.start(text, 0, text.length, TeraLexer.STATE_OUTER)
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            when (type) {
                TeraTokenTypes.VARIABLE_START, TeraTokenTypes.TAG_START -> {
                    open = true
                    kind = if (type == TeraTokenTypes.TAG_START) '%' else '{'
                    contentStart = end
                    tokens = mutableListOf()
                }
                TeraTokenTypes.VARIABLE_END, TeraTokenTypes.TAG_END -> if (open) {
                    blocks.add(TeraBlock(kind, contentStart, start, true, tokens))
                    open = false
                }
                TeraTokenTypes.KEYWORD, TeraTokenTypes.IDENTIFIER, TeraTokenTypes.STRING,
                TeraTokenTypes.NUMBER, TeraTokenTypes.OPERATOR ->
                    if (open) tokens.add(TeraTok(type, text.substring(start, end), TextRange(start, end)))
                else -> Unit
            }
            lexer.advance()
        }
        if (open) blocks.add(TeraBlock(kind, contentStart, text.length, false, tokens))
        return blocks
    }

    fun scan(text: String): TeraSymbols = symbols(scanBlocks(text))

    fun symbols(blocks: List<TeraBlock>): TeraSymbols {
        val macros = mutableListOf<MacroDef>()
        val imports = mutableListOf<ImportDecl>()
        val calls = mutableListOf<MacroCall>()
        for (block in blocks) {
            val t = block.tokens
            if (block.kind == '%' && t.isNotEmpty() && t[0].type == TeraTokenTypes.KEYWORD) {
                when (t[0].text) {
                    "macro" -> parseMacro(t)?.let(macros::add)
                    "import" -> parseImport(t)?.let(imports::add)
                }
            }
            collectCalls(block, calls)
        }
        return TeraSymbols(macros, imports, calls)
    }

    // ── parsing helpers ──────────────────────────────────────────────────────

    private class Args(val groups: List<List<TeraTok>>, val closeIndex: Int)

    private val OPEN = setOf("(", "[", "{")
    private val CLOSE = setOf(")", "]", "}")

    /** Splits the tokens after `tokens[openIndex]` (a `(`) into top-level comma separated groups. */
    private fun splitArgs(tokens: List<TeraTok>, openIndex: Int): Args {
        val groups = mutableListOf<List<TeraTok>>()
        var current = mutableListOf<TeraTok>()
        var depth = 1
        var i = openIndex + 1
        while (i < tokens.size) {
            val tok = tokens[i]
            val isOp = tok.type == TeraTokenTypes.OPERATOR
            when {
                isOp && tok.text in OPEN -> { depth++; current.add(tok) }
                isOp && tok.text in CLOSE -> {
                    depth--
                    if (depth == 0) {
                        if (current.isNotEmpty()) groups.add(current)
                        return Args(groups, i)
                    }
                    current.add(tok)
                }
                isOp && tok.text == "," && depth == 1 -> { groups.add(current); current = mutableListOf() }
                else -> current.add(tok)
            }
            i++
        }
        if (current.isNotEmpty()) groups.add(current)
        return Args(groups, -1)
    }

    private fun isOp(t: TeraTok?, text: String) = t != null && t.type == TeraTokenTypes.OPERATOR && t.text == text

    private fun parseMacro(t: List<TeraTok>): MacroDef? {
        val name = t.getOrNull(1)?.takeIf { it.type == TeraTokenTypes.IDENTIFIER } ?: return null
        if (!isOp(t.getOrNull(2), "(")) return null
        val params = splitArgs(t, 2).groups.mapNotNull { g ->
            val id = g.firstOrNull()?.takeIf { it.type == TeraTokenTypes.IDENTIFIER } ?: return@mapNotNull null
            val hasDefault = isOp(g.getOrNull(1), "=")
            MacroParam(id.text, hasDefault, id.range, if (hasDefault) g.drop(2).joinToString("") { it.text } else null)
        }
        return MacroDef(name.text, params, name.range)
    }

    private fun parseImport(t: List<TeraTok>): ImportDecl? {
        val path = t.getOrNull(1)?.takeIf { it.type == TeraTokenTypes.STRING } ?: return null
        val asTok = t.getOrNull(2)
        val alias = t.getOrNull(3)?.takeIf { it.type == TeraTokenTypes.IDENTIFIER } ?: return null
        if (asTok?.text != "as") return null
        return ImportDecl(unquote(path.text), alias.text, path.range, alias.range)
    }

    fun unquote(s: String): String =
        if (s.length >= 2 && (s[0] == '"' || s[0] == '\'') && s.last() == s[0]) s.substring(1, s.length - 1)
        else s.drop(1)

    private fun collectCalls(block: TeraBlock, out: MutableList<MacroCall>) {
        val t = block.tokens
        for (i in 0..t.size - 5) {
            if (t[i].type != TeraTokenTypes.IDENTIFIER || !isOp(t[i + 1], ":") || !isOp(t[i + 2], ":")) continue
            if (t[i + 3].type != TeraTokenTypes.IDENTIFIER || !isOp(t[i + 4], "(")) continue
            if (i > 0 && isOp(t[i - 1], ".")) continue
            val open = t[i + 4]
            val args = splitArgs(t, i + 4)
            val closed = args.closeIndex >= 0
            out.add(MacroCall(
                namespace = t[i].text,
                namespaceRange = t[i].range,
                name = t[i + 3].text,
                nameRange = t[i + 3].range,
                args = args.groups.map { g ->
                    val named = g[0].type == TeraTokenTypes.IDENTIFIER && isOp(g.getOrNull(1), "=")
                    CallArg(if (named) g[0].text else null, TextRange(g.first().range.startOffset, g.last().range.endOffset))
                },
                closed = closed,
                openParen = open.range.startOffset,
                argsStart = open.range.endOffset,
                argsEnd = if (closed) t[args.closeIndex].range.startOffset else block.contentEnd,
            ))
        }
    }
}
