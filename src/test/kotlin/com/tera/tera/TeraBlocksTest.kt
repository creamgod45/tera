package com.tera.tera

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `{% block %}` along the `extends` chain: where it comes from, who replaces it, `super()`. */
class TeraBlocksTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = "2"
        myFixture.addFileToProject("templates/base.html.tera", "<html>\n{% block title %}T{% endblock title %}\n{% block content %}{% endblock content %}\n</html>")
        myFixture.addFileToProject("templates/mid.html.tera", "{% extends \"base.html\" %}{% block title %}M{% endblock title %}")
        myFixture.addFileToProject("templates/child.html.tera",
            "{% extends \"mid.html\" %}{% block content %}A {{ super() }}{% endblock content %}")
        myFixture.addFileToProject("templates/other.html.tera",
            "{% extends \"mid.html\" %}{% block content %}B{% endblock content %}{% block title %}O{% endblock title %}")
        myFixture.addFileToProject("templates/orphan.html.tera", "{% extends \"nope.html\" %}{% block x %}{% endblock x %}")
    }

    private fun file(n: String) = myFixture.findFileInTempDir("templates/$n.html.tera")
    private fun names(list: List<TeraDefinition>) = list.map { it.file!!.name.removeSuffix(".html.tera") }

    fun testABlockComesFromTheNearestDefinitionAlongTheChain() {
        // child → mid (no `content`) → base
        assertEquals(listOf("base"), TeraBlocks.parents(file("child"), "content").map { it.first.name.removeSuffix(".html.tera") })
        assertEquals(listOf("base"), TeraBlocks.parents(file("mid"), "title").map { it.first.name.removeSuffix(".html.tera") })
        // other overrides `title` too: its nearest parent is mid, not base
        assertEquals(listOf("mid"), TeraBlocks.parents(file("other"), "title").map { it.first.name.removeSuffix(".html.tera") })
    }

    fun testATemplateWithAMissingParentHasNoParents() {
        assertEmpty(TeraBlocks.parents(file("orphan"), "x"))
        assertEmpty(TeraBlocks.parents(file("base"), "content"))
    }

    fun testOverridersAreFoundBelowTheDefinition() {
        assertEquals(listOf("child", "other"), TeraBlocks.overriders(project, file("base"), "content").map { it.first.name.removeSuffix(".html.tera") })
        assertEquals(listOf("mid", "other"), TeraBlocks.overriders(project, file("base"), "title").map { it.first.name.removeSuffix(".html.tera") })
        assertEmpty(TeraBlocks.overriders(project, file("child"), "content"))
    }

    private fun gotoFrom(template: String, marker: String, occurrence: Int = 0): List<String> {
        val vf = file(template)
        myFixture.configureFromExistingVirtualFile(vf)
        var at = -1
        repeat(occurrence + 1) { at = myFixture.file.text.indexOf(marker, at + 1) }
        val definitions = TeraNavigation.definitions(myFixture.file.text, at + 1, blockLinks = { TeraBlocks.links(project, vf, it) }) { emptyList() }
        return names(definitions)
    }

    fun testGoToDeclarationOnABlockNameLeadsToTheParentBlock() {
        assertEquals(listOf("base"), gotoFrom("child", "content"))
        assertEquals(listOf("base"), gotoFrom("child", "content", 1))      // the name after `endblock`
    }

    fun testOnTheTopmostBlockItListsWhoOverridesIt() {
        assertEquals(listOf("child", "other"), gotoFrom("base", "content"))
    }

    fun testSuperGoesToTheParentsBlock() {
        assertEquals(listOf("base"), gotoFrom("child", "super"))
    }

    fun testTheRealGotoActionOffersTheTargets() {
        myFixture.configureFromExistingVirtualFile(file("child"))
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("content") + 2)
        val target = GotoDeclarationAction.findTargetElement(project, myFixture.editor, myFixture.editor.caretModel.offset)
        assertEquals("base.html.tera", target?.containingFile?.name)
    }

    fun testGuttersShowOverridesAndOverriders() {
        myFixture.configureFromExistingVirtualFile(file("child"))
        val childTips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertTrue(childTips.toString(), "Overrides block content in base.html.tera" in childTips)
        myFixture.configureFromExistingVirtualFile(file("base"))
        val baseTips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertTrue(baseTips.toString(), "Block content is overridden in 2 templates" in baseTips)
        assertTrue(baseTips.toString(), "Block title is overridden in 2 templates" in baseTips)
        myFixture.configureFromExistingVirtualFile(file("orphan"))
        assertEmpty(myFixture.findAllGutters().mapNotNull { it.tooltipText })
    }

    fun testEnclosingBlock() {
        val text = "{% block a %}x{% block b %}y{% endblock b %}z{% endblock a %}"
        val blocks = TeraSymbolScanner.scanBlocks(text)
        assertEquals("a", TeraBlocks.enclosing(blocks, text, text.indexOf("x")))
        assertEquals("b", TeraBlocks.enclosing(blocks, text, text.indexOf("y")))
        assertEquals("a", TeraBlocks.enclosing(blocks, text, text.indexOf("z")))
        assertNull(TeraBlocks.enclosing(blocks, text, text.length))
    }
}
