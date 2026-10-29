plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.tera"
version = "1.3.1"

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
            <h3>1.3.1</h3>
            <ul>
              <li><b>Zola sites</b>: components defined in <code>.html</code> templates are found, no more "Unknown component".</li>
              <li><b>Zola functions and filters</b> (<code>get_url</code>, <code>get_section</code>, <code>markdown</code>, …) are known, and <code>date</code> or <code>striptags</code> are no longer reported as removed.</li>
              <li><b>Zola variables</b>: <code>page</code>, <code>section</code>, <code>term</code> and <code>config</code> have completion, hover and typo warnings.</li>
            </ul>
            <p>Register <code>*.html</code> under Settings → Editor → File Types → Tera Template to use it in a Zola site.</p>
            <hr/>
            <ul>
              <li><b>Zola 網站</b>：找得到 <code>.html</code> 模板裡定義的元件，不再顯示「Unknown component」。</li>
              <li><b>Zola 函式與 filter</b>（<code>get_url</code>、<code>get_section</code>、<code>markdown</code>……）可辨識，<code>date</code>、<code>striptags</code> 不再被標為已移除。</li>
              <li><b>Zola 變數</b>：<code>page</code>、<code>section</code>、<code>term</code>、<code>config</code> 有補全、懸停說明與拼字警告。</li>
            </ul>
            <p>在 Zola 網站使用請到 Settings → Editor → File Types → Tera Template 加入 <code>*.html</code>。</p>
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
