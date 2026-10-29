package com.tera.tera

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * [Zola](https://www.getzola.org/) renders its `templates/` with Tera and adds its own functions and filters
 * (`get_url`, `get_section`, `markdown`, …). A project is a Zola site when a `zola.toml` (or the older
 * `config.toml` next to `content/` and `templates/`) is in it.
 */
object TeraZola {

    fun isZolaProject(project: Project): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        if (FilenameIndex.getVirtualFilesByName("zola.toml", scope).any { it.isValid && !it.isDirectory }) return true
        return FilenameIndex.getVirtualFilesByName("config.toml", scope).any(::isSiteConfig)
    }

    private fun isSiteConfig(f: VirtualFile): Boolean {
        val dir = f.parent ?: return false
        return f.isValid && !f.isDirectory && dir.findChild("content")?.isDirectory == true &&
            dir.findChild("templates")?.isDirectory == true
    }

    private fun req(name: String, doc: String) = BuiltinParam(name, true, null, doc)
    private fun opt(name: String, default: String?, doc: String) = BuiltinParam(name, false, default, doc)

    private fun fn(def: BuiltinDef) = TeraCustomBuiltin("function", def, null)
    private fun filter(def: BuiltinDef) = TeraCustomBuiltin("filter", def, null)

    private const val FROM = "Zola"

    private val lang = opt("lang", null, "language code; defaults to `config.default_language`")
    private val required = opt("required", "true", "throw an error when nothing is found")
    private val allowMissing = opt("allow_missing", "false", "return nothing instead of failing when it does not exist")

    /**
     * What Zola 0.23 (Tera 2) still provides under its Tera 1 name. Plain Tera 2 moved these to tera-contrib, so a
     * Tera 2 project has to register them; Zola does, and the checks must not call them removed.
     * Verified by building a site with each of them.
     */
    private val KEPT = listOf(
        "filter" to "spaceless", "filter" to "striptags", "filter" to "slugify", "filter" to "urlencode",
        "filter" to "urlencode_strict", "filter" to "date", "filter" to "filesizeformat", "filter" to "json_encode",
        "test" to "matching", "function" to "now", "function" to "get_random",
    )

    private fun kept(): List<TeraCustomBuiltin> = KEPT.mapNotNull { (kind, name) ->
        TeraBuiltins.base(kind, TeraVersion.V1).firstOrNull { it.name == name }?.let { d ->
            TeraCustomBuiltin(kind, BuiltinDef(d.name, "$FROM: ${d.description}", d.params, d.example, d.positional, FROM, d.details, d.input), null)
        }
    }

    /** What Zola adds to Tera, as documented for Zola 0.23. */
    val builtins: List<TeraCustomBuiltin> by lazy {
        kept() + listOf(
            fn(BuiltinDef("get_page", "$FROM: the page of a Markdown file", listOf(
                req("path", "path of the `.md` file, from the content directory"), lang, allowMissing,
            ), """{% set page = get_page(path="blog/post.md") %}""", source = FROM)),
            fn(BuiltinDef("get_section", "$FROM: the section of an `_index.md` file", listOf(
                req("path", "path of the `_index.md`, from the content directory"),
                opt("metadata_only", "false", "skip the pages of the section"), lang, allowMissing,
            ), """{% set blog = get_section(path="blog/_index.md") %}""", source = FROM)),
            fn(BuiltinDef("get_taxonomy", "$FROM: a whole taxonomy", listOf(
                req("kind", "name of the taxonomy, e.g. `tags`"), lang, required,
            ), """{% set tags = get_taxonomy(kind="tags") %}""", source = FROM)),
            fn(BuiltinDef("get_taxonomy_term", "$FROM: one term of a taxonomy", listOf(
                req("kind", "name of the taxonomy"), req("term", "the term as written in the front matter"),
                opt("include_pages", "true", "fill the pages of the term"), lang, required,
            ), """{% set term = get_taxonomy_term(kind="tags", term="rust") %}""", source = FROM)),
            fn(BuiltinDef("get_taxonomy_url", "$FROM: the permalink of a taxonomy term", listOf(
                req("kind", "name of the taxonomy"), req("term", "the term as written in the front matter (not slugified)"),
                lang, required,
            ), """{{ get_taxonomy_url(kind="tags", term="rust") }}""", source = FROM)),
            fn(BuiltinDef("get_url", "$FROM: the permalink of a path", listOf(
                req("path", "`@/…` for a content file, otherwise a static file"), lang,
                opt("trailing_slash", "false", "end the URL with a slash"),
                opt("cachebust", "false", "append `?h=<sha256>` of the file"),
            ), """{{ get_url(path="@/blog/_index.md") }}""", source = FROM)),
            fn(BuiltinDef("get_hash", "$FROM: the SHA digest of a file or a string", listOf(
                opt("path", null, "file to hash (give this or `literal`)"),
                opt("literal", null, "string to hash (give this or `path`)"),
                opt("sha_type", "384", "256, 384 or 512"),
                opt("base64", "true", "encode the digest as base64"),
            ), """{{ get_hash(path="js/app.js", sha_type=256) }}""", source = FROM)),
            fn(BuiltinDef("get_image_metadata", "$FROM: width, height, format, … of an image", listOf(
                req("path", "image file"), allowMissing,
            ), """{% set meta = get_image_metadata(path="img/a.png") %}""", source = FROM)),
            fn(BuiltinDef("resize_image", "$FROM: resize an image and return its URL", listOf(
                req("path", "image file"), opt("width", null, "target width"), opt("height", null, "target height"),
                opt("op", "\"fill\"", "`fit_width`, `fit_height`, `scale`, `fit` or `fill`"),
                opt("format", "\"auto\"", "`auto`, `jpg`, `png`, `webp`"), opt("quality", null, "1-100 for jpg and webp"),
                opt("filter", null, "sampling filter"),
            ), """{% set img = resize_image(path="img/a.png", width=600, op="fit_width") %}""", source = FROM)),
            fn(BuiltinDef("load_data", "$FROM: load a file, URL or literal (toml, json, csv, yaml, xml, bibtex)", listOf(
                opt("path", null, "local file"), opt("url", null, "remote URL"), opt("literal", null, "inline text"),
                opt("format", null, "`toml`, `json`, `csv`, `bibtex`, `yaml`, `xml` or `plain`"),
                opt("required", "true", "return nothing instead of failing when it cannot be loaded"),
                opt("method", "\"GET\"", "`GET` or `POST` for a URL"), opt("body", null, "request body"),
                opt("content_type", null, "mime type of the body"),
            ), """{% set data = load_data(path="data/links.toml") %}""", source = FROM)),
            fn(BuiltinDef("trans", "$FROM: a translation", listOf(
                req("key", "key in the language file"), lang,
            ), """{{ trans(key="title", lang=lang) }}""", source = FROM)),

            filter(BuiltinDef("markdown", "$FROM: Markdown to HTML", listOf(
                opt("inline", "false", "do not wrap the text in a paragraph"),
            ), """{{ text | markdown(inline=true) }}""", source = FROM)),
            filter(BuiltinDef("base64_encode", "$FROM: encode as base64", source = FROM)),
            filter(BuiltinDef("base64_decode", "$FROM: decode from base64", source = FROM)),
            filter(BuiltinDef("regex_replace", "$FROM: replace text by a regular expression", listOf(
                req("pattern", "regular expression"), req("rep", "replacement, `\$name` for a group"),
            ), """{{ s | regex_replace(pattern=`\d+`, rep="#") }}""", source = FROM)),
            filter(BuiltinDef("num_format", "$FROM: format a number for a locale", listOf(
                opt("locale", null, "e.g. `en-IN`; defaults to `config.default_language`"),
            ), """{{ 1000000 | num_format }}""", source = FROM)),
        )
    }

    // ── variables ────────────────────────────────────────────────────────────

    /** What a template is rendered for; each gets its own variables. Checked against `{{ __tera_context }}` of Zola 0.23.6. */
    enum class Kind { PAGE, SECTION, TAXONOMY_LIST, TAXONOMY_SINGLE, NOT_FOUND }

    private fun str() = TeraType.Scalar("string")
    private fun num() = TeraType.Scalar("number")
    private fun bool() = TeraType.Scalar("bool")
    private fun strings() = TeraType.ArrayOf(str())
    private fun open() = TeraType.MapOf(TeraType.Unknown)
    private fun f(name: String, type: TeraType, doc: String? = null) = TeraField(name, type, null, doc)
    private fun struct(name: String, vararg fields: TeraField) = TeraType.Struct(name, fields.toList())

    private val heading: TeraType = struct("heading",
        f("level", num()), f("id", str()), f("permalink", str()), f("title", str()), f("children", TeraType.ArrayOf(TeraType.Unknown)))

    private val asset = strings()

    private val pageType: TeraType by lazy {
        struct("page",
            f("title", str()), f("description", str(), "from the front matter; empty when not set"),
            f("content", str(), "rendered HTML"), f("summary", str(), "the part before `<!-- more -->`"),
            f("date", str(), "front matter date, `YYYY-MM-DD`"), f("updated", str()),
            f("year", num()), f("month", num()), f("day", num()),
            f("slug", str()), f("path", str(), "path of the page, with leading and trailing slash"),
            f("permalink", str(), "absolute URL"), f("relative_path", str(), "of the Markdown file, from `content/`"),
            f("colocated_path", str()), f("components", strings(), "the segments of `path`"),
            f("taxonomies", TeraType.MapOf(strings()), "terms by taxonomy name, e.g. `page.taxonomies.tags`"),
            f("extra", open(), "the `[extra]` table of the front matter"),
            f("authors", strings()), f("aliases", strings()),
            f("word_count", num()), f("reading_time", num(), "minutes"),
            f("toc", TeraType.ArrayOf(heading), "headings of the page, nested through `children`"),
            f("assets", asset, "colocated files"), f("draft", bool()), f("hidden", bool()), f("include_in_feeds", bool()),
            f("lang", str()), f("ancestors", strings(), "`_index.md` files of the sections above"),
            f("translations", TeraType.ArrayOf(TeraType.Unknown)), f("backlinks", TeraType.ArrayOf(TeraType.Unknown)),
            f("lower", TeraType.Unknown, "the neighbour before in the section's order, if any"),
            f("higher", TeraType.Unknown, "the neighbour after in the section's order, if any"),
        )
    }

    private val sectionType: TeraType by lazy {
        struct("section",
            f("title", str()), f("description", str()), f("content", str(), "rendered HTML"),
            f("path", str()), f("permalink", str()), f("relative_path", str()), f("colocated_path", str()),
            f("components", strings()), f("extra", open(), "the `[extra]` table of `_index.md`"),
            f("pages", TeraType.ArrayOf(pageType), "pages of the section, in its `sort_by` order"),
            f("subsections", strings(), "`_index.md` paths of the subsections"),
            f("toc", TeraType.ArrayOf(heading)), f("word_count", num()), f("reading_time", num()),
            f("assets", asset), f("draft", bool()), f("hidden", bool()), f("transparent", bool()),
            f("generate_feeds", bool()), f("paginate_by", TeraType.Unknown), f("paginate_reversed", bool()),
            f("lang", str()), f("ancestors", strings()), f("translations", TeraType.ArrayOf(TeraType.Unknown)),
            f("backlinks", TeraType.ArrayOf(TeraType.Unknown)),
            f("lower", TeraType.Unknown), f("higher", TeraType.Unknown),
        )
    }

    private val taxonomyType: TeraType = struct("taxonomy",
        f("name", str()), f("slug", str()), f("paginate_by", TeraType.Unknown), f("paginate_path", TeraType.Unknown),
        f("render", bool()), f("feed", bool()))

    private val termType: TeraType by lazy {
        struct("term",
            f("name", str(), "as written in the front matter"), f("slug", str()), f("path", str()), f("permalink", str()),
            f("pages", TeraType.ArrayOf(pageType)), f("page_count", num()))
    }

    private val paginatorType: TeraType by lazy {
        struct("paginator",
            f("pages", TeraType.ArrayOf(pageType), "pages of the current pager"),
            f("current_index", num(), "1-based"), f("number_pagers", num()), f("total_pages", num()),
            f("paginate_by", num()), f("first", str(), "URL of the first pager"), f("last", str(), "URL of the last pager"),
            f("previous", TeraType.Unknown, "URL of the previous pager, if any"), f("next", TeraType.Unknown, "URL of the next pager, if any"),
            f("base_url", str()), f("pagers", TeraType.ArrayOf(TeraType.Unknown)),
        )
    }

    private val configType: TeraType = struct("config",
        f("base_url", str()), f("title", TeraType.Unknown), f("description", TeraType.Unknown), f("mode", str(), "`build`, `serve` or `check`"),
        f("default_language", str()), f("languages", open()), f("generate_feed", bool()), f("generate_feeds", bool()),
        f("feed_filenames", strings()), f("taxonomies", TeraType.ArrayOf(taxonomyType)), f("author", TeraType.Unknown),
        f("build_search_index", bool()), f("extra", open(), "the `[extra]` table of the site config"),
        f("markdown", open()), f("search", open()), f("generate_sitemap", bool()), f("generate_robots_txt", bool()),
        f("exclude_paginated_pages_in_sitemap", str()),
    )

    private fun globals(withLocation: Boolean) = listOfNotNull(
        f("config", configType, "the site configuration (`zola.toml`)"),
        f("lang", str(), "language of the page being rendered"),
        f("zola_version", str()),
        if (withLocation) f("current_path", str()) else null,
        if (withLocation) f("current_url", str()) else null,
    )

    fun variables(kind: Kind): List<TeraField> = when (kind) {
        Kind.PAGE -> globals(true) + f("page", pageType, "the page being rendered")
        Kind.SECTION -> globals(true) + f("section", sectionType, "the section being rendered") +
            f("paginator", paginatorType, "only when the section sets `paginate_by`")
        Kind.TAXONOMY_LIST -> globals(true) + f("taxonomy", taxonomyType) + f("terms", TeraType.ArrayOf(termType)) +
            f("paginator", paginatorType, "only when the taxonomy sets `paginate_by`")
        Kind.TAXONOMY_SINGLE -> globals(true) + f("taxonomy", taxonomyType) + f("term", termType) +
            f("paginator", paginatorType, "only when the taxonomy sets `paginate_by`")
        Kind.NOT_FOUND -> globals(false)
    }

    // ── which template is rendered for what ──────────────────────────────────

    private fun siteRoot(file: VirtualFile): VirtualFile? {
        var dir = file.parent
        while (dir != null) {
            if (dir.findChild("zola.toml")?.isDirectory == false || dir.findChild("config.toml")?.let(::isSiteConfig) == true) return dir
            dir = dir.parent
        }
        return null
    }

    /** Path of [file] under the `templates/` directory of its site or of a theme in it. */
    private fun templateName(root: VirtualFile, file: VirtualFile): String? {
        val path = file.path.removePrefix(root.path).trimStart('/')
        val parts = path.split('/')
        val at = when {
            parts.firstOrNull() == "templates" -> 0
            parts.firstOrNull() == "themes" && parts.getOrNull(2) == "templates" -> 2
            else -> return null
        }
        return parts.drop(at + 1).joinToString("/").takeIf { it.isNotEmpty() }
    }

    private val TEMPLATE_KEY = Regex("""(?m)^\s*(page_)?template\s*[=:]\s*["']([^"']+)["']""")

    /** Templates that Markdown files name with `template =` (or `page_template =`), by the kind of file naming them. */
    private fun named(project: Project): Map<String, Set<Kind>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val found = mutableMapOf<String, MutableSet<Kind>>()
            for (md in FilenameIndex.getAllFilesByExt(project, "md", GlobalSearchScope.projectScope(project))) {
                if (!md.isValid || md.isDirectory) continue
                val head = TeraFiles.textOf(md).take(4000)
                for (m in TEMPLATE_KEY.findAll(head)) {
                    val kind = if (m.groupValues[1].isNotEmpty() || md.name != "_index.md") Kind.PAGE else Kind.SECTION
                    found.getOrPut(m.groupValues[2]) { mutableSetOf() }.add(kind)
                }
            }
            CachedValueProvider.Result.create(found as Map<String, Set<Kind>>, PsiModificationTracker.MODIFICATION_COUNT)
        }

    /** The kinds [name] (relative to `templates/`) is rendered for, or none when that is not known. */
    fun kinds(project: Project, root: VirtualFile, name: String): Set<Kind> {
        val kinds = mutableSetOf<Kind>()
        when (name) {
            "page.html" -> kinds.add(Kind.PAGE)
            "section.html", "index.html" -> kinds.add(Kind.SECTION)
            "404.html" -> kinds.add(Kind.NOT_FOUND)
            "taxonomy_list.html" -> kinds.add(Kind.TAXONOMY_LIST)
            "taxonomy_single.html" -> kinds.add(Kind.TAXONOMY_SINGLE)
        }
        val dir = name.substringBeforeLast('/', "")
        if (dir.isNotEmpty() && !dir.contains('/') && (name.endsWith("/list.html") || name.endsWith("/single.html"))) {
            val config = (root.findChild("zola.toml") ?: root.findChild("config.toml"))?.let(TeraFiles::textOf).orEmpty()
            if (Regex("""["']${Regex.escape(dir)}["']""").containsMatchIn(config)) {
                kinds.add(if (name.endsWith("/list.html")) Kind.TAXONOMY_LIST else Kind.TAXONOMY_SINGLE)
            }
        }
        named(project)[name]?.let(kinds::addAll)
        return kinds
    }

    /** Variables of the template [template] in a Zola site; null for any other file and for a template of unknown use. */
    fun resolve(project: Project, template: VirtualFile): TeraContextResult? {
        val root = siteRoot(template) ?: return null
        val name = templateName(root, template) ?: return null
        val kinds = kinds(project, root, name).takeIf { it.isNotEmpty() } ?: return null
        val byName = linkedMapOf<String, TeraField>()
        for (k in kinds) for (v in variables(k)) byName.putIfAbsent(v.name, v)
        return TeraContextResult(byName.values.toList(), complete = true, sources = listOf("Zola"))
    }
}

/** Variables Zola gives a template: `page`, `section`, `term`, `config`, … */
class TeraZolaContextProvider : TeraContextProvider {
    override fun resolve(project: Project, template: VirtualFile): TeraContextResult? = TeraZola.resolve(project, template)
}
