package com.tera.tera

import com.intellij.psi.tree.IElementType

object TeraTokenTypes {
    // 普通文本（模板标记之外的内容）
    @JvmField val OUTER_TEXT = IElementType("TERA_OUTER_TEXT", TeraLanguage)

    // 定界符
    @JvmField val VARIABLE_START = IElementType("TERA_VARIABLE_START", TeraLanguage) // {{
    @JvmField val VARIABLE_END   = IElementType("TERA_VARIABLE_END",   TeraLanguage) // }}
    @JvmField val TAG_START      = IElementType("TERA_TAG_START",      TeraLanguage) // {%
    @JvmField val TAG_END        = IElementType("TERA_TAG_END",        TeraLanguage) // %}
    @JvmField val COMMENT_START  = IElementType("TERA_COMMENT_START",  TeraLanguage) // {#
    @JvmField val COMMENT_END    = IElementType("TERA_COMMENT_END",    TeraLanguage) // #}

    // 块内内容
    @JvmField val KEYWORD         = IElementType("TERA_KEYWORD",         TeraLanguage)
    @JvmField val IDENTIFIER      = IElementType("TERA_IDENTIFIER",      TeraLanguage)
    @JvmField val STRING          = IElementType("TERA_STRING",          TeraLanguage)
    @JvmField val NUMBER          = IElementType("TERA_NUMBER",          TeraLanguage)
    @JvmField val OPERATOR        = IElementType("TERA_OPERATOR",        TeraLanguage)
    @JvmField val COMMENT_CONTENT = IElementType("TERA_COMMENT_CONTENT", TeraLanguage)
    @JvmField val TAG_WHITESPACE  = IElementType("TERA_TAG_WHITESPACE",  TeraLanguage)
}
