package com.tera.tera

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/** A place to jump to: [file] null means the current file; [offset] -1 means the file as a whole. */
data class TeraDefinition(val file: VirtualFile?, val offset: Int, val element: PsiElement? = null)

/** Go-to-definition for macro calls, namespaces, call arguments and template paths. */
object TeraNavigation {

    private val PATH_TAGS = setOf("import", "extends", "include")

    /**
     * @param templatesOf every template [path] resolves to, with the macros each defines
     *   (several when the name is ambiguous, e.g. `macros.html.tera` and `macros.txt.tera`).
     */
    fun definitions(
        text: String,
        offset: Int,
        context: TeraContextResult? = null,
        custom: List<TeraCustomBuiltin> = emptyList(),
        templatesOf: (String) -> List<Pair<VirtualFile, List<MacroDef>>>,
    ): List<TeraDefinition> {
        val blocks = TeraSymbolScanner.scanBlocks(text)
        val symbols = TeraSymbolScanner.symbols(blocks)
        val result = linkedSetOf<TeraDefinition>()

        fun TextRange.touches() = offset in startOffset..endOffset

        // import/extends/include "<path>" → the template file(s)
        for (block in blocks) {
            val first = block.tokens.firstOrNull()?.text
            if (block.kind != '%' || first !in PATH_TAGS) continue
            val string = block.tokens.firstOrNull { it.type == TeraTokenTypes.STRING && it.range.touches() } ?: continue
            templatesOf(TeraSymbolScanner.unquote(string.text)).forEach { result.add(TeraDefinition(it.first, -1)) }
        }

        /** Macro definitions a call can land on: (file or null for this file, definition). */
        fun targets(call: MacroCall): List<Pair<VirtualFile?, MacroDef>> =
            if (call.namespace == "self") {
                symbols.macros.filter { it.name == call.name }.map { null to it }
            } else {
                symbols.imports.filter { it.alias == call.namespace }
                    .flatMap { templatesOf(it.path) }
                    .flatMap { (file, macros) -> macros.filter { it.name == call.name }.map { file to it } }
            }

        for (call in symbols.calls) {
            when {
                call.nameRange.touches() ->
                    targets(call).forEach { (file, def) -> result.add(TeraDefinition(file, def.nameRange.startOffset)) }
                call.namespaceRange.touches() ->
                    symbols.imports.filter { it.alias == call.namespace }
                        .forEach { result.add(TeraDefinition(null, it.aliasRange.startOffset)) }
                else -> {
                    val arg = call.args.firstOrNull { it.nameRange?.touches() == true } ?: continue
                    targets(call).forEach { (file, def) ->
                        def.params.filter { it.name == arg.name }
                            .forEach { result.add(TeraDefinition(file, it.range.startOffset)) }
                    }
                }
            }
        }

        // A variable: its Rust declaration, or the `set`/`for`/macro parameter that introduced it.
        TeraVariables.refAt(text, offset, context)?.let { ref ->
            ref.result.field?.target?.let { result.add(TeraDefinition(null, -1, it)) }
            ref.result.binding?.nameRange?.let { result.add(TeraDefinition(null, it.startOffset)) }
        }

        // A function, filter or test registered from Rust: its definition there.
        TeraBuiltinRefs.at(text, offset, custom)?.let { ref ->
            custom.firstOrNull { it.kind == ref.kind && it.def.name == ref.def.name }?.target
                ?.let { result.add(TeraDefinition(null, -1, it)) }
        }
        return result.toList()
    }
}

class TeraGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (editor == null) return null
        val project = editor.project ?: return null
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        if (file.viewProvider.baseLanguage != TeraLanguage) return null
        val vFile = file.originalFile.virtualFile
        val here = file.viewProvider.getPsi(TeraLanguage) ?: return null

        val definitions = TeraNavigation.definitions(here.text, offset, TeraContexts.forFile(file), TeraContexts.customBuiltins(file.project)) { path ->
            vFile?.let { TeraImports.templatesOf(it, path) }.orEmpty()
        }
        val manager = PsiManager.getInstance(project)
        val targets = definitions.mapNotNull { def ->
            def.element?.let { return@mapNotNull it }
            val psi = if (def.file == null) here else manager.findFile(def.file)?.let {
                it.viewProvider.getPsi(TeraLanguage) ?: it
            } ?: return@mapNotNull null
            if (def.offset < 0) psi else psi.findElementAt(def.offset)
        }
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
