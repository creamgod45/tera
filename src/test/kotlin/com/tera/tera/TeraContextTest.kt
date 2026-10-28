package com.tera.tera

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Render-context features (diagnostics, navigation, hover, inheritance) against Rocket's todo and
 * templating example templates. A fake provider stands in for the Rust side.
 */
class TeraContextTest : BasePlatformTestCase() {

    /** These tests describe Tera 1.x behaviour (Rocket's engine); 2.x has its own tests. */
    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).state.version = "1"
    }

    private fun fixture(path: String) = javaClass.getResource("/rocket/examples/$path")!!.readText()
    private fun scalar(n: String) = TeraType.Scalar(n)

    private fun taskType(target: (String) -> PsiElement? = { null }) = TeraType.Struct("Task", listOf(
        TeraField("id", scalar("number"), "Option<i32>", target = target("id")),
        TeraField("description", scalar("string"), "String", "What needs to be done.", target("description")),
        TeraField("completed", scalar("bool"), "bool", target = target("completed")),
    ))

    private fun todoContext(complete: Boolean = true, target: (String) -> PsiElement? = { null }) = TeraContextResult(listOf(
        TeraField("flash", TeraType.Tuple(listOf(scalar("string"), scalar("string"))), "Option<(String, String)>",
            "Message shown above the list.", target("flash")),
        TeraField("tasks", TeraType.ArrayOf(taskType(target)), "Vec<Task>", "All tasks, newest first.", target("tasks")),
    ), complete, listOf("examples/todo/src/main.rs"))

    private val templatingContext = TeraContextResult(listOf(
        TeraField("title", scalar("string")), TeraField("name", scalar("string")),
        TeraField("items", TeraType.ArrayOf(scalar("string"))), TeraField("uri", scalar("string")),
    ), complete = true)

    private fun check(text: String, ctx: TeraContextResult? = todoContext()) = TeraVariables.check(text, ctx)

    private fun install(vararg byFile: Pair<String, TeraContextResult>) {
        val map = byFile.toMap()
        val provider = object : TeraContextProvider {
            override fun resolve(project: com.intellij.openapi.project.Project, template: com.intellij.openapi.vfs.VirtualFile) =
                map[template.name]
        }
        TeraContextProvider.EP.point.registerExtension(provider, testRootDisposable)
    }

    // ── Rocket templates produce no diagnostics ──────────────────────────────

    fun testRocketTodoTemplateIsClean() {
        assertEmpty(check(fixture("todo/static/index.html.tera")))
    }

    fun testRocketTemplatingTemplatesAreClean() {
        for (f in listOf("base", "index", "nav", "error/404")) {
            assertEmpty(f, check(fixture("templating/templates/tera/$f.html.tera"), templatingContext))
        }
    }

    fun testRocketTodoTemplateReportsEveryRealMistake() {
        val broken = fixture("todo/static/index.html.tera")
            .replace("task.description", "task.descripton")
            .replace("{% for task in tasks %}", "{% for task in taks %}")
        val errors = check(broken)
        // `taks` is not a variable; `task` then has unknown type, so the typo field is not guessable.
        assertEquals(listOf("taks"), errors.map { it.range.substring(broken) }.distinct())
        assertTrue(errors.all { it.severity == HighlightSeverity.WEAK_WARNING })
    }

    // ── undefined variables and fields ───────────────────────────────────────

    fun testUndefinedVariableIsAWeakWarningOnTheName() {
        val text = "<p>{{ taks }}</p>"
        val e = check(text).single()
        assertEquals(HighlightSeverity.WEAK_WARNING, e.severity)
        assertEquals("taks", e.range.substring(text))
        assertEquals("Variable 'taks' is not defined in this template or in the Rust render context", e.message)
    }

    fun testUnknownFieldOnKnownStructIsAWarning() {
        val text = "{% for task in tasks %}{{ task.descripton }}{% endfor %}"
        val e = check(text).single()
        assertEquals(HighlightSeverity.WARNING, e.severity)
        assertEquals("descripton", e.range.substring(text))
        assertEquals("Field 'descripton' does not exist on 'Task'", e.message)
    }

    fun testTupleIndexOutOfRangeAndLoopOutsideLoop() {
        assertEquals("2", check("{{ flash.2 }}").single().let { "{{ flash.2 }}".let { t -> it.range.substring(t) } })
        assertEquals(listOf("loop"), check("{{ loop.index }}").map { it.range.substring("{{ loop.index }}") })
        assertEmpty(check("{% for t in tasks %}{{ loop.index }}{{ loop.first }}{{ loop.last }}{% endfor %}"))
        assertEquals("foo", check("{% for t in tasks %}{{ loop.foo }}{% endfor %}").single()
            .range.substring("{% for t in tasks %}{{ loop.foo }}{% endfor %}"))
    }

    fun testLocalVariablesAreKnown() {
        assertEmpty(check("{% set a = 1 %}{% set b = a %}{{ a }}{{ b }}"))
        assertEmpty(check("{% for k, v in tasks %}{{ k }}{{ v.description }}{% endfor %}"))
        assertEquals(1, check("{% for t in tasks %}{% endfor %}{{ t }}").size)
    }

    fun testIndexedPathsAndFilterChains() {
        assertEmpty(check("{{ tasks[0].description }}{{ tasks.0.id }}{{ tasks | first }}"))
        assertEquals("nope", check("{{ tasks[0].nope }}").single().range.substring("{{ tasks[0].nope }}"))
        assertEmpty(check("{% set t = tasks | first %}{{ t.description | upper }}"))
    }

    fun testIdentifiersThatAreNotVariablesAreNotFlagged() {
        assertEmpty(check("""{{ now() }}{{ range(end=3) }}{{ tasks | length }}{{ "tasks" | upper }}"""))
        assertEmpty(check("{% if tasks | length is odd %}{% endif %}{% if flash is not defined %}{% endif %}"))
        assertEmpty(check("""{% import "macros" as m %}{{ m::input(label=flash.0, name="n") }}"""))
        assertEmpty(check("{{ tasks | sort(attribute=\"id\") | map(attribute=\"id\") | join(sep=\",\") }}"))
    }

    fun testOptionalPathsAreAllowedToBeUndefined() {
        assertEmpty(check("{% if ghost is defined %}x{% endif %}{% if ghost is undefined %}y{% endif %}"))
        assertEmpty(check("{{ ghost | default(value=\"x\") }}"))
        assertEquals(1, check("{{ ghost | upper }}").size)
    }

    /** Verified against tera 1.20.1's call stack: a macro frame falls back to the render context. */
    fun testMacrosSeeTheRenderContextInTera1() {
        assertEmpty(check("{% macro m(a) %}{{ a }}{{ tasks }}{% endmacro %}{{ tasks }}"))
    }

    fun testMacrosDoNotSeeSetOrLoopVariablesAroundThemInTera1() {
        val text = "{% set x = 1 %}{% for t in tasks %}{% macro m() %}{{ x }}{{ t }}{% endmacro %}{% endfor %}"
        val e = check(text)
        assertEquals(listOf("x", "t"), e.map { it.range.substring(text) })
        assertEquals("Variable 'x' is not defined in this template or in the Rust render context", e[0].message)
    }

    fun testSilentWithoutCompleteKnowledge() {
        assertEmpty(check("{{ nothing }}", todoContext(complete = false)))
        assertEmpty(check("{{ nothing }}", null))
    }

    fun testUnknownTypesNeverProduceFieldWarnings() {
        val ctx = TeraContextResult(listOf(TeraField("data", TeraType.Unknown, "serde_json::Value")), true)
        assertEmpty(check("{{ data.anything.at.all }}{{ data[0].x }}", ctx))
    }

    // ── annotator integration ────────────────────────────────────────────────

    fun testAnnotatorUsesContextFromProviders() {
        install("index.html.tera" to todoContext())
        val file = myFixture.addFileToProject("templates/index.html.tera", "{{ tasks | length }}{{ taks }}")
        val annotator = TeraExternalAnnotator()
        val input = annotator.collectInformation(file)!!
        assertNotNull(input.context)
        val errors = annotator.doAnnotate(input)
        assertEquals(listOf("taks"), errors.map { it.range.substring(file.text) })
    }

    fun testAnnotatorWithoutProviderResultHasNoVariableDiagnostics() {
        val file = myFixture.addFileToProject("templates/lonely.html.tera", "{{ whatever }}")
        val annotator = TeraExternalAnnotator()
        assertNull(annotator.collectInformation(file)!!.context)
        assertEmpty(annotator.doAnnotate(annotator.collectInformation(file)!!))
    }

    // ── inheritance: base and partials use their users' context ──────────────

    fun testBaseTemplateGetsContextOfTemplatesThatExtendIt() {
        install("index.html.tera" to templatingContext)
        myFixture.addFileToProject("templates/tera/index.html.tera", fixture("templating/templates/tera/index.html.tera"))
        val base = myFixture.addFileToProject("templates/tera/base.html.tera", fixture("templating/templates/tera/base.html.tera"))
        val ctx = TeraContexts.forFile(base)!!
        assertEquals(setOf("title", "name", "items", "uri"), ctx.variables.map { it.name }.toSet())
        assertTrue(ctx.complete)
    }

    fun testPartialIncludedByBaseInheritsThroughTheChain() {
        install("index.html.tera" to templatingContext)
        myFixture.addFileToProject("templates/tera/index.html.tera", fixture("templating/templates/tera/index.html.tera"))
        myFixture.addFileToProject("templates/tera/base.html.tera", fixture("templating/templates/tera/base.html.tera"))
        val nav = myFixture.addFileToProject("templates/tera/nav.html.tera", fixture("templating/templates/tera/nav.html.tera"))
        assertNotNull(TeraContexts.forFile(nav))
        assertTrue(TeraContexts.forFile(nav)!!.variables.any { it.name == "title" })
    }

    fun testUnrenderedChildMakesInheritedContextIncomplete() {
        install("index.html.tera" to templatingContext)
        myFixture.addFileToProject("templates/tera/index.html.tera", fixture("templating/templates/tera/index.html.tera"))
        myFixture.addFileToProject("templates/tera/orphan.html.tera", """{% extends "tera/base" %}""")
        val base = myFixture.addFileToProject("templates/tera/base.html.tera", fixture("templating/templates/tera/base.html.tera"))
        val ctx = TeraContexts.forFile(base)!!
        assertFalse("an orphan child may supply other variables", ctx.complete)
        assertEmpty(TeraExternalAnnotator().doAnnotate(TeraExternalAnnotator().collectInformation(base)!!))
    }

    fun testTemplateWithNoRenderSiteAndNoDependentsHasNoContext() {
        install("other.html.tera" to todoContext())
        val file = myFixture.addFileToProject("templates/alone.html.tera", "{{ x }}")
        assertNull(TeraContexts.forFile(file))
    }

    fun testFailingProviderDoesNotBreakOtherProviders() {
        val broken = object : TeraContextProvider {
            override fun resolve(project: com.intellij.openapi.project.Project, template: com.intellij.openapi.vfs.VirtualFile):
                TeraContextResult? = throw NoClassDefFoundError("org/rust/Missing")
        }
        TeraContextProvider.EP.point.registerExtension(broken, testRootDisposable)
        install("index.html.tera" to todoContext())
        val file = myFixture.addFileToProject("templates/index.html.tera", "{{ tasks }}")
        com.intellij.testFramework.LoggedErrorProcessor.executeWith<RuntimeException>(
            object : com.intellij.testFramework.LoggedErrorProcessor() {
                override fun processWarn(category: String, message: String, t: Throwable?): Boolean = false
            },
        ) { assertEquals(listOf("flash", "tasks"), TeraContexts.forFile(file)!!.variables.map { it.name }) }
    }

    fun testInheritanceCycleTerminates() {
        install("a.html.tera" to todoContext())
        myFixture.addFileToProject("templates/a.html.tera", """{% extends "b" %}""")
        val b = myFixture.addFileToProject("templates/b.html.tera", """{% extends "a" %}""")
        assertNotNull(TeraContexts.forFile(b))
    }

    // ── goto declaration ─────────────────────────────────────────────────────

    private fun rustLeaf(): Map<String, PsiElement> {
        val rs = myFixture.addFileToProject("src/main.rs", "flash tasks id description completed")
        return listOf("flash", "tasks", "id", "description", "completed").associateWith {
            rs.findElementAt(rs.text.indexOf(it))!!
        }
    }

    private fun gotoTargets(name: String, textWithCaret: String): List<PsiElement> {
        val caret = textWithCaret.indexOf("<caret>")
        val file = myFixture.addFileToProject("templates/$name", textWithCaret.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        return GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, caret).toList()
    }

    fun testGotoVariableGoesToRustDeclaration() {
        val leaves = rustLeaf()
        install("index.html.tera" to todoContext(target = { leaves[it] }))
        val t = gotoTargets("index.html.tera", "{{ ta<caret>sks }}")
        assertEquals(listOf(leaves["tasks"]), t)
    }

    fun testGotoFieldGoesToRustField() {
        val leaves = rustLeaf()
        install("index.html.tera" to todoContext(target = { leaves[it] }), "index2.html.tera" to todoContext(target = { leaves[it] }))
        val t = gotoTargets("index.html.tera", "{% for task in tasks %}{{ task.descr<caret>iption }}{% endfor %}")
        assertEquals(listOf(leaves["description"]), t)
        val t2 = gotoTargets("index2.html.tera", "{{ tasks[0].com<caret>pleted }}")
        assertEquals(listOf(leaves["completed"]), t2)
    }

    fun testGotoLoopVariableGoesToItsForHeader() {
        install("index.html.tera" to todoContext())
        val text = "{% for task in tasks %}{{ ta<caret>sk.id }}{% endfor %}"
        val t = gotoTargets("index.html.tera", text)
        assertEquals(1, t.size)
        assertEquals(text.replace("<caret>", "").indexOf("task in"), t.single().textOffset)
    }

    fun testGotoSetVariableAndMacroParameter() {
        install("index.html.tera" to todoContext())
        val set = "{% set total = 1 %}{{ to<caret>tal }}"
        assertEquals(set.indexOf("total"), gotoTargets("index.html.tera", set).single().textOffset)
        val macro = "{% macro m(who) %}{{ wh<caret>o }}{% endmacro %}"
        assertEquals(macro.indexOf("who"), gotoTargets("macro.html.tera", macro).single().textOffset)
    }

    fun testGotoUndefinedVariableHasNoTarget() {
        install("index.html.tera" to todoContext())
        assertEmpty(gotoTargets("index.html.tera", "{{ ghos<caret>t }}"))
    }

    // ── hover ────────────────────────────────────────────────────────────────

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&amp;", "&").replace(Regex("\\s+"), " ").replace(" :", ":").trim()

    private fun hoverAt(name: String, textWithCaret: String): String? {
        val caret = textWithCaret.indexOf("<caret>")
        val file = myFixture.addFileToProject("templates/$name", textWithCaret.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        if (IdeDocumentationTargetProvider.getInstance(project).documentationTargets(myFixture.editor, file, caret).isEmpty()) return null
        val provider = TeraDocumentationProvider()
        val element = provider.getCustomDocumentationElement(myFixture.editor, file, null, caret) ?: return null
        return provider.generateDoc(element, null)?.let(::plain)
    }

    fun testHoverContextVariableShowsRustTypeDocAndSource() {
        install("index.html.tera" to todoContext())
        val doc = hoverAt("index.html.tera", "{{ ta<caret>sks }}")!!
        assertTrue(doc, doc.contains("(variable) tasks: Vec<Task>"))
        assertTrue(doc, doc.contains("All tasks, newest first."))
        assertTrue(doc, doc.contains("Template type: array<Task>"))
        assertTrue(doc, doc.contains("Source: Rust render context (examples/todo/src/main.rs)"))
    }

    fun testHoverFieldShowsItsDocComment() {
        install("index.html.tera" to todoContext())
        val doc = hoverAt("index.html.tera", "{% for task in tasks %}{{ task.descr<caret>iption }}{% endfor %}")!!
        assertTrue(doc, doc.contains("(variable) description: String"))
        assertTrue(doc, doc.contains("What needs to be done."))
        assertTrue(doc, doc.contains("Source: field of the Rust render context"))
    }

    fun testHoverLoopVariableListsStructFields() {
        install("index.html.tera" to todoContext())
        val doc = hoverAt("index.html.tera", "{% for task in tasks %}{{ ta<caret>sk.id }}{% endfor %}")!!
        assertTrue(doc, doc.contains("(variable) task: Task"))
        assertTrue(doc, doc.contains("id: Option<i32>"))
        assertTrue(doc, doc.contains("description: String"))
        assertTrue(doc, doc.contains("Source: loop variable"))
    }

    fun testHoverLoopBuiltinAndSetVariable() {
        install("index.html.tera" to todoContext())
        val loop = hoverAt("index.html.tera", "{% for task in tasks %}{{ loop.ind<caret>ex }}{% endfor %}")!!
        assertTrue(loop, loop.contains("(variable) index: number"))
        assertTrue(loop, loop.contains("1-based index of the current iteration"))
        val set = hoverAt("set.html.tera", "{% set total = tasks | length %}{{ to<caret>tal }}")!!
        assertTrue(set, set.contains("(variable) total: number"))
        assertTrue(set, set.contains("Source: set in template"))
    }

    fun testHoverUnknownVariableShowsNothing() {
        install("index.html.tera" to todoContext())
        assertNull(hoverAt("index.html.tera", "{{ gho<caret>st }}"))
    }

    // ── editor completion end to end ─────────────────────────────────────────

    fun testEditorCompletionOfContextVariablesAndFields() {
        install("index.html.tera" to todoContext(), "two.html.tera" to todoContext())
        val file = myFixture.addFileToProject("templates/index.html.tera", "{% for task in tasks %}{{ task.desc }}{% endfor %}")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf(" }}"))
        myFixture.completeBasic()
        assertTrue(myFixture.editor.document.text.contains("task.description"))

        val file2 = myFixture.addFileToProject("templates/two.html.tera", "{{ ta }}")
        myFixture.configureFromExistingVirtualFile(file2.virtualFile)
        myFixture.editor.caretModel.moveToOffset(file2.text.indexOf(" }}"))
        myFixture.completeBasic()
        // `tasks` is a list, so the loops over it are offered beside the variable: pick the variable itself.
        val items = myFixture.lookupElements
        if (items != null) {
            assertTrue(items.map { it.lookupString }.toString(), items.count { it.lookupString == "tasks" } >= 2)
            myFixture.lookup.currentItem = items.first { it.`object` !is TeraSnippet && it.lookupString == "tasks" }
            myFixture.finishLookup('\n')
        }
        assertTrue(myFixture.editor.document.text.contains("tasks"))
    }

    // ── presentation ─────────────────────────────────────────────────────────

    fun testDescriptionsAreSeparatedFromTheNameInTheLookupList() {
        install("index.html.tera" to todoContext())
        val file = myFixture.addFileToProject("templates/index.html.tera", "{{  }}")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(3)
        myFixture.completeBasic()
        val items = myFixture.lookup.items
        fun tail(name: String): String {
            val p = com.intellij.codeInsight.lookup.LookupElementPresentation()
            items.first { it.lookupString == name }.renderElement(p)
            return p.tailText.orEmpty()
        }
        assertEquals("  read an environment variable", tail("get_env"))
        assertEquals("  current datetime", tail("now"))
        assertEquals(": Vec<Task>", tail("tasks"))
    }

    fun testDisplayTailRules() {
        assertEquals(": Vec<Task>", displayTail(": Vec<Task>"))
        assertEquals("(a, b=\"x\")", displayTail("(a, b=\"x\")"))
        assertEquals("  uppercase", displayTail("uppercase"))
        assertEquals("  macros", displayTail("macros"))
        assertEquals("  already", displayTail("  already"))
    }
}
