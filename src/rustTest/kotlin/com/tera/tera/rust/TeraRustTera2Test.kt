package com.tera.tera.rust

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tera.tera.TeraContexts
import com.tera.tera.TeraCustomBuiltin
import com.tera.tera.TeraSettings

/**
 * How Tera 2 projects register things: `register_test` (keyword arguments), `Kwargs` with `get` / `must_get`, closures
 * with a `Kwargs` parameter, and the items of the separate `tera-contrib` crate.
 */
class TeraRustTera2Test : BasePlatformTestCase() {

    private val header = """
        use tera::{Tera, Kwargs, State, TeraResult, Value};
    """.trimIndent() + "\n"

    private fun find(rust: String): List<TeraCustomBuiltin> {
        myFixture.addFileToProject("src/main.rs", header + rust)
        return RustCustomBuiltins.find(project)
    }

    private fun List<TeraCustomBuiltin>.named(kind: String, name: String) = first { it.kind == kind && it.def.name == name }
    private fun TeraCustomBuiltin.args() = def.params.map { it.name to it.required }

    private val registrations = """
        /// Shortens text to a length.
        fn shorten(val: &str, kwargs: Kwargs, _: &State) -> TeraResult<String> {
            let length = kwargs.must_get::<usize>("length")?;
            let end = kwargs.get::<&str>("end")?.unwrap_or("…");
            Ok(format!("{}{}", &val[..length.min(val.len())], end))
        }

        /// Is the age at least `min`?
        fn is_adult(age: i64, kwargs: Kwargs, _: &State) -> bool {
            let min = kwargs.get::<i64>("min").unwrap().unwrap_or(18);
            age >= min
        }

        fn setup(tera: &mut Tera) {
            tera.register_filter("double", |x: i64, _: Kwargs, _: &State| x * 2);
            tera.register_filter("shorten", shorten);
            tera.register_test("adult", is_adult);
            tera.register_function("greet", |kwargs: Kwargs, _: &State| -> TeraResult<String> {
                let who = kwargs.must_get::<&str>("who")?;
                let punctuation = kwargs.get::<&str>("punctuation")?;
                Ok(format!("hello {who}{}", punctuation.unwrap_or("!")))
            });
        }
    """.trimIndent()

    // ── Tera 2's own API ─────────────────────────────────────────────────────

    fun testFilterWithoutArguments() {
        val f = find(registrations).named("filter", "double")
        assertEmpty(f.args())
        assertEquals("double", f.def.signature())
    }

    fun testMustGetIsRequiredAndGetIsOptional() {
        val f = find(registrations).named("filter", "shorten")
        assertEquals(listOf("length" to true, "end" to false), f.args())
        assertEquals("shorten(length, end=…)", f.def.signature())
        assertEquals("Shortens text to a length.", f.def.description)
        assertEquals("shorten", f.target!!.text)
    }

    fun testRegisterTestTakesKeywordArguments() {
        val t = find(registrations).named("test", "adult")
        assertFalse("Tera 2 tests are not positional", t.def.positional)
        assertEquals(listOf("min" to false), t.args())
        assertEquals("Is the age at least `min`?", t.def.description)
    }

    fun testClosureWithAKwargsParameter() {
        val f = find(registrations).named("function", "greet")
        assertEquals(listOf("who" to true, "punctuation" to false), f.args())
        assertEquals("\"greet\"", f.target!!.text)
    }

    fun testTera1AndTera2StylesSideBySide() {
        val found = find("""
            fn old(args: &HashMap<String, Value>) -> Result<bool> { Ok(true) }
            fn new(v: i64, kwargs: Kwargs, _: &State) -> bool { kwargs.must_get::<i64>("n").unwrap() > v }
            fn setup(tera: &mut Tera) {
                tera.register_tester("positional", old);
                tera.register_test("keyword", new);
            }
        """.trimIndent())
        assertTrue(found.named("test", "positional").def.positional)
        assertEmpty(found.named("test", "positional").def.params)
        assertFalse(found.named("test", "keyword").def.positional)
        assertEquals(listOf("n" to true), found.named("test", "keyword").args())
    }

    fun testReceiverNamedDifferentlyAndTypedAsKwargs() {
        val f = find("""
            fn f(v: &str, k: Kwargs, _: &State) -> String { k.must_get::<&str>("a").unwrap(); k.get::<i64>("b").unwrap(); v.into() }
            fn setup(tera: &mut Tera) { tera.register_filter("f", f); }
        """.trimIndent()).named("filter", "f")
        assertEquals(listOf("a" to true, "b" to false), f.args())
    }

    fun testArgSpecsReader() {
        assertEquals(listOf("x" to true, "y" to false),
            RustCustomBuiltins.argSpecs("""kwargs.must_get::<u8>("x")?; kwargs.get::<u8>("y")?;""", "function"))
        assertEquals(listOf("x" to true), RustCustomBuiltins.argSpecs("""kwargs.get("x"); kwargs.must_get::<u8>("x")""", "function"))
        assertEquals(listOf("z" to false), RustCustomBuiltins.argSpecs("""args.get("z")""", "function"))
        assertEmpty(RustCustomBuiltins.argSpecs("""kwargs.must_get::<u8>("value")""", "filter"))
    }

    // ── tera-contrib ─────────────────────────────────────────────────────────

    private val contrib = """
        fn setup(tera: &mut Tera) {
            tera.register_filter("date", tera_contrib::dates::date);
            tera.register_function("now", tera_contrib::dates::now);
            tera.register_filter("slug", tera_contrib::slug::slug);
            tera.register_filter("json_encode", tera_contrib::json::json_encode);
            tera.register_test("matching", tera_contrib::regex::Matching::default());
            tera.register_filter("b64", tera_contrib::base64::b64_encode);
            tera.register_function("random", tera_contrib::rand::get_random);
        }
    """.trimIndent()

    fun testContribFiltersFunctionsAndTestsAreDescribedExactly() {
        val found = find(contrib)
        assertEquals(listOf("format" to false, "locale" to false, "timezone" to false), found.named("filter", "date").args())
        assertEquals(listOf("timezone" to false), found.named("function", "now").args())
        assertEmpty(found.named("filter", "slug").args())
        assertEquals(listOf("pretty" to false), found.named("filter", "json_encode").args())
        assertEquals(listOf("pat" to true), found.named("test", "matching").args())
        assertEquals(listOf("start" to true, "end" to true, "seed" to false), found.named("function", "random").args())
    }

    fun testContribItemsKeepTheNameTheyAreRegisteredUnder() {
        val b64 = find(contrib).named("filter", "b64")
        assertEquals("b64", b64.def.name)
        assertEquals(listOf("url_safe" to false, "padded" to false), b64.args())
        assertTrue(b64.def.details!!, b64.def.details!!.startsWith("From tera-contrib (b64_encode)"))
        assertEquals("b64(url_safe=false, padded=true)", b64.def.signature())
    }

    fun testContribDescriptionAndSource() {
        val d = find(contrib).named("filter", "date").def
        assertEquals("format a date or timestamp", d.description)
        assertTrue(d.source!!.startsWith("main.rs:"))
    }

    fun testALocalFunctionNamedLikeAContribItemIsNotContrib() {
        val f = find("""
            use tera_contrib as _unused;
            /// My own date filter.
            fn date(v: &str, kwargs: Kwargs, _: &State) -> String { kwargs.must_get::<&str>("mine").unwrap(); v.into() }
            fn setup(tera: &mut Tera) { tera.register_filter("date", date); }
        """.trimIndent()).named("filter", "date")
        assertEquals(listOf("mine" to true), f.args())
        assertEquals("My own date filter.", f.def.description)
    }

    fun testContribIsNeverGuessedWithoutTheCrate() {
        val f = find("""fn setup(tera: &mut Tera) { tera.register_filter("date", some_other::dates::date); }""").named("filter", "date")
        assertEmpty(f.args())
        assertTrue(f.def.description.startsWith("registered in Rust"))
    }

    // ── end to end ───────────────────────────────────────────────────────────

    private fun errorsOf(templateName: String, text: String): List<String> {
        TeraSettings.getInstance(project).state.version = "2"
        myFixture.configureByText(templateName, text)
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.mapNotNull { it.description }
    }

    fun testRegisteringTheContribFilterSilencesTheRemovalError() {
        val src = """{{ ts | date(format="%Y") }}"""
        assertTrue(errorsOf("a.html.tera", src).any { it.startsWith("Filter 'date' does not exist in Tera 2") })
        myFixture.addFileToProject("src/main.rs", header + contrib)
        assertEquals(emptyList<String>(), errorsOf("b.html.tera", src))
    }

    fun testRegisteredTestsAreAcceptedAndCheckedInTera2() {
        myFixture.addFileToProject("src/main.rs", header + registrations)
        assertEquals(emptyList<String>(), errorsOf("a.html.tera", "{% if age is adult(min=21) %}x{% endif %}{{ n | double }}{{ n | shorten(length=3) }}{{ greet(who=\"a\") }}"))
        assertEquals(1, TeraContexts.customBuiltins(project).count { it.kind == "test" })
    }

    fun testCompletionOfARegisteredFilterArguments() {
        myFixture.addFileToProject("src/main.rs", header + registrations)
        TeraSettings.getInstance(project).state.version = "2"
        myFixture.configureByText("c.html.tera", "{{ n | shorten(<caret>) }}")
        myFixture.completeBasic()
        assertEquals(setOf("length", "end"), myFixture.lookupElementStrings!!.toSet())
    }

    fun testCompletionOfARegisteredTestArgument() {
        myFixture.addFileToProject("src/main.rs", header + registrations)
        TeraSettings.getInstance(project).state.version = "2"
        myFixture.configureByText("d.html.tera", "{% if age is adult(<caret>) %}{% endif %}")
        myFixture.completeBasic()
        assertEquals(listOf("min"), myFixture.lookupElementStrings)             // the keyword argument of `adult`
        myFixture.finishLookup('\n')
        assertEquals("{% if age is adult(min=) %}{% endif %}", myFixture.editor.document.text)
    }
}
