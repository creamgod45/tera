package com.tera.tera

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Template variables described in a file instead of in Rust: for data that no Rust type describes (a `json!` value, a
 * `serde_json::Value` read from a file or an API, a static site generator). A JSON Schema subset:
 * `type`, `properties`, `items`, `additionalProperties` (a schema: a map of that), `description`, `title`, and local
 * `$ref` (`#/definitions/x`, `#/$defs/x`). The root is an object whose properties are the template's variables.
 *
 * Which schema belongs to which template: a `tera-context.json` in the template directory or above it,
 *
 *     { "mappings": { "pages/(star).html": "schemas/page.json", "(star)(star)": { "type": "object", "properties": { "site": {"type": "object"} } } } }
 *
 * where each key is a glob (a star, two stars, `?`; written out below) over the template's name relative to that file, with or without the
 * extension, and each value a path to a schema (relative to the mapping file) or an inline schema. Or, next to a template,
 * `detail.html.schema.json` for `detail.html.tera`. Several matches add up; Rust-derived variables win on a conflict.
 * A schema is a closed description only with `"additionalProperties": false` at its root; otherwise the plugin does not
 * report variables it does not know.
 */
object TeraSchemaContext {
    const val MAPPING_FILE = "tera-context.json"

    /** `*` any characters but `/`, `**` anything, `?` one character but `/`. */
    fun globMatches(glob: String, path: String): Boolean {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && glob.startsWith("**", i) -> { sb.append(".*"); i += 2; if (glob.getOrNull(i) == '/') i++; continue }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.toString()).matches(path)
    }

    private fun parse(text: String): JsonObject? = try { JsonParser.parseString(text) as? JsonObject } catch (e: Exception) { null }

    private fun type(schema: JsonObject, root: JsonObject, depth: Int): TeraType {
        if (depth > 12) return TeraType.Unknown
        schema.get("\$ref")?.takeIf { it.isJsonPrimitive }?.asString?.let { ref ->
            if (!ref.startsWith("#/")) return TeraType.Unknown
            var node: JsonElement = root
            for (part in ref.removePrefix("#/").split('/')) node = (node as? JsonObject)?.get(part) ?: return TeraType.Unknown
            return (node as? JsonObject)?.let { type(it, root, depth + 1) } ?: TeraType.Unknown
        }
        val declared = schema.get("type")?.let { if (it.isJsonArray) (it as JsonArray).firstOrNull { e -> e.asString != "null" }?.asString else it.takeIf { e -> e.isJsonPrimitive }?.asString }
        val kind = declared ?: if (schema.has("properties")) "object" else if (schema.has("items")) "array" else null
        return when (kind) {
            "string" -> TeraType.Scalar("string")
            "integer", "number" -> TeraType.Scalar("number")
            "boolean" -> TeraType.Scalar("bool")
            "array" -> TeraType.ArrayOf((schema.get("items") as? JsonObject)?.let { type(it, root, depth + 1) } ?: TeraType.Unknown)
            "object" -> {
                val props = schema.get("properties") as? JsonObject
                val extra = schema.get("additionalProperties") as? JsonObject
                when {
                    props != null -> TeraType.Struct(schema.get("title")?.asString ?: "object", fields(props, root, depth + 1))
                    extra != null -> TeraType.MapOf(type(extra, root, depth + 1))
                    else -> TeraType.MapOf(TeraType.Unknown)
                }
            }
            else -> TeraType.Unknown
        }
    }

    private fun fields(properties: JsonObject, root: JsonObject, depth: Int): List<TeraField> =
        properties.entrySet().map { (name, node) ->
            val schema = node as? JsonObject
            val t = schema?.let { type(it, root, depth) } ?: TeraType.Unknown
            TeraField(name, t, schema?.get("type")?.takeIf { it.isJsonPrimitive }?.asString, schema?.get("description")?.takeIf { it.isJsonPrimitive }?.asString)
        }

    /** The variables a schema document describes, and whether it says that nothing else exists. */
    fun variables(schemaText: String): Pair<List<TeraField>, Boolean>? {
        val root = parse(schemaText) ?: return null
        val properties = root.get("properties") as? JsonObject ?: return emptyList<TeraField>() to false
        val closed = root.get("additionalProperties")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean } == true
        return fields(properties, root, 0) to closed
    }

    private fun variables(schema: JsonObject): Pair<List<TeraField>, Boolean> {
        val properties = schema.get("properties") as? JsonObject ?: return emptyList<TeraField>() to false
        val closed = schema.get("additionalProperties")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean } == true
        return fields(properties, schema, 0) to closed
    }

    /** The result for [template]: what every matching mapping and a sibling schema file describe. */
    fun resolve(project: Project, template: VirtualFile): TeraContextResult? {
        val found = mutableListOf<Pair<List<TeraField>, Boolean>>()
        val sources = mutableListOf<String>()

        // `detail.html.schema.json` next to `detail.html.tera`
        template.parent?.findChild(template.name.removeSuffix(".tera") + ".schema.json")?.let { f ->
            variables(TeraFiles.textOf(f))?.let { found.add(it); sources.add(f.name) }
        }

        var dir = template.parent
        while (dir != null) {
            val mapping = dir.findChild(MAPPING_FILE)
            if (mapping != null) {
                val rel = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(template, dir, '/')
                val names = rel?.let { listOf(it, it.removeSuffix(".tera"), it.substringBeforeLast('/', "").let { d -> (if (d.isEmpty()) "" else "$d/") + it.substringAfterLast('/').substringBefore('.') }) }.orEmpty().distinct()
                val mappings = parse(TeraFiles.textOf(mapping))?.get("mappings") as? JsonObject
                for ((glob, target) in mappings?.entrySet().orEmpty()) {
                    if (names.none { globMatches(glob, it) }) continue
                    val schema: JsonObject? = when {
                        target.isJsonObject -> target.asJsonObject
                        target.isJsonPrimitive -> dir.findFileByRelativePath(target.asString)?.let { parse(TeraFiles.textOf(it)) }
                        else -> null
                    }
                    schema?.let { found.add(variables(it)); sources.add("${mapping.name}: $glob") }
                }
            }
            dir = dir.parent
        }
        if (found.isEmpty()) return null
        val byName = linkedMapOf<String, TeraField>()
        for ((fields, _) in found) for (f in fields) byName.putIfAbsent(f.name, f)
        return TeraContextResult(byName.values.toList(), complete = found.all { it.second }, sources = sources, fallback = true)
    }
}

/** Variables from a context file: the fallback when no Rust code renders the template, and under what Rust says. */
class TeraSchemaContextProvider : TeraContextProvider {
    override fun resolve(project: Project, template: VirtualFile): TeraContextResult? = TeraSchemaContext.resolve(project, template)
}
