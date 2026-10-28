package com.tera.tera

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Where a component is called: the name in `<ui.card .../>` or in the opening tag of `{% <ui.card> %}`. */
data class TeraUsage(val file: VirtualFile, val range: TextRange)

/** The reverse of go-to-definition: every call of a component in the project. */
object TeraUsages {

    /** All component calls of the project by component name, cached until any PSI changes. */
    private fun index(project: Project): Map<String, List<TeraUsage>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val found = linkedMapOf<String, MutableList<TeraUsage>>()
            for (file in TeraFiles.projectFiles(project, "tera").filter { "/target/" !in it.path }.sortedBy { it.path }) {
                val text = TeraFiles.textOf(file)
                if (!text.contains('<')) continue
                for (call in TeraComponentScanner.calls(TeraSymbolScanner.scanBlocks(text))) {
                    found.getOrPut(call.name) { mutableListOf() }.add(TeraUsage(file, call.nameRange))
                }
            }
            CachedValueProvider.Result.create<Map<String, List<TeraUsage>>>(found, PsiModificationTracker.MODIFICATION_COUNT)
        }

    fun ofComponent(project: Project, name: String): List<TeraUsage> = index(project)[name].orEmpty()

    /** The element at [offset] in the Tera view of [file], where the IDE can navigate to. */
    fun elementAt(project: Project, file: VirtualFile, offset: Int): PsiElement? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val tera = psi.viewProvider.getPsi(TeraLanguage) ?: psi
        return tera.findElementAt(offset)
    }
}

/**
 * Gutter icons in a template: on a component definition, "N usages" (click to pick one, like a Laravel Idea view
 * usage); on a call, a jump to the definition.
 */
class TeraComponentLineMarkerProvider : RelatedItemLineMarkerProvider() {

    private class Marks(val definitions: Map<Int, ComponentDef>, val calls: Map<Int, ComponentCall>)

    private fun marks(file: PsiFile): Marks = CachedValuesManager.getCachedValue(file) {
        val text = file.text
        val blocks = TeraSymbolScanner.scanBlocks(text)
        CachedValueProvider.Result.create(
            Marks(
                TeraComponentScanner.definitions(text, blocks).associateBy { it.nameRange.startOffset },
                TeraComponentScanner.calls(blocks).associateBy { it.nameRange.startOffset },
            ),
            file,
        )
    }

    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (element.firstChild != null) return
        val file = element.containingFile ?: return
        if (file.language != TeraLanguage) return
        val start = element.textRange.startOffset
        val marks = marks(file)
        val project = element.project
        val vFile = file.originalFile.virtualFile ?: return

        marks.definitions[start]?.let { def ->
            val usages = TeraUsages.ofComponent(project, def.name)
            if (usages.isEmpty()) return
            result.add(
                NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
                    .setTargets(NotNullLazyValue.lazy { usages.mapNotNull { TeraUsages.elementAt(project, it.file, it.range.startOffset) } })
                    .setTooltipText("${usages.size} usage${if (usages.size == 1) "" else "s"} of ${def.name}")
                    .setPopupTitle("Usages of ${def.name}")
                    .setCellRenderer(teraUsageRenderer)
                    .createLineMarkerInfo(element),
            )
            return
        }
        marks.calls[start]?.let { call ->
            // Several calls on one line would get merged by the platform into a list that only shows the element
            // text ("ui", "ui"); one icon per line lists every definition with its source line instead.
            val text = file.text
            val lineStart = text.lastIndexOf('\n', start - 1) + 1
            val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val onLine = marks.calls.values.filter { it.nameRange.startOffset in lineStart until lineEnd }
            if (onLine.minOf { it.nameRange.startOffset } != start) return
            val visible = TeraComponents.visible(project, vFile, text)
            val names = onLine.map { it.name }.distinct()
            val defs = visible.filter { it.def.name in names }.distinctBy { it.file to it.def.nameRange.startOffset }
            if (defs.isEmpty()) return
            result.add(
                NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
                    .setTargets(NotNullLazyValue.lazy {
                        defs.mapNotNull { TeraUsages.elementAt(project, it.file ?: vFile, it.def.nameRange.startOffset) }
                    })
                    .setTooltipText(if (names.size == 1) "Component ${names[0]}" else "Components ${names.joinToString()}")
                    .setPopupTitle(if (names.size == 1) "Definition of ${names[0]}" else "Definitions of ${names.joinToString()}")
                    .setCellRenderer(teraUsageRenderer)
                    .createLineMarkerInfo(element),
            )
        }
    }
}
