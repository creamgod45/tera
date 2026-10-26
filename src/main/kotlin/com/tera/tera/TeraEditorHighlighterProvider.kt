package com.tera.tera

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.util.LayerDescriptor
import com.intellij.openapi.editor.ex.util.LayeredLexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.EditorHighlighterProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class TeraEditorHighlighterProvider : EditorHighlighterProvider {
    override fun getEditorHighlighter(
        project: Project?, fileType: FileType, file: VirtualFile?, scheme: EditorColorsScheme,
    ): EditorHighlighter {
        val highlighter = LayeredLexerEditorHighlighter(TeraSyntaxHighlighter(), scheme)
        val dataHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(
            templateDataLanguage(project, file), project, file,
        )
        highlighter.registerLayer(TeraTokenTypes.OUTER_TEXT, LayerDescriptor(dataHighlighter, ""))
        return highlighter
    }
}
