package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import junit.framework.TestCase

/** Version-specific diagnostics and the Tera 1 → 2 migration rules. */
class TeraMigrationTest : TestCase() {

    private fun v2(text: String, custom: List<TeraCustomBuiltin> = emptyList()) = TeraMigration.check(text, TeraVersion.V2, custom)
    private fun v1(text: String, hints: Boolean = true) = TeraMigration.check(text, TeraVersion.V1, emptyList(), hints)

    private fun TeraError.text(src: String) = range.substring(src)
    private fun fixed(src: String, e: TeraError, i: Int = 0) = e.fixes[i].applyTo(src)
    private fun assertEmpty(c: Collection<*>) = assertTrue("expected empty but was $c", c.isEmpty())

    private fun custom(kind: String, name: String) =
        TeraCustomBuiltin(kind, BuiltinDef(name, "x", source = "main.rs:1"), null)

    // ── renamed ──────────────────────────────────────────────────────────────

    fun testRenamedFiltersAreErrorsWithAFixInTera2() {
        for ((old, new) in mapOf("as_str" to "str", "linebreaksbr" to "newlines_to_br",
            "trim_start_matches" to "trim_start", "trim_end_matches" to "trim_end")) {
            val src = "{{ x | $old }}"
            val e = v2(src).single { it.text(src) == old }
            assertEquals(HighlightSeverity.ERROR, e.severity)
            assertTrue(e.message, e.message.startsWith("Filter '$old' does not exist in Tera 2"))
            assertTrue(e.message, e.message.contains("`$new`"))
            assertEquals("{{ x | $new }}", fixed(src, e))
            assertEquals("Rename to '$new'", e.fixes.single().label)
        }
    }

    fun testRenamedTests() {
        val a = "{% if x is object %}{% endif %}"
        assertEquals("{% if x is map %}{% endif %}", fixed(a, v2(a).single { it.text(a) == "object" }))
        val b = "{% if i is divisibleby(3) %}{% endif %}"
        val e = v2(b).first { it.text(b) == "divisibleby" }
        assertEquals("{% if i is divisible_by(divisor=3) %}{% endif %}", fixed(b, e))
        assertEquals("Rename to 'divisible_by' with keyword arguments", e.fixes.single().label)
    }

    fun testRenamedTestWithoutArgumentsJustRenames() {
        val src = "{% if x is not object %}{% endif %}"
        assertEquals("{% if x is not map %}{% endif %}", fixed(src, v2(src).single()))
    }

    // ── removed ──────────────────────────────────────────────────────────────

    fun testRemovedWithoutEquivalentAreErrorsWithGuidance() {
        val cases = mapOf(
            "{{ x | date(format=\"%Y\") }}" to "tera-contrib",
            "{{ x | slugify }}" to "slug",
            "{{ x | urlencode }}" to "urlencode",
            "{{ x | json_encode }}" to "json",
            "{{ x | slice(start=1) }}" to "slicing",
            "{{ x | map(attribute=\"a\") }}" to "comprehension",
            "{{ x | filter(attribute=\"a\") }}" to "comprehension",
            "{{ x | concat(with=y) }}" to "spread",
            "{{ now() }}" to "tera-contrib",
            "{{ get_env(name=\"A\") }}" to "context",
            "{{ get_random(end=3) }}" to "rand",
            "{% if x is matching(\"a\") %}{% endif %}" to "regex",
        )
        for ((src, hint) in cases) {
            val e = v2(src).first { it.severity == HighlightSeverity.ERROR && it.message.contains("does not exist in Tera 2") }
            assertTrue("$src: ${e.message}", e.message.contains(hint))
            assertEmpty(e.fixes)
        }
    }

    fun testRegisteredReplacementSilencesTheRemovalError() {
        assertEmpty(v2("{{ x | date(format=\"%Y\") }}", listOf(custom("filter", "date"))).filter { it.message.contains("does not exist") })
        assertEquals(1, v2("{{ x | date(format=\"%Y\") }}", listOf(custom("function", "date"))).count { it.message.contains("does not exist") })
        assertEmpty(v2("{{ now() }}", listOf(custom("function", "now"))).filter { it.message.contains("does not exist") })
    }

    fun testUnknownNamesAreOnlyAWeakWarningInTera2() {
        val src = "{{ x | frobnicate }}{{ nothing() }}{% if x is weird %}{% endif %}"
        val warnings = v2(src).filter { it.message.startsWith("Unknown") }
        assertEquals(3, warnings.size)
        assertTrue(warnings.all { it.severity == HighlightSeverity.WEAK_WARNING })
        assertEquals("Unknown filter 'frobnicate' in Tera 2.x", warnings[0].message)
        assertEmpty(v2("{{ x | frobnicate }}", listOf(custom("filter", "frobnicate"))))
    }

    fun testEverythingInTheTera2CatalogIsAccepted() {
        val filters = TeraBuiltins.FILTER_DEFS_V2.joinToString("") { f ->
            val args = f.required.joinToString(", ") { "${it.name}=1" }
            "{{ x | ${f.name}${if (args.isEmpty()) "" else "($args)"} }}"
        }
        assertEmpty(v2(filters))
        val tests = TeraBuiltins.TEST_DEFS_V2.joinToString("") { t ->
            val args = t.required.joinToString(", ") { "${it.name}=1" }
            "{% if x is ${t.name}${if (args.isEmpty()) "" else "($args)"} %}{% endif %}"
        }
        assertEmpty(v2(tests))
        assertEmpty(v2("{{ range(end=3) }}{{ throw(message=\"x\") }}"))
    }

    // ── arguments ────────────────────────────────────────────────────────────

    fun testTestsTakeKeywordArgumentsInTera2() {
        val src = "{% if s is starting_with(\"a\") %}{% endif %}"
        val e = v2(src).single()
        assertEquals("Tests take keyword arguments in Tera 2: write starting_with(pat=…)", e.message)
        assertEquals("{% if s is starting_with(pat=\"a\") %}{% endif %}", fixed(src, e))
        val tooMany = "{% if s is odd(1) %}{% endif %}"
        assertEmpty(v2(tooMany).filter { it.fixes.isNotEmpty() })
    }

    fun testMissingRequiredArguments() {
        val src = "{{ x | truncate }}{{ x | replace(from=\"a\") }}{{ range() }}{% if i is divisible_by %}{% endif %}"
        val e = v2(src).filter { it.message.contains("missing required") }
        assertEquals(listOf(
            "filter 'truncate' is missing required argument(s): length",
            "filter 'replace' is missing required argument(s): to",
            "function 'range' is missing required argument(s): end",
            "test 'divisible_by' is missing required argument(s): divisor",
        ), e.map { it.message })
        assertTrue(e.all { it.severity == HighlightSeverity.ERROR })
    }

    fun testUnknownArgumentIsAWarning() {
        val src = "{{ x | upper(loud=true) }}"
        val e = v2(src).single()
        assertEquals(HighlightSeverity.WARNING, e.severity)
        assertEquals("filter 'upper' has no argument 'loud'", e.message)
        assertEquals("loud", e.text(src))
    }

    fun testArgumentsRemovedOrRenamedInTera2() {
        val indent = "{{ x | indent(prefix=\"  \") }}"
        val e = v2(indent).single()
        assertEquals(HighlightSeverity.ERROR, e.severity)
        assertEquals("{{ x | indent(indentation=\"  \") }}", fixed(indent, e))

        assertEquals("{{ x | int() }}", fixed("{{ x | int(default=0) }}", v2("{{ x | int(default=0) }}").single()))
        assertEquals("{{ x | int(base=2) }}", fixed("{{ x | int(default=0, base=2) }}", v2("{{ x | int(default=0, base=2) }}").single()))
        assertEquals("{{ x | int(base=2) }}", fixed("{{ x | int(base=2, default=0) }}", v2("{{ x | int(base=2, default=0) }}").single()))
        assertEquals("{{ x | float() }}", fixed("{{ x | float(default=1.5) }}", v2("{{ x | float(default=1.5) }}").single()))
        val unique = "{{ x | unique(attribute=\"a\") }}"
        assertEquals("{{ x | unique() }}", fixed(unique, v2(unique).first()))
    }

    fun testSameArgumentsAreFineInTera1() {
        assertEmpty(v1("{{ x | indent(prefix=\"  \") }}{{ x | int(default=0) }}", hints = false))
        assertEmpty(v1("{% if i is divisibleby(2) %}{% endif %}", hints = false))
    }

    // ── tags ─────────────────────────────────────────────────────────────────

    fun testMacrosAndImportsAreGoneInTera2() {
        val src = "{% import \"macros\" as m %}\n{% macro f(a) %}x{% endmacro f %}"
        val e = v2(src)
        val macro = e.first { it.text(src) == "macro" }
        assertEquals("Tag 'macro' does not exist in Tera 2: macros were replaced by components (component/endcomponent)", macro.message)
        assertEquals("{% import \"macros\" as m %}\n{% component f(a) %}x{% endmacro f %}", fixed(src, macro))
        val end = e.first { it.text(src) == "endmacro" }
        assertEquals("{% endcomponent f %}", fixed("{% endmacro f %}", v2("{% endmacro f %}").first { it.text("{% endmacro f %}") == "endmacro" }))
        assertNotNull(end)
        val imp = e.first { it.text(src) == "import" }
        assertEquals("{% macro f(a) %}x{% endmacro f %}", fixed(src, imp))
        assertEquals("Remove this tag", imp.fixes.single().label)
    }

    fun testFromImportIsGoneToo() {
        val src = "{% from \"m\" import a %}text"
        assertEquals("text", fixed(src, v2(src).first { it.text(src) == "from" }))
    }

    fun testIncludeIsASingleNameInTera2() {
        for (src in listOf("{% include [\"a\", \"b\"] %}", "{% include \"a\" ignore missing %}", "{% include \"a\" with context %}")) {
            assertEquals(src, 1, v2(src).count { it.message.startsWith("In Tera 2, include takes a single template name") })
        }
        assertEmpty(v2("{% include \"a\" %}"))
    }

    fun testReservedNamesCannotBeAssignedInTera2() {
        val src = "{% set loop = 1 %}{% set none = 2 %}{% set fine = 3 %}"
        assertEquals(listOf("loop", "none"), v2(src).map { it.text(src) })
        assertEmpty(v2("{% set fine = 3 %}"))
    }

    // ── loop ─────────────────────────────────────────────────────────────────

    fun testLoopFieldsPerVersion() {
        val src = "{% for x in xs %}{{ loop.index }}{{ loop.length }}{{ loop.foo }}{% endfor %}"
        assertEquals(listOf("foo"), v2(src).map { it.text(src) })
        assertEquals(listOf("length", "foo"), v1(src, false).map { it.text(src) })
        assertEquals("Invalid field of 'loop': foo. Only index, index0, first, last, length exist.", v2(src).single().message)
    }

    // ── macro calls ──────────────────────────────────────────────────────────

    fun testMacroCallBecomesAComponentCall() {
        val src = "{{ m::input(label=\"Publish Date\", type=\"date\", name=\"submission.date\") }}"
        val e = v2(src).single { it.message.startsWith("Macro calls were removed") }
        assertEquals("Macro calls were removed in Tera 2: call the component with <input .../>", e.message)
        assertEquals("{{ <input label=\"Publish Date\" type=\"date\" name=\"submission.date\"/> }}", fixed(src, e))
    }

    fun testMacroCallArgumentsKeepTheirValues() {
        val src = "{{ self::row(name=name, count=items | length, active=true, title=\"a b\") }}"
        assertEquals("{{ <row name count={items | length} active={true} title=\"a b\"/> }}", fixed(src, v2(src).single()))
        assertEquals("{{ <f s={\"a\\\"b\"}/> }}", fixed("{{ m::f(s=\"a\\\"b\") }}", v2("{{ m::f(s=\"a\\\"b\") }}").single()))
    }

    fun testUnclosedMacroCallGetsNoFix() {
        assertEmpty(v2("{{ m::input(label=\"x\"").first { it.message.startsWith("Macro calls") }.fixes)
    }

    // ── hints in Tera 1 projects ─────────────────────────────────────────────

    fun testTera1ProjectsGetStruckThroughHints() {
        val src = "{{ x | as_str }}{% macro f() %}{% endmacro %}{% import \"m\" as m %}{{ m::g(a=1) }}"
        val hints = v1(src)
        assertTrue(hints.all { it.severity == HighlightSeverity.WEAK_WARNING && it.deprecated })
        assertTrue(hints.any { it.message == "Tera 2 removes the filter 'as_str': renamed to `str`" })
        assertTrue(hints.any { it.message.startsWith("Tera 2 removes the tag 'macro'") })
        assertTrue(hints.any { it.message.startsWith("Tera 2 removes the tag 'import'") })
        assertTrue(hints.any { it.message.startsWith("Tera 2 removes the macro call 'm::g'") })
        assertTrue(hints.all { it.fixes.isNotEmpty() })
    }

    fun testHintsCanBeSwitchedOff() {
        assertEmpty(v1("{{ x | as_str }}{% macro f() %}{% endmacro %}{{ m::g(a=1) }}", hints = false))
    }

    fun testHintsAreSilencedByARegisteredReplacement() {
        val src = "{{ x | date(format=\"%Y\") }}"
        assertEquals(1, TeraMigration.check(src, TeraVersion.V1, emptyList(), true).size)
        assertEmpty(TeraMigration.check(src, TeraVersion.V1, listOf(custom("filter", "date")), true))
    }

    fun testHintsForArgumentChangesInTera1() {
        val src = "{{ x | indent(prefix=\"  \") | int(default=0) }}"
        val hints = v1(src)
        assertEquals(2, hints.size)
        assertEquals("Tera 2 changes argument 'prefix' of 'indent': renamed to `indentation` (and `width` sets a count of spaces)", hints[0].message)
    }

    // ── syntax only Tera 2 has ───────────────────────────────────────────────

    fun testTera2OnlySyntaxIsAnErrorInTera1() {
        val cases = mapOf(
            "{% component c() %}x{% endcomponent %}" to listOf("`component`", "`endcomponent`"),
            "{% set x %}body{% endset %}" to listOf("Set block", "`endset`"),
            "{{ <c a=\"1\"/> }}{% <c> %}b{% </c> %}" to listOf("Component call", "Component call"),
            "{{ a < b }}" to emptyList(),
            "{{ \"a\" if x else \"b\" }}" to listOf("Ternary expression"),
            "{{ a?.b }}{{ a?[0] }}" to listOf("Optional chaining", "Optional chaining"),
            "{{ {...m} }}" to listOf("Spread"),
            "{{ xs[1:2] }}{{ xs[::2] }}" to listOf("Slicing", "Slicing"),
            "{{ [x for x in xs] }}" to listOf("List comprehension"),
        )
        for ((src, what) in cases) {
            val found = v1(src, false).filter { it.severity == HighlightSeverity.ERROR }
            assertEquals(src, what, found.map { it.message.substringBefore(" needs Tera 2") })
            assertTrue(found.all { it.message.endsWith("needs Tera 2, but this project uses Tera 1.x") || it.message.contains("needs Tera 2") })
        }
    }

    fun testTera2OnlySyntaxIsFineInTera2() {
        val src = """
            {% component c(a, b = 1, ...rest) %}{{ body }}{% endcomponent c %}
            {% set x %}body{% endset %}
            {{ <c a="1" b={2}/> }}{% <c a="1"> %}b{% </c> %}
            {{ "a" if x else "b" }}{{ a?.b }}{{ a?[0] }}{{ {...m} }}{{ xs[1:2] }}{{ xs[::2] }}{{ [x for x in xs if x] }}
        """.trimIndent()
        assertEmpty(v2(src))
    }

    fun testExistingTera1SyntaxIsNotMistakenForTera2Syntax() {
        val src = "{{ m::input(a=1) }}{{ xs | join(sep=\",\") }}{% if a == b %}{% endif %}{{ a ~ b }}"
        assertEmpty(v1(src, false))
    }

    // ── whole file ───────────────────────────────────────────────────────────

    fun testMigrateRewritesAFileForTera2() {
        val src = """
            {% import "macros" as m %}
            {% macro badge(label, count="0") %}<b>{{ label | linebreaksbr }}</b>{% endmacro badge %}
            {% for x in xs %}
              {{ m::badge(label=x.name, count="3") }}
              {% if loop.index is divisibleby(2) %}{{ x.v | as_str | indent(prefix="  ") }}{% endif %}
            {% endfor %}
        """.trimIndent()
        val r = TeraMigration.migrate(src)
        assertEquals("""
            {% component badge(label, count="0") %}<b>{{ label | newlines_to_br }}</b>{% endcomponent badge %}
            {% for x in xs %}
              {{ <badge label={x.name} count="3"/> }}
              {% if loop.index is divisible_by(divisor=2) %}{{ x.v | str | indent(indentation="  ") }}{% endif %}
            {% endfor %}
        """.trimIndent(), r.text)
        assertTrue(r.applied >= 7)
        assertEmpty(r.remaining)
    }

    fun testMigrateReportsWhatNeedsAHuman() {
        val r = TeraMigration.migrate("{{ x | date(format=\"%Y\") }}{{ y | upper }}")
        assertEquals("{{ x | date(format=\"%Y\") }}{{ y | upper }}", r.text)
        assertEquals(1, r.remaining.size)
        assertTrue(r.remaining.single().message.contains("tera-contrib"))
    }

    fun testMigrateLeavesATera2FileAlone() {
        val src = "{% component c(a) %}{{ a | str }}{% endcomponent c %}{{ <c a={1}/> }}"
        val r = TeraMigration.migrate(src)
        assertEquals(src, r.text)
        assertEquals(0, r.applied)
        assertEmpty(r.remaining)
    }

    fun testMigrationIsIdempotent() {
        val src = "{% macro f(a) %}{{ a | as_str }}{% endmacro %}{{ self::f(a=1) }}{% if i is divisibleby(2) %}x{% endif %}"
        val once = TeraMigration.migrate(src).text
        assertEquals(once, TeraMigration.migrate(once).text)
    }

    fun testFixApplicationIgnoresOverlappingEdits() {
        val edits = listOf(TeraEdit(com.intellij.openapi.util.TextRange(0, 5), "A"), TeraEdit(com.intellij.openapi.util.TextRange(3, 8), "B"))
        assertEquals("helBrld", TeraFix.applyEdits("hello world", edits))  // the later edit wins, the overlapping earlier one is skipped
    }
}
