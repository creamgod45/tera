package com.tera.tera

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Comment, brace matching, auto-closing delimiters and folding: the editor basics of a template language. */
class TeraEditingTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
    }

    private fun open(text: String) = myFixture.configureByText("t.html.tera", text)

    // ── comment ─────────────────────────────────────────────────────────────

    fun testCommentOutATagWithTeraComments() {
        open("<p>{% if <caret>x %}yes{% endif %}</p>")
        myFixture.performEditorAction("CommentByBlockComment")
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("{# "))
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains(" #}"))
    }

    // ── braces ──────────────────────────────────────────────────────────────

    fun testTheDelimitersMatch() {
        val file = open("{{ x }}")
        // The platform's own matching walks the editor highlighter; what is ours to get right is the registration and pairs.
        for (open in listOf(TeraTokenTypes.VARIABLE_START, TeraTokenTypes.TAG_START, TeraTokenTypes.COMMENT_START)) {
            assertTrue("$open", BraceMatchingUtil.getBraceMatcher(file.fileType, open) is com.intellij.codeInsight.highlighting.BraceMatcher)
        }
        assertEquals(3, TeraBraceMatcher().pairs.size)
        assertTrue(TeraBraceMatcher().pairs.all { it.leftBraceType != it.rightBraceType })
    }

    // ── auto-close ──────────────────────────────────────────────────────────

    fun testTypingTheOpeningDelimitersCompletesThePair() {
        open("<p><caret></p>")
        myFixture.type("{{")
        myFixture.checkResult("<p>{{ <caret> }}</p>")
        open("<p><caret></p>")
        myFixture.type("{%")
        myFixture.checkResult("<p>{% <caret> %}</p>")
        open("<p><caret></p>")
        myFixture.type("{#")
        myFixture.checkResult("<p>{# <caret> #}</p>")
    }

    fun testASingleBraceIsLeftAlone() {
        open("a<caret>")
        myFixture.type("{")
        myFixture.checkResult("a{<caret>")
    }

    fun testATypedClosedPairIsNotDoubled() {
        open("<p>{{  }}<caret></p>")
        myFixture.type("x")
        myFixture.checkResult("<p>{{  }}x<caret></p>")
    }

    fun testBackspaceRemovesBothHalvesOfAnEmptyPair() {
        open("<p><caret></p>")
        myFixture.type("{{")
        myFixture.checkResult("<p>{{ <caret> }}</p>")
        myFixture.performEditorAction("EditorBackSpace")      // the space
        myFixture.performEditorAction("EditorBackSpace")      // the second `{` and the closer
        myFixture.checkResult("<p>{<caret></p>")
    }

    fun testInsideJavaScriptTheBraceThatTheEditorAddedIsReused() {
        myFixture.configureByText("t.html.tera", "<script>var a = <caret></script>")
        myFixture.type("{{")
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("{{  }}") && !text.contains("}}}") && !text.contains("{{ }}}"))
    }

    // ── folding ─────────────────────────────────────────────────────────────

    private fun regions(text: String): List<Pair<String, String>> {
        val file = open(text)
        val doc = myFixture.editor.document
        return TeraFoldingBuilder().buildFoldRegions(file.viewProvider.getPsi(TeraLanguage)!!, doc, false)
            .map { doc.getText(it.range).lines().first() to it.placeholderText.orEmpty() }
    }

    fun testBlocksAndLoopsFold() {
        val found = regions("""
            {% block content %}
              {% for x in xs %}
                {{ x }}
              {% endfor %}
            {% endblock content %}
        """.trimIndent())
        assertEquals(setOf("{% block content %}" to "{% block content %}…", "{% for x in xs %}" to "{% for x in xs %}…"), found.toSet())
    }

    fun testIfWithElseFoldsOnceAndATrimMarkerCountsAsPartOfTheTag() {
        val found = regions("{%- if a -%}\n x\n{% else %}\n y\n{%- endif -%}\n")
        assertEquals(1, found.size)
        assertEquals("{%- if a -%}…", found.single().second)
    }

    fun testCapturingSetAndComponentsFold() {
        val found = regions("{% set x %}\n a\n{% endset %}\n{% component c() %}\n b\n{% endcomponent %}\n{% set y = 1 %}\n")
        assertEquals(listOf("{% set x %}", "{% component c() %}"), found.map { it.first })
    }

    fun testComponentBodyTagsFold() {
        val found = regions("{% <ui.card title=\"a\"> %}\n body\n{% </ui.card> %}\n")
        assertEquals(1, found.size)
    }

    fun testUnclosedAndSingleLineBlocksDoNotFold() {
        assertEquals(0, regions("{% if a %}\nno end\n").size)
        assertEquals(0, regions("{% if a %}x{% endif %}\n").size)
        assertEquals(0, regions("{% endfor %}\n{% for x in y %}\nz\n").size)
    }
}

class TeraLiveTemplatesTest : BasePlatformTestCase() {

    private fun applicable(version: String): Set<String> {
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = version
        myFixture.configureByText("t.html.tera", "<p><caret></p>")
        val ctx = com.intellij.codeInsight.template.TemplateActionContext.expanding(myFixture.file, myFixture.editor)
        return com.intellij.codeInsight.template.impl.TemplateManagerImpl.listApplicableTemplates(ctx)
            .filter { it.groupName == "Tera" }.map { it.key }.toSet()
    }

    /** Starts the template as Tab would and finishes the session, returning the text. */
    private fun expand(key: String, version: String): String {
        applicable(version)
        val template = com.intellij.codeInsight.template.impl.TemplateSettings.getInstance().getTemplate(key, "Tera")!!
        myFixture.editor.caretModel.moveToOffset(3)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.codeInsight.template.TemplateManager.getInstance(project).startTemplate(myFixture.editor, template)
            com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        }
        return myFixture.editor.document.text
    }

    fun testTheCommonTemplatesAreThereInBothVersions() {
        val common = setOf("xx", "block", "inblock", "if", "ifi", "ifelse", "elif", "else", "forloop", "forkv", "forelse",
            "extend", "include", "filter", "raw", "comment", "super", "set", "setglobal")
        for (v in listOf("1", "2")) assertTrue(v, applicable(v).containsAll(common))
    }

    fun testMacroAndImportAreTera1Only() {
        assertTrue(applicable("1").containsAll(listOf("macro", "import")))
        assertTrue(applicable("2").none { it == "macro" || it == "import" })
    }

    fun testComponentsAndTernaryAreTera2Only() {
        val v2 = setOf("component", "comp", "compbody", "setblock", "ternary", "break", "continue")
        assertTrue(applicable("2").containsAll(v2))
        assertTrue(applicable("1").none { it in v2 })
    }

    private fun applicableAt(text: String, version: String = "2"): Set<String> {
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = version
        myFixture.configureByText("u.html.tera", text)
        val ctx = com.intellij.codeInsight.template.TemplateActionContext.expanding(myFixture.file, myFixture.editor)
        return com.intellij.codeInsight.template.impl.TemplateManagerImpl.listApplicableTemplates(ctx)
            .filter { it.groupName == "Tera" }.map { it.key }.toSet()
    }

    fun testStatementsAreNotOfferedInsideADelimiter() {
        for (text in listOf("{{ categories | values | <caret> }}", "{% if <caret> %}", "{# <caret> #}")) {
            val keys = applicableAt(text)
            assertTrue(text, keys.none { it in setOf("forloop", "forkv", "if", "block", "xx", "component") })
        }
        assertTrue(applicableAt("{{ a }} <caret>").contains("forkv"))
    }

    fun testComprehensionIsOfferedInsideAnExpressionOnly() {
        assertTrue(applicableAt("{{ <caret> }}").contains("comprehension"))
        assertFalse(applicableAt("<p><caret></p>").contains("comprehension"))
        assertFalse(applicableAt("{{ <caret> }}", "1").contains("comprehension"))
    }

    fun testExpansions() {
        assertTrue(expand("forloop", "2").contains("{% for item in list %}") && expand("forloop", "2").contains("{% endfor %}"))
        assertTrue(expand("block", "1").contains("{% block name %}") && expand("block", "1").contains("{% endblock name %}"))
        assertTrue(expand("extend", "1").contains("{% extends \"base.html\" %}"))
        assertTrue(expand("compbody", "2").contains("{% <ui.name > %}") || expand("compbody", "2").contains("{% </ui.name> %}"))
        assertTrue(expand("ternary", "2").contains("a if condition else b"))
    }
}
