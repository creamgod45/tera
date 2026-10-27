package com.tera.tera.rust

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The plugin must not depend on what a project calls its render function: a template path passed to any call whose
 * callee takes something serializable is a render site, and the serializable argument is the context.
 */
class TeraRustAnyRenderFunctionTest : BasePlatformTestCase() {

    private fun names(templatePath: String, rust: String): List<String>? {
        myFixture.addFileToProject("src/main.rs", "use serde::Serialize;\n$rust")
        val template = myFixture.addFileToProject(templatePath, "")
        return TeraRustContextProvider().resolve(project, template.virtualFile)?.variables?.map { it.name }
    }

    private val data = "#[derive(Serialize)] struct Data { one: String, two: u32 }\n"

    fun testAFreeFunctionWithAnyNameAndTheContextFirst() {
        assertEquals(listOf("one", "two"), names("templates/a.html.tera", data + """
            fn paint<T: Serialize>(data: &T, view: &str) -> String { String::new() }
            fn go() { paint(&Data { one: String::new(), two: 1 }, "a"); }
        """.trimIndent()))
    }

    fun testAMethodOfAnotherEngineWithAnImplSerializeParameter() {
        assertEquals(listOf("one", "two"), names("templates/pages/b.html.tera", data + """
            struct Views;
            impl Views { fn show(&self, extra: u8, page: &str, model: impl Serialize) {} }
            fn go(v: &Views) { v.show(1, "pages/b.html", Data { one: String::new(), two: 1 }); }
        """.trimIndent()))
    }

    fun testABoundInAWhereClause() {
        assertEquals(listOf("one", "two"), names("templates/c.html.tera", data + """
            fn html<C>(name: &str, ctx: C) where C: Serialize + Send {}
            fn go() { html("c", Data { one: String::new(), two: 1 }); }
        """.trimIndent()))
    }

    fun testANonRenderCallThatTakesNoSerializableValueIsNotASite() {
        assertNull(names("templates/d.html.tera", data + """
            fn log(name: &str, n: u32) {}
            fn go() { log("d", 3); }
        """.trimIndent()))
    }
}

/** From the Rust string that names a template: open it. */
class TeraRustTemplateStringNavigationTest : BasePlatformTestCase() {

    private fun load() {
        myFixture.addFileToProject("app/Cargo.toml", "[package]\nname=\"app\"\n")
        myFixture.addFileToProject("app/Rocket.toml", "[default]\ntemplate_dir = \"views\"\n")
        myFixture.addFileToProject("app/views/pages/x.html.tera", "{{ one }}")
        myFixture.addFileToProject("app/src/main.rs", """
            use serde::Serialize;
            #[derive(Serialize)] struct D { one: String }
            fn show(name: &str, d: impl Serialize) {}
            fn go() { show("pages/x.html", D { one: String::new() }); let other = "not a template"; }
        """.trimIndent())
    }

    fun testTheGutterOfTheStringOpensTheTemplate() {
        load()
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/src/main.rs"))
        val tips = myFixture.findAllGutters().mapNotNull { it.tooltipText }.filter { it.startsWith("Open template") }
        assertEquals(tips.toString(), 1, tips.size)
        assertTrue(tips.single(), tips.single().contains("x.html.tera") && tips.single().contains("`views`"))
    }

    fun testCmdClickOnTheStringGoesToTheTemplate() {
        load()
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/src/main.rs"))
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("pages/x") + 2)
        val target = com.intellij.codeInsight.navigation.actions.GotoDeclarationAction.findTargetElement(
            project, myFixture.editor, myFixture.editor.caretModel.offset)
        assertEquals("x.html.tera", target?.containingFile?.name)
    }

    fun testTheContextComesFromTheConfiguredRoot() {
        load()
        val t = myFixture.findFileInTempDir("app/views/pages/x.html.tera")
        val vars = com.tera.tera.TeraContexts.forFile(myFixture.psiManager.findFile(t)!!)?.variables?.map { it.name }
        assertEquals(listOf("one"), vars)
    }
}

class TeraRustConstPathAndCompletionTest : BasePlatformTestCase() {

    fun testAConstantThatHoldsThePathIsARenderSite() {
        myFixture.addFileToProject("src/main.rs", """
            use serde::Serialize;
            #[derive(Serialize)] struct D { one: String }
            const PAGE: &str = "pages/c";
            fn show(name: &str, d: impl Serialize) {}
            fn go() { show(PAGE, D { one: String::new() }); }
        """.trimIndent())
        val t = myFixture.addFileToProject("templates/pages/c.html.tera", "")
        assertEquals(listOf("one"), TeraRustContextProvider().resolve(project, t.virtualFile)?.variables?.map { it.name })
    }

    private fun complete(rust: String): List<String> {
        myFixture.addFileToProject("app/Cargo.toml", "[package]\nname=\"app\"\n")
        myFixture.addFileToProject("app/templates/pages/detail.html.tera", "")
        myFixture.addFileToProject("app/templates/pages/list.html.tera", "")
        myFixture.addFileToProject("app/templates/base.html.tera", "")
        myFixture.configureByText("main.rs", rust)
        val moved = myFixture.addFileToProject("app/src/main.rs", myFixture.file.text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(moved.virtualFile)
        myFixture.editor.caretModel.moveToOffset(rust.indexOf("<caret>"))
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testTemplatePathsAreOfferedInsideTheStringOfACall() {
        val names = complete("fn go() { show(\"pa<caret>\", 1); }")
        assertEquals(listOf("pages/detail", "pages/list"), names.sorted())
    }

    fun testExtensionsAreUsedWhenTheProjectWritesThem() {
        myFixture.addFileToProject("app/Cargo.toml", "[package]\nname=\"app\"\n")
        myFixture.addFileToProject("app/templates/pages/detail.html.tera", "")
        myFixture.addFileToProject("app/templates/base.html.tera", "")
        val code = "fn go() { show(\"base.html\", 1); show(\"pages/detail.html\", 2); show(\"ba<caret>\", 3); }"
        val moved = myFixture.addFileToProject("app/src/main.rs", code.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(moved.virtualFile)
        myFixture.editor.caretModel.moveToOffset(code.indexOf("<caret>"))
        myFixture.completeBasic()
        // A single candidate is inserted at once, so no lookup is left.
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.endsWith("show(\"base.html\", 3); }"))
    }

    fun testNothingIsOfferedOutsideACallsString() {
        assertEmpty(complete("fn go() { let x = \"pa<caret>\"; }"))
    }
}

/** The type a Rust filter produces, read from its code, for the loops offered after it. */
class TeraRustFilterReturnsTest : BasePlatformTestCase() {

    private val header = "use std::collections::HashMap;\nuse rocket_dyn_templates::tera::{Tera, Value, Result, to_value};\n"

    private var files = 0

    /** Each call adds its own source file; the filter is looked up by the name it is registered under. */
    private fun returns(rust: String, name: String): com.tera.tera.TeraType? {
        val unique = "f${files++}"
        myFixture.addFileToProject("src/$unique.rs", header + rust.replace("\"f\"", "\"$unique\"").replace("register_filter(\"$unique\", f)", "register_filter(\"$unique\", f)"))
        return RustCustomBuiltins.find(project).first { it.kind == "filter" && it.def.name == unique }.returns
    }

    private val register = "pub fn customize(tera: &mut Tera) { tera.register_filter(\"f\", f); }\n"

    fun testTheDocLineStatesIt() {
        assertEquals(com.tera.tera.TeraType.ArrayOf(com.tera.tera.TeraType.Scalar("string")), returns("""
            /// Splits tags.
            /// @returns array<string>
            fn f(value: &Value, _: &HashMap<String, Value>) -> Result<Value> { Ok(value.clone()) }
        """.trimIndent() + "\n" + register, "f"))
    }

    fun testToValueOfAVecIsAList() {
        val t = returns("""
            fn f(value: &Value, _: &HashMap<String, Value>) -> Result<Value> {
                let parts: Vec<String> = value.as_str().unwrap_or("").split(',').map(String::from).collect();
                Ok(to_value(parts).unwrap())
            }
        """.trimIndent() + "\n" + register, "f")
        assertEquals(com.tera.tera.TeraType.ArrayOf(com.tera.tera.TeraType.Scalar("string")), t)
    }

    fun testValueArrayAndObjectAndJson() {
        assertTrue(returns("fn f(v: &Value, _: &HashMap<String, Value>) -> Result<Value> { Ok(Value::Array(vec![])) }\n$register", "f") is com.tera.tera.TeraType.ArrayOf)
        assertTrue(returns("fn f(v: &Value, _: &HashMap<String, Value>) -> Result<Value> { Ok(Value::Object(Default::default())) }\n$register", "f") is com.tera.tera.TeraType.MapOf)
    }

    fun testAClosureIsReadToo() {
        myFixture.addFileToProject("src/main.rs", header + """
            pub fn customize(tera: &mut Tera) {
                tera.register_filter("f", |v: &Value, _: &HashMap<String, Value>| Ok(Value::String("x".into())));
            }
        """.trimIndent())
        assertEquals(com.tera.tera.TeraType.Scalar("string"), RustCustomBuiltins.find(project).first { it.def.name == "f" }.returns)
    }

    fun testDisagreeingOrUnreadableReturnsGiveNothing() {
        assertNull(returns("""
            fn f(v: &Value, _: &HashMap<String, Value>) -> Result<Value> {
                if v.is_null() { return Ok(Value::Array(vec![])); }
                Ok(Value::String("x".into()))
            }
        """.trimIndent() + "\n" + register, "f"))
        assertNull(returns("fn f(v: &Value, _: &HashMap<String, Value>) -> Result<Value> { Ok(v.clone()) }\n$register", "f"))
    }
}

class TeraRustManualRootTest : BasePlatformTestCase() {
    fun testAHandChosenRootDecidesTheTemplateNameForTheContext() {
        com.tera.tera.TeraSettings.getInstance(project).loadState(com.tera.tera.TeraSettingsState())
        com.tera.tera.TeraSettings.getInstance(project).state.templateRoots = "site/pages-dir"
        myFixture.addFileToProject("site/src/main.rs", """
            use serde::Serialize;
            #[derive(Serialize)] struct D { one: String }
            fn show(name: &str, d: impl Serialize) {}
            fn go() { show("blog/post", D { one: String::new() }); }
        """.trimIndent())
        val t = myFixture.addFileToProject("site/pages-dir/blog/post.html.tera", "")
        assertEquals(listOf("one"), TeraRustContextProvider().resolve(project, t.virtualFile)?.variables?.map { it.name })
    }
}
