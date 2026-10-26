package com.tera.tera

import com.intellij.openapi.vfs.VirtualFile

/**
 * Resolves Tera template names the way frameworks such as Rocket expose them: relative to a
 * template root, with the extensions after the first dot optional (`"macros"` finds
 * `macros.html.tera`). The root is found by walking up from the importing file.
 */
object TeraTemplateResolver {
    private const val MAX_NAMES = 500

    fun resolve(from: VirtualFile, name: String): VirtualFile? = resolveAll(from, name).firstOrNull()

    /**
     * Every template [name] can refer to at the nearest root that has a match: an exact path first,
     * then siblings that differ only by extensions (`macros.html.tera`, `macros.txt.tera`, ...).
     */
    fun resolveAll(from: VirtualFile, name: String): List<VirtualFile> {
        val rel = name.trim().trimStart('/')
        if (rel.isEmpty()) return emptyList()
        val base = rel.substringAfterLast('/')
        var dir = from.parent
        while (dir != null) {
            val found = linkedSetOf<VirtualFile>()
            dir.findFileByRelativePath(rel)?.takeIf { !it.isDirectory }?.let(found::add)
            val holder = if ('/' in rel) dir.findFileByRelativePath(rel.substringBeforeLast('/')) else dir
            holder?.takeIf { it.isDirectory }?.children
                ?.filter { !it.isDirectory && it.name.endsWith(".tera") && it.name.startsWith("$base.") }
                ?.sortedBy { it.name }
                ?.let(found::addAll)
            if (found.isNotEmpty()) return found.toList()
            dir = dir.parent
        }
        return emptyList()
    }

    /** Template names usable in `import`/`extends`/`include`, relative to the nearest `templates` folder. */
    fun templateNames(from: VirtualFile): List<String> {
        var root = from.parent ?: return emptyList()
        var probe: VirtualFile? = root
        while (probe != null) {
            if (probe.name == "templates") { root = probe; break }
            probe = probe.parent
        }
        val names = sortedSetOf<String>()
        fun walk(dir: VirtualFile, prefix: String) {
            for (child in dir.children) {
                if (names.size >= MAX_NAMES) return
                if (child.isDirectory) walk(child, "$prefix${child.name}/")
                else if (child.name.endsWith(".tera")) names.add(prefix + child.name.substringBefore('.'))
            }
        }
        walk(root, "")
        return names.toList()
    }
}
