package com.tera.tera

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Turns the places a template engine reports into links in the Run window: Tera 2's ` --> pages/detail.html:30:49`,
 * Tera 1's `Failed to parse 'pages/x.html'` followed by ` --> 3:10`, and the template names in `Failed to render
 * 'x'`, `while rendering 'x'` and Rocket's `template 'x': error:`. The names are read against the project's template
 * roots; text that names no template of the project is left alone.
 */
class TeraConsoleFilter(private val project: Project) : Filter {

    private companion object {
        val POSITION_WITH_FILE = Regex("""-->\s+([^\s:]+):(\d+):(\d+)""")
        val POSITION_ONLY = Regex("""-->\s+(\d+):(\d+)""")
        val NAME = Regex("""\b(?:[Pp]arse|[Rr]ender|rendering|[Tt]emplate|extends|include|import)\s+'([^']+)'""")
    }

    /** The template named in the last line that named one: Tera 1 reports a position without the file. */
    private var lastName: String? = null

    private fun fileOf(name: String): VirtualFile? = TeraTemplateRoots.resolve(project, null, name).firstOrNull()?.first

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val base = entireLength - line.length
        val items = mutableListOf<Filter.ResultItem>()
        for (m in NAME.findAll(line)) {
            lastName = m.groupValues[1]
            val file = fileOf(m.groupValues[1]) ?: continue
            val range = m.groups[1]!!.range
            items.add(Filter.ResultItem(base + range.first, base + range.last + 1, OpenFileHyperlinkInfo(project, file, 0)))
        }
        POSITION_WITH_FILE.find(line)?.let { m ->
            val file = fileOf(m.groupValues[1])
            if (file != null) {
                lastName = m.groupValues[1]
                items.add(Filter.ResultItem(base + m.groups[1]!!.range.first, base + m.groups[3]!!.range.last + 1,
                    OpenFileHyperlinkInfo(project, file, m.groupValues[2].toInt() - 1, m.groupValues[3].toInt() - 1)))
            }
        } ?: POSITION_ONLY.find(line)?.let { m ->
            val file = lastName?.let(::fileOf)
            if (file != null) {
                items.add(Filter.ResultItem(base + m.groups[1]!!.range.first, base + m.groups[2]!!.range.last + 1,
                    OpenFileHyperlinkInfo(project, file, m.groupValues[1].toInt() - 1, m.groupValues[2].toInt() - 1)))
            }
        }
        return items.takeIf { it.isNotEmpty() }?.let { Filter.Result(it) }
    }
}

class TeraConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(TeraConsoleFilter(project))
}
