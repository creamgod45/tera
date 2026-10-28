package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** Reformat Code re-indents by nesting and changes nothing else. */
class TeraFormattingTest : BasePlatformTestCase() {

    private fun fmt(text: String) = TeraIndenter.format(text, "  ")
    private fun squash(s: String) = s.filter { !it.isWhitespace() }

    private fun reformat(name: String, text: String): String {
        myFixture.configureByText(name, text)
        myFixture.performEditorAction("ReformatCode")
        return myFixture.editor.document.text
    }

    fun testNestingOfHtmlAndTeraTagsDecidesTheIndent() {
        val out = fmt("""
            <div>
            {% if a %}
            <p>x</p>
            {% else %}
            <span>y</span>
            {% endif %}
            </div>
        """.trimIndent())
        assertEquals("""
            <div>
              {% if a %}
                <p>x</p>
              {% else %}
                <span>y</span>
              {% endif %}
            </div>
        """.trimIndent(), out)
    }

    fun testBlocksLoopsAndComponentsNest() {
        val out = fmt("""
            {% block content %}
            {% for x in xs %}
            {% <ui.card> %}
            {{ x }}
            {% </ui.card> %}
            {% endfor %}
            {% endblock content %}
        """.trimIndent())
        assertEquals("""
            {% block content %}
              {% for x in xs %}
                {% <ui.card> %}
                  {{ x }}
                {% </ui.card> %}
              {% endfor %}
            {% endblock content %}
        """.trimIndent(), out)
    }

    fun testInlineTagsAndVoidElementsDoNotNest() {
        val out = fmt("<ul>\n<li>{% if a %}x{% endif %}</li>\n<br>\n<img src=\"a\">\n<input type=\"text\"/>\n</ul>")
        assertEquals("<ul>\n  <li>{% if a %}x{% endif %}</li>\n  <br>\n  <img src=\"a\">\n  <input type=\"text\"/>\n</ul>", out)
    }

    fun testWhitespaceSensitiveContentIsLeftAlone() {
        val src = "<div>\n<pre>\n   keep  \n      this\n</pre>\n<script>\n   var a = 1;\n</script>\n<p>x</p>\n</div>"
        val out = fmt(src)
        assertTrue(out, out.contains("<pre>\n   keep  \n      this\n</pre>"))
        assertTrue(out, out.contains("   var a = 1;"))
        assertTrue(out, out.contains("  <script>\n   var a = 1;\n  </script>"))
        assertTrue(out, out.contains("  <p>x</p>"))
    }

    fun testRawBlocksCommentsAndContinuationLinesAreKept() {
        val src = "<div>\n{% raw %}\n   {{ literal }}\n      {% not a tag %}\n{% endraw %}\n{# a\n    comment #}\n<a href=\"x\"\n      class=\"y\">z</a>\n</div>"
        val out = fmt(src)
        assertTrue(out, out.contains("   {{ literal }}\n      {% not a tag %}"))
        assertTrue(out, out.contains("  {# a\n    comment #}"))
        assertTrue(out, out.contains("  <a href=\"x\"\n      class=\"y\">z</a>"))
    }

    fun testHtmlThatIsNotClosedDoesNotRunAway() {
        val out = fmt("<ul>\n<li>one\n<li>two\n</ul>\n<p>after</p>")
        assertTrue(out, out.endsWith("\n<p>after</p>"))
        assertTrue(out, out.contains("\n</ul>\n"))
    }

    fun testAttributesWithTeraTagsAndGreaterThanSigns() {
        val out = fmt("<div>\n<a href=\"{{ url }}\" title=\"a > b\" class=\"{% if x %}y{% endif %}\">t</a>\n</div>")
        assertEquals("<div>\n  <a href=\"{{ url }}\" title=\"a > b\" class=\"{% if x %}y{% endif %}\">t</a>\n</div>", out)
    }

    fun testIdempotentAndBlankLinesAndCrlf() {
        val src = "<div>\r\n\r\n{% if a %}\r\nx\r\n{% endif %}\r\n</div>\r\n"
        val once = fmt(src)
        assertEquals(once, fmt(once))
        assertEquals("<div>\r\n\r\n  {% if a %}\r\n    x\r\n  {% endif %}\r\n</div>\r\n", once)
    }

    fun testTheRealReformatActionUsesIt() {
        val out = reformat("t.html.tera", "<div>\n{% if a %}\n<p>x</p>\n{% endif %}\n</div>\n")
        val unit = " ".repeat(com.intellij.psi.codeStyle.CodeStyleSettingsManager.getSettings(project).getLanguageIndentOptions(com.intellij.lang.html.HTMLLanguage.INSTANCE).INDENT_SIZE)
        assertEquals("<div>\n$unit{% if a %}\n$unit$unit<p>x</p>\n$unit{% endif %}\n</div>\n", out)
    }

    /** Reformatting must never change anything but whitespace at the start of lines, on real templates. */
    fun testRealTemplatesKeepTheirContentAndAreStable() {
        val dirs = listOf("examples/review-poc/templates", "examples/forms-tera2/templates", "src/test/resources/rocket/examples")
        val files = dirs.flatMap { d -> File(d).walkTopDown().filter { it.isFile && it.name.endsWith(".tera") }.toList() }
        assertTrue(files.size > 20)
        for (f in files) {
            val before = f.readText()
            val after = fmt(before)
            assertEquals(f.name, squash(before), squash(after))
            assertEquals("${f.name} is stable", after, fmt(after))
            // Only leading whitespace of lines changed.
            val a = before.lines(); val b = after.lines()
            assertEquals(f.name, a.size, b.size)
            for ((x, y) in a.zip(b)) assertEquals(f.name, x.trimStart(), y.trimStart())
        }
    }

    fun testTheReformatActionOnRealTemplatesOnlyMovesIndentation() {
        val files = listOf("examples/review-poc/templates", "src/test/resources/rocket/examples").flatMap { d ->
            File(d).walkTopDown().filter { it.isFile && it.name.endsWith(".tera") }.toList()
        }
        for (f in files) {
            val before = f.readText()
            val after = reformat(f.name, before)
            assertEquals(f.name, squash(before), squash(after))
            for ((x, y) in before.lines().zip(after.lines())) assertEquals(f.name, x.trimStart(), y.trimStart())
            assertEquals("${f.name} is stable", after, reformat(f.name, after))
        }
    }
}
