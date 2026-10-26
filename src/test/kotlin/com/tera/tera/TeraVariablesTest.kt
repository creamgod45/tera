package com.tera.tera

import junit.framework.TestCase

/** Variable, field, filter and scope completion, using the shapes of Rocket's todo and templating examples. */
class TeraVariablesTest : TestCase() {

    private fun assertEmpty(c: Collection<*>) = assertTrue("expected empty but was $c", c.isEmpty())

    private fun scalar(n: String) = TeraType.Scalar(n)

    /** examples/todo: `struct Context { flash: Option<(String, String)>, tasks: Vec<Task> }`. */
    private val task = TeraType.Struct("Task", listOf(
        TeraField("id", scalar("number"), "Option<i32>"),
        TeraField("description", scalar("string"), "String"),
        TeraField("completed", scalar("bool"), "bool"),
    ))
    private val todo = TeraContextResult(listOf(
        TeraField("flash", TeraType.Tuple(listOf(scalar("string"), scalar("string"))), "Option<(String, String)>"),
        TeraField("tasks", TeraType.ArrayOf(task), "Vec<Task>"),
    ), complete = true)

    /** examples/templating: `context! { title, name, items }`. */
    private val templating = TeraContextResult(listOf(
        TeraField("title", scalar("string")),
        TeraField("name", scalar("string")),
        TeraField("items", TeraType.ArrayOf(scalar("string"))),
        TeraField("scores", TeraType.MapOf(scalar("number"))),
    ), complete = true)

    private fun suggest(textWithCaret: String, context: TeraContextResult? = todo): List<TeraSuggestion> {
        val caret = textWithCaret.indexOf("<caret>")
        return TeraCompletionEngine.suggest(textWithCaret.replace("<caret>", ""), caret, { null }, { emptyList() }, context)
    }

    private fun names(textWithCaret: String, context: TeraContextResult? = todo) =
        suggest(textWithCaret, context).map { it.text }

    // ── top-level variables ──────────────────────────────────────────────────

    fun testContextVariablesWithRustTypes() {
        val s = suggest("{{ <caret> }}")
        val tasks = s.first { it.text == "tasks" }
        assertEquals(": Vec<Task>", tasks.tail)
        assertEquals("context", tasks.type)
        assertEquals(": Option<(String, String)>", s.first { it.text == "flash" }.tail)
    }

    fun testVariablesAvailableInEveryExpressionPosition() {
        for (src in listOf("{{ <caret> }}", "{% if <caret> %}{% endif %}", "{% for x in <caret> %}{% endfor %}",
            "{% set y = <caret> %}", "{% elif <caret> %}", "{{ a ~ <caret> }}")) {
            assertTrue(src, names(src).containsAll(listOf("tasks", "flash")))
        }
    }

    fun testPrefixIsFilteredByTheIdeNotTheEngine() {
        assertTrue(names("{{ ta<caret> }}").containsAll(listOf("tasks", "flash")))
    }

    fun testBuiltinFunctionsAreOffered() {
        val s = suggest("{{ <caret> }}")
        assertEquals(setOf("get_env", "get_random", "now", "range", "throw"),
            s.filter { it.type == "function" }.map { it.text }.toSet())
        assertEquals("()", s.first { it.text == "now" }.suffix)
    }

    fun testNothingWhenDeclaringNames() {
        assertEmpty(names("{% for <caret> in tasks %}{% endfor %}"))
        assertEmpty(names("{% for a, <caret> in tasks %}{% endfor %}"))
        assertEmpty(names("{% set <caret> = 1 %}"))
        assertEmpty(names("{% set_global <caret> %}"))
        assertEmpty(names("{% block <caret> %}{% endblock %}"))
    }

    fun testNoContextStillOffersBuiltinsAndLocals() {
        val n = names("{% set a = 1 %}{{ <caret> }}", null)
        assertTrue(n.contains("a") && n.contains("range"))
        assertFalse(n.contains("tasks"))
    }

    // ── fields ───────────────────────────────────────────────────────────────

    fun testFieldsOfLoopVariable() {
        val s = suggest("{% for task in tasks %}{{ task.<caret> }}{% endfor %}")
        assertEquals(listOf("id", "description", "completed"), s.map { it.text })
        assertEquals(listOf(": Option<i32>", ": String", ": bool"), s.map { it.tail })
        assertTrue(s.all { it.type == "field" })
    }

    fun testFieldsWithPrefixAndInsideIf() {
        assertEquals(listOf("id", "description", "completed"),
            names("{% for task in tasks %}{% if task.com<caret> %}{% endif %}{% endfor %}"))
    }

    fun testNoFieldsForArraysTuplesAndScalars() {
        assertEmpty(names("{{ tasks.<caret> }}"))
        assertEmpty(names("{{ flash.<caret> }}"))
        assertEmpty(names("{% for task in tasks %}{{ task.description.<caret> }}{% endfor %}"))
        assertEmpty(names("{{ unknown.<caret> }}"))
    }

    fun testIndexedAccessesResolveElements() {
        val expected = listOf("id", "description", "completed")
        assertEquals(expected, names("{{ tasks[0].<caret> }}"))
        assertEquals(expected, names("{{ tasks.0.<caret> }}"))
        assertEquals(expected, names("{% set t = tasks | first %}{{ t.<caret> }}"))
        assertEquals(expected, names("{% set t = tasks | reverse | first %}{{ t.<caret> }}"))
    }

    fun testMapValuesAndKeys() {
        assertEmpty(names("{% for name, score in scores %}{{ name.<caret> }}{% endfor %}", templating))
        val s = suggest("{% for k, v in scores %}{{ <caret> }}{% endfor %}", templating)
        assertEquals(": string", s.first { it.text == "k" }.tail)
        assertEquals(": number", s.first { it.text == "v" }.tail)
    }

    // ── scopes ───────────────────────────────────────────────────────────────

    fun testLoopVariableOnlyInsideItsLoop() {
        assertTrue(names("{% for task in tasks %}{{ <caret> }}{% endfor %}").contains("task"))
        assertFalse(names("{% for task in tasks %}{% endfor %}{{ <caret> }}").contains("task"))
        assertFalse(names("{{ <caret> }}{% for task in tasks %}{% endfor %}").contains("task"))
    }

    fun testLoopBuiltinOnlyInsideLoop() {
        assertEquals(listOf("index", "index0", "first", "last"),
            names("{% for task in tasks %}{{ loop.<caret> }}{% endfor %}"))
        assertTrue(names("{% for task in tasks %}{{ <caret> }}{% endfor %}").contains("loop"))
        assertFalse(names("{{ <caret> }}").contains("loop"))
        assertEmpty(names("{{ loop.<caret> }}"))
    }

    fun testNestedLoopsAndInnerShadowing() {
        val src = "{% for task in tasks %}{% for task in items %}{{ task.<caret> }}{% endfor %}{% endfor %}"
        assertEmpty(names(src, TeraContextResult(todo.variables + templating.variables, true)))
        val outer = "{% for task in tasks %}{% for i in items %}{% endfor %}{{ task.<caret> }}{% endfor %}"
        assertEquals(listOf("id", "description", "completed"),
            names(outer, TeraContextResult(todo.variables + templating.variables, true)))
    }

    fun testLoopEndsAtMatchingEndforInsideIf() {
        val src = "{% for task in tasks %}{% if task.completed %}x{% endif %}{% endfor %}{{ <caret> }}"
        assertFalse(names(src).contains("task"))
        assertTrue(names("{% for task in tasks %}{% if task.completed %}{{ <caret> }}{% endif %}{% endfor %}").contains("task"))
    }

    fun testSetVariablesAndTheirTypes() {
        val s = suggest("{% set total = tasks | length %}{% set label = \"x\" %}{% set done = true %}{{ <caret> }}")
        assertEquals(": number", s.first { it.text == "total" }.tail)
        assertEquals(": string", s.first { it.text == "label" }.tail)
        assertEquals(": bool", s.first { it.text == "done" }.tail)
    }

    fun testSetInsideLoopIsLocalButSetGlobalEscapes() {
        val local = "{% for task in tasks %}{% set n = 1 %}{% endfor %}{{ <caret> }}"
        assertFalse(names(local).contains("n"))
        val global = "{% for task in tasks %}{% set_global g = 1 %}{% endfor %}{{ <caret> }}"
        assertTrue(names(global).contains("g"))
    }

    fun testSetInsideIfIsVisibleAfterwards() {
        assertTrue(names("{% if true %}{% set n = 1 %}{% endif %}{{ <caret> }}").contains("n"))
    }

    fun testMacroSeesItsParametersAndTheContextButNotSurroundingVariables() {
        val n = names("{% set outer = 1 %}{% macro m(a, b=\"x\", c=1) %}{{ <caret> }}{% endmacro %}")
        assertTrue(n.containsAll(listOf("a", "b", "c")))
        assertTrue("a Tera 1 macro falls back to the render context", n.contains("tasks"))
        assertFalse("but not to the set variables around it", n.contains("outer"))
        val after = names("{% macro m(a) %}{% endmacro %}{{ <caret> }}")
        assertTrue(after.contains("tasks"))
        assertFalse(after.contains("a"))
    }

    fun testMacroParameterTypesFromDefaults() {
        val s = suggest("{% macro m(a, b=\"x\", c=1, d=true) %}{{ <caret> }}{% endmacro %}")
        assertEquals(": any", s.first { it.text == "a" }.tail)
        assertEquals(": string", s.first { it.text == "b" }.tail)
        assertEquals(": number", s.first { it.text == "c" }.tail)
        assertEquals(": bool", s.first { it.text == "d" }.tail)
    }

    // ── filters and tests ────────────────────────────────────────────────────

    fun testFiltersAfterPipe() {
        val s = suggest("{{ name | <caret> }}")
        assertTrue(s.map { it.text }.containsAll(listOf("upper", "lower", "length", "default", "join", "safe", "escape")))
        assertTrue(s.all { it.type == "filter" })
        assertEquals("uppercase", s.first { it.text == "upper" }.tail)
        assertTrue(names("{{ name | up<caret> }}").contains("upper"))
        assertTrue(names("{{ name | upper | <caret> }}").contains("trim"))
        assertEquals(TeraBuiltins.FILTERS.keys.toList(), names("{{ x | <caret> }}"))
    }

    fun testTestsAfterIs() {
        assertEquals(TeraBuiltins.TESTS.keys.toList(), names("{% if x is <caret> %}{% endif %}"))
        assertEquals(TeraBuiltins.TESTS.keys.toList(), names("{% if x is not <caret> %}{% endif %}"))
        assertTrue(names("{% if x is def<caret> %}{% endif %}").contains("defined"))
    }

    fun testNothingOutsideBlocks() {
        assertEmpty(names("<p><caret></p>"))
        assertEmpty(names("{# <caret> #}"))
    }
}
