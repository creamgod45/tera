package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange

/** Checks of Tera 2 components: how they are called and what their bodies may read. */
object TeraComponentChecker {

    private fun msg(key: String, vararg p: Any) = TeraBundle.message(key, *p)

    fun check(text: String, components: List<TeraComponent>): List<TeraError> = callErrors(text, components) + bodyErrors(text)

    /** How components are called: unknown component, unknown, duplicate or missing attributes. */
    fun callErrors(text: String, components: List<TeraComponent>): List<TeraError> {
        val errors = mutableListOf<TeraError>()
        callChecks(TeraSymbolScanner.scanBlocks(text), components, errors)
        return errors
    }

    /** What component bodies read without defining it. */
    fun bodyErrors(text: String): List<TeraError> {
        val errors = mutableListOf<TeraError>()
        bodyChecks(text, TeraSymbolScanner.scanBlocks(text), errors)
        return errors
    }

    // ── calls ────────────────────────────────────────────────────────────────

    private fun callChecks(blocks: List<TeraBlock>, components: List<TeraComponent>, out: MutableList<TeraError>) {
        val byName = components.groupBy { it.def.name }
        for (call in TeraComponentScanner.calls(blocks)) {
            val def = byName[call.name]?.first()?.def
            if (def == null) {
                out.add(TeraError(msg("component.unknown", call.name), call.nameRange, HighlightSeverity.ERROR))
                continue
            }
            val given = mutableSetOf<String>()
            val known = def.params.filter { !it.rest }.map { it.name }.toSet()
            for (attr in call.attrs) {
                val name = attr.name ?: continue
                if (!given.add(name)) {
                    out.add(TeraError(msg("component.attr.duplicate", name), attr.range, HighlightSeverity.ERROR))
                } else if (name !in known && !def.hasRest) {
                    out.add(TeraError(msg("component.attr.unknown", call.name, name), attr.nameRange ?: attr.range, HighlightSeverity.ERROR))
                }
            }
            if (call.closed && call.attrs.none { it.spread }) {
                val missing = def.requiredParams.map { it.name }.filter { it !in given }
                if (missing.isNotEmpty()) {
                    out.add(TeraError(msg("component.attr.missing", call.name, missing.joinToString(", ")), call.nameRange, HighlightSeverity.ERROR))
                }
            }
        }
    }

    // ── bodies ───────────────────────────────────────────────────────────────

    /**
     * A component cannot see the render context or the variables around its call; what it needs from there has to be
     * an implicit parameter. Names the body reads without defining them are reported, with the parameter as a fix.
     */
    private fun bodyChecks(text: String, blocks: List<TeraBlock>, out: MutableList<TeraError>) {
        val snapshots = TeraScopes.snapshots(blocks, null, TeraVersion.V2, text)
        val occurrences = TeraVariables.collect(blocks, snapshots)
        // Which component each block belongs to: the innermost open `component` tag.
        val owner = IntArray(blocks.size) { -1 }
        val open = ArrayDeque<Int>()
        for ((i, b) in blocks.withIndex()) {
            val first = b.tokens.firstOrNull()?.text
            if (b.kind == '%' && first == "endcomponent") { if (open.isNotEmpty()) open.removeLast() }
            owner[i] = open.lastOrNull() ?: -1
            if (b.kind == '%' && first == "component") open.addLast(i)
        }
        val defs = blocks.indices.filter { blocks[it].kind == '%' && blocks[it].tokens.firstOrNull()?.text == "component" }
            .associateWith { TeraComponentScanner.definitions(text, listOf(blocks[it])).firstOrNull() }

        val missingByOwner = linkedMapOf<Int, LinkedHashSet<String>>()
        val flagged = mutableListOf<Pair<VarOccurrence, Int>>()
        for (occ in occurrences) {
            if (!occ.snapshot.hidesContext || occ.optional) continue
            val owning = owner[occ.blockIndex].takeIf { it >= 0 } ?: continue
            if (occ.resolve(occ.segs.size - 1, null).failedAt != 0) continue
            val root = occ.segs[0].name
            if (root in setOf("loop", "self")) continue
            missingByOwner.getOrPut(owning) { linkedSetOf() }.add(root)
            flagged.add(occ to owning)
        }
        for ((occ, owning) in flagged) {
            val def = defs[owning]
            val names = missingByOwner.getValue(owning)
            val fix = def?.takeIf { it.closeParen >= 0 }?.let {
                val insert = (if (it.params.isEmpty()) "" else ", ") + names.joinToString(", ") { n -> "@$n" }
                TeraFix(msg("component.fix.implicit", names.joinToString(", ") { n -> "@$n" }),
                    listOf(TeraEdit(TextRange(it.closeParen, it.closeParen), insert)))
            }
            val tok = occ.tokens[0]
            out.add(TeraError(msg("component.context", tok.text), tok.range, HighlightSeverity.ERROR, listOfNotNull(fix)))
        }
    }
}
