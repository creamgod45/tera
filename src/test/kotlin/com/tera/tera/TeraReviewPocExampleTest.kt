package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * `examples/review-poc` is a Rocket + Tera 2 request/review/statistics app with a four-level layout and a component
 * library; its own behaviour is checked by `cargo test`. These tests check what the plugin says about its templates.
 */
class TeraReviewPocExampleTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
    }

    private val dir = File("examples/review-poc/templates")
    private fun templates(): Map<String, String> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".tera") }
            .associate { it.relativeTo(dir).path to it.readText() }

    private fun components() = TeraComponentScanner.definitions(templates().getValue("components.html.tera")).map { TeraComponent(it, null) }

    fun testTheExampleIsThere() {
        assertTrue("run the tests from the repository root", dir.isDirectory)
        assertTrue(templates().size == 11)
    }

    fun testItIsATera2ProjectToThePlugin() {
        myFixture.addFileToProject("review-poc/Cargo.toml", File("examples/review-poc/Cargo.toml").readText())
        val template = myFixture.addFileToProject("review-poc/templates/base.html.tera", "")
        assertEquals(TeraVersion.V2, TeraVersions.forFile(project, template.virtualFile).version)
    }

    fun testNoTemplateHasAnErrorOrWarning() {
        TeraSettings.getInstance(project).state.version = "2"
        val components = components()
        for ((n, text) in templates()) {
            val found = (TeraMigration.check(text, TeraVersion.V2) + TeraComponentChecker.check(text, components) +
                TeraTypeChecker.check(text, null, components) + TeraExternalAnnotator().doAnnotate(text))
                .filter { it.severity == HighlightSeverity.ERROR || it.severity == HighlightSeverity.WARNING }
            assertEmpty(found.map { "$n: ${it.message}" })
        }
    }

    fun testTheComponentLibraryIsDefinedOnceAndUsedEverywhere() {
        val defs = components().map { it.def.name }
        for (name in listOf("ui.nav_item", "ui.card", "ui.badge", "ui.stat", "ui.button", "ui.bar", "ui.empty",
            "form.field", "form.select", "form.textarea", "form.checkbox")) assertTrue("$name defined", name in defs)
        val used = templates().filterKeys { it != "components.html.tera" }.values
            .flatMap { TeraComponentScanner.calls(TeraSymbolScanner.scanBlocks(it)) }.map { it.name }.toSet()
        for (name in defs) assertTrue("$name is used", name in used)
    }

    fun testPagesExtendTheLayoutChain() {
        val t = templates()
        assertTrue(t.getValue("layouts/app.html.tera").contains("{% extends \"base.html\" %}"))
        assertTrue(t.getValue("layouts/section.html.tera").contains("{% extends \"layouts/app.html\" %}"))
        for (page in listOf("dashboard", "requests", "new", "detail", "stats"))
            assertTrue(page, t.getValue("pages/$page.html.tera").contains("{% extends \"layouts/section.html\" %}"))
    }

    fun testTheTypeCheckerCatchesMistakesInThisAppsComponents() {
        TeraSettings.getInstance(project).state.version = "2"
        val components = components()
        fun problems(call: String) = TeraComponentChecker.check("{{ $call }}", components).map { it.message }
        assertTrue(problems("<ui.stat/>").any { it.contains("label") })
        assertTrue(problems("<ui.stat label=\"x\" value=\"1\" colour=\"red\"/>").isNotEmpty() || components.first { it.def.name == "ui.stat" }.def.hasRest)
        assertTrue(problems("<ui.nope/>").isNotEmpty())
    }
}
