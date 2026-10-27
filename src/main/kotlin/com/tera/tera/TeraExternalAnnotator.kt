package com.tera.tera

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

// ── 错误数据 ──────────────────────────────────────────────────────────────────
data class TeraError(
    val message: String,
    val range: TextRange,
    val severity: HighlightSeverity,
    /** Quick fixes offered on this error. */
    val fixes: List<TeraFix> = emptyList(),
    /** Draw the range struck through, like a deprecated symbol. */
    val deprecated: Boolean = false,
)

// ── ExternalAnnotator ─────────────────────────────────────────────────────────
/** File text plus the macros of every template it imports (null = import could not be resolved). */
class TeraAnnotatorInput(
    val text: String,
    val importedMacros: Map<String, List<MacroDef>?>,
    val context: TeraContextResult? = null,
    val version: TeraVersion = TeraVersion.V1,
    val custom: List<TeraCustomBuiltin> = emptyList(),
    val migrationHints: Boolean = true,
    val components: List<TeraComponent> = emptyList(),
)

class TeraExternalAnnotator : ExternalAnnotator<TeraAnnotatorInput, List<TeraError>>() {

    override fun collectInformation(file: PsiFile): TeraAnnotatorInput? {
        if (file !is TeraFile) return null
        val text = file.text
        val vFile = file.originalFile.virtualFile
        val imported = TeraSymbolScanner.scan(text).imports.associate { imp ->
            imp.path to vFile?.let { TeraImports.macrosOf(it, imp.path) }
        }
        return TeraAnnotatorInput(
            text, imported, TeraContexts.forFile(file), TeraVersions.forFile(file.project, vFile).version,
            TeraContexts.customBuiltins(file.project), TeraSettings.getInstance(file.project).state.migrationHints,
            TeraComponents.visible(file.project, vFile, text),
        )
    }

    override fun doAnnotate(input: TeraAnnotatorInput): List<TeraError> =
        analyze(input.text) + TeraSemanticChecker.check(input.text) { input.importedMacros[it] } +
            TeraVariables.check(input.text, input.context, input.version) +
            TeraMigration.check(input.text, input.version, input.custom, input.migrationHints) +
            (if (input.version == TeraVersion.V2) TeraComponentChecker.check(input.text, input.components) +
                TeraTypeChecker.check(input.text, input.context, input.components, input.custom) else emptyList())

    /** Syntax checks only (block structure, tag shapes); no cross-template analysis. */
    fun doAnnotate(fileText: String): List<TeraError> = analyze(fileText)

    override fun apply(file: PsiFile, errors: List<TeraError>?, holder: AnnotationHolder) {
        errors?.forEach { err ->
            var builder = holder.newAnnotation(err.severity, err.message).range(err.range)
            if (err.deprecated) builder = builder.highlightType(com.intellij.codeInspection.ProblemHighlightType.LIKE_DEPRECATED)
            for (fix in err.fixes) builder = builder.withFix(TeraEditIntention(fix))
            builder.create()
        }
    }

    // ── 常量 ─────────────────────────────────────────────────────────────────

    private data class OpenBlock(val keyword: String, val range: TextRange)

    /** 闭块关键字 → 对应开块关键字 */
    private val CLOSERS = mapOf(
        "endfor"    to "for",
        "endif"     to "if",
        "endblock"  to "block",
        "endmacro"  to "macro",
        "endraw"    to "raw",
        "endfilter" to "filter",
        "endcomponent" to "component",
        "endset"    to "set",
    )
    /** `set` opens a block only without `=` (Tera 2: `{% set x %}...{% endset %}`), see [opensBlock]. */
    private val OPENERS         = CLOSERS.values.toSet() - "set"
    private val MIDDLE_KEYWORDS = setOf("else", "elif")

    /** 不接受任何参数的关键字 */
    private val NAMED_CLOSERS = setOf("endmacro", "endblock", "endcomponent")
    private val NO_ARG_KEYWORDS = (CLOSERS.keys - NAMED_CLOSERS) + setOf("else", "break", "continue")

    /**
     * 只能出现在表达式内部、不能作为 tag 第一个词的关键字。
     * 出现在 tag 开头说明写法有误（如 {% in ... %}）。
     */
    private val EXPR_ONLY_KEYWORDS = setOf(
        "in", "and", "or", "not", "is", "true", "false", "loop", "super"
    )

    // ── tag 内 token ──────────────────────────────────────────────────────────

    private data class TagToken(
        val type: IElementType,
        val text: String,
        val range: TextRange,
    )

    // ── 主分析逻辑 ────────────────────────────────────────────────────────────

    private fun analyze(text: String): List<TeraError> {
        val errors = mutableListOf<TeraError>()
        val stack  = ArrayDeque<OpenBlock>()

        var delimStart = -1
        var delimText  = ""

        var inTag             = false
        val currentTagTokens  = mutableListOf<TagToken>()

        val lexer = TeraLexer()
        lexer.start(text, 0, text.length, TeraLexer.STATE_OUTER)

        while (lexer.tokenType != null) {
            val tt = lexer.tokenType!!
            val ts = lexer.tokenStart
            val te = lexer.tokenEnd

            when (tt) {
                TeraTokenTypes.VARIABLE_START -> { delimStart = ts; delimText = "{{" }
                TeraTokenTypes.TAG_START      -> {
                    delimStart = ts; delimText = "{%"
                    inTag = true; currentTagTokens.clear()
                }
                TeraTokenTypes.COMMENT_START  -> { delimStart = ts; delimText = "{#" }

                TeraTokenTypes.VARIABLE_END -> { delimStart = -1; delimText = "" }
                TeraTokenTypes.COMMENT_END  -> { delimStart = -1; delimText = "" }
                TeraTokenTypes.TAG_END      -> {
                    delimStart = -1; delimText = ""
                    processTag(currentTagTokens, stack, errors)
                    inTag = false
                }

                // 收集 tag 内所有有效 token（跳过空白）
                TeraTokenTypes.KEYWORD,
                TeraTokenTypes.IDENTIFIER,
                TeraTokenTypes.STRING,
                TeraTokenTypes.NUMBER,
                TeraTokenTypes.OPERATOR -> {
                    if (inTag) {
                        currentTagTokens.add(TagToken(tt, text.substring(ts, te), TextRange(ts, te)))
                    }
                }

                else -> Unit
            }

            lexer.advance()
        }

        // EOF：未闭合定界符
        if (delimStart >= 0) {
            val closer = when (delimText) { "{{" -> "}}" ; "{%" -> "%}" ; else -> "#}" }
            errors.add(TeraError(
                TeraBundle.message("error.unclosed.delimiter", delimText, closer),
                TextRange(delimStart, delimStart + 2),
                HighlightSeverity.ERROR,
            ))
        }

        // EOF：未闭合块标签
        stack.forEach { block ->
            errors.add(TeraError(
                if (block.keyword.startsWith("<")) TeraBundle.message("error.component.unclosed", block.keyword, "</" + block.keyword.drop(1))
                else TeraBundle.message("error.unclosed.block", block.keyword),
                block.range,
                HighlightSeverity.ERROR,
            ))
        }

        return errors
    }

    // ── tag 内容校验 ──────────────────────────────────────────────────────────

    private fun processTag(
        tokens: List<TagToken>,
        stack : ArrayDeque<OpenBlock>,
        errors: MutableList<TeraError>,
    ) {
        if (tokens.isEmpty()) return

        // ── 点号合法性检测（对整个 tag 内所有 token 扫描）────────────────────
        // `...` (Tera 2 spread: `...rest`, `{...props}`) is three adjacent dots, not a mistyped `..`.
        var i = 0
        while (i < tokens.size) {
            if (tokens[i].text != ".") { i++; continue }
            val a = tokens.getOrNull(i + 1)
            val b = tokens.getOrNull(i + 2)
            if (a?.text == "." && b?.text == "." &&
                a.range.startOffset == tokens[i].range.endOffset && b.range.startOffset == a.range.endOffset
            ) { i += 3; continue }
            val next = a
            when {
                // 双点：loop..last
                next?.text == "." ->
                    errors.add(TeraError(
                        TeraBundle.message("error.double.dot"),
                        TextRange(tokens[i].range.startOffset, next.range.endOffset),
                        HighlightSeverity.ERROR,
                    ))
                // 悬空点：tag_text. 或 tag_text. | filter
                next == null ||
                (next.type != TeraTokenTypes.IDENTIFIER && next.type != TeraTokenTypes.NUMBER) ->
                    errors.add(TeraError(
                        TeraBundle.message("error.dangling.dot"),
                        tokens[i].range,
                        HighlightSeverity.ERROR,
                    ))
            }
            i++
        }

        val first = tokens[0]

        // Tera 2 component tags: `{% <name attr="x"> %}` ... `{% </name> %}` (or self-closing `{% <name/> %}`).
        if (first.type == TeraTokenTypes.OPERATOR && first.text == "<") {
            processComponentTag(tokens, stack, errors)
            return
        }

        // ── 第一个 token 必须是关键字，否则是非法 tag 开头 ────────────────────
        if (first.type != TeraTokenTypes.KEYWORD) {
            errors.add(TeraError(
                TeraBundle.message("error.tag.invalid.start", first.text),
                first.range, HighlightSeverity.ERROR,
            ))
            return
        }

        // ── 表达式关键字不能作为 tag 开头（如 {% in ... %}）────────────────────
        if (first.text in EXPR_ONLY_KEYWORDS) {
            errors.add(TeraError(
                TeraBundle.message("error.tag.invalid.name", first.text),
                first.range, HighlightSeverity.ERROR,
            ))
            return
        }

        // ── 不接受参数的关键字后面不能有内容 ─────────────────────────────────────
        if (first.text in NO_ARG_KEYWORDS && tokens.size > 1) {
            errors.add(TeraError(
                TeraBundle.message("error.tag.no.args", first.text),
                tokens[1].range, HighlightSeverity.ERROR,
            ))
            return
        }

        // ── 按 tag 类型做专项校验 ─────────────────────────────────────────────
        // Tera permits one optional identifier after endmacro/endblock; endcomponent also takes a dotted name (ui.button).
        if (first.text in NAMED_CLOSERS && tokens.size > 1) {
            val rest = tokens.drop(1)
            val joined = rest.joinToString("") { it.text }
            val ok = if (first.text == "endcomponent") joined.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*"))
            else rest.size == 1 && joined.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))
            if (!ok) {
                errors.add(TeraError(
                    TeraBundle.message("error.tag.optional.name", first.text),
                    TextRange(rest.first().range.startOffset, tokens.last().range.endOffset),
                    HighlightSeverity.ERROR,
                ))
            }
        }

        when (first.text) {
            "for"  -> validateFor(tokens, errors)
            "if",
            "elif" -> validateCondition(tokens, errors)
        }

        // ── 块结构入栈 / 出栈 ─────────────────────────────────────────────────
        when {
            first.text in OPENERS || opensBlock(first.text, tokens) -> stack.addLast(OpenBlock(blockName(first.text), first.range))

            first.text in CLOSERS -> {
                val expected = CLOSERS[first.text]!!
                when {
                    stack.isEmpty() ->
                        errors.add(TeraError(
                            TeraBundle.message("error.unexpected.closer", first.text, expected),
                            first.range, HighlightSeverity.ERROR,
                        ))
                    stack.last().keyword != expected -> {
                        val last = stack.last()
                        errors.add(TeraError(
                            TeraBundle.message("error.block.mismatch", first.text, last.keyword),
                            first.range, HighlightSeverity.ERROR,
                        ))
                    }
                    else -> stack.removeLast()
                }
            }

            first.text in MIDDLE_KEYWORDS -> {
                // `else` also separates the body of a `for` from what to show when the loop is empty.
                val allowed = if (first.text == "else") setOf("if", "for") else setOf("if")
                if (stack.isEmpty() || stack.last().keyword !in allowed) {
                    errors.add(TeraError(
                        TeraBundle.message("error.middle.outside.if", first.text),
                        first.range, HighlightSeverity.ERROR,
                    ))
                }
            }
        }
    }

    /** `{% set x %}` / `{% set x | filter %}` (no `=`) starts a block closed by `endset`. */
    private fun opensBlock(keyword: String, tokens: List<TagToken>): Boolean =
        (keyword == "set" || keyword == "set_global") && tokens.size >= 2 &&
            tokens.none { it.type == TeraTokenTypes.OPERATOR && it.text == "=" }

    private fun blockName(keyword: String) = if (keyword == "set_global") "set" else keyword

    /** `<name ...>` opens, `</name>` closes, `<name .../>` is complete. Names may be dotted (`ui.button`). */
    private fun processComponentTag(tokens: List<TagToken>, stack: ArrayDeque<OpenBlock>, errors: MutableList<TeraError>) {
        val closing = tokens.getOrNull(1)?.let { it.type == TeraTokenTypes.OPERATOR && it.text == "/" } == true
        val nameStart = if (closing) 2 else 1
        val nameParts = mutableListOf<TagToken>()
        var i = nameStart
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TeraTokenTypes.IDENTIFIER && (nameParts.isEmpty() || nameParts.last().text == ".")) nameParts.add(t)
            else if (t.type == TeraTokenTypes.OPERATOR && t.text == "." && nameParts.isNotEmpty() && nameParts.last().text != ".") nameParts.add(t)
            else break
            i++
        }
        if (nameParts.isEmpty() || nameParts.last().text == ".") {
            errors.add(TeraError(TeraBundle.message("error.tag.invalid.start", tokens[0].text), tokens[0].range, HighlightSeverity.ERROR))
            return
        }
        val name = nameParts.joinToString("") { it.text }
        val range = TextRange(tokens[0].range.startOffset, nameParts.last().range.endOffset)
        val selfClosing = tokens.size >= 2 && tokens.last().text == ">" && tokens[tokens.size - 2].text == "/"
        when {
            closing -> when {
                stack.isEmpty() -> errors.add(TeraError(TeraBundle.message("error.unexpected.closer", "</$name>", "<$name>"), range, HighlightSeverity.ERROR))
                stack.last().keyword != "<$name>" -> errors.add(TeraError(
                    TeraBundle.message("error.component.mismatch", "</$name>", stack.last().keyword), range, HighlightSeverity.ERROR))
                else -> stack.removeLast()
            }
            selfClosing -> Unit
            else -> stack.addLast(OpenBlock("<$name>", range))
        }
    }

    /**
     * 校验 `{% for IDENT [, IDENT] in EXPR %}` 结构：
     * - `for` 后第一个有效 token 不能是 `in`（缺少循环变量）
     * - `for` 后必须有 `in` 关键字
     */
    private fun validateFor(tokens: List<TagToken>, errors: MutableList<TeraError>) {
        // tokens[0] = "for"
        val rest = tokens.drop(1)

        if (rest.isEmpty()) {
            errors.add(TeraError(
                TeraBundle.message("error.for.missing.all"),
                tokens[0].range, HighlightSeverity.ERROR,
            ))
            return
        }

        // 第一个非 for token 就是 `in` → 缺少循环变量
        if (rest[0].text == "in") {
            errors.add(TeraError(
                TeraBundle.message("error.for.missing.variable"),
                rest[0].range, HighlightSeverity.ERROR,
            ))
            return
        }

        // 没有 `in` 关键字
        val hasIn = rest.any { it.type == TeraTokenTypes.KEYWORD && it.text == "in" }
        if (!hasIn) {
            errors.add(TeraError(
                TeraBundle.message("error.for.missing.in"),
                tokens[0].range, HighlightSeverity.ERROR,
            ))
        }
    }

    /** 校验 if/elif 后必须有条件表达式 */
    private fun validateCondition(tokens: List<TagToken>, errors: MutableList<TeraError>) {
        if (tokens.size < 2) {
            errors.add(TeraError(
                TeraBundle.message("error.condition.missing", tokens[0].text),
                tokens[0].range, HighlightSeverity.ERROR,
            ))
        }
    }
}
