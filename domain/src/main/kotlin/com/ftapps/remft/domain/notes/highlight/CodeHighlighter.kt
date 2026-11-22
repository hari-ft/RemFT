package com.ftapps.remft.domain.notes.highlight

enum class CodeTokenKind { Keyword, String, Comment, Number, Name, Meta }

data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

object CodeHighlighter {
    private val aliases: Map<String, String> = mapOf(
        "sh" to "bash",
        "zsh" to "bash",
        "console" to "shell",
        "js" to "javascript",
        "jsx" to "javascript",
        "mjs" to "javascript",
        "cjs" to "javascript",
        "ts" to "typescript",
        "tsx" to "typescript",
        "yml" to "yaml",
        "html" to "xml",
        "svg" to "xml",
        "md" to "markdown",
        "py" to "python",
        "rs" to "rust",
        "kt" to "kotlin",
        "kts" to "kotlin",
        "golang" to "go",
        "toml" to "ini",
        "conf" to "ini",
        "patch" to "diff",
    )

    private val grammars: Set<String> = setOf(
        "bash",
        "css",
        "dart",
        "diff",
        "go",
        "ini",
        "java",
        "javascript",
        "json",
        "kotlin",
        "markdown",
        "python",
        "rust",
        "shell",
        "sql",
        "swift",
        "typescript",
        "xml",
        "yaml",
    )

    private val infoSeparators = Regex("[\\s,]")

    fun languageOf(info: String?): String? {
        if (info == null) return null
        val trimmed = info.trim()
        if (trimmed.isEmpty()) return null
        val name = trimmed.lowercase().split(infoSeparators).first()
        val resolved = aliases[name] ?: name
        return resolved.takeIf { it in grammars }
    }

    fun highlight(code: String, language: String?): List<CodeToken> {
        val resolved = languageOf(language) ?: return emptyList()
        if (code.isEmpty()) return emptyList()
        return try {
            val out = TokenSink(code.length)
            when (resolved) {
                "css" -> lexCss(code, out)
                "diff" -> lexDiff(code, out)
                "ini" -> lexIni(code, out)
                "markdown" -> lexMarkdown(code, out)
                "xml" -> lexXml(code, out)
                "yaml" -> lexYaml(code, out)
                else -> lexGeneric(code, specs.getValue(resolved), out)
            }
            out.tokens
        } catch (e: Exception) {
            emptyList()
        }
    }

    private class TokenSink(private val length: Int) {
        val tokens = ArrayList<CodeToken>()

        fun add(start: Int, end: Int, kind: CodeTokenKind) {
            val s = start.coerceIn(0, length)
            val e = end.coerceIn(0, length)
            if (e <= s) return
            val last = tokens.lastOrNull()
            if (last != null && s < last.end) return
            if (last != null && last.end == s && last.kind == kind) {
                tokens[tokens.size - 1] = last.copy(end = e)
                return
            }
            tokens.add(CodeToken(s, e, kind))
        }
    }

    private class Spec(
        val keywords: Set<String> = emptySet(),
        val literals: Set<String> = emptySet(),
        val types: Set<String> = emptySet(),
        val declarations: Set<String> = emptySet(),
        val lineComments: List<String> = emptyList(),
        val blockComment: Pair<String, String>? = null,
        val nestedComments: Boolean = false,
        val quotes: String = "\"'",
        val rawQuotes: String = "",
        val multilineQuotes: String = "",
        val tripleQuotes: Boolean = false,
        val annotations: Boolean = false,
        val hashDirectives: Boolean = false,
        val rustSyntax: Boolean = false,
        val stringPrefixes: String = "",
        val ignoreCase: Boolean = false,
        val dollarIdentifiers: Boolean = false,
        val shellVariables: Boolean = false,
        val hashCommentNeedsBoundary: Boolean = false,
        val backtickName: Boolean = false,
        val jsonKeys: Boolean = false,
        val promptMeta: Boolean = false,
    )

    private fun words(text: String): Set<String> = text.split(' ').filter { it.isNotEmpty() }.toHashSet()

    private val cBlock = "/*" to "*/"

    private val jsKeywords = words(
        "break case catch class const continue debugger default delete do else export extends finally for " +
            "function if import in instanceof let new return super switch this throw try typeof var void while " +
            "with yield async await of static get set from as",
    )
    private val jsLiterals = words("true false null undefined NaN Infinity")
    private val jsBuiltins = words(
        "console window document globalThis Array Object String Number Boolean Promise Map Set WeakMap WeakSet " +
            "JSON Math Date Error RegExp Symbol BigInt require module exports process",
    )

    private val bashSpec = Spec(
        keywords = words(
            "if then else elif fi case esac for while until do done in function select time return exit break " +
                "continue local export readonly declare typeset unset shift source alias unalias set trap eval exec",
        ),
        literals = words("true false"),
        types = words(
            "echo cd pwd printf read test cat grep sed awk ls rm cp mv mkdir rmdir touch chmod chown sudo curl " +
                "wget git tar find xargs sort uniq head tail wc kill ps env which make npm yarn docker ssh",
        ),
        declarations = words("function"),
        lineComments = listOf("#"),
        hashCommentNeedsBoundary = true,
        rawQuotes = "'",
        multilineQuotes = "\"'",
        shellVariables = true,
    )

    private val specs: Map<String, Spec> = mapOf(
        "bash" to bashSpec,
        "shell" to Spec(
            keywords = bashSpec.keywords,
            literals = bashSpec.literals,
            types = bashSpec.types,
            declarations = bashSpec.declarations,
            lineComments = bashSpec.lineComments,
            hashCommentNeedsBoundary = true,
            rawQuotes = "'",
            multilineQuotes = "\"'",
            shellVariables = true,
            promptMeta = true,
        ),
        "dart" to Spec(
            keywords = words(
                "abstract as assert async await base break case catch class const continue covariant default " +
                    "deferred do else enum export extends extension external factory final finally for get hide if " +
                    "implements import in interface is late library mixin new on operator part required rethrow " +
                    "return sealed set show static super switch sync this throw try typedef var when while with yield",
            ),
            literals = words("true false null"),
            types = words("int double num String bool List Map Set Future Stream Object Never dynamic void Iterable Function"),
            declarations = words("class mixin enum extension typedef"),
            lineComments = listOf("//"),
            blockComment = cBlock,
            nestedComments = true,
            tripleQuotes = true,
            annotations = true,
            stringPrefixes = "r",
        ),
        "go" to Spec(
            keywords = words(
                "break case chan const continue default defer else fallthrough for func go goto if import " +
                    "interface map package range return select struct switch type var",
            ),
            literals = words("true false nil iota"),
            types = words(
                "append cap close complex copy delete imag len make new panic print println real recover int int8 " +
                    "int16 int32 int64 uint uint8 uint16 uint32 uint64 uintptr float32 float64 complex64 complex128 " +
                    "string bool byte rune error any",
            ),
            declarations = words("func type"),
            lineComments = listOf("//"),
            blockComment = cBlock,
            quotes = "\"'`",
            rawQuotes = "`",
            multilineQuotes = "`",
        ),
        "java" to Spec(
            keywords = words(
                "abstract assert break case catch class const continue default do else enum extends final finally " +
                    "for goto if implements import instanceof interface native new package private protected public " +
                    "return static strictfp super switch synchronized this throw throws transient try volatile while " +
                    "var record yield sealed permits",
            ),
            literals = words("true false null"),
            types = words("boolean byte char double float int long short void String Object Integer Long Double Boolean List Map Set"),
            declarations = words("class interface enum record"),
            lineComments = listOf("//"),
            blockComment = cBlock,
            tripleQuotes = true,
            annotations = true,
        ),
        "javascript" to Spec(
            keywords = jsKeywords,
            literals = jsLiterals,
            types = jsBuiltins,
            declarations = words("class function"),
            lineComments = listOf("//"),
            blockComment = cBlock,
            quotes = "\"'`",
            multilineQuotes = "`",
            annotations = true,
            dollarIdentifiers = true,
        ),
        "typescript" to Spec(
            keywords = jsKeywords + words(
                "interface type enum namespace module declare abstract implements private protected public " +
                    "readonly keyof infer is satisfies override",
            ),
            literals = jsLiterals,
            types = jsBuiltins + words("any unknown never string number boolean symbol bigint object void Record Partial Readonly"),
            declarations = words("class function interface type enum namespace"),
            lineComments = listOf("//"),
            blockComment = cBlock,
            quotes = "\"'`",
            multilineQuotes = "`",
            annotations = true,
            dollarIdentifiers = true,
        ),
        "json" to Spec(
            literals = words("true false null"),
            quotes = "\"",
            jsonKeys = true,
        ),
        "kotlin" to Spec(
            keywords = words(
                "package import class interface fun object val var typealias constructor by companion init this " +
                    "super typeof where if else when try catch finally for do while throw return continue break as " +
                    "is in out override open abstract final enum sealed data inner private protected public internal " +
                    "annotation lateinit const suspend inline noinline crossinline reified tailrec operator infix " +
                    "external vararg expect actual",
            ),
            literals = words("true false null"),
            types = words(
                "Int Long Short Byte Double Float Boolean Char String Unit Any Nothing Array List MutableList Map " +
                    "MutableMap Set MutableSet Sequence",
            ),
            declarations = words("class interface fun object typealias"),
            lineComments = listOf("//"),
            blockComment = cBlock,
            nestedComments = true,
            tripleQuotes = true,
            annotations = true,
        ),
        "python" to Spec(
            keywords = words(
                "and as assert async await break class continue def del elif else except finally for from global " +
                    "if import in is lambda nonlocal not or pass raise return try while with yield match case",
            ),
            literals = words("True False None"),
            types = words(
                "print len range int str float list dict set tuple bool bytes type isinstance super self cls open " +
                    "enumerate zip map filter sorted reversed min max sum abs any all object",
            ),
            declarations = words("def class"),
            lineComments = listOf("#"),
            tripleQuotes = true,
            annotations = true,
            stringPrefixes = "rbfu",
        ),
        "rust" to Spec(
            keywords = words(
                "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod " +
                    "move mut pub ref return self Self static struct super trait type unsafe use where while union",
            ),
            literals = words("true false"),
            types = words(
                "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result " +
                    "Box Rc Arc Some None Ok Err",
            ),
            declarations = words("fn struct enum trait mod type union"),
            lineComments = listOf("//"),
            blockComment = cBlock,
            nestedComments = true,
            quotes = "",
            rustSyntax = true,
        ),
        "sql" to Spec(
            keywords = words(
                "select from where insert into values update set delete create table drop alter add column index " +
                    "view join inner left right outer full cross on as and or not is in between like ilike limit " +
                    "offset order by group having distinct union all exists case when then else end primary key " +
                    "foreign references default unique check constraint begin commit rollback transaction with " +
                    "returning if replace database schema grant revoke asc desc cast over partition",
            ),
            literals = words("null true false"),
            types = words(
                "count sum avg min max coalesce int integer varchar text boolean date time timestamp serial bigint " +
                    "smallint char float real numeric decimal blob json",
            ),
            lineComments = listOf("--"),
            blockComment = cBlock,
            ignoreCase = true,
            backtickName = true,
        ),
        "swift" to Spec(
            keywords = words(
                "associatedtype class deinit enum extension fileprivate func import init inout internal let open " +
                    "operator private protocol public rethrows static struct subscript typealias var break case " +
                    "continue default defer do else fallthrough for guard if in repeat return switch where while as " +
                    "catch is super self Self throw throws try async await some any actor nonisolated mutating " +
                    "override final lazy weak unowned convenience required",
            ),
            literals = words("true false nil"),
            types = words("Int Double Float String Bool Character Array Dictionary Set Optional Void Any AnyObject Error"),
            declarations = words("class struct enum protocol extension func actor typealias"),
            lineComments = listOf("//"),
            blockComment = cBlock,
            nestedComments = true,
            quotes = "\"",
            tripleQuotes = true,
            annotations = true,
            hashDirectives = true,
        ),
    )

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun peek(t: String, i: Int): Char = if (i in t.indices) t[i] else '\u0000'

    private fun lineEnd(t: String, i: Int): Int {
        val e = t.indexOf('\n', i)
        return if (e < 0) t.length else e
    }

    private fun endAfter(t: String, close: String, from: Int): Int {
        val idx = t.indexOf(close, from)
        return if (idx < 0) t.length else idx + close.length
    }

    private fun scanString(t: String, from: Int, delim: String, escapes: Boolean, multiline: Boolean): Int {
        val n = t.length
        val first = delim[0]
        var k = from
        while (k < n) {
            val c = t[k]
            if (escapes && c == '\\') {
                k += 2
                continue
            }
            if (c == first && t.startsWith(delim, k)) return k + delim.length
            if (!multiline && c == '\n') return k
            k++
        }
        return n
    }

    private fun numberEnd(t: String, i: Int): Int {
        val n = t.length
        var j = i
        val next = peek(t, j + 1)
        if (peek(t, j) == '0' && (next == 'x' || next == 'X')) {
            j += 2
            while (j < n && (isHex(t[j]) || t[j] == '_')) j++
        } else if (peek(t, j) == '0' && (next == 'b' || next == 'B' || next == 'o' || next == 'O')) {
            j += 2
            while (j < n && (isDigit(t[j]) || t[j] == '_')) j++
        } else {
            while (j < n && (isDigit(t[j]) || t[j] == '_')) j++
            if (j < n && t[j] == '.' && isDigit(peek(t, j + 1))) {
                j++
                while (j < n && (isDigit(t[j]) || t[j] == '_')) j++
            }
            if (j < n && (t[j] == 'e' || t[j] == 'E')) {
                var k = j + 1
                if (k < n && (t[k] == '+' || t[k] == '-')) k++
                if (k < n && isDigit(t[k])) {
                    j = k
                    while (j < n && isDigit(t[j])) j++
                }
            }
        }
        while (j < n && (t[j].isLetterOrDigit() || t[j] == '_')) j++
        return if (j > i) j else i + 1
    }

    private fun atLineStart(t: String, i: Int): Boolean {
        var k = i - 1
        while (k >= 0 && (t[k] == ' ' || t[k] == '\t')) k--
        return k < 0 || t[k] == '\n'
    }

    private fun lexGeneric(t: String, spec: Spec, out: TokenSink) {
        val n = t.length
        fun identStart(c: Char) = c.isLetter() || c == '_' || (spec.dollarIdentifiers && c == '$')
        fun identPart(c: Char) = c.isLetterOrDigit() || c == '_' || (spec.dollarIdentifiers && c == '$')
        fun identEnd(from: Int): Int {
            var k = from
            while (k < n && identPart(t[k])) k++
            return k
        }
        fun quoted(at: Int, raw: Boolean): Int {
            val q = t[at]
            if (spec.tripleQuotes && peek(t, at + 1) == q && peek(t, at + 2) == q) {
                return scanString(t, at + 3, "$q$q$q", !raw, true)
            }
            return scanString(t, at + 1, q.toString(), !raw && q !in spec.rawQuotes, q in spec.multilineQuotes)
        }

        var i = 0
        var expectName = false
        while (i < n) {
            val c = t[i]
            if (c.isWhitespace()) {
                i++
                continue
            }

            if (spec.promptMeta && (c == '$' || c == '>') && peek(t, i + 1) == ' ' && atLineStart(t, i)) {
                out.add(i, i + 1, CodeTokenKind.Meta)
                i += 2
                continue
            }

            var lineComment = false
            for (marker in spec.lineComments) {
                if (t.startsWith(marker, i)) {
                    val boundary = !spec.hashCommentNeedsBoundary || marker != "#" || i == 0 ||
                        t[i - 1].isWhitespace() || t[i - 1] == ';'
                    if (boundary) {
                        lineComment = true
                        break
                    }
                }
            }
            if (lineComment) {
                val e = lineEnd(t, i)
                out.add(i, e, CodeTokenKind.Comment)
                i = e
                expectName = false
                continue
            }

            val block = spec.blockComment
            if (block != null && t.startsWith(block.first, i)) {
                val e = if (spec.nestedComments) nestedEnd(t, i, block.first, block.second) else endAfter(t, block.second, i + block.first.length)
                out.add(i, e, CodeTokenKind.Comment)
                i = e
                continue
            }

            if (spec.rustSyntax && c == '#' && (peek(t, i + 1) == '[' || (peek(t, i + 1) == '!' && peek(t, i + 2) == '['))) {
                val e = bracketEnd(t, i)
                out.add(i, e, CodeTokenKind.Meta)
                i = e
                expectName = false
                continue
            }

            if (spec.hashDirectives && c == '#' && identStart(peek(t, i + 1))) {
                val e = identEnd(i + 1)
                out.add(i, e, CodeTokenKind.Meta)
                i = e
                expectName = false
                continue
            }

            if (spec.annotations && c == '@' && identStart(peek(t, i + 1))) {
                var e = identEnd(i + 1)
                while (peek(t, e) == '.' && identStart(peek(t, e + 1))) e = identEnd(e + 1)
                out.add(i, e, CodeTokenKind.Meta)
                i = e
                expectName = false
                continue
            }

            if (spec.shellVariables && c == '$') {
                val nx = peek(t, i + 1)
                val e = when {
                    nx == '{' -> {
                        var k = i + 2
                        while (k < n && t[k] != '}' && t[k] != '\n') k++
                        if (k < n && t[k] == '}') k + 1 else k
                    }
                    identStart(nx) -> identEnd(i + 1)
                    isDigit(nx) || nx in "@#?*!$-" -> i + 2
                    else -> i + 1
                }
                if (e > i + 1) out.add(i, e, CodeTokenKind.Name)
                i = e
                expectName = false
                continue
            }

            if (spec.backtickName && c == '`') {
                val e = scanString(t, i + 1, "`", false, false)
                out.add(i, e, CodeTokenKind.Name)
                i = e
                continue
            }

            if (spec.rustSyntax) {
                val e = rustLiteral(t, i)
                if (e > i) {
                    out.add(i, e, CodeTokenKind.String)
                    i = e
                    expectName = false
                    continue
                }
                if (c == '\'') {
                    i = if (identStart(peek(t, i + 1))) identEnd(i + 1) else i + 1
                    continue
                }
            }

            if (spec.stringPrefixes.isNotEmpty() && c.lowercaseChar() in spec.stringPrefixes) {
                var j = i
                while (j < n && j - i < 2 && t[j].lowercaseChar() in spec.stringPrefixes) j++
                if (j < n && t[j] in spec.quotes) {
                    val raw = (i until j).any { t[it] == 'r' || t[it] == 'R' }
                    val e = quoted(j, raw)
                    out.add(i, e, CodeTokenKind.String)
                    i = e
                    expectName = false
                    continue
                }
            }

            if (c in spec.quotes) {
                val e = quoted(i, false)
                var kind = CodeTokenKind.String
                if (spec.jsonKeys) {
                    var k = e
                    while (k < n && (t[k] == ' ' || t[k] == '\t')) k++
                    if (k < n && t[k] == ':') kind = CodeTokenKind.Name
                }
                out.add(i, e, kind)
                i = e
                expectName = false
                continue
            }

            if (isDigit(c) || (c == '.' && isDigit(peek(t, i + 1)))) {
                val e = numberEnd(t, i)
                out.add(i, e, CodeTokenKind.Number)
                i = e
                expectName = false
                continue
            }

            if (identStart(c)) {
                var e = identEnd(i)
                val word = t.substring(i, e)
                val key = if (spec.ignoreCase) word.lowercase() else word
                if (spec.rustSyntax && peek(t, e) == '!' && peek(t, e + 1) != '=') {
                    e++
                    out.add(i, e, CodeTokenKind.Keyword)
                    expectName = false
                } else if (key in spec.keywords) {
                    out.add(i, e, CodeTokenKind.Keyword)
                    expectName = key in spec.declarations
                } else if (key in spec.literals || key in spec.types) {
                    out.add(i, e, CodeTokenKind.Keyword)
                    expectName = false
                } else if (expectName) {
                    out.add(i, e, CodeTokenKind.Name)
                    expectName = false
                }
                i = e
                continue
            }

            expectName = false
            i++
        }
    }

    private fun nestedEnd(t: String, i: Int, open: String, close: String): Int {
        val n = t.length
        var depth = 0
        var k = i
        while (k < n) {
            if (t.startsWith(open, k)) {
                depth++
                k += open.length
                continue
            }
            if (t.startsWith(close, k)) {
                depth--
                k += close.length
                if (depth <= 0) return k
                continue
            }
            k++
        }
        return n
    }

    private fun bracketEnd(t: String, i: Int): Int {
        val n = t.length
        var depth = 0
        var k = i
        while (k < n) {
            val c = t[k]
            if (c == '\n') return k
            if (c == '[') depth++
            if (c == ']') {
                depth--
                if (depth <= 0) return k + 1
            }
            k++
        }
        return n
    }

    private fun charLiteral(t: String, i: Int): Int {
        val n = t.length
        if (peek(t, i + 1) == '\\') {
            val limit = minOf(n, i + 12)
            var k = i + 2
            while (k < limit && t[k] != '\'' && t[k] != '\n') k++
            return if (k < limit && t[k] == '\'') k + 1 else -1
        }
        val c = peek(t, i + 1)
        if (c == '\'' || c == '\n' || c == '\u0000') return -1
        val width = if (c.isHighSurrogate()) 2 else 1
        return if (peek(t, i + 1 + width) == '\'') i + 2 + width else -1
    }

    private fun rustLiteral(t: String, i: Int): Int {
        val n = t.length
        var j = i
        if (t[j] == 'b') j++
        if (j < n && t[j] == 'r') {
            var k = j + 1
            var hashes = 0
            while (k < n && t[k] == '#' && hashes <= 255) {
                hashes++
                k++
            }
            if (hashes <= 255 && k < n && t[k] == '"') {
                return endAfter(t, "\"" + "#".repeat(hashes), k + 1)
            }
            return i
        }
        if (j > i) {
            if (j < n && t[j] == '"') return scanString(t, j + 1, "\"", true, true)
            if (j < n && t[j] == '\'') {
                val e = charLiteral(t, j)
                return if (e > j) e else i
            }
            return i
        }
        if (t[i] == '"') return scanString(t, i + 1, "\"", true, true)
        if (t[i] == '\'') {
            val e = charLiteral(t, i)
            return if (e > i) e else i
        }
        return i
    }

    private fun lexDiff(t: String, out: TokenSink) {
        var i = 0
        val n = t.length
        while (i < n) {
            val e = lineEnd(t, i)
            val kind = when {
                t.startsWith("@@", i) -> CodeTokenKind.Meta
                t.startsWith("+++", i) || t.startsWith("---", i) || t.startsWith("diff ", i) ||
                    t.startsWith("index ", i) || t.startsWith("Index:", i) || t.startsWith("===", i) -> CodeTokenKind.Comment
                t[i] == '+' || t[i] == '>' -> CodeTokenKind.String
                t[i] == '-' || t[i] == '<' -> CodeTokenKind.Meta
                else -> null
            }
            if (kind != null) out.add(i, e, kind)
            i = e + 1
        }
    }

    private val iniLiterals = words("true false on off yes no null")

    private fun lexIni(t: String, out: TokenSink) {
        val n = t.length
        var i = 0
        while (i < n) {
            val e = lineEnd(t, i)
            var p = i
            while (p < e && t[p].isWhitespace()) p++
            if (p < e) {
                val c = t[p]
                if (c == ';' || c == '#') {
                    out.add(p, e, CodeTokenKind.Comment)
                } else if (c == '[') {
                    var k = p + 1
                    while (k < e && t[k] != ']') k++
                    val close = if (k < e) k + 1 else e
                    out.add(p, close, CodeTokenKind.Meta)
                    lexIniValue(t, close, e, out)
                } else {
                    var eq = p
                    while (eq < e && t[eq] != '=' && t[eq] != '"' && t[eq] != '\'') eq++
                    if (eq < e && t[eq] == '=') {
                        var keyEnd = eq
                        while (keyEnd > p && t[keyEnd - 1].isWhitespace()) keyEnd--
                        out.add(p, keyEnd, CodeTokenKind.Name)
                        lexIniValue(t, eq + 1, e, out)
                    } else {
                        lexIniValue(t, p, e, out)
                    }
                }
            }
            i = e + 1
        }
    }

    private fun lexIniValue(t: String, from: Int, end: Int, out: TokenSink) {
        var k = from
        while (k < end) {
            val c = t[k]
            when {
                c.isWhitespace() -> k++
                c == ';' || c == '#' -> {
                    out.add(k, end, CodeTokenKind.Comment)
                    k = end
                }
                c == '"' || c == '\'' -> {
                    val e = minOf(scanString(t, k + 1, c.toString(), c == '"', false), end)
                    out.add(k, e, CodeTokenKind.String)
                    k = e
                }
                isDigit(c) || ((c == '-' || c == '+') && isDigit(peek(t, k + 1))) -> {
                    val start = k
                    val e = minOf(numberEnd(t, if (c == '-' || c == '+') k + 1 else k), end)
                    out.add(start, e, CodeTokenKind.Number)
                    k = e
                }
                c.isLetter() -> {
                    var e = k
                    while (e < end && (t[e].isLetterOrDigit() || t[e] == '_' || t[e] == '-' || t[e] == '.')) e++
                    if (t.substring(k, e).lowercase() in iniLiterals) out.add(k, e, CodeTokenKind.Keyword)
                    k = e
                }
                else -> k++
            }
        }
    }

    private val yamlLiterals = words("true false null yes no on off ~")

    private fun lexYaml(t: String, out: TokenSink) {
        val n = t.length
        var i = 0
        while (i < n) {
            val e = lineEnd(t, i)
            lexYamlLine(t, i, e, out)
            i = e + 1
        }
    }

    private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r'

    private fun lexYamlLine(t: String, s: Int, e: Int, out: TokenSink) {
        var p = s
        if ((t.startsWith("---", s) || t.startsWith("...", s)) && (s + 3 >= e || isBlank(t[s + 3]))) {
            out.add(s, s + 3, CodeTokenKind.Meta)
            p = s + 3
        }
        while (p < e && isBlank(t[p])) p++
        if (p >= e) return
        if (t[p] == '#') {
            out.add(p, e, CodeTokenKind.Comment)
            return
        }
        while (p < e && t[p] == '-' && (p + 1 >= e || isBlank(t[p + 1]))) {
            out.add(p, p + 1, CodeTokenKind.Number)
            p++
            while (p < e && isBlank(t[p])) p++
        }
        if (p >= e) return
        if (t[p] == '#') {
            out.add(p, e, CodeTokenKind.Comment)
            return
        }
        val c = t[p]
        if (c == '"' || c == '\'') {
            val close = minOf(scanString(t, p + 1, c.toString(), c == '"', false), e)
            var k = close
            while (k < e && isBlank(t[k])) k++
            if (k < e && t[k] == ':' && (k + 1 >= e || isBlank(t[k + 1]))) {
                out.add(p, close, CodeTokenKind.Name)
                lexYamlValue(t, k + 1, e, out)
                return
            }
        } else if (c !in "[{|>&*!%@`") {
            var k = p
            while (k < e) {
                val ch = t[k]
                if (ch == ':' && (k + 1 >= e || isBlank(t[k + 1]))) {
                    var keyEnd = k
                    while (keyEnd > p && isBlank(t[keyEnd - 1])) keyEnd--
                    out.add(p, keyEnd, CodeTokenKind.Name)
                    lexYamlValue(t, k + 1, e, out)
                    return
                }
                if (ch == '#' && k > p && isBlank(t[k - 1])) break
                k++
            }
        }
        lexYamlValue(t, p, e, out)
    }

    private fun lexYamlValue(t: String, from: Int, e: Int, out: TokenSink) {
        var p = from
        while (p < e) {
            val c = t[p]
            when {
                isBlank(c) -> p++
                c == '#' -> {
                    out.add(p, e, CodeTokenKind.Comment)
                    return
                }
                c == '"' || c == '\'' -> {
                    val close = minOf(scanString(t, p + 1, c.toString(), c == '"', false), e)
                    out.add(p, close, CodeTokenKind.String)
                    p = close
                }
                c == '&' || c == '*' || c == '!' -> {
                    var k = p + 1
                    while (k < e && !isBlank(t[k])) k++
                    out.add(p, k, CodeTokenKind.Meta)
                    p = k
                }
                c == '|' || c == '>' -> {
                    var k = p + 1
                    while (k < e && (t[k] == '-' || t[k] == '+' || isDigit(t[k]))) k++
                    out.add(p, k, CodeTokenKind.Meta)
                    p = k
                }
                else -> {
                    var k = p
                    while (k < e && !(t[k] == '#' && k > p && isBlank(t[k - 1]))) k++
                    var end = k
                    while (end > p && isBlank(t[end - 1])) end--
                    val word = t.substring(p, end)
                    if (word.lowercase() in yamlLiterals) {
                        out.add(p, end, CodeTokenKind.Keyword)
                    } else {
                        val numberStart = if (c == '-' || c == '+') p + 1 else p
                        if (numberStart < end && (isDigit(t[numberStart]) || t[numberStart] == '.') && numberEnd(t, numberStart) == end) {
                            out.add(p, end, CodeTokenKind.Number)
                        }
                    }
                    p = k
                }
            }
        }
    }

    private class Finder(private val t: String, private val end: Int, private val ch: Char) {
        private var scanned = -1
        private var found = -1

        fun next(from: Int): Int {
            if (found >= from) return found
            var k = maxOf(from, scanned)
            while (k < end && t[k] != ch) k++
            scanned = k
            found = if (k < end) k else -1
            return found
        }
    }

    private fun lexMarkdown(t: String, out: TokenSink) {
        val n = t.length
        var i = 0
        var fence: String? = null
        while (i < n) {
            val e = lineEnd(t, i)
            var p = i
            while (p < e && isBlank(t[p])) p++
            val opensFence = t.startsWith("```", p) || t.startsWith("~~~", p)
            when {
                fence != null -> if (t.startsWith(fence, p)) fence = null
                opensFence -> fence = t.substring(p, p + 3)
                p >= e -> Unit
                t[p] == '#' -> {
                    var h = p
                    while (h < e && t[h] == '#') h++
                    if (h - p <= 6 && (h >= e || isBlank(t[h]))) out.add(p, e, CodeTokenKind.Meta) else markdownInline(t, p, e, out)
                }
                t[p] == '>' -> out.add(p, e, CodeTokenKind.String)
                isRule(t, p, e) -> out.add(p, e, CodeTokenKind.Meta)
                else -> {
                    var q = p
                    if (t[q] in "-*+" && q + 1 < e && isBlank(t[q + 1])) {
                        out.add(q, q + 1, CodeTokenKind.Number)
                        q += 2
                    } else if (isDigit(t[q])) {
                        var d = q
                        while (d < e && d - q < 9 && isDigit(t[d])) d++
                        if (d < e && (t[d] == '.' || t[d] == ')') && (d + 1 >= e || isBlank(t[d + 1]))) {
                            out.add(q, d + 1, CodeTokenKind.Number)
                            q = d + 1
                        }
                    }
                    markdownInline(t, q, e, out)
                }
            }
            i = e + 1
        }
    }

    private fun isRule(t: String, p: Int, e: Int): Boolean {
        val c = t[p]
        if (c != '-' && c != '*' && c != '_') return false
        var count = 0
        for (k in p until e) {
            when {
                t[k] == c -> count++
                isBlank(t[k]) -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    private fun markdownInline(t: String, from: Int, e: Int, out: TokenSink) {
        val ticks = Finder(t, e, '`')
        val closeBrackets = Finder(t, e, ']')
        val closeParens = Finder(t, e, ')')
        var k = from
        while (k < e) {
            val c = t[k]
            if (c == '`') {
                val close = ticks.next(k + 1)
                if (close < 0) return
                k = close + 1
                continue
            }
            if (c == '[') {
                val close = closeBrackets.next(k + 1)
                if (close < 0) return
                if (close + 1 < e && t[close + 1] == '(') {
                    val paren = closeParens.next(close + 2)
                    if (paren >= 0) {
                        out.add(k, close + 1, CodeTokenKind.String)
                        out.add(close + 1, paren + 1, CodeTokenKind.Meta)
                        k = paren + 1
                        continue
                    }
                }
            }
            k++
        }
    }

    private fun isXmlNameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == ':'

    private fun isXmlNamePart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == ':' || c == '-' || c == '.'

    private fun lexXml(t: String, out: TokenSink) {
        val n = t.length
        var i = 0
        while (i < n) {
            val lt = t.indexOf('<', i)
            if (lt < 0) return
            i = lt
            when {
                t.startsWith("<!--", i) -> {
                    val e = endAfter(t, "-->", i + 4)
                    out.add(i, e, CodeTokenKind.Comment)
                    i = e
                }
                t.startsWith("<![CDATA[", i) -> {
                    val e = endAfter(t, "]]>", i + 9)
                    out.add(i, e, CodeTokenKind.String)
                    i = e
                }
                t.startsWith("<?", i) -> {
                    val e = endAfter(t, "?>", i + 2)
                    out.add(i, e, CodeTokenKind.Meta)
                    i = e
                }
                t.startsWith("<!", i) -> {
                    val e = endAfter(t, ">", i + 2)
                    out.add(i, e, CodeTokenKind.Meta)
                    i = e
                }
                else -> {
                    var j = i + 1
                    if (peek(t, j) == '/') j++
                    if (!isXmlNameStart(peek(t, j))) {
                        i++
                        continue
                    }
                    val nameStart = j
                    while (j < n && isXmlNamePart(t[j])) j++
                    out.add(nameStart, j, CodeTokenKind.Name)
                    while (j < n) {
                        val c = t[j]
                        if (c == '>') {
                            j++
                            break
                        }
                        if (c == '<') break
                        if (c == '"' || c == '\'') {
                            val e = endAfter(t, c.toString(), j + 1)
                            out.add(j, e, CodeTokenKind.String)
                            j = e
                            continue
                        }
                        if (c == '=') {
                            j++
                            while (j < n && t[j].isWhitespace()) j++
                            if (j < n && t[j] != '"' && t[j] != '\'' && t[j] != '>' && t[j] != '<') {
                                val s = j
                                while (j < n && !t[j].isWhitespace() && t[j] != '>' && t[j] != '<') j++
                                out.add(s, j, CodeTokenKind.String)
                            }
                            continue
                        }
                        if (isXmlNameStart(c)) {
                            val s = j
                            while (j < n && isXmlNamePart(t[j])) j++
                            out.add(s, j, CodeTokenKind.Name)
                            continue
                        }
                        j++
                    }
                    i = j
                }
            }
        }
    }

    private fun isCssIdentStart(t: String, i: Int): Boolean {
        val c = peek(t, i)
        if (c.isLetter() || c == '_') return true
        if (c == '-') {
            val d = peek(t, i + 1)
            return d.isLetter() || d == '_' || d == '-'
        }
        return false
    }

    private fun cssIdentEnd(t: String, i: Int): Int {
        var k = i
        while (k < t.length && (t[k].isLetterOrDigit() || t[k] == '-' || t[k] == '_')) k++
        return k
    }

    private fun cssStatementIsSelector(t: String, from: Int): Boolean {
        var k = from
        while (k < t.length) {
            when (t[k]) {
                '{' -> return true
                ';', '}' -> return false
            }
            k++
        }
        return false
    }

    private fun lexCss(t: String, out: TokenSink) {
        val n = t.length
        var i = 0
        var statementStart = true
        var selector = false
        while (i < n) {
            val c = t[i]
            if (statementStart && !c.isWhitespace() && !t.startsWith("/*", i)) {
                selector = cssStatementIsSelector(t, i)
                statementStart = false
            }
            val depth = if (selector) 0 else 1
            when {
                c.isWhitespace() -> i++
                t.startsWith("/*", i) -> {
                    val e = endAfter(t, "*/", i + 2)
                    out.add(i, e, CodeTokenKind.Comment)
                    i = e
                }
                c == '"' || c == '\'' -> {
                    val e = scanString(t, i + 1, c.toString(), true, false)
                    out.add(i, e, CodeTokenKind.String)
                    i = e
                }
                c == '{' || c == '}' || c == ';' -> {
                    statementStart = true
                    i++
                }
                c == '@' && isCssIdentStart(t, i + 1) -> {
                    val e = cssIdentEnd(t, i + 1)
                    out.add(i, e, CodeTokenKind.Keyword)
                    i = e
                }
                c == '!' && t.regionMatches(i + 1, "important", 0, 9, ignoreCase = true) -> {
                    out.add(i, i + 10, CodeTokenKind.Meta)
                    i += 10
                }
                depth == 0 && (c == '.' || c == '#') && isCssIdentStart(t, i + 1) -> {
                    val e = cssIdentEnd(t, i + 1)
                    out.add(i, e, CodeTokenKind.Name)
                    i = e
                }
                depth == 0 && c == ':' -> {
                    var j = i + 1
                    if (peek(t, j) == ':') j++
                    i = if (isCssIdentStart(t, j)) cssIdentEnd(t, j) else j
                }
                depth == 0 && c == '[' -> {
                    var j = i + 1
                    while (j < n && t[j] != ']' && t[j] != '\n' && t[j] != '{') j++
                    i = if (j < n && t[j] == ']') j + 1 else j
                }
                depth > 0 && c == '#' && isHex(peek(t, i + 1)) -> {
                    var j = i + 1
                    while (j < n && t[j].isLetterOrDigit()) j++
                    out.add(i, j, CodeTokenKind.Number)
                    i = j
                }
                isDigit(c) || (c == '.' && isDigit(peek(t, i + 1))) ||
                    (c == '-' && (isDigit(peek(t, i + 1)) || (peek(t, i + 1) == '.' && isDigit(peek(t, i + 2))))) -> {
                    var e = numberEnd(t, if (c == '-') i + 1 else i)
                    if (peek(t, e) == '%') e++
                    out.add(i, e, CodeTokenKind.Number)
                    i = e
                }
                isCssIdentStart(t, i) -> {
                    val e = cssIdentEnd(t, i)
                    if (depth == 0) {
                        out.add(i, e, CodeTokenKind.Keyword)
                    } else {
                        var k = e
                        while (k < n && (t[k] == ' ' || t[k] == '\t')) k++
                        if (k < n && t[k] == ':') out.add(i, e, CodeTokenKind.Name)
                    }
                    i = e
                }
                else -> i++
            }
        }
    }
}
