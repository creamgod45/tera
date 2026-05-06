package com.tera.tera

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

// ── PSI 文件节点 ──────────────────────────────────────────────────────────────
class TeraFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, TeraLanguage) {
    override fun getFileType(): FileType = TeraFileType
    override fun toString(): String = "Tera File"
}

// ── 最小化 Parser：把所有 token 平铺在根节点下 ────────────────────────────────
private class TeraParser : PsiParser {
    override fun parse(root: IElementType, builder: com.intellij.lang.PsiBuilder): ASTNode {
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        return builder.treeBuilt
    }
}

// ── ParserDefinition ──────────────────────────────────────────────────────────
class TeraParserDefinition : ParserDefinition {

    companion object {
        val FILE = IFileElementType(TeraLanguage)

        val WHITESPACE_TOKENS = TokenSet.create(TeraTokenTypes.TAG_WHITESPACE)
        val COMMENT_TOKENS    = TokenSet.create(
            TeraTokenTypes.COMMENT_START,
            TeraTokenTypes.COMMENT_CONTENT,
            TeraTokenTypes.COMMENT_END
        )
        val STRING_TOKENS     = TokenSet.create(TeraTokenTypes.STRING)
    }

    override fun createLexer(project: Project?): Lexer = TeraLexer()
    override fun createParser(project: Project?): PsiParser = TeraParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getWhitespaceTokens(): TokenSet = WHITESPACE_TOKENS
    override fun getCommentTokens(): TokenSet = COMMENT_TOKENS
    override fun getStringLiteralElements(): TokenSet = STRING_TOKENS
    override fun createFile(viewProvider: FileViewProvider): PsiFile = TeraFile(viewProvider)
    override fun createElement(node: ASTNode): PsiElement =
        throw UnsupportedOperationException("Tera has no composite PSI elements")
}
