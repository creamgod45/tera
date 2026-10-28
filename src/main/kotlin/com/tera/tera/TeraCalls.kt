package com.tera.tera

/** A use of a filter, test or function inside a template expression, with the arguments written for it. */
class TeraUse(
    val kind: String,
    val name: TeraTok,
    val args: List<List<TeraTok>>,
    val closed: Boolean,
    val hasParens: Boolean,
    /** Where the use is: the block it is in and the index of the name among that block's tokens. */
    val blockIndex: Int = -1,
    val index: Int = -1,
) {
    /** `name` of a `name=value` argument group (`from` is a keyword for the lexer, so accept it too). */
    fun argName(group: List<TeraTok>): String? {
        val first = group.firstOrNull() ?: return null
        val named = (first.type == TeraTokenTypes.IDENTIFIER || first.type == TeraTokenTypes.KEYWORD) &&
            group.getOrNull(1)?.let { it.type == TeraTokenTypes.OPERATOR && it.text == "=" } == true
        return if (named) first.text else null
    }

    val isPositional get() = args.isNotEmpty() && args.all { argName(it) == null }
}

object TeraCalls {
    private fun isOp(t: TeraTok?, s: String) = t != null && t.type == TeraTokenTypes.OPERATOR && t.text == s
    private fun isKw(t: TeraTok?, s: String) = t != null && t.type == TeraTokenTypes.KEYWORD && t.text == s

    /** Start of the expression part of a block, or null if the block has none we look at. */
    fun expressionStart(block: TeraBlock): Int? {
        val t = block.tokens
        if (block.kind == '{') return 0
        if (t.isEmpty() || t[0].type != TeraTokenTypes.KEYWORD) return null
        return when (t[0].text) {
            "if", "elif", "filter" -> 1
            "for" -> t.indexOfFirst { isKw(it, "in") }.let { if (it < 0) null else it + 1 }
            "set", "set_global" -> t.indexOfFirst { isOp(it, "=") }.let { if (it < 0) null else it + 1 }
            else -> null
        }
    }

    fun scan(blocks: List<TeraBlock>): List<TeraUse> {
        val out = mutableListOf<TeraUse>()
        for ((blockIndex, block) in blocks.withIndex()) {
            val from = expressionStart(block) ?: continue
            val t = block.tokens
            val filterTag = block.kind == '%' && t.firstOrNull()?.text == "filter"
            for (i in from until t.size) {
                val tok = t[i]
                val prev = t.getOrNull(i - 1)
                // `filter` is a keyword for the lexer but also the name of a Tera 1 filter: `x | filter(...)`.
                val filterName = tok.type == TeraTokenTypes.KEYWORD && (isOp(prev, "|") || (filterTag && i == 1))
                if (tok.type != TeraTokenTypes.IDENTIFIER && !filterName) continue
                val next = t.getOrNull(i + 1)
                val kind = when {
                    isOp(prev, "|") || (filterTag && i == 1) -> "filter"
                    isKw(prev, "is") || (isKw(prev, "not") && isKw(t.getOrNull(i - 2), "is")) -> "test"
                    isOp(next, "(") && !isOp(prev, ".") && !isOp(prev, ":") && !isOp(prev, "<") -> "function"
                    else -> continue
                }
                if (isOp(next, "(")) {
                    val a = TeraSymbolScanner.splitArgs(t, i + 1)
                    out.add(TeraUse(kind, tok, a.groups, a.closeIndex >= 0, true, blockIndex, i))
                } else {
                    out.add(TeraUse(kind, tok, emptyList(), true, false, blockIndex, i))
                }
            }
        }
        return out
    }
}
