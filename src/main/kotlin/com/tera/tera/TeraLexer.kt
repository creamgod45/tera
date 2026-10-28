package com.tera.tera

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Tera 模板词法分析器（手写状态机）
 *
 * 识别三种 Tera 标记：
 *   {{ expr }}   —— 变量/表达式输出
 *   {% tag %}    —— 控制标签（for/if/block/macro/raw 等）
 *   {# ... #}   —— 注释
 *
 * 同时支持 Tera 的空白裁剪语法：{{- / -}}、{%- / -%}。
 */
class TeraLexer : LexerBase() {

    // ── 状态常量 ────────────────────────────────────────────────────────────
    companion object {
        const val STATE_OUTER      = 0
        const val STATE_IN_VAR     = 1   // 在 {{ }} 内
        const val STATE_IN_TAG     = 2   // 在 {% %} 内
        const val STATE_IN_COMMENT = 3   // 在 {# #} 内

        /** 控制流关键字（开块/闭块/行内） */
        val KEYWORDS = setOf(
            "for", "endfor",
            "if", "elif", "else", "endif",
            "block", "endblock",
            "macro", "endmacro",
            "raw", "endraw",
            "filter", "endfilter",
            "set", "set_global", "endset",
            "component", "endcomponent",
            "extends", "include", "import", "from",
            "break", "continue",
            // 布尔/逻辑字面量和运算符关键字
            "in", "and", "or", "not", "is",
            "true", "false",
            // 循环内置变量
            "loop", "super",
        )

        /** 双字符运算符 */
        val TWO_CHAR_OPS = setOf("==", "!=", "<=", ">=", "~=", "**")
    }

    // ── 词法器状态 ───────────────────────────────────────────────────────────
    private var buffer: CharSequence = ""
    private var bufferEnd  = 0
    private var tokenStart = 0
    private var tokenEnd   = 0
    private var tokenType: IElementType? = null
    private var state = STATE_OUTER
    private var tokenState = STATE_OUTER

    // ── LexerBase 实现 ───────────────────────────────────────────────────────

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer    = buffer
        this.bufferEnd = endOffset
        this.tokenStart = startOffset
        this.tokenEnd   = startOffset
        this.state      = initialState
        advance()
    }

    override fun getState()           = tokenState
    override fun getTokenType()       = tokenType
    override fun getTokenStart()      = tokenStart
    override fun getTokenEnd()        = tokenEnd
    override fun getBufferSequence()  = buffer
    override fun getBufferEnd()       = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        tokenState = state
        if (tokenStart >= bufferEnd) { tokenType = null; return }

        when (state) {
            STATE_OUTER      -> lexOuter()
            STATE_IN_VAR     -> lexInsideBlock("}}", TeraTokenTypes.VARIABLE_END, STATE_OUTER)
            STATE_IN_TAG     -> lexInsideBlock("%}", TeraTokenTypes.TAG_END,      STATE_OUTER)
            STATE_IN_COMMENT -> lexComment()
        }
    }

    // ── 外层文本 ─────────────────────────────────────────────────────────────

    private fun lexOuter() {
        var pos = tokenStart

        while (pos < bufferEnd) {
            val c = buffer[pos]
            if (c == '{' && pos + 1 < bufferEnd) {
                // 遇到标记开始，先把之前的文本 emit 出去
                if (pos > tokenStart) {
                    tokenEnd  = pos
                    tokenType = TeraTokenTypes.OUTER_TEXT
                    return
                }
                val delimiterLength = if (pos + 2 < bufferEnd && buffer[pos + 2] == '-') 3 else 2
                when (buffer[pos + 1]) {
                    '{' -> { emitDelimiter(pos, delimiterLength, TeraTokenTypes.VARIABLE_START, STATE_IN_VAR);     return }
                    '%' -> { emitDelimiter(pos, delimiterLength, TeraTokenTypes.TAG_START,      STATE_IN_TAG);     return }
                    '#' -> { emitDelimiter(pos, delimiterLength, TeraTokenTypes.COMMENT_START,  STATE_IN_COMMENT); return }
                }
            }
            pos++
        }

        // 到达文件末尾
        tokenEnd  = bufferEnd
        tokenType = TeraTokenTypes.OUTER_TEXT
    }

    private fun emitDelimiter(pos: Int, len: Int, type: IElementType, nextState: Int) {
        tokenEnd  = pos + len
        tokenType = type
        state     = nextState
    }

    // ── 注释块 ───────────────────────────────────────────────────────────────

    private fun lexComment() {
        val pos = tokenStart

        if (pos + 2 < bufferEnd && buffer[pos] == '-' &&
            buffer[pos + 1] == '#' && buffer[pos + 2] == '}') {
            emitDelimiter(pos, 3, TeraTokenTypes.COMMENT_END, STATE_OUTER)
            return
        }

        // 结束定界符 #}
        if (pos + 1 < bufferEnd && buffer[pos] == '#' && buffer[pos + 1] == '}') {
            tokenEnd  = pos + 2
            tokenType = TeraTokenTypes.COMMENT_END
            state     = STATE_OUTER
            return
        }

        // 注释内容，扫描到 #} 为止
        var end = pos
        while (end < bufferEnd) {
            if (buffer[end] == '-' && end + 2 < bufferEnd &&
                buffer[end + 1] == '#' && buffer[end + 2] == '}') break
            if (buffer[end] == '#' && end + 1 < bufferEnd && buffer[end + 1] == '}') break
            end++
        }
        tokenEnd  = end
        tokenType = TeraTokenTypes.COMMENT_CONTENT
    }

    // ── 变量/标签块内部 ──────────────────────────────────────────────────────

    /**
     * @param closeDelim   结束定界符（`}}` 或 `%}`）
     * @param closeType    结束 token 类型
     * @param nextState    关闭后切换到的状态
     */
    private fun lexInsideBlock(closeDelim: String, closeType: IElementType, nextState: Int) {
        val pos = tokenStart
        val c0  = buffer[pos]

        // ── 空白裁剪 `-` + 结束定界符：`-}}` 或 `-%}` ────────────────────────
        if (c0 == '-' && pos + 2 < bufferEnd &&
            buffer[pos + 1] == closeDelim[0] && buffer[pos + 2] == closeDelim[1]) {
            tokenEnd  = pos + 3
            tokenType = closeType
            state     = nextState
            return
        }

        // ── 结束定界符 ────────────────────────────────────────────────────────
        if (pos + 1 < bufferEnd &&
            c0 == closeDelim[0] && buffer[pos + 1] == closeDelim[1]) {
            tokenEnd  = pos + 2
            tokenType = closeType
            state     = nextState
            return
        }

        // ── 空白 ──────────────────────────────────────────────────────────────
        if (c0.isWhitespace()) {
            var end = pos + 1
            while (end < bufferEnd && buffer[end].isWhitespace()) end++
            tokenEnd  = end
            tokenType = TeraTokenTypes.TAG_WHITESPACE
            return
        }

        // ── 字符串字面量 ──────────────────────────────────────────────────────
        if (c0 == '"' || c0 == '\'') {
            tokenEnd  = scanString(pos, c0)
            tokenType = TeraTokenTypes.STRING
            return
        }

        // ── 数字（含负数：仅当前一个 token 不是标识符/右括号时才算） ──────────
        if (c0.isDigit()) {
            tokenEnd  = scanNumber(pos)
            tokenType = TeraTokenTypes.NUMBER
            return
        }

        // ── 标识符或关键字 ─────────────────────────────────────────────────────
        if (c0.isLetter() || c0 == '_') {
            var end = pos + 1
            while (end < bufferEnd && (buffer[end].isLetterOrDigit() || buffer[end] == '_')) end++
            val word = buffer.substring(pos, end)
            tokenEnd  = end
            tokenType = if (word in KEYWORDS) TeraTokenTypes.KEYWORD else TeraTokenTypes.IDENTIFIER
            return
        }

        // ── 双字符运算符 ──────────────────────────────────────────────────────
        if (pos + 1 < bufferEnd) {
            val two = buffer.substring(pos, pos + 2)
            if (two in TWO_CHAR_OPS) {
                tokenEnd  = pos + 2
                tokenType = TeraTokenTypes.OPERATOR
                return
            }
        }

        // ── 单字符运算符/标点 ─────────────────────────────────────────────────
        tokenEnd  = pos + 1
        tokenType = TeraTokenTypes.OPERATOR
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    /** 扫描字符串字面量，返回结束位置（含引号） */
    private fun scanString(start: Int, quote: Char): Int {
        var pos = start + 1
        while (pos < bufferEnd) {
            val c = buffer[pos]
            if (c == '\\') {
                pos += 2          // 跳过转义字符
                if (pos > bufferEnd) pos = bufferEnd   // 防止 \ 在末尾时越界
                continue
            }
            if (c == quote) { pos++; break }
            pos++
        }
        return pos
    }

    /** 扫描数字字面量（整数或浮点），返回结束位置 */
    private fun scanNumber(start: Int): Int {
        var pos = start + 1
        var hasDot = false
        while (pos < bufferEnd) {
            val c = buffer[pos]
            if (c == '.' && !hasDot && pos + 1 < bufferEnd && buffer[pos + 1].isDigit()) {
                hasDot = true; pos++; continue
            }
            if (!c.isDigit()) break
            pos++
        }
        return pos
    }
}
