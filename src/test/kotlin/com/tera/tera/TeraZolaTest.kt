package com.tera.tera

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** A Zola site: templates are `.html` mapped to Tera, components live in any of them, and Zola adds its own functions. */
class TeraZolaTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).state.version = "2"
        runWriteAction { FileTypeManager.getInstance().associatePattern(TeraFileType, "*.html") }
    }

    override fun tearDown() {
        try {
            runWriteAction { FileTypeManager.getInstance().removeAssociatedExtension(TeraFileType, "html") }
        } finally {
            super.tearDown()
        }
    }

    private fun site() {
        myFixture.addFileToProject("zola.toml", "base_url = \"https://example.com\"\n")
        myFixture.addFileToProject("templates/macros.html",
            "{% component post_card(page) %}<p>{{ page.title }}</p>{% endcomponent post_card %}")
    }

    fun testComponentsAreFoundInHtmlTemplatesMappedToTera() {
        site()
        assertTrue(TeraComponents.project(project).any { it.def.name == "post_card" })
    }

    fun testZolaFunctionsAndFiltersAreKnownInAZolaSite() {
        site()
        val custom = TeraContexts.customBuiltins(project)
        for (name in listOf("get_url", "get_section", "get_taxonomy", "get_taxonomy_url", "get_page", "load_data", "resize_image")) {
            assertTrue(name, custom.any { it.kind == "function" && it.def.name == name })
        }
        assertTrue(custom.any { it.kind == "filter" && it.def.name == "markdown" })

        val text = """{% set blog = get_section(path="blog/_index.md") %}{{ get_url(path="a") | base64_encode }}"""
        val errors = TeraMigration.check(text, TeraVersion.V2, custom)
        assertTrue(errors.map { it.message }.toString(), errors.none { it.message.contains("Unknown") })
    }

    fun testFiltersZolaStillProvidesAreNotCalledRemoved() {
        val text = """{{ now() | date(format="%Y") }}{{ "<b>a</b>" | striptags | slugify }}"""
        site()
        val inZola = TeraMigration.check(text, TeraVersion.V2, TeraContexts.customBuiltins(project)).map { it.message }
        assertTrue(inZola.toString(), inZola.isEmpty())
        // The same text in a plain Tera 2 project is an error: tera-contrib has to be registered there.
        assertTrue(TeraMigration.check(text, TeraVersion.V2, emptyList()).any { it.message.contains("tera-contrib") })
    }

    fun testNothingIsAddedOutsideAZolaSite() {
        myFixture.addFileToProject("templates/a.html", "{{ get_url(path=\"a\") }}")
        assertTrue(TeraContexts.customBuiltins(project).none { it.def.name == "get_url" })
    }

    fun testOlderConfigTomlNextToContentAndTemplatesCounts() {
        myFixture.addFileToProject("config.toml", "base_url = \"https://example.com\"\n")
        myFixture.addFileToProject("content/_index.md", "+++\n+++\n")
        myFixture.addFileToProject("templates/index.html", "")
        assertTrue(TeraZola.isZolaProject(project))
    }

    private fun put(path: String, text: String): com.intellij.psi.PsiFile {
        val existing = myFixture.findFileInTempDir(path)
        if (existing == null) return myFixture.addFileToProject(path, text)
        runWriteAction { com.intellij.openapi.vfs.VfsUtil.saveText(existing, text) }
        return myFixture.psiManager.findFile(existing)!!
    }

    private fun ctx(path: String, text: String) = TeraContexts.forFile(put(path, text))

    private fun problems(path: String, text: String): List<String> {
        val c = ctx(path, text)
        return TeraVariables.check(text, c, TeraVersion.V2).map { it.message }
    }

    fun testPageTemplateKnowsPageAndConfig() {
        site()
        val c = ctx("templates/page.html", "{{ page.title }}")!!
        assertTrue(c.complete)
        assertEquals(setOf("config", "lang", "zola_version", "current_path", "current_url", "page"), c.variables.map { it.name }.toSet())
        
        assertTrue(problems("templates/page.html", "{{ page.title }} {{ page.taxonomies.tags }} {{ page.extra.anything.at.all }} {{ config.extra.x }}").isEmpty())
    }

    fun testMisspelledFieldsAndVariablesAreReported() {
        site()
        val found = problems("templates/page.html", "{{ page.titl }} {{ section.title }}")
        assertEquals(found.toString(), 2, found.size)
    }

    fun testSectionTaxonomyAndNotFoundTemplates() {
        myFixture.addFileToProject("zola.toml", "base_url = \"https://e.com\"\n[[taxonomies]]\nname = \"tags\"\n")
        assertTrue(problems("templates/section.html", "{% for p in section.pages %}{{ p.title }}{{ p.date }}{% endfor %}{{ paginator.current_index }}").isEmpty())
        assertTrue(problems("templates/tags/list.html", "{% for t in terms %}{{ t.name }}{{ t.page_count }}{% endfor %}{{ taxonomy.name }}").isEmpty())
        assertTrue(problems("templates/tags/single.html", "{{ term.name }}{{ term.pages | length }}").isEmpty())
        assertEquals(1, problems("templates/404.html", "{{ page.title }}").size)
    }

    fun testTemplateNamedByFrontMatterGetsPageVariables() {
        site()
        myFixture.addFileToProject("content/blog/post.md", "+++\ntitle = \"x\"\ntemplate = \"project.html\"\n+++\n")
        myFixture.addFileToProject("content/projects/_index.md", "+++\ntemplate = \"projects.html\"\n+++\n")
        assertTrue(problems("templates/project.html", "{{ page.title }}").isEmpty())
        assertEquals(1, problems("templates/project.html", "{{ section.title }}").size)
        assertTrue(problems("templates/projects.html", "{{ section.pages }}").isEmpty())
    }

    fun testBaseTemplateGetsVariablesFromTheTemplatesExtendingIt() {
        site()
        myFixture.addFileToProject("templates/page.html", "{% extends \"base.html\" %}")
        val c = ctx("templates/base.html", "{{ page.title }}{{ config.title }}")!!
        assertTrue(c.variables.any { it.name == "page" })
    }

    fun testNoZolaVariablesOutsideASite() {
        assertNull(ctx("templates/page.html", "{{ page.title }}"))
    }
}
