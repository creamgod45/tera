package com.tera.tera

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Functions, filters and tests registered by project code, as a provider reports them. */
class TeraCustomBuiltinsTest : BasePlatformTestCase() {

    /** These tests describe Tera 1.x behaviour (Rocket's engine); 2.x has its own tests. */
    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).state.version = "1"
    }

    private fun custom(kind: String, name: String, description: String, args: List<String> = emptyList(),
                       doc: String? = null, source: String = "main.rs:7", target: PsiElement? = null) =
        TeraCustomBuiltin(kind, BuiltinDef(name, description,
            args.map { BuiltinParam(it, false, null, "argument read by the Rust code") },
            positional = kind == "test", source = source, details = doc), target)

    private fun install(vararg items: TeraCustomBuiltin) {
        val list = items.toList()
        val provider = object : TeraContextProvider {
            override fun resolve(project: com.intellij.openapi.project.Project, template: com.intellij.openapi.vfs.VirtualFile) = null
            override fun customBuiltins(project: com.intellij.openapi.project.Project) = list
        }
        TeraContextProvider.EP.point.registerExtension(provider, testRootDisposable)
    }

    private val urlFor = custom("function", "url_for", "Builds an absolute URL.", listOf("name", "query"),
        doc = "Builds an absolute URL.\nUses the route name.")
    private val shorten = custom("filter", "shorten", "registered in Rust (main.rs:9)", listOf("length"))
    private val blank = custom("test", "blank", "registered in Rust (main.rs:11)")

    private fun suggest(textWithCaret: String, custom: List<TeraCustomBuiltin>): List<TeraSuggestion> {
        val caret = textWithCaret.indexOf("<caret>")
        return TeraCompletionEngine.suggest(textWithCaret.replace("<caret>", ""), caret, { null }, { emptyList() }, null, custom)
    }

    // ── completion ───────────────────────────────────────────────────────────

    fun testCustomItemsAreOfferedNextToBuiltIns() {
        val all = listOf(urlFor, shorten, blank)
        val fn = suggest("{{ <caret> }}", all)
        assertTrue(fn.map { it.text }.containsAll(listOf("range", "now", "url_for")))
        assertEquals("function", fn.first { it.text == "url_for" }.type)
        assertEquals("Builds an absolute URL.", fn.first { it.text == "url_for" }.tail)
        assertTrue(suggest("{{ x | <caret> }}", all).map { it.text }.containsAll(listOf("upper", "shorten")))
        assertTrue(suggest("{% if x is <caret> %}{% endif %}", all).map { it.text }.containsAll(listOf("odd", "blank")))
    }

    fun testCustomFunctionsDoNotLeakIntoOtherKinds() {
        val all = listOf(urlFor, shorten, blank)
        assertFalse(suggest("{{ x | <caret> }}", all).any { it.text == "url_for" || it.text == "blank" })
        assertFalse(suggest("{% if x is <caret> %}{% endif %}", all).any { it.text == "shorten" })
        assertFalse(suggest("{{ <caret> }}", all).any { it.text == "shorten" })
    }

    fun testCustomArgumentNamesInsideTheCall() {
        val all = listOf(urlFor, shorten)
        assertEquals(listOf("name", "query"), suggest("{{ url_for(<caret>) }}", all).map { it.text })
        assertEquals(listOf("query"), suggest("{{ url_for(name=\"home\", <caret>) }}", all).map { it.text })
        assertEquals(listOf("length"), suggest("{{ title | shorten(<caret>) }}", all).map { it.text })
        assertTrue(suggest("{{ url_for(<caret>) }}", all).all { it.suffix == "=" && it.tail == "default none" })
    }

    fun testWithoutDetectedArgumentsThereAreNoArgumentSuggestions() {
        val noArgs = custom("function", "year", "registered in Rust (main.rs:3)")
        assertEmpty(suggest("{{ year(<caret>) }}", listOf(noArgs)))
        assertEquals("()", suggest("{{ <caret> }}", listOf(noArgs)).first { it.text == "year" }.suffix)
    }

    fun testCustomOverridesABuiltinOfTheSameName() {
        val myRange = custom("function", "range", "My range", listOf("upto"))
        val names = suggest("{{ <caret> }}", listOf(myRange)).filter { it.text == "range" }
        assertEquals(1, names.size)
        assertEquals("My range", names.single().tail)
        assertEquals(listOf("upto"), suggest("{{ range(<caret>) }}", listOf(myRange)).map { it.text })
    }

    fun testNothingChangesWithoutCustomBuiltins() {
        assertEquals(TeraBuiltins.FILTERS.keys.toList(), suggest("{{ x | <caret> }}", emptyList()).map { it.text })
        assertEquals(TeraBuiltins.TESTS.keys.toList(), suggest("{% if x is <caret> %}{% endif %}", emptyList()).map { it.text })
    }

    // ── hover ────────────────────────────────────────────────────────────────

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&amp;", "&").replace(Regex("\\s+"), " ").replace(" :", ":").trim()

    private fun hover(name: String, textWithCaret: String): String? {
        val caret = textWithCaret.indexOf("<caret>")
        val file = myFixture.addFileToProject("templates/$name", textWithCaret.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        if (IdeDocumentationTargetProvider.getInstance(project).documentationTargets(myFixture.editor, file, caret).isEmpty()) return null
        val provider = TeraDocumentationProvider()
        val element = provider.getCustomDocumentationElement(myFixture.editor, file, null, caret) ?: return null
        return provider.generateDoc(element, null)?.let(::plain)
    }

    fun testHoverCustomFunctionShowsDocCommentArgumentsAndSource() {
        install(urlFor)
        val doc = hover("a.html.tera", "{{ url<caret>_for(name=\"home\") }}")!!
        assertTrue(doc, doc.contains("(function) url_for(name=…, query=…)"))
        assertTrue(doc, doc.contains("Builds an absolute URL. Uses the route name."))
        assertTrue(doc, doc.contains("name – optional – argument read by the Rust code"))
        assertTrue(doc, doc.contains("Source: registered in Rust (main.rs:7)"))
    }

    fun testHoverCustomFilterAndTest() {
        install(shorten, blank)
        val f = hover("b.html.tera", "{{ title | short<caret>en(length=5) }}")!!
        assertTrue(f, f.contains("(filter) shorten(length=…)"))
        assertTrue(f, f.contains("Source: registered in Rust"))
        val t = hover("c.html.tera", "{% if x is bl<caret>ank %}{% endif %}")!!
        assertTrue(t, t.contains("(test) blank"))
    }

    fun testHoverCustomOverrideReplacesTheBuiltInDescription() {
        install(custom("function", "range", "My range", listOf("upto"), doc = "My own range."))
        val doc = hover("d.html.tera", "{{ ra<caret>nge(upto=3) }}")!!
        assertTrue(doc, doc.contains("(function) range(upto=…)"))
        assertTrue(doc, doc.contains("My own range."))
        assertFalse(doc, doc.contains("step_by"))
    }

    fun testHoverBuiltInsAreUnchangedWhenOthersAreRegistered() {
        install(urlFor)
        val doc = hover("e.html.tera", "{{ ra<caret>nge(end=3) }}")!!
        assertTrue(doc, doc.contains("(function) range(end, start=0, step_by=1)"))
        assertTrue(doc, doc.contains("Source: Tera built-in function"))
    }

    // ── goto ─────────────────────────────────────────────────────────────────

    fun testGotoCustomFunctionLandsOnItsRustDefinition() {
        val rs = myFixture.addFileToProject("src/main.rs", "fn url_for() {}")
        val leaf = rs.findElementAt(rs.text.indexOf("url_for"))!!
        install(custom("function", "url_for", "x", target = leaf))
        val text = "{{ url<caret>_for(name=1) }}"
        val file = myFixture.addFileToProject("templates/g.html.tera", text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val caret = text.indexOf("<caret>")
        myFixture.editor.caretModel.moveToOffset(caret)
        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, caret).toList()
        assertEquals(listOf(leaf), targets)
    }

    fun testGotoBuiltInHasNoTarget() {
        install(urlFor)
        val text = "{{ ra<caret>nge(end=1) }}"
        val file = myFixture.addFileToProject("templates/h.html.tera", text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val caret = text.indexOf("<caret>")
        myFixture.editor.caretModel.moveToOffset(caret)
        assertEmpty(GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, caret).toList())
    }

    // ── caching and robustness ───────────────────────────────────────────────

    fun testProviderFailuresAreIgnored() {
        val broken = object : TeraContextProvider {
            override fun resolve(project: com.intellij.openapi.project.Project, template: com.intellij.openapi.vfs.VirtualFile) = null
            override fun customBuiltins(project: com.intellij.openapi.project.Project): List<TeraCustomBuiltin> =
                throw NoClassDefFoundError("org/rust/Missing")
        }
        TeraContextProvider.EP.point.registerExtension(broken, testRootDisposable)
        install(urlFor)
        com.intellij.testFramework.LoggedErrorProcessor.executeWith<RuntimeException>(
            object : com.intellij.testFramework.LoggedErrorProcessor() {
                override fun processWarn(category: String, message: String, t: Throwable?): Boolean = false
            },
        ) { assertEquals(listOf("url_for"), TeraContexts.customBuiltins(project).map { it.def.name }) }
    }

    fun testEditorCompletionInsertsRequiredArguments() {
        install(custom("filter", "wrap", "x", listOf("with")))
        val file = myFixture.addFileToProject("templates/i.html.tera", "{{ title | wra }}")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf(" }}"))
        myFixture.completeBasic()
        // a single match is inserted directly; arguments the code only reads optionally add no parentheses
        assertEquals("{{ title | wrap }}", myFixture.editor.document.text)
    }
}
