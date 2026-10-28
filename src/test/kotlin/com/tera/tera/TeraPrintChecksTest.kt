package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `{{ list }}` and `{{ object }}` print what the engines really print (checked against Tera 1.20.1 and 2.4.0). */
class TeraPrintChecksTest : BasePlatformTestCase() {

    private fun s(n: String) = TeraType.Scalar(n)

    private val context = TeraContextResult(listOf(
        TeraField("categories", TeraType.ArrayOf(s("string"))),
        TeraField("counts", TeraType.MapOf(s("number"))),
        TeraField("user", TeraType.Struct("User", listOf(TeraField("id", s("number")), TeraField("name", s("string"))))),
        TeraField("title", s("string")),
        TeraField("rows", TeraType.ArrayOf(TeraType.Struct("Row", listOf(TeraField("label", s("string")))))),
    ), complete = true)

    private fun printed(text: String, v: TeraVersion = TeraVersion.V2) = TeraPrintChecks.printed(text, context, v)

    fun testAListIsPointedOutWithTheRealOutputOfTheVersion() {
        val t2 = printed("<p>{{ categories }}</p>").single()
        assertEquals("'categories' is a list: it is printed as JSON", t2.message)
        assertEquals(HighlightSeverity.WEAK_WARNING, t2.severity)
        val t1 = printed("<p>{{ categories }}</p>", TeraVersion.V1).single()
        assertTrue(t1.message, t1.message.contains("[a, b]"))
    }

    fun testAnObjectIsAWarningInTera1BecauseItPrintsObject() {
        val t1 = printed("{{ user }}", TeraVersion.V1).single()
        assertEquals("'user' is an object: Tera 1 prints it as [object]", t1.message)
        assertEquals(HighlightSeverity.WARNING, t1.severity)
        assertEquals(HighlightSeverity.WEAK_WARNING, printed("{{ counts }}").single().severity)
    }

    fun testOnlyWholeListsAndObjectsAreReported() {
        assertEmpty(printed("{{ title }} {{ categories | length }} {{ categories | join(sep=\", \") }} {{ user.name }} {{ rows | first }}"))
        assertEmpty(printed("{% for c in categories %}{{ c }}{% endfor %} {% if categories %}x{% endif %}"))
        assertEmpty(printed("{{ unknown }} {{ user.id }}"))
    }

    fun testListsAfterFiltersThatKeepThemAreReportedWithTheWholeChain() {
        val e = printed("{{ categories | sort | reverse }}").single()
        assertTrue(e.message, e.message.startsWith("'categories | sort | reverse' is a list"))
    }

    fun testQuickFixesLoopJoinAndPrintAField() {
        val text = "<p>{{ categories }}</p> <b>{{ user }}</b>"
        val fixes = printed(text).associate { it.message.substringBefore(" is") to it.fixes }
        val loop = fixes.getValue("'categories'").first { it.label.startsWith("for category") }
        assertEquals("<p>{% for category in categories %}{{ category }}{% endfor %}</p> <b>{{ user }}</b>", loop.applyTo(text))
        val join = fixes.getValue("'categories'").first { it.label.contains("join") }
        assertEquals("<p>{{ categories | join(sep=\", \") }}</p> <b>{{ user }}</b>", join.applyTo(text))
        val field = fixes.getValue("'user'").first { it.label == "Print user.name" }
        assertEquals("<p>{{ categories }}</p> <b>{{ user.name }}</b>", field.applyTo(text))
    }

    fun testNothingWithoutAContext() {
        assertEmpty(TeraPrintChecks.printed("{{ categories }}", null, TeraVersion.V2))
    }

    // ── the unfinished filter ────────────────────────────────────────────────

    fun testATrailingBarIsAnErrorWithAFix() {
        val text = "{{ categories | values |  }}"
        val e = TeraPrintChecks.dangling(text).single()
        assertEquals("A filter name is expected after '|'", e.message)
        assertEquals(HighlightSeverity.ERROR, e.severity)
        assertEquals("{{ categories | values  }}", e.fixes.single().applyTo(text))
        assertEquals(text.lastIndexOf('|'), e.range.startOffset)
    }

    fun testABarFollowedByNothingUseful() {
        assertEquals(1, TeraPrintChecks.dangling("{{ a | | upper }}").size)
        assertEmpty(TeraPrintChecks.dangling("{{ a | upper | lower(x=1) }} {% if a | length > 1 %}x{% endif %}"))
    }

    fun testTheAnnotatorReportsBothWithoutAnyContext() {
        val errors = TeraExternalAnnotator().doAnnotate("{{ x | }}")
        assertTrue(errors.toString(), errors.any { it.message.startsWith("A filter name is expected") })
    }

    fun testThePrintWarningCanBeSwitchedOff() {
        val text = "{{ categories }}"
        val on = TeraExternalAnnotator().doAnnotate(TeraAnnotatorInput(text, emptyMap(), context, TeraVersion.V2))
        val off = TeraExternalAnnotator().doAnnotate(TeraAnnotatorInput(text, emptyMap(), context, TeraVersion.V2, printWarnings = false))
        assertTrue(on.any { it.message.contains("is a list") })
        assertTrue(off.none { it.message.contains("is a list") })
    }

    fun testEveryTemplateOfTheRocketExamplesStaysQuiet() {
        // No Rust context here: the check needs one, and the dangling check must not fire on real templates.
        val dir = java.io.File("src/test/resources/rocket/examples")
        for (f in dir.walkTopDown().filter { it.isFile && it.name.endsWith(".tera") }) {
            assertEmpty(f.name, TeraPrintChecks.dangling(f.readText()))
        }
    }
}
