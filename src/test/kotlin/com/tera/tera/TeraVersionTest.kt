package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import junit.framework.TestCase

class CargoManifestTest : TestCase() {
    fun testDependencySectionsAndForms() {
        val deps = CargoManifest.dependencies("""
            [package]
            name = "app"
            version = "0.1.0"

            [dependencies]
            tera = "1.19.0"           # the engine
            rocket = { path = "../core", version = "0.5" }
            serde = { version = "1", features = ["derive"] }
            local = { workspace = true }
            plain = "*"

            [dev-dependencies]
            insta = "1"

            [dependencies.handlebars]
            version = "5.1"
            optional = true

            [target.'cfg(unix)'.dependencies]
            nix = "0.27"

            [features]
            tera = ["dep:tera"]
        """.trimIndent())
        assertEquals("1.19.0", deps.getValue("tera").version)
        assertEquals("../core", deps.getValue("rocket").path)
        assertEquals("0.5", deps.getValue("rocket").version)
        assertEquals("1", deps.getValue("serde").version)
        assertTrue(deps.getValue("local").workspace)
        assertEquals("1", deps.getValue("insta").version)
        assertEquals("5.1", deps.getValue("handlebars").version)
        assertEquals("0.27", deps.getValue("nix").version)
        assertFalse("[features] entries are not dependencies", deps.containsKey("features"))
        assertEquals(setOf("tera", "rocket", "serde", "local", "plain", "insta", "handlebars", "nix"), deps.keys)
    }

    fun testWorkspaceDependenciesAndPackageVersion() {
        val toml = """
            [workspace]
            members = ["a"]
            [workspace.dependencies]
            tera = "2.4"
            [package]
            name = "x"
            version = "3.2.1"
        """.trimIndent()
        assertEquals("2.4", CargoManifest.dependencies(toml).getValue("tera").version)
        assertEquals("3.2.1", CargoManifest.packageVersion(toml))
        assertNull(CargoManifest.packageVersion("[dependencies]\ntera = \"1\""))
    }

    fun testMajorOfRequirements() {
        for ((req, major) in mapOf("1.19.0" to 1, "^1.2" to 1, "~2.1" to 2, ">=2" to 2, "=2.4.0" to 2, "2" to 2, "*" to null)) {
            assertEquals(req, major, CargoManifest.majorOf(req))
        }
    }

    fun testLockVersion() {
        val lock = """
            [[package]]
            name = "serde"
            version = "1.0.1"

            [[package]]
            name = "tera"
            version = "1.20.1"
            source = "registry+https://github.com/rust-lang/crates.io-index"
        """.trimIndent()
        assertEquals("1.20.1", CargoManifest.lockVersion(lock, "tera"))
        assertEquals("1.0.1", CargoManifest.lockVersion(lock, "serde"))
        assertNull(CargoManifest.lockVersion(lock, "missing"))
    }
}

/** Which Tera version a template is for. */
class TeraVersionTest : BasePlatformTestCase() {

    private fun at(path: String): TeraVersionInfo {
        val file = myFixture.addFileToProject(path, "")
        return TeraVersions.forFile(project, file.virtualFile)
    }

    fun testDirectTera1Dependency() {
        myFixture.addFileToProject("app/Cargo.toml", "[dependencies]\ntera = \"1.19.0\"\n")
        val info = at("app/templates/a.html.tera")
        assertEquals(TeraVersion.V1, info.version)
        assertEquals("Cargo.toml: tera = \"1.19.0\"", info.source)
    }

    fun testDirectTera2DependencyInlineTable() {
        myFixture.addFileToProject("app/Cargo.toml", "[dependencies]\ntera = { version = \"2.4\", features = [\"unicode\"] }\n")
        assertEquals(TeraVersion.V2, at("app/templates/a.html.tera").version)
    }

    fun testTera2DependencyAsTable() {
        myFixture.addFileToProject("app/Cargo.toml", "[dependencies.tera]\nversion = \"2\"\n")
        assertEquals(TeraVersion.V2, at("app/templates/a.html.tera").version)
    }

    fun testWorkspaceInheritedDependency() {
        myFixture.addFileToProject("ws/Cargo.toml", "[workspace]\nmembers = [\"app\"]\n[workspace.dependencies]\ntera = \"2.4\"\n")
        myFixture.addFileToProject("ws/app/Cargo.toml", "[dependencies]\ntera = { workspace = true }\n")
        val info = at("ws/app/templates/a.html.tera")
        assertEquals(TeraVersion.V2, info.version)
        assertTrue(info.source, info.source.contains("workspace"))
    }

    fun testPathDependencyOnATeraCrate() {
        myFixture.addFileToProject("ws/tera-fork/Cargo.toml", "[package]\nname = \"tera\"\nversion = \"1.20.1\"\n")
        myFixture.addFileToProject("ws/app/Cargo.toml", "[dependencies]\ntera = { path = \"../tera-fork\" }\n")
        assertEquals(TeraVersion.V1, at("ws/app/templates/a.html.tera").version)
    }

    /** Rocket's examples/forms: a `path` dependency on rocket_dyn_templates, which depends on tera 1.19. */
    fun testRocketFormsLayoutFollowsThePathDependency() {
        myFixture.addFileToProject("Rocket/contrib/dyn_templates/Cargo.toml",
            "[package]\nname = \"rocket_dyn_templates\"\n[dependencies]\ntera = { version = \"1.19.0\", optional = true }\n")
        myFixture.addFileToProject("Rocket/examples/forms/Cargo.toml", """
            [package]
            name = "forms"
            [dependencies]
            rocket = { path = "../../core/lib" }
            [dependencies.rocket_dyn_templates]
            path = "../../contrib/dyn_templates"
            features = ["tera"]
        """.trimIndent())
        val info = at("Rocket/examples/forms/templates/index.html.tera")
        assertEquals(TeraVersion.V1, info.version)
        assertEquals("rocket_dyn_templates uses tera 1.19.0", info.source)
    }

    fun testRocketDynTemplatesFromTheRegistryIsTera1() {
        myFixture.addFileToProject("app/Cargo.toml", "[dependencies]\nrocket_dyn_templates = { version = \"0.2\", features = [\"tera\"] }\n")
        val info = at("app/templates/a.html.tera")
        assertEquals(TeraVersion.V1, info.version)
        assertTrue(info.source, info.source.contains("rocket_dyn_templates"))
    }

    fun testRocketDynTemplatesOnATera2PathDependencyIsTera2() {
        myFixture.addFileToProject("dyn/Cargo.toml", "[dependencies]\ntera = \"2.4\"\n")
        myFixture.addFileToProject("app/Cargo.toml", "[dependencies]\nrocket_dyn_templates = { path = \"../dyn\" }\n")
        assertEquals(TeraVersion.V2, at("app/templates/a.html.tera").version)
    }

    fun testDirectTeraDependencyBeatsRocketDynTemplates() {
        myFixture.addFileToProject("app/Cargo.toml", "[dependencies]\nrocket_dyn_templates = \"0.2\"\ntera = \"2.4\"\n")
        assertEquals(TeraVersion.V2, at("app/templates/a.html.tera").version)
    }

    fun testNearestManifestWithoutTeraDefersToTheParent() {
        myFixture.addFileToProject("ws/Cargo.toml", "[dependencies]\ntera = \"1.20\"\n")
        myFixture.addFileToProject("ws/inner/Cargo.toml", "[dependencies]\nserde = \"1\"\n")
        assertEquals(TeraVersion.V1, at("ws/inner/templates/a.html.tera").version)
    }

    fun testCargoLockIsTheFallback() {
        myFixture.addFileToProject("app/Cargo.toml", "[dependencies]\nserde = \"1\"\n")
        myFixture.addFileToProject("app/Cargo.lock", "[[package]]\nname = \"tera\"\nversion = \"1.20.1\"\n")
        val info = at("app/templates/a.html.tera")
        assertEquals(TeraVersion.V1, info.version)
        assertEquals("Cargo.lock: tera 1.20.1", info.source)
    }

    fun testNothingFoundDefaultsToTera2() {
        val info = at("lonely/templates/a.html.tera")
        assertEquals(TeraVersion.V2, info.version)
        assertTrue(info.source, info.source.startsWith("default"))
        assertEquals(TeraVersion.V2, TeraVersion.DEFAULT)
    }

    fun testSettingsOverrideCargo() {
        myFixture.addFileToProject("app/Cargo.toml", "[dependencies]\ntera = \"1.19\"\n")
        TeraSettings.getInstance(project).state.version = "2"
        val info = at("app/templates/a.html.tera")
        assertEquals(TeraVersion.V2, info.version)
        assertEquals("Settings → Tera", info.source)
        TeraSettings.getInstance(project).state.version = "1"
        assertEquals(TeraVersion.V1, at("app/templates/b.html.tera").version)
        TeraSettings.getInstance(project).state.version = "auto"
        assertEquals(TeraVersion.V1, at("app/templates/c.html.tera").version)
    }

    fun testUnsavedManifestEditsAreSeen() {
        val manifest = myFixture.addFileToProject("app/Cargo.toml", "[dependencies]\ntera = \"1.19\"\n")
        assertEquals(TeraVersion.V1, at("app/templates/a.html.tera").version)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(manifest.virtualFile)!!
                .setText("[dependencies]\ntera = \"2.4\"\n")
        }
        assertEquals(TeraVersion.V2, at("app/templates/b.html.tera").version)
    }
}
