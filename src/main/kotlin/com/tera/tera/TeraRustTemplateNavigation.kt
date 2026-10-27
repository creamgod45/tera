package com.tera.tera

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * Opening a template from the Rust string that names it: `render("pages/detail.html", ..)`, whichever function it is
 * given to. Cmd+click on the string, or the gutter icon beside it, jumps to the template file (a list when several
 * match, e.g. `macros.html.tera` and `macros.txt.tera`). The path is read against the project's template directory
 * and working directory ([TeraTemplateRoots]).
 */
object TeraRustTemplateStrings {
    private val STRING = Regex(""""((?:[^"\\\n]|\\.)*)"""")

    fun isRust(file: com.intellij.psi.PsiFile?) = file?.virtualFile?.extension == "rs" || file?.language?.id == "Rust"

    /** The text of the plain string literal around [offset] in [text], or null. */
    fun literalAt(text: String, offset: Int): String? {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return STRING.findAll(text.substring(lineStart, lineEnd))
            .firstOrNull { offset - lineStart in it.range.first..(it.range.last + 1) }?.groupValues?.get(1)
    }

    fun targets(element: PsiElement, name: String): List<PsiElement> {
        val project = element.project
        val from = element.containingFile?.originalFile?.virtualFile
        val manager = PsiManager.getInstance(project)
        return TeraTemplateRoots.resolve(project, from, name).mapNotNull { (file, _) ->
            manager.findFile(file)?.let { it.viewProvider.getPsi(TeraLanguage) ?: it }
        }
    }
}

class TeraRustTemplateGotoHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (editor == null || sourceElement == null) return null
        val project = editor.project ?: return null
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        if (!TeraRustTemplateStrings.isRust(file)) return null
        val name = TeraRustTemplateStrings.literalAt(editor.document.text, offset) ?: return null
        return TeraRustTemplateStrings.targets(sourceElement, name).takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}

/** A gutter icon on a Rust string that names a template. */
class TeraRustTemplateLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (element.firstChild != null) return
        val text = element.text
        if (text.length < 3 || !text.startsWith("\"") || !text.endsWith("\"")) return
        if (!TeraRustTemplateStrings.isRust(element.containingFile)) return
        val name = text.substring(1, text.length - 1)
        val from = element.containingFile.originalFile.virtualFile
        val hits = TeraTemplateRoots.resolve(element.project, from, name)
        if (hits.isEmpty()) return
        result.add(
            NavigationGutterIconBuilder.create(TeraIcons.FILE)
                .setTargets(NotNullLazyValue.lazy { TeraRustTemplateStrings.targets(element, name) })
                .setTooltipText("Open template ${hits.first().first.name} (${hits.first().second})")
                .setPopupTitle("Templates named \"$name\"")
                .createLineMarkerInfo(element),
        )
    }
}
