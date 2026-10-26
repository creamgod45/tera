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
) {
    val required: List<BuiltinParam> get() = params.filter { it.required }

    fun signature(): String =
        if (params.isEmpty() && positional.not()) name
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

object TeraBuiltins {
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
            opt("timezone", null, "IANA time zone name such as Europe/Berlin (needs Tera's chrono-tz feature)"),
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
        BuiltinDef("filesizeformat", "human readable size"),
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
        "as_str", "filesizeformat", "pluralize", "spaceless",
    )
    val NUMBER_RESULT = setOf("length", "wordcount", "int", "float", "round", "abs")
}
