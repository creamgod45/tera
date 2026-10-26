package com.tera.tera

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** Replace [range] by [text]. */
data class TeraEdit(val range: TextRange, val text: String)

/** One labelled change made of non-overlapping edits. */
data class TeraFix(val label: String, val edits: List<TeraEdit>) {
    /** [text] with every edit applied. */
    fun applyTo(text: String): String = applyEdits(text, edits)

    companion object {
        /** Applies [edits] back to front so offsets stay valid; overlapping edits are skipped. */
        fun applyEdits(text: String, edits: List<TeraEdit>): String {
            val sb = StringBuilder(text)
            var lastStart = Int.MAX_VALUE
            for (e in edits.sortedByDescending { it.range.startOffset }) {
                if (e.range.endOffset > lastStart) continue
                sb.replace(e.range.startOffset, e.range.endOffset, e.text)
                lastStart = e.range.startOffset
            }
            return sb.toString()
        }
    }
}

/** Offers a [TeraFix] as an intention / quick fix. */
class TeraEditIntention(private val fix: TeraFix) : IntentionAction {
    override fun getText(): String = fix.label
    override fun getFamilyName(): String = "Tera"
    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file != null && fix.edits.all { it.range.endOffset <= file.textLength }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = editor?.document ?: file?.viewProvider?.document ?: return
        var lastStart = Int.MAX_VALUE
        for (e in fix.edits.sortedByDescending { it.range.startOffset }) {
            if (e.range.endOffset > lastStart) continue
            document.replaceString(e.range.startOffset, e.range.endOffset, e.text)
            lastStart = e.range.startOffset
        }
    }
}
