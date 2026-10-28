package com.tera.tera

import com.intellij.lang.annotation.HighlightSeverity

/**
 * Checks macro definitions, `import ... as` aliases and `ns::macro(...)` calls.
 *
 * @param importedMacros macros of the template imported from `path`, or null when it cannot be resolved.
 */
object TeraSemanticChecker {

    fun check(text: String, importedMacros: (String) -> List<MacroDef>?): List<TeraError> {
        val symbols = TeraSymbolScanner.scan(text)
        val errors = mutableListOf<TeraError>()

        val seenMacros = mutableSetOf<String>()
        for (macro in symbols.macros) {
            if (!seenMacros.add(macro.name)) {
                errors.add(TeraError(TeraBundle.message("semantic.macro.duplicate", macro.name),
                    macro.nameRange, HighlightSeverity.WARNING))
            }
            val seenParams = mutableSetOf<String>()
            for (p in macro.params) if (!seenParams.add(p.name)) {
                errors.add(TeraError(TeraBundle.message("semantic.param.duplicate", p.name),
                    p.range, HighlightSeverity.ERROR))
            }
        }

        val resolvedByAlias = mutableMapOf<String, List<MacroDef>?>()
        for (imp in symbols.imports) {
            val macros = importedMacros(imp.path)
            if (macros == null) {
                errors.add(TeraError(TeraBundle.message("semantic.import.unresolved", imp.path),
                    imp.pathRange, HighlightSeverity.WARNING))
            }
            resolvedByAlias[imp.alias] = macros
        }

        for (call in symbols.calls) {
            val target: List<MacroDef>?
            when {
                call.namespace == "self" -> target = symbols.macros
                call.namespace in resolvedByAlias -> target = resolvedByAlias[call.namespace] ?: continue
                else -> {
                    errors.add(TeraError(TeraBundle.message("semantic.namespace.unknown", call.namespace),
                        call.nameRange, HighlightSeverity.ERROR))
                    continue
                }
            }
            val macro = target.firstOrNull { it.name == call.name }
            if (macro == null) {
                val key = if (call.namespace == "self") "semantic.macro.undefined.self" else "semantic.macro.undefined.in"
                val where = symbols.imports.firstOrNull { it.alias == call.namespace }?.path ?: call.namespace
                errors.add(TeraError(TeraBundle.message(key, call.name, where),
                    call.nameRange, HighlightSeverity.ERROR))
                continue
            }
            checkArguments(call, macro, errors)
        }
        return errors
    }

    private fun checkArguments(call: MacroCall, macro: MacroDef, errors: MutableList<TeraError>) {
        val given = mutableSetOf<String>()
        val known = macro.params.map { it.name }.toSet()
        for (arg in call.args) {
            when {
                arg.name == null -> errors.add(TeraError(TeraBundle.message("semantic.arg.positional"),
                    arg.range, HighlightSeverity.ERROR))
                arg.name !in known -> errors.add(TeraError(
                    TeraBundle.message("semantic.arg.unknown", macro.name, arg.name),
                    arg.range, HighlightSeverity.ERROR))
                !given.add(arg.name) -> errors.add(TeraError(
                    TeraBundle.message("semantic.arg.duplicate", arg.name),
                    arg.range, HighlightSeverity.ERROR))
            }
        }
        if (!call.closed) return
        val missing = macro.required.map { it.name }.filter { it !in given }
        if (missing.isNotEmpty()) {
            errors.add(TeraError(
                TeraBundle.message("semantic.arg.missing", macro.name, missing.joinToString(", ")),
                call.nameRange, HighlightSeverity.ERROR))
        }
    }
}
