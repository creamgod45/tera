package com.tera.tera

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Go to definition for macros, namespaces, arguments and template paths. */
class TeraNavigationTest : BasePlatformTestCase() {

    /** These tests describe Tera 1.x behaviour (Rocket's engine); 2.x has its own tests. */
    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).state.version = "1"
    }

    private fun fixture(path: String) = javaClass.getResource("/rocket/examples/$path")!!.readText()
    private val macrosText get() = fixture("forms/templates/macros.html.tera")
    private val indexText get() = fixture("forms/templates/index.html.tera")

    /** Opens [textWithCaret] as templates/[name] and returns all go-to-declaration targets at the caret. */
    private fun targetsAt(name: String, textWithCaret: String): List<PsiElement> {
        val caret = textWithCaret.indexOf("<caret>")
        val file = myFixture.addFileToProject("templates/$name", textWithCaret.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        return GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, caret).toList()
    }

    private fun PsiElement.where() = containingFile.name to text

    // ── single targets, real Rocket templates ────────────────────────────────

    fun testMacroCallJumpsToDefinitionInImportedFile() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        val targets = targetsAt("index.html.tera", indexText.replaceFirst("m::input(", "m::inp<caret>ut("))
        assertEquals(listOf("macros.html.tera" to "input"), targets.map { it.where() })
        val def = targets.single()
        assertEquals(macrosText.indexOf("macro input(") + "macro ".length, def.textOffset)
    }

    fun testSelfCallJumpsToMacroInSameFile() {
        val text = macrosText.replace("self::value_for(name=name) }}'", "self::val<caret>ue_for(name=name) }}'")
        val targets = targetsAt("macros.html.tera", text)
        assertEquals(listOf("macros.html.tera" to "value_for"), targets.map { it.where() })
        assertEquals(macrosText.indexOf("macro value_for") + "macro ".length, targets.single().textOffset)
    }

    fun testNamespaceJumpsToImportAlias() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        val targets = targetsAt("page.html.tera", """{% import "macros" as m %}{{ <caret>m::input(type="t", label="l", name="n") }}""")
        assertEquals(listOf("page.html.tera" to "m"), targets.map { it.where() })
    }

    fun testImportPathJumpsToTemplateFile() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        val targets = targetsAt("page.html.tera", """{% import "mac<caret>ros" as m %}""")
        assertEquals(listOf("macros.html.tera"), targets.map { it.containingFile.name })
        assertTrue(targets.single() is com.intellij.psi.PsiFile)
    }

    fun testExtendsAndIncludePathsJumpToTemplates() {
        myFixture.addFileToProject("templates/base.html.tera", "<html></html>")
        for (tag in listOf("extends", "include")) {
            val targets = targetsAt("p_$tag.html.tera", """{% $tag "ba<caret>se" %}""")
            assertEquals(listOf("base.html.tera"), targets.map { it.containingFile.name })
        }
    }

    fun testArgumentNameJumpsToParameterDeclaration() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        val targets = targetsAt("page.html.tera",
            """{% import "macros" as m %}{{ m::input(type="t", la<caret>bel="l", name="n") }}""")
        assertEquals(listOf("macros.html.tera" to "label"), targets.map { it.where() })
        assertEquals(macrosText.indexOf("label, name, value=\"\")"), targets.single().textOffset)
    }

    // ── several definition sources → several targets (IDE shows a chooser list) ─

    fun testAmbiguousTemplateNameOffersEveryFile() {
        myFixture.addFileToProject("templates/macros.html.tera", "{% macro input(a) %}{% endmacro %}")
        myFixture.addFileToProject("templates/macros.txt.tera", "{% macro input(a) %}{% endmacro %}")
        val targets = targetsAt("page.html.tera", """{% import "macros" as m %}{{ m::in<caret>put(a=1) }}""")
        assertEquals(listOf("macros.html.tera", "macros.txt.tera"), targets.map { it.containingFile.name }.sorted())
        assertTrue(targets.all { it.text == "input" })
    }

    fun testAmbiguousImportPathOffersEveryFile() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        myFixture.addFileToProject("templates/macros.txt.tera", "{% macro x() %}{% endmacro %}")
        val targets = targetsAt("page.html.tera", """{% import "ma<caret>cros" as m %}""")
        assertEquals(listOf("macros.html.tera", "macros.txt.tera"), targets.map { it.containingFile.name }.sorted())
    }

    fun testDuplicateDefinitionsInOneFileOfferBoth() {
        val text = "{% macro a() %}{% endmacro %}{% macro a() %}{% endmacro %}{{ self::<caret>a() }}"
        val targets = targetsAt("dup.html.tera", text)
        assertEquals(2, targets.size)
        assertEquals(listOf(text.indexOf("a()"), text.lastIndexOf("a() %}")).sorted(), targets.map { it.textOffset }.sorted())
    }

    fun testAliasImportedTwiceOffersBothImportTags() {
        myFixture.addFileToProject("templates/a.html.tera", "{% macro f() %}{% endmacro %}")
        myFixture.addFileToProject("templates/b.html.tera", "{% macro f() %}{% endmacro %}")
        val text = """{% import "a" as m %}{% import "b" as m %}{{ <caret>m::f() }}"""
        val targets = targetsAt("two.html.tera", text)
        assertEquals(2, targets.size)
        assertEquals(listOf(text.indexOf("m %}"), text.lastIndexOf("m %}")).sorted(), targets.map { it.textOffset }.sorted())
    }

    fun testSameMacroNameInTwoImportsOfSameAliasOffersBothFiles() {
        myFixture.addFileToProject("templates/a.html.tera", "{% macro f() %}{% endmacro %}")
        myFixture.addFileToProject("templates/b.html.tera", "{% macro f() %}{% endmacro %}")
        val targets = targetsAt("three.html.tera", """{% import "a" as m %}{% import "b" as m %}{{ m::<caret>f() }}""")
        assertEquals(listOf("a.html.tera", "b.html.tera"), targets.map { it.containingFile.name }.sorted())
    }

    // ── nothing to jump to ───────────────────────────────────────────────────

    fun testUndefinedMacroHasNoTarget() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        assertEmpty(targetsAt("page.html.tera", """{% import "macros" as m %}{{ m::nope<caret>() }}"""))
    }

    fun testUnresolvedImportHasNoTarget() {
        assertEmpty(targetsAt("page.html.tera", """{% import "gho<caret>st" as g %}"""))
    }

    fun testSelfNamespaceAndPlainTextHaveNoTarget() {
        val text = "<p>hello</p>{% macro a() %}{% endmacro %}{{ <caret>self::a() }}"
        assertEmpty(targetsAt("self.html.tera", text))
    }

    fun testUnknownArgumentHasNoTarget() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        assertEmpty(targetsAt("page.html.tera",
            """{% import "macros" as m %}{{ m::value_for(zz<caret>z=1, name="n") }}"""))
    }

    // ── pure engine ──────────────────────────────────────────────────────────

    fun testEngineEdgesOfTokensAreInclusive() {
        val text = "{% macro a() %}{% endmacro %}{{ self::a() }}"
        val start = text.lastIndexOf("a()")
        val engine = { off: Int -> TeraNavigation.definitions(text, off) { emptyList() } }
        assertEquals(1, engine(start).size)          // just before the name
        assertEquals(1, engine(start + 1).size)      // just after the name
        assertEquals(0, engine(start - 1 - "self::".length - 1).size)
    }
}
