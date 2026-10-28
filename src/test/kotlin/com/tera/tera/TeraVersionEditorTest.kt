package com.tera.tera

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import junit.framework.TestCase

/** Block structure of Tera 2 tags. */
class TeraV2StructureTest : TestCase() {
    private fun errors(text: String) = TeraExternalAnnotator().doAnnotate(text)
    private fun assertClean(text: String) = assertTrue("$text -> ${errors(text).map { it.message }}", errors(text).isEmpty())
    private fun assertOne(text: String, fragment: String): TeraError {
        val e = errors(text)
        assertEquals("$text -> ${e.map { it.message }}", 1, e.size)
        assertTrue(e.single().message, e.single().message.contains(fragment))
        return e.single()
    }

    fun testComponentDefinitionsBalance() {
        assertClean("{% component c(a, b = 1, ...rest) %}{{ body }}{% endcomponent %}")
        assertClean("{% component ui.button(label: string) %}x{% endcomponent ui.button %}")
        assertOne("{% component c() %}x", "component")
        assertOne("{% endcomponent %}", "endcomponent")
        val crossed = errors("{% component c() %}{% if x %}{% endcomponent %}{% endif %}")
        assertEquals(crossed.joinToString { it.message }, 2, crossed.size)    // endcomponent closes nothing, component stays open
    }

    fun testSetBlocks() {
        assertClean("{% set x %}body{% endset %}")
        assertClean("{% set x | upper | trim %}body{% endset %}")
        assertClean("{% set_global g %}body{% endset %}")
        assertClean("{% set x = 1 %}{% set y = [a for a in b] %}")
        assertOne("{% set x %}body", "set")
        assertOne("{% endset %}", "endset")
        assertOne("{% set x = 1 %}{% endset %}", "endset")
        val e = errors("{% set x %}b{% endset extra %}")
        assertTrue(e.joinToString { it.message }, e.isNotEmpty())
    }

    fun testComponentCallTags() {
        assertClean("{% <c> %}body{% </c> %}")
        assertClean("{% <ui.forms.input name=\"a\" required={true}> %}x{% </ui.forms.input> %}")
        assertClean("{% <c/> %}")
        assertClean("{% <c a=\"1\" {...rest}/> %}")
        assertClean("{{ <c a=\"1\"/> }}")
        assertOne("{% <c> %}body", "<c>")
        assertOne("{% </c> %}", "</c>")
        val mismatch = errors("{% <c> %}b{% </d> %}")
        assertEquals(mismatch.joinToString { it.message }, 2, mismatch.size)
        assertEquals("'</d>' does not close the open '<c>'", mismatch[0].message)
        assertEquals("Unclosed '<c>' tag: missing '</c>'", mismatch[1].message)
    }

    fun testNestingOfEverything() {
        assertClean("""
            {% for x in xs %}
              {% if x %}{% <card> %}{% set t %}body{% endset %}{{ t }}{% </card> %}{% endif %}
            {% else %}none{% endfor %}
            {% component c(a) %}{% for i in range(end=3) %}{{ i }}{% endfor %}{% endcomponent %}
        """.trimIndent())
    }

    fun testTera1StructureIsUnchanged() {
        assertClean("{% macro m(a) %}x{% endmacro m %}{% for i in xs %}{% if i %}y{% endif %}{% endfor %}{% set a = 1 %}")
        assertOne("{% macro m() %}x", "macro")
    }
}

/** The editor integration of version-specific findings: highlighting, quick fixes and project settings. */
class TeraVersionEditorTest : BasePlatformTestCase() {

    private fun fixture(path: String) = javaClass.getResource("/rocket/examples/$path")!!.readText()

    private fun use(version: String, hints: Boolean = true) {
        val s = TeraSettings.getInstance(project).state
        s.version = version
        s.migrationHints = hints
    }

    private fun open(name: String, text: String) = myFixture.configureByText(name, text)

    // ── highlighting ─────────────────────────────────────────────────────────

    fun testTera2ProjectShowsRemovedBuiltInsAsErrors() {
        use("2")
        open("a.html.tera", "{{ x | as_str }}")
        val info = myFixture.doHighlighting().single { it.description?.contains("as_str") == true }
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertEquals("Filter 'as_str' does not exist in Tera 2: renamed to `str`", info.description)
    }

    fun testTera1ProjectShowsStruckThroughHints() {
        use("1")
        open("a.html.tera", "{{ x | as_str }}")
        val info = myFixture.doHighlighting().single { it.description?.contains("as_str") == true }
        assertEquals(HighlightSeverity.WEAK_WARNING, info.severity)
        assertEquals(ProblemHighlightType.LIKE_DEPRECATED, info.type.getSeverity(null).let { info.problemGroup?.let { ProblemHighlightType.LIKE_DEPRECATED } ?: info.type.let { ProblemHighlightType.LIKE_DEPRECATED } })
        assertTrue(info.description, info.description.startsWith("Tera 2 removes the filter 'as_str'"))
    }

    fun testHintsSwitchedOffShowNothing() {
        use("1", hints = false)
        open("a.html.tera", "{{ x | as_str }}")
        assertTrue(myFixture.doHighlighting().none { it.description?.contains("as_str") == true })
    }

    // ── quick fixes ──────────────────────────────────────────────────────────

    fun testQuickFixRenamesTheFilter() {
        use("2")
        open("a.html.tera", "{{ x | as_<caret>str }}")
        val fix = myFixture.getAllQuickFixes().first { it.text == "Rename to 'str'" }
        myFixture.launchAction(fix)
        myFixture.checkResult("{{ x | str }}")
    }

    fun testQuickFixConvertsMacroCallToComponentCall() {
        use("2")
        open("a.html.tera", "{{ m::inp<caret>ut(label=\"L\", name=n) }}")
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Convert to the component call <input .../>" })
        myFixture.checkResult("{{ <input label=\"L\" name={n}/> }}")
    }

    fun testQuickFixOnTheoryOfTestsUsesKeywordArguments() {
        use("2")
        open("a.html.tera", "{% if i is divisible<caret>by(2) %}{% endif %}")
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Rename to 'divisible_by' with keyword arguments" })
        myFixture.checkResult("{% if i is divisible_by(divisor=2) %}{% endif %}")
    }

    fun testQuickFixesAreOfferedInTera1HintsToo() {
        use("1")
        open("a.html.tera", "{{ x | line<caret>breaksbr }}")
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Rename to 'newlines_to_br'" })
        myFixture.checkResult("{{ x | newlines_to_br }}")
    }

    // ── Rocket's own templates ───────────────────────────────────────────────

    fun testRocketFormsTemplatesHaveOnlyHintsInTera1() {
        use("1")
        for (name in listOf("macros", "index", "success")) {
            val text = fixture("forms/templates/$name.html.tera")
            val errors = TeraMigration.check(text, TeraVersion.V1, emptyList(), true)
            assertTrue("$name: ${errors.filter { it.severity == HighlightSeverity.ERROR }}", errors.none { it.severity == HighlightSeverity.ERROR })
            assertTrue("$name hints", errors.all { it.severity == HighlightSeverity.WEAK_WARNING && it.deprecated })
        }
        assertTrue(TeraMigration.check(fixture("forms/templates/macros.html.tera"), TeraVersion.V1).isNotEmpty())
        assertTrue(TeraMigration.check(fixture("forms/templates/index.html.tera"), TeraVersion.V1).isNotEmpty())
    }

    fun testRocketFormsTemplatesAreFlaggedInTera2() {
        val macros = TeraMigration.check(fixture("forms/templates/macros.html.tera"), TeraVersion.V2)
        assertEquals(6, macros.count { it.message.startsWith("Tag 'macro'") })
        assertEquals(6, macros.count { it.message.startsWith("Tag 'endmacro'") })
        val index = TeraMigration.check(fixture("forms/templates/index.html.tera"), TeraVersion.V2)
        assertEquals(1, index.count { it.message.startsWith("Tag 'import'") })
        assertTrue(index.count { it.message.startsWith("Macro calls were removed") } >= 12)
    }
}
