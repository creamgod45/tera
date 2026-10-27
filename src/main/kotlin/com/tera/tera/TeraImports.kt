package com.tera.tera

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

object TeraImports {
    /** Every template that [path] names when imported from [from], with the macros each defines. */
    fun templatesOf(from: VirtualFile, path: String): List<Pair<VirtualFile, List<MacroDef>>> =
        TeraTemplateResolver.resolveAll(from, path).map { target ->
            val text = TeraFiles.textOf(target)
            target to TeraSymbolScanner.scan(text).macros
        }

    /** Macros available through [path] from [from]; null if the template can't be found. */
    fun macrosOf(from: VirtualFile, path: String): List<MacroDef>? =
        templatesOf(from, path).takeIf { it.isNotEmpty() }?.flatMap { it.second }
}
