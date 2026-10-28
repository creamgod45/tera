package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A project with hundreds of templates: the project-wide scans (usages, overrides, rename, contexts) and a long template
 * must stay in the range of seconds, not minutes. The limits are generous; they catch a scan that became quadratic.
 */
class TeraPerformanceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = "2"
    }

    private fun <T> timed(label: String, limitMs: Long, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000
        System.err.println("PERF $label: ${ms}ms")
        assertTrue("$label took ${ms}ms (limit ${limitMs}ms)", ms <= limitMs)
        return result
    }

    private fun site(pages: Int) {
        myFixture.addFileToProject("templates/components.html.tera",
            (0 until 20).joinToString("\n") { "{% component ui.c$it(title: string = \"\") %}<i>{{ title }}</i>{{ body }}{% endcomponent %}" })
        myFixture.addFileToProject("templates/base.html.tera", "<html>{% block content %}{% endblock content %}</html>")
        myFixture.addFileToProject("templates/layout.html.tera", "{% extends \"base.html\" %}{% block content %}{% block inner %}{% endblock inner %}{% endblock content %}")
        for (i in 0 until pages) {
            myFixture.addFileToProject("templates/pages/p$i.html.tera",
                "{% extends \"layout.html\" %}{% block inner %}{% set x$i = $i %}{{ x$i }}{% <ui.c${i % 20} title=\"t\"> %}{% for a in items %}{{ a }}{% endfor %}{% </ui.c${i % 20}> %}{{ <ui.c${(i + 1) % 20}/> }}{% endblock inner %}")
        }
    }

    fun testProjectWideScansOverThreeHundredTemplates() {
        site(300)
        val base = myFixture.findFileInTempDir("templates/base.html.tera")
        timed("usages of a component (first, builds the index)", 15_000) { TeraUsages.ofComponent(project, "ui.c3") }.also { assertTrue("${it.size}", it.size >= 30) }
        timed("usages of a component (cached)", 500) { TeraUsages.ofComponent(project, "ui.c4") }
        timed("overriders of a block across the layout chain", 15_000) { TeraBlocks.overriders(project, base, "content") }
            .also { assertEquals(1, it.size) }
        timed("overriders of the inner block", 15_000) { TeraBlocks.overriders(project, myFixture.findFileInTempDir("templates/layout.html.tera"), "inner") }
            .also { assertEquals(300, it.size) }
        timed("every place a component name is written", 15_000) { TeraComponentRename.occurrences(project, "ui.c7") }
            .also { assertTrue(it.size >= 15) }
    }

    fun testALongTemplateIsAnalysedQuickly() {
        val body = (0 until 600).joinToString("\n") { "{% set v$it = $it %}<p>{{ v$it }}</p>{% for i in xs %}{{ i }}{% endfor %}{% if v$it %}y{% endif %}" }
        val file = myFixture.addFileToProject("templates/long.html.tera", body)
        val annotator = TeraExternalAnnotator()
        val input = annotator.collectInformation(file)!!
        timed("annotate ${body.lines().size} lines", 20_000) { annotator.doAnnotate(input) }
        timed("symbol analysis of ${body.lines().size} lines", 20_000) { TeraSymbolAnalysis.of(file)!! }
            .also { assertTrue(it.decls.size >= 600) }
        timed("fold regions", 5_000) {
            TeraFoldingBuilder().buildFoldRegions(file.viewProvider.getPsi(TeraLanguage)!!, myFixture.getDocument(file), false)
        }
        timed("re-indent", 5_000) { TeraIndenter.format(body, "  ") }
    }
}
