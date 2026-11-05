package com.virlin.app.ui.notes

/**
 * Display-only syntax colouring for code blocks.
 *
 * Nothing here is stored: the language is guessed from the text each time it is shown, so
 * existing notes are coloured without being rewritten. The scanner is a single linear pass and
 * never throws on unterminated strings or comments; text it cannot classify stays uncoloured.
 */
internal enum class CodeLanguage(val label: String) {
    PYTHON("Python"), JAVASCRIPT("JavaScript"), TYPESCRIPT("TypeScript"), KOTLIN("Kotlin"),
    JAVA("Java"), SQL("SQL"), BASH("Bash"), JSON("JSON"), HTML("HTML"), CSS("CSS"),
    C_LIKE("Code"), PLAIN("Code"),
}

internal enum class CodeTokenKind { KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, CONSTANT, PROPERTY, TAG, DECORATOR }

internal data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

internal data class CodeHighlight(val language: CodeLanguage, val tokens: List<CodeToken>)

internal object CodeHighlighter {
    /** Larger blocks are shown uncoloured so editing them stays responsive. */
    const val MAX_HIGHLIGHT_CHARS = 60_000
    private const val DETECT_SAMPLE_CHARS = 4_000

    fun highlight(text: String): CodeHighlight = highlightAs(text, detect(text))

    fun highlightAs(text: String, language: CodeLanguage): CodeHighlight {
        if (text.length > MAX_HIGHLIGHT_CHARS || language == CodeLanguage.PLAIN)
            return CodeHighlight(language, emptyList())
        val tokens = runCatching {
            when (language) {
                CodeLanguage.HTML -> scanHtml(text)
                CodeLanguage.CSS -> scanCss(text)
                else -> scanCode(text, specFor(language))
            }
        }.getOrDefault(emptyList())
        return CodeHighlight(language, tokens)
    }

    // ------------------------------------------------------------------ detection

    fun detect(text: String): CodeLanguage {
        val sample = text.take(DETECT_SAMPLE_CHARS)
        val trimmed = sample.trimStart()
        if (trimmed.isEmpty()) return CodeLanguage.PLAIN
        val firstLine = trimmed.lineSequence().first()
        if (firstLine.startsWith("#!")) return when {
            "python" in firstLine -> CodeLanguage.PYTHON
            "node" in firstLine -> CodeLanguage.JAVASCRIPT
            else -> CodeLanguage.BASH
        }
        if (looksLikeJson(trimmed)) return CodeLanguage.JSON
        if (trimmed.startsWith("<") && ("</" in sample || "/>" in sample)) return CodeLanguage.HTML
        val scores = HashMap<CodeLanguage, Double>()
        SIGNALS.forEach { (language, regex, weight) ->
            val hits = regex.findAll(sample).take(3).count()
            if (hits > 0) scores[language] = (scores[language] ?: 0.0) + hits * weight
        }
        val js = scores[CodeLanguage.JAVASCRIPT] ?: 0.0
        val tsOnly = scores.remove(CodeLanguage.TYPESCRIPT) ?: 0.0
        if (tsOnly > 0 && js + tsOnly >= 2.0) scores[CodeLanguage.TYPESCRIPT] = js + tsOnly
        val best = scores.maxByOrNull { it.value } ?: return CodeLanguage.PLAIN
        return if (best.value >= 2.0) best.key else CodeLanguage.PLAIN
    }

    private fun looksLikeJson(trimmed: String): Boolean {
        val first = trimmed[0]
        if (first != '{' && first != '[') return false
        val next = trimmed.drop(1).trimStart().firstOrNull() ?: return false
        return if (first == '{') next == '"' || next == '}'
        else next == '{' || next == '[' || next == '"' || next == ']' || next.isDigit() || next == '-'
    }

    private data class Signal(val language: CodeLanguage, val regex: Regex, val weight: Double)

    private fun s(language: CodeLanguage, pattern: String, weight: Double, ignoreCase: Boolean = false) =
        Signal(language, Regex(pattern, if (ignoreCase) setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
            else setOf(RegexOption.MULTILINE)), weight)

    private val SIGNALS = listOf(
        s(CodeLanguage.PYTHON, "^\\s*(async )?def \\w+\\(.*\\)\\s*(->.*)?:\\s*(#.*)?$", 3.0),
        s(CodeLanguage.PYTHON, "^\\s*from [\\w.]+ import ", 3.0),
        s(CodeLanguage.PYTHON, "^\\s*import [\\w.]+(\\s+as \\w+)?\\s*(#.*)?$", 1.0),
        s(CodeLanguage.PYTHON, "^\\s*class \\w+(\\(.*\\))?:\\s*(#.*)?$", 3.0),
        s(CodeLanguage.PYTHON, "^\\s*(elif .*:|except\\b.*:|try:|finally:)\\s*(#.*)?$", 2.0),
        s(CodeLanguage.PYTHON, "^\\s*(if|for|while|with) .*:\\s*(#.*)?$", 2.0),
        s(CodeLanguage.PYTHON, "\\bself\\.", 2.0),
        s(CodeLanguage.PYTHON, "\\b(None|True|False)\\b", 1.0),
        s(CodeLanguage.PYTHON, "^\\s*@dataclass", 3.0),

        s(CodeLanguage.KOTLIN, "^\\s*(private |internal |override |suspend )*fun (<.*> )?[\\w.]+\\(", 3.0),
        s(CodeLanguage.KOTLIN, "^\\s*(private |internal )?(val|var) \\w+(\\s*:\\s*[\\w<>?, ]+)?\\s*=", 2.0),
        s(CodeLanguage.KOTLIN, "^\\s*package [\\w.]+\\s*$", 2.0),
        s(CodeLanguage.KOTLIN, "\\bdata class \\w+\\(", 3.0),
        s(CodeLanguage.KOTLIN, "\\bwhen\\s*(\\(.*\\))?\\s*\\{", 1.0),
        s(CodeLanguage.KOTLIN, ":\\s*(String|Int|Boolean|Unit|Long)\\??\\b", 1.0),

        s(CodeLanguage.JAVA, "\\b(public|private|protected)\\s+(static\\s+)?(final\\s+)?[\\w<>\\[\\]]+\\s+\\w+\\s*\\(", 3.0),
        s(CodeLanguage.JAVA, "System\\.out\\.", 3.0),
        s(CodeLanguage.JAVA, "^\\s*import [\\w.*]+;\\s*$", 2.0),
        s(CodeLanguage.JAVA, "^\\s*package [\\w.]+;\\s*$", 3.0),
        s(CodeLanguage.JAVA, "\\bpublic (final )?class \\w+", 3.0),

        s(CodeLanguage.JAVASCRIPT, "\\b(const|let) \\w+\\s*=", 2.0),
        s(CodeLanguage.JAVASCRIPT, "=>", 1.5),
        s(CodeLanguage.JAVASCRIPT, "\\bfunction\\s*\\w*\\s*\\(", 2.0),
        s(CodeLanguage.JAVASCRIPT, "console\\.\\w+\\(", 3.0),
        s(CodeLanguage.JAVASCRIPT, "^\\s*import .* from ['\"]", 3.0),
        s(CodeLanguage.JAVASCRIPT, "require\\(['\"]", 3.0),
        s(CodeLanguage.JAVASCRIPT, "^\\s*export (default |const |function |class )", 3.0),
        s(CodeLanguage.JAVASCRIPT, "\\b(document|window)\\.", 2.0),

        s(CodeLanguage.TYPESCRIPT, ":\\s*(string|number|boolean|any|void|unknown)\\b", 2.0),
        s(CodeLanguage.TYPESCRIPT, "^\\s*(export )?interface \\w+", 2.0),
        s(CodeLanguage.TYPESCRIPT, "^\\s*(export )?type \\w+\\s*=", 2.0),

        s(CodeLanguage.SQL, "^\\s*select\\b[^;]*?\\bfrom\\b", 4.0, ignoreCase = true),
        s(CodeLanguage.SQL, "^\\s*(insert into|update \\w+ set|delete from|create (or replace )?(table|view|index)|alter table|drop table)\\b", 4.0, ignoreCase = true),
        s(CodeLanguage.SQL, "^\\s*with \\w+ as \\(", 4.0, ignoreCase = true),
        s(CodeLanguage.SQL, "\\b(inner|left|right|full|cross) (outer )?join\\b", 2.0, ignoreCase = true),
        s(CodeLanguage.SQL, "\\b(group|order|partition) by\\b", 2.0, ignoreCase = true),

        s(CodeLanguage.BASH, "^\\s*(sudo|echo|export|cd|ls|mkdir|rm|cp|mv|chmod|curl|wget|git|npm|npx|pip3?|apt(-get)?|brew|docker|kubectl|source|gradlew?)\\b", 1.5),
        s(CodeLanguage.BASH, "^\\s*(if \\[|fi\\s*$|then\\s*$|done\\s*$|esac\\s*$)", 3.0),
        s(CodeLanguage.BASH, "^\\s*set -[euxo]", 3.0),
        s(CodeLanguage.BASH, "\\$\\{\\w+\\}|\\$[A-Z_]{2,}\\b", 1.0),

        s(CodeLanguage.CSS, "^\\s*[\\w-]+\\s*:\\s*[^;{}]+;\\s*$", 1.5),
        s(CodeLanguage.CSS, "^\\s*[.#]?[\\w-]+([\\s,>+~:.#\\w-]*)\\{\\s*$", 1.0),
        s(CodeLanguage.CSS, "@(media|import|keyframes|font-face)\\b", 3.0),

        s(CodeLanguage.C_LIKE, "#include\\s*[<\"]", 4.0),
        s(CodeLanguage.C_LIKE, "\\bint main\\s*\\(", 4.0),
        s(CodeLanguage.C_LIKE, "\\bfn \\w+\\s*[(<]", 3.0),
        s(CodeLanguage.C_LIKE, "\\bfunc \\w+\\s*\\(", 3.0),
        s(CodeLanguage.C_LIKE, "^\\s*using System", 4.0),
        s(CodeLanguage.C_LIKE, "\\bstd::", 3.0),
        s(CodeLanguage.C_LIKE, "\\bprintf\\(", 2.0),
    )

    // ------------------------------------------------------------------ language specs

    private class Spec(
        val keywords: Set<String>,
        val constants: Set<String> = emptySet(),
        val lineComments: List<String> = emptyList(),
        val blockComment: Pair<String, String>? = null,
        val quotes: String = "\"'",
        val multilineQuote: Char? = null,
        val tripleQuotes: Boolean = false,
        val caseInsensitive: Boolean = false,
        val decorators: Boolean = false,
        val dollarVariables: Boolean = false,
        val jsonKeys: Boolean = false,
        val functions: Boolean = true,
    )

    private fun words(value: String) = value.split(' ').filter { it.isNotEmpty() }.toHashSet()

    private val JS_KEYWORDS = words("const let var function return if else for while do switch case break continue " +
        "new class extends import from export default async await try catch finally throw typeof instanceof in of " +
        "this super yield delete void static get set")
    private val TS_EXTRA = words("interface type enum implements public private protected readonly declare namespace " +
        "abstract as keyof")
    private val C_FAMILY = words("if else for while do switch case break continue return goto struct union enum typedef " +
        "static const volatile extern sizeof void int char float double long short unsigned signed bool auto class " +
        "public private protected virtual template typename namespace using new delete try catch throw fn let mut " +
        "impl trait pub use mod match loop func package import var type map chan go defer select interface")

    private val PYTHON = Spec(
        keywords = words("def class return if elif else for while in not and or import from as with try except " +
            "finally raise pass break continue lambda yield global nonlocal assert del is async await match case"),
        constants = words("True False None self"),
        lineComments = listOf("#"), tripleQuotes = true, decorators = true,
    )
    private val KOTLIN = Spec(
        keywords = words("fun val var class object interface data sealed enum when if else for while do return in " +
            "is as package import private public internal protected override open abstract suspend companion init " +
            "throw try catch finally typealias by lateinit const inline reified annotation"),
        constants = words("true false null this super it"),
        lineComments = listOf("//"), blockComment = "/*" to "*/", tripleQuotes = true, decorators = true,
    )
    private val JAVA = Spec(
        keywords = words("public private protected static final abstract class interface enum extends implements " +
            "import package return if else for while do switch case break continue new try catch finally throw " +
            "throws void int long double float boolean char byte short var record synchronized default"),
        constants = words("true false null this super"),
        lineComments = listOf("//"), blockComment = "/*" to "*/", decorators = true,
    )
    private val JAVASCRIPT = Spec(
        keywords = JS_KEYWORDS, constants = words("true false null undefined NaN"),
        lineComments = listOf("//"), blockComment = "/*" to "*/", quotes = "\"'`", multilineQuote = '`',
    )
    private val TYPESCRIPT = Spec(
        keywords = JS_KEYWORDS + TS_EXTRA, constants = words("true false null undefined NaN"),
        lineComments = listOf("//"), blockComment = "/*" to "*/", quotes = "\"'`", multilineQuote = '`',
        decorators = true,
    )
    private val SQL = Spec(
        keywords = words("select from where join left right inner outer full cross on group by order having limit " +
            "offset insert into values update set delete create table view index drop alter add primary key foreign " +
            "references not null and or as distinct union all with case when then else end is in like between " +
            "exists over partition asc desc default unique check constraint replace returning"),
        constants = words("true false null"),
        lineComments = listOf("--"), blockComment = "/*" to "*/", caseInsensitive = true,
    )
    private val BASH = Spec(
        keywords = words("if then else elif fi for in do done while until case esac function return local export " +
            "set unset echo exit source readonly declare shift trap"),
        lineComments = listOf("#"), dollarVariables = true, functions = false,
    )
    private val JSON = Spec(
        keywords = emptySet(), constants = words("true false null"),
        quotes = "\"", jsonKeys = true, functions = false,
    )
    private val C_LIKE = Spec(
        keywords = C_FAMILY, constants = words("true false null nullptr NULL nil self this"),
        lineComments = listOf("//"), blockComment = "/*" to "*/", quotes = "\"'`", multilineQuote = '`',
    )

    private fun specFor(language: CodeLanguage): Spec = when (language) {
        CodeLanguage.PYTHON -> PYTHON
        CodeLanguage.KOTLIN -> KOTLIN
        CodeLanguage.JAVA -> JAVA
        CodeLanguage.JAVASCRIPT -> JAVASCRIPT
        CodeLanguage.TYPESCRIPT -> TYPESCRIPT
        CodeLanguage.SQL -> SQL
        CodeLanguage.BASH -> BASH
        CodeLanguage.JSON -> JSON
        else -> C_LIKE
    }

    // ------------------------------------------------------------------ scanners

    private fun Char.isIdentStart() = isLetter() || this == '_'
    private fun Char.isIdentPart() = isLetterOrDigit() || this == '_'

    private fun lineEnd(text: String, from: Int): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }

    /** End (exclusive) of a quoted string starting at [start]; stops at a line break unless [multiline]. */
    private fun stringEnd(text: String, start: Int, quote: Char, multiline: Boolean): Int {
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') { i += 2; continue }
            if (c == quote) return i + 1
            if (c == '\n' && !multiline) return i
            i++
        }
        return text.length
    }

    private fun scanCode(text: String, spec: Spec): List<CodeToken> {
        val tokens = ArrayList<CodeToken>()
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            val block = spec.blockComment
            if (block != null && text.startsWith(block.first, i)) {
                val close = text.indexOf(block.second, i + block.first.length)
                val end = if (close < 0) n else close + block.second.length
                tokens += CodeToken(i, end, CodeTokenKind.COMMENT); i = end; continue
            }
            val comment = spec.lineComments.firstOrNull { text.startsWith(it, i) }
            if (comment != null && (!spec.dollarVariables || i == 0 || text[i - 1].isWhitespace())) {
                val end = lineEnd(text, i)
                tokens += CodeToken(i, end, CodeTokenKind.COMMENT); i = end; continue
            }
            if (c in spec.quotes) {
                val end = if (spec.tripleQuotes && i + 2 < n && text[i + 1] == c && text[i + 2] == c) {
                    val close = text.indexOf("$c$c$c", i + 3)
                    if (close < 0) n else close + 3
                } else stringEnd(text, i, c, multiline = c == spec.multilineQuote)
                var kind = CodeTokenKind.STRING
                if (spec.jsonKeys) {
                    var j = end
                    while (j < n && text[j].isWhitespace()) j++
                    if (j < n && text[j] == ':') kind = CodeTokenKind.PROPERTY
                }
                tokens += CodeToken(i, end, kind); i = end; continue
            }
            if (spec.decorators && c == '@' && i + 1 < n && text[i + 1].isIdentStart()) {
                var j = i + 1
                while (j < n && (text[j].isIdentPart() || text[j] == '.')) j++
                tokens += CodeToken(i, j, CodeTokenKind.DECORATOR); i = j; continue
            }
            if (spec.dollarVariables && c == '$' && i + 1 < n) {
                val end = when {
                    text[i + 1] == '{' -> text.indexOf('}', i + 2).let { if (it < 0 || it > lineEnd(text, i)) i + 1 else it + 1 }
                    text[i + 1].isIdentPart() -> { var j = i + 1; while (j < n && text[j].isIdentPart()) j++; j }
                    text[i + 1] in "@#?!$*-" -> i + 2
                    else -> i + 1
                }
                if (end > i + 1) { tokens += CodeToken(i, end, CodeTokenKind.CONSTANT); i = end; continue }
            }
            if (c.isDigit() && (i == 0 || !text[i - 1].isIdentPart())) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_') &&
                    !(text[j] == '.' && (j + 1 >= n || !text[j + 1].isDigit()))) j++
                tokens += CodeToken(i, j, CodeTokenKind.NUMBER); i = j; continue
            }
            if (c.isIdentStart()) {
                var j = i + 1
                while (j < n && text[j].isIdentPart()) j++
                val word = text.substring(i, j)
                val key = if (spec.caseInsensitive) word.lowercase() else word
                val kind = when {
                    key in spec.keywords -> CodeTokenKind.KEYWORD
                    key in spec.constants -> CodeTokenKind.CONSTANT
                    spec.functions && nextNonSpace(text, j) == '(' -> CodeTokenKind.FUNCTION
                    else -> null
                }
                if (kind != null) tokens += CodeToken(i, j, kind)
                i = j; continue
            }
            i++
        }
        return tokens
    }

    private fun nextNonSpace(text: String, from: Int): Char? {
        var j = from
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        return text.getOrNull(j)
    }

    private fun scanHtml(text: String): List<CodeToken> {
        val tokens = ArrayList<CodeToken>()
        val n = text.length
        var i = 0
        while (i < n) {
            if (text.startsWith("<!--", i)) {
                val close = text.indexOf("-->", i + 4)
                val end = if (close < 0) n else close + 3
                tokens += CodeToken(i, end, CodeTokenKind.COMMENT); i = end; continue
            }
            if (text[i] == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '/' || text[i + 1] == '!')) {
                var j = i + 1
                if (j < n && (text[j] == '/' || text[j] == '!')) j++
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == ':')) j++
                tokens += CodeToken(i, j, CodeTokenKind.TAG)
                while (j < n && text[j] != '>') {
                    val c = text[j]
                    when {
                        c == '"' || c == '\'' -> {
                            val end = stringEnd(text, j, c, multiline = true)
                            tokens += CodeToken(j, end, CodeTokenKind.STRING); j = end
                        }
                        c.isLetter() -> {
                            var k = j + 1
                            while (k < n && (text[k].isLetterOrDigit() || text[k] == '-' || text[k] == ':')) k++
                            tokens += CodeToken(j, k, CodeTokenKind.PROPERTY); j = k
                        }
                        c == '<' -> break
                        else -> j++
                    }
                }
                if (j < n && text[j] == '>') { tokens += CodeToken(j, j + 1, CodeTokenKind.TAG); j++ }
                i = j; continue
            }
            i++
        }
        return tokens
    }

    private fun scanCss(text: String): List<CodeToken> {
        val tokens = ArrayList<CodeToken>()
        val n = text.length
        var depth = 0
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                text.startsWith("/*", i) -> {
                    val close = text.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    tokens += CodeToken(i, end, CodeTokenKind.COMMENT); i = end
                }
                c == '"' || c == '\'' -> {
                    val end = stringEnd(text, i, c, multiline = false)
                    tokens += CodeToken(i, end, CodeTokenKind.STRING); i = end
                }
                c == '{' -> { depth++; i++ }
                c == '}' -> { depth = (depth - 1).coerceAtLeast(0); i++ }
                c == '@' -> {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '-')) j++
                    tokens += CodeToken(i, j, CodeTokenKind.KEYWORD); i = j
                }
                c == '#' && depth > 0 -> {
                    var j = i + 1
                    while (j < n && text[j].isLetterOrDigit()) j++
                    tokens += CodeToken(i, j, CodeTokenKind.NUMBER); i = j
                }
                c.isDigit() && (i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '-')) -> {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '%')) j++
                    tokens += CodeToken(i, j, CodeTokenKind.NUMBER); i = j
                }
                depth > 0 && (c.isLetter() || c == '-') -> {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '-')) j++
                    if (nextNonSpace(text, j) == ':') tokens += CodeToken(i, j, CodeTokenKind.PROPERTY)
                    i = j
                }
                else -> i++
            }
        }
        return tokens
    }
}
