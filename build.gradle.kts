plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.tera"
version = "1.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// The Rust render-context integration (src/rust) needs the Rust plugin's classes: they come from the local
// RustRover given by -PrustRoverHome, else from ~/Applications/RustRover.app when present. Without either,
// the plugin is built without that integration.
val rustRoverHomeProperty = providers.gradleProperty("rustRoverHome").orNull
val rustPluginLib = (rustRoverHomeProperty?.let(::file) ?: file("${System.getProperty("user.home")}/Applications/RustRover.app"))
    .resolve("Contents/plugins/intellij-rust/lib").takeIf { it.isDirectory }

sourceSets {
    main { if (rustPluginLib != null) kotlin.srcDir("src/rust/kotlin") }
    test { if (rustRoverHomeProperty != null) kotlin.srcDir("src/rustTest/kotlin") }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    if (rustRoverHomeProperty == null && rustPluginLib != null) {
        compileOnly(fileTree(rustPluginLib) { include("*.jar", "modules/*.jar") })
    }
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        val rustRoverHome = providers.gradleProperty("rustRoverHome").orNull
        if (rustRoverHome != null) {
            // Rust integration: build and test against a local RustRover, which bundles the Rust plugin.
            local(rustRoverHome)
            bundledPlugins("com.jetbrains.rust", "com.intellij.css", "JavaScript")
        } else if (providers.gradleProperty("webTests").orNull == "true") {
            intellijIdeaUltimate("2025.1")
            bundledPlugins("com.intellij.css", "JavaScript")
        } else {
            intellijIdeaCommunity("2025.1")
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:



    }
}

intellijPlatform {
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            providers.gradleProperty("verifyIde").orNull?.let { local(file(it)) }
        }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            // untilBuild 不设上限，兼容所有未来版本
        }

        changeNotes = """
            Fix false errors for whitespace-trimming delimiters and named endmacro/endblock tags.
            Check macro calls against definitions (including import "..." as alias) and add macro, argument, namespace, keyword and template-name completion.
            Complete and check template variables from the Rust code that renders them (Template::render): struct fields, context! keys, loop and set variables, filters, tests and functions.
            Add go to definition (with a chooser for multiple sources) and hover documentation for macros, parameters and import aliases, including comment descriptions and @param types.
            Integrate native template data language parsing and highlighting for HTML, CSS and JavaScript.
        """.trimIndent()
    }
}

tasks {
    test {
        systemProperty("tera.webTests", providers.gradleProperty("webTests").getOrElse("false"))
        if (providers.gradleProperty("webTests").orNull != "true") {
            exclude("**/TeraWebIntegrationTest.class")
        }
    }
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        val level = if (providers.gradleProperty("rustRoverHome").isPresent) "25" else "21"
        sourceCompatibility = level
        targetCompatibility = level
    }

    // buildSearchableOptions 在非默认 locale 系统上有已知 bug，禁用之
    buildSearchableOptions {
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            if (providers.gradleProperty("rustRoverHome").isPresent) org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
            else org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        )
    }
}
