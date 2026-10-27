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
