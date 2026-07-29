package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorTasksTest {
    @Test
    fun tutorRequestAndOutputRoundTripWithoutGrantingMasteryAuthority() {
        val request = request()
        val output = output()

        val decodedRequest = ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request))
        val decodedOutput = ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(output))

        assertEquals(request, decodedRequest)
        assertEquals(output, decodedOutput)
        assertTrue(ModelTaskCompletionValidator.validate(request, output).isEmpty())
        assertTrue(requireNotNull(output.plan.diagnosticItem).knowledgeNodeIds.isEmpty())
    }

    @Test
    fun explanationOnlyTurnRoundTripsWithoutAnArtificialChoiceBlock() {
        val explanationOnly = output().copy(
            plan = output().plan.copy(diagnosticItem = null),
        )

        assertEquals(
            explanationOnly,
            ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(explanationOnly)),
        )
        assertTrue(ModelTaskCompletionValidator.validate(request(), explanationOnly).isEmpty())
    }

    @Test
    fun currentQuestionTextResponseRoundTripsWithoutADiagnosticChoice() {
        val request = respondRequest(
            input = respondInput().copy(
                studentMessage = "请直接告诉我这道题的完整答案",
                requestedMove = TutorMoveType.REVEAL_SOLUTION,
            ),
        )
        val output = respondOutput().copy(
            solutionRevealed = true,
            intentDecision = TutorIntentDecision(
                intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
                confidence = 1.0,
                explicitActionRequest = false,
                memoryPreference = TutorMemoryPreference.UNCHANGED,
                requestedLocalCapability = TutorRequestedLocalCapability.NONE,
            ),
        )

        assertEquals(request, ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request)))
        assertEquals(output, ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(output)))
        assertTrue(ModelTaskCompletionValidator.validate(request, output).isEmpty())
        assertTrue(output.solutionRevealed)
        assertEquals(null, output.visualScene)
        assertTrue(output.suggestedMoves.isEmpty())
    }

    @Test
    fun legacyTextResponseDefaultsToNoSolutionExposure() {
        val encoded = ModelTaskCodec.encodeOutput(respondOutput())
        val legacy = encoded
            .replace(",\"cycleOrdinal\":1", "")
            .replace(",\"turnOrdinal\":1", "")
            .replace(",\"solutionRevealed\":false", "")

        assertTrue(encoded != legacy)
        val decoded = ModelTaskCodec.decodeOutput(legacy) as TutorRespondOutput
        assertEquals(1, decoded.cycleOrdinal)
        assertEquals(1, decoded.turnOrdinal)
        assertEquals(false, decoded.solutionRevealed)
        assertEquals(TutorFreeResponseEvaluation.UNKNOWN, decoded.freeResponseEvaluation)
    }

    @Test
    fun legacyTutorPlanDefaultsToNoEarlierStudentMessages() {
        val encoded = ModelTaskCodec.encodeRequest(request())
        val legacy = encoded.replace(",\"priorCycleStudentMessages\":[]", "")

        assertTrue(encoded != legacy)
        val decoded = ModelTaskCodec.decodeRequest(legacy).input as TutorPlanInput
        assertTrue(decoded.priorCycleStudentMessages.isEmpty())
    }

    @Test
    fun legacyTutorResponseDefaultsToNoSelectedChoiceId() {
        val request = respondRequest(respondInput().copy(selectedChoiceId = "directive-choice-a"))
        val encoded = ModelTaskCodec.encodeRequest(request)
        val legacy = encoded.replace(",\"selectedChoiceId\":\"directive-choice-a\"", "")

        assertTrue(encoded != legacy)
        assertEquals(
            "directive-choice-a",
            (ModelTaskCodec.decodeRequest(encoded).input as TutorRespondInput).selectedChoiceId,
        )
        val decoded = ModelTaskCodec.decodeRequest(legacy).input as TutorRespondInput
        assertEquals(null, decoded.selectedChoiceId)
    }

    @Test
    fun legacyTutorResponseSnapshotKeepsItsPreChoiceIdFingerprints() {
        val preChoiceIdRequest = respondRequest().copy(
            schemaVersion = ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION,
        )
        val encodedWithCurrentDefaults = ModelTaskCodec.encodeRequest(preChoiceIdRequest)
        val legacySnapshot = encodedWithCurrentDefaults.replace(",\"selectedChoiceId\":null", "")
        val restoredRequest = ModelTaskCodec.decodeRequest(legacySnapshot)
        val oldRequestFingerprint = sha256(legacySnapshot)
        val oldOperationFingerprint = sha256(
            restoredRequest.input.kind.name + "\n" +
                oldFingerprintJson.encodeToString(ModelTaskInput.serializer(), restoredRequest.input)
                    .replace(",\"selectedChoiceId\":null", ""),
        )

        assertEquals(oldRequestFingerprint, ModelTaskFingerprint.of(restoredRequest))
        assertEquals(oldOperationFingerprint, ModelTaskLogicalOperationFingerprint.of(restoredRequest))
        val restoredSnapshot = ModelTaskSnapshot(
            taskId = "legacy-tutor-response",
            request = restoredRequest,
            requestFingerprint = oldRequestFingerprint,
            status = ModelTaskStatus.QUEUED,
            stateVersion = 1,
            stage = ModelTaskStage.WAITING,
            userMessage = "等待模型处理",
            attemptCount = 0,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )

        assertEquals(restoredRequest, restoredSnapshot.request)
        assertEquals(null, (restoredSnapshot.request.input as TutorRespondInput).selectedChoiceId)
    }

    @Test
    fun studentMessagePreservesOrdinaryMathCodeAndLinksButRejectsUnsafeControls() {
        val exact = "  x < 3 时为什么？\n参考 https://example.com 和 `f'(x)`  "

        assertEquals(exact, respondInput().copy(studentMessage = exact).studentMessage)
        assertEquals(
            exact,
            TutorChatHistoryEntry(exact, "仍然只解释当前题。").studentMessage,
        )
        val invalid = runCatching {
            respondInput().copy(studentMessage = "不可见\u202E控制")
        }.exceptionOrNull()
        assertTrue(invalid is InvalidTutorStudentMessageException)
    }

    @Test
    fun tutorResponseCompletionRequiresExactPersistedContextIdentity() {
        val directRequest = respondRequest(
            respondInput().copy(explanationMode = TutorExplanationMode.DIRECT),
        )
        val directOutput = respondOutput().copy(
            solutionRevealed = true,
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )
        val mismatches = listOf(
            directOutput.copy(sessionId = "another-session"),
            directOutput.copy(draftRevisionNumber = 3),
            directOutput.copy(questionDocumentId = "another-question"),
            directOutput.copy(responseOrdinal = 4),
            directOutput.copy(cycleOrdinal = 2),
            directOutput.copy(turnOrdinal = 2),
        )

        mismatches.forEach { mismatch ->
            assertEquals(
                listOf(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH),
                ModelTaskCompletionValidator.validate(directRequest, mismatch).map { it.code },
            )
        }
    }

    @Test
    fun nonQuestionIntentCannotSmuggleTeachingOrAnswerContent() {
        val lookupIntent = TutorIntentDecision(
            intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
            confidence = 0.92,
            explicitActionRequest = true,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
        )
        val unsafe = respondOutput().copy(
            solutionRevealed = true,
            visualScene = stepFlowScene(),
            suggestedMoves = listOf(
                TutorSuggestedMove(
                    "respond-1",
                    "继续关键一步",
                    TutorMoveType.DEEPEN_REASONING,
                ),
            ),
            intentDecision = lookupIntent,
        )

        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(respondRequest(), unsafe).map { it.code },
        )
    }

    @Test
    fun completeAnswerRequiresExplicitStudentAuthorityBeforeTaskCompletion() {
        val unauthorizedInput = respondInput().copy(
            studentMessage = "我觉得这个答案不对",
            requestedMove = null,
        )
        val solution = respondOutput().copy(
            solutionRevealed = true,
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )

        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(
                respondRequest(unauthorizedInput),
                solution,
            ).map { it.code },
        )

        val explicitTextInput = unauthorizedInput.copy(studentMessage = "请告诉我答案")
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(
                respondRequest(explicitTextInput),
                solution,
            ).map { it.code },
        )

        val quotedTextInput = unauthorizedInput.copy(
            studentMessage = "我不是在让你给我答案，只是在引用这句话。",
        )
        assertFalse(quotedTextInput.authorizesSolutionExposure())

        val explicitButtonInput = unauthorizedInput.copy(
            studentMessage = "继续",
            requestedMove = TutorMoveType.REVEAL_SOLUTION,
        )
        assertTrue(
            ModelTaskCompletionValidator.validate(
                respondRequest(explicitButtonInput),
                solution,
            ).isEmpty(),
        )
    }

    @Test
    fun unauthorizedGuidedExplanationIsRejectedEvenWhenProviderDeniesRevealingTheSolution() {
        val guidedInput = respondInput().copy(
            explanationMode = TutorExplanationMode.GUIDED,
            studentMessage = "这一步应该怎么判断？",
            requestedMove = null,
        )
        val untrustedExplanation = respondOutput().copy(
            solutionRevealed = false,
            messageMarkdown = "最终答案是 2。完整解法如下。",
            interactionDirective = null,
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )

        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(
                respondRequest(guidedInput),
                untrustedExplanation,
            ).map { it.code },
        )
    }

    @Test
    fun guidedModeAllowsTheModelToExplainWithoutForcingAnInteraction() {
        val guidedInput = respondInput().copy(
            explanationMode = TutorExplanationMode.GUIDED,
            studentMessage = "这一步应该怎么判断？",
            requestedMove = null,
        )
        val explanation = respondOutput().copy(
            solutionRevealed = false,
            messageMarkdown = "先比较二次项系数，再判断配方时需要补上的常数。",
            interactionDirective = null,
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )

        assertTrue(
            ModelTaskCompletionValidator.validate(
                respondRequest(guidedInput),
                explanation,
            ).isEmpty(),
        )
    }

    @Test
    fun guidedModeRejectsObviousAnswerDisclosureInsideAnInteractionPrompt() {
        val guidedInput = respondInput().copy(
            explanationMode = TutorExplanationMode.GUIDED,
        )
        val unsafeOutputs = listOf(
            respondOutput().copy(
                solutionRevealed = false,
                messageMarkdown = "先完成这个判断。",
                interactionDirective = TutorInteractionDirective.FreeResponse(
                    promptMarkdown = "答案是 2，请照抄。",
                ),
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
            respondOutput().copy(
                solutionRevealed = false,
                messageMarkdown = "**最终答案：** 2。",
                interactionDirective = null,
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
        )

        unsafeOutputs.forEach { unsafe ->
            assertEquals(
                listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
                ModelTaskCompletionValidator.validate(
                    respondRequest(guidedInput),
                    unsafe,
                ).map { it.code },
            )
        }
    }

    @Test
    fun guidedVisibleFieldsRejectKnownConclusionAndChoiceLeaksWithoutRejectingARealQuestion() {
        val guidedInput = respondInput().copy(explanationMode = TutorExplanationMode.GUIDED)
        val unsafe = listOf(
            respondOutput().copy(
                messageMarkdown = "由 f'(x)>0，所以选B。",
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
            respondOutput().copy(
                messageMarkdown = "整理方程后，解得 x=2。",
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
            respondOutput().copy(
                messageMarkdown = "由条件可得 y=3。",
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
            respondOutput().copy(
                messageMarkdown = "因此应该选择C。",
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
            respondOutput().copy(
                interactionDirective = TutorInteractionDirective.FreeResponse(
                    "由 f'(x)>0，所以选B，对吗？",
                ),
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
            respondOutput().copy(
                interactionDirective = TutorInteractionDirective.Choices(
                    promptMarkdown = "这个答案是由哪个条件决定的？",
                    choices = listOf(
                        TutorInteractionChoice("a", "正确答案：B"),
                        TutorInteractionChoice("b", "检查导数符号"),
                    ),
                ),
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
        )
        unsafe.forEach { output ->
            assertEquals(
                listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
                ModelTaskCompletionValidator.validate(
                    respondRequest(guidedInput),
                    output,
                ).map { it.code },
            )
        }

        val legalQuestion = respondOutput().copy(
            messageMarkdown = GUIDED_INTERACTION_MESSAGE,
            interactionDirective = TutorInteractionDirective.Choices(
                promptMarkdown = "这个答案是由哪个条件决定的？",
                choices = listOf(
                    TutorInteractionChoice("sign", "检查导数符号"),
                    TutorInteractionChoice("value", "代入临界点"),
                ),
            ),
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )
        assertTrue(
            ModelTaskCompletionValidator.validate(
                respondRequest(guidedInput),
                legalQuestion,
            ).isEmpty(),
        )
    }

    @Test
    fun locallyAuthorizedSolutionTakesPriorityOverTheModelIntentLabel() {
        val directInput = respondInput().copy(explanationMode = TutorExplanationMode.DIRECT)
        val mislabeledSolution = respondOutput().copy(
            solutionRevealed = true,
            messageMarkdown = "完整解法是先求导，再根据导数符号写出全部单调区间。",
            intentDecision = TutorIntentDecision(
                intent = TutorMessageIntent.CASUAL_CONVERSATION,
                confidence = 0.96,
                explicitActionRequest = false,
                memoryPreference = TutorMemoryPreference.UNCHANGED,
                requestedLocalCapability = TutorRequestedLocalCapability.NONE,
            ),
        )

        assertEquals(mislabeledSolution, mislabeledSolution.locallyConstrainedFor(directInput))
        assertTrue(
            ModelTaskCompletionValidator.validate(
                respondRequest(directInput),
                mislabeledSolution,
            ).isEmpty(),
        )
    }

    @Test
    fun explicitRevealCannotBeSilentlyDowngradedToANonLearningReply() {
        val revealInput = respondInput().copy(
            explanationMode = TutorExplanationMode.GUIDED,
            requestedMove = TutorMoveType.REVEAL_SOLUTION,
        )
        val deniedReveal = respondOutput().copy(
            solutionRevealed = false,
            messageMarkdown = "好的，我们先暂停。",
            intentDecision = TutorIntentDecision(
                intent = TutorMessageIntent.END_OR_PAUSE,
                confidence = 0.98,
                explicitActionRequest = false,
                memoryPreference = TutorMemoryPreference.UNCHANGED,
                requestedLocalCapability = TutorRequestedLocalCapability.NONE,
            ),
        )

        assertTrue(revealInput.authorizesSolutionExposure())
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(
                respondRequest(revealInput),
                deniedReveal,
            ).map { it.code },
        )
    }

    @Test
    fun nonLearningIntentRejectsDeterministicTeachingButAllowsABenignReply() {
        val guidedInput = respondInput().copy(
            explanationMode = TutorExplanationMode.GUIDED,
            requestedMove = null,
        )
        val pauseIntent = TutorIntentDecision(
            intent = TutorMessageIntent.END_OR_PAUSE,
            confidence = 0.98,
            explicitActionRequest = false,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.NONE,
        )
        val unsafeMessages = listOf(
            "最终答案为 B。",
            "先求导，再令 f'(x)=0。",
            "由已知条件推出 x=2。",
        )

        unsafeMessages.forEach { message ->
            val unsafe = respondOutput().copy(
                messageMarkdown = message,
                intentDecision = pauseIntent,
            )
            assertEquals(
                listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
                ModelTaskCompletionValidator.validate(
                    respondRequest(guidedInput),
                    unsafe,
                ).map { it.code },
            )
        }

        val benign = respondOutput().copy(
            messageMarkdown = "好的，这次先暂停。",
            intentDecision = pauseIntent,
        )
        assertTrue(
            ModelTaskCompletionValidator.validate(
                respondRequest(guidedInput),
                benign,
            ).isEmpty(),
        )
    }

    @Test
    fun directAndGuidedExplanationOnlyRepliesRejectQuestionsAnywhereInTheText() {
        val directInput = respondInput().copy(explanationMode = TutorExplanationMode.DIRECT)
        val directWithEmbeddedQuestion = respondOutput().copy(
            solutionRevealed = true,
            messageMarkdown = "你明白吗？接着根据导数符号写出全部单调区间。",
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )
        val guidedInput = respondInput().copy(
            explanationMode = TutorExplanationMode.GUIDED,
            requestedMove = null,
        )
        val guidedWithEmbeddedQuestion = respondOutput().copy(
            messageMarkdown = "先比较导数符号？然后说明函数的变化。",
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )

        listOf(
            respondRequest(directInput) to directWithEmbeddedQuestion,
            respondRequest(guidedInput) to guidedWithEmbeddedQuestion,
        ).forEach { (request, output) ->
            assertEquals(
                listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
                ModelTaskCompletionValidator.validate(request, output).map { it.code },
            )
        }
    }

    @Test
    fun guidedFreeResponseAndVisualTargetAcceptSafeImperativesButRejectAnswerDeclarations() {
        val guidedInput = respondInput().copy(explanationMode = TutorExplanationMode.GUIDED)
        val safeDirectives = listOf<TutorInteractionDirective>(
            TutorInteractionDirective.FreeResponse("请写下下一步判断。"),
            TutorInteractionDirective.VisualTarget("点出图中的临界点。", "critical-point"),
        )

        safeDirectives.forEach { directive ->
            val providerOutput = respondOutput().copy(
                interactionDirective = directive,
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            )
            val constrained = requireNotNull(providerOutput.locallyConstrainedFor(guidedInput))
            assertEquals(GUIDED_INTERACTION_MESSAGE, constrained.messageMarkdown)
            assertEquals(directive, constrained.interactionDirective)
        }

        val answerDeclaration = respondOutput().copy(
            interactionDirective = TutorInteractionDirective.FreeResponse("请写下答案是 2。"),
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(
                respondRequest(guidedInput),
                answerDeclaration,
            ).map { it.code },
        )
    }

    @Test
    fun guidedChoiceLabelsRejectMetaAnswersWithoutRejectingSubjectErrorDescriptions() {
        val guidedInput = respondInput().copy(explanationMode = TutorExplanationMode.GUIDED)
        val safe = respondOutput().copy(
            interactionDirective = TutorInteractionDirective.Choices(
                promptMarkdown = "这一步更像是哪类问题？",
                choices = listOf(
                    TutorInteractionChoice("sign", "符号错误"),
                    TutorInteractionChoice("calculation", "计算错误"),
                ),
            ),
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )
        assertTrue(
            ModelTaskCompletionValidator.validate(
                respondRequest(guidedInput),
                safe.copy(messageMarkdown = GUIDED_INTERACTION_MESSAGE),
            ).isEmpty(),
        )

        listOf("最终选项", "答案 B", "正确答案", "应选").forEachIndexed { index, marker ->
            val unsafe = respondOutput().copy(
                interactionDirective = TutorInteractionDirective.Choices(
                    promptMarkdown = "这一步更像是哪类问题？",
                    choices = listOf(
                        TutorInteractionChoice("unsafe-$index", marker),
                        TutorInteractionChoice("other-$index", "检查条件"),
                    ),
                ),
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            )
            assertEquals(
                listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
                ModelTaskCompletionValidator.validate(
                    respondRequest(guidedInput),
                    unsafe,
                ).map { it.code },
            )
        }
    }

    @Test
    fun directModeAuthorizesSolutionAcrossCompletionAndExposureBoundaries() {
        val directInput = respondInput().copy(
            explanationMode = TutorExplanationMode.DIRECT,
            studentMessage = "这一步为什么要先判断导数符号？",
            requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
        )
        val solution = respondOutput().copy(
            solutionRevealed = true,
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )

        assertTrue(
            ModelTaskCompletionValidator.validate(
                respondRequest(directInput),
                solution,
            ).isEmpty(),
        )
        assertTrue(solution.canExposeSolutionFor(directInput))
    }

    @Test
    fun guidedInteractionTypeAndFieldsRemainModelAuthoredWhileItsLeadInIsLocal() {
        val directives = listOf<TutorInteractionDirective>(
            TutorInteractionDirective.Choices(
                promptMarkdown = "下一步选哪种判断？",
                choices = listOf(
                    TutorInteractionChoice("sign", "判断导数符号"),
                    TutorInteractionChoice("value", "代入临界点"),
                ),
            ),
            TutorInteractionDirective.FreeResponse("下一步应该判断什么？"),
            TutorInteractionDirective.VisualTarget("图中哪个位置是临界点？", "critical-point"),
            TutorInteractionDirective.Continue,
        )
        val guidedInput = respondInput().copy(
            explanationMode = TutorExplanationMode.GUIDED,
        )
        val directInput = guidedInput.copy(
            explanationMode = TutorExplanationMode.DIRECT,
        )
        val revealInput = guidedInput.copy(
            requestedMove = TutorMoveType.REVEAL_SOLUTION,
        )

        directives.forEach { directive ->
            val providerOutput = respondOutput().copy(
                messageMarkdown = "由 f'(x)>0，所以选B。",
                interactionDirective = directive,
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            )
            val output = requireNotNull(providerOutput.locallyConstrainedFor(guidedInput))
            assertEquals(GUIDED_INTERACTION_MESSAGE, output.messageMarkdown)
            assertEquals(directive, output.interactionDirective)
            assertTrue(
                ModelTaskCompletionValidator.validate(
                    respondRequest(guidedInput),
                    output,
                ).isEmpty(),
            )
            listOf(directInput, revealInput).forEach { input ->
                assertEquals(
                    listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
                    ModelTaskCompletionValidator.validate(
                        respondRequest(input),
                        output,
                    ).map { it.code },
                )
            }
        }
    }

    @Test
    fun directCurrentQuestionRejectsInteractionQuestionsAndIncompleteReplies() {
        val directInput = respondInput().copy(explanationMode = TutorExplanationMode.DIRECT)
        val invalid = listOf(
            respondOutput().copy(
                solutionRevealed = false,
                messageMarkdown = "先求导，再判断各区间的符号。",
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
            respondOutput().copy(
                solutionRevealed = true,
                messageMarkdown = "你觉得下一步是什么？",
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
            respondOutput().copy(
                solutionRevealed = true,
                messageMarkdown = "先想一想导数符号。",
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
            respondOutput().copy(
                solutionRevealed = true,
                interactionDirective = TutorInteractionDirective.Continue,
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
            ),
        )
        invalid.forEach { output ->
            assertEquals(
                listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
                ModelTaskCompletionValidator.validate(
                    respondRequest(directInput),
                    output,
                ).map { it.code },
            )
        }
    }

    @Test
    fun directNonQuestionIntentStillUsesTheNonTeachingIntentBoundary() {
        val directInput = respondInput().copy(explanationMode = TutorExplanationMode.DIRECT)
        val casual = respondOutput().copy(
            solutionRevealed = false,
            messageMarkdown = "好的，我们先暂停。",
            interactionDirective = null,
            intentDecision = TutorIntentDecision(
                intent = TutorMessageIntent.END_OR_PAUSE,
                confidence = 0.98,
                explicitActionRequest = false,
                memoryPreference = TutorMemoryPreference.UNCHANGED,
                requestedLocalCapability = TutorRequestedLocalCapability.NONE,
            ),
        )

        assertTrue(
            ModelTaskCompletionValidator.validate(
                respondRequest(directInput),
                casual,
            ).isEmpty(),
        )
    }

    @Test
    fun guidedModeWithoutExplicitRequestRejectsSolutionAcrossBothBoundaries() {
        val guidedInput = respondInput().copy(
            explanationMode = TutorExplanationMode.GUIDED,
            studentMessage = "这一步为什么要先判断导数符号？",
            requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
        )
        val solution = respondOutput().copy(
            solutionRevealed = true,
            intentDecision = TutorIntentDecision.currentQuestionDefault(),
        )

        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_INTENT_BOUNDARY_VIOLATION),
            ModelTaskCompletionValidator.validate(
                respondRequest(guidedInput),
                solution,
            ).map { it.code },
        )
        assertFalse(solution.canExposeSolutionFor(guidedInput))
    }

    @Test
    fun intentCannotRequestALocalCapabilityWithoutAnExplicitMatchingGoal() {
        assertTrue(
            runCatching {
                TutorIntentDecision(
                    intent = TutorMessageIntent.CASUAL_CONVERSATION,
                    confidence = 0.95,
                    explicitActionRequest = true,
                    memoryPreference = TutorMemoryPreference.UNCHANGED,
                    requestedLocalCapability =
                        TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                TutorIntentDecision(
                    intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
                    confidence = 0.95,
                    explicitActionRequest = false,
                    memoryPreference = TutorMemoryPreference.UNCHANGED,
                    requestedLocalCapability =
                        TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
                )
            }.isFailure,
        )
    }

    @Test
    fun tutorResponseTextHistoryAndMoveBudgetsAreStrict() {
        assertTrue(
            runCatching {
                respondInput().copy(
                    studentMessage = "问".repeat(TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS + 1),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                respondInput().copy(
                    priorMessages = List(TutorRespondInput.MAX_PRIOR_MESSAGES + 1) {
                        TutorChatHistoryEntry("为什么？", "仍然只解释当前题。")
                    },
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                respondInput().copy(
                    priorMessages = List(2) {
                        TutorChatHistoryEntry(
                            studentMessage = "问".repeat(TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS),
                            assistantMarkdown = "解".repeat(TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS),
                        )
                    },
                )
            }.isFailure,
        )
        assertTrue(
            runCatching { respondOutput().copy(messageMarkdown = "`不允许的代码`") }.isFailure,
        )
        assertTrue(
            runCatching { respondOutput().copy(messageMarkdown = "查看检索召回结果") }.isFailure,
        )
        assertTrue(
            runCatching {
                output().copy(plan = output().plan.copy(openingMarkdown = "先看原子知识关系"))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                TutorSuggestedMove(
                    id = "internal-label",
                    label = "查看检索召回",
                    type = TutorMoveType.CONNECT_KNOWLEDGE,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                output().copy(
                    plan = output().plan.copy(inferredKnowledgeLabels = listOf("学习投影")),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                respondOutput().copy(
                    suggestedMoves = listOf(
                        TutorSuggestedMove("respond-1", "继续关键一步", TutorMoveType.DEEPEN_REASONING),
                        TutorSuggestedMove("respond-2", "针对当前误区", TutorMoveType.TARGET_MISCONCEPTION),
                        TutorSuggestedMove("respond-3", "换一种表示", TutorMoveType.CHANGE_REPRESENTATION),
                        TutorSuggestedMove("respond-4", "联系当前知识", TutorMoveType.CONNECT_KNOWLEDGE),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                respondOutput().copy(
                    suggestedMoves = listOf(
                        TutorSuggestedMove("duplicate-1", "继续关键一步", TutorMoveType.DEEPEN_REASONING),
                        TutorSuggestedMove("duplicate-2", "再继续一步", TutorMoveType.DEEPEN_REASONING),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun contextualMovesMayBeAbsentAndNeverExceedThree() {
        assertTrue(output().plan.suggestedMoves.isEmpty())
        assertTrue(
            runCatching {
                output().plan.copy(
                    suggestedMoves = listOf(
                        TutorSuggestedMove("a", "看关键条件", TutorMoveType.DEEPEN_REASONING),
                        TutorSuggestedMove("b", "换成图像", TutorMoveType.CHANGE_REPRESENTATION),
                        TutorSuggestedMove("c", "联系定义", TutorMoveType.CONNECT_KNOWLEDGE),
                        TutorSuggestedMove("d", "查看讲解", TutorMoveType.REVEAL_SOLUTION),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun boundedVisualScenesRoundTripIndependentlyFromTheOptionalDiagnostic() {
        listOf(
            stepFlowScene(),
            comparisonScene(),
            evidenceChainScene(),
            processTimelineScene(),
            conceptMapScene(),
            formulaDerivationScene(),
            spatialDiagramScene(),
            circuitDiagramScene(),
        ).forEach { scene ->
            val explanationOnly = output().copy(
                plan = output().plan.copy(
                    diagnosticItem = null,
                    visualScene = scene,
                ),
            )

            assertEquals(
                explanationOnly,
                ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(explanationOnly)),
            )
            assertTrue(ModelTaskCompletionValidator.validate(request(), explanationOnly).isEmpty())
        }
    }

    @Test
    fun persistedOutputWithoutVisualSceneFieldStillDecodes() {
        val encoded = ModelTaskCodec.encodeOutput(output())
        val legacySnapshot = encoded.replace("\"visualScene\":null,", "")

        assertTrue(encoded != legacySnapshot)
        assertEquals(output(), ModelTaskCodec.decodeOutput(legacySnapshot))
    }

    @Test
    fun visualScenesEnforceShapeAndTotalBudgets() {
        val invalidFactories: List<() -> Any> = listOf(
            { stepFlowScene().copy(title = "题".repeat(TutorVisualScene.MAX_TITLE_CHARS + 1)) },
            { stepFlowScene().copy(steps = stepFlowScene().steps.take(1)) },
            {
                stepFlowScene().copy(
                    steps = (1..TutorVisualScene.MAX_STEP_COUNT).map { index ->
                        TutorSceneStep(
                            stepId = "large-step-$index",
                            label = "步骤$index",
                            bodyMarkdown = "甲".repeat(TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS),
                            formula = "x".repeat(TutorVisualScene.MAX_FORMULA_CHARS),
                        )
                    },
                )
            },
            {
                comparisonScene().copy(
                    rows = (1..TutorVisualScene.MAX_COMPARISON_ROW_COUNT + 1).map { index ->
                        comparisonScene().rows.single().copy(rowId = "row-$index")
                    },
                )
            },
            {
                evidenceChainScene().copy(
                    evidence = (1..TutorVisualScene.MAX_EVIDENCE_COUNT + 1).map { index ->
                        evidenceChainScene().evidence.first().copy(pointId = "point-$index")
                    },
                )
            },
            { processTimelineScene().copy(stages = processTimelineScene().stages.take(1)) },
            { conceptMapScene().copy(relations = conceptMapScene().relations.take(1)) },
            { formulaDerivationScene().copy(steps = emptyList()) },
            { spatialDiagramScene().copy(nodes = spatialDiagramScene().nodes.take(1)) },
            {
                spatialDiagramScene().copy(
                    nodes = spatialDiagramScene().nodes.mapIndexed { index, node ->
                        if (index == 1) node.copy(anchor = TutorDiagramAnchor.CENTER) else node
                    },
                )
            },
            {
                spatialDiagramScene().copy(
                    edges = spatialDiagramScene().edges.mapIndexed { index, edge ->
                        if (index == 0) edge.copy(toNodeId = "missing-node") else edge
                    },
                )
            },
            {
                TutorDiagramNode(
                    nodeId = "long-circle",
                    label = "圆形标签确实太长",
                    anchor = TutorDiagramAnchor.TOP,
                    shape = TutorDiagramNodeShape.CIRCLE,
                )
            },
            { spatialDiagramScene().copy(title = "原子知识关系") },
            {
                processTimelineScene().copy(
                    stages = processTimelineScene().stages.mapIndexed { index, stage ->
                        if (index == processTimelineScene().stages.lastIndex) {
                            stage.copy(transitionMarkdown = "不存在的下一阶段")
                        } else {
                            stage
                        }
                    },
                )
            },
            {
                conceptMapScene().copy(
                    relations = conceptMapScene().relations.mapIndexed { index, relation ->
                        if (index == 0) {
                            relation.copy(
                                relationLabel = "关系".repeat(
                                    TutorVisualScene.MAX_RELATION_LABEL_CHARS,
                                ),
                            )
                        } else {
                            relation
                        }
                    },
                )
            },
            {
                stepFlowScene().copy(
                    steps = listOf(
                        stepFlowScene().steps.first(),
                        stepFlowScene().steps.first(),
                    ),
                )
            },
            { stepFlowScene().copy(schemaVersion = TutorVisualScene.SCHEMA_VERSION + 1) },
        )

        invalidFactories.forEach { factory -> assertTrue(runCatching(factory).isFailure) }
    }

    @Test
    fun spatialDiagramRejectsLinesThatHideNodesOrCrossEachOther() {
        val passThroughNode = listOf(
            TutorDiagramNode("left", "A", TutorDiagramAnchor.LEFT, TutorDiagramNodeShape.POINT),
            TutorDiagramNode("center", "B", TutorDiagramAnchor.CENTER, TutorDiagramNodeShape.POINT),
            TutorDiagramNode("right", "C", TutorDiagramAnchor.RIGHT, TutorDiagramNodeShape.POINT),
        )
        assertTrue(
            runCatching {
                TutorSpatialDiagramScene(
                    sceneId = "pass-through",
                    title = "不可穿过中间点",
                    nodes = passThroughNode,
                    edges = listOf(
                        TutorDiagramEdge(
                            edgeId = "pass-through-edge",
                            fromNodeId = "left",
                            toNodeId = "right",
                            style = TutorDiagramEdgeStyle.LINE,
                        ),
                    ),
                )
            }.isFailure,
        )

        val crossingNodes = listOf(
            TutorDiagramNode("top-left", "A", TutorDiagramAnchor.TOP_LEFT, TutorDiagramNodeShape.POINT),
            TutorDiagramNode("top-right", "B", TutorDiagramAnchor.TOP_RIGHT, TutorDiagramNodeShape.POINT),
            TutorDiagramNode(
                "bottom-left",
                "C",
                TutorDiagramAnchor.BOTTOM_LEFT,
                TutorDiagramNodeShape.POINT,
            ),
            TutorDiagramNode(
                "bottom-right",
                "D",
                TutorDiagramAnchor.BOTTOM_RIGHT,
                TutorDiagramNodeShape.POINT,
            ),
        )
        assertTrue(
            runCatching {
                TutorSpatialDiagramScene(
                    sceneId = "crossing",
                    title = "不可交叉",
                    nodes = crossingNodes,
                    edges = listOf(
                        TutorDiagramEdge(
                            "diagonal-a",
                            "top-left",
                            "bottom-right",
                            style = TutorDiagramEdgeStyle.LINE,
                        ),
                        TutorDiagramEdge(
                            "diagonal-b",
                            "top-right",
                            "bottom-left",
                            style = TutorDiagramEdgeStyle.LINE,
                        ),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun circuitDiagramUsesOnlyConnectedAlignedComponentsAndSolidWires() {
        val circuit = circuitDiagramScene()

        assertTrue(circuit.isCircuitDiagram)
        assertEquals(8, circuit.nodes.size)
        assertTrue(
            runCatching {
                circuit.copy(
                    edges = circuit.edges.mapIndexed { index, edge ->
                        if (index == 0) edge.copy(style = TutorDiagramEdgeStyle.ARROW) else edge
                    },
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                circuit.copy(
                    nodes = circuit.nodes.mapIndexed { index, node ->
                        if (index == 0) node.copy(shape = TutorDiagramNodeShape.BLOCK) else node
                    },
                )
            }.isFailure,
        )
    }

    @Test
    fun visualSceneContentRejectsActiveOrRemoteMarkupAndUnsupportedFormulaCommands() {
        listOf(
            "https://example.com/answer",
            "<div>答案</div>",
            "`println(1)`",
            "![图](asset.png)",
            "[答案](https://example.com)",
            "答案\u202E反转",
        ).forEach { unsafe ->
            assertTrue(
                unsafe,
                runCatching {
                    stepFlowScene().copy(
                        steps = stepFlowScene().steps.mapIndexed { index, step ->
                            if (index == 0) step.copy(bodyMarkdown = unsafe) else step
                        },
                    )
                }.isFailure,
            )
        }
        assertTrue(
            runCatching {
                stepFlowScene().copy(
                    steps = stepFlowScene().steps.mapIndexed { index, step ->
                        if (index == 0) step.copy(formula = "\\href{https://example.com}{x}") else step
                    },
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                processTimelineScene().copy(
                    stages = processTimelineScene().stages.mapIndexed { index, stage ->
                        if (index == 0) stage.copy(transitionMarkdown = "<script>run()</script>") else stage
                    },
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                conceptMapScene().copy(
                    relations = conceptMapScene().relations.mapIndexed { index, relation ->
                        if (index == 0) {
                            relation.copy(targetMarkdown = "[外部答案](https://example.com)")
                        } else {
                            relation
                        }
                    },
                )
            }.isFailure,
        )
    }

    @Test
    fun meaningfulTwoChoiceInteractionIsAllowedWhenTheCurrentQuestionNeedsIt() {
        val twoChoicePlan = output().plan.copy(
            diagnosticItem = requireNotNull(output().plan.diagnosticItem).copy(
                choices = requireNotNull(output().plan.diagnosticItem).choices.take(2),
            ),
        )

        assertEquals(2, requireNotNull(twoChoicePlan.diagnosticItem).choices.size)
    }

    @Test
    fun actionOnlyConversationMemoryRetainsTheDirectTeachingBoundary() {
        val memory = TutorConversationMemory(
            completedCycleCount = 1,
            answeredTurnCount = 0,
            correctChoiceCount = 0,
            lastRequestedMove = TutorMoveType.CHANGE_REPRESENTATION,
            solutionWasRevealed = true,
        )

        assertEquals(0, memory.answeredTurnCount)
        assertEquals(null, memory.lastFeedbackMarkdown)
        assertEquals(TutorMoveType.CHANGE_REPRESENTATION, memory.lastRequestedMove)
        assertTrue(memory.solutionWasRevealed)
        val laterRequest = request().copy(
            input = input().copy(cycleOrdinal = 2, priorConversationMemory = memory),
        )
        assertEquals(
            laterRequest,
            ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(laterRequest)),
        )
    }

    @Test
    fun laterCyclePreservesExactEarlierStudentMessagesInOrder() {
        val memory = TutorConversationMemory(
            completedCycleCount = 1,
            answeredTurnCount = 0,
            correctChoiceCount = 0,
            solutionWasRevealed = true,
        )
        val exactMessages = listOf(
            "  我卡在配方法第二步\n",
            "为什么这里要同时加上 4？  ",
        )
        val laterRequest = request().copy(
            input = input().copy(
                cycleOrdinal = 2,
                priorConversationMemory = memory,
                priorCycleStudentMessages = exactMessages,
            ),
        )

        val decoded = ModelTaskCodec.decodeRequest(
            ModelTaskCodec.encodeRequest(laterRequest),
        ).input as TutorPlanInput

        assertEquals(exactMessages, decoded.priorCycleStudentMessages)
    }

    @Test
    fun earlierStudentMessageBudgetsAndCycleBoundaryAreStrict() {
        val memory = TutorConversationMemory(
            completedCycleCount = 1,
            answeredTurnCount = 0,
            correctChoiceCount = 0,
            solutionWasRevealed = true,
        )
        assertTrue(
            runCatching {
                input().copy(priorCycleStudentMessages = listOf("不应出现在第一轮"))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                input().copy(
                    cycleOrdinal = 2,
                    priorConversationMemory = memory,
                    priorCycleStudentMessages = List(
                        TutorPlanInput.MAX_PRIOR_CYCLE_STUDENT_MESSAGES + 1,
                    ) { "卡点-$it" },
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                input().copy(
                    cycleOrdinal = 2,
                    priorConversationMemory = memory,
                    priorCycleStudentMessages = List(6) {
                        "问".repeat(TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS)
                    },
                )
            }.isFailure,
        )
    }

    @Test
    fun conversationMemoryRequiresFeedbackExactlyWhenAChoiceWasAnswered() {
        assertTrue(
            runCatching {
                TutorConversationMemory(
                    completedCycleCount = 1,
                    answeredTurnCount = 0,
                    correctChoiceCount = 0,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                TutorConversationMemory(
                    completedCycleCount = 1,
                    answeredTurnCount = 1,
                    correctChoiceCount = 1,
                    solutionWasRevealed = true,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                TutorConversationMemory(
                    completedCycleCount = 1,
                    answeredTurnCount = 0,
                    correctChoiceCount = 0,
                    lastFeedbackMarkdown = "不能伪造选择反馈",
                    solutionWasRevealed = true,
                )
            }.isFailure,
        )
    }

    @Test
    fun modelCannotClaimEvidenceThatWasNotDisclosed() {
        val invalid = output().copy(
            plan = output().plan.copy(targetedEvidenceLabels = listOf("未发送的知识点")),
        )

        assertEquals(
            listOf(ModelTaskCompletionIssueCode.MODEL_CLAIMED_UNDISCLOSED_EVIDENCE),
            ModelTaskCompletionValidator.validate(request(), invalid).map { it.code },
        )
    }

    @Test
    fun modelCannotUseMasteredEvidenceAsTheDiagnosticTarget() {
        val request = request().copy(
            input = input().copy(
                relevantLearningEvidence = listOf(
                    TutorKnowledgeEvidence(
                        "node-linear",
                        "一次函数基础",
                        TutorEvidenceLevel.MASTERED,
                        0.92,
                    ),
                ),
            ),
        )
        val output = output().copy(
            plan = output().plan.copy(targetedEvidenceLabels = listOf("一次函数基础")),
        )

        assertEquals(
            listOf(ModelTaskCompletionIssueCode.MODEL_TARGETED_MASTERED_EVIDENCE),
            ModelTaskCompletionValidator.validate(request, output).map { it.code },
        )
    }

    @Test
    fun tutorEgressAllowsOnlyConfirmedDocumentAndRelevantSummary() {
        val request = request()
        val provider = provider()

        val execution = ModelEgressPolicy.authorize(request, provider, 1)

        assertTrue(execution.permit is ModelExecutionPermit.External)
        assertTrue(requireNotNull(request.egressManifest).assets.isEmpty())
        assertEquals(
            ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
            request.egressManifest.disclosedData,
        )
    }

    @Test
    fun followUpTurnCarriesOnlyBoundedContiguousConversationEvidence() {
        val history = TutorTurnHistoryEntry(
            turnOrdinal = 1,
            diagnosticStemMarkdown = "若导数先正后负，原函数怎样变化？",
            selectedChoiceMarkdown = "先减后增",
            selectionWasCorrect = false,
            feedbackMarkdown = "你把导数正负与增减的对应关系反过来了。",
            requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
        )
        val request = request().copy(
            input = input().copy(turnOrdinal = 2, priorTurns = listOf(history)),
        )
        val output = output().copy(turnOrdinal = 2)

        assertEquals(
            request,
            ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request)),
        )
        assertTrue(ModelTaskCompletionValidator.validate(request, output).isEmpty())
    }

    @Test
    fun outputCannotJumpToAnotherConversationTurn() {
        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH),
            ModelTaskCompletionValidator.validate(
                request(),
                output().copy(turnOrdinal = 2),
            ).map { it.code },
        )
    }

    @Test
    fun outputCannotJumpToAnotherConversationCycle() {
        val memory = TutorConversationMemory(
            completedCycleCount = 1,
            answeredTurnCount = 4,
            correctChoiceCount = 2,
            lastFeedbackMarkdown = "定义域已稳定，符号变化仍需练习。",
        )
        val laterRequest = request().copy(
            input = input().copy(cycleOrdinal = 2, priorConversationMemory = memory),
        )

        assertEquals(
            listOf(ModelTaskCompletionIssueCode.TUTOR_CONTEXT_MISMATCH),
            ModelTaskCompletionValidator.validate(
                laterRequest,
                output().copy(cycleOrdinal = 1),
            ).map { it.code },
        )
    }

    @Test
    fun staleQuestionMemoryCannotExposeAFalseRetentionEstimate() {
        assertTrue(
            runCatching {
                TutorQuestionLearningEvidence(
                    independentRecallCount = 1,
                    assistedRecallCount = 0,
                    retrievalFailureCount = 1,
                    answerRevealCount = 0,
                    retentionEstimate = 0.7,
                    reviewStatus = TutorQuestionReviewStatus.STALE,
                )
            }.isFailure,
        )
    }

    private fun request(): ModelTaskRequest {
        val provider = provider()
        return ModelTaskRequest(
            requestId = "tutor-plan-request",
            input = input(),
            occurredAtEpochMillis = 1,
            egressManifest = ModelEgressManifest(
                authorizationId = "tutor-authorization",
                subjectId = SESSION_ID,
                purpose = ModelEgressPurpose.TUTORING,
                authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_PLAN),
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_PLAN,
                approvedAtEpochMillis = 1,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
                prohibitedData = ModelEgressManifest.TUTOR_PLAN_PROHIBITED_DATA,
            ),
        )
    }

    private fun input() = TutorPlanInput(
        sessionId = SESSION_ID,
        draftRevisionNumber = 2,
        subject = "MATH",
        questionDocument = QuestionDocument(
            id = "question-1",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        relevantLearningEvidence = listOf(
            TutorKnowledgeEvidence("node-derivative", "导数符号", TutorEvidenceLevel.LEARNING, 0.35),
        ),
        projectionIsCurrent = true,
    )

    private fun output() = TutorPlanOutput(
        sessionId = SESSION_ID,
        draftRevisionNumber = 2,
        questionDocumentId = "question-1",
        plan = TutorTurnPlan(
            openingMarkdown = "先判断导数的符号如何变化。",
            diagnosticItem = TutorAssessmentItem(
                id = "diagnostic-1",
                stemMarkdown = "若导数先正后负，原函数怎样变化？",
                choices = listOf(
                    TutorChoice("a", "先增后减", "抓住了导数符号与单调性的对应。"),
                    TutorChoice("b", "先减后增", "你把正负对应关系反过来了。"),
                    TutorChoice("c", "始终递增", "需要关注导数变号，而不只是出现过正值。"),
                ),
                correctChoiceId = "a",
            ),
            solutionMarkdown = "先求导，再解导数大于零与小于零的区间。",
            alternateMethodMarkdown = "也可以画出导函数的符号表，从图像变化理解单调性。",
            difficultyReasonMarkdown = "这里区分符号对应错误与变号遗漏。",
            targetedEvidenceLabels = listOf("导数符号"),
            inferredKnowledgeLabels = listOf("导数", "函数单调性"),
        ),
        modelVersion = "model-v1",
    )

    private fun respondInput() = TutorRespondInput(
        sessionId = SESSION_ID,
        draftRevisionNumber = 2,
        subject = "MATH",
        questionDocument = input().questionDocument,
        relevantLearningEvidence = input().relevantLearningEvidence,
        projectionIsCurrent = true,
        questionLearningEvidence = null,
        responseOrdinal = 3,
        studentMessage = "为什么导数为正时原函数递增？",
        visibleTutorContextMarkdown = "刚才已经确认要从导数符号理解当前题。",
        priorMessages = listOf(
            TutorChatHistoryEntry(
                studentMessage = "先看哪一步？",
                assistantMarkdown = "先确定导数在各区间的符号。",
            ),
        ),
        requestedMove = TutorMoveType.DEEPEN_REASONING,
    )

    private fun respondOutput() = TutorRespondOutput(
        sessionId = SESSION_ID,
        draftRevisionNumber = 2,
        questionDocumentId = "question-1",
        responseOrdinal = 3,
        messageMarkdown = "因为导数描述原函数的瞬时变化方向，导数为正表示函数值随自变量增加而上升。",
        modelVersion = "model-v1",
    )

    private fun respondRequest(
        input: TutorRespondInput = respondInput(),
    ): ModelTaskRequest {
        val provider = provider().copy(supportedTasks = setOf(ModelTaskKind.TUTOR_RESPOND))
        return ModelTaskRequest(
            requestId = "tutor-respond-request",
            input = input,
            occurredAtEpochMillis = 1,
            egressManifest = ModelEgressManifest(
                authorizationId = "tutor-respond-authorization",
                subjectId = SESSION_ID,
                purpose = ModelEgressPurpose.TUTORING,
                authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_RESPOND),
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_RESPOND,
                approvedAtEpochMillis = 1,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
                prohibitedData = ModelEgressManifest.TUTOR_RESPOND_PROHIBITED_DATA,
            ),
        )
    }

    private fun stepFlowScene() = TutorStepFlowScene(
        sceneId = "scene-step",
        title = "解题路径",
        steps = listOf(
            TutorSceneStep(
                stepId = "step-1",
                label = "判断符号",
                bodyMarkdown = "先确定导数为正和为负的区间。",
                formula = "f'(x)>0",
                emphasis = TutorSceneEmphasis.KEY,
            ),
            TutorSceneStep(
                stepId = "step-2",
                label = "写出结论",
                bodyMarkdown = "把符号区间对应到原函数的增减性。",
                emphasis = TutorSceneEmphasis.CHECK,
            ),
        ),
    )

    private fun comparisonScene() = TutorComparisonScene(
        sceneId = "scene-comparison",
        title = "两种判断对照",
        leftTitle = "导数为正",
        rightTitle = "导数为负",
        rows = listOf(
            TutorComparisonRow(
                rowId = "comparison-1",
                criterion = "原函数变化",
                leftMarkdown = "原函数递增",
                rightMarkdown = "原函数递减",
                takeawayMarkdown = "先判断导数符号，再对应增减性。",
            ),
        ),
    )

    private fun evidenceChainScene() = TutorEvidenceChainScene(
        sceneId = "scene-evidence",
        title = "推理依据",
        claimMarkdown = "函数在目标区间内先增后减。",
        evidence = listOf(
            TutorEvidencePoint(
                pointId = "evidence-1",
                kind = TutorEvidencePointKind.GIVEN,
                markdown = "导数在分界点两侧由正变负。",
            ),
            TutorEvidencePoint(
                pointId = "evidence-2",
                kind = TutorEvidencePointKind.INFERENCE,
                markdown = "导数正负分别对应原函数递增和递减。",
            ),
        ),
        conclusionMarkdown = "因此原函数先增后减。",
    )

    private fun processTimelineScene() = TutorProcessTimelineScene(
        sceneId = "scene-process",
        title = "反应变化过程",
        stages = listOf(
            TutorProcessStage(
                stageId = "stage-1",
                label = "开始",
                bodyMarkdown = "反应物充分接触。",
                transitionMarkdown = "达到反应条件",
            ),
            TutorProcessStage(
                stageId = "stage-2",
                label = "变化后",
                bodyMarkdown = "生成物比例趋于稳定。",
            ),
        ),
    )

    private fun conceptMapScene() = TutorConceptMapScene(
        sceneId = "scene-concept",
        title = "函数关系",
        centerMarkdown = "导数符号",
        relations = listOf(
            TutorConceptRelation(
                relationId = "relation-1",
                relationLabel = "决定",
                targetMarkdown = "原函数增减性",
            ),
            TutorConceptRelation(
                relationId = "relation-2",
                relationLabel = "发生改变时",
                targetMarkdown = "可能出现极值",
                detailMarkdown = "还要核对定义域和变号方向。",
            ),
        ),
    )

    private fun formulaDerivationScene() = TutorFormulaDerivationScene(
        sceneId = "scene-formula-derivation",
        title = "配方过程",
        startFormula = "x^2+4x+1",
        steps = listOf(
            TutorFormulaDerivationStep(
                stepId = "derivation-1",
                reasonMarkdown = "先补成完全平方。",
                resultFormula = "x^2+4x+4-3",
            ),
            TutorFormulaDerivationStep(
                stepId = "derivation-2",
                reasonMarkdown = "把前三项写成平方。",
                resultFormula = "(x+2)^2-3",
            ),
        ),
    )

    private fun spatialDiagramScene() = TutorSpatialDiagramScene(
        sceneId = "scene-spatial",
        title = "物体受到哪些力",
        nodes = listOf(
            TutorDiagramNode(
                nodeId = "spatial-object",
                label = "物体",
                anchor = TutorDiagramAnchor.CENTER,
                shape = TutorDiagramNodeShape.BLOCK,
            ),
            TutorDiagramNode(
                nodeId = "spatial-normal",
                label = "N",
                anchor = TutorDiagramAnchor.TOP,
                shape = TutorDiagramNodeShape.CIRCLE,
            ),
            TutorDiagramNode(
                nodeId = "spatial-gravity",
                label = "G",
                anchor = TutorDiagramAnchor.BOTTOM,
                shape = TutorDiagramNodeShape.CIRCLE,
            ),
        ),
        edges = listOf(
            TutorDiagramEdge(
                edgeId = "spatial-normal-edge",
                fromNodeId = "spatial-object",
                toNodeId = "spatial-normal",
                label = "支持力",
                style = TutorDiagramEdgeStyle.ARROW,
            ),
            TutorDiagramEdge(
                edgeId = "spatial-gravity-edge",
                fromNodeId = "spatial-object",
                toNodeId = "spatial-gravity",
                label = "重力",
                style = TutorDiagramEdgeStyle.ARROW,
            ),
        ),
        captionMarkdown = "箭头从受力物体出发，只表示当前题已知的两个力。",
    )

    private fun circuitDiagramScene() = TutorSpatialDiagramScene(
        sceneId = "scene-circuit",
        title = "串联电路中的元件",
        nodes = listOf(
            TutorDiagramNode(
                "circuit-top-left",
                "接点",
                TutorDiagramAnchor.TOP_LEFT,
                TutorDiagramNodeShape.JUNCTION,
            ),
            TutorDiagramNode(
                "circuit-resistor",
                "电阻",
                TutorDiagramAnchor.TOP,
                TutorDiagramNodeShape.RESISTOR,
            ),
            TutorDiagramNode(
                "circuit-top-right",
                "接点",
                TutorDiagramAnchor.TOP_RIGHT,
                TutorDiagramNodeShape.JUNCTION,
            ),
            TutorDiagramNode(
                "circuit-lamp",
                "灯泡",
                TutorDiagramAnchor.RIGHT,
                TutorDiagramNodeShape.LAMP,
            ),
            TutorDiagramNode(
                "circuit-bottom-right",
                "接点",
                TutorDiagramAnchor.BOTTOM_RIGHT,
                TutorDiagramNodeShape.JUNCTION,
            ),
            TutorDiagramNode(
                "circuit-battery",
                "电源",
                TutorDiagramAnchor.BOTTOM,
                TutorDiagramNodeShape.BATTERY,
            ),
            TutorDiagramNode(
                "circuit-bottom-left",
                "接点",
                TutorDiagramAnchor.BOTTOM_LEFT,
                TutorDiagramNodeShape.JUNCTION,
            ),
            TutorDiagramNode(
                "circuit-switch",
                "开关",
                TutorDiagramAnchor.LEFT,
                TutorDiagramNodeShape.SWITCH_OPEN,
            ),
        ),
        edges = listOf(
            TutorDiagramEdge(
                "wire-1",
                "circuit-top-left",
                "circuit-resistor",
                style = TutorDiagramEdgeStyle.LINE,
            ),
            TutorDiagramEdge(
                "wire-2",
                "circuit-resistor",
                "circuit-top-right",
                style = TutorDiagramEdgeStyle.LINE,
            ),
            TutorDiagramEdge(
                "wire-3",
                "circuit-top-right",
                "circuit-lamp",
                style = TutorDiagramEdgeStyle.LINE,
            ),
            TutorDiagramEdge(
                "wire-4",
                "circuit-lamp",
                "circuit-bottom-right",
                style = TutorDiagramEdgeStyle.LINE,
            ),
            TutorDiagramEdge(
                "wire-5",
                "circuit-bottom-right",
                "circuit-battery",
                style = TutorDiagramEdgeStyle.LINE,
            ),
            TutorDiagramEdge(
                "wire-6",
                "circuit-battery",
                "circuit-bottom-left",
                style = TutorDiagramEdgeStyle.LINE,
            ),
            TutorDiagramEdge(
                "wire-7",
                "circuit-bottom-left",
                "circuit-switch",
                style = TutorDiagramEdgeStyle.LINE,
            ),
            TutorDiagramEdge(
                "wire-8",
                "circuit-switch",
                "circuit-top-left",
                style = TutorDiagramEdgeStyle.LINE,
            ),
        ),
        captionMarkdown = "开关目前断开，闭合后电流流过电阻和灯泡。",
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider-1",
        providerDisplayName = "兼容模型",
        modelId = "model-1",
        supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        providerConfigurationVersion = "configuration-v1",
    )

    private companion object {
        const val SESSION_ID = "tutor-session-1"
    }
}

private val oldFingerprintJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
