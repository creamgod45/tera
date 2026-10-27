package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Regression: a directory whose name ends in `.tera` (the plugin verifier writes `.../plugins/com.tera.tera`) is returned by the
 * extension index like a template. Reading it threw `Cannot get content of directory` inside the annotator.
 */
class TeraDirectoryNamedLikeATemplateTest : BasePlatformTestCase() {

    private fun directories() {
        myFixture.tempDirFixture.findOrCreateDir("build/reports/pluginVerifier/IC/plugins/com.tera.tera")
        myFixture.tempDirFixture.findOrCreateDir("vendor/odd.rs")
    }

    fun testExtensionLookupSkipsDirectories() {
        directories()
        val file = myFixture.addFileToProject("templates/a.html.tera", "{{ x }}")
        val found = TeraFiles.projectFiles(project, "tera")
        assertEquals(listOf("a.html.tera"), found.map { it.name })
        assertTrue(found.none { it.isDirectory })
        assertNotNull(file)
    }

    fun testReadingADirectoryGivesNoText() {
        directories()
        val dir = myFixture.tempDirFixture.findOrCreateDir("build/reports/pluginVerifier/IC/plugins/com.tera.tera")
        assertEquals("", TeraFiles.textOf(dir))
    }

    fun testContextAndComponentLookupsSurviveSuchDirectories() {
        directories()
        val base = myFixture.addFileToProject("templates/base.html.tera", "{% component c(a) %}{{ a }}{% endcomponent c %}")
        myFixture.addFileToProject("templates/child.html.tera", "{% extends \"base\" %}")
        TeraContexts.forFile(base)                                      // the call in the stack trace
        assertEquals(listOf("c"), TeraComponents.project(project).map { it.def.name })
        assertEquals(setOf("base.html.tera", "child.html.tera"), TeraMigrationRunner.templates(project).map { it.name }.toSet())
    }

    fun testTheAnnotatorRunsInAProjectWithSuchDirectories() {
        directories()
        val file = myFixture.addFileToProject("templates/a.html.tera", "{{ x | upper }}")
        val annotator = TeraExternalAnnotator()
        val input = annotator.collectInformation(file)!!          // threw IOException before the fix
        assertNotNull(annotator.doAnnotate(input))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.doHighlighting()
    }

    fun testMigratingTheProjectIgnoresSuchDirectories() {
        directories()
        myFixture.addFileToProject("templates/a.html.tera", "{{ x | as_str }}")
        val plan = TeraMigrationRunner.plan(project, TeraMigrationRunner.templates(project))
        assertEquals(1, plan.size)
    }
}
