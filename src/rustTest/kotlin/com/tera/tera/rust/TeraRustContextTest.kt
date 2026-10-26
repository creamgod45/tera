package com.tera.tera.rust

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tera.tera.TeraContextResult
import com.tera.tera.TeraContexts
import com.tera.tera.TeraExternalAnnotator
import com.tera.tera.TeraField
import com.tera.tera.TeraType
import com.tera.tera.TeraVariables

/**
 * Render contexts derived from Rust source through the Rust plugin. Run with -PrustRoverHome=<RustRover.app>.
 * Rust code mirrors Rocket's todo, templating and cookies examples; types from crates that are not
 * part of the project (e.g. rocket::form::Context) must never be guessed.
 */
class TeraRustContextTest : BasePlatformTestCase() {

    private val serde = "use serde::Serialize;\nuse rocket_dyn_templates::{Template, context};\n"

    private fun resolve(templatePath: String, rust: String): TeraContextResult? {
        myFixture.addFileToProject("src/main.rs", serde + rust)
        val template = myFixture.addFileToProject(templatePath, "")
        return TeraRustContextProvider().resolve(project, template.virtualFile)
    }

    private fun TeraContextResult.field(name: String): TeraField = variables.first { it.name == name }
    private fun TeraContextResult.names() = variables.map { it.name }

    // ── template names ───────────────────────────────────────────────────────

    fun testTemplateNamesFollowRocketRules() {
        fun name(path: String) = TeraRustContextProvider().templateName(myFixture.addFileToProject(path, "").virtualFile)
        assertEquals("index", name("templates/index.html.tera"))
        assertEquals("tera/index", name("templates/tera/index.html.tera"))
        assertEquals("tera/error/404", name("templates/tera/error/404.html.tera"))
        assertEquals("macros", name("examples/forms/templates/macros.html.tera"))
        assertEquals("plain", name("elsewhere/plain.tera"))
    }

    // ── struct contexts ──────────────────────────────────────────────────────

    fun testStructLiteralFieldsTypesDocsAndTargets() {
        val ctx = resolve("templates/index.html.tera", """
            #[derive(Serialize)]
            struct Ctx {
                /// The display name.
                /// Shown in the header.
                name: String,
                age: u32,
                ratio: f64,
                admin: bool,
                tags: Vec<String>,
                nick: Option<String>,
            }
            fn index() -> Template {
                Template::render("index", &Ctx { name: String::new(), age: 1, ratio: 0.5, admin: false, tags: vec![], nick: None })
            }
        """.trimIndent())!!
        assertTrue(ctx.complete)
        assertEquals(listOf("name", "age", "ratio", "admin", "tags", "nick"), ctx.names())
        assertEquals(TeraType.Scalar("string"), ctx.field("name").type)
        assertEquals("String", ctx.field("name").rustType)
        assertEquals("The display name.\nShown in the header.", ctx.field("name").doc)
        assertEquals(TeraType.Scalar("number"), ctx.field("age").type)
        assertEquals(TeraType.Scalar("number"), ctx.field("ratio").type)
        assertEquals(TeraType.Scalar("bool"), ctx.field("admin").type)
        assertEquals(TeraType.ArrayOf(TeraType.Scalar("string")), ctx.field("tags").type)
        assertEquals(TeraType.Scalar("string"), ctx.field("nick").type)
        assertEquals("Option<String>", ctx.field("nick").rustType)
        val target = ctx.field("name").target!!
        assertEquals("name", target.text)
        assertEquals("main.rs", target.containingFile.name)
    }

    fun testRocketTodoContextThroughConstructorFunctions() {
        val ctx = resolve("templates/index.html.tera", """
            #[derive(Serialize)]
            struct Task { id: Option<i32>, description: String, completed: bool }

            #[derive(Debug, Serialize)]
            struct Context {
                flash: Option<(String, String)>,
                tasks: Vec<Task>
            }
            impl Context {
                pub async fn err<M: std::fmt::Display>(conn: &DbConn, msg: M) -> Context {
                    Context { flash: Some(("error".into(), msg.to_string())), tasks: vec![] }
                }
                pub async fn raw(conn: &DbConn, flash: Option<(String, String)>) -> Context {
                    Context { flash, tasks: vec![] }
                }
            }
            async fn a(conn: DbConn) -> Result<Template, Template> {
                Err(Template::render("index", Context::err(&conn, "Failed to toggle task.").await))
            }
            async fn b(conn: DbConn, flash: Option<(String, String)>) -> Template {
                Template::render("index", Context::raw(&conn, flash).await)
            }
        """.trimIndent())!!
        assertTrue(ctx.complete)
        assertEquals(listOf("flash", "tasks"), ctx.names())
        assertEquals(TeraType.Tuple(listOf(TeraType.Scalar("string"), TeraType.Scalar("string"))), ctx.field("flash").type)
        val task = (ctx.field("tasks").type as TeraType.ArrayOf).element as TeraType.Struct
        assertEquals("Task", task.name)
        assertEquals(listOf("id", "description", "completed"), task.fields.map { it.name })
        assertEquals(listOf("number", "string", "bool"), task.fields.map { it.type.display() })
        assertEquals(2, ctx.sources.size)
    }

    fun testDefaultConstructedContext() {
        val ctx = resolve("templates/index.html.tera", """
            #[derive(Default, Serialize)]
            struct Context { errors: Vec<String>, title: String }
            fn index() -> Template { Template::render("index", &Context::default()) }
        """.trimIndent())!!
        assertEquals(listOf("errors", "title"), ctx.names())
        assertTrue(ctx.complete)
    }

    // ── context! macro ───────────────────────────────────────────────────────

    fun testRocketTemplatingContextMacro() {
        val ctx = resolve("templates/tera/index.html.tera", """
            fn hello(name: &str) -> Template {
                Template::render("tera/index", context! {
                    title: "Hello",
                    name: Some(name),
                    items: vec!["One", "Two", "Three"],
                })
            }
        """.trimIndent())!!
        assertTrue(ctx.complete)
        assertEquals(listOf("title", "name", "items"), ctx.names())
        assertEquals(TeraType.Scalar("string"), ctx.field("title").type)
        assertEquals(TeraType.Scalar("string"), ctx.field("name").type)
        assertEquals(TeraType.ArrayOf(TeraType.Scalar("string")), ctx.field("items").type)
        assertEquals("title", ctx.field("title").target!!.text)
    }

    fun testContextMacroShorthandUsesLocalVariableTypes() {
        val ctx = resolve("templates/message.html.tera", """
            fn index() -> Template {
                let message = String::from("hi");
                let present = true;
                let count = 3;
                Template::render("message", context! { present, message, count, user_id: count })
            }
        """.trimIndent())!!
        assertEquals(TeraType.Scalar("bool"), ctx.field("present").type)
        assertEquals(TeraType.Scalar("string"), ctx.field("message").type)
        assertEquals(TeraType.Scalar("number"), ctx.field("count").type)
        assertEquals(TeraType.Scalar("number"), ctx.field("user_id").type)
        assertTrue(ctx.complete)
    }

    fun testContextMacroValueGuesses() {
        val ctx = resolve("templates/g.html.tera", """
            fn g() -> Template {
                Template::render("g", context! {
                    a: 1, b: 2.5, c: true, d: format!("{}", 1), e: "x".to_string(),
                    f: [1, 2], g: Some(1), h: something_unknown(), i: nested(1, (2, 3)),
                })
            }
        """.trimIndent())!!
        assertEquals(listOf("number", "number", "bool", "string", "string", "array<number>", "number", "any", "any"),
            ctx.variables.map { it.type.display() })
        assertEquals(9, ctx.variables.size)
    }

    // ── serde ────────────────────────────────────────────────────────────────

    fun testSerdeRenameSkipAndRenameAll() {
        val ctx = resolve("templates/s.html.tera", """
            #[derive(Serialize)]
            #[serde(rename_all = "camelCase")]
            struct Ctx {
                first_name: String,
                #[serde(rename = "Mail")] email_address: String,
                #[serde(skip)] secret: String,
                #[serde(skip_serializing)] other: String,
                #[serde(rename(serialize = "ser_name", deserialize = "de_name"))] both: String,
            }
            fn s() -> Template { Template::render("s", &Ctx { first_name: String::new(), email_address: String::new(), secret: String::new(), other: String::new(), both: String::new() }) }
        """.trimIndent())!!
        assertEquals(listOf("firstName", "Mail", "ser_name"), ctx.names())
    }

    fun testSerdeRenameAllRules() {
        for ((rule, expected) in mapOf(
            "lowercase" to "my_field", "UPPERCASE" to "MY_FIELD", "PascalCase" to "MyField", "camelCase" to "myField",
            "snake_case" to "my_field", "SCREAMING_SNAKE_CASE" to "MY_FIELD", "kebab-case" to "my-field",
            "SCREAMING-KEBAB-CASE" to "MY-FIELD",
        )) assertEquals(rule, expected, RustTypes.serdeRename(rule, "my_field"))
    }

    fun testFlattenedStructIsMergedAndFlattenedMapIsInexact() {
        val merged = resolve("templates/f.html.tera", """
            #[derive(Serialize)] struct Base { id: u32, created: String }
            #[derive(Serialize)] struct Ctx { #[serde(flatten)] base: Base, title: String }
            fn f() -> Template { Template::render("f", &Ctx { base: Base { id: 1, created: String::new() }, title: String::new() }) }
        """.trimIndent())!!
        assertEquals(listOf("id", "created", "title"), merged.names())
        assertTrue(merged.complete)
    }

    fun testFlattenedMapMakesContextIncomplete() {
        val ctx = resolve("templates/m.html.tera", """
            #[derive(Serialize)] struct Ctx { #[serde(flatten)] extra: std::collections::HashMap<String, String>, title: String }
            fn m() -> Template { Template::render("m", &Ctx { extra: Default::default(), title: String::new() }) }
        """.trimIndent())!!
        assertEquals(listOf("title"), ctx.names())
        assertFalse("unknown extra keys exist at runtime", ctx.complete)
    }

    // ── nested and generic types ─────────────────────────────────────────────

    fun testNestedStructsCollectionsAndWrappers() {
        val ctx = resolve("templates/n.html.tera", """
            use std::collections::{HashMap, BTreeMap, HashSet};
            #[derive(Serialize)] struct Address { city: String, zip: Option<String> }
            #[derive(Serialize)] struct Meters(f64);
            #[derive(Serialize)] struct Pair(String, u8);
            #[derive(Serialize)] struct Ctx {
                home: Address,
                others: Vec<Address>,
                by_name: HashMap<String, Vec<String>>,
                sorted: BTreeMap<String, Address>,
                unique: HashSet<u8>,
                boxed: Option<Box<Address>>,
                length: Meters,
                pair: Pair,
            }
            fn n(c: Ctx) -> Template { Template::render("n", &c) }
        """.trimIndent())!!
        assertEquals("Address", ctx.field("home").type.display())
        assertEquals(listOf("city", "zip"), (ctx.field("home").type as TeraType.Struct).fields.map { it.name })
        assertEquals("array<Address>", ctx.field("others").type.display())
        assertEquals("map<array<string>>", ctx.field("by_name").type.display())
        assertEquals("map<Address>", ctx.field("sorted").type.display())
        assertEquals("array<number>", ctx.field("unique").type.display())
        assertEquals("Address", ctx.field("boxed").type.display())
        assertEquals("number", ctx.field("length").type.display())
        assertEquals("(string, number)", ctx.field("pair").type.display())
    }

    fun testRecursiveTypesTerminate() {
        val ctx = resolve("templates/r.html.tera", """
            #[derive(Serialize)] struct Node { name: String, children: Vec<Node>, parent: Option<Box<Node>> }
            fn r(n: Node) -> Template { Template::render("r", &n) }
        """.trimIndent())!!
        assertEquals(listOf("name", "children", "parent"), ctx.names())
        val children = ctx.field("children").type as TeraType.ArrayOf
        assertEquals(TeraType.Unknown, children.element)
    }

    // ── completeness ─────────────────────────────────────────────────────────

    fun testExternalCrateTypesAreNeverGuessed() {
        val ctx = resolve("templates/e.html.tera", """
            fn e(form: Form<Contextual<'_, Submit<'_>>>) -> Template {
                Template::render("e", &form.context)
            }
        """.trimIndent())!!
        assertEmpty(ctx.variables)
        assertFalse(ctx.complete)
    }

    fun testSeveralRenderSitesAreMerged() {
        val ctx = resolve("templates/index.html.tera", """
            #[derive(Serialize)] struct A { one: String }
            #[derive(Serialize)] struct B { two: String, one: u8 }
            fn a() -> Template { Template::render("index", &A { one: String::new() }) }
            fn b() -> Template { Template::render("index", &B { two: String::new(), one: 1 }) }
            fn c() -> Template { Template::render("other", &A { one: String::new() }) }
        """.trimIndent())!!
        assertEquals(listOf("one", "two"), ctx.names())
        assertEquals("string", ctx.field("one").type.display())
        assertEquals(2, ctx.sources.size)
        assertTrue(ctx.complete)
    }

    fun testOneUnresolvableSiteKeepsKnownVariablesButIsIncomplete() {
        val ctx = resolve("templates/index.html.tera", """
            #[derive(Serialize)] struct A { one: String }
            fn a() -> Template { Template::render("index", &A { one: String::new() }) }
            fn b(x: Unknown) -> Template { Template::render("index", x.something()) }
        """.trimIndent())!!
        assertEquals(listOf("one"), ctx.names())
        assertFalse(ctx.complete)
    }

    fun testTemplateThatIsNotRenderedHasNoContext() {
        assertNull(resolve("templates/lonely.html.tera", """
            #[derive(Serialize)] struct A { one: String }
            fn a() -> Template { Template::render("index", &A { one: String::new() }) }
        """.trimIndent()))
    }

    fun testRustFilesUnderTargetAreIgnored() {
        myFixture.addFileToProject("target/debug/generated.rs", serde +
            """fn x() -> Template { Template::render("t", context! { ghost: 1 }) }""")
        val template = myFixture.addFileToProject("templates/t.html.tera", "")
        assertNull(TeraRustContextProvider().resolve(project, template.virtualFile))
    }

    // ── end to end through the registered provider ───────────────────────────

    private val todoRust = """
        #[derive(Serialize)]
        struct Task {
            id: Option<i32>,
            /// What needs to be done.
            description: String,
            completed: bool,
        }
        #[derive(Serialize)]
        struct Context {
            /// Message shown above the list.
            flash: Option<(String, String)>,
            tasks: Vec<Task>,
        }
        fn index() -> Template { Template::render("index", &Context { flash: None, tasks: vec![] }) }
    """.trimIndent()

    private fun openTemplate(name: String, text: String): com.intellij.psi.PsiFile {
        val file = myFixture.addFileToProject("templates/$name", text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        return file
    }

    fun testRegisteredProviderFeedsCompletionAndDiagnostics() {
        myFixture.addFileToProject("src/main.rs", serde + todoRust)
        val file = openTemplate("index.html.tera", "{% for task in tasks %}{{ task.desc<caret> }}{% endfor %}{{ taks }}")
        assertNotNull("provider must be registered through the optional Rust dependency", TeraContexts.forFile(file))
        myFixture.completeBasic()
        assertTrue(myFixture.editor.document.text.contains("task.description"))

        val annotator = TeraExternalAnnotator()
        val errors = annotator.doAnnotate(annotator.collectInformation(file)!!)
        assertEquals(listOf("taks"), errors.map { it.range.substring(file.text) })
    }

    fun testRocketTodoTemplateIsCleanAgainstItsRustContext() {
        myFixture.addFileToProject("src/main.rs", serde + todoRust)
        val text = javaClass.getResource("/rocket/examples/todo/static/index.html.tera")!!.readText()
        val file = myFixture.addFileToProject("templates/index.html.tera", text)
        val ctx = TeraContexts.forFile(file)!!
        assertTrue(ctx.complete)
        assertEmpty(TeraVariables.check(text, ctx))
    }

    fun testGotoVariableAndFieldLandsInRustSource() {
        myFixture.addFileToProject("src/main.rs", serde + todoRust)
        val text = "{% for task in tasks %}{{ task.descr<caret>iption }}{% endfor %}"
        openTemplate("index.html.tera", text)
        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, text.indexOf("<caret>")).toList()
        assertEquals(1, targets.size)
        assertEquals("description", targets.single().text)
        assertEquals("main.rs", targets.single().containingFile.name)

        val text2 = "{{ ta<caret>sks }}"
        openTemplate("two.html.tera", text2)
        // `two` is not rendered anywhere, so nothing to jump to
        assertEmpty(GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, text2.indexOf("<caret>")).toList())
    }
}
