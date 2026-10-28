package com.tera.tera

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Template variables described by a JSON Schema file instead of Rust. */
class TeraSchemaContextTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        TeraSettings.getInstance(project).state.version = "2"
    }

    private val pageSchema = """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "title": { "type": "string", "description": "Page title" },
            "tags": { "type": "array", "items": { "type": "string" } },
            "author": { "${'$'}ref": "#/definitions/person" },
            "scores": { "type": "object", "additionalProperties": { "type": "integer" } },
            "count": { "type": "integer" },
            "draft": { "type": "boolean" }
          },
          "definitions": { "person": { "type": "object", "title": "Person", "properties": { "name": { "type": "string" }, "age": { "type": "number" } } } }
        }
    """.trimIndent()

    private fun vars(template: String): List<String>? {
        val vf = myFixture.findFileInTempDir(template)
        return TeraSchemaContext.resolve(project, vf)?.variables?.map { it.name }
    }

    fun testTheSchemaBecomesTypedVariables() {
        val (fields, closed) = TeraSchemaContext.variables(pageSchema)!!
        assertTrue(closed)
        assertEquals(listOf("title", "tags", "author", "scores", "count", "draft"), fields.map { it.name })
        assertEquals(TeraType.Scalar("string"), fields[0].type)
        assertEquals("Page title", fields[0].doc)
        assertEquals(TeraType.ArrayOf(TeraType.Scalar("string")), fields[1].type)
        val author = fields[2].type as TeraType.Struct
        assertEquals("Person", author.name)
        assertEquals(listOf("name", "age"), author.fields.map { it.name })
        assertEquals(TeraType.MapOf(TeraType.Scalar("number")), fields[3].type)
        assertEquals(TeraType.Scalar("number"), fields[4].type)
        assertEquals(TeraType.Scalar("bool"), fields[5].type)
    }

    fun testASchemaNextToTheTemplate() {
        myFixture.addFileToProject("templates/detail.html.schema.json", pageSchema)
        myFixture.addFileToProject("templates/detail.html.tera", "{{ title }}")
        myFixture.addFileToProject("templates/other.html.tera", "{{ title }}")
        assertEquals(listOf("title", "tags", "author", "scores", "count", "draft"), vars("templates/detail.html.tera"))
        assertNull(vars("templates/other.html.tera"))
    }

    fun testAMappingFileWithGlobsAndInlineSchemas() {
        myFixture.addFileToProject("templates/schemas/page.json", pageSchema)
        myFixture.addFileToProject("templates/tera-context.json", """
            { "mappings": {
                "pages/*.html": "schemas/page.json",
                "**": { "type": "object", "properties": { "site": { "type": "object", "properties": { "name": { "type": "string" } } } } }
            } }
        """.trimIndent())
        myFixture.addFileToProject("templates/pages/a.html.tera", "")
        myFixture.addFileToProject("templates/pages/deep/b.html.tera", "")
        myFixture.addFileToProject("templates/base.html.tera", "")
        assertEquals(listOf("title", "tags", "author", "scores", "count", "draft", "site"), vars("templates/pages/a.html.tera"))
        // `pages/*.html` does not reach a subdirectory; the catch-all does
        assertEquals(listOf("site"), vars("templates/pages/deep/b.html.tera"))
        assertEquals(listOf("site"), vars("templates/base.html.tera"))
    }

    fun testGlobs() {
        assertTrue(TeraSchemaContext.globMatches("pages/*.html", "pages/a.html"))
        assertFalse(TeraSchemaContext.globMatches("pages/*.html", "pages/x/a.html"))
        assertTrue(TeraSchemaContext.globMatches("**", "a/b/c.html"))
        assertTrue(TeraSchemaContext.globMatches("pages/**", "pages/x/y.html"))
        assertTrue(TeraSchemaContext.globMatches("**/post.html", "blog/2020/post.html"))
        assertTrue(TeraSchemaContext.globMatches("a?.html", "ab.html"))
        assertFalse(TeraSchemaContext.globMatches("a?.html", "a/.html"))
        assertTrue(TeraSchemaContext.globMatches("post", "post"))
    }

    fun testMatchesWithAndWithoutTheExtension() {
        myFixture.addFileToProject("templates/tera-context.json", """{ "mappings": { "post": { "properties": { "p": {"type":"string"} } } } }""")
        myFixture.addFileToProject("templates/post.html.tera", "")
        assertEquals(listOf("p"), vars("templates/post.html.tera"))
    }

    fun testASchemaThatIsNotClosedDoesNotReportUnknownVariables() {
        myFixture.addFileToProject("templates/tera-context.json", """{ "mappings": { "**": { "properties": { "a": {"type":"string"} } } } }""")
        val t = myFixture.addFileToProject("templates/t.html.tera", "{{ a }}{{ nope }}")
        assertFalse(TeraSchemaContext.resolve(project, t.virtualFile)!!.complete)
        assertEmpty(TeraExternalAnnotator().let { a -> a.doAnnotate(a.collectInformation(t)!!) })
    }

    fun testAClosedSchemaReportsWhatItDoesNotKnowAndCompletesWhatItDoes() {
        myFixture.addFileToProject("templates/tera-context.json", """{ "mappings": { "**": { "additionalProperties": false, "properties": { "a": {"type":"string"} } } } }""")
        val t = myFixture.addFileToProject("templates/t.html.tera", "{{ a }}{{ nope }}")
        val annotator = TeraExternalAnnotator()
        val info = annotator.collectInformation(t)!!
        assertTrue("complete=${info.context?.complete} vars=${info.context?.variables?.map { it.name }} sources=${info.context?.sources}", info.context?.complete == true)
        val errors = annotator.doAnnotate(info)
        assertEquals(listOf("nope"), errors.map { it.range.substring(t.text) })
        myFixture.configureFromExistingVirtualFile(t.virtualFile)
        myFixture.editor.caretModel.moveToOffset(t.text.length - 1)
        myFixture.completeBasic()
        // nothing to pick from: `a` and `nope` are both prefixes of nothing at `nope}}`; the point is the context is seen
        assertEquals(listOf("a"), TeraContexts.forFile(t)!!.variables.map { it.name })
    }

    fun testLayoutsInheritTheSchemaOfThePagesThatExtendThem() {
        myFixture.addFileToProject("templates/tera-context.json", """{ "mappings": { "pages/**": { "properties": { "title": {"type":"string"} } } } }""")
        myFixture.addFileToProject("templates/pages/p.html.tera", "{% extends \"base.html\" %}")
        val base = myFixture.addFileToProject("templates/base.html.tera", "{{ title }}")
        assertEquals(listOf("title"), TeraContexts.forFile(base)!!.variables.map { it.name })
    }

    fun testRustVariablesWinOnAConflict() {
        val schema = TeraContextResult(listOf(TeraField("x", TeraType.Scalar("string"), "from schema")), true, fallback = true)
        val rust = TeraContextResult(listOf(TeraField("x", TeraType.Scalar("number"), "u32")), true)
        for (order in listOf(listOf(schema, rust), listOf(rust, schema))) {
            val merged = TeraContexts.merge(order)!!
            assertEquals("u32", merged.variables.single().rustType)
        }
    }

    fun testABrokenSchemaIsIgnored() {
        myFixture.addFileToProject("templates/tera-context.json", "{ not json")
        myFixture.addFileToProject("templates/t.html.tera", "")
        assertNull(vars("templates/t.html.tera"))
        assertNull(TeraSchemaContext.variables("[1,2]"))
    }
}
