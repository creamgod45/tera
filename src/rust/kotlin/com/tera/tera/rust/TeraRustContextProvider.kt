package com.tera.tera.rust

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.tera.tera.TeraContextProvider
import com.tera.tera.TeraContextResult
import com.tera.tera.TeraField
import com.tera.tera.TeraType
import org.rust.lang.core.psi.RsArrayType
import org.rust.lang.core.psi.RsCallExpr
import org.rust.lang.core.psi.RsExpr
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsLetDecl
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.RsMacroExpr
import org.rust.lang.core.psi.RsParenExpr
import org.rust.lang.core.psi.RsPathType
import org.rust.lang.core.psi.RsRefLikeType
import org.rust.lang.core.psi.RsTupleType
import org.rust.lang.core.psi.RsTypeReference
import org.rust.lang.core.psi.RsValueParameter
import org.rust.lang.core.psi.RsPathExpr
import org.rust.lang.core.psi.RsPatBinding
import org.rust.lang.core.macros.findMacroCallExpandedFrom
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.RsStructLiteral
import org.rust.lang.core.psi.RsUnaryExpr
import org.rust.lang.core.psi.ext.RsStructOrEnumItemElement
import org.rust.lang.core.psi.ext.expansionFlatten
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.ty.TyArray
import org.rust.lang.core.types.ty.TyBool
import org.rust.lang.core.types.ty.TyChar
import org.rust.lang.core.types.ty.TyFloat
import org.rust.lang.core.types.ty.TyInteger
import org.rust.lang.core.types.ty.TyRef
import org.rust.lang.core.types.ty.TySlice
import org.rust.lang.core.types.ty.TyStr
import org.rust.lang.core.types.ty.TyTuple
import org.rust.lang.core.types.normType
import org.rust.lang.core.types.type

/**
 * Finds the `Template::render("name", <context>)` calls that render a template and derives the
 * variables from the Rust side, using the Rust plugin's PSI and type inference: struct fields
 * (honouring serde attributes) or the keys of a `context! { ... }` macro.
 */
class TeraRustContextProvider : TeraContextProvider {

    private class Site(val file: VirtualFile, val call: RsCallExpr, val arg: RsExpr?, val line: Int)

    override fun resolve(project: Project, template: VirtualFile): TeraContextResult? {
        if (DumbService.isDumb(project)) return null
        val sites = renderSites(project, templateName(template))
        if (sites.isEmpty()) return null
        val vars = linkedMapOf<String, TeraField>()
        var complete = true
        val sources = mutableListOf<String>()
        for (site in sites) {
            sources.add("${site.file.name}:${site.line}")
            val ctx = site.arg?.let { RustContext.of(it) }
            if (ctx == null) { complete = false; continue }
            if (!ctx.complete) complete = false
            for (f in ctx.fields) vars.putIfAbsent(f.name, f)
        }
        return TeraContextResult(vars.values.toList(), complete, sources)
    }

    /** Rocket names a template by its path below `templates/`, without any extension. */
    internal fun templateName(template: VirtualFile): String {
        val parts = ArrayDeque<String>()
        var dir: VirtualFile? = template.parent
        var found = false
        while (dir != null) {
            if (dir.name == "templates") { found = true; break }
            parts.addFirst(dir.name)
            dir = dir.parent
        }
        val base = template.name.substringBefore('.')
        return if (found) (parts + base).joinToString("/") else base
    }

    private fun renderSites(project: Project, name: String): List<Site> {
        val pattern = Regex("""\bTemplate\s*::\s*render\s*\(\s*"${Regex.escape(name)}"\s*,""")
        val result = mutableListOf<Site>()
        val files = FilenameIndex.getAllFilesByExt(project, "rs", GlobalSearchScope.projectScope(project))
        for (vf in files) {
            if ("/target/" in vf.path) continue
            val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.text
                ?: String(vf.contentsToByteArray(), vf.charset)
            val matches = pattern.findAll(text).toList()
            if (matches.isEmpty()) continue
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            for (m in matches) {
                val leaf = psi.findElementAt(m.range.first) ?: continue
                val call = PsiTreeUtil.getParentOfType(leaf, RsCallExpr::class.java, false) ?: continue
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                result.add(Site(vf, call, call.valueArgumentList.exprList.getOrNull(1), line))
            }
        }
        return result
    }
}

/** What one render call provides. [complete] is false when parts of the context could not be understood. */
internal class RustContext(val fields: List<TeraField>, val complete: Boolean) {
    companion object {
        fun of(arg: RsExpr): RustContext? {
            var e: RsExpr = arg
            while (true) {
                e = when {
                    e is RsUnaryExpr && e.and != null -> e.expr ?: break
                    e is RsParenExpr -> e.expr ?: break
                    else -> break
                }
            }
            if (e is RsMacroExpr && e.macroCall.path?.referenceName == "context") return fromMacro(e.macroCall)

            val viaTypes = RustTypes.struct(RustTypes.typeOf(e))
            if (viaTypes != null) return RustContext(viaTypes.fields, viaTypes.exact)
            // Type inference can fail (missing std, unresolved attributes): fall back to the written path.
            val viaPath = RustTypes.struct(structFromExpression(e))
            if (viaPath != null) return RustContext(viaPath.fields, viaPath.exact)
            return null
        }

        /** `Type { .. }` or `Type::function(..)[.await]`, resolved from the path alone. */
        private fun structFromExpression(expr: RsExpr): StructItemRef? {
            if (expr is RsStructLiteral) {
                val item = (expr.path.reference?.resolve() as? RsStructItem)
                    ?: expr.path.referenceName?.let { RustTypes.structByName(expr, it) }
                return item?.let(::StructItemRef)
            }
            var e: PsiElement = expr
            if (e.text.endsWith(".await")) e = PsiTreeUtil.getChildOfType(e, RsExpr::class.java) ?: return null
            val call = e as? RsCallExpr ?: return null
            val path = (call.expr as? RsPathExpr)?.path ?: return null
            val qualifier = path.path ?: return null
            val item = (qualifier.reference?.resolve() as? RsStructItem)
                ?: qualifier.referenceName?.let { RustTypes.structByName(call, it) } ?: return null
            return StructItemRef(item)
        }

        private fun fromMacro(call: RsMacroCall): RustContext {
            val text = call.text
            val open = text.indexOfFirst { it == '{' || it == '(' || it == '[' }
            if (open < 0 || text.length < open + 2) return RustContext(emptyList(), false)
            val base = call.textRange.startOffset + open + 1
            val inner = text.substring(open + 1, text.length - 1)
            val file = call.containingFile
            val fields = mutableListOf<TeraField>()
            var complete = true
            for ((start, end) in RustText.splitTopLevel(inner)) {
                val entry = inner.substring(start, end)
                val m = Regex("""^\s*([A-Za-z_]\w*)\s*(?::(.*))?$""", RegexOption.DOT_MATCHES_ALL).matchEntire(entry)
                if (m == null) { if (entry.isNotBlank()) complete = false; continue }
                val key = m.groupValues[1]
                val value = (m.groups[2]?.value ?: key).trim()
                val keyOffset = base + start + entry.indexOf(key)
                fields.add(TeraField(key, RustTypes.guess(value, call), null, null, file.findElementAt(keyOffset)))
            }
            return RustContext(fields, complete)
        }
    }
}

internal class StructItemRef(val item: RsStructOrEnumItemElement)

internal object RustText {
    /** Ranges of the top-level comma separated parts of [s], ignoring commas inside brackets or strings. */
    fun splitTopLevel(s: String): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var depth = 0
        var start = 0
        var quote = '\u0000'
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != '\u0000' -> { if (c == '\\') i++ else if (c == quote) quote = '\u0000' }
                c == '"' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> { out.add(start to i); start = i + 1 }
            }
            i++
        }
        if (s.substring(start).isNotBlank()) out.add(start to s.length)
        return out
    }
}

internal object RustTypes {
    private const val MAX_DEPTH = 5

    class StructInfo(val fields: List<TeraField>, val exact: Boolean)

    private val ARRAYS = setOf("Vec", "VecDeque", "HashSet", "BTreeSet", "LinkedList", "BinaryHeap", "IndexSet")
    private val MAPS = setOf("HashMap", "BTreeMap", "IndexMap")
    private val WRAPPERS = setOf("Option", "Box", "Rc", "Arc", "Cow", "RefCell", "Cell", "Mutex", "RwLock")
    private val STRINGS = setOf("String", "OsString", "PathBuf", "Uuid")
    private val NUMBERS = setOf(
        "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64",
    )

    /** Last resort when name resolution fails (e.g. unresolved derive/attribute macros): `struct Name` in the project. */
    fun structByName(context: PsiElement, name: String): RsStructItem? {
        val project = context.project
        val pattern = Regex("""\bstruct\s+${Regex.escape(name)}\b""")
        val localFirst = listOfNotNull(context.containingFile?.virtualFile) +
            FilenameIndex.getAllFilesByExt(project, "rs", GlobalSearchScope.projectScope(project))
        for (vf in localFirst.distinct()) {
            if ("/target/" in vf.path) continue
            val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.text
                ?: String(vf.contentsToByteArray(), vf.charset)
            val m = pattern.find(text) ?: continue
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            val leaf = psi.findElementAt(m.range.last) ?: continue
            PsiTreeUtil.getParentOfType(leaf, RsStructItem::class.java, false)?.takeIf { it.name == name }?.let { return it }
        }
        return structFromMacroExpansion(context, name)
    }

    /**
     * `struct Name` does not appear in the source when a `macro_rules!` call generates it: look through the
     * expansions of macro calls that mention the name. Needs the Rust plugin's macro expansion to be enabled.
     */
    private fun structFromMacroExpansion(context: PsiElement, name: String): RsStructItem? {
        val project = context.project
        val files = listOfNotNull(context.containingFile?.virtualFile) +
            FilenameIndex.getAllFilesByExt(project, "rs", GlobalSearchScope.projectScope(project))
        for (vf in files.distinct()) {
            if ("/target/" in vf.path) continue
            val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.text
                ?: String(vf.contentsToByteArray(), vf.charset)
            if (!Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text)) continue
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            for (call in PsiTreeUtil.findChildrenOfType(psi, RsMacroCall::class.java)) {
                if (!call.text.contains(name)) continue
                val found = try {
                    call.expansionFlatten.filterIsInstance<RsStructItem>().firstOrNull { it.name == name }
                } catch (e: ProcessCanceledException) { throw e } catch (e: Exception) { null }
                if (found != null) return found
            }
        }
        return null
    }

    fun typeOf(e: RsExpr): Ty? = try { e.type } catch (ex: ProcessCanceledException) { throw ex } catch (ex: Exception) { null }

    fun struct(ty: Ty?): StructInfo? = unwrap(ty)?.let { adt ->
        val item = adt.item as? RsStructItem ?: return null
        if (!inProject(item)) return null
        structOf(item, 0, emptySet())
    }

    fun struct(ref: StructItemRef?): StructInfo? =
        (ref?.item as? RsStructItem)?.takeIf { inProject(it) }?.let { structOf(it, 0, emptySet()) }

    private fun unwrap(ty: Ty?): TyAdt? {
        var t = ty
        while (t != null) {
            t = when (t) {
                is TyRef -> t.referenced
                is TyAdt -> if (t.item.name in WRAPPERS && t.typeArguments.isNotEmpty()) t.typeArguments[0] else return t
                else -> return null
            }
        }
        return null
    }

    fun toTera(ty: Ty?, depth: Int, seen: Set<PsiElement>): TeraType = when (ty) {
        null -> TeraType.Unknown
        is TyRef -> toTera(ty.referenced, depth, seen)
        is TyInteger, is TyFloat -> TeraType.Scalar("number")
        is TyBool -> TeraType.Scalar("bool")
        is TyStr, is TyChar -> TeraType.Scalar("string")
        is TyTuple -> if (ty.types.isEmpty()) TeraType.Unknown else TeraType.Tuple(ty.types.map { toTera(it, depth + 1, seen) })
        is TySlice -> TeraType.ArrayOf(toTera(ty.elementType, depth + 1, seen))
        is TyArray -> TeraType.ArrayOf(toTera(ty.base, depth + 1, seen))
        is TyAdt -> adt(ty, depth, seen)
        else -> TeraType.Unknown
    }

    private fun adt(ty: TyAdt, depth: Int, seen: Set<PsiElement>): TeraType {
        val name = ty.item.name
        val args = ty.typeArguments
        return when {
            name in STRINGS -> TeraType.Scalar("string")
            name in WRAPPERS -> toTera(args.firstOrNull(), depth, seen)
            name in ARRAYS -> TeraType.ArrayOf(toTera(args.firstOrNull(), depth + 1, seen))
            name in MAPS -> TeraType.MapOf(toTera(args.getOrNull(1), depth + 1, seen))
            else -> (ty.item as? RsStructItem)?.let { structType(it, depth, seen) } ?: TeraType.Unknown
        }
    }

    /** A struct as the template sees it; library types and cycles are not expanded. */
    private fun structType(item: RsStructItem, depth: Int, seen: Set<PsiElement>): TeraType {
        if (depth >= MAX_DEPTH || item in seen || !inProject(item)) return TeraType.Unknown
        return when {
            item.blockFields != null -> TeraType.Struct(item.name ?: "struct", structOf(item, depth + 1, seen).fields)
            item.tupleFields != null -> {
                val elems = item.tupleFields!!.tupleFieldDeclList.map { fromRef(it.typeReference, depth + 1, seen + item) }
                if (elems.size == 1) elems[0] else TeraType.Tuple(elems)
            }
            else -> TeraType.Unknown
        }
    }

    /** Items produced by a macro belong to the project when the macro call does, not the synthetic expansion file. */
    private fun inProject(item: PsiElement): Boolean {
        val site = originOf(item)
        val vf = site.containingFile?.virtualFile ?: return false
        return com.intellij.openapi.roots.ProjectFileIndex.getInstance(item.project).isInContent(vf)
    }

    /** The macro call an expanded element comes from, else the element itself. */
    private fun originOf(element: PsiElement): PsiElement =
        try { element.findMacroCallExpandedFrom() ?: element } catch (e: ProcessCanceledException) { throw e } catch (e: Exception) { element }

    /** A declaration to navigate to; for macro-generated fields the call site instead of the expansion file. */
    fun navigationTarget(leaf: PsiElement): PsiElement {
        val project = leaf.project
        val index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
        fun inContent(e: PsiElement) = e.containingFile?.virtualFile?.let(index::isInContent) == true
        if (inContent(leaf)) return leaf
        val mapped = try { leaf.navigationElement } catch (e: ProcessCanceledException) { throw e } catch (e: Exception) { leaf }
        if (inContent(mapped)) return mapped
        return originOf(leaf).takeIf(::inContent) ?: leaf
    }

    /**
     * Type of a written type reference: from type inference when the Rust plugin knows it, otherwise from the
     * source text (so a missing standard library or unresolved dependency does not hide `String` or `Vec<T>`).
     */
    fun fromRef(ref: RsTypeReference?, depth: Int, seen: Set<PsiElement>): TeraType {
        ref ?: return TeraType.Unknown
        val inferred = toTera(rawTypeOf(ref), depth, seen)
        if (inferred != TeraType.Unknown && unknowns(inferred) == 0) return inferred
        val written = fromWrittenType(ref, depth, seen)
        return if (unknowns(written) < unknowns(inferred) || inferred == TeraType.Unknown) written else inferred
    }

    private fun unknowns(t: TeraType): Int = when (t) {
        TeraType.Unknown -> 1
        is TeraType.Scalar -> 0
        is TeraType.ArrayOf -> unknowns(t.element)
        is TeraType.MapOf -> unknowns(t.value)
        is TeraType.Tuple -> t.elements.sumOf(::unknowns)
        is TeraType.Struct -> 0
    }

    private fun fromWrittenType(ref: RsTypeReference, depth: Int, seen: Set<PsiElement>): TeraType {
        return when (ref) {
            is RsRefLikeType -> fromRef(ref.typeReference, depth, seen)
            is RsTupleType -> ref.typeReferenceList.takeIf { it.isNotEmpty() }
                ?.let { list -> TeraType.Tuple(list.map { fromRef(it, depth + 1, seen) }) } ?: TeraType.Unknown
            is RsArrayType -> TeraType.ArrayOf(fromRef(ref.typeReference, depth + 1, seen))
            is RsPathType -> pathType(ref, depth, seen)
            else -> TeraType.Unknown
        }
    }

    private fun pathType(ref: RsPathType, depth: Int, seen: Set<PsiElement>): TeraType {
        val path = ref.path
        val name = path.referenceName ?: return TeraType.Unknown
        val args = path.typeArgumentList?.typeReferenceList.orEmpty()
        return when {
            name in NUMBERS -> TeraType.Scalar("number")
            name == "bool" -> TeraType.Scalar("bool")
            name == "str" || name == "char" || name in STRINGS -> TeraType.Scalar("string")
            name in WRAPPERS -> fromRef(args.firstOrNull(), depth, seen)
            name in ARRAYS -> TeraType.ArrayOf(fromRef(args.firstOrNull(), depth + 1, seen))
            name in MAPS -> TeraType.MapOf(fromRef(args.getOrNull(1), depth + 1, seen))
            else -> ((path.reference?.resolve() as? RsStructItem) ?: structByName(ref, name))
                ?.let { structType(it, depth, seen) } ?: TeraType.Unknown
        }
    }

    private fun rawTypeOf(ref: org.rust.lang.core.psi.RsTypeReference): Ty? =
        try { ref.normType } catch (ex: ProcessCanceledException) { throw ex } catch (ex: Exception) { null }

    /** Fields of [item] as serde would serialize them. [StructInfo.exact] is false if something was not understood. */
    fun structOf(item: RsStructItem, depth: Int, seen: Set<PsiElement>): StructInfo {
        val rule = serdeValue(item.outerAttrList.joinToString(" ") { it.text }, "rename_all")
        val next = seen + item
        val out = mutableListOf<TeraField>()
        var exact = true
        for (field in item.blockFields?.namedFieldDeclList.orEmpty()) {
            val attrs = field.outerAttrList.joinToString(" ") { it.text }.takeIf { "serde" in it }.orEmpty()
            if (Regex("""\bskip\b|\bskip_serializing\b""").containsMatchIn(attrs)) continue
            val rustName = field.identifier.text
            val type = fromRef(field.typeReference, depth, next)
            if (Regex("""\bflatten\b""").containsMatchIn(attrs)) {
                val inner = type as? TeraType.Struct
                if (inner != null) out.addAll(inner.fields) else exact = false
                continue
            }
            val renamed = serdeValue(attrs, "rename") ?: serdeValue(attrs, "serialize")
                ?: rule?.let { serdeRename(it, rustName) } ?: rustName
            out.add(TeraField(renamed, type, field.typeReference?.text, docOf(field.text), navigationTarget(field.identifier)))
        }
        return StructInfo(out, exact)
    }

    private fun serdeValue(attrs: String, key: String): String? =
        Regex("""\b$key\s*=\s*"([^"]+)"""").find(attrs)?.groupValues?.get(1)

    private fun docOf(text: String): String? =
        text.lines().map { it.trim() }.filter { it.startsWith("///") }.joinToString("\n") { it.removePrefix("///").trim() }
            .ifBlank { null }

    /** serde's `rename_all` rules applied to a snake_case Rust field name. */
    fun serdeRename(rule: String, name: String): String {
        val words = name.split('_').filter { it.isNotEmpty() }
        fun pascal() = words.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        return when (rule) {
            "lowercase" -> name.lowercase()
            "UPPERCASE" -> name.uppercase()
            "PascalCase" -> pascal()
            "camelCase" -> pascal().replaceFirstChar(Char::lowercaseChar)
            "snake_case" -> name
            "SCREAMING_SNAKE_CASE" -> name.uppercase()
            "kebab-case" -> words.joinToString("-")
            "SCREAMING-KEBAB-CASE" -> words.joinToString("-").uppercase()
            else -> name
        }
    }

    /** Best-effort type of a `context!` value written as source text. */
    fun guess(value: String, anchor: PsiElement): TeraType {
        val v = value.trim().removePrefix("&").removePrefix("mut ").trim()
        return when {
            v.startsWith("\"") || Regex("""^r#*"""").containsMatchIn(v) -> TeraType.Scalar("string")
            Regex("""^-?\d+(_\d+)*(\.\d+)?([iuf]\d+|usize|isize)?$""").matches(v) -> TeraType.Scalar("number")
            v == "true" || v == "false" -> TeraType.Scalar("bool")
            v.startsWith("format!(") || v.endsWith(".to_string()") || v.endsWith(".to_owned()") ||
                v.startsWith("String::from(") || v.startsWith("String::new(") -> TeraType.Scalar("string")
            v.startsWith("vec![") && v.endsWith("]") -> TeraType.ArrayOf(firstElement(v.substring(5, v.length - 1), anchor))
            v.startsWith("[") && v.endsWith("]") -> TeraType.ArrayOf(firstElement(v.substring(1, v.length - 1), anchor))
            v.startsWith("Some(") && v.endsWith(")") -> guess(v.substring(5, v.length - 1), anchor)
            Regex("""^[A-Za-z_]\w*$""").matches(v) -> localVariable(v, anchor)
            else -> TeraType.Unknown
        }
    }

    private fun firstElement(inner: String, anchor: PsiElement): TeraType =
        RustText.splitTopLevel(inner).firstOrNull()?.let { guess(inner.substring(it.first, it.second), anchor) } ?: TeraType.Unknown

    private fun localVariable(name: String, anchor: PsiElement): TeraType {
        val fn = PsiTreeUtil.getParentOfType(anchor, RsFunction::class.java) ?: return TeraType.Unknown
        val binding = PsiTreeUtil.findChildrenOfType(fn, RsPatBinding::class.java).firstOrNull { it.name == name }
            ?: return TeraType.Unknown
        val ty = try { binding.type } catch (ex: ProcessCanceledException) { throw ex } catch (ex: Exception) { null }
        val inferred = toTera(ty, 0, emptySet())
        if (inferred != TeraType.Unknown) return inferred
        PsiTreeUtil.getParentOfType(binding, RsValueParameter::class.java)?.let { return fromRef(it.typeReference, 0, emptySet()) }
        val let = PsiTreeUtil.getParentOfType(binding, RsLetDecl::class.java) ?: return TeraType.Unknown
        let.typeReference?.let { return fromRef(it, 0, emptySet()) }
        return let.expr?.let { guess(it.text, anchor) } ?: TeraType.Unknown
    }
}
