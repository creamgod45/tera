package com.tera.tera

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Where a project's template names are relative to, the way the running program would see it. A relative path in
 * `render("pages/x", ..)` means nothing without a template root and a working directory, and projects set both in
 * different places, so this reads them from where they are configured:
 *
 *  - Rocket: `template_dir` in `Rocket.toml`, or `.merge(("template_dir", "views"))` in code; default `templates`
 *  - Tera: the directory part of the glob given to `Tera::new(..)`, also when built with `concat!(env!(..), "/views/..")`
 *  - `relative!("templates")` (Rocket's helper, relative to the crate)
 *  - a changed working directory: `std::env::set_current_dir("app")`, the PHP `chdir()` of Rust; relative paths are then
 *    taken from that directory (and, as a fallback, from the crate directory)
 */
object TeraTemplateRoots {

    class Root(val dir: VirtualFile, val why: String)

    private val ROCKET_DIR = Regex("""(?m)^\s*template_dir\s*=\s*["']([^"']+)["']""")
    private val ROCKET_CODE = Regex(""""template_dir"\s*,\s*"([^"]+)"""")
    private val TERA_NEW = Regex("""\bTera\s*::\s*new\s*\(""")
    private val RELATIVE = Regex("""\brelative!\s*\(\s*"([^"]+)"""")
    private val CHDIR = Regex("""\bset_current_dir\s*\(\s*(?:&?\s*(?:Path::new\s*\(\s*)?)?"([^"]+)"""")
    private val STRING = Regex(""""((?:[^"\\]|\\.)*)"""")

    /** The crate directory of [file]: the nearest ancestor that has a `Cargo.toml`. */
    fun crateDir(file: VirtualFile): VirtualFile? {
        var dir = if (file.isDirectory) file else file.parent
        while (dir != null) {
            if (dir.findChild("Cargo.toml") != null) return dir
            dir = dir.parent
        }
        return null
    }

    /** Everything the project's sources and `Rocket.toml` files say about the template directory and working directory. */
    private class Config(val dirs: List<String>, val workDirs: List<String>)

    private fun config(project: Project): Config =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val dirs = linkedSetOf<String>()
            val work = linkedSetOf<String>()
            for (f in TeraFiles.projectFiles(project, "toml").filter { it.name == "Rocket.toml" }) {
                ROCKET_DIR.findAll(TeraFiles.textOf(f)).forEach { dirs.add(it.groupValues[1]) }
            }
            for (f in TeraFiles.projectFiles(project, "rs").filter { "/target/" !in it.path }) {
                val text = TeraFiles.textOf(f)
                ROCKET_CODE.findAll(text).forEach { dirs.add(it.groupValues[1]) }
                RELATIVE.findAll(text).forEach { dirs.add(it.groupValues[1]) }
                CHDIR.findAll(text).forEach { work.add(it.groupValues[1]) }
                for (m in TERA_NEW.findAll(text)) globPrefix(text, m.range.last + 1)?.let(dirs::add)
            }
            dirs.add("templates")
            CachedValueProvider.Result.create(Config(dirs.toList(), work.toList()), PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** `views` from the glob `Tera::new("views/" + wildcards)`; the string parts of a `concat!(..)` are joined, `env!(..)` parts dropped. */
    private fun globPrefix(text: String, from: Int): String? {
        val end = text.indexOf(')', text.indexOf('"', from).takeIf { it >= 0 } ?: return null)
        val call = text.substring(from, minOf(text.length, end + 30))
        val joined = STRING.findAll(call.substringBefore(";")).joinToString("") { it.groupValues[1] }.replace("//", "/")
        val prefix = joined.takeWhile { it != '*' && it != '?' && it != '[' && it != '{' }.trimEnd('/')
        return prefix.takeIf { it.isNotBlank() }?.removePrefix("/")
    }

    private fun relative(base: VirtualFile?, path: String): VirtualFile? =
        if (path.startsWith("/")) LocalFileSystem.getInstance().findFileByPath(path)
        else base?.findFileByRelativePath(path.removePrefix("./"))

    /**
     * The template roots that exist, for a Rust file [from] (null: the whole project). Each is resolved against the
     * working directory first when the project changes it, then against the crate directory.
     */
    fun roots(project: Project, from: VirtualFile?): List<Root> {
        val config = config(project)
        val crates = from?.let { listOfNotNull(crateDir(it)) }
            ?: TeraFiles.projectFiles(project, "toml").filter { it.name == "Cargo.toml" }.mapNotNull { it.parent }
        val out = linkedMapOf<VirtualFile, Root>()
        for (crate in crates) {
            val bases = config.workDirs.mapNotNull { w -> relative(crate, w)?.takeIf { it.isDirectory }?.let { it to "working directory $w" } } +
                (crate to "the crate directory")
            for (dir in config.dirs) for ((base, label) in bases) {
                val found = relative(base, dir)?.takeIf { it.isDirectory } ?: continue
                out.putIfAbsent(found, Root(found, "`$dir` from $label"))
            }
        }
        return out.values.toList()
    }

    /** Candidate files of a template path written in Rust: an exact path, then the same path with other extensions. */
    private fun inRoot(root: VirtualFile, name: String): List<VirtualFile> {
        val rel = name.trim().removePrefix("./").trimStart('/')
        if (rel.isEmpty()) return emptyList()
        val found = linkedSetOf<VirtualFile>()
        root.findFileByRelativePath(rel)?.takeIf { !it.isDirectory }?.let(found::add)
        root.findFileByRelativePath("$rel.tera")?.takeIf { !it.isDirectory }?.let(found::add)
        val holder = if ('/' in rel) root.findFileByRelativePath(rel.substringBeforeLast('/')) else root
        val base = rel.substringAfterLast('/')
        holder?.takeIf { it.isDirectory }?.children
            ?.filter { !it.isDirectory && it.name.endsWith(".tera") && it.name.startsWith("$base.") }
            ?.sortedBy { it.name }?.let(found::addAll)
        return found.toList()
    }

    /** The template files [name] (as written in a Rust string) refers to, with the root that explains it. */
    fun resolve(project: Project, from: VirtualFile?, name: String): List<Pair<VirtualFile, String>> {
        if (name.isBlank() || name.length > 200 || name.any { it == '\n' || it == '{' }) return emptyList()
        for (root in roots(project, from)) {
            val hits = inRoot(root.dir, name)
            if (hits.isNotEmpty()) return hits.map { it to root.why }
        }
        // Roots nobody configured: any `templates` folder, or the template's own path from any ancestor.
        val rel = name.trim().removePrefix("./").trimStart('/')
        val tail = "/$rel"
        return TeraFiles.projectFiles(project, "tera").filter { f ->
            val path = f.path.removeSuffix(".tera")
            "/target/" !in f.path && (path.endsWith(tail) || path.substringBeforeLast('.', path).endsWith(tail) || path.substringBefore('.', path).endsWith(tail))
        }.sortedBy { it.path }.map { it to "a template path match" }
    }

    /** The names a render call can use for [template], relative to every root that contains it. */
    fun namesOf(project: Project, template: VirtualFile): Set<String> {
        val names = linkedSetOf<String>()
        for (root in roots(project, null)) {
            val rel = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(template, root.dir, '/') ?: continue
            names.add(rel.removeSuffix(".tera"))
            names.add(rel.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + rel.substringAfterLast('/').substringBefore('.'))
        }
        return names
    }
}
