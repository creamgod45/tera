package com.tera.tera

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/** Plans and applies the migration of templates to Tera 2; the actions below only add UI around it. */
object TeraMigrationRunner {

    class FileResult(val file: VirtualFile, val result: TeraMigration.Result, val original: String) {
        val changed get() = result.text != original
    }

    class Report(val changed: List<FileResult>, val applied: Int, val remaining: List<Pair<VirtualFile, TeraError>>)

    fun templates(project: Project): List<VirtualFile> =
        TeraFiles.projectFiles(project, "tera")
            .filter { "/target/" !in it.path && "/node_modules/" !in it.path }
            .sortedBy { it.path }

    private fun textOf(file: VirtualFile): String = TeraFiles.textOf(file)

    /** What the migration would do to [files]; nothing is written. */
    fun plan(project: Project, files: List<VirtualFile>): List<FileResult> {
        val custom = TeraContexts.customBuiltins(project)
        return files.map { f ->
            val text = textOf(f)
            FileResult(f, TeraMigration.migrate(text, custom), text)
        }
    }

    /** Writes the changed files as a single undoable command. */
    fun apply(project: Project, plan: List<FileResult>): Report {
        val changed = plan.filter { it.changed }
        WriteCommandAction.runWriteCommandAction(project, "Migrate Templates to Tera 2", null, Runnable {
            for (r in changed) FileDocumentManager.getInstance().getDocument(r.file)?.setText(r.result.text)
        })
        return Report(changed, changed.sumOf { it.result.applied }, plan.flatMap { r -> r.result.remaining.map { r.file to it } })
    }
}

/** Alt+Enter in a template: rewrite this file for Tera 2. */
class TeraMigrateIntention : IntentionAction {
    override fun getText() = "Migrate this template to Tera 2"
    override fun getFamilyName() = "Tera"
    override fun startInWriteAction() = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is TeraFile) return false
        val text = file.text
        return TeraMigration.migrate(text, TeraContexts.customBuiltins(project)).text != text
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = editor?.document ?: file?.viewProvider?.document ?: return
        val result = TeraMigration.migrate(document.text, TeraContexts.customBuiltins(project))
        document.setText(result.text)
    }
}

/** Code → Migrate Tera Templates to 2.x…: every template in the project. */
class TeraMigrateAllAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val plan = TeraMigrationRunner.plan(project, TeraMigrationRunner.templates(project))
        val changed = plan.count { it.changed }
        if (changed == 0) {
            Messages.showInfoMessage(project, "None of the ${plan.size} templates needs changes for Tera 2.", "Migrate to Tera 2")
            return
        }
        val ok = Messages.showYesNoDialog(project,
            "Rewrite $changed of ${plan.size} templates for Tera 2?\nThis is one undoable change; review it with the diff.",
            "Migrate to Tera 2", "Migrate", "Cancel", Messages.getQuestionIcon())
        if (ok != Messages.YES) return
        val report = TeraMigrationRunner.apply(project, plan)
        val left = report.remaining.groupBy { it.first }.entries.joinToString("\n") { (f, list) -> "  ${f.name}: ${list.size}" }
        Messages.showInfoMessage(project,
            "Changed ${report.changed.size} templates (${report.applied} fixes)." +
                if (report.remaining.isEmpty()) "" else "\n\nStill needing a manual change:\n$left\n\nSee the editor highlights for details.",
            "Migrate to Tera 2")
    }
}
