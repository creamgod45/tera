package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Find Usages and Rename for blocks, macros and variables. */
class TeraRenameTest : BasePlatformTestCase() {

    private fun setUpVersion(v: String) {
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = v
    }

    /** Adds the file at [path] and puts the caret where `<caret>` stands in [text]. */
    private fun openAt(path: String, text: String) {
        val at = text.indexOf("<caret>")
        val file = myFixture.addFileToProject(path, text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(at)
    }

    private fun text(path: String) = TeraFiles.textOf(myFixture.findFileInTempDir(path))

    // ── variables ───────────────────────────────────────────────────────────

    fun testRenameASetVariableFromItsUse() {
        setUpVersion("2")
        myFixture.configureByText("t.html.tera", "{% set greeting = \"hi\" %}{{ greeting }} {{ greet<caret>ing | upper }}")
        myFixture.renameElementAtCaret("hello")
        myFixture.checkResult("{% set hello = \"hi\" %}{{ hello }} {{ hello | upper }}")
    }

    fun testRenameALoopVariableFromItsDeclaration() {
        setUpVersion("2")
        myFixture.configureByText("t.html.tera", "{% for it<caret>em in xs %}{{ item }}{{ item.name }}{% endfor %}{{ item }}")
        myFixture.renameElementAtCaret("entry")
        // The `item` after the loop is not the loop's.
        myFixture.checkResult("{% for entry in xs %}{{ entry }}{{ entry.name }}{% endfor %}{{ item }}")
    }

    fun testFindUsagesOfAVariable() {
        setUpVersion("2")
        myFixture.configureByText("t.html.tera", "{% set a<caret> = 1 %}{{ a }}{{ a + 1 }}")
        val usages = myFixture.testFindUsagesUsingAction()
        assertEquals(2, usages.size)
    }

    fun testNothingToRenameOnAnUnknownNameOrAFilter() {
        setUpVersion("2")
        myFixture.configureByText("t.html.tera", "{{ unkn<caret>own | upper }}")
        val leaf = myFixture.file.findElementAt(myFixture.editor.caretModel.offset) as TeraNameLeaf
        assertNull(leaf.declaration)
        assertNull(leaf.reference)
        assertFalse(TeraFindUsagesProvider().canFindUsagesFor(leaf))
        assertFalse(com.intellij.refactoring.rename.RenamePsiElementProcessor.forElement(leaf) is TeraRenameProcessor)
    }

    // ── blocks ──────────────────────────────────────────────────────────────

    private fun blockProject() {
        myFixture.addFileToProject("templates/base.html.tera", "<html>{% block content %}{% endblock content %}</html>")
        myFixture.addFileToProject("templates/mid.html.tera", "{% extends \"base.html\" %}")
        myFixture.addFileToProject("templates/other.html.tera", "{% extends \"mid.html\" %}{% block content %}B{% endblock content %}")
    }

    fun testRenameABlockFromAnOverrideChangesTheWholeChain() {
        setUpVersion("2")
        blockProject()
        openAt("templates/child.html.tera", "{% extends \"mid.html\" %}{% block con<caret>tent %}A{% endblock content %}")
        myFixture.renameElementAtCaret("main")
        myFixture.checkResult("{% extends \"mid.html\" %}{% block main %}A{% endblock main %}")
        assertEquals("<html>{% block main %}{% endblock main %}</html>", text("templates/base.html.tera"))
        assertEquals("{% extends \"mid.html\" %}{% block main %}B{% endblock main %}", text("templates/other.html.tera"))
    }

    fun testRenameABlockFromTheTopmostDefinition() {
        setUpVersion("2")
        blockProject()
        myFixture.addFileToProject("templates/child.html.tera", "{% extends \"mid.html\" %}{% block content %}A{% endblock content %}")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("templates/base.html.tera"))
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("endblock content") + 12)
        myFixture.renameElementAtCaret("main")
        assertEquals("<html>{% block main %}{% endblock main %}</html>", text("templates/base.html.tera"))
        assertTrue(text("templates/child.html.tera"), text("templates/child.html.tera").contains("{% block main %}A{% endblock main %}"))
    }

    fun testUsagesOfABlockAreTheOverridesAndEndTags() {
        setUpVersion("2")
        blockProject()
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("templates/base.html.tera"))
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("content") + 2)
        val usages = myFixture.testFindUsagesUsingAction()
        // other.html: the override and its endblock; base.html: the endblock
        assertEquals(usages.joinToString { it.toString() }, 3, usages.size)
    }

    // ── macros (Tera 1) ─────────────────────────────────────────────────────

    fun testRenameAMacroChangesItsCalls() {
        setUpVersion("1")
        myFixture.addFileToProject("templates/macros.html.tera", "{% macro field(x) %}{{ x }}{% endmacro field %}")
        openAt("templates/page.html.tera", "{% import \"macros\" as m %}{{ m::fi<caret>eld(x=1) }}{{ m::field(x=2) }}")
        myFixture.renameElementAtCaret("input")
        myFixture.checkResult("{% import \"macros\" as m %}{{ m::input(x=1) }}{{ m::input(x=2) }}")
        assertEquals("{% macro input(x) %}{{ x }}{% endmacro input %}", text("templates/macros.html.tera"))
    }

    fun testRenameAMacroParameterChangesItsUsesInTheBody() {
        setUpVersion("1")
        myFixture.configureByText("t.html.tera", "{% macro m(la<caret>bel) %}{{ label }}{% endmacro m %}")
        myFixture.renameElementAtCaret("text")
        myFixture.checkResult("{% macro m(text) %}{{ text }}{% endmacro m %}")
    }
}

/** Renaming or moving a template file keeps `extends`, `include` and `import` pointing at it. */
class TeraRenameFileTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = "2"
    }

    private fun text(path: String) = TeraFiles.textOf(myFixture.findFileInTempDir(path))

    fun testHowAPathFollowsARenamedFile() {
        assertEquals("layouts/shell.html", TeraTemplatePaths.renamed("layouts/app.html", "app.html.tera", "shell.html.tera"))
        assertEquals("layouts/shell", TeraTemplatePaths.renamed("layouts/app", "app.html.tera", "shell.html.tera"))
        assertEquals("shell.html.tera", TeraTemplatePaths.renamed("app.html.tera", "app.html.tera", "shell.html.tera"))
        assertEquals("other.html", TeraTemplatePaths.renamed("other.html", "app.html.tera", "shell.html.tera"))
        assertEquals("pages/new.html", TeraTemplatePaths.pathFor("layouts/app.html", "pages/new.html.tera"))
        assertEquals("pages/new", TeraTemplatePaths.pathFor("layouts/app", "pages/new.html.tera"))
        assertEquals("pages/new.html.tera", TeraTemplatePaths.pathFor("a.html.tera", "pages/new.html.tera"))
    }

    fun testExtendsIncludeAndImportAreReferencesToTheFile() {
        myFixture.addFileToProject("templates/base.html.tera", "x")
        val page = myFixture.addFileToProject("templates/page.html.tera", "{% extends \"base.html\" %}{% include \"base\" %}")
        myFixture.configureFromExistingVirtualFile(page.virtualFile)
        for (needle in listOf("base.html", "\"base\"")) {
            val ref = myFixture.file.findReferenceAt(myFixture.file.text.indexOf(needle) + 2)
            assertEquals("base.html.tera", (ref?.resolve() as? com.intellij.psi.PsiFile)?.name)
        }
    }

    fun testRenamingTheFileUpdatesTheStrings() {
        myFixture.addFileToProject("templates/layouts/app.html.tera", "x")
        myFixture.addFileToProject("templates/pages/a.html.tera", "{% extends \"layouts/app.html\" %}")
        myFixture.addFileToProject("templates/pages/b.html.tera", "{% include \"layouts/app\" %}")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("templates/layouts/app.html.tera"))
        myFixture.renameElement(myFixture.file, "shell.html.tera")
        assertEquals("{% extends \"layouts/shell.html\" %}", text("templates/pages/a.html.tera"))
        assertEquals("{% include \"layouts/shell\" %}", text("templates/pages/b.html.tera"))
    }

    fun testMovingTheFileUpdatesTheStrings() {
        myFixture.addFileToProject("templates/layouts/app.html.tera", "x")
        myFixture.addFileToProject("templates/ui/keep.txt", "")
        myFixture.addFileToProject("templates/pages/a.html.tera", "{% extends \"layouts/app.html\" %}")
        myFixture.addFileToProject("templates/pages/b.html.tera", "{% include \"layouts/app\" %}")
        myFixture.moveFile("templates/layouts/app.html.tera", "templates/ui")
        assertEquals("{% extends \"ui/app.html\" %}", text("templates/pages/a.html.tera"))
        assertEquals("{% include \"ui/app\" %}", text("templates/pages/b.html.tera"))
    }
}

/** Renaming a component: definition, calls of both forms and the closing tags. */
class TeraComponentRenameTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = "2"
        myFixture.addFileToProject("templates/components.html.tera",
            "{% component ui.card(title: string = \"\") %}<s>{{ body }}</s>{% endcomponent %}\n{% component ui.badge(s: string) %}{{ s }}{% endcomponent %}")
        myFixture.addFileToProject("templates/a.html.tera", "{% <ui.card title=\"A\"> %}{{ <ui.badge s=\"x\"/> }}{% </ui.card> %}")
        myFixture.addFileToProject("templates/b.html.tera", "{{ <ui.card/> }} {{ <ui.card title=\"B\"/> }} {# ui.card #}")
    }

    private fun text(path: String) = TeraFiles.textOf(myFixture.findFileInTempDir(path))

    fun testEveryPlaceTheNameIsWrittenIsFound() {
        val found = TeraComponentRename.occurrences(project, "ui.card")
        assertEquals(mapOf("components.html.tera" to 1, "a.html.tera" to 2, "b.html.tera" to 2), found.map { it.key.name to it.value.size }.toMap())
    }

    fun testRenameChangesDefinitionCallsAndClosingTags() {
        val changed = TeraComponentRename.apply(project, "ui.card", "ui.panel")
        assertEquals(5, changed)
        assertTrue(text("templates/components.html.tera").startsWith("{% component ui.panel(title"))
        assertEquals("{% <ui.panel title=\"A\"> %}{{ <ui.badge s=\"x\"/> }}{% </ui.panel> %}", text("templates/a.html.tera"))
        // The comment is not a component name.
        assertEquals("{{ <ui.panel/> }} {{ <ui.panel title=\"B\"/> }} {# ui.card #}", text("templates/b.html.tera"))
    }

    fun testANameThatIsTakenOrNotValidIsRefused() {
        assertEquals("A component named ui.badge already exists", TeraComponentRename.problem(project, "ui.card", "ui.badge"))
        assertNotNull(TeraComponentRename.problem(project, "ui.card", "ui..x"))
        assertNotNull(TeraComponentRename.problem(project, "ui.card", "2fast"))
        assertNull(TeraComponentRename.problem(project, "ui.card", "ui.panel"))
        assertNull(TeraComponentRename.problem(project, "ui.card", "panel"))
    }

    fun testTheHandlerIsAvailableOnTheNameOfADefinitionACallAndAClosingTag() {
        for ((file, needle) in listOf("components.html.tera" to "ui.card", "a.html.tera" to "<ui.card", "a.html.tera" to "</ui.card")) {
            val vf = myFixture.findFileInTempDir("templates/$file")
            val text = TeraFiles.textOf(vf)
            val offset = text.indexOf(needle) + needle.length - 3
            assertEquals(file, "ui.card", TeraComponentRename.nameAt(text, offset))
        }
        assertNull(TeraComponentRename.nameAt("{{ x }}", 4))
    }

    fun testUndoIsOneCommand() {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("templates/a.html.tera"))
        TeraComponentRename.apply(project, "ui.badge", "ui.pill")
        assertTrue(text("templates/a.html.tera").contains("<ui.pill"))
        assertTrue(text("templates/components.html.tera").contains("component ui.pill("))
    }
}
