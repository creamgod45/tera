package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Rocket's real `examples/forms` templates, migrated from Tera 1 to Tera 2. The migrated copies in
 * `src/test/resources/rocket-tera2` are golden files that were also rendered with the real Tera 1.20.1 and 2.4.0
 * engines (see `tools/verify-migration`).
 */
class TeraMigrationRocketTest : BasePlatformTestCase() {

    private val names = listOf("macros", "index", "success")
    private fun original(n: String) = javaClass.getResource("/rocket/examples/forms/templates/$n.html.tera")!!.readText()
    private fun golden(n: String) = javaClass.getResource("/rocket-tera2/forms/templates/$n.html.tera")!!.readText()

    private fun useVersion(v: String) { TeraSettings.getInstance(project).state.version = v }

    private fun errors(text: String, components: List<TeraComponent>) =
        (TeraMigration.check(text, TeraVersion.V2) + TeraComponentChecker.check(text, components) +
            TeraExternalAnnotator().doAnnotate(text)).filter { it.severity == HighlightSeverity.ERROR }

    // ── the migration ────────────────────────────────────────────────────────

    fun testMigrationProducesExactlyTheGoldenFiles() {
        for (n in names) {
            val r = TeraMigration.migrate(original(n))
            assertEquals(n, golden(n), r.text)
            assertTrue("$n: ${r.remaining.map { it.message }}", r.remaining.isEmpty())
        }
    }

    fun testSuccessNeedsNoChange() {
        val r = TeraMigration.migrate(original("success"))
        assertEquals(0, r.applied)
        assertEquals(original("success"), r.text)
        assertEquals(original("success"), golden("success"))
    }

    fun testMigrationIsStable() {
        for (n in names) assertEquals(n, golden(n), TeraMigration.migrate(golden(n)).text)
    }

    fun testEverythingTera1SpecificIsGone() {
        val all = names.joinToString("\n") { golden(it) }
        for (gone in listOf("{% macro", "{% endmacro", "{% import", "m::", "self::", "divisibleby")) {
            assertFalse("'$gone' is still in the migrated templates", all.contains(gone))
        }
        assertEquals(6, Regex("""\{% component """).findAll(golden("macros")).count())
        assertEquals(6, Regex("""\{% endcomponent """).findAll(golden("macros")).count())
    }

    // ── the components the macros became ─────────────────────────────────────

    private val components get() = TeraComponentScanner.definitions(golden("macros")).map { TeraComponent(it, null) }

    fun testMacrosBecameComponentsWithImplicitParameters() {
        val byName = components.associate { it.def.name to it.def.signature() }
        assertEquals(mapOf(
            "value_for" to "value_for(name, @values)",
            "errors_for" to "errors_for(name, @errors)",
            "input" to "input(type, label, name, value = \"\", @errors)",
            "checkbox" to "checkbox(name, label, value = \"yes\", @errors, @values)",
            "textarea" to "textarea(label, name, placeholder = \"\", max = 250, @errors)",
            "select" to "select(label, name, options)",
        ), byName)
    }

    fun testComponentsCalledFromOtherComponentsDoNotRepeatTheirImplicitParameters() {
        // `input` calls `value_for` and `errors_for`: they read `values` / `errors` from the caller's scope chain themselves,
        // which the real engines confirm (tools/verify-migration), so `input` declares only the `@errors` it reads directly.
        val input = components.first { it.def.name == "input" }.def
        assertEquals(listOf("errors"), input.params.filter { it.implicit }.map { it.name })
        val select = components.first { it.def.name == "select" }.def
        assertTrue(select.params.none { it.implicit })
    }

    fun testEveryCallInTheMigratedIndexMatchesItsComponent() {
        val calls = TeraComponentScanner.calls(TeraSymbolScanner.scanBlocks(golden("index")))
        assertTrue(calls.size >= 12)
        assertEquals(setOf("input", "checkbox", "select", "textarea"), calls.map { it.name }.toSet())
        assertEmpty(TeraComponentChecker.callErrors(golden("index"), components))
    }

    // ── what is reported ─────────────────────────────────────────────────────

    fun testOriginalsAreCleanTera1AndOnlyHintedForTera2() {
        for (n in names) {
            val v1 = TeraMigration.check(original(n), TeraVersion.V1, emptyList(), hints = true)
            assertTrue("$n: ${v1.filter { it.severity == HighlightSeverity.ERROR }.map { it.message }}", v1.none { it.severity == HighlightSeverity.ERROR })
            assertEmpty(TeraMigration.check(original(n), TeraVersion.V1, emptyList(), hints = false))
        }
    }

    fun testMigratedFilesHaveNoTera2Errors() {
        for (n in names) assertEmpty(errors(golden(n), components).map { "$n: ${it.message}" })
    }

    fun testTheOriginalsAreFullOfTera2Errors() {
        assertTrue(errors(original("macros"), emptyList()).size >= 12)
        assertTrue(errors(original("index"), emptyList()).size >= 12)
        assertEmpty(errors(original("success"), emptyList()))
    }

    // ── in the editor ────────────────────────────────────────────────────────

    private fun project(from: (String) -> String) = names.map { myFixture.addFileToProject("templates/$it.html.tera", from(it)) }

    fun testProjectWideComponentsMakeTheMigratedIndexClean() {
        useVersion("2")
        project(::golden)
        val index = myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("templates/index.html.tera"))
        val problems = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertEmpty(problems.map { it.description })
        assertNotNull(index)
    }

    fun testWithoutTheComponentsDefinedTheCallsAreUnknown() {
        useVersion("2")
        myFixture.addFileToProject("templates/index.html.tera", golden("index"))
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("templates/index.html.tera"))
        val problems = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description }
        assertTrue(problems.toString(), problems.any { it == "Unknown component 'input'" })
    }

    fun testMigrateIntentionRewritesTheOpenTemplate() {
        useVersion("2")
        myFixture.configureByText("macros.html.tera", original("macros"))
        val intention = myFixture.getAvailableIntention("Migrate this template to Tera 2")!!
        myFixture.launchAction(intention)
        assertEquals(golden("macros"), myFixture.editor.document.text)
        assertNull("nothing left to migrate", myFixture.getAvailableIntention("Migrate this template to Tera 2"))
    }

    fun testMigrateIntentionIsNotOfferedForTera2Templates() {
        myFixture.configureByText("success.html.tera", golden("success"))
        assertNull(myFixture.getAvailableIntention("Migrate this template to Tera 2"))
    }

    fun testMigratingTheWholeProject() {
        val files = project(::original)
        val plan = TeraMigrationRunner.plan(project, TeraMigrationRunner.templates(project))
        assertEquals(setOf("index.html.tera", "macros.html.tera"), plan.filter { it.changed }.map { it.file.name }.toSet())
        val report = TeraMigrationRunner.apply(project, plan)
        assertEquals(2, report.changed.size)
        assertEmpty(report.remaining)
        for ((n, f) in names.zip(files)) {
            assertEquals(n, golden(n), com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(f.virtualFile)!!.text)
        }
        // a second run has nothing to do
        assertEmpty(TeraMigrationRunner.plan(project, TeraMigrationRunner.templates(project)).filter { it.changed })
    }

    fun testProjectMigrationReportsWhatNeedsAHuman() {
        myFixture.addFileToProject("templates/a.html.tera", "{{ x | date(format=\"%Y\") }}{{ y | as_str }}")
        val report = TeraMigrationRunner.apply(project, TeraMigrationRunner.plan(project, TeraMigrationRunner.templates(project)))
        assertEquals(1, report.changed.size)
        assertEquals(1, report.remaining.size)
        assertTrue(report.remaining.single().second.message.contains("tera-contrib"))
    }
}
