package com.tera.tera

import com.intellij.lang.Language
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.templateLanguages.TemplateDataLanguageMappings
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TeraTemplateIntegrationTest : BasePlatformTestCase() {
    fun testHtmlCompletion() {
        myFixture.configureByText("completion.html.tera", "{% if true %}<di<caret>>{% endif %}")
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.orEmpty().contains("div") || myFixture.editor.document.text.contains("<div"))
    }

    fun testHtmlAndTeraUseTheirOwnColors() {
        val text = "<div class=\"card\">{{ name }}</div>"
        val file = myFixture.configureByText("colors.html.tera", text)
        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file.virtualFile)
        highlighter.setText(text)
        val html = highlighter.createIterator(text.indexOf("div"))
        val tera = highlighter.createIterator(text.indexOf("{{"))
        assertFalse(html.textAttributesKeys.isEmpty())
        assertTrue(tera.textAttributesKeys.contains(TeraHighlighterColors.DELIMITER))
        assertFalse(html.textAttributesKeys.contains(TeraHighlighterColors.DELIMITER))
    }

    fun testTemplateDataLanguageMappingAndCopy() {
        val file = myFixture.configureByText("plain.tera", "<p>{{ name }}</p>")
        assertEquals(HTMLLanguage.INSTANCE, (file.viewProvider as TeraFileViewProvider).templateDataLanguage)
        val copy = file.copy() as com.intellij.psi.PsiFile
        assertEquals(file.text, copy.viewProvider.getPsi(HTMLLanguage.INSTANCE)!!.text)
    }

    fun testExplicitTemplateDataMappingTakesPriority() {
        val file = myFixture.configureByText("mapped.html.tera", "<p>{{ name }}</p>")
        val mappings = TemplateDataLanguageMappings.getInstance(project)
        try {
            mappings.setMapping(file.virtualFile, PlainTextLanguage.INSTANCE)
            assertEquals(PlainTextLanguage.INSTANCE, templateDataLanguage(project, file.virtualFile))
        } finally {
            mappings.setMapping(file.virtualFile, null)
        }
    }

    fun testEditsPreserveHtmlAndTeraStructure() {
        val file = myFixture.configureByText("edit.html.tera", "<p>{{ name }}</p>")
        val replacement = "<section>{%- if true -%}<b>{{ name }}</b>{%- endif -%}</section>"
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(replacement)
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        assertEquals(replacement, file.viewProvider.getPsi(HTMLLanguage.INSTANCE)!!.text)
        assertEmpty(TeraExternalAnnotator().doAnnotate(replacement))
    }
}

/** Run explicitly with -PwebTests=true; requires the IDE's CSS and JavaScript plugins. */
class TeraWebIntegrationTest : BasePlatformTestCase() {
    fun testEmbeddedWebColorsMatchNativeHtml() {
        val text = "<style>.card { color: red; }</style><script>const name = \"{{ name }}\";</script>"
        val native = myFixture.configureByText("native.html", text)
        val nativeHighlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, native.virtualFile)
        nativeHighlighter.setText(text)
        val template = myFixture.configureByText("native.html.tera", text)
        val templateHighlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, template.virtualFile)
        templateHighlighter.setText(text)
        for (word in listOf("color", "red", "const")) {
            val offset = text.indexOf(word)
            val expected = nativeHighlighter.createIterator(offset).textAttributesKeys.map { it.externalName }
            val actual = templateHighlighter.createIterator(offset).textAttributesKeys.map { it.externalName }
            assertFalse("No native color for $word", expected.isEmpty())
            assertEquals("Incorrect template color for $word", expected, actual)
        }
        assertTrue(templateHighlighter.createIterator(text.indexOf("{{")).textAttributesKeys.contains(TeraHighlighterColors.DELIMITER))
    }

    fun testEmbeddedCssAndJavaScriptPsi() {
        val text = """
            <style>.card { color: red; }</style>
            <script>const greeting = "{{ name }}"; console.log(greeting);</script>
            <p>{{ name }}</p>
        """.trimIndent()
        val file = myFixture.configureByText("embedded.html.tera", text)
        val html = file.viewProvider.getPsi(HTMLLanguage.INSTANCE)!!
        assertEquals(text, html.text)
        val elements = PsiTreeUtil.collectElements(html) { true }
        assertTrue("CSS PSI not connected", elements.any { it.language.id == "CSS" })
        assertTrue("JavaScript PSI not connected", elements.any { it.language.id == "JavaScript" })
        assertEmpty(TeraExternalAnnotator().doAnnotate(text))
    }

    fun testDirectCssTemplate() = checkLanguage("style.css.tera", ".card { color: {{ color }}; }", "CSS")
    fun testDirectJavaScriptTemplate() = checkLanguage("script.js.tera", "const name = \"{{ name }}\";", "JavaScript")

    fun testCssCompletion() {
        myFixture.configureByText("completion.css.tera", ".card { col<caret> }")
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.orEmpty().contains("color") || myFixture.editor.document.text.contains("color"))
    }

    fun testJavaScriptLocalCompletion() {
        myFixture.configureByText("completion.js.tera", "const greeting = \"{{ name }}\"; gre<caret>")
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.orEmpty().contains("greeting") || myFixture.editor.document.text.endsWith("greeting"))
    }

    private fun checkLanguage(name: String, text: String, id: String) {
        val file = myFixture.configureByText(name, text)
        val language = Language.findLanguageByID(id)!!
        assertEquals(language, (file.viewProvider as TeraFileViewProvider).templateDataLanguage)
        assertEquals(text, file.viewProvider.getPsi(language)!!.text)
        assertEmpty(TeraExternalAnnotator().doAnnotate(text))
    }
}
