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
        assertNull(applyIntention("{{ categories<caret> | first | upper }}", "for"))
        assertNull(applyIntention("{{ categories<caret> ~ \"x\" }}", "for"))
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

    // ── filter chains ───────────────────────────────────────────────────────

    fun testALoopOverAFilteredList() {
        assertEquals("{% for category in categories | sort %}{{ category }}{% endfor %}",
            applyIntention("{{ categories<caret> | sort }}", "for category in categories | sort"))
        assertEquals("{% for category in categories | sort | reverse %}{{ category }}{% endfor %}",
            applyIntention("{{ categories | sort | reverse<caret> }}", "for category in"))
    }

    fun testMapsGiveListsThroughKeysAndValues() {
        assertEquals("{% for value in counts | values %}{{ value }}{% endfor %}",
            applyIntention("{{ counts | values<caret> }}", "for value in counts | values"))
        assertEquals("{% for key in counts | keys %}{{ key }}{% endfor %}",
            applyIntention("{{ counts | keys<caret> }}", "for key in counts | keys"))
    }

    fun testAnUnfinishedFilterIsIgnored() {
        // The case from the editor: the chain ends in a `|` that has not been completed yet.
        assertEquals("{% for value in counts | values %}{{ value }}{% endfor %}",
            applyIntention("{{ counts | values |<caret> }}", "for value in counts | values"))
    }

    fun testAFilterThatMakesAPlainValueOffersNothing() {
        assertNull(applyIntention("{{ categories | join(sep=\", \")<caret> }}", "for"))
        // `values` needs a map: on a list the chain is not understood, so no loop is guessed.
        assertNull(applyIntention("{{ categories | values<caret> }}", "for"))
    }

    fun testFilterTypes() {
        val list = TeraType.ArrayOf(scalar("string"))
        assertTrue(TeraIterate.afterFilter("sort", list) is TeraType.ArrayOf)
        assertTrue(TeraIterate.afterFilter("values", TeraType.MapOf(scalar("number"))) is TeraType.ArrayOf)
        assertEquals(TeraType.Unknown, TeraIterate.afterFilter("length", list))
        assertEquals(TeraType.Unknown, TeraIterate.afterFilter("nonsense", list))
        assertTrue(TeraIterate.afterFilter("group_by", list) is TeraType.MapOf)
    }
}

/** What a filter the project registers produces decides whether a loop is offered after it. */
class TeraCustomFilterIterateTest : BasePlatformTestCase() {

    private fun filter(name: String, returns: TeraType?) =
        TeraCustomBuiltin("filter", BuiltinDef(name, "custom"), null, returns)

    fun testTheDeclaredTypeSyntax() {
        assertEquals(TeraType.ArrayOf(TeraType.Scalar("string")), TeraTypeSyntax.parse("array<string>"))
        assertEquals(TeraType.ArrayOf(TeraType.Scalar("number")), TeraTypeSyntax.parse("number[]"))
        assertEquals(TeraType.MapOf(TeraType.ArrayOf(TeraType.Scalar("string"))), TeraTypeSyntax.parse("map<array<string>>"))
        assertEquals(TeraType.Scalar("bool"), TeraTypeSyntax.parse("Boolean."))
        assertEquals(TeraType.Unknown, TeraTypeSyntax.parse("any"))
        assertNull(TeraTypeSyntax.parse("Vec<Foo>"))
        assertEquals(TeraType.ArrayOf(TeraType.Scalar("string")), TeraTypeSyntax.fromDoc("Splits tags.\n@returns array<string>\nmore"))
        assertNull(TeraTypeSyntax.fromDoc("nothing declared"))
    }

    fun testAProjectFilterDecidesTheTypeAfterIt() {
        val custom = listOf(filter("tags", TeraType.ArrayOf(TeraType.Scalar("string"))), filter("shout", TeraType.Scalar("string")), filter("mystery", null))
        val text = TeraType.Scalar("string")
        assertEquals(TeraType.ArrayOf(TeraType.Scalar("string")), TeraIterate.afterFilter("tags", text, custom))
        assertEquals(TeraType.Scalar("string"), TeraIterate.afterFilter("shout", text, custom))
        assertEquals(TeraType.Unknown, TeraIterate.afterFilter("mystery", text, custom))
        // Without the registration the name is just an unknown filter.
        assertEquals(TeraType.Unknown, TeraIterate.afterFilter("tags", text))
        // The project's own filter replaces Tera's of the same name.
        assertEquals(TeraType.Unknown, TeraIterate.afterFilter("sort", TeraType.ArrayOf(text), listOf(filter("sort", null))))
    }

    fun testTheIntentionFollowsAProjectFilter() {
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = "2"
        val provider = object : TeraContextProvider {
            override fun resolve(project: com.intellij.openapi.project.Project, template: com.intellij.openapi.vfs.VirtualFile) =
                TeraContextResult(listOf(TeraField("post", TeraType.Struct("Post", listOf(TeraField("tags", TeraType.Scalar("string")))))), true)
            override fun customBuiltins(project: com.intellij.openapi.project.Project) =
                listOf(filter("split_tags", TeraType.ArrayOf(TeraType.Scalar("string"))))
        }
        TeraContextProvider.EP.point.registerExtension(provider, testRootDisposable)
        myFixture.configureByText("t.html.tera", "{{ post.tags | split_tags<caret> }}")
        val action = myFixture.availableIntentions.first { it.text.startsWith("Tera: for tag in post.tags | split_tags") }
        myFixture.launchAction(action)
        assertEquals("{% for tag in post.tags | split_tags %}{{ tag }}{% endfor %}", myFixture.editor.document.text)
    }
}
