package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Template paths written in Rust, read against the template directory and working directory the project sets. */
class TeraTemplateRootsTest : BasePlatformTestCase() {

    private fun rs(path: String) = myFixture.findFileInTempDir(path)
    private fun resolve(from: String, name: String) =
        TeraTemplateRoots.resolve(project, rs(from), name).map { it.first.path.substringAfter("/src/") }

    private fun crate(name: String = "app") {
        myFixture.addFileToProject("$name/Cargo.toml", "[package]\nname=\"$name\"\n")
    }

    fun testTheDefaultTemplatesDirectory() {
        crate(); myFixture.addFileToProject("app/src/main.rs", "fn main(){}")
        myFixture.addFileToProject("app/templates/pages/x.html.tera", "")
        assertEquals(listOf("app/templates/pages/x.html.tera"), resolve("app/src/main.rs", "pages/x"))
        assertEquals(listOf("app/templates/pages/x.html.tera"), resolve("app/src/main.rs", "pages/x.html"))
        assertEquals(listOf("app/templates/pages/x.html.tera"), resolve("app/src/main.rs", "./pages/x.html.tera"))
    }

    fun testRocketTomlTemplateDir() {
        crate(); myFixture.addFileToProject("app/src/main.rs", "fn main(){}")
        myFixture.addFileToProject("app/Rocket.toml", "[default]\ntemplate_dir = \"views\"\n")
        myFixture.addFileToProject("app/views/a.html.tera", "")
        assertEquals(listOf("app/views/a.html.tera"), resolve("app/src/main.rs", "a"))
    }

    fun testTheGlobOfTeraNew() {
        crate()
        myFixture.addFileToProject("app/src/main.rs", "fn main(){ let t = Tera::new(\"web/**/*.html\").unwrap(); }")
        myFixture.addFileToProject("app/web/blog/p.html.tera", "")
        assertEquals(listOf("app/web/blog/p.html.tera"), resolve("app/src/main.rs", "blog/p"))
    }

    fun testAGlobBuiltFromTheManifestDirectory() {
        crate()
        myFixture.addFileToProject("app/src/main.rs", "fn main(){ Tera::new(concat!(env!(\"CARGO_MANIFEST_DIR\"), \"/pages/**/*\")); }")
        myFixture.addFileToProject("app/pages/q.html.tera", "")
        assertEquals(listOf("app/pages/q.html.tera"), resolve("app/src/main.rs", "q"))
    }

    fun testRelativeMacroAndMergedConfiguration() {
        crate()
        myFixture.addFileToProject("app/src/main.rs", "fn main(){ let figment = rocket::Config::figment().merge((\"template_dir\", \"tpl\")); }")
        myFixture.addFileToProject("app/tpl/m.html.tera", "")
        assertEquals(listOf("app/tpl/m.html.tera"), resolve("app/src/main.rs", "m"))
    }

    fun testAChangedWorkingDirectoryIsTheBaseOfRelativePaths() {
        crate()
        myFixture.addFileToProject("app/src/main.rs", "fn main(){ std::env::set_current_dir(\"site\").unwrap(); }")
        myFixture.addFileToProject("app/site/templates/w.html.tera", "")
        myFixture.addFileToProject("app/templates/w.html.tera", "")
        // The working directory wins; the crate directory is only the fallback.
        assertEquals(listOf("app/site/templates/w.html.tera"), resolve("app/src/main.rs", "w"))
        val why = TeraTemplateRoots.roots(project, rs("app/src/main.rs")).map { it.why }
        assertTrue(why.toString(), why.first().contains("working directory site"))
    }

    fun testTwoCratesKeepTheirOwnTemplates() {
        crate("one"); crate("two")
        myFixture.addFileToProject("one/src/main.rs", "fn main(){}")
        myFixture.addFileToProject("two/src/main.rs", "fn main(){}")
        myFixture.addFileToProject("one/templates/i.html.tera", "")
        myFixture.addFileToProject("two/templates/i.html.tera", "")
        assertEquals(listOf("one/templates/i.html.tera"), resolve("one/src/main.rs", "i"))
        assertEquals(listOf("two/templates/i.html.tera"), resolve("two/src/main.rs", "i"))
    }

    fun testSeveralExtensionsGiveSeveralTargets() {
        crate(); myFixture.addFileToProject("app/src/main.rs", "fn main(){}")
        myFixture.addFileToProject("app/templates/macros.html.tera", "")
        myFixture.addFileToProject("app/templates/macros.txt.tera", "")
        assertEquals(2, resolve("app/src/main.rs", "macros").size)
    }

    fun testUnknownNamesAndGarbageResolveToNothing() {
        crate(); myFixture.addFileToProject("app/src/main.rs", "fn main(){}")
        assertEmpty(resolve("app/src/main.rs", "nope"))
        assertEmpty(resolve("app/src/main.rs", ""))
        assertEmpty(resolve("app/src/main.rs", "{}/x"))
    }

    fun testNamesOfATemplateFollowTheConfiguredRoot() {
        crate(); myFixture.addFileToProject("app/src/main.rs", "fn main(){}")
        myFixture.addFileToProject("app/Rocket.toml", "template_dir = \"views\"\n")
        val t = myFixture.addFileToProject("app/views/pages/d.html.tera", "").virtualFile
        assertTrue(TeraTemplateRoots.namesOf(project, t).containsAll(listOf("pages/d.html", "pages/d")))
    }

    fun testLiteralAtFindsTheStringUnderTheCaret() {
        val text = "fn a() { tpl.render(\"pages/x.html\", &c); other(\"zzz\"); }"
        assertEquals("pages/x.html", TeraRustTemplateStrings.literalAt(text, text.indexOf("pages") + 3))
        assertEquals("zzz", TeraRustTemplateStrings.literalAt(text, text.indexOf("zzz")))
        assertNull(TeraRustTemplateStrings.literalAt(text, text.indexOf("tpl")))
    }
}
