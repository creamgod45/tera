package com.tera.tera

import com.intellij.codeInsight.navigation.GotoTargetHandler
import com.intellij.codeInsight.navigation.GotoTargetPresentationProvider
import com.intellij.codeInsight.navigation.GotoTargetRendererProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import javax.swing.Icon

/**
 * How a place in a template is shown in a list of usages or definitions: the line of the template it is on, and
 * `file:line` beside it, as the usage popup of the Rust plugin does, instead of only the word and the file.
 */
class TeraUsageRenderer : PsiElementListCellRenderer<PsiElement>() {

    override fun getElementText(element: PsiElement): String = describe(element).first
    override fun getContainerText(element: PsiElement, name: String?): String? = describe(element).second
    override fun getIcon(element: PsiElement): Icon = TeraIcons.FILE

    companion object {
        private const val MAX = 110

        /** The trimmed line the element is on, and `file:line`. */
        fun describe(element: PsiElement): Pair<String, String> {
            val file = element.containingFile ?: return element.text to ""
            val document = PsiDocumentManager.getInstance(element.project).getDocument(file)
            val offset = element.textRange?.startOffset ?: 0
            val text = document?.charsSequence ?: file.text
            val lineStart = text.lastIndexOf('\n', offset - 1) + 1
            val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd).trim().replace(Regex("\\s+"), " ")
            val shown = if (line.length > MAX) line.take(MAX - 1) + "…" else line
            val number = text.subSequence(0, lineStart).count { it == '\n' } + 1
            return shown to "${file.name}:$number"
        }

        fun isTemplatePlace(element: PsiElement) = element.containingFile?.language?.isKindOf(TeraLanguage) == true
    }
}

/** The same rendering in the chooser that Cmd+click opens when a name has several definitions or usages. */
class TeraGotoRendererProvider : GotoTargetRendererProvider {
    override fun getRenderer(element: PsiElement, gotoData: GotoTargetHandler.GotoData): PsiElementListCellRenderer<*>? =
        if (TeraUsageRenderer.isTemplatePlace(element)) TeraUsageRenderer() else null
}

/** The chooser of newer platform versions asks for a presentation instead of a renderer. */
class TeraGotoPresentationProvider : GotoTargetPresentationProvider {
    override fun getTargetPresentation(element: PsiElement, differentNames: Boolean): TargetPresentation? {
        if (!TeraUsageRenderer.isTemplatePlace(element)) return null
        val (text, place) = TeraUsageRenderer.describe(element)
        return TargetPresentation.builder(text).icon(TeraIcons.FILE).containerText(place).presentation()
    }
}

val teraUsageRenderer: Computable<PsiElementListCellRenderer<*>> = Computable { TeraUsageRenderer() }
