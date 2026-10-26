package com.tera.tera

/** Tera's built-in filters, tests and functions (names are fixed by the engine, not by the user's project). */
object TeraBuiltins {
    val FILTERS: Map<String, String> = linkedMapOf(
        "abs" to "absolute value", "addslashes" to "escape quotes with backslashes", "as_str" to "convert to string",
        "capitalize" to "uppercase first letter", "concat" to "append to an array", "date" to "format a timestamp",
        "default" to "fallback value when undefined", "escape" to "HTML-escape", "escape_xml" to "XML-escape",
        "filesizeformat" to "human readable size", "filter" to "keep items matching an attribute",
        "first" to "first element", "float" to "convert to float", "get" to "get a key from an object",
        "group_by" to "group array by attribute", "indent" to "indent lines", "int" to "convert to integer",
        "join" to "join array with a separator", "json_encode" to "serialize as JSON", "last" to "last element",
        "length" to "length of string, array or object", "linebreaksbr" to "newlines to <br>",
        "lower" to "lowercase", "map" to "extract an attribute from each item", "nth" to "nth element",
        "pluralize" to "plural suffix by count", "reverse" to "reverse array or string", "round" to "round a number",
        "safe" to "mark as safe, skip escaping", "slice" to "sub-range of an array or string",
        "slugify" to "URL slug", "sort" to "sort array", "spaceless" to "remove whitespace between tags",
        "split" to "split into array", "striptags" to "remove HTML tags", "title" to "title case",
        "trim" to "trim whitespace", "trim_end" to "trim trailing whitespace", "trim_end_matches" to "trim suffix",
        "trim_start" to "trim leading whitespace", "trim_start_matches" to "trim prefix",
        "truncate" to "shorten to a length", "unique" to "remove duplicates", "upper" to "uppercase",
        "urlencode" to "percent-encode", "urlencode_strict" to "strict percent-encode", "wordcount" to "count words",
    )

    val TESTS: Map<String, String> = linkedMapOf(
        "containing" to "contains a value", "defined" to "variable exists", "divisibleby" to "divisible by a number",
        "ending_with" to "string suffix", "even" to "even number", "iterable" to "array or string",
        "matching" to "matches a regex", "number" to "is a number", "object" to "is an object", "odd" to "odd number",
        "starting_with" to "string prefix", "string" to "is a string", "undefined" to "variable does not exist",
    )

    val FUNCTIONS: Map<String, String> = linkedMapOf(
        "get_env" to "read an environment variable", "get_random" to "random number in a range",
        "now" to "current datetime", "range" to "generate a number sequence", "throw" to "abort rendering with a message",
    )

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
