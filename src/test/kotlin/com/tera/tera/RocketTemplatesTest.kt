package com.tera.tera

import com.intellij.lang.html.HTMLLanguage
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RocketTemplatesTest : BasePlatformTestCase() {
    fun testFixtureChecksums() {
        val checksums = javaClass.getResource("/rocket/checksums.sha256")!!.readText().trim().lines()
        assertEquals(8, checksums.size)
        for (line in checksums) {
            val (expected, path) = line.split("  ", limit = 2)
            val bytes = javaClass.getResource("/rocket/$path")!!.readBytes()
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals("Fixture changed: $path", expected, actual)
        }
    }

    fun testFormsMacros() = checkTemplate("forms/templates/macros.html.tera", "input", "textarea", "select")
    fun testFormsIndex() = checkTemplate("forms/templates/index.html.tera", "html", "style", "form")
    fun testFormsSuccess() = checkTemplate("forms/templates/success.html.tera", "html", "style", "ul")
    fun testTemplatingBase() = checkTemplate("templating/templates/tera/base.html.tera", "html", "footer")
    fun testTemplatingIndex() = checkTemplate("templating/templates/tera/index.html.tera", "h1", "ul")
    fun testTemplatingNav() = checkTemplate("templating/templates/tera/nav.html.tera", "a")
    fun testTemplatingNotFound() = checkTemplate("templating/templates/tera/error/404.html.tera", "html", "h1")
    fun testTodoIndex() = checkTemplate("todo/static/index.html.tera", "html", "input", "button")

    fun testMissingMacroCloserIsReportedAtItsOpener() {
        val original = fixture("forms/templates/macros.html.tera")
        val broken = original.replace("{% endmacro select %}", "")
        val errors = TeraExternalAnnotator().doAnnotate(broken)
        assertEquals(1, errors.size)
        assertEquals(broken.indexOf("macro select"), errors.single().range.startOffset)
        assertEquals("macro", errors.single().range.substring(broken))
        assertEquals(TeraBundle.message("error.unclosed.block", "macro"), errors.single().message)
        assertEquals(com.intellij.lang.annotation.HighlightSeverity.ERROR, errors.single().severity)
    }

    fun testUnexpectedCloserIsReportedAtTheCloser() {
        val original = fixture("templating/templates/tera/nav.html.tera")
        val broken = original + "\n{% endmacro orphan %}"
        val errors = TeraExternalAnnotator().doAnnotate(broken)
        assertEquals(1, errors.size)
        assertEquals("endmacro", errors.single().range.substring(broken))
        assertEquals(TeraBundle.message("error.unexpected.closer", "endmacro", "macro"), errors.single().message)
        assertEquals(com.intellij.lang.annotation.HighlightSeverity.ERROR, errors.single().severity)
    }

    private fun checkTemplate(path: String, vararg expectedTags: String) {
        val text = fixture(path)
        assertEmpty(TeraExternalAnnotator().doAnnotate(text))
        val file = myFixture.configureByText(path.substringAfterLast('/'), text)
        assertInstanceOf(file.viewProvider, TeraFileViewProvider::class.java)
        val html = file.viewProvider.getPsi(HTMLLanguage.INSTANCE)!!
        assertEquals(text, html.text)
        val tags = PsiTreeUtil.findChildrenOfType(html, XmlTag::class.java)
        for (name in expectedTags) assertTrue("Missing HTML tag $name in $path", tags.any { it.name == name })
        for (tag in tags) {
            assertTrue("Wrong source offset for ${tag.name} in $path", text.substring(tag.textOffset).startsWith("<${tag.name}"))
        }
        assertLexerRestarts(text)
    }

    private fun assertLexerRestarts(text: String) {
        val lexer = TeraLexer()
        lexer.start(text)
        var end = 0
        while (lexer.tokenType != null) {
            assertEquals(end, lexer.tokenStart)
            assertTrue(lexer.tokenEnd > lexer.tokenStart)
            val restarted = TeraLexer()
            restarted.start(text, lexer.tokenStart, text.length, lexer.state)
            assertEquals(lexer.tokenType, restarted.tokenType)
            assertEquals(lexer.tokenEnd, restarted.tokenEnd)
            end = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(text.length, end)
    }

    private fun fixture(path: String): String =
        javaClass.getResource("/rocket/examples/$path")!!.readText()
}
