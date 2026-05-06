package com.tera.tera

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

// ── 颜色键定义 ────────────────────────────────────────────────────────────────
object TeraHighlighterColors {

    // 定界符：{{ }} {% %} {# #} —— 蓝色（亮色主题 #0070C1，暗色主题 #569CD6）
    @JvmField val DELIMITER  = attr("TERA_DELIMITER",
        light = Color(0x0070C1), dark = Color(0x569CD6), style = Font.BOLD)

    // 变量/标识符：紫色（亮色主题 #800080，暗色主题 #C586C0）
    @JvmField val IDENTIFIER = attr("TERA_IDENTIFIER",
        light = Color(0x800080), dark = Color(0xC586C0))

    // 其余 token 仍继承 IDE 默认色，让插件在各主题下都协调
    @JvmField val KEYWORD    = key("TERA_KEYWORD",    DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val STRING     = key("TERA_STRING",     DefaultLanguageHighlighterColors.STRING)
    @JvmField val NUMBER     = key("TERA_NUMBER",     DefaultLanguageHighlighterColors.NUMBER)
    @JvmField val OPERATOR   = key("TERA_OPERATOR",   DefaultLanguageHighlighterColors.OPERATION_SIGN)
    @JvmField val COMMENT    = key("TERA_COMMENT",    DefaultLanguageHighlighterColors.BLOCK_COMMENT)

    // ── 工具方法 ────────────────────────────────────────────────────────────
    private fun key(name: String, fallback: TextAttributesKey) =
        TextAttributesKey.createTextAttributesKey(name, fallback)

    /** 用精确颜色创建颜色键，同时适配亮色/暗色主题 */
    private fun attr(name: String, light: Color, dark: Color, style: Int = Font.PLAIN): TextAttributesKey {
        val attrs = TextAttributes(JBColor(light, dark), null, null, null, style)
        return TextAttributesKey.createTextAttributesKey(name, attrs)
    }
}

// ── SyntaxHighlighter ────────────────────────────────────────────────────────
class TeraSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = TeraLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        when (tokenType) {
            TeraTokenTypes.VARIABLE_START, TeraTokenTypes.VARIABLE_END,
            TeraTokenTypes.TAG_START,      TeraTokenTypes.TAG_END      -> keys(TeraHighlighterColors.DELIMITER)
            TeraTokenTypes.COMMENT_START,  TeraTokenTypes.COMMENT_END,
            TeraTokenTypes.COMMENT_CONTENT                             -> keys(TeraHighlighterColors.COMMENT)
            TeraTokenTypes.KEYWORD                                     -> keys(TeraHighlighterColors.KEYWORD)
            TeraTokenTypes.IDENTIFIER                                  -> keys(TeraHighlighterColors.IDENTIFIER)
            TeraTokenTypes.STRING                                      -> keys(TeraHighlighterColors.STRING)
            TeraTokenTypes.NUMBER                                      -> keys(TeraHighlighterColors.NUMBER)
            TeraTokenTypes.OPERATOR                                    -> keys(TeraHighlighterColors.OPERATOR)
            else                                                       -> emptyArray()
        }

    private fun keys(key: TextAttributesKey) = arrayOf(key)
}
