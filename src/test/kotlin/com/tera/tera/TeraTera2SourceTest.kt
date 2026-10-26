package com.tera.tera

import junit.framework.TestCase
import java.io.File

/**
 * Cross-checks the Tera 2 catalog against the `tera 2.x` and `tera-contrib` crates when they are in the local Cargo
 * registry; skipped otherwise. This is what keeps names, argument names and required-ness honest.
 */
class TeraTera2SourceTest : TestCase() {

    private fun crate(prefix: String, marker: String): File? =
        File(System.getProperty("user.home"), ".cargo/registry/src").listFiles().orEmpty()
            .flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.name.startsWith(prefix) && File(it, marker).exists() }
            .maxByOrNull { it.name }

    private val tera2 = crate("tera-2.", "src/tera.rs")
    private val contrib = crate("tera-contrib-", "src/lib.rs")

    /** Rust fn name -> keyword arguments it reads. */
    private fun argsByFn(vararg files: File): Map<String, List<Pair<String, Boolean>>> {
        val out = mutableMapOf<String, List<Pair<String, Boolean>>>()
        for (f in files) {
            val text = f.readText()
            val starts = Regex("""(?m)^pub(?:\(crate\))? fn (\w+)""").findAll(text).toList()
            for ((i, m) in starts.withIndex()) {
                val body = text.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: text.length).substringBefore("\n}\n")
                out[m.groupValues[1]] = Regex("""kwargs\.(must_get|get)::<[^>]+>\("(\w+)"\)""").findAll(body)
                    .map { it.groupValues[2] to (it.groupValues[1] == "must_get") }.distinctBy { it.first }.toList()
            }
        }
        return out
    }

    private fun check(kind: String, defs: List<BuiltinDef>, registered: Map<String, String>, args: Map<String, List<Pair<String, Boolean>>>, special: Set<String> = emptySet()) {
        val names = defs.map { it.name }.toSet()
        assertEquals("$kind: registered by Tera 2 but missing here", emptySet<String>(), registered.keys - names)
        assertEquals("$kind: listed here but not provided by Tera 2", emptySet<String>(), names - registered.keys - special)
        for (def in defs) {
            val fn = registered[def.name] ?: continue
            val real = args[fn] ?: continue
            assertEquals("$kind ${def.name} arguments", real.map { it.first }.toSet(), def.params.map { it.name }.toSet())
            for (p in def.params) {
                assertEquals("$kind ${def.name}.${p.name} required", real.first { it.first == p.name }.second, p.required)
            }
        }
    }

    fun testTera2CatalogMatchesTheCrate() {
        val c = tera2 ?: return
        val tera = File(c, "src/tera.rs").readText()
        fun registered(kind: String) = Regex("""register_$kind\("(\w+)", crate::(?:\w+)::(\w+)\)""").findAll(tera)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val args = argsByFn(File(c, "src/filters.rs"), File(c, "src/functions.rs"), File(c, "src/tests.rs"))
        check("filter", TeraBuiltins.FILTER_DEFS_V2, registered("filter"), args)
        check("test", TeraBuiltins.TEST_DEFS_V2, registered("test"), args)
        check("function", TeraBuiltins.FUNCTION_DEFS_V2, registered("function"), args)
    }

    fun testContribCatalogMatchesTheCrate() {
        val c = contrib ?: return
        val args = argsByFn(*File(c, "src").listFiles { f -> f.extension == "rs" }!!)
        val struct = setOf("Matching", "RegexReplace")
        var checked = 0
        for ((rustName, entry) in TeraBuiltins.CONTRIB) {
            if (rustName in struct || rustName !in args) continue
            val real = args.getValue(rustName)
            val def = entry.second
            assertEquals("$rustName arguments", real.map { it.first }.toSet(), def.params.map { it.name }.toSet())
            for (p in def.params) assertEquals("$rustName.${p.name} required", real.first { it.first == p.name }.second, p.required)
            checked++
        }
        assertTrue("expected to compare the function-based contrib items", checked >= 12)
        // The struct-based ones read their arguments in `call`.
        val regex = File(c, "src/regex.rs").readText()
        assertTrue(regex.contains("must_get::<&str>(\"pat\")") && regex.contains("must_get::<&str>(\"pattern\")"))
    }
}
