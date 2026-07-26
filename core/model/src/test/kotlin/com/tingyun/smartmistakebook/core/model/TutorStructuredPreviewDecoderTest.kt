package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorStructuredPreviewDecoderTest {
    @Test
    fun messageFirstRespondWithholdsPreviewButStillAcceptsTheCompleteDocument() {
        val decoder = TutorStructuredPreviewDecoder(
            target = TutorStreamTarget.RESPOND,
            solutionPreviewAllowed = false,
        )

        val delta = decoder.append(
            """{"messageMarkdown":"不能提前显示","solutionRevealed":false,"intentDecision":{}}""",
        )

        assertEquals("", delta)
        assertEquals(
            TutorStructuredPreviewCompletion.Accepted("不能提前显示"),
            decoder.complete(),
        )
    }

    @Test
    fun unauthorizedRespondDoesNotPreviewARevealedSolution() {
        val decoder = TutorStructuredPreviewDecoder(
            target = TutorStreamTarget.RESPOND,
            solutionPreviewAllowed = false,
        )

        val delta = decoder.append(
            """{"intentDecision":{},"solutionRevealed":true,"messageMarkdown":"答案是 42"}""",
        )

        assertEquals("", delta)
        assertSame(TutorStructuredPreviewCompletion.Rejected, decoder.complete())
    }

    @Test
    fun unauthorizedRespondNeverPreviewsFreeMessageMarkdown() {
        val decoder = TutorStructuredPreviewDecoder(
            target = TutorStreamTarget.RESPOND,
            solutionPreviewAllowed = false,
        )

        val firstDelta = decoder.append(
            """{"intentDecision":{},"solutionRevealed":false,"answer":"不能显示","messageMarkdown":"先看""",
        )
        val secondDelta = decoder.append("""条件。"}""")

        assertEquals("", firstDelta)
        assertEquals("", secondDelta)
        assertEquals(
            TutorStructuredPreviewCompletion.Accepted("先看条件。"),
            decoder.complete(),
        )
    }

    @Test
    fun respondGuardAndMessageRemainIncrementalWhenEveryTokenIsFragmented() {
        val json =
            """{"intentDecision":{"intent":"current_question_help"},"solutionRevealed":true,"messageMarkdown":"分片安全"}"""
        for (splitAt in 0..json.length) {
            val splitDecoder = TutorStructuredPreviewDecoder(
                target = TutorStreamTarget.RESPOND,
                solutionPreviewAllowed = true,
            )
            val splitDecoded = buildString {
                append(splitDecoder.append(json.substring(0, splitAt)))
                append(splitDecoder.append(json.substring(splitAt)))
            }
            assertEquals("splitAt=$splitAt", "分片安全", splitDecoded)
        }

        val decoder = TutorStructuredPreviewDecoder(
            target = TutorStreamTarget.RESPOND,
            solutionPreviewAllowed = true,
        )
        val decoded = buildString {
            json.forEach { character -> append(decoder.append(character.toString())) }
        }

        assertEquals("分片安全", decoded)
        assertEquals(
            TutorStructuredPreviewCompletion.Accepted("分片安全"),
            decoder.complete(),
        )
    }

    @Test
    fun singleCharacterFragmentsAreInspectedOnlyOnceByTheIncrementalScanner() {
        val message = "分片".repeat(1_000)
        val json = """{"messageMarkdown":"$message"}"""
        val decoder = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)

        val decoded = buildString {
            json.forEach { character -> append(decoder.append(character.toString())) }
        }

        assertEquals(message, decoded)
        assertEquals(json.length.toLong(), decoder.incrementalInspectedCharacterCount)
    }

    @Test
    fun authorizedRespondWithOldFieldOrderWithholdsPreviewButCompletes() {
        val decoder = TutorStructuredPreviewDecoder(
            target = TutorStreamTarget.RESPOND,
            solutionPreviewAllowed = true,
        )

        assertEquals(
            "",
            decoder.append("""{"solutionRevealed":true,"intentDecision":{},"messageMarkdown":"答案"}"""),
        )
        assertEquals(
            TutorStructuredPreviewCompletion.Accepted("答案"),
            decoder.complete(),
        )
    }

    @Test
    fun authorizedRespondMayPreviewAfterAnExplicitRevealedSolutionGuard() {
        val decoder = TutorStructuredPreviewDecoder(
            target = TutorStreamTarget.RESPOND,
            solutionPreviewAllowed = true,
        )

        assertEquals(
            "答案",
            decoder.append("""{"intentDecision":{},"solutionRevealed":true,"messageMarkdown":"答案"}"""),
        )
        assertEquals(
            TutorStructuredPreviewCompletion.Accepted("答案"),
            decoder.complete(),
        )
    }

    @Test
    fun lobbyStreamsWithoutASolutionGuardAndIgnoresNestedOrInternalText() {
        val decoder = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)

        val delta = decoder.append(
            """
            {
              "answer":"内部答案",
              "metadata":{"messageMarkdown":"嵌套内容"},
              "messageMarkdown":"只显示这里"
            }
            """.trimIndent(),
        )

        assertEquals("只显示这里", delta)
        assertFalse(delta.contains("内部答案"))
        assertFalse(delta.contains("嵌套内容"))
        assertEquals(
            TutorStructuredPreviewCompletion.Accepted("只显示这里"),
            decoder.complete(),
        )
    }

    @Test
    fun arbitraryFragmentBoundariesPreserveEscapesAndUnicodePairs() {
        val json =
            """{"messageMarkdown":"引号：\"，反斜杠：\\，换行：\n，表情：\uD83D\uDE00"}"""
        val expected = "引号：\"，反斜杠：\\，换行：\n，表情：😀"

        for (splitAt in 0..json.length) {
            val decoder = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)
            val decoded = buildString {
                append(decoder.append(json.substring(0, splitAt)))
                append(decoder.append(json.substring(splitAt)))
            }

            assertEquals("splitAt=$splitAt", expected, decoded)
            assertEquals(
                "splitAt=$splitAt",
                TutorStructuredPreviewCompletion.Accepted(expected),
                decoder.complete(),
            )
        }

        val characterDecoder = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)
        val oneCharacterAtATime = buildString {
            json.forEach { character -> append(characterDecoder.append(character.toString())) }
        }
        assertEquals(expected, oneCharacterAtATime)
    }

    @Test
    fun aLiteralSurrogatePairSplitAcrossFragmentsIsEmittedOnlyWhenComplete() {
        val emoji = "😀"
        val decoder = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)

        val first = decoder.append("""{"messageMarkdown":"${emoji[0]}""")
        val second = decoder.append("""${emoji[1]}"}""")

        assertEquals("", first)
        assertEquals(emoji, second)
        assertEquals(
            TutorStructuredPreviewCompletion.Accepted(emoji),
            decoder.complete(),
        )
    }

    @Test
    fun duplicateRootMessageAndMalformedOrUnfinishedJsonFailClosed() {
        val duplicate = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)
        duplicate.append("""{"messageMarkdown":"第一段","messageMarkdown":"第二段"}""")
        assertSame(TutorStructuredPreviewCompletion.Rejected, duplicate.complete())

        val malformed = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)
        assertEquals("", malformed.append("""{"messageMarkdown":"坏转义：\x"}"""))
        assertSame(TutorStructuredPreviewCompletion.Rejected, malformed.complete())

        val unfinished = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)
        assertEquals("尚未结束", unfinished.append("""{"messageMarkdown":"尚未结束"""))
        assertSame(TutorStructuredPreviewCompletion.Rejected, unfinished.complete())
    }

    @Test
    fun unsafeControlCharactersAndTextOverTheTutorBudgetAreRejected() {
        val unsafe = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)
        assertEquals("", unsafe.append("{\"messageMarkdown\":\"a\u0000b\"}"))
        assertSame(TutorStructuredPreviewCompletion.Rejected, unsafe.complete())

        val overBudget = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)
        val oversizedMessage = "文".repeat(TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS + 1)
        val delta = overBudget.append("""{"messageMarkdown":"$oversizedMessage"}""")
        assertTrue(delta.isEmpty())
        assertSame(TutorStructuredPreviewCompletion.Rejected, overBudget.complete())
    }

    @Test
    fun oversizedStructuredPreviewInputIsRejectedBeforeInternalFieldsCanConsumeMoreMemory() {
        val decoder = TutorStructuredPreviewDecoder(target = TutorStreamTarget.LOBBY)
        val oversizedInternalField = "x".repeat(70_000)

        assertEquals(
            "",
            decoder.append("""{"internal":"$oversizedInternalField","messageMarkdown":"不能到达"}"""),
        )
        assertSame(TutorStructuredPreviewCompletion.Rejected, decoder.complete())
    }
}
