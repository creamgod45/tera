package com.tera.tera

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

// ── 颜色键定义 ────────────────────────────────────────────────────────────────
object TeraHighlighterColors {

    // 定界符和标识符的默认颜色通过 colorSchemes/*.xml 提供，不在此硬编码
    @JvmField val DELIMITER  = TextAttributesKey.createTextAttributesKey("TERA_DELIMITER")
    @JvmField val IDENTIFIER = TextAttributesKey.createTextAttributesKey("TERA_IDENTIFIER")

    // 其余 token 继承 IDE 默认色
    @JvmField val KEYWORD  = TextAttributesKey.createTextAttributesKey("TERA_KEYWORD",  DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val STRING   = TextAttributesKey.createTextAttributesKey("TERA_STRING",   DefaultLanguageHighlighterColors.STRING)
    @JvmField val NUMBER   = TextAttributesKey.createTextAttributesKey("TERA_NUMBER",   DefaultLanguageHighlighterColors.NUMBER)
    @JvmField val OPERATOR = TextAttributesKey.createTextAttributesKey("TERA_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    @JvmField val COMMENT  = TextAttributesKey.createTextAttributesKey("TERA_COMMENT",  DefaultLanguageHighlighterColors.BLOCK_COMMENT)
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
