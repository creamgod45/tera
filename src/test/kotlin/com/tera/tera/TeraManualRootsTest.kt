package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Settings → Tera → Template roots: chosen by hand, they win over what is detected. */
class TeraManualRootsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
    }

    private fun roots(vararg dirs: String) { TeraSettings.getInstance(project).state.templateRoots = dirs.joinToString("\n") }
    private fun file(path: String) = myFixture.findFileInTempDir(path)

    fun testNothingChangesWithoutManualRoots() {
        assertEmpty(TeraTemplateRoots.manual(project))
        myFixture.addFileToProject("templates/base.html.tera", "")
        val page = myFixture.addFileToProject("templates/pages/a.html.tera", "")
        assertEquals(listOf("base.html.tera"), TeraTemplateResolver.resolveAll(page.virtualFile, "base").map { it.name })
    }

    fun testANameIsReadAgainstAHandChosenRootFromAnywhere() {
        myFixture.addFileToProject("web/skin/layout.html.tera", "")
        val page = myFixture.addFileToProject("elsewhere/deep/page.html.tera", "")
        assertEmpty(TeraTemplateResolver.resolveAll(page.virtualFile, "layout"))
        roots("web/skin")
        assertEquals(listOf("web/skin/layout.html.tera"), TeraTemplateResolver.resolveAll(page.virtualFile, "layout").map { it.path.substringAfter("/src/") })
        assertEquals(listOf("web/skin/layout.html.tera"), TeraTemplateResolver.resolveAll(page.virtualFile, "layout.html").map { it.path.substringAfter("/src/") })
    }

    fun testImportsSeeTheMacrosOfAHandChosenRoot() {
        myFixture.addFileToProject("web/skin/macros.html.tera", "{% macro m(x) %}{{ x }}{% endmacro m %}")
        val page = myFixture.addFileToProject("elsewhere/page.html.tera", "")
        roots("web/skin")
        assertEquals(listOf("m"), TeraImports.macrosOf(page.virtualFile, "macros")!!.map { it.name })
    }

    fun testHandChosenRootsComeFirstAndSayWhy() {
        myFixture.addFileToProject("app/Cargo.toml", "[package]\nname=\"app\"\n")
        myFixture.addFileToProject("app/src/main.rs", "fn main(){}")
        myFixture.addFileToProject("app/templates/p.html.tera", "")
        myFixture.addFileToProject("app/ui/p.html.tera", "")
        assertEquals(listOf("app/templates/p.html.tera"), TeraTemplateRoots.resolve(project, file("app/src/main.rs"), "p").map { it.first.path.substringAfter("/src/") })
        roots("app/ui")
        val hit = TeraTemplateRoots.resolve(project, file("app/src/main.rs"), "p").single()
        assertEquals("app/ui/p.html.tera", hit.first.path.substringAfter("/src/"))
        assertTrue(hit.second, hit.second.startsWith("Settings → Tera"))
    }

    fun testMissingAndBlankLinesAreIgnoredAndTemplateNamesFollowTheRoot() {
        myFixture.addFileToProject("web/skin/pages/a.html.tera", "")
        myFixture.addFileToProject("web/skin/base.html.tera", "")
        roots("", "does/not/exist", "web/skin", "  ")
        assertEquals(1, TeraTemplateRoots.manual(project).size)
        val page = file("web/skin/pages/a.html.tera")
        assertEquals(listOf("base", "pages/a"), TeraTemplateResolver.templateNames(page))
    }

    fun testTheSettingSurvivesSaving() {
        roots("web/skin")
        val saved = TeraSettings.getInstance(project).state
        val reloaded = TeraSettingsState().also { it.templateRoots = saved.templateRoots }
        assertEquals("web/skin", reloaded.templateRoots)
    }
}
