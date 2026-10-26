package com.tera.tera

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** What the editor offers and says for a Tera 2.x project. */
class TeraV2CompletionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).state.version = "2"
    }

    private fun suggest(textWithCaret: String, version: TeraVersion = TeraVersion.V2): List<TeraSuggestion> {
        val caret = textWithCaret.indexOf("<caret>")
        return TeraCompletionEngine.suggest(textWithCaret.replace("<caret>", ""), caret, { null }, { emptyList() },
            null, emptyList(), version)
    }

    private fun names(src: String, version: TeraVersion = TeraVersion.V2) = suggest(src, version).map { it.text }

    // ── what exists ──────────────────────────────────────────────────────────

    fun testFiltersAreTheTera2Ones() {
        val n = names("{{ x | <caret> }}")
        assertEquals(TeraBuiltins.FILTER_DEFS_V2.map { it.name }, n)
        assertTrue(n.containsAll(listOf("keys", "values", "pairs", "str", "newlines_to_br", "escape_html", "trim_start")))
        for (gone in listOf("slice", "map", "filter", "concat", "date", "urlencode", "slugify", "json_encode",
            "as_str", "linebreaksbr", "trim_start_matches", "addslashes", "spaceless", "striptags", "filesizeformat")) {
            assertFalse("$gone does not exist in Tera 2", gone in n)
        }
    }

    fun testTestsAreTheTera2Ones() {
        val n = names("{% if x is <caret> %}{% endif %}")
        assertEquals(TeraBuiltins.TEST_DEFS_V2.map { it.name }, n)
        assertTrue(n.containsAll(listOf("divisible_by", "none", "integer", "float", "bool", "array", "map")))
        for (gone in listOf("divisibleby", "object", "matching")) assertFalse(gone, gone in n)
    }

    fun testOnlyRangeAndThrowAreCoreFunctions() {
        val fn = suggest("{{ <caret> }}").filter { it.type == "function" }.map { it.text }
        assertEquals(listOf("range", "throw"), fn)
        for (gone in listOf("now", "get_env", "get_random")) assertFalse(gone in fn)
    }

    fun testTagKeywordsHaveComponentsAndNoMacros() {
        val n = names("{% <caret> %}")
        assertTrue(n.containsAll(listOf("component", "endcomponent", "endset", "set", "set_global", "include", "extends")))
        for (gone in listOf("macro", "endmacro", "import")) assertFalse(gone, gone in n)
        assertTrue(names("{% <caret> %}", TeraVersion.V1).containsAll(listOf("macro", "endmacro", "import")))
        assertFalse("component" in names("{% <caret> %}", TeraVersion.V1))
    }

    fun testLoopHasLengthOnlyInTera2() {
        val src = "{% for x in [1, 2] %}{{ loop.<caret> }}{% endfor %}"
        assertEquals(listOf("index", "index0", "first", "last", "length"), names(src))
        assertEquals(listOf("index", "index0", "first", "last"), names(src, TeraVersion.V1))
    }

    // ── arguments ────────────────────────────────────────────────────────────

    fun testTestsTakeKeywordArguments() {
        assertEquals(listOf("divisor"), names("{% if i is divisible_by(<caret>) %}{% endif %}"))
        assertEquals(listOf("pat"), names("{% if s is starting_with(<caret>) %}{% endif %}"))
        val insert = suggest("{% if i is <caret> %}{% endif %}")
        assertEquals("(divisor=)", insert.first { it.text == "divisible_by" }.suffix)
        assertEquals("(pat=)", insert.first { it.text == "containing" }.suffix)
        assertEquals("", insert.first { it.text == "odd" }.suffix)
    }

    fun testFilterArgumentsOfTera2() {
        assertEquals(listOf("length", "end"), names("{{ t | truncate(<caret>) }}"))
        assertEquals("(length=)", suggest("{{ t | <caret> }}").first { it.text == "truncate" }.suffix)
        assertEquals(listOf("width", "indentation", "first", "blank"), names("{{ t | indent(<caret>) }}"))
        assertEquals(listOf("pat"), names("{{ t | trim(<caret>) }}"))
        assertEquals(listOf("base"), names("{{ t | int(<caret>) }}"))
        assertEquals(listOf("end", "start", "step_by"), names("{{ range(<caret>) }}"))
        assertEquals("(end=)", suggest("{{ <caret> }}").first { it.text == "range" }.suffix)
    }

    // ── hover ────────────────────────────────────────────────────────────────

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&amp;", "&").replace(Regex("\\s+"), " ").replace(" :", ":").trim()

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

    fun testHoverUsesTheTera2Signature() {
        val t = hover("a.html.tera", "{{ title | trunc<caret>ate(length=5) }}")!!
        assertTrue(t, t.contains("(filter) truncate(length, end=\"…\")"))
        assertTrue(t, t.contains("length – required – maximum number of characters"))
        val test = hover("b.html.tera", "{% if i is divisible<caret>_by(divisor=2) %}{% endif %}")!!
        assertTrue(test, test.contains("(test) divisible_by(divisor)"))
        assertTrue(test, test.contains("divisor – required – number to divide by"))
        assertFalse(test, test.contains("positional"))
    }

    fun testHoverKnowsNothingOfRemovedBuiltins() {
        assertNull(hover("c.html.tera", "{{ ts | da<caret>te(format=\"%Y\") }}"))
        assertNull(hover("d.html.tera", "{{ no<caret>w() }}"))
        assertNull(hover("e.html.tera", "{% if i is divisib<caret>leby(2) %}{% endif %}"))
    }

    fun testHoverNewTera2Filters() {
        val k = hover("f.html.tera", "{{ m | ke<caret>ys }}")!!
        assertTrue(k, k.contains("(filter) keys"))
        assertTrue(k, k.contains("Keys of a map."))
    }
}
