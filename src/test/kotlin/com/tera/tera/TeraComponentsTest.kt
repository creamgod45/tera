package com.tera.tera

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import junit.framework.TestCase

/** Scanning of Tera 2 component definitions and calls, modelled on the examples in the tera 2.4.0 test suite. */
class TeraComponentScannerTest : TestCase() {
    private fun defs(text: String) = TeraComponentScanner.definitions(text)
    private fun calls(text: String) = TeraComponentScanner.calls(TeraSymbolScanner.scanBlocks(text))
    private fun assertEmpty(c: Collection<*>) = assertTrue("expected empty but was $c", c.isEmpty())

    fun testPlainAndTypedParameters() {
        val d = defs("""{% component card(name, age, priority: integer = 1, visible: bool = true) %}x{% endcomponent card %}""").single()
        assertEquals("card", d.name)
        assertEquals(listOf("name", "age", "priority", "visible"), d.params.map { it.name })
        assertEquals(listOf(null, null, "integer", "bool"), d.params.map { it.type })
        assertEquals(listOf(null, null, "1", "true"), d.params.map { it.defaultText })
        assertEquals(listOf("name", "age"), d.requiredParams.map { it.name })
        assertEquals("card(name, age, priority: integer = 1, visible: bool = true)", d.signature())
    }

    fun testImplicitAndRestParameters() {
        val d = defs("""{% component show(@name = "default", @global_lang, label, ...rest) %}{% endcomponent show %}""").single()
        assertEquals(listOf(true, true, false, false), d.params.map { it.implicit })
        assertEquals(listOf(false, false, false, true), d.params.map { it.rest })
        assertEquals("\"default\"", d.params[0].defaultText)
        assertEquals(listOf("label"), d.requiredParams.map { it.name })      // implicit and rest are never required
        assertTrue(d.hasRest)
        assertEquals("show(@name = \"default\", @global_lang, label, ...rest)", d.signature())
    }

    fun testDottedNamesAndNoParameters() {
        val text = "{% component ui.forms.advanced.widget(title: string) %}{% endcomponent ui.forms.advanced.widget %}{% component hello() %}{% endcomponent %}"
        assertEquals(listOf("ui.forms.advanced.widget", "hello"), defs(text).map { it.name })
        assertEmpty(defs(text)[1].params)
    }

    fun testDefaultsContainingCommasAndBrackets() {
        val text = """{% component c(integer=-1, float=-1.5, positive_array=[1, 2], negative_map={"foo": -1, "b": [1, 2]}, s="a,b") %}{% endcomponent %}"""
        val d = defs(text).single()
        assertEquals(listOf("-1", "-1.5", "[1, 2]", "{\"foo\": -1, \"b\": [1, 2]}", "\"a,b\""), d.params.map { it.defaultText })
    }

    fun testCloseParenOffsetMarksWhereParametersAreAdded() {
        val text = "{% component c(a, b) %}x{% endcomponent %}"
        assertEquals(text.indexOf(")"), defs(text).single().closeParen)
        val none = "{% component c() %}"
        assertEquals(none.indexOf(")"), defs(none).single().closeParen)
    }

    fun testNameRangeCoversTheWholeDottedName() {
        val text = "{% component ui.button(a) %}"
        assertEquals("ui.button", defs(text).single().nameRange.substring(text))
    }

    fun testCallForms() {
        val text = """
            {{ <hello/> }}
            {{ <card name="x" age={30} priority={5} visible={some_bool}/> }}
            {{ <button name age/> }}
            {{ <ui.button label="Click me" variant="secondary"/> }}
            {% <hello> %}Hello {{ name }}!{% </hello> %}
            {% <ui.forms.advanced.widget title="Advanced Settings"> %}x{% </ui.forms.advanced.widget> %}
            {{ <open_button {...extra} age={10} foo="bar"/> }}
        """.trimIndent()
        val c = calls(text)
        assertEquals(listOf("hello", "card", "button", "ui.button", "hello", "ui.forms.advanced.widget", "open_button"), c.map { it.name })
        assertTrue(c.all { it.closed })
        assertEquals(listOf("name", "age", "priority", "visible"), c[1].attrs.map { it.name })
        assertEquals(listOf("name", "age"), c[2].attrs.map { it.name })               // shorthand
        assertEquals(listOf(null, "age", "foo"), c[6].attrs.map { it.name })
        assertEquals(listOf(true, false, false), c[6].attrs.map { it.spread })
        assertEquals("ui.button", c[3].nameRange.substring(text))
    }

    fun testAttributeNamesThatAreKeywordsToTheLexer() {
        val c = calls("""{{ <field type="text" from="a" for="b" if={x}/> }}""").single()
        assertEquals(listOf("type", "from", "for", "if"), c.attrs.map { it.name })
    }

    fun testComparisonsAndClosingTagsAreNotCalls() {
        assertEmpty(calls("{{ a < b }}{{ a<b }}{% if a < b %}x{% endif %}{{ (a) < b }}{{ x[0] < y }}"))
        assertEmpty(calls("{% </hello> %}"))
        assertEquals(1, calls("{{ a < b and <c/> }}").size)
        assertEquals(1, calls("{{ [<a/>] }}").size)
    }

    fun testUnclosedCall() {
        val c = calls("{{ <card name=\"x\" ").single()
        assertFalse(c.closed)
        assertEquals(listOf("name"), c.attrs.map { it.name })
    }

    fun testCallsNestedInAttributeValues() {
        val c = calls("""{{ <outer body={<inner a="1"/>}/> }}""")
        assertTrue(c.map { it.name }.contains("outer"))
    }
}

/** What the plugin says about components. */
class TeraComponentsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).state.version = "2"
    }

    private fun comps(vararg src: String) = src.flatMap { TeraComponentScanner.definitions(it) }.map { TeraComponent(it, null) }

    private val button = "{% component button(label: string, variant: string = \"primary\", @lang, ...rest) %}{{ label }}{{ body }}{% endcomponent button %}"
    private val card = "{% component card(name, age, priority: integer = 1) %}x{% endcomponent card %}"
    private val plain = "{% component plain() %}x{% endcomponent plain %}"

    private fun check(tpl: String, vararg defs: String) = TeraComponentChecker.callErrors(tpl, comps(*defs))
    private fun TeraError.text(src: String) = range.substring(src)

    // ── calls ────────────────────────────────────────────────────────────────

    fun testValidCallsAreClean() {
        assertEmpty(check("{{ <card name=\"a\" age={3}/> }}{{ <card name age priority={2}/> }}{{ <plain/> }}", card, plain))
        assertEmpty(check("{% <button label=\"x\"> %}body{% </button> %}", button))
    }

    fun testUnknownComponent() {
        val src = "{{ <missing a=\"1\"/> }}"
        val e = check(src).single()
        assertEquals("Unknown component 'missing'", e.message)
        assertEquals("missing", e.text(src))
        assertEquals(HighlightSeverity.ERROR, e.severity)
    }

    fun testMissingRequiredAttributes() {
        val src = "{{ <card age={1}/> }}"
        assertEquals("Component 'card' is missing required attribute(s): name", check(src, card).single().message)
        assertEquals("Component 'card' is missing required attribute(s): name, age", check("{{ <card/> }}", card).single().message)
        // implicit and defaulted parameters, and the rest parameter, are never required
        assertEmpty(check("{{ <button label=\"x\"/> }}", button))
    }

    fun testUnknownAndDuplicateAttributes() {
        val unknown = "{{ <card name age bogus=\"1\"/> }}"
        val e = check(unknown, card).single()
        assertEquals("Component 'card' has no attribute 'bogus'", e.message)
        assertEquals("bogus", e.text(unknown))
        val dup = "{{ <card name=\"a\" name=\"b\" age/> }}"
        assertEquals("Attribute 'name' is given more than once", check(dup, card).single().message)
    }

    fun testRestParameterAcceptsAnyAttribute() {
        assertEmpty(check("{{ <button label=\"x\" id=\"b\" class=\"btn\" data_x={1}/> }}", button))
    }

    fun testImplicitParametersMayBePassedExplicitly() {
        assertEmpty(check("{{ <button label=\"x\" lang=\"fr\"/> }}", button))
    }

    fun testSpreadSkipsTheMissingCheckButNotTheUnknownCheck() {
        assertEmpty(check("{% set p = {\"name\": 1} %}{{ <card {...p} age/> }}", card))
        assertEquals(1, check("{{ <card {...p} bogus=\"1\"/> }}", card).size)
    }

    fun testUnclosedCallDoesNotReportMissingAttributes() {
        assertEmpty(check("{{ <card ", card))
    }

    // ── bodies ───────────────────────────────────────────────────────────────

    private fun body(text: String) = TeraComponentChecker.bodyErrors(text)

    fun testNamesTheBodyDoesNotDefineMustBeImplicitParameters() {
        val src = "{% component c(a) %}{{ a }}{{ values }}{{ errors.x }}{% endcomponent c %}"
        val e = body(src)
        assertEquals(listOf("values", "errors"), e.map { it.text(src) })
        assertEquals("Components cannot read the render context or the caller's variables: declare '@values' as an implicit parameter", e[0].message)
        assertTrue(e.all { it.severity == HighlightSeverity.ERROR })
        // one fix for the whole component, shared by both errors
        assertEquals(e[0].fixes, e[1].fixes)
        assertEquals("{% component c(a, @values, @errors) %}{{ a }}{{ values }}{{ errors.x }}{% endcomponent c %}", e[0].fixes.single().applyTo(src))
        assertEquals("Declare implicit parameter(s) @values, @errors", e[0].fixes.single().label)
    }

    fun testFixForAComponentWithoutParameters() {
        val src = "{% component c() %}{{ title }}{% endcomponent %}"
        assertEquals("{% component c(@title) %}{{ title }}{% endcomponent %}", body(src).single().fixes.single().applyTo(src))
    }

    fun testWhatTheBodyDefinesItselfIsFine() {
        val src = """
            {% component c(a, @b, ...rest) %}
              {{ a }}{{ b }}{{ rest.x }}{{ body }}
              {% set t = 1 %}{{ t }}{% set u %}x{% endset %}{{ u }}
              {% for i in range(end=3) %}{{ i }}{{ loop.index }}{% endfor %}
              {% for k, v in rest %}{{ k }}{{ v }}{% endfor %}
              {{ <other x={a}/> }}{{ <other a b/> }}
            {% endcomponent c %}
        """.trimIndent()
        assertEmpty(body(src))
    }

    fun testOptionalAccessIsNotAnError() {
        assertEmpty(body("{% component c() %}{% if x is defined %}y{% endif %}{{ z | default(value=1) }}{% endcomponent %}"))
    }

    fun testOnlyTheOwningComponentIsAffected() {
        val src = "{% component a(x) %}{{ x }}{% endcomponent a %}{% component b() %}{{ y }}{% endcomponent b %}{{ outside }}"
        val e = body(src)
        assertEquals(listOf("y"), e.map { it.text(src) })     // text outside components is the template's, not checked here
        assertEquals("{% component a(x) %}{{ x }}{% endcomponent a %}{% component b(@y) %}{{ y }}{% endcomponent b %}{{ outside }}",
            e.single().fixes.single().applyTo(src))
    }

    fun testShorthandAttributesAreVariableUses() {
        val src = "{% component c() %}{{ <card name age/> }}{% endcomponent c %}"
        assertEquals(listOf("name", "age"), body(src).map { it.text(src) })
    }

    // ── completion ───────────────────────────────────────────────────────────

    private fun suggest(textWithCaret: String, defs: List<TeraComponent> = comps(button, card, plain),
                        version: TeraVersion = TeraVersion.V2): List<TeraSuggestion> {
        val caret = textWithCaret.indexOf("<caret>")
        return TeraCompletionEngine.suggest(textWithCaret.replace("<caret>", ""), caret, { null }, { emptyList() },
            null, emptyList(), version, defs)
    }

    private fun names(src: String, defs: List<TeraComponent> = comps(button, card, plain), version: TeraVersion = TeraVersion.V2) =
        suggest(src, defs, version).map { it.text }

    fun testComponentNamesAfterTheOpeningAngleBracket() {
        assertEquals(listOf("button", "card", "plain"), names("{{ <<caret> }}"))
        assertEquals(listOf("button", "card", "plain"), names("{{ <ca<caret> }}"))
        assertEquals(listOf("button", "card", "plain"), names("{% <<caret> %}"))
        assertEquals(listOf("button", "card", "plain"), names("{{ a ~ <<caret> }}"))
        val s = suggest("{{ <<caret> }}").first { it.text == "card" }
        assertEquals("(name, age, priority: integer = 1)", s.tail)
        assertEquals("component", s.type)
    }

    fun testNoComponentNamesAfterAComparison() {
        assertFalse("button" in names("{{ a <<caret> }}"))
        assertFalse("button" in names("{% if a <<caret> %}{% endif %}"))
    }

    fun testComponentNamesOnlyInTera2() {
        assertEquals(emptyList<String>(), names("{{ <<caret> }}", version = TeraVersion.V1).filter { it == "button" })
    }

    fun testAttributeNamesInsideACallTag() {
        assertEquals(listOf("name", "age", "priority"), names("{{ <card <caret>/> }}"))
        assertEquals(listOf("age", "priority"), names("{{ <card name=\"x\" <caret>/> }}"))
        assertEquals(listOf("priority"), names("{{ <card name age <caret>/> }}"))
        assertEquals(listOf("age", "priority"), names("{{ <card name={1} <caret>/> }}"))
        assertEquals(listOf("age", "priority"), names("{% <card name <caret>> %}x{% </card> %}"))
    }

    fun testAttributeSuggestionsDescribeAndInsertByType() {
        val s = suggest("{{ <button <caret>/> }}").associateBy { it.text }
        assertEquals(setOf("label", "variant", "lang"), s.keys)             // the rest parameter is not an attribute name
        assertEquals("required", s.getValue("label").tail)
        assertEquals("default \"primary\"", s.getValue("variant").tail)
        assertEquals("implicit", s.getValue("lang").tail)
        assertEquals("=\"\"", s.getValue("label").suffix)
        val typed = suggest("{{ <card <caret>/> }}").associateBy { it.text }
        assertEquals("=\"\"", typed.getValue("name").suffix)                // untyped: usually a string
        assertEquals("={}", typed.getValue("priority").suffix)              // integer: an expression
        assertTrue(typed.values.all { it.type == "attribute" })
    }

    fun testNoAttributesWhereTheyDoNotApply() {
        assertEmpty(names("{{ <card name=<caret>/> }}"))                    // a value is being written
        assertFalse("age" in names("{{ <card name={<caret>}/> }}"))         // inside {…}: an expression
        assertEmpty(names("{{ <unknown <caret>/> }}"))
        assertFalse("age" in names("{{ <card name age/> <caret> }}"))       // the tag is closed
        assertEmpty(names("{{ <plain <caret>/> }}"))
    }

    fun testExpressionsInsideAttributeBracesStillComplete() {
        val s = names("{% set item = 1 %}{{ <card name={<caret>}/> }}")
        assertTrue(s.toString(), "item" in s)
    }

    fun testVariablesInsideAComponentBody() {
        val src = "{% set outer = 1 %}{% component c(a, @lang, ...rest) %}{{ <caret> }}{% endcomponent c %}"
        val n = names(src)
        assertTrue(n.toString(), n.containsAll(listOf("a", "lang", "rest", "body")))
        assertFalse("outer" in n)                                           // the caller's and surrounding variables are not visible
        val typed = suggest("{% component c(n: integer, s = \"x\", ...rest) %}{{ <caret> }}{% endcomponent %}")
        assertEquals(": number", typed.first { it.text == "n" }.tail)
        assertEquals(": string", typed.first { it.text == "s" }.tail)
        assertEquals(": map<any>", typed.first { it.text == "rest" }.tail)
        assertEquals(": string", typed.first { it.text == "body" }.tail)
    }

    fun testRenderContextIsHiddenInsideAComponentOnly() {
        val ctx = TeraContextResult(listOf(TeraField("title", TeraType.Scalar("string"))), true)
        val caret = "{{ <caret> }}{% component c() %}{% endcomponent %}"
        fun with(text: String) = TeraCompletionEngine.suggest(text.replace("<caret>", ""), text.indexOf("<caret>"), { null }, { emptyList() },
            ctx, emptyList(), TeraVersion.V2, emptyList()).map { it.text }
        assertTrue("title" in with(caret))
        assertFalse("title" in with("{% component c() %}{{ <caret> }}{% endcomponent %}"))
        assertTrue("title" in with("{% component c() %}{% endcomponent %}{{ <caret> }}"))
    }

    fun testNoMacroNamespacesInTera2() {
        val s = TeraCompletionEngine.suggest("{% macro m() %}{% endmacro %}{{  }}", 33, { null }, { emptyList() }, null, emptyList(), TeraVersion.V2)
        assertTrue(s.none { it.suffix == "::" })
    }

    // ── documentation and navigation ─────────────────────────────────────────

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&amp;", "&").replace(Regex("\\s+"), " ").replace(" :", ":").trim()

    private val documented = """
        {# A clickable button.
           @param label Text shown on the button
           @param variant Visual style #}
        $button
    """.trimIndent()

    private fun hover(name: String, textWithCaret: String): String? {
        val caret = textWithCaret.indexOf("<caret>")
        val file = myFixture.addFileToProject("templates/$name", textWithCaret.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        if (IdeDocumentationTargetProvider.getInstance(project).documentationTargets(myFixture.editor, file, caret).isEmpty()) return null
        val provider = TeraDocumentationProvider()
        val element = provider.getCustomDocumentationElement(myFixture.editor, file, null, caret) ?: return null
        return provider.generateDoc(element, null)?.let(::plain)
    }

    fun testHoverComponentNameShowsSignatureAttributesAndComment() {
        myFixture.addFileToProject("templates/ui.html.tera", documented)
        val doc = hover("page.html.tera", "{{ <but<caret>ton label=\"x\"/> }}")!!
        assertTrue(doc, doc.contains("component button(label: string, variant: string = \"primary\", @lang, ...rest)"))
        assertTrue(doc, doc.contains("A clickable button."))
        assertTrue(doc, doc.contains("label: string – required – Text shown on the button"))
        assertTrue(doc, doc.contains("variant: string – optional, default \"primary\" – Visual style"))
        assertTrue(doc, doc.contains("lang – implicit, read from the caller's scope"))
        assertTrue(doc, doc.contains("...rest – collects every other attribute into a map"))
        assertTrue(doc, doc.contains("Called as: <button label=…/>"))
        assertTrue(doc, doc.contains("Defined in: ui.html.tera"))
    }

    fun testHoverAttributeName() {
        myFixture.addFileToProject("templates/ui.html.tera", documented)
        val doc = hover("page2.html.tera", "{{ <button lab<caret>el=\"x\"/> }}")!!
        assertTrue(doc, doc.contains("(attribute) label: string"))
        assertTrue(doc, doc.contains("Text shown on the button"))
        assertTrue(doc, doc.contains("Kind: required"))
        assertTrue(doc, doc.contains("Component: button(label: string"))
        assertTrue(doc, doc.contains("About component: A clickable button."))
    }

    fun testHoverTheDefinitionItself() {
        val doc = hover("defs.html.tera", documented.replace("component button(label", "component but<caret>ton(label"))!!
        assertTrue(doc, doc.contains("component button("))
        val attr = hover("defs2.html.tera", documented.replace("variant: string =", "var<caret>iant: string ="))!!
        assertTrue(attr, attr.contains("(attribute) variant: string = \"primary\""))
        val implicit = hover("defs3.html.tera", documented.replace("@lang", "@la<caret>ng"))!!
        assertTrue(implicit, implicit.contains("(attribute) lang: any"))
        assertTrue(implicit, implicit.contains("implicit, read from the caller's scope"))
    }

    fun testHoverUnknownComponentShowsNothing() {
        assertNull(hover("n.html.tera", "{{ <nope<caret> a=\"1\"/> }}"))
    }

    private fun goto(name: String, textWithCaret: String): List<com.intellij.psi.PsiElement> {
        val caret = textWithCaret.indexOf("<caret>")
        val file = myFixture.addFileToProject("templates/$name", textWithCaret.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        return GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, caret).toList()
    }

    fun testGotoComponentDefinitionInAnotherTemplate() {
        myFixture.addFileToProject("templates/ui.html.tera", "$card\n$button")
        val t = goto("g1.html.tera", "{{ <ca<caret>rd name age/> }}")
        assertEquals(listOf("card"), t.map { it.text })
        assertEquals("ui.html.tera", t.single().containingFile.name)
    }

    fun testGotoAttributeJumpsToTheParameter() {
        myFixture.addFileToProject("templates/ui.html.tera", card)
        val t = goto("g2.html.tera", "{{ <card na<caret>me=\"x\" age/> }}")
        assertEquals(listOf("name"), t.map { it.text })
        assertEquals("ui.html.tera", t.single().containingFile.name)
    }

    fun testGotoDottedComponentAndSameTemplate() {
        val src = "{% component ui.chip(a) %}x{% endcomponent ui.chip %}{{ <ui.ch<caret>ip a=\"1\"/> }}"
        val t = goto("g3.html.tera", src)
        assertEquals(listOf("ui"), t.map { it.text })              // the leaf at the start of the dotted name
        assertEquals(src.replace("<caret>", "").indexOf("ui.chip"), t.single().textOffset)
    }

    fun testGotoOfAnUnknownComponentHasNoTarget() {
        assertEmpty(goto("g4.html.tera", "{{ <mis<caret>sing/> }}"))
    }

    fun testComponentDefinedTwiceOffersBothSources() {
        myFixture.addFileToProject("templates/a.html.tera", card)
        myFixture.addFileToProject("templates/b.html.tera", card)
        val t = goto("g5.html.tera", "{{ <ca<caret>rd name age/> }}")
        assertEquals(listOf("a.html.tera", "b.html.tera"), t.map { it.containingFile.name }.sorted())
    }

    // ── editor ───────────────────────────────────────────────────────────────

    fun testEditorShowsComponentErrorsAndOffersTheImplicitParameterFix() {
        val file = myFixture.configureByText("c.html.tera", "{% component c(a) %}{{ a }}{{ val<caret>ues }}{% endcomponent c %}")
        val info = myFixture.doHighlighting().single { it.description?.startsWith("Components cannot read") == true }
        assertEquals(HighlightSeverity.ERROR, info.severity)
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Declare implicit parameter(s) @values" })
        myFixture.checkResult("{% component c(a, @values) %}{{ a }}{{ values }}{% endcomponent c %}")
        assertNotNull(file)
    }

    fun testEditorCompletionInsertsTheAttributeWithQuotes() {
        myFixture.addFileToProject("templates/ui.html.tera", card)
        myFixture.configureByText("page.html.tera", "{{ <card nam<caret>/> }}")
        myFixture.completeBasic()
        myFixture.checkResult("{{ <card name=\"\"/> }}")
        assertEquals(myFixture.editor.document.text.indexOf("\"\"") + 1, myFixture.caretOffset)
    }
}
