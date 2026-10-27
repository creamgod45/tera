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
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** `{% block name %}`: [range] is the name. */
data class BlockDef(val name: String, val range: TextRange)

/** Where a block comes from and where it is replaced, along the `extends` chain. */
class TeraBlockLinks(val parents: List<TeraDefinition>, val overriders: List<TeraDefinition>) {
    companion object { val NONE = TeraBlockLinks(emptyList(), emptyList()) }
}

object TeraBlocks {
    private const val MAX_DEPTH = 20

    fun defs(blocks: List<TeraBlock>): List<BlockDef> = blocks.mapNotNull { b ->
        val t = b.tokens
        if (b.kind == '%' && t.size >= 2 && t[0].text == "block" && t[1].type == TeraTokenTypes.IDENTIFIER) BlockDef(t[1].text, t[1].range) else null
    }

    fun defs(text: String): List<BlockDef> = defs(TeraSymbolScanner.scanBlocks(text))

    /** The name of the template a file extends, as written. */
    fun extendsPath(blocks: List<TeraBlock>): String? = blocks.firstNotNullOfOrNull { b ->
        val t = b.tokens
        if (b.kind == '%' && t.size >= 2 && t[0].text == "extends" && t[1].type == TeraTokenTypes.STRING) TeraSymbolScanner.unquote(t[1].text) else null
    }

    /** The block a position is in (the innermost one open there), if any. */
    fun enclosing(blocks: List<TeraBlock>, text: String, offset: Int): String? {
        val stack = ArrayDeque<String>()
        for (b in blocks) {
            val range = TeraBlockRanges.of(text, b)
            if (range.startOffset >= offset) break
            val t = b.tokens
            if (b.kind != '%' || t.isEmpty()) continue
            if (t[0].text == "block" && t.size >= 2) stack.addLast(t[1].text)
            else if (t[0].text == "endblock" && stack.isNotEmpty()) stack.removeLast()
        }
        return stack.lastOrNull()
    }

    /** The templates a file extends (several when the name matches siblings that differ by extension). */
    private fun parentsOf(file: VirtualFile): List<VirtualFile> {
        val path = extendsPath(TeraSymbolScanner.scanBlocks(TeraFiles.textOf(file))) ?: return emptyList()
        return TeraTemplateResolver.resolveAll(file, path)
    }

    /** Where [name] is defined above [from]: the nearest definition along each `extends` chain. */
    fun parents(from: VirtualFile, name: String): List<Pair<VirtualFile, BlockDef>> {
        val found = linkedMapOf<VirtualFile, BlockDef>()
        val seen = mutableSetOf(from)
        fun climb(file: VirtualFile, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (parent in parentsOf(file)) {
                if (!seen.add(parent)) continue
                val def = defs(TeraFiles.textOf(parent)).firstOrNull { it.name == name }
                if (def != null) found[parent] = def else climb(parent, depth + 1)
            }
        }
        climb(from, 0)
        return found.toList()
    }

    /** child template → the templates it extends, for the whole project, cached until any PSI changes. */
    private fun extendsIndex(project: Project): Map<VirtualFile, List<VirtualFile>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val map = TeraFiles.projectFiles(project, "tera").filter { "/target/" !in it.path }
                .associateWith { parentsOf(it) }.filterValues { it.isNotEmpty() }
            CachedValueProvider.Result.create(map, PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** Templates below [from] (children, grandchildren, ...) that define [name] again. */
    fun overriders(project: Project, from: VirtualFile, name: String): List<Pair<VirtualFile, BlockDef>> {
        val index = extendsIndex(project)
        val result = linkedMapOf<VirtualFile, BlockDef>()
        val seen = mutableSetOf(from)
        var frontier = listOf(from)
        var depth = 0
        while (frontier.isNotEmpty() && depth++ <= MAX_DEPTH) {
            val next = index.filter { (_, parents) -> parents.any { it in frontier } }.keys.filter { seen.add(it) }
            for (child in next.sortedBy { it.path }) {
                defs(TeraFiles.textOf(child)).firstOrNull { it.name == name }?.let { result[child] = it }
            }
            frontier = next
        }
        return result.toList()
    }

    fun links(project: Project, from: VirtualFile, name: String): TeraBlockLinks = TeraBlockLinks(
        parents(from, name).map { TeraDefinition(it.first, it.second.range.startOffset) },
        overriders(project, from, name).map { TeraDefinition(it.first, it.second.range.startOffset) },
    )
}

/** Gutter icons on a `{% block %}`: overrides a parent's block (click to go there), is overridden by children. */
class TeraBlockLineMarkerProvider : RelatedItemLineMarkerProvider() {

    private fun marks(file: PsiFile): Map<Int, BlockDef> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(TeraBlocks.defs(file.text).associateBy { it.range.startOffset }, file)
    }

    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (element.firstChild != null) return
        val file = element.containingFile ?: return
        if (file.language != TeraLanguage) return
        val def = marks(file)[element.textRange.startOffset] ?: return
        val vFile = file.originalFile.virtualFile ?: return
        val project = element.project
        val parents = TeraBlocks.parents(vFile, def.name)
        val overriders = TeraBlocks.overriders(project, vFile, def.name)
        fun targets(list: List<Pair<VirtualFile, BlockDef>>) =
            NotNullLazyValue.lazy<Collection<PsiElement>> { list.mapNotNull { TeraUsages.elementAt(project, it.first, it.second.range.startOffset) } }
        if (parents.isNotEmpty()) {
            result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
                .setTargets(targets(parents))
                .setTooltipText("Overrides block ${def.name} in ${parents.joinToString { it.first.name }}")
                .setPopupTitle("Block ${def.name} in the parent template")
                .createLineMarkerInfo(element))
        }
        if (overriders.isNotEmpty()) {
            result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridenMethod)
                .setTargets(targets(overriders))
                .setTooltipText("Block ${def.name} is overridden in ${overriders.size} template${if (overriders.size == 1) "" else "s"}")
                .setPopupTitle("Templates that override block ${def.name}")
                .createLineMarkerInfo(element))
        }
    }
}
