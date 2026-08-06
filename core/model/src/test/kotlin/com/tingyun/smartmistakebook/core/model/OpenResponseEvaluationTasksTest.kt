package com.tingyun.smartmistakebook.core.model

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenResponseEvaluationTasksTest {
    @Test
    fun `request round trip carries only opaque bounded evaluation scope`() {
        val request = request()
        val encoded = OpenResponseEvaluationJsonCodec.encodeRequest(request)
        val restored = OpenResponseEvaluationJsonCodec.restoreIssuedRequest(encoded, request)

        assertSame(request, restored)
        assertTrue(encoded.contains("\"knowledgeScope\""))
        assertFalse(encoded.contains("serializedKnowledgeScope"))
        assertFalse(
            OpenResponseEvaluationJsonCodec::class.java.methods.any {
                it.name == "decodeRequest"
            },
        )
        listOf(
            "answerText",
            "questionText",
            "rubricText",
            "learnerId",
            "mastery",
            "score",
            "weight",
            "confidence",
            "occurredAt",
            "sql",
            "knowledgeText",
        ).forEach { forbidden ->
            assertFalse("Protocol leaked $forbidden", encoded.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun `request scope cannot be widened by mutating caller or returned lists`() {
        val source = mutableListOf(scope(), scope(refFingerprint = OTHER_KNOWLEDGE_REF))
        val request = request(knowledgeScope = source)
        val unadmitted = scope(refFingerprint = UNADMITTED_KNOWLEDGE_REF)

        source.add(unadmitted)
        source[0] = unadmitted
        source.clear()
        (request.knowledgeScope as MutableList).add(unadmitted)
        (request.knowledgeScope as MutableList)[0] = unadmitted
        (request.knowledgeScope as MutableList).clear()

        assertEquals(
            listOf(KNOWLEDGE_REF, OTHER_KNOWLEDGE_REF),
            request.knowledgeScope.map(OpenResponseKnowledgeScopeRef::refFingerprint),
        )
        assertTrue(
            OpenResponseEvaluationRequest::class.java.declaredConstructors.all {
                it.isPrivateOrSyntheticConstructorBridge()
            },
        )
        assertTrue(
            HostIssuedOpenResponseEvaluationRequest::class.java.declaredConstructors.all {
                it.isPrivateOrSyntheticConstructorBridge()
            },
        )
        assertTrue(
            OpenResponseEvaluationHostAuthority::class.java.declaredConstructors.all {
                it.isPrivateOrSyntheticConstructorBridge()
            },
        )
        assertTrue(
            OpenResponseEvaluatorBinding::class.java.methods.none {
                it.name == "copy" ||
                    it.name.startsWith("copy\$") ||
                    it.name.startsWith("component")
            },
        )
    }

    @Test
    fun `reflected synthetic constructor cannot forge host issued authority`() {
        val issued = request()
        val encoded = OpenResponseEvaluationJsonCodec.encodeRequest(issued)
        val syntheticConstructor =
            HostIssuedOpenResponseEvaluationRequest::class.java.declaredConstructors.single {
                Modifier.isPublic(it.modifiers) &&
                    it.isSynthetic &&
                    it.parameterTypes.lastOrNull()?.name ==
                    "kotlin.jvm.internal.DefaultConstructorMarker"
            }
        val forged =
            syntheticConstructor.newInstance(
                *Array<Any?>(syntheticConstructor.parameterCount) { null },
            ) as HostIssuedOpenResponseEvaluationRequest

        assertThrows(IllegalArgumentException::class.java) {
            forged.knowledgeScope
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.encodeRequest(forged)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.restoreIssuedRequest(encoded, forged)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.decodeDecision(validDecisionJson(), forged)
        }
        assertTrue(
            HostIssuedOpenResponseEvaluationRequest::class.java.declaredMethods.none {
                it.name.contains("OWNER_SEAL", ignoreCase = true) ||
                    it.name.contains("ownerSeal", ignoreCase = true)
            },
        )
    }

    @Test
    fun `valid qualitative decision is admitted and remains round trip stable`() {
        val request = request()
        val decoded = OpenResponseEvaluationJsonCodec.decodeDecision(
            validDecisionJson(),
            request,
        )

        assertEquals(OpenResponseEvaluationOutcome.CORRECT, decoded.outcome)
        assertEquals(
            listOf(OpenResponseKnowledgeEffect(KNOWLEDGE_REF, OpenResponseKnowledgeRole.SUPPORTED_CORRECTNESS)),
            decoded.knowledgeEffects,
        )
        assertEquals(
            decoded,
            OpenResponseEvaluationJsonCodec.decodeDecision(
                OpenResponseEvaluationJsonCodec.encodeDecision(decoded, request),
                request,
            ),
        )
        assertTrue(
            decoded.javaClass.methods.none {
                it.name.contains("write", ignoreCase = true) ||
                    it.name.contains("persist", ignoreCase = true) ||
                    it.name.contains("event", ignoreCase = true)
            },
        )
    }

    @Test
    fun `decision effects cannot be added replaced or cleared after scope validation`() {
        val request =
            request(
                knowledgeScope =
                    listOf(
                        scope(),
                        scope(refFingerprint = OTHER_KNOWLEDGE_REF),
                    ),
            )
        val effects =
            """
            [
              {"refFingerprint":"$KNOWLEDGE_REF","role":"SUPPORTED_CORRECTNESS"},
              {"refFingerprint":"$OTHER_KNOWLEDGE_REF","role":"COULD_NOT_ASSESS"}
            ]
            """.trimIndent()
        val decision =
            OpenResponseEvaluationJsonCodec.decodeDecision(
                validDecisionJson(effects = effects),
                request,
            )
        val unadmitted =
            OpenResponseKnowledgeEffect(
                UNADMITTED_KNOWLEDGE_REF,
                OpenResponseKnowledgeRole.LOCATED_GAP,
            )

        (decision.knowledgeEffects as MutableList).add(unadmitted)
        (decision.knowledgeEffects as MutableList)[0] = unadmitted
        (decision.knowledgeEffects as MutableList).clear()

        assertEquals(
            listOf(KNOWLEDGE_REF, OTHER_KNOWLEDGE_REF),
            decision.knowledgeEffects.map(OpenResponseKnowledgeEffect::refFingerprint),
        )
        val encoded = OpenResponseEvaluationJsonCodec.encodeDecision(decision, request)
        assertFalse(encoded.contains(UNADMITTED_KNOWLEDGE_REF))

        val narrowerRequest =
            request(
                knowledgeScope =
                    listOf(scope(refFingerprint = OTHER_KNOWLEDGE_REF)),
            )
        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.encodeDecision(decision, narrowerRequest)
        }
    }

    @Test
    fun `arbitrary JSON cannot become a host authority root or smuggle evaluator data`() {
        val issued = request()
        val encoded = OpenResponseEvaluationJsonCodec.encodeRequest(issued)
        val selfConsistentForgedScope = encoded.replace(QUESTION, OTHER_QUESTION)

        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.restoreIssuedRequest(
                selfConsistentForgedScope,
                issued,
            )
        }

        listOf(
            "learnerId:student-42",
            "weight:0.9",
            "occurredAt:1700000000",
            "c3R1ZGVudC1hbnN3ZXI",
        ).forEach { smuggled ->
            val forgedEvaluator =
                encoded.replace(
                    "\"evaluator\":\"RUBRIC\"",
                    "\"evaluator\":\"$smuggled\"",
                )
            val forgedPolicy = encoded.replace(POLICY, smuggled)
            listOf(forgedEvaluator, forgedPolicy).forEach { forged ->
                assertThrows(
                    "Accepted evaluator data: $smuggled",
                    IllegalArgumentException::class.java,
                ) {
                    OpenResponseEvaluationJsonCodec.restoreIssuedRequest(forged, issued)
                }
            }
        }
    }

    @Test
    fun `construction rejects general cross subject cross question duplicate and oversized scope`() {
        assertThrows(IllegalArgumentException::class.java) {
            request(subject = SubjectKind.GENERAL, knowledgeScope = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(
                knowledgeScope = listOf(
                    scope(subject = SubjectKind.PHYSICS),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(
                knowledgeScope = listOf(
                    scope(questionFingerprint = OTHER_QUESTION),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(knowledgeScope = listOf(scope(), scope()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(
                knowledgeScope =
                    (0..OpenResponseEvaluationRequest.MAX_KNOWLEDGE_REFS).map { index ->
                        scope(refFingerprint = index.fingerprint())
                    },
            )
        }
    }

    @Test
    fun `construction rejects malformed fingerprints and unbounded evaluator tokens`() {
        assertThrows(IllegalArgumentException::class.java) {
            binding(questionFingerprint = "A".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(policyFingerprint = "learnerId:student-42")
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(evidenceFingerprint = "not-a-fingerprint")
        }
    }

    @Test
    fun `decision rejects stale case session question answer rubric evaluator and evidence bindings`() {
        val request = request()
        listOf(
            CASE to OTHER_CASE,
            SESSION to OTHER_SESSION,
            QUESTION to OTHER_QUESTION,
            ANSWER to OTHER_ANSWER,
            RUBRIC to OTHER_RUBRIC,
            EVIDENCE to OTHER_EVIDENCE,
            "\"evaluator\":\"RUBRIC\"" to "\"evaluator\":\"OTHER\"",
            POLICY to OTHER_POLICY,
        ).forEach { (current, changed) ->
            assertThrows(IllegalArgumentException::class.java) {
                OpenResponseEvaluationJsonCodec.decodeDecision(
                    validDecisionJson().replace(current, changed),
                    request,
                )
            }
        }
    }

    @Test
    fun `decision rejects cross question knowledge refs duplicate refs and effect over budget`() {
        val request = request()
        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.decodeDecision(
                validDecisionJson(refFingerprint = OTHER_KNOWLEDGE_REF),
                request,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.decodeDecision(
                validDecisionJson(
                    effects =
                        """
                        [
                          {"refFingerprint":"$KNOWLEDGE_REF","role":"SUPPORTED_CORRECTNESS"},
                          {"refFingerprint":"$KNOWLEDGE_REF","role":"LOCATED_GAP"}
                        ]
                        """.trimIndent(),
                ),
                request,
            )
        }

        val refs =
            (0..OpenResponseEvaluationRequest.MAX_KNOWLEDGE_REFS).map { it.fingerprint() }
        val wideRequest =
            request(
                knowledgeScope =
                    refs.take(OpenResponseEvaluationRequest.MAX_KNOWLEDGE_REFS).map {
                        scope(refFingerprint = it)
                    },
            )
        val effects = refs.joinToString(prefix = "[", postfix = "]") { ref ->
            """{"refFingerprint":"$ref","role":"COULD_NOT_ASSESS"}"""
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.decodeDecision(
                validDecisionJson(effects = effects),
                wideRequest,
            )
        }
    }

    @Test
    fun `strict parser rejects unknown duplicate and unicode escaped duplicate fields`() {
        val valid = validDecisionJson()
        val unknown = valid.replace(
            "\"outcome\":\"CORRECT\",",
            "\"outcome\":\"CORRECT\",\"unexpected\":\"value\",",
        )
        val duplicate = valid.replace(
            "\"outcome\":\"CORRECT\",",
            "\"outcome\":\"CORRECT\",\"outcome\":\"INCORRECT\",",
        )
        val escapedDuplicate = valid.replace(
            "\"outcome\":\"CORRECT\",",
            "\"outcome\":\"CORRECT\",\"\\u006futcome\":\"INCORRECT\",",
        )
        val nestedEscapedDuplicate = valid.replace(
            "\"questionFingerprint\":\"$QUESTION\",",
            "\"questionFingerprint\":\"$QUESTION\"," +
                "\"\\u0071uestionFingerprint\":\"$OTHER_QUESTION\",",
        )
        val effectEscapedDuplicate = valid.replace(
            "\"role\":\"SUPPORTED_CORRECTNESS\"",
            "\"role\":\"SUPPORTED_CORRECTNESS\",\"\\u0072ole\":\"LOCATED_GAP\"",
        )

        listOf(
            unknown,
            duplicate,
            escapedDuplicate,
            nestedEscapedDuplicate,
            effectEscapedDuplicate,
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                OpenResponseEvaluationJsonCodec.decodeDecision(payload, request())
            }
        }
    }

    @Test
    fun `strict parser rejects raw content identity sql event and numeric assessment fields`() {
        val forbiddenFields = mapOf(
            "answerText" to "\"student answer\"",
            "questionText" to "\"question\"",
            "rubricText" to "\"rubric\"",
            "learnerId" to "\"learner-1\"",
            "knowledgeText" to "\"knowledge body\"",
            "sql" to "\"INSERT INTO mastery\"",
            "eventId" to "\"event-1\"",
            "weight" to "0.9",
            "score" to "100",
            "confidence" to "0.99",
            "occurredAtEpochMillis" to "1234",
        )
        forbiddenFields.forEach { (field, value) ->
            val malicious = validDecisionJson().replace(
                "\"outcome\":\"CORRECT\",",
                "\"outcome\":\"CORRECT\",\"$field\":$value,",
            )
            assertThrows("Accepted forbidden field $field", IllegalArgumentException::class.java) {
                OpenResponseEvaluationJsonCodec.decodeDecision(malicious, request())
            }
        }
    }

    @Test
    fun `strict parser rejects numeric weight inside a knowledge effect`() {
        val malicious = validDecisionJson().replace(
            "\"role\":\"SUPPORTED_CORRECTNESS\"",
            "\"role\":\"SUPPORTED_CORRECTNESS\",\"weight\":1",
        )

        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.decodeDecision(malicious, request())
        }
    }

    @Test
    fun `strict parser rejects invalid enum schema trailing data depth and total size`() {
        val invalidEnum = validDecisionJson().replace("\"CORRECT\"", "\"MASTERED\"")
        val invalidSchema = validDecisionJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        val trailing = validDecisionJson() + "{}"
        val deepUnknown =
            """{"schemaVersion":1,"binding":{"caseFingerprint":"$CASE"},"x":[[[[[[[[[0]]]]]]]]] }"""
        val oversized = " ".repeat(OpenResponseEvaluationJsonCodec.MAX_JSON_CHARS + 1)

        listOf(invalidEnum, invalidSchema, trailing, deepUnknown, oversized).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                OpenResponseEvaluationJsonCodec.decodeDecision(payload, request())
            }
        }
    }

    @Test
    fun `strict parser rejects non canonical overflow and special schema numbers`() {
        listOf(
            "1.0",
            "1e0",
            "2147483648",
            "1e999999999",
            "NaN",
            "Infinity",
        ).forEach { number ->
            val payload =
                validDecisionJson().replace(
                    "\"schemaVersion\":1",
                    "\"schemaVersion\":$number",
                )
            assertThrows("Accepted schema number $number", IllegalArgumentException::class.java) {
                OpenResponseEvaluationJsonCodec.decodeDecision(payload, request())
            }
        }
    }

    @Test
    fun `strict parser enforces exact total key value object and array budgets`() {
        val valid = validDecisionJson()
        val exactTotalBudget =
            valid + " ".repeat(OpenResponseEvaluationJsonCodec.MAX_JSON_CHARS - valid.length)
        assertEquals(
            OpenResponseEvaluationOutcome.CORRECT,
            OpenResponseEvaluationJsonCodec.decodeDecision(exactTotalBudget, request()).outcome,
        )
        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.decodeDecision(exactTotalBudget + " ", request())
        }

        val tooLongKey =
            valid.replace(
                "\"outcome\":\"CORRECT\",",
                "\"outcome\":\"CORRECT\",\"${"k".repeat(65)}\":0,",
            )
        val tooLongValue =
            valid.replace(
                "\"outcome\":\"CORRECT\",",
                "\"outcome\":\"CORRECT\",\"extra\":\"${"v".repeat(257)}\",",
            )
        val tooManyObjectEntries =
            valid.replace(
                "\"outcome\":\"CORRECT\",",
                "\"outcome\":\"CORRECT\"," +
                    (0 until 10).joinToString(separator = "") { index ->
                        "\"extra$index\":0,"
                    },
            )
        listOf(tooLongKey, tooLongValue, tooManyObjectEntries).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                OpenResponseEvaluationJsonCodec.decodeDecision(payload, request())
            }
        }

        val refs =
            (0 until OpenResponseEvaluationRequest.MAX_KNOWLEDGE_REFS).map { it.fingerprint() }
        val boundaryRequest =
            request(knowledgeScope = refs.map { scope(refFingerprint = it) })
        val boundaryEffects =
            refs.joinToString(prefix = "[", postfix = "]") { ref ->
                """{"refFingerprint":"$ref","role":"COULD_NOT_ASSESS"}"""
            }
        assertEquals(
            OpenResponseEvaluationRequest.MAX_KNOWLEDGE_REFS,
            OpenResponseEvaluationJsonCodec
                .decodeDecision(
                    validDecisionJson(effects = boundaryEffects),
                    boundaryRequest,
                ).knowledgeEffects.size,
        )
        val oversizedEffects =
            (refs + UNADMITTED_KNOWLEDGE_REF)
                .joinToString(prefix = "[", postfix = "]") { ref ->
                    """{"refFingerprint":"$ref","role":"COULD_NOT_ASSESS"}"""
                }
        assertThrows(IllegalArgumentException::class.java) {
            OpenResponseEvaluationJsonCodec.decodeDecision(
                validDecisionJson(effects = oversizedEffects),
                boundaryRequest,
            )
        }
    }

    @Test
    fun `nullable decoder fails closed without changing the admitted request`() {
        val request = request()

        assertNull(OpenResponseEvaluationJsonCodec.decodeDecisionOrNull(null, request))
        assertNull(OpenResponseEvaluationJsonCodec.decodeDecisionOrNull("{", request))
        assertEquals(
            OpenResponseEvaluationOutcome.CORRECT,
            OpenResponseEvaluationJsonCodec
                .decodeDecisionOrNull(validDecisionJson(), request)
                ?.outcome,
        )
    }

    private fun request(
        subject: SubjectKind = SubjectKind.MATH,
        knowledgeScope: List<OpenResponseKnowledgeScopeRef> = listOf(scope()),
        policyFingerprint: String = POLICY,
        evidenceFingerprint: String = EVIDENCE,
    ) = issueTestOpenResponseEvaluationRequest(
        binding = binding(),
        subject = subject,
        knowledgeScope = knowledgeScope,
        evaluator = OpenResponseEvaluatorKind.RUBRIC,
        policyFingerprint = policyFingerprint,
        evidenceFingerprint = evidenceFingerprint,
    )

    private fun binding(
        questionFingerprint: String = QUESTION,
    ) = OpenResponseEvaluationBinding(
        caseFingerprint = CASE,
        sessionFingerprint = SESSION,
        questionFingerprint = questionFingerprint,
        answerFingerprint = ANSWER,
        rubricFingerprint = RUBRIC,
    )

    private fun scope(
        refFingerprint: String = KNOWLEDGE_REF,
        subject: SubjectKind = SubjectKind.MATH,
        questionFingerprint: String = QUESTION,
    ) = OpenResponseKnowledgeScopeRef(
        refFingerprint = refFingerprint,
        subject = subject,
        questionFingerprint = questionFingerprint,
    )

    private fun validDecisionJson(
        refFingerprint: String = KNOWLEDGE_REF,
        effects: String =
            """[{"refFingerprint":"$refFingerprint","role":"SUPPORTED_CORRECTNESS"}]""",
    ): String =
        """
        {
          "schemaVersion":1,
          "binding":{
            "caseFingerprint":"$CASE",
            "sessionFingerprint":"$SESSION",
            "questionFingerprint":"$QUESTION",
            "answerFingerprint":"$ANSWER",
            "rubricFingerprint":"$RUBRIC"
          },
          "subject":"MATH",
          "outcome":"CORRECT",
          "knowledgeEffects":$effects,
          "evaluator":{"evaluator":"RUBRIC","policyFingerprint":"$POLICY"},
          "evidenceFingerprint":"$EVIDENCE"
        }
        """.trimIndent()

    private fun Int.fingerprint(): String = toString(16).padStart(64, '0')

    private fun java.lang.reflect.Constructor<*>.isPrivateOrSyntheticConstructorBridge(): Boolean =
        Modifier.isPrivate(modifiers) ||
            (
                Modifier.isPublic(modifiers) &&
                    isSynthetic &&
                    parameterTypes.lastOrNull()?.name ==
                    "kotlin.jvm.internal.DefaultConstructorMarker"
            )

    private companion object {
        val CASE = "a".repeat(64)
        val SESSION = "b".repeat(64)
        val QUESTION = "c".repeat(64)
        val ANSWER = "d".repeat(64)
        val RUBRIC = "e".repeat(64)
        val EVIDENCE = "f".repeat(64)
        val POLICY = "9".repeat(64)
        val KNOWLEDGE_REF = "1".repeat(64)

        val OTHER_CASE = "2".repeat(64)
        val OTHER_SESSION = "3".repeat(64)
        val OTHER_QUESTION = "4".repeat(64)
        val OTHER_ANSWER = "5".repeat(64)
        val OTHER_RUBRIC = "6".repeat(64)
        val OTHER_EVIDENCE = "7".repeat(64)
        val OTHER_POLICY = "0".repeat(64)
        val OTHER_KNOWLEDGE_REF = "8".repeat(64)
        val UNADMITTED_KNOWLEDGE_REF = "ab".repeat(32)
    }
}
