package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import junit.framework.TestCase

class TeraTypeRulesTest : TestCase() {
    private fun kind(text: String) = TeraTypeRules.literalKind(TeraSymbolScanner.scanBlocks("{{ $text }}").single().tokens)

    fun testLiteralKinds() {
        assertEquals("string", kind("\"a\""))
        assertEquals("string", kind("'a'"))
        assertEquals("integer", kind("3"))
        assertEquals("integer", kind("-3"))
        assertEquals("float", kind("1.5"))
        assertEquals("float", kind("-1.5"))
        assertEquals("bool", kind("true"))
        assertEquals("bool", kind("False"))
        assertEquals("none", kind("none"))
        assertEquals("array", kind("[1, 2]"))
        assertEquals("map", kind("{\"a\": 1}"))
        for (not in listOf("x", "x.y", "1 + 2", "f(1)", "[1][0]", "\"a\" ~ \"b\"")) assertNull(not, kind(not))
    }

    fun testLiteralKindsOfDefaultText() {
        for ((t, k) in mapOf("\"x\"" to "string", "-1" to "integer", "2.5" to "float", "true" to "bool", "[1]" to "array",
            "{}" to "map", "None" to "none", "foo" to null)) assertEquals(t, k, TeraTypeRules.literalKindOfText(t))
    }

    /** Mirrors `Type::matches_value` in tera 2.4.0: an integer is not a float and a float is not an integer. */
    fun testMatching() {
        val kinds = listOf("string", "bool", "integer", "float", "array", "map", "bytes")
        val expected = mapOf(
            "string" to setOf("string"), "bool" to setOf("bool"), "integer" to setOf("integer"), "float" to setOf("float"),
            "number" to setOf("integer", "float"), "array" to setOf("array"), "map" to setOf("map"), "bytes" to setOf("bytes"),
        )
        for ((declared, ok) in expected) for (k in kinds) assertEquals("$declared <- $k", k in ok, TeraTypeRules.matches(declared, k))
    }

    private fun param(type: String?, default: String?) =
        ComponentParam("p", type, default, implicit = false, rest = false, range = com.intellij.openapi.util.TextRange(0, 1))

    fun testEffectiveTypeIsTheAnnotationElseInferredFromTheDefault() {
        assertEquals("string", TeraTypeRules.effectiveType(param("String", null)))
        assertEquals("integer", TeraTypeRules.effectiveType(param("INTEGER", "1")))
        assertEquals("integer", TeraTypeRules.effectiveType(param(null, "1")))
        assertEquals("float", TeraTypeRules.effectiveType(param(null, "1.5")))
        assertEquals("array", TeraTypeRules.effectiveType(param(null, "[1]")))
        assertNull(TeraTypeRules.effectiveType(param(null, null)))
        assertNull("none gives no type", TeraTypeRules.effectiveType(param(null, "none")))
        assertNull("a bad annotation is reported elsewhere", TeraTypeRules.effectiveType(param("strng", "1")))
    }

    fun testInferredKinds() {
        assertEquals("string", TeraTypeRules.kindOf(TeraType.Scalar("string")))
        assertEquals("array", TeraTypeRules.kindOf(TeraType.Tuple(listOf(TeraType.Unknown))))
        assertEquals("map", TeraTypeRules.kindOf(TeraType.Struct("S", emptyList())))
        assertNull(TeraTypeRules.kindOf(TeraType.Unknown))
        assertTrue(TeraTypeRules.matchesInferred("float", "number"))        // an inferred number may be a float
        assertFalse(TeraTypeRules.matchesInferred("string", "number"))
    }
}

/** Tera 2 type problems found while editing instead of while rendering. */
class TeraTypeCheckerTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState().also { it.version = "2" })
    }

    private fun scalar(n: String) = TeraType.Scalar(n)
    private val task = TeraType.Struct("Task", listOf(TeraField("id", scalar("number")), TeraField("title", scalar("string"))))
    private val context = TeraContextResult(listOf(
        TeraField("title", scalar("string")), TeraField("count", scalar("number")), TeraField("ready", scalar("bool")),
        TeraField("tasks", TeraType.ArrayOf(task)), TeraField("names", TeraType.ArrayOf(scalar("string"))),
        TeraField("scores", TeraType.MapOf(scalar("number"))), TeraField("flash", TeraType.Tuple(listOf(scalar("string"), scalar("string")))),
    ), complete = true)

    private fun comps(vararg src: String) = src.flatMap { TeraComponentScanner.definitions(it) }.map { TeraComponent(it, null) }
    private fun check(text: String, vararg components: String, ctx: TeraContextResult? = context, custom: List<TeraCustomBuiltin> = emptyList()) =
        TeraTypeChecker.check(text, ctx, comps(*components), custom)
    private fun TeraError.text(src: String) = range.substring(src)
    private fun fixed(src: String, e: TeraError) = e.fixes.single().applyTo(src)

    // ── component definitions ────────────────────────────────────────────────

    fun testUnknownParameterType() {
        val src = "{% component c(a: strng, b: int) %}x{% endcomponent %}"
        val e = check(src)
        assertEquals(listOf("strng", "int"), e.map { it.text(src) })
        assertEquals("Unknown type 'strng': the only types allowed are string, bool, integer, float, number, array, map and bytes", e[0].message)
        assertTrue(e.all { it.severity == HighlightSeverity.ERROR })
    }

    fun testEveryTypeNameIsAcceptedInAnyCase() {
        for (t in TeraTypeRules.COMPONENT_TYPES) {
            assertEmpty("$t", check("{% component c(a: $t) %}x{% endcomponent %}"))
            assertEmpty(t.uppercase(), check("{% component c(a: ${t.uppercase()}) %}x{% endcomponent %}"))
        }
    }

    /** The message and the example of tera 2.4.0's `component_default_mismatched_type` snapshot. */
    fun testDefaultOfTheWrongTypeIsAnError() {
        val src = "{% component foo(bar: string = 1) %}{{bar}}{% endcomponent %}"
        val e = check(src).single()
        assertEquals("Default value for 'bar' has type 'integer' but the argument has type 'string'", e.message)
        assertEquals("1", e.text(src))
    }

    fun testDefaultTypeMatrix() {
        val bad = listOf("a: float = 1", "a: integer = 1.5", "a: array = {}", "a: map = []", "a: bool = \"no\"", "a: string = true",
            "a: integer = \"1\"", "@a: string = 1")
        for (p in bad) assertEquals(p, 1, check("{% component c($p) %}x{% endcomponent %}").size)
        val good = listOf("a: number = 1", "a: number = 1.5", "a: integer = -1", "a: float = -1.5", "a: string = \"x\"", "a: bool = true",
            "a: array = [1, 2]", "a: map = {\"k\": 1}", "a: string = none", "a = 1", "a = \"x\"", "a", "@a = \"x\"", "...rest")
        for (p in good) assertEmpty(p, check("{% component c($p) %}x{% endcomponent %}"))
    }

    // ── component calls ──────────────────────────────────────────────────────

    private val card = "{% component card(name: string, age: integer, ratio: float = 0.5, tags = [], visible = true, title = \"t\") %}x{% endcomponent card %}"

    fun testLiteralOfTheWrongTypeIsAnError() {
        val src = "{{ <card name={1} age=\"x\" ratio={1} tags={\"a\"} visible=\"yes\" title={3}/> }}"
        val e = check(src, card)
        assertEquals(listOf("{1}", "\"x\"", "{1}", "{\"a\"}", "\"yes\"", "{3}"), e.map { it.text(src) })
        assertTrue(e.all { it.severity == HighlightSeverity.ERROR })
        assertEquals("Component argument 'name' (type: 'integer') does not match expected type: 'string'", e[0].message)
        assertEquals("Component argument 'age' (type: 'string') does not match expected type: 'integer'", e[1].message)
        assertEquals("Component argument 'ratio' (type: 'integer') does not match expected type: 'float'", e[2].message)   // 1 is not a float
        assertEquals("Component argument 'tags' (type: 'string') does not match expected type: 'array'", e[3].message)      // inferred from `tags = []`
        assertEquals("Component argument 'visible' (type: 'string') does not match expected type: 'bool'", e[4].message)
    }

    fun testLiteralsOfTheRightTypeAreFine() {
        assertEmpty(check("{{ <card name=\"a\" age={3} ratio={1.5} tags={[1]} visible={false} title=\"x\"/> }}", card))
        assertEmpty(check("{{ <card name={\"a\"} age={-3} ratio={-1.5} tags={[]} visible={none}/> }}", card))
        assertEmpty(check("{% <card name=\"a\" age={3}> %}body{% </card> %}", card))
    }

    fun testTypeFromTheDefaultIsEnforcedWithoutAnAnnotation() {
        val c = "{% component c(n = 1, s = \"x\") %}x{% endcomponent c %}"
        assertEquals(2, check("{{ <c n=\"3\" s={3}/> }}", c).size)
        assertEmpty(check("{{ <c n={3} s=\"3\"/> }}", c))
    }

    fun testNumberAcceptsIntegersAndFloats() {
        val c = "{% component c(n: number) %}x{% endcomponent c %}"
        assertEmpty(check("{{ <c n={1}/> }}{{ <c n={1.5}/> }}", c))
        assertEquals(1, check("{{ <c n=\"1\"/> }}", c).size)
    }

    fun testExpressionsOfAKnownTypeAreWarnedAbout() {
        val src = "{{ <card name={count} age={title} tags={names} visible={ready}/> }}"
        val e = check(src, card)
        assertEquals(listOf("{count}", "{title}"), e.map { it.text(src) })
        assertTrue(e.all { it.severity == HighlightSeverity.WARNING })
        assertEquals("Component argument 'name' (type: 'number') does not match expected type: 'string'", e[0].message)
    }

    fun testShorthandAndLocalVariables() {
        val c = "{% component c(count: string, label: integer) %}x{% endcomponent c %}"
        val src = "{{ <c count label/> }}"
        val e = check(src, c)
        assertEquals(listOf("count"), e.map { it.text(src) })           // `label` is not defined here: unknown, silent
        val set = "{% set n = 1 %}{% set s = \"x\" %}{{ <c count={s} label={n}/> }}"
        assertEmpty(check(set, c))
        assertEquals(2, check("{% set n = 1 %}{% set s = \"x\" %}{{ <c count={n} label={s}/> }}", c).size)
    }

    fun testUntypedRestAndUnknownAreNotChecked() {
        val c = "{% component c(a, ...rest) %}x{% endcomponent c %}"
        assertEmpty(check("{{ <c a={1} b=\"x\" d={[1]}/> }}", c))
        assertEmpty(check("{{ <missing a={1}/> }}"))                      // unknown component: another checker
        assertEmpty(check("{{ <card name={unknown_var} age={f(1)}/> }}", card))
    }

    // ── filters ──────────────────────────────────────────────────────────────

    /** `{{ [1] | upper }}` is the example of tera 2.4.0's `filter_wrong_arg_type` snapshot. */
    fun testFilterOnALiteralOfTheWrongKind() {
        val src = "{{ [1] | upper }}"
        val e = check(src).single()
        assertEquals("Filter 'upper' expects string, but the value is array", e.message)
        assertEquals(HighlightSeverity.ERROR, e.severity)
        assertEquals("upper", e.text(src))
        assertEmpty(e.fixes)
    }

    fun testConversionFixesForNumbersAndStrings() {
        val a = "{{ 1 | upper }}"
        assertEquals("{{ 1 | str | upper }}", fixed(a, check(a).single()))
        assertEquals("Convert with '| str' first", check(a).single().fixes.single().label)
        val b = "{{ true | trim }}"
        assertEquals("{{ true | str | trim }}", fixed(b, check(b).single()))
        val c = "{{ \"3.5\" | round }}"
        assertEquals("{{ \"3.5\" | float | round }}", fixed(c, check(c).single()))
        val d = "{{ \"3\" | pluralize }}"
        assertEquals("{{ \"3\" | int | pluralize }}", fixed(d, check(d).single()))
    }

    fun testFiltersOnKnownTypesAreWarnings() {
        val src = "{{ tasks | upper }}{{ count | upper }}{{ title | round }}{{ scores | first }}{{ title | sort }}"
        val e = check(src)
        assertEquals(listOf("upper", "upper", "round", "first", "sort"), e.map { it.text(src) })
        assertTrue(e.all { it.severity == HighlightSeverity.WARNING })
        assertEquals("Filter 'upper' expects string, but the value is array<Task>", e[0].message)
        assertEquals("Filter 'upper' expects string, but the value is number", e[1].message)
        assertEquals("Filter 'first' expects array, but the value is map<number>", e[3].message)
        assertEquals("{{ tasks | upper }}{{ count | str | upper }}{{ title | round }}{{ scores | first }}{{ title | sort }}",
            e[1].fixes.single().applyTo(src))
    }

    fun testFittingFiltersAreSilent() {
        val src = "{{ tasks | first }}{{ tasks | length }}{{ names | join(sep=\",\") }}{{ title | upper }}{{ count | round }}" +
            "{{ scores | keys }}{{ scores | get(key=\"a\") }}{{ title | reverse }}{{ names | reverse }}{{ title | float }}" +
            "{{ count | str }}{{ count | default(value=1) }}{{ flash | first }}{{ ghost | upper }}{{ ready | str | upper }}"
        assertEmpty(check(src))
    }

    fun testChainsAreTypedStepByStep() {
        val src = "{{ title | length | upper }}{{ title | upper | lower }}{{ names | first | upper }}{{ tasks | first | upper }}{{ title | trim | length | round }}"
        assertEquals(listOf("upper", "upper"), check(src).map { it.text(src) })            // number | upper; Task | upper
        assertEquals("Filter 'upper' expects string, but the value is Task", check(src)[1].message)
    }

    fun testOperandBoundaries() {
        assertEmpty(check("{{ names[0] | upper }}{{ tasks[0].title | upper }}{{ (title) | upper }}{{ \"a\" ~ title | upper }}"))
        val src = "{{ count ~ title | upper }}{{ tasks | length + count | upper }}"
        assertEquals(listOf("upper"), check(src).map { it.text(src) })       // `count | upper`; the first binds to `title`
        assertEmpty(check("{% for t in tasks | sort(attribute=\"id\") %}{{ t.title | upper }}{% endfor %}"))
        assertEquals(1, check("{% if count | upper == \"a\" %}{% endif %}").size)
        assertEquals(1, check("{% set s = count | upper %}").size)
    }

    fun testFilterLengthRejectsNumbers() {
        assertEquals(1, check("{{ 1 | length }}").size)
        assertEmpty(check("{{ \"a\" | length }}{{ [1] | length }}{{ {\"a\": 1} | length }}"))
    }

    fun testFilterTagAndUnknownFiltersAreIgnored() {
        assertEmpty(check("{% filter upper %}x{% endfilter %}{{ count | frobnicate }}"))
    }

    fun testARegisteredFilterOfTheSameNameIsTheUsersToJudge() {
        val mine = TeraCustomBuiltin("filter", BuiltinDef("upper", "mine", source = "main.rs:1"), null)
        assertEmpty(check("{{ count | upper }}", custom = listOf(mine)))
    }

    // ── tests ────────────────────────────────────────────────────────────────

    fun testTestsOnTheWrongKind() {
        val src = "{% if \"a\" is odd %}{% endif %}{% if title is even %}{% endif %}{% if tasks is starting_with(pat=\"a\") %}{% endif %}"
        val e = check(src)
        assertEquals(listOf("odd", "even", "starting_with"), e.map { it.text(src) })
        assertEquals("Test 'odd' expects number, but the value is string", e[0].message)
        assertEquals(listOf(HighlightSeverity.ERROR, HighlightSeverity.WARNING, HighlightSeverity.WARNING), e.map { it.severity })
        assertEquals("{% if title | float is even %}{% endif %}", fixed("{% if title is even %}{% endif %}", check("{% if title is even %}{% endif %}").single()))
    }

    fun testTestsOnTheRightKindAndNegation() {
        assertEmpty(check("{% if count is odd %}{% endif %}{% if count is not even %}{% endif %}{% if title is starting_with(pat=\"a\") %}{% endif %}" +
            "{% if tasks is iterable %}{% endif %}{% if x is defined %}{% endif %}{% if count is divisible_by(divisor=2) %}{% endif %}"))
        assertEquals(1, check("{% if title is not odd %}{% endif %}").size)
    }

    // ── nothing else ─────────────────────────────────────────────────────────

    fun testNoContextMeansOnlyLiteralsAreChecked() {
        assertEmpty(check("{{ count | upper }}{{ tasks | upper }}", ctx = null))
        assertEquals(1, check("{{ 1 | upper }}", ctx = null).size)
    }

    // ── in the editor ────────────────────────────────────────────────────────

    fun testEditorShowsTheErrorAndTheConversionFix() {
        myFixture.configureByText("a.html.tera", "{{ 1 | up<caret>per }}")
        val info = myFixture.doHighlighting().single { it.description?.startsWith("Filter 'upper' expects") == true }
        assertEquals(HighlightSeverity.ERROR, info.severity)
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Convert with '| str' first" })
        myFixture.checkResult("{{ 1 | str | upper }}")
    }

    fun testTypeChecksAreTera2Only() {
        TeraSettings.getInstance(project).state.version = "1"
        myFixture.configureByText("b.html.tera", "{{ 1 | upper }}")
        assertTrue(myFixture.doHighlighting().none { it.description?.contains("expects") == true })
    }

    fun testCompletionPutsFittingFiltersFirst() {
        fun suggest(src: String, version: TeraVersion = TeraVersion.V2) = TeraCompletionEngine.suggest(
            src.replace("<caret>", ""), src.indexOf("<caret>"), { null }, { emptyList() }, context, emptyList(), version, emptyList())
        val onString = suggest("{{ title | <caret> }}").associateBy { it.text }
        assertEquals(1.0, onString.getValue("upper").priority)
        assertEquals(1.0, onString.getValue("length").priority)
        assertEquals(-1.0, onString.getValue("round").priority)
        assertEquals(-1.0, onString.getValue("first").priority)
        assertEquals(0.0, onString.getValue("str").priority)               // accepts anything
        val onArray = suggest("{{ tasks | <caret> }}").associateBy { it.text }
        assertEquals(1.0, onArray.getValue("first").priority)
        assertEquals(-1.0, onArray.getValue("upper").priority)
        val unknown = suggest("{{ ghost | <caret> }}")
        assertTrue(unknown.all { it.priority == 0.0 })
        assertTrue(suggest("{{ title | <caret> }}", TeraVersion.V1).all { it.priority == 0.0 })
        val tests = suggest("{% if count is <caret> %}{% endif %}").associateBy { it.text }
        assertEquals(1.0, tests.getValue("odd").priority)
        assertEquals(-1.0, tests.getValue("starting_with").priority)
    }

    fun testHoverShowsTheInputKind() {
        val provider = TeraDocumentationProvider()
        val file = myFixture.addFileToProject("templates/h.html.tera", "{{ x | upper }}{{ y | length }}{{ z | str }}")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        fun doc(marker: String): String {
            val caret = file.text.indexOf(marker) + 1
            myFixture.editor.caretModel.moveToOffset(caret)
            val el = provider.getCustomDocumentationElement(myFixture.editor, file, null, caret)!!
            return provider.generateDoc(el, null)!!.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
        }
        assertTrue(doc("upper"), doc("upper").contains("Input: string – other kinds of value are rejected when rendering"))
        assertTrue(doc("length"), doc("length").contains("Input: string or array or map"))
        assertFalse(doc("str"), doc("str").contains("Input:"))
    }
}
