package com.tera.tera

import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Template errors of Tera 1, Tera 2 and Rocket, as the real engines print them, become links. */
class TeraConsoleFilterTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TeraSettings.getInstance(project).loadState(TeraSettingsState())
        // 40 lines of 80 characters, so every line:column of the samples exists.
        val body = (0 until 40).joinToString("\n") { "x".repeat(80) }
        for (n in listOf("bad", "ok", "detail")) myFixture.addFileToProject("templates/pages/$n.html.tera", body)
    }

    private fun sample(n: String) = javaClass.getResource("/errors/$n.txt")!!.readText()

    /** Feeds the text line by line, as the console does, and returns the links with the text they sit on. */
    private fun links(text: String): List<Pair<String, Triple<String, Int, Int>>> {
        val filter = TeraConsoleFilter(project)
        val out = mutableListOf<Pair<String, Triple<String, Int, Int>>>()
        var offset = 0
        for (line in text.lineSequence().map { it + "\n" }.filter { it != "\n" || true }.toList().dropLast(if (text.endsWith("\n")) 1 else 0)) {
            offset += line.length
            val result = filter.applyFilter(line, offset) ?: continue
            for (item in result.resultItems) {
                val info = item.hyperlinkInfo as OpenFileHyperlinkInfo
                val d = info.descriptor!!
                out.add(text.substring(item.highlightStartOffset, item.highlightEndOffset) to Triple(d.file.name, d.offset / 81, d.offset % 81))
            }
        }
        return out
    }

    fun testTera2PositionsAreLinks() {
        assertEquals("pages/ok.html:1:7" to Triple("ok.html.tera", 0, 6), links(sample("tera2-render")).single())
        assertEquals("pages/bad.html:3:9" to Triple("bad.html.tera", 2, 8), links(sample("tera2-parse")).single())
    }

    fun testTera1ParseErrorsLinkTheNameAndThePositionThatFollows() {
        val l = links(sample("tera1-parse"))
        assertEquals(listOf("pages/bad.html", "3:10"), l.map { it.first })
        assertEquals(Triple("bad.html.tera", 2, 9), l[1].second)
        assertEquals(0, l[0].second.second)
    }

    fun testTera1RenderErrorsLinkTheTemplateNames() {
        val l = links(sample("tera1-render"))
        assertEquals(listOf("pages/ok.html", "pages/ok.html"), l.map { it.first })
        assertTrue(l.all { it.second.first == "ok.html.tera" && it.second.second == 0 })
    }

    fun testRocketsLinePrefixesDoNotGetInTheWay() {
        val l = links(sample("rocket-tera2"))
        assertEquals(listOf("pages/detail.html", "pages/detail.html:30:49"), l.map { it.first })
        assertEquals(Triple("detail.html.tera", 29, 48), l[1].second)
    }

    fun testNamesOfTemplatesThatAreNotInTheProjectAreLeftAlone() {
        assertEmpty(links("Failed to render 'pages/unknown.html'\n --> pages/unknown.html:3:4\n"))
        assertEmpty(links("error: something --> not a position\nTemplate 'pages/none.html' not found\n"))
    }
}

