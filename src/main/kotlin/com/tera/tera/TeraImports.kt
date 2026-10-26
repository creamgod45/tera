package com.tera.tera

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

object TeraImports {
    /** Macros defined by the template that [path] names when imported from [from]; null if it can't be found. */
    fun macrosOf(from: VirtualFile, path: String): List<MacroDef>? {
        val target = TeraTemplateResolver.resolve(from, path) ?: return null
        val text = FileDocumentManager.getInstance().getDocument(target)?.text
            ?: String(target.contentsToByteArray(), target.charset)
        return TeraSymbolScanner.scan(text).macros
    }
}
