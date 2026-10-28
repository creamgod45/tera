package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Finding where a component is used, from its definition: the reverse of go-to-definition. */
class TeraUsagesTest : BasePlatformTestCase() {

    private val components = """
        {% component ui.card(title: string = "") %}<section>{{ body }}</section>{% endcomponent %}
        {% component ui.badge(status: string) %}<b>{{ status }}</b>{% endcomponent %}
        {% component ui.unused() %}x{% endcomponent %}
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = "2"
        myFixture.addFileToProject("templates/components.html.tera", components)
        myFixture.addFileToProject("templates/a.html.tera", "{% <ui.card title=\"A\"> %}{{ <ui.badge status=\"ok\"/> }}{% </ui.card> %}")
        myFixture.addFileToProject("templates/b.html.tera", "{{ <ui.badge status=\"no\"/> }}\n{{ <ui.badge status=\"x\"/> }}")
    }

    fun testUsagesAreFoundInEveryTemplateIncludingBodyCalls() {
        val badge = TeraUsages.ofComponent(project, "ui.badge")
        assertEquals(listOf("a.html.tera", "b.html.tera", "b.html.tera"), badge.map { it.file.name })
        assertEquals(listOf("a.html.tera"), TeraUsages.ofComponent(project, "ui.card").map { it.file.name })
        assertEmpty(TeraUsages.ofComponent(project, "ui.unused"))
    }

    fun testClosingTagsAreNotUsages() {
        assertEquals(1, TeraUsages.ofComponent(project, "ui.card").size)
    }

    fun testGoToDeclarationOnADefinitionListsItsUsages() {
        val file = myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("templates/components.html.tera"))
        val offset = components.indexOf("ui.badge") + 1
        val defs = TeraNavigation.definitions(components, offset, usagesOf = { name ->
            TeraUsages.ofComponent(project, name).map { TeraDefinition(it.file, it.range.startOffset) }
        }) { emptyList() }
        assertEquals(3, defs.size)
        assertNotNull(file)
    }

    fun testTheGutterOfADefinitionCountsUsagesAndAUnusedOneHasNone() {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("templates/components.html.tera"))
        val tips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertTrue(tips.toString(), "1 usage of ui.card" in tips)
        assertTrue(tips.toString(), "3 usages of ui.badge" in tips)
        assertTrue(tips.toString(), tips.none { "ui.unused" in it })
    }

    fun testTheGutterOfACallJumpsToTheDefinition() {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("templates/b.html.tera"))
        val tips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertEquals(listOf("Component ui.badge", "Component ui.badge"), tips)
    }
}

/** The lists show the line the place is on and `file:line`, so a usage can be recognised without opening it. */
class TeraUsageRendererTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = "2"
    }

    fun testALineAndItsLocation() {
        myFixture.addFileToProject("templates/components.html.tera", "{% component ui.card(title: string = \"\") %}x{% endcomponent %}")
        myFixture.addFileToProject("templates/a.html.tera", "<div>\n    {% <ui.card   title=\"A\"> %}\n</div>")
        val usage = TeraUsages.ofComponent(project, "ui.card").single()
        val element = TeraUsages.elementAt(project, usage.file, usage.range.startOffset)!!
        val (text, place) = TeraUsageRenderer.describe(element)
        assertEquals("{% <ui.card title=\"A\"> %}", text)
        assertEquals("a.html.tera:2", place)
        assertEquals(text, TeraUsageRenderer().getElementText(element))
    }

    fun testEveryLeafOfATemplateGetsTheRenderer() {
        myFixture.addFileToProject("templates/components.html.tera", "{% component form.select(name: string) %}x{% endcomponent %}")
        myFixture.addFileToProject("templates/a.html.tera", "{{ <form.select name=\"a\"/> }}")
        val usage = TeraUsages.ofComponent(project, "form.select").single()
        val element = TeraUsages.elementAt(project, usage.file, usage.range.startOffset)!!
        assertTrue(TeraUsageRenderer.isTemplatePlace(element))
    }

    fun testTwoCallsOnALineGiveOneIconWithBothDefinitions() {
        myFixture.addFileToProject("templates/components.html.tera",
            "{% component ui.badge(status: string) %}x{% endcomponent %}\n{% component ui.button(label: string) %}y{% endcomponent %}")
        myFixture.configureByText("a.html.tera", "{{ <ui.badge status=\"a\"/> }}{{ <ui.button label=\"b\"/> }}")
        val gutters = myFixture.findAllGutters().filter { it.tooltipText?.startsWith("Components") == true }
        assertEquals(1, gutters.size)
        assertEquals("Components ui.badge, ui.button", gutters.single().tooltipText)
    }

    fun testLongLinesAreCut() {
        val long = "{% <ui.card title=\"" + "x".repeat(300) + "\"> %}"
        myFixture.addFileToProject("templates/b.html.tera", long)
        val file = myFixture.findFileInTempDir("templates/b.html.tera")
        val element = TeraUsages.elementAt(project, file, 5)!!
        assertTrue(TeraUsageRenderer.describe(element).first.length <= 110)
    }
}
