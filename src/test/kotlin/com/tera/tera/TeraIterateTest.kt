package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `{{ categories }}` prints a JSON-ish array; the plugin offers the loop, the loop with a fallback, or a join instead. */
class TeraIterateTest : BasePlatformTestCase() {

    private fun scalar(n: String) = TeraType.Scalar(n)

    private val context = TeraContextResult(listOf(
        TeraField("categories", TeraType.ArrayOf(scalar("string")), "[&str; 5]"),
        TeraField("requests", TeraType.ArrayOf(TeraType.Struct("Request", listOf(
            TeraField("id", scalar("number")), TeraField("applicant", scalar("string")))))),
        TeraField("counts", TeraType.MapOf(scalar("number")), "HashMap<String, u32>"),
        TeraField("title", scalar("string")),
    ), complete = true)

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = "2"
        val provider = object : TeraContextProvider {
            override fun resolve(project: com.intellij.openapi.project.Project, template: com.intellij.openapi.vfs.VirtualFile) = context
        }
        TeraContextProvider.EP.point.registerExtension(provider, testRootDisposable)
    }

    fun testItemNamesAreSingular() {
        assertEquals("category", TeraIterate.itemName("categories"))
        assertEquals("request", TeraIterate.itemName("requests"))
        assertEquals("box", TeraIterate.itemName("boxes"))
        assertEquals("entry", TeraIterate.itemName("page.entries"))
        assertEquals("item", TeraIterate.itemName("data"))
        assertEquals("item", TeraIterate.itemName("class"))
        assertEquals("item_item", TeraIterate.itemName("item"))
    }

    fun testSnippetsDependOnTheType() {
        val list = TeraIterate.snippets("categories", TeraType.ArrayOf(scalar("string")))
        assertEquals(listOf(TeraSnippetKind.FOR, TeraSnippetKind.FOR_ELSE, TeraSnippetKind.JOIN), list.map { it.kind })
        assertEquals("{% for category in categories %}{{ category }}{% endfor %}", list[0].text)
        assertEquals("{{ categories | join(sep=\", \") }}", list[2].text)
        // Records show a field of the element; they cannot be joined.
        val records = TeraIterate.snippets("requests", context.variables[1].type)
        assertEquals(listOf(TeraSnippetKind.FOR, TeraSnippetKind.FOR_ELSE), records.map { it.kind })
        assertTrue(records[0].text, records[0].text.contains("{{ request.applicant }}"))
        assertEquals("{% for key, value in counts %}{{ key }}: {{ value }}{% endfor %}", TeraIterate.snippets("counts", context.variables[2].type).single().text)
        assertEmpty(TeraIterate.snippets("title", scalar("string")))
    }

    fun testTheCaretLandsInsideTheLoopBody() {
        val s = TeraIterate.snippets("categories", TeraType.ArrayOf(scalar("string"))).first()
        assertEquals("{% for category in categories %}{{ category", s.text.substring(0, s.caret))
    }

    // ── Alt+Enter ───────────────────────────────────────────────────────────

    private fun applyIntention(text: String, name: String): String? {
        myFixture.configureByText("t.html.tera", text)
        val action = myFixture.availableIntentions.firstOrNull { it.text.startsWith("Tera: $name") } ?: return null
        myFixture.launchAction(action)
        return myFixture.editor.document.text
    }

    fun testTheLoopReplacesThePrintedList() {
        assertEquals("<ul>{% for category in categories %}{{ category }}{% endfor %}</ul>",
            applyIntention("<ul>{{ cate<caret>gories }}</ul>", "for category in categories"))
    }

    fun testTheFallbackVariantAndTheJoin() {
        assertEquals("{% for category in categories %}{{ category }}{% else %}Nothing to show.{% endfor %}",
            applyIntention("{{ categories<caret> }}", "for category in categories … else"))
        assertEquals("{{ categories | join(sep=\", \") }}", applyIntention("{{ <caret>categories }}", "categories | join"))
    }

    fun testNothingIsOfferedForScalarsOrExpressionsThatAreNotJustAVariable() {
        assertNull(applyIntention("{{ ti<caret>tle }}", "for"))
        assertNull(applyIntention("{{ categories<caret> | length }}", "for"))
        assertNull(applyIntention("{{ categories<caret> | join(sep=\"-\") }}", "for"))
        assertNull(applyIntention("{{ <caret>unknown }}", "for"))
    }

    // ── completion ──────────────────────────────────────────────────────────

    fun testCompletionOffersTheLoopNextToTheVariable() {
        myFixture.configureByText("t.html.tera", "<p>{{ cate<caret> }}</p>")
        myFixture.completeBasic()
        val items = myFixture.lookupElements!!.map { it.lookupString }
        assertTrue(items.toString(), items.count { it == "categories" } >= 2)
    }

    fun testChoosingTheLoopReplacesTheWholeExpression() {
        myFixture.configureByText("t.html.tera", "<p>{{ cate<caret> }}</p>")
        myFixture.completeBasic()
        val loop = myFixture.lookupElements!!.first { com.intellij.codeInsight.lookup.LookupElementPresentation().also { p -> it.renderElement(p) }.itemText == "for category in categories" }
        myFixture.lookup.currentItem = loop
        myFixture.finishLookup('\n')
        assertEquals("<p>{% for category in categories %}{{ category }}{% endfor %}</p>", myFixture.editor.document.text)
    }

    fun testNoLoopIsOfferedInsideAFilterChainOrForAScalar() {
        myFixture.configureByText("t.html.tera", "{{ title<caret> }}")
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElements.isNullOrEmpty() || myFixture.lookupElements!!.none { it.lookupString == "title" && it.`object` is TeraSnippet })
    }
}
