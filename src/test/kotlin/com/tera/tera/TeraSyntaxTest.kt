package com.tera.tera

import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TeraSyntaxTest : BasePlatformTestCase() {
    private fun errors(text: String) = TeraExternalAnnotator().doAnnotate(text)

    private fun assertValid(text: String) {
        assertEmpty(errors(text))
    }

    fun testTrimmedDelimitersAreSingleTokens() {
        val cases = listOf(
            Triple("{{- value -}}", TeraTokenTypes.VARIABLE_START, TeraTokenTypes.VARIABLE_END),
            Triple("{%- if value -%}", TeraTokenTypes.TAG_START, TeraTokenTypes.TAG_END),
            Triple("{#- comment -#}", TeraTokenTypes.COMMENT_START, TeraTokenTypes.COMMENT_END),
        )
        for ((text, start, end) in cases) {
            val tokens = lex(text)
            assertEquals(start to text.take(3), tokens.first())
            assertEquals(end to text.takeLast(3), tokens.last())
            assertFalse(tokens.contains(TeraTokenTypes.OPERATOR to "-"))
        }
    }

    fun testOrdinaryDelimitersAndMinusOperatorsArePreserved() {
        val tokens = lex("{{ -1 - 2 }}")
        assertEquals(TeraTokenTypes.VARIABLE_START to "{{", tokens.first())
        assertEquals(TeraTokenTypes.VARIABLE_END to "}}", tokens.last())
        assertEquals(2, tokens.count { it == TeraTokenTypes.OPERATOR to "-" })
        assertValid("{% if value %}{{ value }}{% endif %}{# comment #}")
    }

    fun testAllTrimCombinations() {
        for (left in listOf("", "-")) {
            for (right in listOf("", "-")) {
                assertValid("{%${left} if value ${right}%}{{${left} value ${right}}}{%${left} endif ${right}%}")
                assertValid("{#${left} comment ${right}#}")
            }
        }
    }

    fun testNamedAndUnnamedClosers() {
        for ((opener, name) in listOf("macro" to "example()", "block" to "example")) {
            for (closerName in listOf("", " example")) {
                assertValid("{% $opener $name %}Hello{% end$opener$closerName %}")
                assertValid("{%- $opener $name -%}Hello{%- end$opener$closerName -%}")
            }
        }
    }

    fun testNestedMacroWithWhitespaceControl() {
        assertValid("""
            {% macro value_for(name) %}
                {%- if name in values -%}
                    {{- values | get(key=name) | first -}}
                {%- endif -%}
            {% endmacro value_for %}
        """.trimIndent())
    }

    fun testInvalidClosingArgumentsStillReportErrors() {
        for (suffix in listOf("123", "\"example\"", "example extra", "example()")) {
            for (opener in listOf("macro example()", "block example")) {
                val closer = "end" + opener.substringBefore(' ')
                assertFalse(errors("{% $opener %}{% $closer $suffix %}").isEmpty())
            }
        }
        assertFalse(errors("{% if true %}{% endif extra %}").isEmpty())
        assertFalse(errors("{% for item in items %}{% endfor extra %}").isEmpty())
    }

    fun testBrokenStructureStillReportsErrors() {
        for (text in listOf(
            "{%- macro example() -%}",
            "{%- if true -%}{%- endmacro example -%}",
            "{% endmacro example %}",
            "{%- if true",
            "{{- value",
            "{#- comment",
        )) {
            assertFalse("Expected an error for: $text", errors(text).isEmpty())
        }
    }

    private fun lex(text: String): List<Pair<IElementType, String>> {
        val lexer = TeraLexer()
        lexer.start(text)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            tokens.add(lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return tokens
    }
}
