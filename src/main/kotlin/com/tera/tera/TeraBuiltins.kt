package com.tera.tera

/** One argument of a built-in function, filter or test. [default] is shown as written, e.g. `0` or `""`. */
class BuiltinParam(val name: String, val required: Boolean, val default: String?, val doc: String)

/**
 * A function, filter or test that Tera itself provides (names and arguments are fixed by the engine,
 * not by the user's project). Functions and filters take keyword arguments; tests take positional ones.
 */
class BuiltinDef(
    val name: String,
    val description: String,
    val params: List<BuiltinParam> = emptyList(),
    val example: String? = null,
    val positional: Boolean = false,
    /** Where a project-defined entry was registered (e.g. `main.rs:12`); null for Tera's own. */
    val source: String? = null,
    /** Longer documentation, e.g. the Rust doc comment of a registered function. */
    val details: String? = null,
    /**
     * Kinds of value this filter or test accepts as its input (`string`, `number`, `array`, `map`, `bool`);
     * empty means anything. Tera 2 rejects other kinds when rendering.
     */
    val input: List<String> = emptyList(),
) {
    fun withInput(kinds: List<String>) = BuiltinDef(name, description, params, example, positional, source, details, kinds)

    val required: List<BuiltinParam> get() = params.filter { it.required }

    fun signature(): String =
        if (params.isEmpty()) name
        else params.joinToString(", ", "$name(", ")") {
            when {
                it.required || positional -> it.name
                it.default != null -> "${it.name}=${it.default}"
                else -> "${it.name}=…"
            }
        }

    /** Text inserted after the name: the required keyword arguments, with the caret after the first `=`. */
    fun callSuffix(alwaysParens: Boolean): Pair<String, Int>? {
        if (positional) return null
        val req = required
        if (req.isEmpty()) return if (alwaysParens) "()" to 1 else null
        return "(" + req.joinToString(", ") { "${it.name}=" } + ")" to (1 + req[0].name.length + 1)
    }
}

/** A function, filter or test registered by the project's Rust code (`register_function` and friends). */
class TeraCustomBuiltin(val kind: String, val def: BuiltinDef, val target: com.intellij.psi.PsiElement?)

object TeraBuiltins {
    /**
     * The definitions of [kind] (`function`, `filter` or `test`) for [version], with project-registered ones
     * taking precedence over Tera's own of the same name.
     */
    fun defs(kind: String, custom: List<TeraCustomBuiltin>, version: TeraVersion = TeraVersion.V1): List<BuiltinDef> {
        val own = custom.filter { it.kind == kind }.map { it.def }
        val names = own.map { it.name }.toSet()
        return base(kind, version).filter { it.name !in names } + own
    }

    fun byName(kind: String, custom: List<TeraCustomBuiltin>, version: TeraVersion = TeraVersion.V1): Map<String, BuiltinDef> =
        defs(kind, custom, version).associateBy { it.name }

    fun base(kind: String, version: TeraVersion): List<BuiltinDef> = when (version) {
        TeraVersion.V1 -> when (kind) { "function" -> FUNCTION_DEFS; "filter" -> FILTER_DEFS; else -> TEST_DEFS }
        TeraVersion.V2 -> when (kind) { "function" -> FUNCTION_DEFS_V2; "filter" -> FILTER_DEFS_V2; else -> TEST_DEFS_V2 }
    }

    private fun req(name: String, doc: String) = BuiltinParam(name, true, null, doc)
    private fun opt(name: String, default: String?, doc: String) = BuiltinParam(name, false, default, doc)
    private fun pos(name: String, doc: String) = BuiltinParam(name, true, null, doc)

    val FUNCTION_DEFS: List<BuiltinDef> = listOf(
        BuiltinDef("get_env", "read an environment variable", listOf(
            req("name", "name of the environment variable"),
            opt("default", null, "value used when the variable is not set; without it, rendering fails"),
        ), """{{ get_env(name="HOME", default="/") }}"""),
        BuiltinDef("get_random", "random number in a range", listOf(
            opt("start", "0", "smallest number that can be returned"),
            req("end", "first number that cannot be returned"),
        ), "{{ get_random(end=10) }}"),
        BuiltinDef("now", "current datetime", listOf(
            opt("utc", "false", "use UTC instead of the local time zone"),
            opt("timestamp", "false", "return a Unix timestamp instead of a formatted string"),
        ), "{{ now(utc=true) | date(format=\"%Y\") }}"),
        BuiltinDef("range", "generate a number sequence", listOf(
            req("end", "first integer that is not in the array"),
            opt("start", "0", "first integer of the array"),
            opt("step_by", "1", "step between two integers"),
        ), "{% for i in range(start=1, end=4) %}{{ i }}{% endfor %}"),
        BuiltinDef("throw", "abort rendering with a message", listOf(
            req("message", "why rendering failed"),
        ), """{{ throw(message="missing title") }}"""),
    )

    val FILTER_DEFS: List<BuiltinDef> = listOf(
        BuiltinDef("abs", "absolute value"),
        BuiltinDef("addslashes", "escape quotes with backslashes"),
        BuiltinDef("as_str", "convert to string"),
        BuiltinDef("capitalize", "uppercase first letter"),
        BuiltinDef("concat", "append to an array", listOf(req("with", "value or array to append")),
            "{{ [1, 2] | concat(with=3) }}"),
        BuiltinDef("date", "format a timestamp", listOf(
            opt("format", "\"%Y-%m-%d\"", "strftime format string"),
            opt("timezone", null, "IANA time zone name (needs Tera's chrono-tz feature)"),
            opt("locale", null, "locale for month and day names"),
        ), """{{ ts | date(format="%d.%m.%Y") }}"""),
        BuiltinDef("default", "fallback value when undefined", listOf(
            req("value", "value to use"),
            opt("boolean", "false", "also use the value when the input is falsy"),
        ), """{{ nick | default(value="anonymous") }}"""),
        BuiltinDef("escape", "HTML-escape"),
        BuiltinDef("escape_xml", "XML-escape"),
        BuiltinDef("filesizeformat", "human readable size", listOf(opt("binary", "false", "use 1024-based units (KiB, MiB)"))),
        BuiltinDef("filter", "keep items matching an attribute", listOf(
            req("attribute", "attribute to test"),
            opt("value", null, "value it must equal; without it, the attribute must be truthy"),
        ), """{{ tasks | filter(attribute="completed", value=true) }}"""),
        BuiltinDef("first", "first element"),
        BuiltinDef("float", "convert to float", listOf(opt("default", "0.0", "value when conversion fails"))),
        BuiltinDef("get", "get a key from an object", listOf(
            req("key", "key to read"),
            opt("default", null, "value when the key is missing"),
        ), """{{ values | get(key="name") }}"""),
        BuiltinDef("group_by", "group array by attribute", listOf(req("attribute", "attribute to group on")),
            """{% for key, items in tasks | group_by(attribute="status") %}"""),
        BuiltinDef("indent", "indent lines", listOf(
            req("prefix", "text put in front of each line"),
            opt("first", "false", "also indent the first line"),
            opt("blank", "false", "also indent blank lines"),
        ), """{{ text | indent(prefix="  ") }}"""),
        BuiltinDef("int", "convert to integer", listOf(
            opt("default", "0", "value when conversion fails"),
            opt("base", "10", "number base of the input"),
        )),
        BuiltinDef("join", "join array with a separator", listOf(opt("sep", "\"\"", "separator between items")),
            """{{ tags | join(sep=", ") }}"""),
        BuiltinDef("json_encode", "serialize as JSON", listOf(opt("pretty", "false", "indent the output"))),
        BuiltinDef("last", "last element"),
        BuiltinDef("length", "length of string, array or object"),
        BuiltinDef("linebreaksbr", "newlines to <br>"),
        BuiltinDef("lower", "lowercase"),
        BuiltinDef("map", "extract an attribute from each item", listOf(req("attribute", "attribute to extract")),
            """{{ tasks | map(attribute="id") }}"""),
        BuiltinDef("nth", "nth element", listOf(req("n", "zero-based position"))),
        BuiltinDef("pluralize", "plural suffix by count", listOf(
            opt("singular", "\"\"", "suffix when the number is 1"),
            opt("plural", "\"s\"", "suffix otherwise"),
        ), """field{{ errors | length | pluralize }}"""),
        BuiltinDef("replace", "replace text", listOf(
            req("from", "text to find"),
            req("to", "replacement"),
        ), """{{ name | replace(from="a", to="b") }}"""),
        BuiltinDef("reverse", "reverse array or string"),
        BuiltinDef("round", "round a number", listOf(
            opt("precision", "0", "digits after the decimal point"),
            opt("method", "\"common\"", "common, ceil or floor"),
        )),
        BuiltinDef("safe", "mark as safe, skip escaping"),
        BuiltinDef("slice", "sub-range of an array or string", listOf(
            opt("start", "0", "first index, negative counts from the end"),
            opt("end", null, "index after the last item"),
        )),
        BuiltinDef("slugify", "URL slug"),
        BuiltinDef("sort", "sort array", listOf(opt("attribute", null, "attribute to sort objects by"))),
        BuiltinDef("spaceless", "remove whitespace between tags"),
        BuiltinDef("split", "split into array", listOf(req("pat", "separator"))),
        BuiltinDef("striptags", "remove HTML tags"),
        BuiltinDef("title", "title case"),
        BuiltinDef("trim", "trim whitespace"),
        BuiltinDef("trim_end", "trim trailing whitespace"),
        BuiltinDef("trim_end_matches", "trim suffix", listOf(req("pat", "text to remove from the end"))),
        BuiltinDef("trim_start", "trim leading whitespace"),
        BuiltinDef("trim_start_matches", "trim prefix", listOf(req("pat", "text to remove from the start"))),
        BuiltinDef("truncate", "shorten to a length", listOf(
            opt("length", "255", "maximum number of characters"),
            opt("end", "\"…\"", "text appended when shortened"),
        )),
        BuiltinDef("unique", "remove duplicates", listOf(
            opt("attribute", null, "attribute to compare objects by"),
            opt("case_sensitive", "false", "compare strings case-sensitively"),
        )),
        BuiltinDef("upper", "uppercase"),
        BuiltinDef("urlencode", "percent-encode"),
        BuiltinDef("urlencode_strict", "strict percent-encode"),
        BuiltinDef("wordcount", "count words"),
    )

    val TEST_DEFS: List<BuiltinDef> = listOf(
        BuiltinDef("containing", "contains a value", listOf(pos("value", "value to look for")), positional = true),
        BuiltinDef("defined", "variable exists"),
        BuiltinDef("divisibleby", "divisible by a number", listOf(pos("divisor", "number to divide by")),
            "{% if i is divisibleby(2) %}", positional = true),
        BuiltinDef("ending_with", "string suffix", listOf(pos("suffix", "expected ending")), positional = true),
        BuiltinDef("even", "even number"),
        BuiltinDef("iterable", "array or string"),
        BuiltinDef("matching", "matches a regex", listOf(pos("regex", "regular expression")), positional = true),
        BuiltinDef("number", "is a number"),
        BuiltinDef("object", "is an object"),
        BuiltinDef("odd", "odd number"),
        BuiltinDef("starting_with", "string prefix", listOf(pos("prefix", "expected beginning")), positional = true),
        BuiltinDef("string", "is a string"),
        BuiltinDef("undefined", "variable does not exist"),
    )

    // ── Tera 2.x, from the tera 2.4.0 crate ──────────────────────────────────

    val FUNCTION_DEFS_V2: List<BuiltinDef> = listOf(
        BuiltinDef("range", "generate a number sequence", listOf(
            req("end", "first integer that is not in the array"),
            opt("start", "0", "first integer of the array"),
            opt("step_by", "1", "step between two integers, may be negative"),
        ), "{% for i in range(start=1, end=4) %}{{ i }}{% endfor %}"),
        BuiltinDef("throw", "abort rendering with a message", listOf(
            req("message", "why rendering failed"),
        ), """{{ throw(message="missing title") }}"""),
    )

    val FILTER_DEFS_V2: List<BuiltinDef> by lazy { FILTER_DEFS_V2_PLAIN.map { it.withInput(V2_FILTER_INPUT[it.name].orEmpty()) } }
    private val FILTER_DEFS_V2_PLAIN: List<BuiltinDef> = listOf(
        BuiltinDef("abs", "absolute value"),
        BuiltinDef("capitalize", "uppercase the first character, lowercase the rest"),
        BuiltinDef("default", "fallback value when undefined", listOf(
            req("value", "value to use"),
            opt("boolean", "false", "also use the value when the input is falsy"),
        ), """{{ nick | default(value="anonymous") }}"""),
        BuiltinDef("escape", "escape for the current output format"),
        BuiltinDef("escape_html", "HTML-escape"),
        BuiltinDef("escape_xml", "XML-escape"),
        BuiltinDef("first", "first element, none when empty"),
        BuiltinDef("float", "convert to float"),
        BuiltinDef("get", "get a key from a map", listOf(
            req("key", "key to read, may be a path such as a.b"),
            opt("default", null, "value when the key is missing"),
        ), """{{ values | get(key="name") }}"""),
        BuiltinDef("group_by", "group an array by an attribute", listOf(req("attribute", "attribute path to group on")),
            """{% for key, items in tasks | group_by(attribute="status") %}"""),
        BuiltinDef("indent", "indent lines", listOf(
            opt("width", "4", "number of indentation characters"),
            opt("indentation", null, "indentation text, a space by default"),
            opt("first", "false", "also indent the first line"),
            opt("blank", "false", "also indent blank lines"),
        ), "{{ text | indent(width=2) }}"),
        BuiltinDef("int", "convert to integer", listOf(opt("base", "10", "number base of the input"))),
        BuiltinDef("join", "join array with a separator", listOf(opt("sep", null, "separator between items")),
            """{{ tags | join(sep=", ") }}"""),
        BuiltinDef("keys", "keys of a map"),
        BuiltinDef("last", "last element, none when empty"),
        BuiltinDef("length", "length of string, array or map"),
        BuiltinDef("lower", "lowercase"),
        BuiltinDef("newlines_to_br", "newlines to <br>"),
        BuiltinDef("nth", "nth element, none when out of range", listOf(req("n", "zero-based position"))),
        BuiltinDef("pairs", "key and value pairs of a map"),
        BuiltinDef("pluralize", "plural suffix by count", listOf(
            opt("singular", "\"\"", "suffix when the number is 1"),
            opt("plural", "\"s\"", "suffix otherwise"),
        ), """field{{ errors | length | pluralize }}"""),
        BuiltinDef("replace", "replace text", listOf(
            req("from", "text to find"),
            req("to", "replacement"),
        ), """{{ name | replace(from="a", to="b") }}"""),
        BuiltinDef("reverse", "reverse array or string"),
        BuiltinDef("round", "round a number", listOf(
            opt("method", null, "ceil or floor; omit for ordinary rounding"),
            opt("precision", "0", "digits after the decimal point"),
        )),
        BuiltinDef("safe", "mark as safe, skip escaping"),
        BuiltinDef("sort", "sort array", listOf(opt("attribute", null, "attribute to sort objects by"))),
        BuiltinDef("split", "split into array", listOf(req("pat", "separator"))),
        BuiltinDef("str", "convert to string"),
        BuiltinDef("title", "uppercase the first letter of each word"),
        BuiltinDef("trim", "trim whitespace, or a pattern from both ends", listOf(opt("pat", null, "text to remove instead of whitespace"))),
        BuiltinDef("trim_end", "trim trailing whitespace or a pattern", listOf(opt("pat", null, "text to remove instead of whitespace"))),
        BuiltinDef("trim_start", "trim leading whitespace or a pattern", listOf(opt("pat", null, "text to remove instead of whitespace"))),
        BuiltinDef("truncate", "shorten to a length", listOf(
            req("length", "maximum number of characters"),
            opt("end", "\"…\"", "text appended when shortened"),
        ), "{{ title | truncate(length=20) }}"),
        BuiltinDef("unique", "remove duplicates"),
        BuiltinDef("upper", "uppercase"),
        BuiltinDef("values", "values of a map"),
        BuiltinDef("wordcount", "count words"),
    )

    val TEST_DEFS_V2: List<BuiltinDef> by lazy { TEST_DEFS_V2_PLAIN.map { it.withInput(V2_TEST_INPUT[it.name].orEmpty()) } }
    private val TEST_DEFS_V2_PLAIN: List<BuiltinDef> = listOf(
        BuiltinDef("array", "is an array"),
        BuiltinDef("bool", "is a boolean"),
        BuiltinDef("containing", "contains a value", listOf(req("pat", "value to look for")), "{% if tags is containing(pat=\"rust\") %}"),
        BuiltinDef("defined", "variable exists"),
        BuiltinDef("divisible_by", "divisible by a number", listOf(req("divisor", "number to divide by")),
            "{% if i is divisible_by(divisor=2) %}"),
        BuiltinDef("ending_with", "string suffix", listOf(req("pat", "expected ending"))),
        BuiltinDef("even", "even number"),
        BuiltinDef("float", "is a float"),
        BuiltinDef("integer", "is an integer"),
        BuiltinDef("iterable", "array or string"),
        BuiltinDef("map", "is a map"),
        BuiltinDef("none", "is none"),
        BuiltinDef("number", "is a number"),
        BuiltinDef("odd", "odd number"),
        BuiltinDef("starting_with", "string prefix", listOf(req("pat", "expected beginning"))),
        BuiltinDef("string", "is a string"),
        BuiltinDef("undefined", "variable does not exist"),
    )

    /**
     * What the separate `tera-contrib` crate offers. Nothing here exists until the project registers it, e.g.
     * `tera.register_filter("date", tera_contrib::dates::date)`, so these only enrich registrations the plugin finds.
     * Keyed by the Rust item name.
     */
    val CONTRIB: Map<String, Pair<String, BuiltinDef>> = listOf(
        "filter" to BuiltinDef("b64_encode", "base64-encode", listOf(
            opt("url_safe", "false", "use the URL-safe alphabet"), opt("padded", "true", "add `=` padding"))),
        "filter" to BuiltinDef("b64_decode", "base64-decode", listOf(opt("url_safe", "false", "use the URL-safe alphabet"))),
        "function" to BuiltinDef("now", "current datetime", listOf(opt("timezone", "\"UTC\"", "IANA time zone name"))),
        "filter" to BuiltinDef("date", "format a date or timestamp", listOf(
            opt("format", null, "strftime format string"), opt("locale", null, "locale for month and day names"),
            opt("timezone", null, "IANA time zone name"))),
        "test" to BuiltinDef("is_before", "is before another date", listOf(
            req("other", "date to compare with"), opt("inclusive", "false", "equal dates count as before"))),
        "test" to BuiltinDef("is_after", "is after another date", listOf(
            req("other", "date to compare with"), opt("inclusive", "false", "equal dates count as after"))),
        "filter" to BuiltinDef("filesize_format", "human readable size", listOf(opt("binary", "true", "use 1024-based units"))),
        "filter" to BuiltinDef("format", "format with a Rust-style spec", listOf(req("spec", "format specifier such as .2 or 05"))),
        "filter" to BuiltinDef("json_encode", "serialize as JSON", listOf(opt("pretty", "false", "indent the output"))),
        "function" to BuiltinDef("get_random", "random number in a range", listOf(
            req("start", "smallest number"), req("end", "first number that cannot be returned"),
            opt("seed", null, "seed for reproducible numbers"))),
        "filter" to BuiltinDef("shuffle", "shuffle an array", listOf(opt("seed", null, "seed for reproducible order"))),
        "filter" to BuiltinDef("striptags", "remove HTML tags"),
        "filter" to BuiltinDef("spaceless", "remove whitespace between tags"),
        "test" to BuiltinDef("matching", "matches a regex", listOf(req("pat", "regular expression"))),
        "filter" to BuiltinDef("regex_replace", "replace regex matches", listOf(
            req("pattern", "regular expression"), req("rep", "replacement"))),
        "filter" to BuiltinDef("slug", "URL slug"),
        "filter" to BuiltinDef("urlencode", "percent-encode"),
        "filter" to BuiltinDef("urlencode_strict", "strict percent-encode"),
    ).associate { (kind, def) -> def.name to (kind to def) } + mapOf(
        "Matching" to ("test" to BuiltinDef("matching", "matches a regex", listOf(req("pat", "regular expression")))),
        "RegexReplace" to ("filter" to BuiltinDef("regex_replace", "replace regex matches", listOf(
            req("pattern", "regular expression"), req("rep", "replacement")))),
    )

    /** What the Tera 2 filters demand of their input, from the parameter types in tera 2.4.0's `filters.rs`. */
    private val V2_FILTER_INPUT: Map<String, List<String>> = buildMap {
        for (n in listOf("wordcount", "escape_html", "escape_xml", "newlines_to_br", "truncate", "split", "safe", "upper", "lower",
            "escape", "trim", "trim_start", "trim_end", "replace", "capitalize", "title", "indent")) put(n, listOf("string"))
        for (n in listOf("first", "last", "nth", "join", "sort", "unique", "group_by")) put(n, listOf("array"))
        for (n in listOf("get", "values", "keys", "pairs")) put(n, listOf("map"))
        for (n in listOf("round", "abs", "pluralize")) put(n, listOf("number"))
        put("reverse", listOf("string", "array"))
        put("length", listOf("string", "array", "map"))
        put("float", listOf("string", "number"))
    }
    private val V2_TEST_INPUT: Map<String, List<String>> = mapOf(
        "odd" to listOf("number"), "even" to listOf("number"), "divisible_by" to listOf("number"),
        "starting_with" to listOf("string"), "ending_with" to listOf("string"),
    )

    val FILTERS: Map<String, String> = FILTER_DEFS.associate { it.name to it.description }
    val TESTS: Map<String, String> = TEST_DEFS.associate { it.name to it.description }
    val FUNCTIONS: Map<String, String> = FUNCTION_DEFS.associate { it.name to it.description }

    val FUNCTION_BY_NAME: Map<String, BuiltinDef> = FUNCTION_DEFS.associateBy { it.name }
    val FILTER_BY_NAME: Map<String, BuiltinDef> = FILTER_DEFS.associateBy { it.name }
    val TEST_BY_NAME: Map<String, BuiltinDef> = TEST_DEFS.associateBy { it.name }

    /** Filters whose result has the same shape as their input. */
    val TYPE_PRESERVING = setOf("reverse", "sort", "unique", "slice", "filter", "default", "concat")
    val STRING_RESULT = setOf(
        "upper", "lower", "capitalize", "title", "trim", "trim_start", "trim_end", "trim_start_matches",
        "trim_end_matches", "replace", "truncate", "slugify", "urlencode", "urlencode_strict", "escape",
        "escape_xml", "safe", "addslashes", "linebreaksbr", "indent", "striptags", "join", "date", "json_encode",
        "as_str", "filesizeformat", "pluralize", "spaceless", "str", "newlines_to_br", "escape_html",
    )
    val NUMBER_RESULT = setOf("length", "wordcount", "int", "float", "round", "abs")
}
