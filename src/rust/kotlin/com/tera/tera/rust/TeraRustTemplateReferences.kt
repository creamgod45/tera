package com.tera.tera.rust

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.tera.tera.TeraTemplatePaths
import com.tera.tera.TeraTemplateRoots
import org.rust.lang.core.psi.RsLitExpr

/**
 * The string of `render("pages/detail.html", ..)` as a reference to the template file, so that renaming or moving the
 * file updates it, Find Usages of a template lists the Rust code that renders it, and Cmd+click goes there.
 */
class TeraRustTemplateReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(RsLitExpr::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val text = element.text
                if (text.length < 3 || text.length > 202 || !text.startsWith("\"") || !text.endsWith("\"")) return PsiReference.EMPTY_ARRAY
                val name = text.substring(1, text.length - 1)
                if (!Regex("[A-Za-z0-9_./-]+").matches(name)) return PsiReference.EMPTY_ARRAY
                val from = element.containingFile?.originalFile?.virtualFile
                if (TeraTemplateRoots.resolveInRoots(element.project, from, name).isEmpty()) return PsiReference.EMPTY_ARRAY
                return arrayOf(TeraRustTemplateReference(element, name))
            }
        })
    }
}

class TeraRustTemplateReference(element: PsiElement, private val name: String) :
    PsiReferenceBase<PsiElement>(element, TextRange(1, element.textLength - 1), true) {

    private fun files(): List<VirtualFile> =
        TeraTemplateRoots.resolveInRoots(element.project, element.containingFile?.originalFile?.virtualFile, name)

    override fun resolve(): PsiElement? = files().firstNotNullOfOrNull { element.manager.findFile(it) }

    override fun isReferenceTo(element: PsiElement) = element is PsiFile && element.originalFile.virtualFile in files()

    override fun handleElementRename(newElementName: String): PsiElement {
        val old = (resolve() as? PsiFile)?.name.orEmpty()
        return ElementManipulators.handleContentChange(element, rangeInElement, TeraTemplatePaths.renamed(name, old, newElementName))
    }

    override fun bindToElement(target: PsiElement): PsiElement {
        val file = (target as? PsiFile)?.originalFile?.virtualFile ?: return element
        val roots = TeraTemplateRoots.roots(element.project, element.containingFile?.originalFile?.virtualFile).map { it.dir }
        val root = roots.firstOrNull { VfsUtilCore.isAncestor(it, file, true) } ?: return element
        val rel = VfsUtilCore.getRelativePath(file, root, '/') ?: return element
        return ElementManipulators.handleContentChange(element, rangeInElement, TeraTemplatePaths.pathFor(name, rel))
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
