package com.ftapps.remft.domain.notes.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CodeHighlighterTest {
    private val languages = listOf(
        "bash", "css", "dart", "diff", "go", "ini", "java", "javascript", "json", "kotlin", "markdown",
        "python", "rust", "shell", "sql", "swift", "typescript", "xml", "yaml",
    )

    private fun kinds(code: String, language: String): List<Pair<String, CodeTokenKind>> =
        CodeHighlighter.highlight(code, language).map { code.substring(it.start, it.end) to it.kind }

    private fun assertHas(code: String, language: String, text: String, kind: CodeTokenKind) {
        val found = kinds(code, language)
        assertTrue("$language: expected '$text' as $kind in $found", found.contains(text to kind))
    }

    private fun assertValid(code: String, tokens: List<CodeToken>) {
        var previousEnd = 0
        for (token in tokens) {
            assertTrue(token.start >= previousEnd)
            assertTrue(token.end > token.start)
            assertTrue(token.end <= code.length)
            previousEnd = token.end
        }
    }

    @Test
    fun resolvesLanguagesAndAliases() {
        for (language in languages) assertEquals(language, CodeHighlighter.languageOf(language))
        assertEquals("bash", CodeHighlighter.languageOf("sh"))
        assertEquals("bash", CodeHighlighter.languageOf("zsh"))
        assertEquals("shell", CodeHighlighter.languageOf("console"))
        assertEquals("javascript", CodeHighlighter.languageOf("jsx"))
        assertEquals("typescript", CodeHighlighter.languageOf("TSX"))
        assertEquals("python", CodeHighlighter.languageOf("py"))
        assertEquals("rust", CodeHighlighter.languageOf("rs"))
        assertEquals("kotlin", CodeHighlighter.languageOf("kt"))
        assertEquals("kotlin", CodeHighlighter.languageOf("kts"))
        assertEquals("yaml", CodeHighlighter.languageOf("yml"))
        assertEquals("xml", CodeHighlighter.languageOf("html"))
        assertEquals("xml", CodeHighlighter.languageOf("svg"))
        assertEquals("ini", CodeHighlighter.languageOf("toml"))
        assertEquals("diff", CodeHighlighter.languageOf("patch"))
        assertEquals("markdown", CodeHighlighter.languageOf("md"))
        assertEquals("kotlin", CodeHighlighter.languageOf("  kotlin title=\"x\""))
        assertEquals("json", CodeHighlighter.languageOf("json,linenums"))
        assertNull(CodeHighlighter.languageOf(null))
        assertNull(CodeHighlighter.languageOf("   "))
        assertNull(CodeHighlighter.languageOf("brainfuck"))
    }

    @Test
    fun unknownLanguageIsEmpty() {
        assertEquals(emptyList<CodeToken>(), CodeHighlighter.highlight("val x = 1", "cobol"))
        assertEquals(emptyList<CodeToken>(), CodeHighlighter.highlight("val x = 1", null))
        assertEquals(emptyList<CodeToken>(), CodeHighlighter.highlight("", "kotlin"))
    }

    @Test
    fun kotlin() {
        val code = "@Composable\nfun greet(name: String): Int {\n    // hi\n    val s = \"hey \$name\"\n    return 0x1F + 2.5f\n}\n/* block */ class Box"
        assertHas(code, "kotlin", "@Composable", CodeTokenKind.Meta)
        assertHas(code, "kotlin", "fun", CodeTokenKind.Keyword)
        assertHas(code, "kotlin", "greet", CodeTokenKind.Name)
        assertHas(code, "kotlin", "String", CodeTokenKind.Keyword)
        assertHas(code, "kotlin", "// hi", CodeTokenKind.Comment)
        assertHas(code, "kotlin", "\"hey \$name\"", CodeTokenKind.String)
        assertHas(code, "kotlin", "0x1F", CodeTokenKind.Number)
        assertHas(code, "kotlin", "2.5f", CodeTokenKind.Number)
        assertHas(code, "kotlin", "/* block */", CodeTokenKind.Comment)
        assertHas(code, "kotlin", "Box", CodeTokenKind.Name)
        assertHas("val t = \"\"\"a\n\"b\"\n\"\"\"", "kotlin", "\"\"\"a\n\"b\"\n\"\"\"", CodeTokenKind.String)
    }

    @Test
    fun python() {
        val code = "@decorator\ndef run(x):\n    # note\n    return f\"v{x}\" + r'\\d' + \"\"\"doc\"\"\" if True else None"
        assertHas(code, "python", "@decorator", CodeTokenKind.Meta)
        assertHas(code, "python", "def", CodeTokenKind.Keyword)
        assertHas(code, "python", "run", CodeTokenKind.Name)
        assertHas(code, "python", "# note", CodeTokenKind.Comment)
        assertHas(code, "python", "f\"v{x}\"", CodeTokenKind.String)
        assertHas(code, "python", "r'\\d'", CodeTokenKind.String)
        assertHas(code, "python", "\"\"\"doc\"\"\"", CodeTokenKind.String)
        assertHas(code, "python", "True", CodeTokenKind.Keyword)
    }

    @Test
    fun javascriptAndTypescript() {
        val js = "const a = `t \${b}`; function go() { return 'x' /* c */ }"
        assertHas(js, "js", "const", CodeTokenKind.Keyword)
        assertHas(js, "js", "`t \${b}`", CodeTokenKind.String)
        assertHas(js, "js", "go", CodeTokenKind.Name)
        assertHas(js, "js", "'x'", CodeTokenKind.String)
        assertHas(js, "js", "/* c */", CodeTokenKind.Comment)
        val ts = "interface Shape { size: number }\ntype Id = string"
        assertHas(ts, "ts", "interface", CodeTokenKind.Keyword)
        assertHas(ts, "ts", "Shape", CodeTokenKind.Name)
        assertHas(ts, "ts", "number", CodeTokenKind.Keyword)
        assertHas(ts, "ts", "Id", CodeTokenKind.Name)
    }

    @Test
    fun rust() {
        val code = "#[derive(Debug)]\nfn main<'a>() { let c = 'x'; let s = r#\"raw \"q\"\"#; println!(\"{}\", 42u32); }"
        assertHas(code, "rust", "#[derive(Debug)]", CodeTokenKind.Meta)
        assertHas(code, "rust", "fn", CodeTokenKind.Keyword)
        assertHas(code, "rust", "main", CodeTokenKind.Name)
        assertHas(code, "rust", "'x'", CodeTokenKind.String)
        assertHas(code, "rust", "r#\"raw \"q\"\"#", CodeTokenKind.String)
        assertHas(code, "rust", "println!", CodeTokenKind.Keyword)
        assertHas(code, "rust", "42u32", CodeTokenKind.Number)
        assertTrue(kinds(code, "rust").none { it.first.startsWith("'a") })
    }

    @Test
    fun goJavaDartSwiftSql() {
        assertHas("func main() { s := `raw\nline` }", "go", "`raw\nline`", CodeTokenKind.String)
        assertHas("func main() {}", "go", "main", CodeTokenKind.Name)
        assertHas("@Override public class A {}", "java", "@Override", CodeTokenKind.Meta)
        assertHas("@Override public class A {}", "java", "A", CodeTokenKind.Name)
        assertHas("final s = r'\\n'; class W {}", "dart", "r'\\n'", CodeTokenKind.String)
        assertHas("final s = r'\\n'; class W {}", "dart", "W", CodeTokenKind.Name)
        assertHas("#if DEBUG\nstruct V {}\n#endif", "swift", "#if", CodeTokenKind.Meta)
        assertHas("#if DEBUG\nstruct V {}\n#endif", "swift", "V", CodeTokenKind.Name)
        val sql = "SELECT name FROM `users` WHERE id = 3 -- tail\n/* b */"
        assertHas(sql, "sql", "SELECT", CodeTokenKind.Keyword)
        assertHas(sql, "sql", "FROM", CodeTokenKind.Keyword)
        assertHas(sql, "sql", "`users`", CodeTokenKind.Name)
        assertHas(sql, "sql", "3", CodeTokenKind.Number)
        assertHas(sql, "sql", "-- tail", CodeTokenKind.Comment)
    }

    @Test
    fun bashAndShell() {
        val code = "#!/bin/sh\nif [ -n \"\$HOME\" ]; then echo 'hi' # c\nfi\necho \${PATH} \$1 a#b"
        assertHas(code, "bash", "#!/bin/sh", CodeTokenKind.Comment)
        assertHas(code, "bash", "if", CodeTokenKind.Keyword)
        assertHas(code, "bash", "\"\$HOME\"", CodeTokenKind.String)
        assertHas(code, "bash", "'hi'", CodeTokenKind.String)
        assertHas(code, "bash", "# c", CodeTokenKind.Comment)
        assertHas(code, "bash", "\${PATH}", CodeTokenKind.Name)
        assertHas(code, "bash", "\$1", CodeTokenKind.Name)
        assertTrue(kinds(code, "bash").none { it.first == "#b" })
        assertHas("$ npm install\nok", "console", "$", CodeTokenKind.Meta)
    }

    @Test
    fun json() {
        val code = "{\"name\": \"x\", \"n\": -1.5e3, \"ok\": true, \"none\": null}"
        assertHas(code, "json", "\"name\"", CodeTokenKind.Name)
        assertHas(code, "json", "\"x\"", CodeTokenKind.String)
        assertHas(code, "json", "1.5e3", CodeTokenKind.Number)
        assertHas(code, "json", "true", CodeTokenKind.Keyword)
        assertHas(code, "json", "null", CodeTokenKind.Keyword)
    }

    @Test
    fun yaml() {
        val code = "---\n# top\nname: app\nversion: 1.2\nlist:\n  - item # c\n  - \"quoted\"\nflag: true\nref: &anchor x\n"
        assertHas(code, "yaml", "---", CodeTokenKind.Meta)
        assertHas(code, "yaml", "# top", CodeTokenKind.Comment)
        assertHas(code, "yaml", "name", CodeTokenKind.Name)
        assertHas(code, "yaml", "1.2", CodeTokenKind.Number)
        assertHas(code, "yaml", "-", CodeTokenKind.Number)
        assertHas(code, "yaml", "# c", CodeTokenKind.Comment)
        assertHas(code, "yaml", "\"quoted\"", CodeTokenKind.String)
        assertHas(code, "yaml", "true", CodeTokenKind.Keyword)
        assertHas(code, "yaml", "&anchor", CodeTokenKind.Meta)
    }

    @Test
    fun iniAndToml() {
        val code = "; comment\n[server]\nport = 8080\nname = \"main\"\nenabled = true # on\n"
        assertHas(code, "ini", "; comment", CodeTokenKind.Comment)
        assertHas(code, "ini", "[server]", CodeTokenKind.Meta)
        assertHas(code, "toml", "port", CodeTokenKind.Name)
        assertHas(code, "toml", "8080", CodeTokenKind.Number)
        assertHas(code, "toml", "\"main\"", CodeTokenKind.String)
        assertHas(code, "toml", "true", CodeTokenKind.Keyword)
        assertHas(code, "toml", "# on", CodeTokenKind.Comment)
    }

    @Test
    fun diff() {
        val code = "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-old\n+new\n same"
        assertHas(code, "diff", "@@ -1,2 +1,2 @@", CodeTokenKind.Meta)
        assertHas(code, "diff", "-old", CodeTokenKind.Meta)
        assertHas(code, "diff", "+new", CodeTokenKind.String)
        assertHas(code, "diff", "--- a/x", CodeTokenKind.Comment)
        assertTrue(kinds(code, "diff").none { it.first.contains("same") })
    }

    @Test
    fun xmlAndHtml() {
        val code = "<?xml version=\"1.0\"?>\n<!-- c -->\n<a href=\"/x\" data-id=7>text</a>\n<![CDATA[raw]]>"
        assertHas(code, "xml", "<?xml version=\"1.0\"?>", CodeTokenKind.Meta)
        assertHas(code, "xml", "<!-- c -->", CodeTokenKind.Comment)
        assertHas(code, "html", "a", CodeTokenKind.Name)
        assertHas(code, "html", "href", CodeTokenKind.Name)
        assertHas(code, "html", "\"/x\"", CodeTokenKind.String)
        assertHas(code, "html", "7", CodeTokenKind.String)
        assertHas(code, "html", "<![CDATA[raw]]>", CodeTokenKind.String)
        assertTrue(kinds(code, "html").none { it.first == "text" })
    }

    @Test
    fun css() {
        val code = "@media screen { .card #main div:hover { color: #ff0000; margin: -4px 1.5em !important; } } /* x */ a { content: \"q\" }"
        assertHas(code, "css", "@media", CodeTokenKind.Keyword)
        assertHas(code, "css", ".card", CodeTokenKind.Name)
        assertHas(code, "css", "#main", CodeTokenKind.Name)
        assertHas(code, "css", "color", CodeTokenKind.Name)
        assertHas(code, "css", "#ff0000", CodeTokenKind.Number)
        assertHas(code, "css", "-4px", CodeTokenKind.Number)
        assertHas(code, "css", "1.5em", CodeTokenKind.Number)
        assertHas(code, "css", "!important", CodeTokenKind.Meta)
        assertHas(code, "css", "/* x */", CodeTokenKind.Comment)
        assertHas(code, "css", "\"q\"", CodeTokenKind.String)
    }

    @Test
    fun markdown() {
        val code = "# Title\n> quote\n- item [link](http://x)\n1. one\n```\n# not heading\n```\n---"
        assertHas(code, "markdown", "# Title", CodeTokenKind.Meta)
        assertHas(code, "markdown", "> quote", CodeTokenKind.String)
        assertHas(code, "markdown", "-", CodeTokenKind.Number)
        assertHas(code, "markdown", "[link]", CodeTokenKind.String)
        assertHas(code, "markdown", "(http://x)", CodeTokenKind.Meta)
        assertHas(code, "markdown", "1.", CodeTokenKind.Number)
        assertHas(code, "markdown", "---", CodeTokenKind.Meta)
        assertTrue(kinds(code, "markdown").none { it.first == "# not heading" })
    }

    @Test
    fun fuzzKeepsTokensSortedDisjointAndInBounds() {
        val random = Random(20261122)
        val alphabet = "abcfnrtxyz019_ \n\t\"'`#/*-+=:;.,()[]{}<>!?@\$%&|\\~rbRé中😀"
        repeat(400) {
            val length = random.nextInt(0, 300)
            val code = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
            for (language in languages) {
                assertValid(code, CodeHighlighter.highlight(code, language))
            }
        }
    }

    @Test
    fun pathologicalInputsFinishQuickly() {
        val big = 100_000
        val inputs = listOf(
            "\"" + "a".repeat(big),
            "/*" + "x".repeat(big),
            "'" + "\\".repeat(big),
            "\"\"\"" + "\"".repeat(big),
            "r" + "#".repeat(big) + "\"",
            "<" + "a ".repeat(big / 2),
            "<!--" + "-".repeat(big),
            "[".repeat(big) + "]",
            "`".repeat(big),
            " ".repeat(big) + "$",
            "a: ".repeat(big / 3),
            "x = ".repeat(big / 4),
            "{".repeat(big),
            "#".repeat(big),
            "-".repeat(big),
            "1e".repeat(big / 2),
            "@".repeat(big),
            "\n".repeat(big),
        )
        val started = System.nanoTime()
        for (code in inputs) {
            for (language in languages) {
                assertValid(code, CodeHighlighter.highlight(code, language))
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 20)
    }
}
