package com.tera.tera

import junit.framework.TestCase
import java.io.File

/**
 * Cross-checks the built-in names and argument names against the Tera crate's own source, when it is in the
 * local Cargo registry (a `tera-1.x` folder under `~/.cargo/registry/src`). Without it the checks are skipped, because the data
 * can only be as good as the engine it describes.
 */
class TeraTeraSourceTest : TestCase() {

    private val crate: File? = File(System.getProperty("user.home"), ".cargo/registry/src").listFiles().orEmpty()
        .flatMap { it.listFiles().orEmpty().toList() }
        .filter { it.name.startsWith("tera-1.") && File(it, "src/tera.rs").exists() }
        .maxByOrNull { it.name }

    private fun source(path: String) = File(crate, path).readText()

    private fun checkNames(kind: String, mine: List<BuiltinDef>, registered: Set<String>, special: Set<String>) {
        val own = mine.map { it.name }.toSet()
        assertEquals("$kind registered by Tera but missing here", emptySet<String>(), registered - own)
        assertEquals("$kind listed here but not provided by Tera", emptySet<String>(), own - registered - special)
    }

    fun testBuiltinNamesMatchTheEngine() {
        val c = crate ?: return
        val tera = File(c, "src/tera.rs").readText()
        fun registered(kind: String) = Regex("""register_$kind\("([a-z_0-9]+)", [a-z_:]+\)""").findAll(tera)
            .map { it.groupValues[1] }.toSet()
        // `hello` and `echo` are registered by Tera's own doc/tests, not part of the engine.
        checkNames("function", TeraBuiltins.FUNCTION_DEFS, registered("function") - "echo", emptySet())
        checkNames("filter", TeraBuiltins.FILTER_DEFS, registered("filter") - "hello", setOf("default", "safe"))
        checkNames("test", TeraBuiltins.TEST_DEFS, registered("tester") - "hello", emptySet())
    }

    fun testKeywordArgumentsMatchWhatTheEngineReads() {
        val c = crate ?: return
        val tera = File(c, "src/tera.rs").readText()
        val files = File(c, "src/builtins").walkTopDown().filter { it.extension == "rs" }.toList()
        val bodies = mutableMapOf<String, String>()      // rust fn name -> body text
        for (f in files) {
            val text = f.readText()
            val starts = Regex("""(?m)^pub fn (\w+)""").findAll(text).toList()
            for ((i, m) in starts.withIndex()) {
                bodies[m.groupValues[1]] = text.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: text.length)
            }
        }
        fun read(fnName: String): Set<String> =
            Regex("""args\.get\("(\w+)"\)""").findAll(bodies.getValue(fnName)).map { it.groupValues[1] }.toSet()

        val ruleFn = mapOf("escape" to "escape_html")
        val registeredFilter = Regex("""register_filter\("(\w+)", (?:\w+::)?(\w+)\)""").findAll(tera)
            .associate { it.groupValues[1] to it.groupValues[2] }
        for (def in TeraBuiltins.FILTER_DEFS) {
            val fnName = registeredFilter[def.name] ?: ruleFn[def.name] ?: continue     // default/safe: renderer built-ins
            if (fnName !in bodies) continue
            assertEquals("filter ${def.name}", read(fnName), def.params.map { it.name }.toSet())
        }
        val registeredFunction = Regex("""register_function\("(\w+)", (?:\w+::)?(\w+)\)""").findAll(tera)
            .associate { it.groupValues[1] to it.groupValues[2] }
        for (def in TeraBuiltins.FUNCTION_DEFS) {
            val fnName = registeredFunction[def.name] ?: continue
            if (fnName !in bodies) continue
            assertEquals("function ${def.name}", read(fnName), def.params.map { it.name }.toSet())
        }
    }

    fun testRequiredArgumentsAreTheOnesTheEngineDemands() {
        val c = crate ?: return
        val functions = File(c, "src/builtins/functions.rs").readText()
        // Each required argument is rejected with "didn't receive a `x` argument" / "expected an arg called `x`".
        for (def in TeraBuiltins.FUNCTION_DEFS) for (p in def.params.filter { it.required }) {
            assertTrue("${def.name}.${p.name}", functions.contains("`${p.name}`"))
        }
    }
}
