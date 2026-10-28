package com.tera.tera

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** The Tera engine generations the plugin knows. 2.x is the primary target; 1.x is what Rocket uses. */
enum class TeraVersion(val label: String) {
    V1("1.x"),
    V2("2.x");

    companion object {
        /** Used when nothing says otherwise. */
        val DEFAULT = V2
    }
}

/** The version in effect for a file and why. */
class TeraVersionInfo(val version: TeraVersion, val source: String)

// ── Cargo manifests ──────────────────────────────────────────────────────────

class CargoDep(val version: String?, val path: String?, val workspace: Boolean)

/** Reads just enough of a `Cargo.toml` to find dependencies; not a general TOML parser. */
object CargoManifest {
    private val DEP_SECTION = Regex("""^\[(?:target\..+\.)?(dependencies|dev-dependencies|build-dependencies)\]$""")
    private val DEP_TABLE = Regex("""^\[(?:target\..+\.)?(?:dependencies|dev-dependencies|build-dependencies)\.([\w\-]+)\]$""")

    fun dependencies(toml: String): Map<String, CargoDep> {
        val out = linkedMapOf<String, CargoDep>()
        var inDeps = false
        var tableName: String? = null
        val table = mutableMapOf<String, String>()
        fun flushTable() {
            tableName?.let { out[it] = depFrom(table) }
            tableName = null
            table.clear()
        }
        for (raw in toml.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                flushTable()
                inDeps = line == "[workspace.dependencies]" || DEP_SECTION.matches(line)
                DEP_TABLE.matchEntire(line)?.let { tableName = it.groupValues[1]; inDeps = false }
                continue
            }
            if (tableName != null) {
                Regex("""^([\w\-]+)\s*=\s*(.+)$""").matchEntire(line)?.let { table[it.groupValues[1]] = it.groupValues[2].trim() }
                continue
            }
            if (!inDeps) continue
            val m = Regex("""^([\w\-]+)\s*=\s*(.+)$""").matchEntire(line) ?: continue
            val name = m.groupValues[1]
            val value = m.groupValues[2].trim()
            out[name] = when {
                value.startsWith("\"") -> CargoDep(value.trim('"'), null, false)
                value.startsWith("{") -> {
                    val inline = mutableMapOf<String, String>()
                    Regex("""([\w\-]+)\s*=\s*("[^"]*"|true|false|\[[^\]]*\])""").findAll(value)
                        .forEach { inline[it.groupValues[1]] = it.groupValues[2] }
                    depFrom(inline)
                }
                else -> CargoDep(null, null, false)
            }
        }
        flushTable()
        return out
    }

    private fun depFrom(entries: Map<String, String>) = CargoDep(
        version = entries["version"]?.trim('"'),
        path = entries["path"]?.trim('"'),
        workspace = entries["workspace"] == "true",
    )

    /** `[package] version`, for crates reached through a `path` dependency. */
    fun packageVersion(toml: String): String? {
        var inPackage = false
        for (raw in toml.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) { inPackage = line == "[package]"; continue }
            if (inPackage) Regex("""^version\s*=\s*"([^"]+)"""").find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    fun lockVersion(lock: String, crate: String): String? =
        Regex("""\[\[package]]\s*name\s*=\s*"${Regex.escape(crate)}"\s*version\s*=\s*"([^"]+)"""").find(lock)?.groupValues?.get(1)

    /** `1.19.0`, `^1.2`, `>=2`, `~2.1` → the major version. */
    fun majorOf(requirement: String): Int? = Regex("""\d+""").find(requirement)?.value?.toIntOrNull()
}

// ── Detection ────────────────────────────────────────────────────────────────

object TeraVersions {
    private fun versionOf(requirement: String): TeraVersion? =
        CargoManifest.majorOf(requirement)?.let { if (it >= 2) TeraVersion.V2 else TeraVersion.V1 }

    private fun read(file: VirtualFile): String =
        TeraFiles.textOf(file)

    /** The version for a template: the project setting, else what the nearest Cargo manifest says, else [TeraVersion.DEFAULT]. */
    fun forFile(project: Project, file: VirtualFile?): TeraVersionInfo {
        when (TeraSettings.getInstance(project).state.version) {
            "1" -> return TeraVersionInfo(TeraVersion.V1, "Settings → Tera")
            "2" -> return TeraVersionInfo(TeraVersion.V2, "Settings → Tera")
        }
        return (file?.let(::detect)) ?: TeraVersionInfo(TeraVersion.DEFAULT, "default (no Tera dependency found)")
    }

    /** Looks in the Cargo manifests above [template], then in `Cargo.lock`. */
    fun detect(template: VirtualFile): TeraVersionInfo? {
        var dir = template.parent
        while (dir != null) {
            dir.findChild("Cargo.toml")?.let { manifest -> fromManifest(manifest)?.let { return it } }
            dir = dir.parent
        }
        dir = template.parent
        while (dir != null) {
            dir.findChild("Cargo.lock")?.let { lock ->
                CargoManifest.lockVersion(read(lock), "tera")?.let { v ->
                    versionOf(v)?.let { return TeraVersionInfo(it, "Cargo.lock: tera $v") }
                }
            }
            dir = dir.parent
        }
        return null
    }

    private fun fromManifest(manifest: VirtualFile): TeraVersionInfo? {
        val deps = CargoManifest.dependencies(read(manifest))
        deps["tera"]?.let { dep ->
            requirementOf(manifest, "tera", dep)?.let { (v, how) ->
                versionOf(v)?.let { return TeraVersionInfo(it, "${manifest.name}: $how") }
            }
        }
        deps["rocket_dyn_templates"]?.let { dep ->
            // Rocket's template integration: look through a path dependency, else it is Rocket 0.5's Tera 1.x.
            dep.path?.let { rel ->
                manifest.parent.findFileByRelativePath("$rel/Cargo.toml")?.let { inner ->
                    CargoManifest.dependencies(read(inner))["tera"]?.version?.let { v ->
                        versionOf(v)?.let { return TeraVersionInfo(it, "rocket_dyn_templates uses tera $v") }
                    }
                }
            }
            return TeraVersionInfo(TeraVersion.V1, "rocket_dyn_templates (Rocket) uses Tera 1.x")
        }
        return null
    }

    private fun requirementOf(manifest: VirtualFile, name: String, dep: CargoDep): Pair<String, String>? {
        dep.version?.let { return it to "$name = \"$it\"" }
        if (dep.workspace) {
            var dir = manifest.parent?.parent
            while (dir != null) {
                dir.findChild("Cargo.toml")?.let { root ->
                    CargoManifest.dependencies(read(root))[name]?.version?.let { return it to "workspace $name = \"$it\"" }
                }
                dir = dir.parent
            }
        }
        dep.path?.let { rel ->
            manifest.parent.findFileByRelativePath("$rel/Cargo.toml")?.let { inner ->
                CargoManifest.packageVersion(read(inner))?.let { return it to "path dependency $name $it" }
            }
        }
        return null
    }
}

// ── Settings ─────────────────────────────────────────────────────────────────

class TeraSettingsState {
    /** `auto`, `1` or `2`. */
    var version: String = "auto"

    /** In 1.x projects, point out constructs that Tera 2 removed or renamed. */
    var migrationHints: Boolean = true

    /**
     * Template roots chosen by hand, one per line: relative to the project, or absolute. Template names in `extends`,
     * `include`, `import` and in Rust's `render("..")` are read against these first, then against what the plugin
     * detects from `Rocket.toml`, `Tera::new(..)` and the working directory.
     */
    var templateRoots: String = ""

    /** Point out a list or map that `{{ }}` prints as it is. */
    var printWarnings: Boolean = true
}

@Service(Service.Level.PROJECT)
@State(name = "TeraSettings", storages = [Storage("tera.xml")])
class TeraSettings : PersistentStateComponent<TeraSettingsState> {
    private var state = TeraSettingsState()
    override fun getState(): TeraSettingsState = state
    override fun loadState(s: TeraSettingsState) { state = s }

    companion object {
        fun getInstance(project: Project): TeraSettings = project.getService(TeraSettings::class.java)
    }
}
