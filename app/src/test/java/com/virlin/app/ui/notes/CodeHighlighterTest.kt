package com.virlin.app.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CodeHighlighterTest {

    private fun CodeHighlight.textOf(text: String, kind: CodeTokenKind) =
        tokens.filter { it.kind == kind }.map { text.substring(it.start, it.end) }

    @Test fun pythonFromAGeneratedAnswerIsDetectedAndColoured() {
        val code = """
            from dataclasses import dataclass
            from typing import Optional

            @dataclass
            class Box:
                ""${'"'}
                A docstring that spans
                several lines.
                ""${'"'}
                x: float
                def area(self) -> float:
                    return self.x * 2  # doubled
        """.trimIndent()
        val result = CodeHighlighter.highlight(code)
        assertEquals(CodeLanguage.PYTHON, result.language)
        val keywords = result.textOf(code, CodeTokenKind.KEYWORD)
        assertTrue(keywords.containsAll(listOf("from", "import", "class", "def", "return")))
        assertEquals(listOf("@dataclass"), result.textOf(code, CodeTokenKind.DECORATOR))
        assertEquals(listOf("# doubled"), result.textOf(code, CodeTokenKind.COMMENT))
        assertTrue(result.textOf(code, CodeTokenKind.STRING).single().contains("several lines."))
        assertTrue("2" in result.textOf(code, CodeTokenKind.NUMBER))
    }

    @Test fun sqlBashJsonAndOthersAreRecognised() {
        val samples = mapOf(
            CodeLanguage.SQL to """
                WITH ranked AS (
                  SELECT id, name, ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary DESC) AS rn
                  FROM employees
                )
                SELECT * FROM ranked WHERE rn = 1; -- top earners
            """.trimIndent(),
            CodeLanguage.BASH to "#!/usr/bin/env bash\nset -euo pipefail\necho \"Deploying \$APP\"\nif [ -f .env ]; then\n  source .env\nfi",
            CodeLanguage.JSON to "{\"name\": \"Virlin\", \"version\": 2, \"ok\": true}",
            CodeLanguage.JAVASCRIPT to "const add = (a, b) => a + b;\nconsole.log(add(1, 2));",
            CodeLanguage.TYPESCRIPT to "interface User { name: string }\nconst u: User = { name: \"a\" };",
            CodeLanguage.KOTLIN to "data class User(val name: String)\nfun greet(u: User) = println(\"Hi\")",
            CodeLanguage.JAVA to "public class Main {\n  public static void main(String[] args) {\n    System.out.println(\"hi\");\n  }\n}",
            CodeLanguage.HTML to "<div class=\"card\"><p>Hello</p></div>",
            CodeLanguage.CSS to ".card {\n  color: #333;\n  padding: 8px;\n}",
        )
        samples.forEach { (language, code) ->
            val result = CodeHighlighter.highlight(code)
            assertEquals("for $code", language, result.language)
            assertTrue("tokens for $language", result.tokens.isNotEmpty())
        }
        val sql = samples.getValue(CodeLanguage.SQL)
        val sqlResult = CodeHighlighter.highlight(sql)
        assertTrue("SELECT" in sqlResult.textOf(sql, CodeTokenKind.KEYWORD))
        assertEquals(listOf("-- top earners"), sqlResult.textOf(sql, CodeTokenKind.COMMENT))
        val json = samples.getValue(CodeLanguage.JSON)
        val jsonResult = CodeHighlighter.highlight(json)
        assertEquals(listOf("\"name\"", "\"version\"", "\"ok\""), jsonResult.textOf(json, CodeTokenKind.PROPERTY))
        assertEquals(listOf("true"), jsonResult.textOf(json, CodeTokenKind.CONSTANT))
    }

    @Test fun proseIsNeverColoured() {
        val prose = "Don't forget to buy milk. It's 5 o'clock and the shop closes soon."
        val result = CodeHighlighter.highlight(prose)
        assertEquals(CodeLanguage.PLAIN, result.language)
        assertTrue(result.tokens.isEmpty())
    }

    @Test fun veryLargeBlocksStayUncolouredButReadable() {
        val code = "def f(x):\n    return x\n".repeat(CodeHighlighter.MAX_HIGHLIGHT_CHARS / 20)
        val result = CodeHighlighter.highlight(code)
        assertTrue(code.length > CodeHighlighter.MAX_HIGHLIGHT_CHARS)
        assertTrue(result.tokens.isEmpty())
    }

    @Test fun highlightingAtTheLimitIsFast() {
        val code = "def f(x):  # c\n    return \"s\" + str(42)\n".repeat(CodeHighlighter.MAX_HIGHLIGHT_CHARS / 42)
        val started = System.nanoTime()
        val result = CodeHighlighter.highlight(code)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.tokens.isNotEmpty())
        assertTrue("took $ms ms", ms < 1_500)
    }

    @Test fun randomInputNeverCrashesAndTokensStayInBounds() {
        val alphabet = "abcXYZ019 _\t\n\"'`#/*-<>{}[]():;,.@\$\\=!"
        val random = Random(7)
        repeat(400) {
            val text = buildString { repeat(random.nextInt(0, 300)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            CodeLanguage.entries.forEach { language ->
                val tokens = CodeHighlighter.highlightAs(text, language).tokens
                var previousEnd = 0
                tokens.forEach { token ->
                    assertTrue("$language bounds in [$text]", token.start >= previousEnd && token.end <= text.length && token.end > token.start)
                    previousEnd = token.end
                }
            }
        }
    }
}
