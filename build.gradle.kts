plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.tera"
version = "1.3.0"

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
            <h3>1.3.0</h3>
            <ul>
              <li><b>Know where a variable comes from</b>: fields and types are read from the Rust code that renders the template, with hover docs and "not defined" warnings.</li>
              <li><b>Tera 2 ready</b>: components, type checks, comprehensions explained on hover, and one-click migration from Tera 1.</li>
              <li><b>Find components fast</b>: Cmd+click or the gutter icon lists each definition with its line and file.</li>
              <li><b>Safer editing</b>: live templates are no longer offered inside <code>{{ }}</code>; no false errors for <code>{%-</code>, <code>-%}</code> or named end tags.</li>
              <li><b>Printing a list or map?</b> Alt+Enter turns it into a loop or a join.</li>
              <li><b>Errors you can click</b> in the Run window, and open a template from the Rust string that names it.</li>
            </ul>
            <p>Works alone: disable or uninstall the original "Tera" plugin first (both use <code>.tera</code>).</p>
            <hr/>
            <ul>
              <li><b>看得出變數來源</b>：欄位與型別來自渲染模板的 Rust 程式碼，有 hover 說明與「未定義」警告。</li>
              <li><b>支援 Tera 2</b>：元件、型別檢查、推導式說明，可一鍵從 Tera 1 遷移。</li>
              <li><b>快速找元件</b>：Cmd+點擊或邊欄圖示，列出每個定義的行內容與檔案。</li>
              <li><b>編輯更安全</b>：<code>{{ }}</code> 內不再出現 Live Template；<code>{%-</code>、<code>-%}</code> 與具名結束標籤不再誤報。</li>
              <li><b>直接輸出清單或 map？</b>Alt+Enter 改成迴圈或 join。</li>
              <li><b>錯誤可點擊</b>（Run 視窗），也能從 Rust 字串開啟對應模板。</li>
            </ul>
            <p>請先停用或移除原本的「Tera」外掛（兩者都使用 <code>.tera</code>）。</p>
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
