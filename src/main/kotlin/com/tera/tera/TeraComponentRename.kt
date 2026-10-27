package com.tera.tera

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler

/**
 * Renaming a component: its definition, every call (`<ui.card/>`, `{% <ui.card> %}`) and the closing tags
 * (`{% </ui.card> %}`). A component name is dotted (`ui.card`), so it is not one identifier of the template and is
 * renamed as text edits in one undoable command.
 */
object TeraComponentRename {
    val VALID = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")

    /** The `</name>` of `{% </name> %}` closing tags of a template, with the name. */
    fun closingTags(blocks: List<TeraBlock>): List<Pair<String, TextRange>> = blocks.mapNotNull { b ->
        val t = b.tokens
        if (b.kind != '%' || t.size < 3 || t[0].text != "<" || t[1].text != "/" || t[2].type != TeraTokenTypes.IDENTIFIER) return@mapNotNull null
        var end = 3
        while (end + 1 < t.size && t[end].text == "." && t[end + 1].type == TeraTokenTypes.IDENTIFIER) end += 2
        val name = t.subList(2, end).joinToString("") { it.text }
        name to TextRange(t[2].range.startOffset, t[end - 1].range.endOffset)
    }

    /** Every place [name] is written as a component name, by file. */
    fun occurrences(project: Project, name: String): Map<VirtualFile, List<TextRange>> {
        val found = linkedMapOf<VirtualFile, MutableList<TextRange>>()
        for (file in TeraFiles.projectFiles(project, "tera").filter { "/target/" !in it.path }.sortedBy { it.path }) {
            val text = TeraFiles.textOf(file)
            if (!text.contains(name.substringBefore('.'))) continue
            val blocks = TeraSymbolScanner.scanBlocks(text)
            val ranges = TeraComponentScanner.definitions(text, blocks).filter { it.name == name }.map { it.nameRange } +
                TeraComponentScanner.calls(blocks).filter { it.name == name }.map { it.nameRange } +
                closingTags(blocks).filter { it.first == name }.map { it.second }
            if (ranges.isNotEmpty()) found[file] = ranges.toMutableList()
        }
        return found
    }

    fun nameAt(text: String, offset: Int): String? {
        val blocks = TeraSymbolScanner.scanBlocks(text)
        TeraComponentScanner.definitions(text, blocks).firstOrNull { offset in it.nameRange.startOffset..it.nameRange.endOffset }?.let { return it.name }
        TeraComponentScanner.calls(blocks).firstOrNull { offset in it.nameRange.startOffset..it.nameRange.endOffset }?.let { return it.name }
        return closingTags(blocks).firstOrNull { offset in it.second.startOffset..it.second.endOffset }?.first
    }

    /** Why [newName] cannot be used, or null. */
    fun problem(project: Project, name: String, newName: String): String? = when {
        !VALID.matches(newName) -> "\"$newName\" is not a component name (letters, digits and underscores, dot separated)"
        newName != name && TeraComponents.project(project).any { it.def.name == newName } -> "A component named $newName already exists"
        else -> null
    }

    /** Applies the rename to every file; returns how many places changed. */
    fun apply(project: Project, name: String, newName: String): Int {
        val where = occurrences(project, name)
        var count = 0
        WriteCommandAction.runWriteCommandAction(project, "Rename Component $name", null, Runnable {
            for ((file, ranges) in where) {
                val doc = FileDocumentManager.getInstance().getDocument(file) ?: continue
                for (r in ranges.sortedByDescending { it.startOffset }) { doc.replaceString(r.startOffset, r.endOffset, newName); count++ }
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        })
        return count
    }
}

/** Shift+F6 on a component name. */
class TeraComponentRenameHandler : RenameHandler {
    private fun nameAt(dataContext: DataContext): String? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
        if (file.viewProvider.baseLanguage != TeraLanguage) return null
        return TeraComponentRename.nameAt(file.viewProvider.document?.text ?: file.text, editor.caretModel.offset)
    }

    override fun isAvailableOnDataContext(dataContext: DataContext) = nameAt(dataContext) != null
    override fun isRenaming(dataContext: DataContext) = isAvailableOnDataContext(dataContext)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext) {
        val name = nameAt(dataContext) ?: return
        val newName = Messages.showInputDialog(project, "Rename component $name and all its uses to:", "Rename Component",
            Messages.getQuestionIcon(), name, object : InputValidatorEx {
                override fun getErrorText(inputString: String) = TeraComponentRename.problem(project, name, inputString.trim())
                override fun checkInput(inputString: String) = getErrorText(inputString) == null
                override fun canClose(inputString: String) = checkInput(inputString)
            })?.trim() ?: return
        if (newName != name) TeraComponentRename.apply(project, name, newName)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {
        invoke(project, CommonDataKeys.EDITOR.getData(dataContext), CommonDataKeys.PSI_FILE.getData(dataContext), dataContext)
    }
}
