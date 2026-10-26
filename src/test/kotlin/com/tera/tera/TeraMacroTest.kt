package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Macro definition / import / call analysis, using Rocket's forms example as the real-world source. */
class TeraMacroTest : BasePlatformTestCase() {

    private fun fixture(path: String): String =
        javaClass.getResource("/rocket/examples/$path")!!.readText()

    private val macrosText get() = fixture("forms/templates/macros.html.tera")
    private val indexText get() = fixture("forms/templates/index.html.tera")

    private val rocketMacros get() = TeraSymbolScanner.scan(macrosText).macros

    private fun check(text: String, imports: Map<String, List<MacroDef>?> = mapOf("macros" to rocketMacros)) =
        TeraSemanticChecker.check(text) { imports[it] }

    // ── scanner ──────────────────────────────────────────────────────────────

    fun testScansAllRocketMacroDefinitions() {
        val byName = rocketMacros.associateBy { it.name }
        assertEquals(listOf("value_for", "errors_for", "input", "checkbox", "textarea", "select"), rocketMacros.map { it.name })
        assertEquals(listOf("type", "label", "name", "value"), byName["input"]!!.params.map { it.name })
        assertEquals(listOf("type", "label", "name"), byName["input"]!!.required.map { it.name })
        assertEquals(listOf("name", "label"), byName["checkbox"]!!.required.map { it.name })
        assertEquals(listOf("label", "name"), byName["textarea"]!!.required.map { it.name })
        assertEquals(listOf("label", "name", "options"), byName["select"]!!.required.map { it.name })
        assertEquals(listOf("name"), byName["value_for"]!!.required.map { it.name })
    }

    fun testMacroNameRangePointsAtTheName() {
        val input = rocketMacros.first { it.name == "input" }
        assertEquals("input", input.nameRange.substring(macrosText))
    }

    fun testScansImportAndCalls() {
        val symbols = TeraSymbolScanner.scan(indexText)
        assertEquals(listOf("macros" to "m"), symbols.imports.map { it.path to it.alias })
        assertEquals("macros", symbols.imports[0].pathRange.substring(indexText).trim('"'))
        assertTrue(symbols.calls.size >= 12)
        assertTrue(symbols.calls.all { it.namespace == "m" && it.closed })
        assertEquals(setOf("input", "checkbox", "select", "textarea"), symbols.calls.map { it.name }.toSet())
    }

    fun testSelfCallsAreScannedInsideMacros() {
        val calls = TeraSymbolScanner.scan(macrosText).calls
        assertEquals(setOf("value_for", "errors_for"), calls.map { it.name }.toSet())
        assertTrue(calls.all { it.namespace == "self" && it.args.single().name == "name" })
    }

    fun testNestedParenthesesAndDefaultsDoNotConfuseArguments() {
        val text = """{% macro m(a, b=f(1, 2), c="x,y") %}{% endmacro %}{{ self::m(a=g(1, 2), c=[1, 2]) }}"""
        val symbols = TeraSymbolScanner.scan(text)
        assertEquals(listOf("a", "b", "c"), symbols.macros.single().params.map { it.name })
        assertEquals(listOf(false, true, true), symbols.macros.single().params.map { it.hasDefault })
        assertEquals(listOf("a", "c"), symbols.calls.single { it.name == "m" }.args.map { it.name })
        assertEmpty(check(text))
    }

    // ── semantic checks: valid code stays clean ──────────────────────────────

    fun testRocketTemplatesHaveNoMacroDiagnostics() {
        assertEmpty(check(macrosText))
        assertEmpty(check(indexText))
    }

    fun testOptionalParametersMayBeOmittedOrGiven() {
        assertEmpty(check("""{% import "macros" as m %}{{ m::input(type="t", label="l", name="n") }}"""))
        assertEmpty(check("""{% import "macros" as m %}{{ m::input(type="t", label="l", name="n", value="v") }}"""))
    }

    // ── semantic checks: real errors are reported at the right place ─────────

    fun testUndefinedMacroInImportedTemplate() {
        val text = """{% import "macros" as m %}{{ m::inptu(type="t", label="l", name="n") }}"""
        val error = check(text).single()
        assertEquals(HighlightSeverity.ERROR, error.severity)
        assertEquals("inptu", error.range.substring(text))
        assertEquals("Macro 'inptu' is not defined in 'macros'", error.message)
    }

    fun testUndefinedSelfMacro() {
        val text = "{% macro a() %}{% endmacro %}{{ self::b() }}"
        val error = check(text).single()
        assertEquals("b", error.range.substring(text))
        assertEquals("Macro 'b' is not defined in this template", error.message)
    }

    fun testMissingRequiredArguments() {
        val text = """{% import "macros" as m %}{{ m::input(label="l") }}"""
        val error = check(text).single()
        assertEquals("input", error.range.substring(text))
        assertEquals("Macro 'input' is missing required argument(s): type, name", error.message)
    }

    fun testUnknownArgument() {
        val text = """{% import "macros" as m %}{{ m::value_for(name="n", extra=1) }}"""
        val error = check(text).single()
        assertEquals("extra=1", error.range.substring(text))
        assertEquals("Macro 'value_for' has no parameter 'extra'", error.message)
    }

    fun testPositionalArgumentIsRejected() {
        val text = """{% import "macros" as m %}{{ m::value_for("n") }}"""
        val errors = check(text)
        assertEquals("\"n\"", errors.first { it.message.contains("keyword") }.range.substring(text))
        assertTrue(errors.any { it.message.contains("missing required") })
    }

    fun testDuplicateArgument() {
        val text = """{% import "macros" as m %}{{ m::value_for(name="a", name="b") }}"""
        val error = check(text).single()
        assertEquals("name=\"b\"", error.range.substring(text))
    }

    fun testUnknownNamespace() {
        val text = "{{ nope::x() }}"
        val error = check(text).single()
        assertEquals("x", error.range.substring(text))
        assertEquals("Unknown macro namespace 'nope': add an import tag using 'as nope'", error.message)
    }

    fun testUnresolvedImportIsAWarningAndSuppressesCallErrors() {
        val text = """{% import "missing" as m %}{{ m::anything(a=1) }}"""
        val error = check(text, mapOf("missing" to null)).single()
        assertEquals(HighlightSeverity.WARNING, error.severity)
        assertEquals("\"missing\"", error.range.substring(text))
        assertEquals("Cannot resolve template 'missing'", error.message)
    }

    fun testDuplicateMacroAndParameter() {
        val text = "{% macro a(x, x) %}{% endmacro %}{% macro a() %}{% endmacro %}"
        val errors = check(text)
        assertEquals(2, errors.size)
        assertEquals(HighlightSeverity.ERROR, errors.single { it.message.startsWith("Duplicate parameter") }.severity)
        val dup = errors.single { it.message.startsWith("Macro 'a'") }
        assertEquals(HighlightSeverity.WARNING, dup.severity)
        assertEquals(text.lastIndexOf("a()"), dup.range.startOffset)
    }

    fun testUnclosedCallDoesNotReportMissingArguments() {
        val text = """{% import "macros" as m %}{{ m::input(label="l" """
        assertTrue(check(text).none { it.message.contains("missing required") })
    }

    // ── resolution through the virtual file system ───────────────────────────

    fun testAnnotatorResolvesRocketStyleImportFromDisk() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        val index = myFixture.addFileToProject("templates/index.html.tera", indexText)
        val input = TeraExternalAnnotator().collectInformation(index)!!
        assertNotNull("'macros' must resolve to macros.html.tera", input.importedMacros["macros"])
        assertEmpty(TeraExternalAnnotator().doAnnotate(input))
    }

    fun testAnnotatorReportsBrokenCallAgainstDiskTemplate() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        val broken = """{% import "macros" as m %}{{ m::checkbox(name="n") }}"""
        val file = myFixture.addFileToProject("templates/page.html.tera", broken)
        val errors = TeraExternalAnnotator().doAnnotate(TeraExternalAnnotator().collectInformation(file)!!)
        assertEquals("Macro 'checkbox' is missing required argument(s): label", errors.single().message)
    }

    fun testUnresolvableImportOnDisk() {
        val file = myFixture.addFileToProject("templates/page.html.tera", """{% import "ghost" as g %}{{ g::x() }}""")
        val errors = TeraExternalAnnotator().doAnnotate(TeraExternalAnnotator().collectInformation(file)!!)
        assertEquals(listOf("Cannot resolve template 'ghost'"), errors.map { it.message })
    }

    fun testTemplateNamesAreRelativeToTemplatesFolderWithoutExtensions() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        myFixture.addFileToProject("templates/tera/error/404.html.tera", "<p></p>")
        val page = myFixture.addFileToProject("templates/tera/page.html.tera", "")
        assertEquals(listOf("macros", "tera/error/404", "tera/page"),
            TeraTemplateResolver.templateNames(page.virtualFile))
    }

    // ── completion engine ────────────────────────────────────────────────────

    private fun suggest(textWithCaret: String): List<TeraSuggestion> {
        val caret = textWithCaret.indexOf("<caret>")
        val text = textWithCaret.replace("<caret>", "")
        return TeraCompletionEngine.suggest(text, caret, { if (it == "macros") rocketMacros else null }, { listOf("macros", "base") })
    }

    fun testCompletesMacroNamesAfterNamespace() {
        val s = suggest("""{% import "macros" as m %}{{ m::<caret> }}""")
        assertEquals(listOf("value_for", "errors_for", "input", "checkbox", "textarea", "select"), s.map { it.text })
        val input = s.first { it.text == "input" }
        assertEquals("(type, label, name, value=…)", input.tail)
        assertEquals("(type=, label=, name=)", input.suffix)
        assertEquals(1 + "type=".length, input.caretInSuffix)
    }

    fun testCompletesMacroNamesWithPrefixTyped() {
        val s = suggest("""{% import "macros" as m %}{{ m::che<caret> }}""")
        assertTrue(s.any { it.text == "checkbox" })
    }

    fun testCompletesSelfMacros() {
        val s = suggest("{% macro a(x) %}{% endmacro %}{% macro b() %}{% endmacro %}{{ self::<caret> }}")
        assertEquals(listOf("a", "b"), s.map { it.text })
        assertEquals("()", s.first { it.text == "b" }.suffix)
    }

    fun testUnknownNamespaceHasNoMacroSuggestions() {
        assertEmpty(suggest("{{ nope::<caret> }}"))
    }

    fun testCompletesNamespaces() {
        val s = suggest("""{% import "macros" as m %}{% macro a() %}{% endmacro %}{{ <caret> }}""")
        val namespaces = s.filter { it.suffix == "::" }
        assertEquals(setOf("self", "m"), namespaces.map { it.text }.toSet())
    }

    fun testNoNamespaceSuggestionsAfterDotOrFilterPipe() {
        assertEmpty(suggest("""{% import "macros" as m %}{{ user.<caret> }}"""))
        val filters = suggest("""{% import "macros" as m %}{{ user | <caret> }}""")
        assertEquals(TeraBuiltins.FILTERS.keys.toList(), filters.map { it.text })
    }

    fun testCompletesRemainingParameterNames() {
        val s = suggest("""{% import "macros" as m %}{{ m::input(label="l", <caret>) }}""")
        assertEquals(listOf("type", "name", "value"), s.map { it.text })
        assertEquals(listOf("required", "required", "optional"), s.map { it.tail })
        assertTrue(s.all { it.suffix == "=" })
    }

    fun testParameterCompletionWorksInUnclosedCallAndWithPrefix() {
        val s = suggest("""{% import "macros" as m %}{{ m::input(na<caret>""")
        assertEquals(listOf("type", "label", "name", "value"), s.map { it.text })
    }

    fun testNoParameterSuggestionsInsideNestedExpression() {
        val s = suggest("""{% import "macros" as m %}{{ m::input(label=foo(<caret>) }}""")
        assertTrue(s.none { it.suffix == "=" })
    }

    fun testCompletesTagKeywordsAtTagStart() {
        val s = suggest("{% <caret> %}").map { it.text }
        assertTrue(s.containsAll(listOf("for", "if", "macro", "endmacro", "import")))
        assertTrue(suggest("{%- en<caret> -%}").map { it.text }.contains("endblock"))
    }

    fun testCompletesTemplateNamesInImportExtendsInclude() {
        for (tag in listOf("import", "extends", "include")) {
            assertEquals(listOf("macros", "base"), suggest("""{% $tag "<caret>" %}""").map { it.text })
        }
        assertEquals(listOf("macros", "base"), suggest("""{% import "ma<caret>" as m %}""").map { it.text })
        assertEmpty(suggest("""{{ "<caret>" }}"""))
    }

    fun testNothingSuggestedOutsideTemplateTags() {
        assertEmpty(suggest("<div><caret></div>"))
        assertEmpty(suggest("{# note <caret> #}"))
    }

    // ── completion through the real editor pipeline ──────────────────────────

    fun testEditorCompletionOfImportedMacro() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        myFixture.configureFromExistingVirtualFile(
            myFixture.addFileToProject("templates/page.html.tera",
                """{% import "macros" as m %}{{ m::checkb }}""").virtualFile,
        )
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(" }}"))
        myFixture.completeBasic()
        // single match is inserted directly with its required arguments
        assertEquals("""{% import "macros" as m %}{{ m::checkbox(name=, label=) }}""", myFixture.editor.document.text)
        assertEquals(myFixture.editor.document.text.indexOf("name=") + "name=".length, myFixture.caretOffset)
    }

    fun testEditorCompletionOfNamespaceThenMacros() {
        myFixture.addFileToProject("templates/macros.html.tera", macrosText)
        myFixture.configureFromExistingVirtualFile(
            myFixture.addFileToProject("templates/page2.html.tera",
                """{% import "macros" as m %}{{  }}""").virtualFile,
        )
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("{{ ") + 3)
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings
        // either a lookup is shown, or the single namespace was inserted directly
        assertTrue(strings?.contains("m") == true || myFixture.editor.document.text.contains("m::"))
    }
}
