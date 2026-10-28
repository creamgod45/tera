package com.tera.tera.rust

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tera.tera.TeraContexts
import com.tera.tera.TeraCustomBuiltin
import com.tera.tera.TeraDocumentationProvider

/**
 * Functions, filters and tests registered from Rust. The Rust fixtures follow how projects register them:
 * Rocket's `Template::custom(|engines| ..)`, a `customize(tera: &mut Tera)` helper (as in Rocket's templating
 * example), plain functions, closures, factory functions returning a closure, `Box::new`, types implementing
 * the engine traits, and names kept in constants.
 */
class TeraRustCustomBuiltinsTest : BasePlatformTestCase() {

    private val header = """
        use std::collections::HashMap;
        use rocket_dyn_templates::{Template, tera::{Tera, Value, Result, Function, Filter, Test}};
    """.trimIndent() + "\n"

    private fun find(rust: String): List<TeraCustomBuiltin> {
        myFixture.addFileToProject("src/main.rs", header + rust)
        return RustCustomBuiltins.find(project)
    }

    private fun List<TeraCustomBuiltin>.named(kind: String, name: String) = first { it.kind == kind && it.def.name == name }
    private fun TeraCustomBuiltin.args() = def.params.map { it.name }

    // ── the usual shape in a Rocket project ──────────────────────────────────

    private val rocketStyle = """
        /// Builds an absolute URL from a route name.
        /// Extra query parameters are optional.
        fn url_for(args: &HashMap<String, Value>) -> Result<Value> {
            let name = args.get("name").ok_or("missing name")?;
            let _query = args.get("query");
            Ok(name.clone())
        }

        /// Shortens text.
        fn shorten(value: &Value, args: &HashMap<String, Value>) -> Result<Value> {
            let _len = args.get("length");
            Ok(value.clone())
        }

        fn is_blank(value: Option<&Value>, _: &[Value]) -> Result<bool> { Ok(value.is_none()) }

        pub fn customize(tera: &mut Tera) {
            tera.register_function("url_for", url_for);
            tera.register_filter("shorten", shorten);
            tera.register_tester("blank", is_blank);
        }

        fn rocket() {
            rocket::build().attach(Template::custom(|engines| {
                customize(&mut engines.tera);
            }));
        }
    """.trimIndent()

    fun testCustomizeHelperAsInRocketsTemplatingExample() {
        val found = find(rocketStyle)
        assertEquals(setOf("function:url_for", "filter:shorten", "test:blank"), found.map { "${it.kind}:${it.def.name}" }.toSet())
    }

    fun testFunctionDocCommentArgumentsAndTarget() {
        val f = find(rocketStyle).named("function", "url_for")
        assertEquals("Builds an absolute URL from a route name.", f.def.description)
        assertEquals("Builds an absolute URL from a route name.\nExtra query parameters are optional.", f.def.details)
        assertEquals(listOf("name", "query"), f.args())
        val line = (header + rocketStyle).lines().indexOfFirst { it.contains("register_function(\"url_for\"") } + 1
        assertEquals("main.rs:$line", f.def.source)
        assertEquals("url_for", f.target!!.text)
        assertEquals("main.rs", f.target!!.containingFile.name)
    }

    fun testFilterArgumentsExcludeTheInputValue() {
        val f = find(rocketStyle).named("filter", "shorten")
        assertEquals(listOf("length"), f.args())
        assertEquals("Shortens text.", f.def.description)
    }

    fun testTesterIsPositional() {
        val t = find(rocketStyle).named("test", "blank")
        assertTrue(t.def.positional)
        assertEmpty(t.def.params)
        assertEquals("blank", t.def.signature())
        assertEquals("is_blank", t.target!!.text)
    }

    // ── registering directly inside Template::custom ─────────────────────────

    fun testClosuresRegisteredInsideTemplateCustom() {
        val found = find("""
            fn rocket() {
                rocket::build().attach(Template::custom(|engines| {
                    engines.tera.register_function("year", |_args: &HashMap<String, Value>| Ok(Value::from(2026)));
                    engines.tera.register_filter("shout", |v: &Value, args: &HashMap<String, Value>| {
                        let _suffix = args.get("suffix");
                        Ok(v.clone())
                    });
                    engines.tera.register_function("add", move |args: &HashMap<String, Value>| {
                        let a = args["a"].as_i64().unwrap_or(0);
                        let b = args.get("b").and_then(|v| v.as_i64()).unwrap_or(0);
                        Ok(Value::from(a + b))
                    });
                }));
            }
        """.trimIndent())
        assertEmpty(found.named("function", "year").args())
        assertEquals(listOf("suffix"), found.named("filter", "shout").args())
        assertEquals(listOf("a", "b"), found.named("function", "add").args())
        // a closure has no declaration of its own: navigation goes to the registered name
        assertEquals("\"year\"", found.named("function", "year").target!!.text)
    }

    // ── other common ways to provide the callable ────────────────────────────

    fun testFactoryFunctionReturningAClosure() {
        val f = find("""
            /// Links to a named route.
            fn make_url_for(urls: HashMap<String, String>) -> impl Function {
                Box::new(move |args: &HashMap<String, Value>| -> Result<Value> {
                    match args.get("name") {
                        Some(name) => Ok(Value::from(urls[name.as_str().unwrap()].clone())),
                        None => Err("oops".into()),
                    }
                })
            }
            fn setup(tera: &mut Tera, urls: HashMap<String, String>) {
                tera.register_function("url_for", make_url_for(urls));
            }
        """.trimIndent()).named("function", "url_for")
        assertEquals(listOf("name"), f.args())
        assertEquals("Links to a named route.", f.def.description)
        assertEquals("make_url_for", f.target!!.text)
    }

    fun testBoxedFunction() {
        val f = find("""
            fn twice(args: &HashMap<String, Value>) -> Result<Value> { Ok(args["n"].clone()) }
            fn setup(tera: &mut Tera) { tera.register_function("twice", Box::new(twice)); }
        """.trimIndent()).named("function", "twice")
        assertEquals(listOf("n"), f.args())
        assertEquals("twice", f.target!!.text)
    }

    fun testTypeImplementingTheEngineTrait() {
        val found = find("""
            struct Greeter { prefix: String }
            /// Greets someone.
            impl Function for Greeter {
                fn call(&self, args: &HashMap<String, Value>) -> Result<Value> {
                    let who = args.get("who");
                    Ok(Value::from(format!("{} {:?}", self.prefix, who)))
                }
            }
            struct Upper;
            impl Filter for Upper {
                fn filter(&self, value: &Value, args: &HashMap<String, Value>) -> Result<Value> {
                    let _x = args.get("locale");
                    Ok(value.clone())
                }
            }
            fn setup(tera: &mut Tera) {
                tera.register_function("greet", Greeter { prefix: "Hi".into() });
                tera.register_filter("up", Upper);
            }
        """.trimIndent())
        val greet = found.named("function", "greet")
        assertEquals(listOf("who"), greet.args())
        assertEquals("Greets someone.", greet.def.description)
        assertEquals("main.rs", greet.target!!.containingFile.name)
        assertEquals(listOf("locale"), found.named("filter", "up").args())
    }

    fun testNameKeptInAConstant() {
        val f = find("""
            const SLUG_FN: &str = "slug_of";
            fn slug_of(args: &HashMap<String, Value>) -> Result<Value> { Ok(args["title"].clone()) }
            fn setup(tera: &mut Tera) { tera.register_function(SLUG_FN, slug_of); }
        """.trimIndent()).named("function", "slug_of")
        assertEquals(listOf("title"), f.args())
    }

    fun testArgumentsFromTryGetValueAndDifferentlyNamedParameter() {
        val f = find("""
            fn sum(kwargs: &HashMap<String, Value>) -> Result<Value> {
                let a: i64 = tera::try_get_value!("sum", "a", i64, kwargs["a"].clone());
                let _ = kwargs.get("b");
                Ok(Value::from(a))
            }
            fn setup(tera: &mut Tera) { tera.register_function("sum", sum); }
        """.trimIndent()).named("function", "sum")
        assertEquals(listOf("a", "b"), f.args())
    }

    fun testArgNamesReader() {
        assertEquals(listOf("x", "y"), RustCustomBuiltins.argNames("""args.get("x"); args["y"]; args.get("x")""", "function"))
        assertEquals(listOf("size"), RustCustomBuiltins.argNames("""fn f(v: &Value, args: &HashMap<String, Value>) { args.get("size"); other.get("nope"); }""", "filter"))
        assertEquals(listOf("p"), RustCustomBuiltins.argNames("""fn f(opts: &HashMap<String, tera::Value>) { opts.get("p"); }""", "function"))
        // Tera 2 tests read keyword arguments too; whether a test is positional (Tera 1) is decided by register_tester vs register_test.
        assertEquals(listOf("x"), RustCustomBuiltins.argNames("""args.get("x")""", "test"))
        assertEmpty(RustCustomBuiltins.argNames("""try_get_value!("f", "value", String, v)""", "filter"))
    }

    // ── precedence, duplicates and what is ignored ───────────────────────────

    fun testLastRegistrationWinsAndBuiltInsCanBeOverridden() {
        val found = find("""
            fn first(args: &HashMap<String, Value>) -> Result<Value> { args.get("a"); Ok(Value::Null) }
            fn second(args: &HashMap<String, Value>) -> Result<Value> { args.get("b"); Ok(Value::Null) }
            fn setup(tera: &mut Tera) {
                tera.register_function("pick", first);
                tera.register_function("pick", second);
                tera.register_function("range", second);
            }
        """.trimIndent())
        assertEquals(listOf("b"), found.named("function", "pick").args())
        assertEquals(1, found.count { it.def.name == "pick" })
        assertEquals("function", found.named("function", "range").kind)
    }

    fun testOtherEnginesAndDynamicNamesAreIgnored() {
        val found = find("""
            fn setup(tera: &mut Tera, hbs: &mut Handlebars, name: String) {
                hbs.register_helper("loud", Box::new(loud));
                tera.register_function(&name, f);
                tera.register_function(name_from_config(), f);
                tera.register_template_string("x", "y");
            }
        """.trimIndent())
        assertEmpty(found)
    }

    fun testDirectoriesNamedLikeRustFilesAreIgnored() {
        myFixture.tempDirFixture.findOrCreateDir("vendor/odd.rs")
        val found = find(rocketStyle)
        assertEquals(setOf("url_for", "shorten", "blank"), found.map { it.def.name }.toSet())
        myFixture.tempDirFixture.findOrCreateDir("templates/odd.tera")
        val template = myFixture.addFileToProject("templates/index.html.tera", "")
        assertNull(TeraRustContextProvider().resolve(project, template.virtualFile))
    }

    fun testRustFilesUnderTargetAreIgnored() {
        myFixture.addFileToProject("target/debug/build/gen.rs", header +
            """fn g(tera: &mut Tera) { tera.register_function("generated", f); }""")
        assertEmpty(find("fn nothing() {}"))
    }

    fun testUnresolvableCalleeStillRegistersTheName() {
        val f = find("""fn setup(tera: &mut Tera) { tera.register_function("mystery", from_another_crate::f); }""").named("function", "mystery")
        assertEmpty(f.args())
        assertEquals("\"mystery\"", f.target!!.text)
        assertTrue(f.def.description.startsWith("registered in Rust (main.rs:"))
    }

    // ── through the registered provider, into the editor features ────────────

    private fun openTemplate(name: String, text: String): com.intellij.psi.PsiFile {
        val file = myFixture.addFileToProject("templates/$name", text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        return file
    }

    fun testRegisteredProviderFeedsCompletion() {
        myFixture.addFileToProject("src/main.rs", header + rocketStyle)
        assertEquals(setOf("url_for", "shorten", "blank"), TeraContexts.customBuiltins(project).map { it.def.name }.toSet())
        openTemplate("a.html.tera", "{{ url_f<caret> }}")
        myFixture.completeBasic()
        assertEquals("{{ url_for() }}", myFixture.editor.document.text)

        openTemplate("b.html.tera", "{{ title | shor<caret> }}")
        myFixture.completeBasic()
        assertEquals("{{ title | shorten }}", myFixture.editor.document.text)
    }

    fun testArgumentCompletionHoverAndGotoForRegisteredFunction() {
        myFixture.addFileToProject("src/main.rs", header + rocketStyle)
        val file = openTemplate("c.html.tera", "{{ url_for(<caret>) }}")
        myFixture.completeBasic()
        assertEquals(listOf("name", "query"), myFixture.lookupElementStrings)

        val text = "{{ url_f<caret>or(name=\"home\") }}"
        val f2 = openTemplate("d.html.tera", text)
        val caret = text.indexOf("<caret>")
        val provider = TeraDocumentationProvider()
        val element = provider.getCustomDocumentationElement(myFixture.editor, f2, null, caret)!!
        val doc = provider.generateDoc(element, null)!!
        assertTrue(doc, doc.contains("Builds an absolute URL from a route name."))
        assertTrue(doc, doc.contains("registered in Rust (main.rs:"))
        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, caret).toList()
        assertEquals(listOf("url_for"), targets.map { it.text })
        assertEquals("main.rs", targets.single().containingFile.name)
        assertNotNull(file)
    }
}
