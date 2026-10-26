package com.tera.tera

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Shape of a value as the template sees it (what Tera gets after serialization). */
sealed interface TeraType {
    data object Unknown : TeraType
    data class Scalar(val name: String) : TeraType
    data class ArrayOf(val element: TeraType) : TeraType
    data class MapOf(val value: TeraType) : TeraType
    data class Tuple(val elements: List<TeraType>) : TeraType
    class Struct(val name: String, val fields: List<TeraField>) : TeraType

    fun display(): String = when (this) {
        Unknown -> "any"
        is Scalar -> name
        is ArrayOf -> "array<${element.display()}>"
        is MapOf -> "map<${value.display()}>"
        is Tuple -> elements.joinToString(", ", "(", ")") { it.display() }
        is Struct -> name
    }
}

/**
 * A variable or field made available to a template.
 * [rustType] is the declared source type for display, [doc] its documentation comment,
 * [target] the declaration to navigate to (e.g. the Rust struct field).
 */
class TeraField(
    val name: String,
    val type: TeraType,
    val rustType: String? = null,
    val doc: String? = null,
    val target: PsiElement? = null,
)

/**
 * Variables a template can read, as found by a [TeraContextProvider].
 * [complete] is true only when every render site was fully understood, which is what allows
 * "undefined variable" diagnostics. [sources] describes where the data came from.
 */
class TeraContextResult(
    val variables: List<TeraField>,
    val complete: Boolean,
    val sources: List<String> = emptyList(),
)

/** Supplies the render context of a template, e.g. by reading the Rust code that renders it. */
interface TeraContextProvider {
    fun resolve(project: Project, template: VirtualFile): TeraContextResult?

    /** Functions, filters and tests the project registers with the template engine. */
    fun customBuiltins(project: Project): List<TeraCustomBuiltin> = emptyList()

    companion object {
        val EP: ExtensionPointName<TeraContextProvider> = ExtensionPointName.create("com.tera.contextProvider")
    }
}

object TeraContexts {
    private const val MAX_DEPTH = 4
    private val LOG = Logger.getInstance(TeraContexts::class.java)

    private fun providers(): List<TeraContextProvider> = try {
        TeraContextProvider.EP.extensionList
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Throwable) {
        LOG.warn("Tera context providers unavailable", e)
        emptyList()
    }
    private val INHERIT = Regex("""\{%-?\s*(?:extends|include)\s+["']([^"']+)["']""")

    /**
     * Context of all providers for [file] plus that of every template that `extends`/`include`s it
     * (a base or partial is rendered with its users' context), cached until any PSI changes.
     */
    fun forFile(file: PsiFile): TeraContextResult? {
        val origin = file.originalFile
        val vFile = origin.virtualFile ?: return null
        return CachedValuesManager.getCachedValue(origin) {
            CachedValueProvider.Result.create(compute(origin.project, vFile), PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    private fun compute(project: Project, start: VirtualFile): TeraContextResult? {
        val visited = mutableSetOf<VirtualFile>()
        val results = mutableListOf<TeraContextResult>()
        var unknownSource = false
        fun visit(f: VirtualFile, depth: Int) {
            if (!visited.add(f) || depth > MAX_DEPTH) return
            val own = providers().mapNotNull { p ->
                try {
                    p.resolve(project, f)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Throwable) {
                    // A provider built against another IDE version must not break the other features.
                    LOG.warn("Tera context provider ${p.javaClass.name} failed", e)
                    null
                }
            }
            results.addAll(own)
            val dependents = dependentsOf(project, f)
            dependents.forEach { visit(it, depth + 1) }
            if (own.isEmpty() && dependents.isEmpty() && f != start) unknownSource = true
        }
        visit(start, 0)
        return merge(results, unknownSource)
    }

    /** Templates in the project whose `extends`/`include` tags name [target]. */
    fun dependentsOf(project: Project, target: VirtualFile): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        return FilenameIndex.getAllFilesByExt(project, "tera", scope).filter { other ->
            if (other == target) return@filter false
            val text = FileDocumentManager.getInstance().getDocument(other)?.text
                ?: String(other.contentsToByteArray(), other.charset)
            INHERIT.findAll(text).any { m -> target in TeraTemplateResolver.resolveAll(other, m.groupValues[1]) }
        }
    }

    /** Project-registered functions, filters and tests from all providers, cached until any PSI changes. */
    fun customBuiltins(project: Project): List<TeraCustomBuiltin> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                providers().flatMap { p ->
                    try {
                        p.customBuiltins(project)
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Throwable) {
                        LOG.warn("Tera context provider ${p.javaClass.name} failed", e)
                        emptyList()
                    }
                },
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    fun merge(results: List<TeraContextResult>, forceIncomplete: Boolean = false): TeraContextResult? {
        if (results.isEmpty()) return null
        val byName = linkedMapOf<String, TeraField>()
        for (r in results) for (v in r.variables) byName.putIfAbsent(v.name, v)
        return TeraContextResult(
            byName.values.toList(), !forceIncomplete && results.all { it.complete }, results.flatMap { it.sources }.distinct(),
        )
    }
}
