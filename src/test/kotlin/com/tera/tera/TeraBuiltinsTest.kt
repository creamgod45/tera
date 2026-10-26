package com.tera.tera

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Signatures, argument completion and hover documentation for Tera's built-in functions, filters and tests. */
class TeraBuiltinsTest : BasePlatformTestCase() {

    private fun suggest(textWithCaret: String): List<TeraSuggestion> {
        val caret = textWithCaret.indexOf("<caret>")
        return TeraCompletionEngine.suggest(textWithCaret.replace("<caret>", ""), caret, { null }, { emptyList() })
    }

    private fun names(src: String) = suggest(src).map { it.text }

    // ── signatures ───────────────────────────────────────────────────────────

    fun testSignaturesOfFunctions() {
        fun sig(n: String) = TeraBuiltins.FUNCTION_BY_NAME.getValue(n).signature()
        assertEquals("range(end, start=0, step_by=1)", sig("range"))
        assertEquals("now(utc=false, timestamp=false)", sig("now"))
        assertEquals("throw(message)", sig("throw"))
        assertEquals("get_random(start=0, end)", sig("get_random"))
        assertEquals("get_env(name, default=…)", sig("get_env"))
    }

    fun testSignaturesOfFiltersAndTests() {
        fun sig(n: String) = TeraBuiltins.FILTER_BY_NAME.getValue(n).signature()
        assertEquals("upper", sig("upper"))
        assertEquals("truncate(length=255, end=\"…\")", sig("truncate"))
        assertEquals("pluralize(singular=\"\", plural=\"s\")", sig("pluralize"))
        assertEquals("replace(from, to)", sig("replace"))
        assertEquals("default(value, boolean=false)", sig("default"))
        assertEquals("divisibleby(divisor)", TeraBuiltins.TEST_BY_NAME.getValue("divisibleby").signature())
        assertEquals("defined", TeraBuiltins.TEST_BY_NAME.getValue("defined").signature())
    }

    fun testEveryBuiltinHasADescriptionAndUniqueName() {
        for (defs in listOf(TeraBuiltins.FUNCTION_DEFS, TeraBuiltins.FILTER_DEFS, TeraBuiltins.TEST_DEFS)) {
            assertEquals(defs.size, defs.map { it.name }.toSet().size)
            assertTrue(defs.all { it.description.isNotBlank() })
            for (d in defs) for (p in d.params) assertTrue("${d.name}.${p.name}", p.doc.isNotBlank())
        }
    }

    // ── insertion after the name ─────────────────────────────────────────────

    fun testFunctionInsertsRequiredArguments() {
        val s = suggest("{{ <caret> }}")
        val range = s.first { it.text == "range" }
        assertEquals("(end=)", range.suffix)
        assertEquals("(end=".length, range.caretInSuffix)
        assertEquals("(name=)", s.first { it.text == "get_env" }.suffix)
        assertEquals("(message=)", s.first { it.text == "throw" }.suffix)
        assertEquals("(end=)", s.first { it.text == "get_random" }.suffix)
        assertEquals("()", s.first { it.text == "now" }.suffix)
        assertEquals(1, s.first { it.text == "now" }.caretInSuffix)
    }

    fun testFilterOnlyGetsParenthesesWhenArgumentsAreRequired() {
        val s = suggest("{{ x | <caret> }}")
        assertEquals("", s.first { it.text == "upper" }.suffix)
        assertEquals("", s.first { it.text == "truncate" }.suffix)       // all optional
        assertEquals("(from=, to=)", s.first { it.text == "replace" }.suffix)
        assertEquals("(value=)", s.first { it.text == "default" }.suffix)
        assertEquals("(sep=)".length.let { "" }, s.first { it.text == "join" }.suffix)
        assertEquals("(attribute=)", s.first { it.text == "map" }.suffix)
    }

    fun testTestsNeverGetKeywordArguments() {
        val s = suggest("{% if i is <caret> %}{% endif %}")
        assertTrue(s.all { it.suffix.isEmpty() })
    }

    // ── argument completion ──────────────────────────────────────────────────

    fun testFunctionArgumentNames() {
        val s = suggest("{{ range(<caret>) }}")
        assertEquals(listOf("end", "start", "step_by"), s.map { it.text })
        assertEquals(listOf("required", "default 0", "default 1"), s.map { it.tail })
        assertTrue(s.all { it.suffix == "=" && it.type == "param" })
    }

    fun testArgumentsAlreadyGivenAreNotOffered() {
        assertEquals(listOf("start", "step_by"), names("{{ range(end=3, <caret>) }}"))
        assertEquals(listOf("end", "step_by"), names("{{ range(start=1, <caret>) }}"))
        assertEquals(listOf("end"), names("{{ range(start=1, step_by=2, <caret>) }}"))
        assertEmpty(names("{{ range(start=1, step_by=2, end=5, <caret>) }}"))
    }

    fun testArgumentCompletionWithPrefixAndUnclosedCall() {
        assertTrue(names("{{ range(st<caret>) }}").containsAll(listOf("start", "step_by")))
        assertEquals(listOf("end", "start", "step_by"), names("{% for i in range(<caret>"))
    }

    fun testFilterArgumentNames() {
        assertEquals(listOf("length", "end"), names("{{ title | truncate(<caret>) }}"))
        assertEquals(listOf("from", "to"), names("{{ title | replace(<caret>) }}"))
        assertEquals(listOf("to"), names("{{ title | replace(from=\"a\", <caret>) }}"))
        assertEquals(listOf("sep"), names("{{ tags | join(<caret>) }}"))
        assertEquals(listOf("singular", "plural"), names("{{ n | pluralize(<caret>) }}"))
    }

    fun testNoArgumentNamesForUnknownCallsPositionalTestsOrNestedExpressions() {
        assertTrue(names("{{ nothing(<caret>) }}").none { it == "end" })
        assertTrue(names("{{ range(end=foo(<caret>)) }}").none { it == "start" })
        assertTrue(names("{{ range(end=<caret>) }}").contains("range"))      // value position: expressions
        assertEmpty(names("{% if i is divisibleby(<caret>) %}{% endif %}").filter { it == "divisor" })
    }

    fun testMacroCallsAreNotMistakenForBuiltins() {
        val text = """{% import "macros" as m %}{{ m::range(<caret>) }}"""
        val caret = text.indexOf("<caret>")
        val s = TeraCompletionEngine.suggest(text.replace("<caret>", ""), caret, { null }, { emptyList() })
        assertTrue(s.none { it.text == "end" })
    }

    // ── hover ────────────────────────────────────────────────────────────────

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").replace(Regex("\\s+"), " ").replace(" :", ":").trim()

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

    fun testHoverFunction() {
        val doc = hover("h1.html.tera", "{{ ra<caret>nge(end=3) }}")!!
        assertTrue(doc, doc.contains("(function) range(end, start=0, step_by=1)"))
        assertTrue(doc, doc.contains("Generate a number sequence."))
        assertTrue(doc, doc.contains("end – required – first integer that is not in the array"))
        assertTrue(doc, doc.contains("start – optional, default 0 – first integer of the array"))
        assertTrue(doc, doc.contains("step_by – optional, default 1 – step between two integers"))
        assertTrue(doc, doc.contains("Example: {% for i in range(start=1, end=4) %}{{ i }}{% endfor %}"))
        assertTrue(doc, doc.contains("Source: Tera built-in function"))
    }

    fun testHoverEveryFunction() {
        for (def in TeraBuiltins.FUNCTION_DEFS) {
            val doc = hover("f_${def.name}.html.tera", "{{ ${def.name.take(2)}<caret>${def.name.drop(2)}() }}")!!
            assertTrue(doc, doc.contains("(function) ${def.signature()}"))
        }
    }

    fun testHoverFilterInAPipeline() {
        val doc = hover("h2.html.tera", "{{ title | trunc<caret>ate(length=5) }}")!!
        assertTrue(doc, doc.contains("(filter) truncate(length=255, end=\"…\")"))
        assertTrue(doc, doc.contains("Shorten to a length."))
        assertTrue(doc, doc.contains("length – optional, default 255 – maximum number of characters"))
        assertTrue(doc, doc.contains("Source: Tera built-in filter"))
        val bare = hover("h3.html.tera", "{{ errors | length | plural<caret>ize }}")!!
        assertTrue(bare, bare.contains("(filter) pluralize(singular=\"\", plural=\"s\")"))
        assertTrue(bare, bare.contains("Arguments:"))
    }

    fun testHoverFilterWithoutArguments() {
        val doc = hover("h4.html.tera", "{{ name | up<caret>per }}")!!
        assertTrue(doc, doc.contains("(filter) upper"))
        assertTrue(doc, doc.contains("Arguments: none"))
    }

    fun testHoverTestAfterIs() {
        val doc = hover("h5.html.tera", "{% if i is divisible<caret>by(2) %}{% endif %}")!!
        assertTrue(doc, doc.contains("(test) divisibleby(divisor)"))
        assertTrue(doc, doc.contains("divisor – positional argument – number to divide by"))
        val negated = hover("h6.html.tera", "{% if x is not def<caret>ined %}{% endif %}")!!
        assertTrue(negated, negated.contains("(test) defined"))
    }

    fun testHoverFilterTagName() {
        val doc = hover("h7.html.tera", "{% filter up<caret>per %}x{% endfilter %}")!!
        assertTrue(doc, doc.contains("(filter) upper"))
    }

    fun testNoBuiltinDocumentationForLookalikes() {
        assertNull(hover("n1.html.tera", "{{ user.ra<caret>nge }}"))          // a field called range
        assertNull(hover("n2.html.tera", "{{ ra<caret>nge }}"))                // not a call
        assertNull(hover("n3.html.tera", "{{ m::ra<caret>nge(end=1) }}"))      // a macro
        assertNull(hover("n4.html.tera", "{{ x | no<caret>pe }}"))             // unknown filter
        assertNull(hover("n5.html.tera", "<p>ra<caret>nge()</p>"))             // plain HTML
    }

    fun testQuickNavigateInfoForBuiltins() {
        val file = myFixture.addFileToProject("templates/q.html.tera", "{{ range(end=1) | length }}{% if a is odd %}{% endif %}")
        val psi = file.viewProvider.getPsi(TeraLanguage)!!
        val p = TeraDocumentationProvider()
        fun info(marker: String) = psi.findElementAt(file.text.indexOf(marker)).let { p.getQuickNavigateInfo(it, it) }
        assertEquals("(function) range(end, start=0, step_by=1)", info("range"))
        assertEquals("(filter) length", info("length"))
        assertEquals("(test) odd", info("odd"))
    }
}
