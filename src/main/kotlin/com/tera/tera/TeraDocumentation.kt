package com.tera.tera

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/** `@param name {type} text` entry of a macro's doc comment. */
data class ParamDoc(val type: String?, val text: String)

/** Parsed `{# ... #}` comment written directly above a macro. */
data class DocComment(val description: String, val params: Map<String, ParamDoc>)

/** A built-in function, filter or test used at some token. */
class BuiltinRef(val def: BuiltinDef, val kind: String, val token: TeraTok)

object TeraBuiltinRefs {
    private val SKIPPED_TAGS = setOf("macro", "endmacro", "import", "extends", "include", "block", "endblock")

    /** The built-in named by the token under [offset], decided by how it is written (`|`, `is`, `name(`). */
    fun at(text: String, offset: Int, custom: List<TeraCustomBuiltin> = emptyList(), version: TeraVersion = TeraVersion.V1): BuiltinRef? {
        for (block in TeraSymbolScanner.scanBlocks(text)) {
            if (offset < block.contentStart || offset > block.contentEnd) continue
            val t = block.tokens
            val first = t.firstOrNull()?.text
            if (block.kind == '%' && first in SKIPPED_TAGS) return null
            val i = t.indexOfFirst { it.type == TeraTokenTypes.IDENTIFIER && offset in it.range.startOffset..it.range.endOffset }
            if (i < 0) return null
            val prev = t.getOrNull(i - 1)
            val next = t.getOrNull(i + 1)
            fun op(x: TeraTok?, s: String) = x != null && x.type == TeraTokenTypes.OPERATOR && x.text == s
            val name = t[i].text
            fun kw(x: TeraTok?, s: String) = x != null && x.type == TeraTokenTypes.KEYWORD && x.text == s
            return when {
                op(prev, "|") || (block.kind == '%' && first == "filter" && i == 1) ->
                    TeraBuiltins.byName("filter", custom, version)[name]?.let { BuiltinRef(it, "filter", t[i]) }
                kw(prev, "is") || (kw(prev, "not") && kw(t.getOrNull(i - 2), "is")) ->
                    TeraBuiltins.byName("test", custom, version)[name]?.let { BuiltinRef(it, "test", t[i]) }
                op(next, "(") && !op(prev, ".") && !op(prev, ":") ->
                    TeraBuiltins.byName("function", custom, version)[name]?.let { BuiltinRef(it, "function", t[i]) }
                else -> null
            }
        }
        return null
    }
}

/** Builds the Quick Documentation popups for macros, their parameters and import aliases. */
object TeraDocumentation {

    private val PARAM_TAG = Regex("""^@param\s+([A-Za-z_]\w*)\s*(?:\{([^}]*)\})?\s*(?:[-–:]\s*)?(.*)$""")
    private val NUMBER = Regex("""-?\d+""")
    private val FLOAT = Regex("""-?\d+\.\d+""")

    // ── model helpers ────────────────────────────────────────────────────────

    /** Declared `{type}` of the parameter, else a type inferred from its default value, else `any`. */
    fun typeOf(p: MacroParam, doc: DocComment?): String {
        doc?.params?.get(p.name)?.type?.takeIf { it.isNotBlank() }?.let { return it }
        val d = p.defaultText?.trim().orEmpty()
        return when {
            d.isEmpty() -> "any"
            d.startsWith("\"") || d.startsWith("'") -> "string"
            d == "true" || d == "false" -> "boolean"
            FLOAT.matches(d) -> "float"
            NUMBER.matches(d) -> "integer"
            d.startsWith("[") -> "array"
            d.startsWith("{") -> "object"
            else -> "any"
        }
    }

    fun signature(m: MacroDef): String =
        m.params.joinToString(", ", "macro ${m.name}(", ")") { p ->
            if (p.hasDefault) "${p.name}=${p.defaultText.orEmpty()}" else p.name
        }

    /** The `{# ... #}` comment placed directly before the macro's opening tag, if any. */
    fun docComment(text: String, macro: MacroDef): DocComment? = docCommentAt(text, macro.nameRange.startOffset)

    /** The comment directly before the `{% macro %}` / `{% component %}` tag that contains [nameStart]. */
    fun docCommentAt(text: String, nameStart: Int): DocComment? {
        val tagStart = text.lastIndexOf("{%", nameStart)
        if (tagStart < 0) return null
        val before = text.substring(0, tagStart).trimEnd()
        if (!before.endsWith("#}")) return null
        val commentStart = before.lastIndexOf("{#")
        if (commentStart < 0) return null
        val body = before.substring(commentStart + 2, before.length - 2).trim('-', ' ', '\t', '\r', '\n')
        val lines = body.lines().map { it.trim().removePrefix("*").trim() }

        val description = mutableListOf<String>()
        val params = linkedMapOf<String, ParamDoc>()
        var current: String? = null
        for (line in lines) {
            val m = PARAM_TAG.matchEntire(line)
            when {
                m != null -> {
                    current = m.groupValues[1]
                    params[current] = ParamDoc(m.groupValues[2].trim().ifEmpty { null }, m.groupValues[3].trim())
                }
                line.startsWith("@") -> current = null
                current != null && line.isNotEmpty() -> params[current]!!.let {
                    params[current] = it.copy(text = (it.text + " " + line).trim())
                }
                current == null -> description.add(line)
            }
        }
        val desc = description.joinToString("\n").trim()
        if (desc.isEmpty() && params.isEmpty()) return null
        return DocComment(desc, params)
    }

    // ── HTML ─────────────────────────────────────────────────────────────────

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)
    private fun para(s: String) = esc(s).replace("\n", "<br>")

    private fun section(sb: StringBuilder, header: String, body: String) {
        sb.append(DocumentationMarkup.SECTION_HEADER_START).append(esc(header))
            .append(DocumentationMarkup.SECTION_SEPARATOR).append(body).append(DocumentationMarkup.SECTION_END)
    }

    fun macroHtml(text: String, macro: MacroDef, fileName: String): String {
        val doc = docComment(text, macro)
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append("<pre>").append(esc(signature(macro))).append("</pre>")
            .append(DocumentationMarkup.DEFINITION_END)
        doc?.description?.takeIf { it.isNotEmpty() }?.let {
            sb.append(DocumentationMarkup.CONTENT_START).append(para(it)).append(DocumentationMarkup.CONTENT_END)
        }
        sb.append(DocumentationMarkup.SECTIONS_START)
        if (macro.params.isEmpty()) {
            section(sb, "Parameters:", "none")
        } else {
            val body = StringBuilder()
            for (p in macro.params) {
                body.append("<p><code>").append(esc(p.name)).append("</code>: <i>").append(esc(typeOf(p, doc)))
                    .append("</i> – ")
                if (p.hasDefault) body.append("optional, default <code>").append(esc(p.defaultText.orEmpty())).append("</code>")
                else body.append("required")
                doc?.params?.get(p.name)?.text?.takeIf { it.isNotEmpty() }?.let { body.append(" – ").append(esc(it)) }
                body.append("</p>")
            }
            section(sb, "Parameters:", body.toString())
        }
        section(sb, "Defined in:", "<code>${esc(fileName)}</code>")
        sb.append(DocumentationMarkup.SECTIONS_END)
        return sb.toString()
    }

    fun paramHtml(text: String, macro: MacroDef, param: MacroParam, fileName: String): String {
        val doc = docComment(text, macro)
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append("<pre>(parameter) ")
            .append(esc(param.name)).append(": ").append(esc(typeOf(param, doc)))
            .append(if (param.hasDefault) " = " + esc(param.defaultText.orEmpty()) else "")
            .append("</pre>").append(DocumentationMarkup.DEFINITION_END)
        doc?.params?.get(param.name)?.text?.takeIf { it.isNotEmpty() }?.let {
            sb.append(DocumentationMarkup.CONTENT_START).append(para(it)).append(DocumentationMarkup.CONTENT_END)
        }
        sb.append(DocumentationMarkup.SECTIONS_START)
        section(sb, "Kind:", if (param.hasDefault) "optional, default <code>${esc(param.defaultText.orEmpty())}</code>" else "required")
        section(sb, "Macro:", "<code>${esc(signature(macro))}</code>")
        doc?.description?.takeIf { it.isNotEmpty() }?.let { section(sb, "About macro:", para(it)) }
        section(sb, "Defined in:", "<code>${esc(fileName)}</code>")
        sb.append(DocumentationMarkup.SECTIONS_END)
        return sb.toString()
    }

    fun builtinHtml(ref: BuiltinRef): String {
        val def = ref.def
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append("<pre>(").append(ref.kind).append(") ")
            .append(esc(def.signature())).append("</pre>").append(DocumentationMarkup.DEFINITION_END)
        sb.append(DocumentationMarkup.CONTENT_START)
        if (def.details != null) sb.append(para(def.details))
        else sb.append(esc(def.description.replaceFirstChar(Char::uppercaseChar))).append(".")
        sb.append(DocumentationMarkup.CONTENT_END)
        sb.append(DocumentationMarkup.SECTIONS_START)
        if (def.params.isNotEmpty()) {
            val body = StringBuilder()
            for (p in def.params) {
                body.append("<p><code>").append(esc(p.name)).append("</code> – ")
                body.append(
                    when {
                        def.positional -> "positional argument"
                        p.required -> "required"
                        p.default != null -> "optional, default <code>${esc(p.default)}</code>"
                        else -> "optional"
                    },
                )
                body.append(" – ").append(esc(p.doc)).append("</p>")
            }
            section(sb, "Arguments:", body.toString())
        } else {
            section(sb, "Arguments:", "none")
        }
        def.example?.let { section(sb, "Example:", "<code>${esc(it)}</code>") }
        section(sb, "Source:", def.source?.let { "registered in Rust (${esc(it)})" } ?: "Tera built-in ${esc(ref.kind)}")
        sb.append(DocumentationMarkup.SECTIONS_END)
        return sb.toString()
    }

    fun componentHtml(text: String, def: ComponentDef, fileName: String): String {
        val doc = docCommentAt(text, def.nameRange.startOffset)
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append("<pre>component ").append(esc(def.signature())).append("</pre>")
            .append(DocumentationMarkup.DEFINITION_END)
        doc?.description?.takeIf { it.isNotEmpty() }?.let {
            sb.append(DocumentationMarkup.CONTENT_START).append(para(it)).append(DocumentationMarkup.CONTENT_END)
        }
        sb.append(DocumentationMarkup.SECTIONS_START)
        if (def.params.isEmpty()) section(sb, "Attributes:", "none") else {
            val body = StringBuilder()
            for (p in def.params) {
                body.append("<p><code>").append(esc(if (p.rest) "...${p.name}" else if (p.implicit) "@${p.name}" else p.name)).append("</code>")
                p.type?.let { body.append(": <i>").append(esc(it)).append("</i>") }
                body.append(" – ").append(componentParamKind(p))
                doc?.params?.get(p.name)?.text?.takeIf { it.isNotEmpty() }?.let { body.append(" – ").append(esc(it)) }
                body.append("</p>")
            }
            section(sb, "Attributes:", body.toString())
        }
        section(sb, "Called as:", "<code>${esc("<" + def.name + def.requiredParams.joinToString("") { " ${it.name}=…" } + "/>")}</code>")
        section(sb, "Defined in:", "<code>${esc(fileName)}</code>")
        sb.append(DocumentationMarkup.SECTIONS_END)
        return sb.toString()
    }

    private fun componentParamKind(p: ComponentParam): String = when {
        p.rest -> "collects every other attribute into a map"
        p.implicit -> "implicit, read from the caller's scope" + (p.defaultText?.let { ", default <code>${esc(it)}</code>" } ?: "")
        p.hasDefault -> "optional, default <code>${esc(p.defaultText.orEmpty())}</code>"
        else -> "required"
    }

    fun componentAttributeHtml(text: String, def: ComponentDef, param: ComponentParam, fileName: String): String {
        val doc = docCommentAt(text, def.nameRange.startOffset)
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append("<pre>(attribute) ").append(esc(param.name))
            .append(": ").append(esc(param.type ?: "any"))
            .append(param.defaultText?.let { " = " + esc(it) }.orEmpty()).append("</pre>").append(DocumentationMarkup.DEFINITION_END)
        doc?.params?.get(param.name)?.text?.takeIf { it.isNotEmpty() }?.let {
            sb.append(DocumentationMarkup.CONTENT_START).append(para(it)).append(DocumentationMarkup.CONTENT_END)
        }
        sb.append(DocumentationMarkup.SECTIONS_START)
        section(sb, "Kind:", componentParamKind(param))
        section(sb, "Component:", "<code>${esc(def.signature())}</code>")
        doc?.description?.takeIf { it.isNotEmpty() }?.let { section(sb, "About component:", para(it)) }
        section(sb, "Defined in:", "<code>${esc(fileName)}</code>")
        sb.append(DocumentationMarkup.SECTIONS_END)
        return sb.toString()
    }

    fun variableHtml(ref: TeraVariables.Ref, context: TeraContextResult?): String {
        val r = ref.result
        val name = ref.occurrence.segs[ref.index].name
        val shown = r.field?.rustType ?: r.type.display()
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append("<pre>(variable) ")
            .append(esc(name)).append(": ").append(esc(shown)).append("</pre>").append(DocumentationMarkup.DEFINITION_END)
        r.field?.doc?.takeIf { it.isNotBlank() }?.let {
            sb.append(DocumentationMarkup.CONTENT_START).append(para(it)).append(DocumentationMarkup.CONTENT_END)
        }
        sb.append(DocumentationMarkup.SECTIONS_START)
        section(sb, "Template type:", "<code>${esc(r.type.display())}</code>")
        val struct = r.type as? TeraType.Struct
        if (struct != null && struct.fields.isNotEmpty()) {
            section(sb, "Fields:", struct.fields.take(30).joinToString("") { f ->
                "<p><code>${esc(f.name)}</code>: ${esc(f.rustType ?: f.type.display())}</p>"
            })
        }
        val source = when {
            r.binding != null -> r.binding.detail ?: "template"
            context != null && ref.index == 0 -> "Rust render context" +
                context.sources.takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")").orEmpty()
            else -> "field of the Rust render context"
        }
        section(sb, "Source:", esc(source))
        sb.append(DocumentationMarkup.SECTIONS_END)
        return sb.toString()
    }

    /** [templates]: file name, its text and macros, for every template the import path resolves to. */
    fun importHtml(imp: ImportDecl, templates: List<Triple<String, String, List<MacroDef>>>): String {
        val sb = StringBuilder()
        sb.append(DocumentationMarkup.DEFINITION_START).append("<pre>import \"").append(esc(imp.path))
            .append("\" as ").append(esc(imp.alias)).append("</pre>").append(DocumentationMarkup.DEFINITION_END)
        sb.append(DocumentationMarkup.SECTIONS_START)
        if (templates.isEmpty()) {
            section(sb, "Template:", "not found")
        } else {
            section(sb, "Template:", templates.joinToString("<br>") { "<code>${esc(it.first)}</code>" })
            val body = StringBuilder()
            for ((_, text, macros) in templates) for (m in macros) {
                body.append("<p><code>").append(esc(signature(m).removePrefix("macro "))).append("</code>")
                docComment(text, m)?.description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotEmpty() }
                    ?.let { body.append(" – ").append(esc(it)) }
                body.append("</p>")
            }
            section(sb, "Macros:", body.toString().ifEmpty { "none" })
        }
        sb.append(DocumentationMarkup.SECTIONS_END)
        return sb.toString()
    }
}

class TeraDocumentationProvider : AbstractDocumentationProvider() {

    /** What a documentation request on a leaf of a Tera file refers to. */
    private sealed interface Described {
        class Macro(val macro: MacroDef) : Described
        class Param(val macro: MacroDef, val param: MacroParam) : Described
        class Import(val imp: ImportDecl) : Described
        class Variable(val ref: TeraVariables.Ref, val context: TeraContextResult?) : Described
        class Builtin(val ref: BuiltinRef) : Described
        class Component(val def: ComponentDef) : Described
        class ComponentAttribute(val def: ComponentDef, val param: ComponentParam) : Described
    }

    private fun versionOf(file: PsiFile): TeraVersion =
        TeraVersions.forFile(file.project, file.originalFile.virtualFile).version

    private fun describe(file: PsiFile, offset: Int): Described? {
        val symbols = TeraSymbolScanner.scan(file.text)
        for (m in symbols.macros) {
            if (m.nameRange.startOffset == offset) return Described.Macro(m)
            m.params.firstOrNull { it.range.startOffset == offset }?.let { return Described.Param(m, it) }
        }
        symbols.imports.firstOrNull { it.aliasRange.startOffset == offset }?.let { return Described.Import(it) }
        for (c in TeraComponentScanner.definitions(file.text)) {
            if (c.nameRange.startOffset == offset) return Described.Component(c)
            c.params.firstOrNull { it.range.startOffset == offset }?.let { return Described.ComponentAttribute(c, it) }
        }
        TeraBuiltinRefs.at(file.text, offset, TeraContexts.customBuiltins(file.project), versionOf(file))?.takeIf { it.token.range.startOffset == offset }
            ?.let { return Described.Builtin(it) }
        val context = TeraContexts.forFile(file)
        TeraVariables.refAt(file.text, offset, context, versionOf(file))?.takeIf { it.range.startOffset == offset }
            ?.let { return Described.Variable(it, context) }
        return null
    }

    /**
     * The declaration leaf (macro name, parameter or import alias — in whichever file declares it)
     * that documentation at [targetOffset] describes: hovering any part of
     * `m::input(label=...)` or a declaration itself both work.
     */
    override fun getCustomDocumentationElement(
        editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int,
    ): PsiElement? {
        if (file.viewProvider.baseLanguage != TeraLanguage) return null
        val here = file.viewProvider.getPsi(TeraLanguage) ?: return null
        val vFile = file.originalFile.virtualFile

        // Hovering a declaration itself.
        val symbols = TeraSymbolScanner.scan(here.text)
        for (m in symbols.macros) {
            if (targetOffset in m.nameRange.startOffset..m.nameRange.endOffset) return here.findElementAt(m.nameRange.startOffset)
            m.params.firstOrNull { targetOffset in it.range.startOffset..it.range.endOffset }
                ?.let { return here.findElementAt(it.range.startOffset) }
        }
        symbols.imports.firstOrNull { targetOffset in it.aliasRange.startOffset..it.aliasRange.endOffset }
            ?.let { return here.findElementAt(it.aliasRange.startOffset) }
        for (c in TeraComponentScanner.definitions(here.text)) {
            if (targetOffset in c.nameRange.startOffset..c.nameRange.endOffset) return here.findElementAt(c.nameRange.startOffset)
            c.params.firstOrNull { targetOffset in it.range.startOffset..it.range.endOffset }
                ?.let { return here.findElementAt(it.range.startOffset) }
        }

        // Hovering a built-in function, filter or test.
        TeraBuiltinRefs.at(here.text, targetOffset, TeraContexts.customBuiltins(file.project), versionOf(file))?.let { return here.findElementAt(it.token.range.startOffset) }

        // Hovering a variable use.
        TeraVariables.refAt(here.text, targetOffset, TeraContexts.forFile(file), versionOf(file))
            ?.let { return here.findElementAt(it.range.startOffset) }

        // Hovering a use: take the first declaration navigation would offer.
        val templatesOf = { path: String -> vFile?.let { TeraImports.templatesOf(it, path) }.orEmpty() }
        val manager = PsiManager.getInstance(file.project)
        for (def in TeraNavigation.definitions(here.text, targetOffset, null, emptyList(), versionOf(file),
            TeraComponents.visible(file.project, vFile, here.text), templatesOf)) {
            if (def.offset < 0) continue
            val psi = if (def.file == null) here
            else manager.findFile(def.file)?.viewProvider?.getPsi(TeraLanguage) ?: continue
            if (describe(psi, def.offset) != null) return psi.findElementAt(def.offset)
        }
        return null
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        element ?: return null
        val file = element.containingFile
        val text = file.text
        return when (val d = describe(file, element.textRange.startOffset)) {
            is Described.Macro -> TeraDocumentation.macroHtml(text, d.macro, file.name)
            is Described.Param -> TeraDocumentation.paramHtml(text, d.macro, d.param, file.name)
            is Described.Import -> {
                val vFile: VirtualFile? = file.originalFile.virtualFile
                val templates = vFile?.let { TeraImports.templatesOf(it, d.imp.path) }.orEmpty().map { (f, macros) ->
                    val src = TeraFiles.textOf(f)
                    Triple(f.name, src, macros)
                }
                TeraDocumentation.importHtml(d.imp, templates)
            }
            is Described.Variable -> TeraDocumentation.variableHtml(d.ref, d.context)
            is Described.Builtin -> TeraDocumentation.builtinHtml(d.ref)
            is Described.Component -> TeraDocumentation.componentHtml(text, d.def, file.name)
            is Described.ComponentAttribute -> TeraDocumentation.componentAttributeHtml(text, d.def, d.param, file.name)
            null -> null
        }
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        element ?: return null
        val file = element.containingFile
        return when (val d = describe(file, element.textRange.startOffset)) {
            is Described.Macro -> "${file.name}: ${TeraDocumentation.signature(d.macro)}"
            is Described.Param -> {
                val doc = TeraDocumentation.docComment(file.text, d.macro)
                "${file.name}: ${d.macro.name}(${d.param.name}: ${TeraDocumentation.typeOf(d.param, doc)})"
            }
            is Described.Import -> "import \"${d.imp.path}\" as ${d.imp.alias}"
            is Described.Builtin -> "(${d.ref.kind}) ${d.ref.def.signature()}"
            is Described.Component -> "${file.name}: component ${d.def.signature()}"
            is Described.ComponentAttribute -> "${file.name}: ${d.def.name}(${d.param.name}${d.param.type?.let { ": $it" }.orEmpty()})"
            is Described.Variable -> "(variable) ${d.ref.occurrence.segs[d.ref.index].name}: " +
                (d.ref.result.field?.rustType ?: d.ref.result.type.display())
            null -> null
        }
    }
}
