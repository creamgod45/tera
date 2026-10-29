package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Hover documentation for Tera 2 comprehensions: the brackets, `for`/`in`, and the variables they bind. */
class TeraComprehensionDocTest : BasePlatformTestCase() {

    private fun scalar(n: String) = TeraType.Scalar(n)
    private val item = TeraType.Struct("Dept", listOf(TeraField("amount", scalar("number"), "i64")))
    private val context = TeraContextResult(listOf(
        TeraField("depts", TeraType.ArrayOf(item), "Vec<Dept>"),
        TeraField("scores", TeraType.MapOf(scalar("number")), "HashMap<String, i32>"),
    ), complete = true)

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&amp;", "&").replace(Regex("\\s+"), " ").replace(" :", ":").trim()

    private var n = 0

    private fun hover(version: String, textWithCaret: String): String? {
        TeraSettings.getInstance(project).state.version = version
        val provider = object : TeraContextProvider {
            override fun resolve(project: com.intellij.openapi.project.Project, template: com.intellij.openapi.vfs.VirtualFile) = context
        }
        if (n == 0) TeraContextProvider.EP.point.registerExtension(provider, testRootDisposable)
        val caret = textWithCaret.indexOf("<caret>")
        val file = myFixture.addFileToProject("templates/c${n++}.html.tera", textWithCaret.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val p = TeraDocumentationProvider()
        val element = p.getCustomDocumentationElement(myFixture.editor, file, null, caret) ?: return null
        return p.generateDoc(element, null)?.let(::plain)
    }

    fun testForKeywordExplainsTheComprehension() {
        val doc = hover("2", "{% set m = [d.amount f<caret>or d in depts] | sort | last %}")!!
        assertTrue(doc, doc.contains("Tera 2 comprehension"))
        assertTrue(doc, doc.contains("Loops over: array<Dept>"))
        assertTrue(doc, doc.contains("Result: a list"))
    }

    fun testOpeningBracketAndInAreDocumentedToo() {
        assertNotNull(hover("2", "{% set m = <caret>[d.amount for d in depts] %}"))
        assertNotNull(hover("2", "{% set m = [d.amount for d i<caret>n depts] %}"))
    }

    fun testBoundVariableShowsItsTypeAndScope() {
        val doc = hover("2", "{% set m = [d.amount for <caret>d in depts] %}")!!
        assertTrue(doc, doc.contains("(comprehension variable) d: Dept"))
        assertTrue(doc, doc.contains("only between the brackets"))
        val use = hover("2", "{% set m = [<caret>d.amount for d in depts] %}")!!
        assertTrue(use, use.contains("(comprehension variable) d: Dept"))
    }

    fun testMapComprehensionKeyAndValue() {
        val key = hover("2", "{% set m = {<caret>k: v for k, v in scores} %}")!!
        assertTrue(key, key.contains("(comprehension variable) k: string"))
        val value = hover("2", "{% set m = {k: <caret>v for k, v in scores} %}")!!
        assertTrue(value, value.contains("v: number"))
        assertTrue(value, value.contains("Result: a map") || value.contains("only between the braces"))
    }

    fun testNothingInTera1() {
        assertNull(hover("1", "{% set m = [d.amount f<caret>or d in depts] %}"))
    }
}
