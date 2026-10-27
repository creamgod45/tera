package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * `examples/forms-tera2` is a real Rocket + Tera 2 application whose own tests run with `cargo test`. These tests keep it
 * consistent with the plugin: same templates as the golden files, and what the plugin says about it.
 */
class TeraFormsTera2ExampleTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        com.tera.tera.TeraSettings.getInstance(project).loadState(com.tera.tera.TeraSettingsState())   // detection, not a leftover setting
    }

    private val dir = File("examples/forms-tera2")
    private fun example(path: String) = File(dir, path).readText()
    private fun golden(n: String) = javaClass.getResource("/rocket-tera2/forms/templates/$n.html.tera")!!.readText()

    fun testTheExampleUsesTheGoldenMigratedTemplates() {
        assertTrue("run the tests from the repository root", dir.isDirectory)
        for (n in listOf("macros", "index", "success")) assertEquals(n, golden(n), example("templates/$n.html.tera"))
    }

    fun testItIsATera2ProjectToThePlugin() {
        val manifest = myFixture.addFileToProject("forms-tera2/Cargo.toml", example("Cargo.toml"))
        val template = myFixture.addFileToProject("forms-tera2/templates/index.html.tera", "")
        val info = TeraVersions.forFile(project, template.virtualFile)
        assertEquals(TeraVersion.V2, info.version)
        assertTrue(info.source, info.source.contains("tera = \"2.4\""))
        assertNotNull(manifest)
    }

    fun testItsTemplatesHaveNoTera2Errors() {
        TeraSettings.getInstance(project).state.version = "2"
        val files = listOf("macros", "index", "success").associateWith { example("templates/$it.html.tera") }
        val components = TeraComponentScanner.definitions(files.getValue("macros")).map { TeraComponent(it, null) }
        for ((n, text) in files) {
            val errors = (TeraMigration.check(text, TeraVersion.V2) + TeraComponentChecker.check(text, components) +
                TeraExternalAnnotator().doAnnotate(text)).filter { it.severity == HighlightSeverity.ERROR }
            assertEmpty(errors.map { "$n: ${it.message}" })
        }
    }

    fun testPageUsesNowWhichTera2OnlyHasThroughRegistration() {
        // Without the registration `now()` is reported as removed; main.rs registering it is what the Rust test checks.
        val errors = TeraMigration.check(example("templates/page.html.tera"), TeraVersion.V2).map { it.message }
        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().startsWith("Function 'now' does not exist in Tera 2"))
    }

    fun testMainRsRegistersWhatTheTemplatesNeed() {
        val main = example("src/main.rs")
        assertTrue(main.contains("register_function(\"now\", tera_contrib::dates::now)"))
        assertTrue(main.contains("autoescape_on([\".html\"])"))
        assertTrue(example("Cargo.toml").contains("preserve_order"))
        assertTrue(example("Cargo.toml").contains("features = [\"date\"]"))
    }
}
