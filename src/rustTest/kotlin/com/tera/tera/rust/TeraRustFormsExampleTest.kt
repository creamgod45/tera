package com.tera.tera.rust

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tera.tera.TeraContexts
import com.tera.tera.TeraSettings
import java.io.File

/** The plugin read against the real `examples/forms-tera2` sources. */
class TeraRustFormsExampleTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        com.tera.tera.TeraSettings.getInstance(project).loadState(com.tera.tera.TeraSettingsState())   // detection, not a leftover setting
    }

    private fun example(path: String) = File("examples/forms-tera2", path).readText()

    private fun load() {
        myFixture.addFileToProject("forms-tera2/Cargo.toml", example("Cargo.toml"))
        myFixture.addFileToProject("forms-tera2/src/main.rs", example("src/main.rs"))
        for (n in listOf("macros", "index", "success")) {
            myFixture.addFileToProject("forms-tera2/templates/$n.html.tera", example("templates/$n.html.tera"))
        }
        // The page template as committed (the working copy may be edited); what matters is the `now()` in it.
        myFixture.addFileToProject("forms-tera2/templates/page.html.tera", "{{ title }}\n{{ count }}\n\n{{ now() }}\n")
    }

    fun testMainRsRegistersNowFromTeraContrib() {
        load()
        val now = TeraContexts.customBuiltins(project).single { it.kind == "function" && it.def.name == "now" }
        assertEquals(listOf("timezone"), now.def.params.map { it.name })
        assertTrue(now.def.source!!, now.def.source!!.startsWith("main.rs:"))
        assertTrue(now.def.details!!.startsWith("From tera-contrib (now)"))
    }

    fun testPageTemplateIsCleanOnceNowIsRegistered() {
        load()
        val page = myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("forms-tera2/templates/page.html.tera"))
        assertNotNull(page)
        assertEquals(emptyList<String>(), myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.mapNotNull { it.description })
    }

    fun testWholeExampleIsCleanInTheEditor() {
        load()
        for (n in listOf("macros", "index", "success")) {
            myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("forms-tera2/templates/$n.html.tera"))
            // success.html.tera has an unclosed <div> in Rocket's original; that is the HTML inspection's finding, not Tera's.
            val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.mapNotNull { it.description }
                .filter { it != "Element div is not closed" }
            assertEquals("$n", emptyList<String>(), errors)
        }
    }

    fun testVersionComesFromTheExamplesCargoToml() {
        load()
        val info = com.tera.tera.TeraVersions.forFile(project, myFixture.findFileInTempDir("forms-tera2/templates/index.html.tera"))
        assertEquals(com.tera.tera.TeraVersion.V2, info.version)
        assertEquals("auto", TeraSettings.getInstance(project).state.version)
    }
}
