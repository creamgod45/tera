package com.tera.tera

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/*
 * Find Usages and Rename for single-word names: blocks (along the `extends` chain), macros and the variables that
 * `set`, `for` and macros declare. Template syntax is not parsed into a tree, so an identifier token is a leaf of its
 * own kind that knows, from an analysis of its file, whether it declares a name or refers to one.
 */

/** A name a leaf declares. */
data class TeraSymbolDecl(val kind: String, val name: String)

/** A name a leaf refers to, and where it is declared (null file: the same file). */
data class TeraSymbolUse(val kind: String, val name: String, val file: VirtualFile?, val offset: Int)

class TeraFileSymbols(val decls: Map<Int, TeraSymbolDecl>, val uses: Map<Int, TeraSymbolUse>, val paths: Map<Int, String> = emptyMap())

object TeraSymbolAnalysis {

    fun of(file: PsiFile): TeraFileSymbols? {
        if (file.language != TeraLanguage) return null
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(compute(file), com.intellij.psi.util.PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    private fun compute(file: PsiFile): TeraFileSymbols {
        val vFile = file.originalFile.virtualFile
        val text = file.text
        val blocks = TeraSymbolScanner.scanBlocks(text)
        val decls = linkedMapOf<Int, TeraSymbolDecl>()
        val uses = linkedMapOf<Int, TeraSymbolUse>()
        val project = file.project

        // Blocks: the topmost definition along `extends` is the declaration; the overrides and every `endblock x` refer to it.
        val blockDefs = TeraBlocks.defs(blocks)
        fun rootOf(name: String): Pair<VirtualFile, BlockDef>? {
            vFile ?: return null
            var current: VirtualFile = vFile
            var found: Pair<VirtualFile, BlockDef>? = null
            repeat(20) {
                val parent = TeraBlocks.parents(current, name).firstOrNull() ?: return found
                found = parent
                current = parent.first
            }
            return found
        }
        for (def in blockDefs) {
            val root = rootOf(def.name)
            if (root == null) decls[def.range.startOffset] = TeraSymbolDecl("block", def.name)
            else uses[def.range.startOffset] = TeraSymbolUse("block", def.name, root.first, root.second.range.startOffset)
        }
        for (b in blocks) {
            val t = b.tokens
            if (b.kind == '%' && t.size >= 2 && t[0].text == "endblock" && t[1].type == TeraTokenTypes.IDENTIFIER) {
                val own = blockDefs.firstOrNull { it.name == t[1].text } ?: continue
                val root = rootOf(own.name)
                uses[t[1].range.startOffset] = if (root == null) TeraSymbolUse("block", own.name, null, own.range.startOffset)
                else TeraSymbolUse("block", own.name, root.first, root.second.range.startOffset)
            }
        }

        // Macros (Tera 1): declared by `macro`, used through `ns::name(`, `self::name(` and `endmacro name`.
        val symbols = TeraSymbolScanner.symbols(blocks)
        for (m in symbols.macros) decls[m.nameRange.startOffset] = TeraSymbolDecl("macro", m.name)
        for (b in blocks) {
            val t = b.tokens
            if (b.kind == '%' && t.size >= 2 && t[0].text == "endmacro" && t[1].type == TeraTokenTypes.IDENTIFIER) {
                symbols.macros.firstOrNull { it.name == t[1].text }?.let { uses[t[1].range.startOffset] = TeraSymbolUse("macro", it.name, null, it.nameRange.startOffset) }
            }
        }
        for (call in symbols.calls) {
            val target = TeraNavigation.definitions(text, call.nameRange.startOffset) { path ->
                vFile?.let { TeraImports.templatesOf(it, path) }.orEmpty()
            }.firstOrNull { it.offset >= 0 } ?: continue
            uses[call.nameRange.startOffset] = TeraSymbolUse("macro", call.name, target.file, target.offset)
        }

        // Variables: `set`/`set_global`/`for` declare; macro parameters too; uses resolve through the scope analysis.
        for (b in blocks) {
            val t = b.tokens
            if (b.kind != '%' || t.size < 2) continue
            when (t[0].text) {
                "set", "set_global" -> if (t[1].type == TeraTokenTypes.IDENTIFIER) decls[t[1].range.startOffset] = TeraSymbolDecl("variable", t[1].text)
                "for" -> for (tok in t.drop(1).takeWhile { !(it.type == TeraTokenTypes.KEYWORD && it.text == "in") }) {
                    if (tok.type == TeraTokenTypes.IDENTIFIER) decls[tok.range.startOffset] = TeraSymbolDecl("variable", tok.text)
                }
            }
        }
        for (m in symbols.macros) for (p in m.params) if (p.range.length == p.name.length) decls[p.range.startOffset] = TeraSymbolDecl("variable", p.name)
        val version = TeraVersions.forFile(project, vFile).version
        val context = TeraContexts.forFile(file)
        for (occ in TeraVariables.collect(blocks, TeraScopes.snapshots(blocks, context, version, text))) {
            val first = occ.tokens.firstOrNull() ?: continue
            val r = occ.resolve(0, context)
            val at = r.binding?.nameRange ?: continue
            if (r.failedAt >= 0 || at.length != first.text.length || at.startOffset == first.range.startOffset) continue
            uses.putIfAbsent(first.range.startOffset, TeraSymbolUse("variable", first.text, null, at.startOffset))
            decls.putIfAbsent(at.startOffset, TeraSymbolDecl("variable", first.text))
        }
        // Template paths in `extends`, `include` and `import`.
        val paths = linkedMapOf<Int, String>()
        for (b in blocks) {
            val t = b.tokens
            if (b.kind == '%' && t.size >= 2 && t[0].text in setOf("extends", "include", "import") && t[1].type == TeraTokenTypes.STRING) {
                paths[t[1].range.startOffset] = TeraSymbolScanner.unquote(t[1].text)
            }
        }
        return TeraFileSymbols(decls, uses, paths)
    }
}

/** An identifier of a template: a declaration, a reference, or neither. */
class TeraNameLeaf(type: IElementType, text: CharSequence) : LeafPsiElement(type, text), PsiNamedElement {
    override fun getName(): String = text

    override fun setName(name: String): PsiElement {
        replaceWithText(name)
        return this
    }

    val declaration: TeraSymbolDecl? get() = containingFile?.let { TeraSymbolAnalysis.of(it) }?.decls?.get(textRange.startOffset)

    override fun getReference(): PsiReference? {
        val use = containingFile?.let { TeraSymbolAnalysis.of(it) }?.uses?.get(textRange.startOffset) ?: return null
        return TeraSymbolReference(this, use)
    }

    override fun getReferences(): Array<PsiReference> = getReference()?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY
}

class TeraSymbolReference(element: TeraNameLeaf, private val use: TeraSymbolUse) :
    PsiReferenceBase<TeraNameLeaf>(element, TextRange(0, element.textLength), false) {

    override fun resolve(): PsiElement? {
        val file = use.file ?: element.containingFile.originalFile.virtualFile ?: return null
        return TeraUsages.elementAt(element.project, file, use.offset) as? TeraNameLeaf
    }

    override fun handleElementRename(newElementName: String): PsiElement = element.setName(newElementName)
    override fun getVariants(): Array<Any> = emptyArray()
}

/** Only declarations are what Find Usages and Rename start from. */
class TeraFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        TeraLexer(), TokenSet.create(TeraTokenTypes.IDENTIFIER, TeraTokenTypes.KEYWORD),
        TeraParserDefinition.COMMENT_TOKENS, TeraParserDefinition.STRING_TOKENS,
    )
    override fun canFindUsagesFor(psiElement: PsiElement) = (psiElement as? TeraNameLeaf)?.declaration != null
    override fun getHelpId(psiElement: PsiElement): String? = null
    override fun getType(element: PsiElement) = (element as? TeraNameLeaf)?.declaration?.kind ?: ""
    override fun getDescriptiveName(element: PsiElement) = (element as? TeraNameLeaf)?.name.orEmpty()
    override fun getNodeText(element: PsiElement, useFullName: Boolean) = (element as? TeraNameLeaf)?.name.orEmpty()
}

/** Which words may be used as a new name. */
class TeraNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?) = name in TeraLexer.KEYWORDS
    override fun isIdentifier(name: String, project: Project?) = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)
}

/** The element a caret on a declaration or a reference leads to: only identifiers that have a meaning. */
class TeraTargetElementEvaluator : com.intellij.codeInsight.TargetElementEvaluatorEx2() {
    override fun getNamedElement(element: PsiElement): PsiElement? = (element as? TeraNameLeaf)?.takeIf { it.declaration != null }
    override fun isAcceptableNamedParent(parent: PsiElement) = (parent as? TeraNameLeaf)?.declaration != null
}

/** Rename starts only from names the analysis calls declarations (or from references to them). */
class TeraRenameProcessor : com.intellij.refactoring.rename.RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement) = (element as? TeraNameLeaf)?.declaration != null
}

/** The string of `extends "layouts/app.html"` and friends: a reference to the template file. */
class TeraStringLeaf(type: IElementType, text: CharSequence) : LeafPsiElement(type, text) {
    override fun getReference(): PsiReference? {
        val path = containingFile?.let { TeraSymbolAnalysis.of(it) }?.paths?.get(textRange.startOffset) ?: return null
        return TeraTemplateReference(this, path)
    }

    override fun getReferences(): Array<PsiReference> = getReference()?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY

    /** Replaces the text between the quotes. */
    fun replacePath(newPath: String) {
        val quote = text.first()
        replaceWithText("$quote$newPath$quote")
    }
}

class TeraTemplateReference(element: TeraStringLeaf, private val path: String) :
    PsiReferenceBase<TeraStringLeaf>(element, TextRange(1, (element.textLength - 1).coerceAtLeast(1)), false) {

    private fun files(): List<VirtualFile> =
        element.containingFile.originalFile.virtualFile?.let { TeraTemplateResolver.resolveAll(it, path) }.orEmpty()

    override fun resolve(): PsiElement? = files().firstNotNullOfOrNull { element.manager.findFile(it) }

    override fun isReferenceTo(element: PsiElement) = element is PsiFile && element.originalFile.virtualFile in files()

    override fun handleElementRename(newElementName: String): PsiElement {
        element.replacePath(TeraTemplatePaths.renamed(path, (resolve() as? PsiFile)?.name.orEmpty(), newElementName))
        return element
    }

    override fun bindToElement(target: PsiElement): PsiElement {
        val file = (target as? PsiFile)?.originalFile?.virtualFile ?: return element
        val project = element.project
        val roots = TeraTemplateRoots.manual(project).map { it.dir } +
            generateSequence(file.parent) { it.parent }.filter { it.name == "templates" }.toList() +
            TeraTemplateRoots.roots(project, element.containingFile.originalFile.virtualFile).map { it.dir }
        val root = roots.firstOrNull { com.intellij.openapi.vfs.VfsUtilCore.isAncestor(it, file, true) } ?: return element
        val rel = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, root, '/') ?: return element
        element.replacePath(TeraTemplatePaths.pathFor(path, rel))
        return element
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

/** How a path written in a template follows a file that was renamed or moved. */
object TeraTemplatePaths {
    /** The path after the file [oldFileName] became [newFileName], keeping the directory and the way the extension was written. */
    fun renamed(path: String, oldFileName: String, newFileName: String): String {
        val dir = path.substringBeforeLast('/', "")
        val last = path.substringAfterLast('/')
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        if (oldFileName.isEmpty()) return path
        val newLast = when {
            last == oldFileName -> newFileName
            last == oldFileName.removeSuffix(".tera") -> newFileName.removeSuffix(".tera")
            oldFileName.startsWith("$last.") -> {
                val extensions = oldFileName.removePrefix(last)                // `.html.tera`
                if (newFileName.endsWith(extensions)) newFileName.removeSuffix(extensions) else newFileName.substringBefore('.')
            }
            else -> return path
        }
        return prefix + newLast
    }

    /** The path for the file now at [relative] (from the template root), written like the [old] path was. */
    fun pathFor(old: String, relative: String): String {
        val oldLast = old.substringAfterLast('/')
        val newLast = relative.substringAfterLast('/')
        val dir = relative.substringBeforeLast('/', "")
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        return prefix + when {
            oldLast.endsWith(".tera") -> newLast
            '.' in oldLast -> newLast.removeSuffix(".tera")
            else -> newLast.substringBefore('.')
        }
    }
}

/**
 * Finds the strings that name a template file when the string does not spell out the whole file name: `"layouts/app"`
 * for `app.html.tera`. The platform looks for the file's name; this also looks for the name up to the first dot.
 */
class TeraTemplateReferencesSearcher : com.intellij.openapi.application.QueryExecutorBase<PsiReference, com.intellij.psi.search.searches.ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(
        queryParameters: com.intellij.psi.search.searches.ReferencesSearch.SearchParameters,
        consumer: com.intellij.util.Processor<in PsiReference>,
    ) {
        val file = queryParameters.elementToSearch as? PsiFile ?: return
        val name = file.name
        if (!name.endsWith(".tera")) return
        val word = name.substringBefore('.')
        if (word.isBlank()) return
        queryParameters.optimizer.searchWord(word, queryParameters.effectiveSearchScope,
            com.intellij.psi.search.UsageSearchContext.IN_STRINGS, true, file)
    }
}
