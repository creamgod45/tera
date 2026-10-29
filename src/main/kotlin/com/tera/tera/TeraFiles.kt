package com.tera.tera

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.IOException

/** Looking at the project's files. The extension index also returns *directories* called like `com.tera.tera`. */
object TeraFiles {
    /** Real files of the project with extension [ext]; directories whose name merely ends in `.tera` or `.rs` are not. */
    fun projectFiles(project: Project, ext: String): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        val byExtension = FilenameIndex.getAllFilesByExt(project, ext, scope)
        // Files the user (or a Zola site) maps to the Tera file type, e.g. `*.html`, count as templates too.
        val byType = if (ext == "tera") FileTypeIndex.getFiles(TeraFileType, scope) else emptyList()
        return (byExtension + byType).distinct().filter { it.isValid && !it.isDirectory }
    }

    /** The current text of [file], from the editor if it is open; empty if it cannot be read. */
    fun textOf(file: VirtualFile): String = try {
        FileDocumentManager.getInstance().getDocument(file)?.text ?: String(file.contentsToByteArray(), file.charset)
    } catch (e: IOException) {
        ""
    }
}
