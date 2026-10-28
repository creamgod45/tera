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
import com.tera.tera.BuiltinDef
import com.tera.tera.BuiltinParam
import com.tera.tera.TeraBuiltins
import com.tera.tera.TeraContextProvider
import com.tera.tera.TeraFiles
import com.tera.tera.TeraCustomBuiltin
import com.tera.tera.TeraContextResult
import com.tera.tera.TeraField
import com.tera.tera.TeraType
import org.rust.lang.core.psi.RsArrayType
import org.rust.lang.core.psi.RsCallExpr
import org.rust.lang.core.psi.RsExpr
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsImplItem
import org.rust.lang.core.psi.RsLambdaExpr
import org.rust.lang.core.psi.RsLitExpr
import org.rust.lang.core.psi.RsValueArgumentList
import org.rust.lang.core.psi.RsMethodCall
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

    private class Site(val file: VirtualFile, val arg: RsExpr?, val line: Int)

    override fun resolve(project: Project, template: VirtualFile): TeraContextResult? {
        if (DumbService.isDumb(project)) return null
        val sites = renderSites(project, templateNames(template) + com.tera.tera.TeraTemplateRoots.namesOf(project, template))
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

    override fun customBuiltins(project: Project): List<TeraCustomBuiltin> =
        if (DumbService.isDumb(project)) emptyList() else RustCustomBuiltins.find(project)

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

    /**
     * Every string a render call can name the template by: Rocket's `pages/detail` and, for engines that keep the
     * extension (a hand-made `Tera::add_raw_templates`), `pages/detail.html`.
     */
    internal fun templateNames(template: VirtualFile): Set<String> {
        val bare = templateName(template)
        val rel = bare.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + template.name.removeSuffix(".tera")
        return linkedSetOf(bare, rel)
    }

    /**
     * Render sites found by meaning, not by the name of the function: a string literal that is the path of this
     * template and is passed to a call, whose context argument is the one the callee declares as serializable
     * (`impl Serialize`, `T: Serialize`, `tera::Context`, `serde_json::Value`) or, when the callee is not known, the
     * `&value` / `context! {..}` that follows the name.
     */
    private fun renderSites(project: Project, names: Set<String>): List<Site> {
        val pattern = Regex("\"(?:" + names.joinToString("|") { Regex.escape(it) } + ")\"")
        val constDecl = Regex("""\b(?:const|static)\s+([A-Za-z_]\w*)\s*:\s*&(?:\s*'static)?\s*str\s*=\s*"([^"]*)"""")
        val files = TeraFiles.projectFiles(project, "rs").filter { "/target/" !in it.path }.map { it to TeraFiles.textOf(it) }
        // `const PAGE: &str = "pages/x";` ... `render(PAGE, ctx)`: the constant stands for the path.
        val constants = files.flatMap { (_, t) -> constDecl.findAll(t).filter { it.groupValues[2] in names }.map { it.groupValues[1] }.toList() }.toSet()
        val constUse = if (constants.isEmpty()) null else Regex("\\b(?:" + constants.joinToString("|") { Regex.escape(it) } + ")\\b")
        val result = mutableListOf<Site>()
        for ((vf, text) in files) {
            val hits = pattern.findAll(text).map { it.range.first + 1 }.toList() +
                constUse?.findAll(text)?.map { it.range.first }?.toList().orEmpty()
            if (hits.isEmpty()) continue
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            for (at in hits) {
                val leaf = psi.findElementAt(at) ?: continue
                val named = PsiTreeUtil.getParentOfType(leaf, RsLitExpr::class.java, false)
                    ?: PsiTreeUtil.getParentOfType(leaf, RsPathExpr::class.java, false)?.takeIf { it.path.referenceName in constants }
                    ?: continue
                val list = named.parent as? RsValueArgumentList ?: continue
                val args = list.exprList
                val callee = RustCallees.resolve(list.parent)
                val arg = RustCallees.contextArgument(list.parent, callee, args, args.indexOf(named)) ?: continue
                result.add(Site(vf, arg, text.substring(0, at).count { it == '\n' } + 1))
            }
        }
        return result
    }
}

/** Reading a call: which function it reaches and which of its arguments carries the template data. */
internal object RustCallees {
    fun resolve(call: PsiElement?): RsFunction? = try {
        when (call) {
            is RsMethodCall -> call.reference?.resolve() as? RsFunction
            is RsCallExpr -> ((call.expr as? RsPathExpr)?.path?.reference?.resolve()) as? RsFunction
            else -> null
        }
    } catch (e: ProcessCanceledException) { throw e } catch (e: Exception) { null }

    fun contextArgument(call: PsiElement?, callee: RsFunction?, args: List<RsExpr>, nameIndex: Int): RsExpr? {
        if (nameIndex < 0) return null
        if (callee != null) {
            // `Type::render(&self, name, ctx)` called by path passes `self` as the first argument.
            val shift = if (call is RsCallExpr && callee.valueParameterList?.selfParameter != null) 1 else 0
            val params = callee.valueParameterList?.valueParameterList.orEmpty()
            val bounds = (callee.typeParameterList?.typeParameterList.orEmpty().filter { "Serialize" in it.text }.mapNotNull { it.name } +
                Regex("""\b(\w+)\s*:[^,{]*\bSerialize\b""").findAll(callee.whereClause?.text.orEmpty()).map { it.groupValues[1] }).toSet()
            val index = params.indexOfFirst { p ->
                val t = p.typeReference?.text.orEmpty()
                "Serialize" in t || Regex("""\b(Context|Value)\b""").containsMatchIn(t) || bounds.any { Regex("""\b$it\b""").containsMatchIn(t) }
            }
            if (index >= 0) return args.getOrNull(index + shift)
        }
        val next = args.drop(nameIndex + 1).firstOrNull() ?: return null
        // A callee the Rust plugin cannot see (an unresolved library): the value after the name, unless it is a plain literal.
        return next.takeIf { it !is RsLitExpr }
    }
}

/** What one render call provides. [complete] is false when parts of the context could not be understood. */
internal class RustContext(val fields: List<TeraField>, val complete: Boolean) {
    companion object {
        fun of(arg: RsExpr, depth: Int = 0): RustContext? {
            var e: RsExpr = arg
            while (true) {
                e = when {
                    e is RsUnaryExpr && e.and != null -> e.expr ?: break
                    e is RsParenExpr -> e.expr ?: break
                    else -> break
                }
            }
            if (e is RsMacroExpr && e.macroCall.path?.referenceName == "context") return fromMacro(e.macroCall)

            // `let ctx = page(..); render("x", &ctx)`: read what the variable was built from.
            if (depth < 3) localInitializer(e)?.let { init -> of(init, depth + 1)?.let { return it } }

            val viaTypes = RustTypes.struct(RustTypes.typeOf(e))
            if (viaTypes != null) return RustContext(viaTypes.fields, viaTypes.exact)
            // Type inference can fail (missing std, unresolved attributes): fall back to the written path.
            val viaPath = RustTypes.struct(structFromExpression(e))
            if (viaPath != null) return RustContext(viaPath.fields, viaPath.exact)
            // `page(title, data)` returning a generic shell such as `Page<T>`, when the call itself is not inferred.
            val viaCall = (e as? RsCallExpr)?.let { RustTypes.viaFunction(it) }
            if (viaCall != null) return RustContext(viaCall.fields, viaCall.exact)
            return null
        }

        /** The initializer of the `let` that declares the local variable [e] names, if it is a plain variable. */
        private fun localInitializer(e: RsExpr): RsExpr? {
            val name = ((e as? RsPathExpr)?.path)?.takeIf { it.path == null }?.referenceName ?: return null
            val fn = PsiTreeUtil.getParentOfType(e, RsFunction::class.java) ?: return null
            return PsiTreeUtil.findChildrenOfType(fn, RsLetDecl::class.java)
                .filter { it.textRange.endOffset <= e.textRange.startOffset }
                .lastOrNull { decl -> PsiTreeUtil.findChildrenOfType(decl.pat, RsPatBinding::class.java).any { it.name == name } }
                ?.expr
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
            TeraFiles.projectFiles(project, "rs")
        for (vf in localFirst.distinct()) {
            if ("/target/" in vf.path) continue
            val text = TeraFiles.textOf(vf)
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
            TeraFiles.projectFiles(project, "rs")
        for (vf in files.distinct()) {
            if ("/target/" in vf.path) continue
            val text = TeraFiles.textOf(vf)
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
        if (isFormContext(adt)) return StructInfo(FORM_CONTEXT, true)
        val item = adt.item as? RsStructItem ?: return null
        if (!inProject(item)) return null
        structOf(item, 0, emptySet(), substitutionsOf(adt))
    }

    /**
     * What a call to a project function returning a struct produces, from the function's written signature: the
     * generic parameters of the struct (`Page<'a, T>`) are bound to the types of the arguments passed for them.
     */
    fun viaFunction(call: RsCallExpr): StructInfo? {
        val path = (call.expr as? RsPathExpr)?.path ?: return null
        val name = path.referenceName ?: return null
        val fn = (path.reference?.resolve() as? RsFunction) ?: functionByName(call, name) ?: return null
        val ret = fn.retType?.typeReference as? RsPathType ?: return null
        val retName = ret.path.referenceName ?: return null
        val item = ((ret.path.reference?.resolve() as? RsStructItem) ?: structByName(ret, retName))?.takeIf { inProject(it) } ?: return null
        val generics = fn.typeParameterList?.typeParameterList.orEmpty().mapNotNull { it.name }
        val args = call.valueArgumentList.exprList
        val bound = mutableMapOf<String, TeraType>()
        fn.valueParameterList?.valueParameterList.orEmpty().forEachIndexed { i, p ->
            val written = p.typeReference?.text?.trim()
            if (written in generics) args.getOrNull(i)?.let { bound[written!!] = typeOfArgument(it) }
        }
        val typeArgs = ret.path.typeArgumentList?.typeReferenceList.orEmpty()
        val params = item.typeParameterList?.typeParameterList.orEmpty().mapNotNull { it.name }
        val subst = params.zip(typeArgs).associate { (param, ref) -> param to (bound[ref.text.trim()] ?: fromRef(ref, 0, emptySet())) }
        return structOf(item, 0, emptySet(), subst)
    }

    private fun typeOfArgument(arg: RsExpr): TeraType {
        var e: RsExpr = arg
        while (true) e = when { e is RsUnaryExpr && e.and != null -> e.expr ?: break; e is RsParenExpr -> e.expr ?: break; else -> break }
        if (e is RsStructLiteral) {
            val item = (e.path.reference?.resolve() as? RsStructItem) ?: e.path.referenceName?.let { structByName(e, it) }
            return item?.let { structType(it, 0, emptySet()) } ?: TeraType.Unknown
        }
        val inferred = toTera(typeOf(e), 0, emptySet())
        return if (inferred != TeraType.Unknown) inferred else guess(e.text, e)
    }

    private fun functionByName(context: PsiElement, name: String): RsFunction? {
        val pattern = Regex("""\bfn\s+${Regex.escape(name)}\b""")
        for (vf in (listOfNotNull(context.containingFile?.virtualFile) + TeraFiles.projectFiles(context.project, "rs")).distinct()) {
            if ("/target/" in vf.path) continue
            val m = pattern.find(TeraFiles.textOf(vf)) ?: continue
            val psi = PsiManager.getInstance(context.project).findFile(vf) ?: continue
            val leaf = psi.findElementAt(m.range.last) ?: continue
            PsiTreeUtil.getParentOfType(leaf, RsFunction::class.java, false)?.takeIf { it.name == name }?.let { return it }
        }
        return null
    }

    /** `struct Page<'a, T>` seen as `Page<'_, DetailData>`: the type parameter names mapped to what they stand for. */
    private fun substitutionsOf(ty: TyAdt): Map<String, TeraType> {
        val names = (ty.item as? RsStructItem)?.typeParameterList?.typeParameterList.orEmpty().mapNotNull { it.name }
        return names.zip(ty.typeArguments.map { toTera(it, 1, emptySet()) }).toMap()
    }

    /** Rocket's `rocket::form::Context`, which serializes as a map and is what a form template reads. */
    private fun isFormContext(ty: TyAdt): Boolean =
        ty.item.name == "Context" && ty.item.containingFile?.virtualFile?.path?.let { "/form/" in it || "rocket" in it } == true &&
            !inProject(ty.item)

    private fun formContext(): TeraType = TeraType.Struct("Context", FORM_CONTEXT)

    /** The serialized fields of `rocket::form::Context` (its `status` is skipped by serde). */
    private val FORM_CONTEXT: List<TeraField> = listOf(
        TeraField("errors", TeraType.MapOf(TeraType.ArrayOf(TeraType.Unknown)), "IndexMap<NameBuf, Errors>",
            "Errors of each field, by field name; every error has `msg`, `name`, `value`."),
        TeraField("values", TeraType.MapOf(TeraType.ArrayOf(TeraType.Scalar("string"))), "IndexMap<&Name, Vec<&str>>",
            "What was submitted for each field, by field name."),
        TeraField("data_fields", TeraType.ArrayOf(TeraType.Scalar("string")), "IndexSet<&Name>", "Names of the submitted data fields."),
        TeraField("form_errors", TeraType.ArrayOf(TeraType.Unknown), "Errors", "Errors that belong to the form, not a field."),
    )

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
            isFormContext(ty) -> formContext()
            else -> (ty.item as? RsStructItem)?.let { structType(it, depth, seen, substitutionsOf(ty)) } ?: TeraType.Unknown
        }
    }

    /** A struct as the template sees it; library types and cycles are not expanded. */
    private fun structType(item: RsStructItem, depth: Int, seen: Set<PsiElement>, subst: Map<String, TeraType> = emptyMap()): TeraType {
        if (depth >= MAX_DEPTH || item in seen || !inProject(item)) return TeraType.Unknown
        return when {
            item.blockFields != null -> TeraType.Struct(item.name ?: "struct", structOf(item, depth + 1, seen, subst).fields)
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
                ?.let { structType(it, depth, seen) } ?: if (name == "Context" && ref.text.startsWith("&").not()) formContext() else TeraType.Unknown
        }
    }

    private fun rawTypeOf(ref: org.rust.lang.core.psi.RsTypeReference): Ty? =
        try { ref.normType } catch (ex: ProcessCanceledException) { throw ex } catch (ex: Exception) { null }

    /** Fields of [item] as serde would serialize them. [StructInfo.exact] is false if something was not understood. */
    fun structOf(item: RsStructItem, depth: Int, seen: Set<PsiElement>, subst: Map<String, TeraType> = emptyMap()): StructInfo {
        val rule = serdeValue(item.outerAttrList.joinToString(" ") { it.text }, "rename_all")
        val next = seen + item
        val out = mutableListOf<TeraField>()
        var exact = true
        for (field in item.blockFields?.namedFieldDeclList.orEmpty()) {
            val attrs = field.outerAttrList.joinToString(" ") { it.text }.takeIf { "serde" in it }.orEmpty()
            if (Regex("""\bskip\b|\bskip_serializing\b""").containsMatchIn(attrs)) continue
            val rustName = field.identifier.text
            val written = field.typeReference?.text?.trim()
            val type = subst[written] ?: fromRef(field.typeReference, depth, next)
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

    internal fun docOf(text: String): String? =
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


/**
 * Functions, filters and tests the project registers with Tera, found from the calls
 * `tera.register_function("name", f)`, `register_filter` and `register_tester`. This is how they appear in
 * Rocket (`Template::custom(|engines| engines.tera.register_function(..))`) and in plain Tera setups. The
 * second argument may be a function, a closure, a factory call returning a closure (`make_url_for(urls)`) or a
 * value whose type implements `Function`/`Filter`/`Test`; keyword arguments are read from `args.get("x")`.
 */
internal object RustCustomBuiltins {
    // Tera 1 registers tests with `register_tester` (positional arguments), Tera 2 with `register_test` (keyword arguments).
    private val CALL = Regex("""\.\s*register_(function|filter|tester|test)\s*\(\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))\s*,""")
    private val KINDS = mapOf("function" to "function", "filter" to "filter", "tester" to "test", "test" to "test")

    fun find(project: Project): List<TeraCustomBuiltin> {
        val found = linkedMapOf<Pair<String, String>, TeraCustomBuiltin>()
        val files = TeraFiles.projectFiles(project, "rs")
        val sources = files.filter { "/target/" !in it.path }.map { it to textOf(it) }
        val consts = Regex("""\bconst\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*&(?:\s*'static)?\s*str\s*=\s*"([^"]*)"""")
            .let { re -> sources.flatMap { (_, t) -> re.findAll(t).map { it.groupValues[1] to it.groupValues[2] }.toList() }.toMap() }
        for ((vf, text) in sources) {
            val matches = CALL.findAll(text).toList()
            if (matches.isEmpty()) continue
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            for (m in matches) {
                val kind = KINDS.getValue(m.groupValues[1])
                val name = m.groupValues[2].ifEmpty { consts[m.groupValues[3]] } ?: continue
                val at = m.range.first + m.value.indexOf("register_")
                val leaf = psi.findElementAt(at) ?: continue
                val call = PsiTreeUtil.getParentOfType(leaf, RsMethodCall::class.java, false) ?: continue
                val args = call.valueArgumentList.exprList
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                val keywordTest = m.groupValues[1] == "test"              // Tera 2
                val positionalTest = kind == "test" && !keywordTest         // Tera 1
                val callee = calleeOf(args.getOrNull(1), kind, args.getOrNull(0), positionalTest)
                val source = "${vf.name}:$line"
                val contrib = if (callee?.target == null) contribOf(args.getOrNull(1), kind, name, text, source) else null
                val doc = callee?.doc
                val description = doc?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "registered in Rust ($source)"
                val params = if (positionalTest) emptyList() else (callee?.args.orEmpty()).map {
                    BuiltinParam(it.first, it.second, null, if (it.second) "required argument" else "argument read by the Rust code")
                }
                val def = contrib ?: BuiltinDef(name, description, params, positional = positionalTest, source = source, details = doc)
                found[kind to name] = TeraCustomBuiltin(kind, def, callee?.target ?: args.getOrNull(0), callee?.returns)
            }
        }
        return found.values.toList()
    }

    private fun textOf(vf: com.intellij.openapi.vfs.VirtualFile) =
        TeraFiles.textOf(vf)

    private class Callee(val doc: String?, val target: PsiElement?, val args: List<Pair<String, Boolean>>, val returns: TeraType? = null)

    private fun calleeOf(expr0: RsExpr?, kind: String, nameLiteral: PsiElement?, positionalTest: Boolean): Callee? {
        var expr: PsiElement = expr0 ?: return null
        // Box::new(f)
        if (expr is RsCallExpr && (expr.expr as? RsPathExpr)?.path?.text?.endsWith("Box::new") == true) {
            expr = expr.valueArgumentList.exprList.firstOrNull() ?: return null
        }
        return try {
            when (expr) {
                is RsLambdaExpr -> Callee(null, nameLiteral, if (positionalTest) emptyList() else argSpecs(expr.text, kind), RustReturns.of(expr, null))
                is RsPathExpr -> {
                    val target = (expr.path.reference?.resolve() as? RsFunction)
                        ?: expr.path.referenceName?.let { functionByName(expr, it) }
                    if (target != null) fromFunction(target, kind, positionalTest) else implOf(expr, expr.path.referenceName, kind, positionalTest)
                }
                is RsCallExpr -> {
                    val path = (expr.expr as? RsPathExpr)?.path
                    val target = (path?.reference?.resolve() as? RsFunction)
                        ?: path?.referenceName?.let { functionByName(expr, it) }
                    if (target != null && path?.path == null) fromFunction(target, kind, positionalTest)
                    else implOf(expr, path?.path?.referenceName ?: path?.referenceName, kind, positionalTest)
                        ?: target?.let { fromFunction(it, kind, positionalTest) }
                }
                is RsStructLiteral -> implOf(expr, expr.path.referenceName, kind, positionalTest)
                else -> null
            }
        } catch (e: ProcessCanceledException) { throw e } catch (e: Exception) { null }
    }

    private fun fromFunction(fn: RsFunction, kind: String, positionalTest: Boolean) =
        Callee(RustTypes.docOf(fn.text), fn.identifier, if (positionalTest) emptyList() else argSpecs(fn.text, kind), RustReturns.of(fn, RustTypes.docOf(fn.text)))

    private fun functionByName(context: PsiElement, name: String): RsFunction? {
        val project = context.project
        val pattern = Regex("""\bfn\s+${Regex.escape(name)}\b""")
        for (vf in TeraFiles.projectFiles(project, "rs")) {
            if ("/target/" in vf.path) continue
            val m = pattern.find(textOf(vf)) ?: continue
            val leaf = PsiManager.getInstance(project).findFile(vf)?.findElementAt(m.range.last) ?: continue
            PsiTreeUtil.getParentOfType(leaf, RsFunction::class.java, false)?.takeIf { it.name == name }?.let { return it }
        }
        return null
    }

    /** `impl Function for Type { fn call(&self, args: ..) }` (or Filter/Test) for the type used as the second argument. */
    private fun implOf(context: PsiElement, typeName: String?, kind: String, positionalTest: Boolean): Callee? {
        typeName ?: return null
        val trait = when (kind) { "function" -> "Function"; "filter" -> "Filter"; else -> "Test" }
        val pattern = Regex("""\bimpl\s+(?:[A-Za-z_:]*::)?$trait\s+for\s+${Regex.escape(typeName)}\b""")
        val project = context.project
        for (vf in TeraFiles.projectFiles(project, "rs")) {
            if ("/target/" in vf.path) continue
            val m = pattern.find(textOf(vf)) ?: continue
            val leaf = PsiManager.getInstance(project).findFile(vf)?.findElementAt(m.range.first) ?: continue
            val impl = PsiTreeUtil.getParentOfType(leaf, RsImplItem::class.java, false) ?: continue
            return Callee(RustTypes.docOf(impl.text), impl, if (positionalTest) emptyList() else argSpecs(impl.text, kind), RustReturns.of(impl, RustTypes.docOf(impl.text)))
        }
        return null
    }

    /**
     * Keyword arguments the code reads, in order of appearance, and whether it insists on them:
     * Tera 1 `args.get("x")`, `args["x"]`, `try_get_value!("f", "x", ..)`; Tera 2 `kwargs.get::<T>("x")` (optional) and
     * `kwargs.must_get::<T>("x")` (required).
     */
    fun argSpecs(code: String, kind: String): List<Pair<String, Boolean>> {
        if (kind == "test") return argSpecsOfTest(code)
        return collect(code, kind)
    }

    /** Tera 2 tests read keyword arguments like everything else; Tera 1 tests have none (see the caller). */
    private fun argSpecsOfTest(code: String) = collect(code, "test")

    private fun collect(code: String, kind: String): List<Pair<String, Boolean>> {
        val typed = Regex("""(\w+)\s*:\s*&\s*(?:std::collections::)?HashMap\s*<\s*String\s*,\s*(?:[\w:]*::)?Value\s*>""")
            .findAll(code).map { it.groupValues[1] }.toList()
        val tera2 = Regex("""(\w+)\s*:\s*(?:[\w:]*::)?Kwargs\b""").findAll(code).map { it.groupValues[1] }.toList()
        val receivers = (typed + tera2 + listOf("args", "kwargs")).distinct()
        val found = mutableListOf<Triple<Int, String, Boolean>>()      // position, name, required
        for (r in receivers) {
            val q = Regex.escape(r)
            Regex("""\b$q\s*\.\s*(get|get_mut|remove|contains_key|must_get)\s*(?:::\s*<[^>]*>)?\s*\(\s*"(\w+)"""").findAll(code)
                .forEach { found.add(Triple(it.range.first, it.groupValues[2], it.groupValues[1] == "must_get")) }
            Regex("""\b$q\s*\[\s*"(\w+)"\s*\]""").findAll(code).forEach { found.add(Triple(it.range.first, it.groupValues[1], false)) }
        }
        Regex("""try_get_value!\s*\(\s*"[^"]*"\s*,\s*"(\w+)"""").findAll(code).forEach { found.add(Triple(it.range.first, it.groupValues[1], false)) }
        val result = linkedMapOf<String, Boolean>()
        for ((_, name, required) in found.sortedBy { it.first }) result[name] = (result[name] ?: false) || required
        if (kind == "filter") result.remove("value")
        return result.map { it.key to it.value }
    }

    /** Names only, for callers that do not care whether an argument is required. */
    fun argNames(code: String, kind: String): List<String> = argSpecs(code, kind).map { it.first }

    // ── tera-contrib ─────────────────────────────────────────────────────────

    /**
     * `tera.register_filter("date", tera_contrib::dates::date)`: the callable is in the `tera-contrib` crate, whose
     * items and arguments are known, so they are described exactly. The registered name is the one the template uses.
     */
    private fun contribOf(callee: RsExpr?, kind: String, name: String, fileText: String, source: String): BuiltinDef? {
        callee ?: return null
        if (!fileText.contains("tera_contrib")) return null
        val segments = Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(
            callee.text.substringBefore('(').takeIf { callee.text.contains("::") } ?: callee.text,
        ).map { it.value }.toList().reversed()
        // `tera_contrib::dates::date`, or `Matching::default()` whose last segment is a constructor.
        val entry = segments.firstNotNullOfOrNull { seg -> TeraBuiltins.CONTRIB[seg]?.takeIf { it.first == kind } } ?: return null
        val d = entry.second
        return BuiltinDef(name, d.description, d.params, d.example, d.positional, source,
            "From tera-contrib (${d.name}). Registered in Rust ($source).")
    }
}


/**
 * What a registered filter or function produces. Tera 1 filters return `Result<Value>`, so the Rust signature says
 * nothing; the type comes from, in order: a `/// @returns array<string>` doc line (the project's own statement), then the
 * values the body builds: `to_value(x)` with the type of `x`, `Value::Array(..)`, `Value::Object(..)`, `json!([..])`,
 * `json!({..})`. When the returns disagree, or none can be read, the result is unknown and nothing is guessed.
 */
internal object RustReturns {
    fun of(root: PsiElement, doc: String?): TeraType? {
        com.tera.tera.TeraTypeSyntax.fromDoc(doc)?.let { return it }
        val found = mutableListOf<TeraType>()
        for (call in PsiTreeUtil.findChildrenOfType(root, RsCallExpr::class.java)) {
            val path = (call.expr as? RsPathExpr)?.path?.text ?: continue
            val last = path.substringAfterLast("::")
            val qualifier = path.substringBeforeLast("::", "")
            when {
                last == "to_value" -> call.valueArgumentList.exprList.firstOrNull()?.let { found.add(typeOf(it)) }
                qualifier.endsWith("Value") -> when (last) {
                    "Array" -> found.add(TeraType.ArrayOf(TeraType.Unknown))
                    "Object" -> found.add(TeraType.MapOf(TeraType.Unknown))
                    "String" -> found.add(TeraType.Scalar("string"))
                    "Number" -> found.add(TeraType.Scalar("number"))
                    "Bool" -> found.add(TeraType.Scalar("bool"))
                }
            }
        }
        for (m in PsiTreeUtil.findChildrenOfType(root, RsMacroExpr::class.java)) {
            if (m.macroCall.path?.referenceName != "json") continue
            val body = m.macroCall.text.let { it.substring(it.indexOfFirst { c -> c == '(' || c == '{' || c == '[' } + 1) }.trimStart()
            when {
                body.startsWith("[") -> found.add(TeraType.ArrayOf(TeraType.Unknown))
                body.startsWith("{") -> found.add(TeraType.MapOf(TeraType.Unknown))
                body.startsWith("\"") -> found.add(TeraType.Scalar("string"))
            }
        }
        val known = found.filter { it != TeraType.Unknown }
        if (known.isEmpty() || known.map { kind(it) }.distinct().size != 1) return null
        return known.minByOrNull { informationLost(it) }
    }

    private fun typeOf(e: RsExpr): TeraType {
        val inferred = RustTypes.toTera(RustTypes.typeOf(e), 0, emptySet())
        return if (inferred != TeraType.Unknown) inferred else RustTypes.guess(e.text, e)
    }

    private fun kind(t: TeraType): String = when (t) {
        is TeraType.Scalar -> t.name
        is TeraType.ArrayOf, is TeraType.Tuple -> "array"
        is TeraType.MapOf, is TeraType.Struct -> "map"
        TeraType.Unknown -> "any"
    }

    private fun informationLost(t: TeraType): Int = when (t) {
        TeraType.Unknown -> 1
        is TeraType.ArrayOf -> informationLost(t.element)
        is TeraType.MapOf -> informationLost(t.value)
        else -> 0
    }
}
