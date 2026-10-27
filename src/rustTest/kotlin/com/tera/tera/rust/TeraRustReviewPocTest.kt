package com.tera.tera.rust

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tera.tera.TeraContexts
import java.io.File

/**
 * `examples/review-poc` renders through its own `Templates::render(name, &ctx)` method with a generic, flattened
 * page shell (`Page<T>`), and its layouts and partials are never rendered directly: their variables come from the
 * pages that extend or include them. The plugin has to follow all of that.
 */
class TeraRustReviewPocTest : BasePlatformTestCase() {

    private val root = File("examples/review-poc")

    private fun load() {
        myFixture.addFileToProject("review-poc/Cargo.toml", File(root, "Cargo.toml").readText())
        root.resolve("src").walkTopDown().filter { it.isFile && it.extension == "rs" }.forEach {
            myFixture.addFileToProject("review-poc/src/" + it.relativeTo(root.resolve("src")).path, it.readText())
        }
        root.resolve("templates").walkTopDown().filter { it.isFile && it.name.endsWith(".tera") }.forEach {
            myFixture.addFileToProject("review-poc/templates/" + it.relativeTo(root.resolve("templates")).path, it.readText())
        }
    }

    private fun names(template: String): Set<String> {
        val vf = myFixture.findFileInTempDir("review-poc/templates/$template")
        val psi = myFixture.psiManager.findFile(vf)!!
        return TeraContexts.forFile(psi)?.variables?.map { it.name }?.toSet().orEmpty()
    }

    fun testAPageGetsItsShellAndItsOwnData() {
        load()
        val detail = names("pages/detail.html.tera")
        // From `Page<T>`: title, active, user, flash, and `T` flattened in; then `DetailData` and the form `Context`.
        for (n in listOf("title", "active", "user", "flash", "request", "errors", "values")) assertTrue("$n in $detail", n in detail)
    }

    fun testEveryPageKnowsItsOwnVariables() {
        load()
        val expected = mapOf(
            "pages/dashboard.html.tera" to listOf("stats", "recent", "waiting"),
            "pages/requests.html.tera" to listOf("requests", "status", "department", "departments", "counts"),
            "pages/new.html.tera" to listOf("departments", "categories", "today", "errors", "values"),
            "pages/stats.html.tera" to listOf("stats"),
            "pages/not_found.html.tera" to listOf("path"),
        )
        for ((t, vars) in expected) {
            val found = names(t)
            for (v in vars + listOf("title", "user")) assertTrue("$t: $v in $found", v in found)
        }
    }

    fun testLayoutsAndPartialsInheritTheContextOfThePagesThatUseThem() {
        load()
        for (t in listOf("layouts/section.html.tera", "layouts/app.html.tera", "partials/flash.html.tera", "base.html.tera")) {
            val found = names(t)
            for (v in listOf("title", "active", "user", "flash")) assertTrue("$t: $v in $found", v in found)
        }
    }

    fun testTheRocketFormContextHasItsFields() {
        load()
        val vf = myFixture.findFileInTempDir("review-poc/templates/pages/new.html.tera")
        val ctx = TeraContexts.forFile(myFixture.psiManager.findFile(vf)!!)!!
        assertTrue(ctx.variables.map { it.name }.containsAll(listOf("errors", "values", "form_errors", "data_fields")))
    }
}
