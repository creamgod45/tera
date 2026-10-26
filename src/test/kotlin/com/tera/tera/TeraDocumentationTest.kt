package com.tera.tera

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Hover / Quick Documentation for macros, their parameters and import aliases. */
class TeraDocumentationTest : BasePlatformTestCase() {

    private fun fixture(path: String) = javaClass.getResource("/rocket/examples/$path")!!.readText()
    private val macrosText get() = fixture("forms/templates/macros.html.tera")

    /** Rocket's macros.html.tera with a doc comment written above `input`. */
    private val documentedMacros get() = macrosText.replaceFirst(
        "{% macro input(",
        """{#
  Renders a labelled <input> with its validation errors.
  @param type {string} HTML input type, e.g. text or date
  @param label Visible label text
  @param name Form field name; also used to look up the value
  @param value - Initial value when the form has none
#}
{% macro input(""",
    )

    private val call = """{{ m::input(label="Publish Date", type="date", name="submission.date") }}"""
    private val page = """{% import "macros" as m %}$call"""

    private fun plain(html: String) =
        html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").replace(" :", ":").trim()

    /** Documentation the IDE shows when hovering at the caret; the platform pipeline must find a target. */
    private fun docAt(name: String, textWithCaret: String): String? {
        val caret = textWithCaret.indexOf("<caret>")
        val file = myFixture.addFileToProject("templates/$name", textWithCaret.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        val targets = IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, file, caret)
        if (targets.isEmpty()) return null
        val provider = TeraDocumentationProvider()
        val element = provider.getCustomDocumentationElement(myFixture.editor, file, null, caret) ?: return null
        return provider.generateDoc(element, null)
    }

    private fun hover(part: String, name: String, macros: String = documentedMacros): String {
        if (myFixture.findFileInTempDir("templates/macros.html.tera") == null) {
            myFixture.addFileToProject("templates/macros.html.tera", macros)
        }
        val at = page.indexOf(part)
        return plain(docAt(name, page.substring(0, at + 1) + "<caret>" + page.substring(at + 1))!!)
    }

    // ── every segment of  m::input(label=…, type=…, name=…)  ─────────────────

    fun testHoverMacroName() {
        val doc = hover("input(", "a.html.tera")
        assertTrue(doc, doc.contains("""macro input(type, label, name, value="")"""))
        assertTrue(doc, doc.contains("Renders a labelled <input> with its validation errors."))
        assertTrue(doc, doc.contains("type: string – required – HTML input type, e.g. text or date"))
        assertTrue(doc, doc.contains("label: any – required – Visible label text"))
        assertTrue(doc, doc.contains("name: any – required – Form field name; also used to look up the value"))
        assertTrue(doc, doc.contains("""value: string – optional, default "" – Initial value when the form has none"""))
        assertTrue(doc, doc.contains("Defined in: macros.html.tera"))
    }

    fun testHoverArgumentNameShowsParameterTypeAndComment() {
        val doc = hover("label=", "b.html.tera")
        assertTrue(doc, doc.contains("(parameter) label: any"))
        assertTrue(doc, doc.contains("Visible label text"))
        assertTrue(doc, doc.contains("Kind: required"))
        assertTrue(doc, doc.contains("""Macro: macro input(type, label, name, value="")"""))
        assertTrue(doc, doc.contains("About macro: Renders a labelled <input> with its validation errors."))
        assertTrue(doc, doc.contains("Defined in: macros.html.tera"))
    }

    fun testHoverEachArgumentOfTheCall() {
        val expected = mapOf(
            "type=" to listOf("(parameter) type: string", "HTML input type, e.g. text or date"),
            "label=" to listOf("(parameter) label: any", "Visible label text"),
            "name=" to listOf("(parameter) name: any", "Form field name; also used to look up the value"),
        )
        var n = 0
        for ((part, needles) in expected) {
            val doc = hover(part, "arg${n++}.html.tera")
            for (needle in needles) assertTrue("$part: $doc", doc.contains(needle))
        }
    }

    fun testHoverNamespaceAliasShowsImportAndItsMacros() {
        val doc = hover("m::", "c.html.tera")
        assertTrue(doc, doc.contains("""import "macros" as m"""))
        assertTrue(doc, doc.contains("Template: macros.html.tera"))
        for (name in listOf("value_for(name)", "errors_for(name)", "checkbox(name, label, value=\"yes\")",
            "textarea(label, name, placeholder=\"\", max=250)", "select(label, name, options)")) {
            assertTrue("$name in $doc", doc.contains(name))
        }
        assertTrue(doc, doc.contains("Renders a labelled <input> with its validation errors."))
    }

    fun testOptionalParameterShowsDefaultAndInferredType() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        val src = """{% import "macros" as m %}{{ m::textarea(label="l", name="n", max=1) }}"""
        for ((part, needles) in mapOf(
            "max=" to listOf("(parameter) max: integer = 250", "Kind: optional, default 250"),
            "placeholder" to emptyList(),
        )) {
            val at = src.indexOf(part).takeIf { it >= 0 } ?: continue
            val doc = plain(docAt("d${part.length}.html.tera", src.substring(0, at + 1) + "<caret>" + src.substring(at + 1))!!)
            for (needle in needles) assertTrue(doc, doc.contains(needle))
        }
    }

    fun testWithoutCommentTypesAreInferredFromDefaultsAndMarkedAny() {
        val doc = hover("input(", "e.html.tera", macrosText)
        assertTrue(doc, doc.contains("type: any – required"))
        assertTrue(doc, doc.contains("""value: string – optional, default """""))
        assertFalse(doc, doc.contains("Renders"))
    }

    fun testInferredTypesForAllLiteralKinds() {
        val text = """{% macro f(a="s", b=1, c=1.5, d=true, e=[1], g={"k": 1}, h=other, i) %}{% endmacro %}{{ self::<caret>f(i=1) }}"""
        val doc = plain(docAt("t.html.tera", text)!!)
        for (needle in listOf("a: string", "b: integer", "c: float", "d: boolean", "e: array", "g: object", "h: any", "i: any")) {
            assertTrue("$needle in $doc", doc.contains(needle))
        }
    }

    // ── declarations and same-file calls ─────────────────────────────────────

    fun testHoverDeclarationsThemselves() {
        val src = documentedMacros
        val macro = plain(docAt("m1.html.tera", src.replaceFirst("macro input(", "macro in<caret>put("))!!)
        assertTrue(macro, macro.contains("macro input("))
        val param = plain(docAt("m2.html.tera", src.replaceFirst("input(type, label,", "input(type, la<caret>bel,"))!!)
        assertTrue(param, param.contains("(parameter) label: any"))
        assertTrue(param, param.contains("Visible label text"))
    }

    fun testSelfCallInsideMacrosFile() {
        val doc = plain(docAt("macros.html.tera",
            macrosText.replace("self::value_for(name=name) }}'", "self::val<caret>ue_for(name=name) }}'"))!!)
        assertTrue(doc, doc.contains("macro value_for(name)"))
    }

    fun testSelfCallArgumentJumpsToSameFileParameter() {
        val text = "{% macro hello(a, b=2) %}{% endmacro %}{{ self::hello(b<caret>=1, a=0) }}"
        val doc = plain(docAt("n.html.tera", text)!!)
        assertTrue(doc, doc.contains("(parameter) b: integer = 2"))
    }

    fun testMacroWithoutParameters() {
        val doc = plain(docAt("n2.html.tera", "{% macro hello() %}Hi{% endmacro hello %}{{ self::he<caret>llo() }}")!!)
        assertTrue(doc, doc.contains("macro hello()"))
        assertTrue(doc, doc.contains("Parameters: none"))
    }

    // ── comments ─────────────────────────────────────────────────────────────

    fun testPlainCommentBecomesDescription() {
        val text = "{# Renders a labelled field.\n   Escapes the label. #}\n{% macro field(label) %}{% endmacro %}{{ self::fi<caret>eld(label=1) }}"
        val doc = plain(docAt("c1.html.tera", text)!!)
        assertTrue(doc, doc.contains("Renders a labelled field. Escapes the label."))
    }

    fun testParamTagContinuationLinesAndTrimmedComment() {
        val text = "{#- Greets.\n @param who {string} Name of\n   the person -#}\n{%- macro hi(who) -%}{% endmacro %}{{ self::<caret>hi(who=1) }}"
        val doc = plain(docAt("c2.html.tera", text)!!)
        assertTrue(doc, doc.contains("Greets."))
        assertTrue(doc, doc.contains("who: string – required – Name of the person"))
    }

    fun testUnrelatedCommentsAreNotDescriptions() {
        val a = plain(docAt("u1.html.tera", "{# header #}<p></p>{% macro f() %}{% endmacro %}{{ self::<caret>f() }}")!!)
        assertFalse(a, a.contains("header"))
        val b = plain(docAt("u2.html.tera", "{# note #}{% if x %}{% endif %}{% macro g() %}{% endmacro %}{{ self::<caret>g() }}")!!)
        assertFalse(b, b.contains("note"))
    }

    fun testHtmlIsEscaped() {
        val text = "{# <script>alert(1)</script> @param a {<i>} <b>x</b> #}{% macro f(a=\"<b>\") %}{% endmacro %}{{ self::<caret>f() }}"
        val html = docAt("x.html.tera", text)!!
        assertFalse(html, html.contains("<script>"))
        assertFalse(html, html.contains("<b>"))
        assertFalse(html, html.contains("<i>x"))
        assertTrue(html, html.contains("&lt;script&gt;"))
    }

    // ── no documentation where there is nothing to document ──────────────────

    fun testNoDocumentationForUnknownMacroUnresolvedImportOrPlainHtml() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        assertNull(docAt("n1.html.tera", """{% import "macros" as m %}{{ m::no<caret>pe() }}"""))
        assertNull(docAt("n3.html.tera", "<p>hel<caret>lo</p>{% macro a() %}{% endmacro %}"))
        assertNull(docAt("n4.html.tera", """{% import "macros" as m %}{{ m::input(zz<caret>z=1) }}"""))
    }

    fun testQuickNavigateInfo() {
        val file = myFixture.addFileToProject("templates/q.html.tera",
            """{% import "x" as x %}{% macro a(x, y=1) %}{% endmacro %}""")
        val psi = file.viewProvider.getPsi(TeraLanguage)!!
        val p = TeraDocumentationProvider()
        fun info(marker: String) = psi.findElementAt(file.text.indexOf(marker)).let { p.getQuickNavigateInfo(it, it) }
        assertEquals("q.html.tera: macro a(x, y=1)", info("a(x"))
        assertEquals("q.html.tera: a(x: any)", info("x, y"))
        assertEquals("q.html.tera: a(y: integer)", info("y=1"))
        assertEquals("import \"x\" as x", info("x %}"))
    }
}
