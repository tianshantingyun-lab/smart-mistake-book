package com.tingyun.smartmistakebook.core.mastery.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong
import kotlin.random.Random

class LocalMasteryPolicyPropertyTest {
    @Test
    fun independentCorrectNeverLowersAndExplicitErrorNeverRaisesHistoricalMastery() {
        repeat(256) { seed ->
            val random = Random(seed)
            val positive = random.nextLong(0L, 20_000_000L)
            val negative = random.nextLong(0L, 20_000_000L)
            val current = projection(positive, negative)
            val mass = random.nextLong(1L, LocalMasteryPolicy.FULL_MASS_MICROS + 1L)

            val afterCorrect =
                LocalMasteryPolicy.nextProjection(
                    learnerId = LEARNER_ID,
                    event = event("positive-$seed", MasteryEventDirection.POSITIVE),
                    attribution = eventAttribution("positive-$seed", mass),
                    current = current,
                )
            val afterError =
                LocalMasteryPolicy.nextProjection(
                    learnerId = LEARNER_ID,
                    event = event("negative-$seed", MasteryEventDirection.NEGATIVE),
                    attribution = eventAttribution("negative-$seed", mass),
                    current = current,
                )

            assertTrue(afterCorrect.masteryScoreMicros >= current.masteryScoreMicros)
            assertTrue(afterError.masteryScoreMicros <= current.masteryScoreMicros)
            assertEquals(
                current.negativeEvidenceMicros,
                afterCorrect.negativeEvidenceMicros,
            )
            assertEquals(
                current.positiveEvidenceMicros,
                afterError.positiveEvidenceMicros,
            )
        }
    }

    @Test
    fun repeatedExplicitErrorLowersStableMasteryAndLaterIndependentCorrectRestoresIt() {
        var current: MasteryKnowledgeProjectionEntity? = null
        for (index in 0 until 20) {
            current =
                LocalMasteryPolicy.nextProjection(
                    learnerId = LEARNER_ID,
                    event = event("build-positive-$index", MasteryEventDirection.POSITIVE),
                    attribution = eventAttribution("build-positive-$index", LocalMasteryPolicy.FULL_MASS_MICROS),
                    current = current,
                )
            if (checkNotNull(current).masteryState == KnowledgeMasteryState.STEADY.name) {
                break
            }
        }
        assertEquals(KnowledgeMasteryState.STEADY.name, checkNotNull(current).masteryState)

        for (index in 0 until 20) {
            current =
                LocalMasteryPolicy.nextProjection(
                    learnerId = LEARNER_ID,
                    event = event("re-error-$index", MasteryEventDirection.NEGATIVE),
                    attribution = eventAttribution("re-error-$index", LocalMasteryPolicy.FULL_MASS_MICROS),
                    current = current,
                )
            if (checkNotNull(current).masteryState != KnowledgeMasteryState.STEADY.name) {
                break
            }
        }
        assertTrue(checkNotNull(current).masteryState != KnowledgeMasteryState.STEADY.name)

        for (index in 0 until 20) {
            current =
                LocalMasteryPolicy.nextProjection(
                    learnerId = LEARNER_ID,
                    event = event("re-correct-$index", MasteryEventDirection.POSITIVE),
                    attribution = eventAttribution("re-correct-$index", LocalMasteryPolicy.FULL_MASS_MICROS),
                    current = current,
                )
            if (checkNotNull(current).masteryState == KnowledgeMasteryState.STEADY.name) {
                break
            }
        }
        assertEquals(KnowledgeMasteryState.STEADY.name, checkNotNull(current).masteryState)
    }

    @Test
    fun answerRevealAndSelfReportAlwaysHaveZeroProjectionAuthority() {
        listOf(
            fact(
                assistance = ObservedAssistance.ANSWER_REVEALED,
                authority = MasteryEvidenceAuthority.LOCAL_VERIFIED,
            ),
            fact(
                assistance = ObservedAssistance.INDEPENDENT,
                authority = MasteryEvidenceAuthority.SELF_REPORTED,
            ),
        ).forEachIndexed { index, fact ->
            val decision = evaluate(fact, candidate("inert-$index"))
            assertTrue(decision is LocalMasteryAdmissionDecision.KeepInert)
        }
    }

    @Test
    fun answerRevealFactCannotBypassTheGateWithAnInconsistentAssistanceLabel() {
        val decision =
            evaluate(
                fact().copy(
                    independentlyAnswered = false,
                    answerRevealed = true,
                    assistance = ObservedAssistance.INDEPENDENT.name,
                ),
                candidate("revealed-with-stale-assistance"),
            ) as LocalMasteryAdmissionDecision.KeepInert

        assertEquals(
            LearningObservationInertReason.ANSWER_ALREADY_REVEALED,
            decision.reason,
        )
    }

    @Test
    fun hintsAndRetriesCanOnlyReducePositiveEvidenceQuality() {
        val independentFirst =
            admittedMass(
                assistance = ObservedAssistance.INDEPENDENT,
                retryState = ObservedRetryState.FIRST_ATTEMPT,
            )
        val oneHint =
            admittedMass(
                assistance = ObservedAssistance.ONE_HINT,
                retryState = ObservedRetryState.FIRST_ATTEMPT,
            )
        val multipleHints =
            admittedMass(
                assistance = ObservedAssistance.MULTIPLE_HINTS,
                retryState = ObservedRetryState.FIRST_ATTEMPT,
            )
        val oneRetry =
            admittedMass(
                assistance = ObservedAssistance.INDEPENDENT,
                retryState = ObservedRetryState.ONE_RETRY,
            )
        val multipleRetries =
            admittedMass(
                assistance = ObservedAssistance.INDEPENDENT,
                retryState = ObservedRetryState.MULTIPLE_RETRIES,
            )

        assertTrue(independentFirst >= oneHint)
        assertTrue(oneHint >= multipleHints)
        assertTrue(independentFirst >= oneRetry)
        assertTrue(oneRetry >= multipleRetries)
    }

    @Test
    fun observedHintCountCannotBypassTheDiscountWithAnInconsistentAssistanceLabel() {
        val unassisted = admittedMass(fact(), candidate("unassisted"))
        val staleAssistance =
            admittedMass(
                fact().copy(
                    independentlyAnswered = false,
                    hintCount = 1,
                    assistance = ObservedAssistance.INDEPENDENT.name,
                ),
                candidate("hint-with-stale-assistance"),
            )

        assertTrue(staleAssistance < unassisted)
    }

    @Test
    fun evidenceQualityUsesTheMoreConservativeHelpFactInBothMismatchDirections() {
        val unassisted = fact()
        val countOnlyOneHint =
            unassisted.copy(
                independentlyAnswered = false,
                hintCount = 1,
                assistance = ObservedAssistance.INDEPENDENT.name,
            )
        val labelOnlyOneHint =
            fact(assistance = ObservedAssistance.ONE_HINT).copy(
                independentlyAnswered = false,
                hintCount = 0,
            )
        val consistentOneHint = labelOnlyOneHint.copy(hintCount = 1)
        val labelOnlyMultipleHints =
            fact(assistance = ObservedAssistance.MULTIPLE_HINTS).copy(
                independentlyAnswered = false,
                hintCount = 0,
            )
        val consistentMultipleHints = labelOnlyMultipleHints.copy(hintCount = 2)

        val unassistedQuality = LocalMasteryPolicy.evidenceQualityMicros(unassisted)
        val oneHintQuality = LocalMasteryPolicy.evidenceQualityMicros(consistentOneHint)
        val multipleHintsQuality =
            LocalMasteryPolicy.evidenceQualityMicros(consistentMultipleHints)

        assertEquals(
            oneHintQuality,
            LocalMasteryPolicy.evidenceQualityMicros(countOnlyOneHint),
        )
        assertEquals(
            oneHintQuality,
            LocalMasteryPolicy.evidenceQualityMicros(labelOnlyOneHint),
        )
        assertEquals(
            multipleHintsQuality,
            LocalMasteryPolicy.evidenceQualityMicros(labelOnlyMultipleHints),
        )
        assertTrue(oneHintQuality < unassistedQuality)
        assertTrue(multipleHintsQuality < oneHintQuality)
    }

    @Test
    fun aSourceFactWithoutAnExplicitLearningEpisodeIsInert() {
        val missingId = evaluate(fact().copy(presentationId = ""), candidate("missing-id"))
        val missingFingerprint =
            evaluate(
                fact().copy(presentationFingerprint = ""),
                candidate("missing-fingerprint"),
            )

        assertEquals(
            LearningObservationInertReason.MISSING_LEARNING_EPISODE,
            (missingId as LocalMasteryAdmissionDecision.KeepInert).reason,
        )
        assertEquals(
            LearningObservationInertReason.MISSING_LEARNING_EPISODE,
            (missingFingerprint as LocalMasteryAdmissionDecision.KeepInert).reason,
        )
    }

    @Test
    fun pendingSavedAttributionCanUseOnlyAStoreVerifiedBinding() {
        val pendingFact =
            fact().copy(
                authorizedProblemBindingsFingerprint = null,
            )
        val pendingCandidate = candidate("pending-saved-attribution")

        assertTrue(
            evaluate(pendingFact, pendingCandidate) is
                LocalMasteryAdmissionDecision.Admit,
        )
        val proof =
            MasterySourceProofEntity(
                sourceFactId = pendingFact.sourceFactId,
                sourceFactCanonicalFingerprint = pendingFact.canonicalFingerprint,
                sourcePolicyVersion = pendingFact.sourcePolicyVersion,
                policySupported = true,
                proofFingerprint = LearnerMasteryFingerprint.sourceProof(pendingFact),
                createdAtEpochMillis = pendingFact.attestedAtEpochMillis,
            )
        val withoutVerifiedBinding =
            LocalMasteryPolicy.evaluate(
                candidate =
                    pendingCandidate.copy(sourceFactId = pendingFact.sourceFactId),
                attributions =
                    listOf(candidateAttribution(pendingCandidate.candidateId)),
                sourceFact = pendingFact,
                sourceProof = proof,
                authorizedBindingFingerprints = emptySet(),
            ) as LocalMasteryAdmissionDecision.KeepInert

        assertEquals(
            LearningObservationInertReason.PROBLEM_BINDING_NOT_AUTHORIZED,
            withoutVerifiedBinding.reason,
        )
    }

    @Test
    fun modelScopedAndOpenResponsesUseVersionedConservativeHeuristics() {
        val trustedChoice = admittedMass(fact(), candidate("trusted-choice"))
        val modelChoice =
            admittedMass(
                fact(),
                candidate("model-choice").copy(
                    candidateOrigin = MasteryCandidateOrigin.MODEL_SCOPED.name,
                ),
            )
        val deterministicOpen =
            admittedMass(
                fact(authority = MasteryEvidenceAuthority.DETERMINISTIC_RUBRIC).copy(
                    responseForm = TrustedLearningResponseForm.FREE_RESPONSE.name,
                    verificationKind = TrustedLearningVerification.DETERMINISTIC_RUBRIC.name,
                ),
                candidate("deterministic-open"),
            )
        val modelOnlyOpen =
            evaluate(
                fact(authority = MasteryEvidenceAuthority.MODEL_REVIEWED).copy(
                    responseForm = TrustedLearningResponseForm.FREE_RESPONSE.name,
                    verificationKind = TrustedLearningVerification.MODEL_REVIEWED.name,
                ),
                candidate("model-only-open").copy(
                    candidateOrigin = MasteryCandidateOrigin.MODEL_SCOPED.name,
                ),
            )

        assertEquals(LocalMasteryPolicy.FULL_MASS_MICROS, trustedChoice)
        assertEquals(500_000L, modelChoice)
        assertEquals(500_000L, deterministicOpen)
        assertEquals(
            LearningObservationInertReason.MODEL_ONLY_OPEN_RESPONSE,
            (modelOnlyOpen as LocalMasteryAdmissionDecision.KeepInert).reason,
        )
    }

    @Test
    fun dedicatedOpenResponseWeightingIsLocalCappedAndAssistanceSensitive() {
        val calibration = LocalMasteryCalibrationRegistry.current(SUBJECT)
        val candidate =
            candidate("dedicated-open-response").copy(
                candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL.name,
            )
        val attribution = candidateAttribution(candidate.candidateId)
        val independentFact =
            fact(authority = MasteryEvidenceAuthority.SELF_REPORTED).copy(
                responseForm = TrustedLearningResponseForm.FREE_RESPONSE.name,
                verificationKind = TrustedLearningVerification.SELF_REPORTED.name,
                hintCount = 0,
                retryState = ObservedRetryState.FIRST_ATTEMPT.name,
                assistance = ObservedAssistance.INDEPENDENT.name,
                independentlyAnswered = true,
            )
        val assistedFact =
            independentFact.copy(
                hintCount = 1,
                retryState = ObservedRetryState.ONE_RETRY.name,
                assistance = ObservedAssistance.ONE_HINT.name,
                independentlyAnswered = false,
            )

        val independent =
            checkNotNull(
                LocalMasteryPolicy.evaluateOpenResponseWeakEvidence(
                    candidate = candidate,
                    sourceFact = independentFact,
                    direction = MasteryEventDirection.POSITIVE,
                    attributions = listOf(attribution),
                    calibrationSnapshot = calibration,
                ),
            ).attributedMasses.single().evidenceMassMicros
        val assisted =
            checkNotNull(
                LocalMasteryPolicy.evaluateOpenResponseWeakEvidence(
                    candidate = candidate,
                    sourceFact = assistedFact,
                    direction = MasteryEventDirection.POSITIVE,
                    attributions = listOf(attribution),
                    calibrationSnapshot = calibration,
                ),
            ).attributedMasses.single().evidenceMassMicros

        assertTrue(independent > assisted)
        assertTrue(independent <= calibration.modelAttributionCapMicros)
        assertTrue(independent <= calibration.openResponseCapMicros)
        assertNull(
            LocalMasteryPolicy.evaluateOpenResponseWeakEvidence(
                candidate = candidate,
                sourceFact = independentFact,
                direction = MasteryEventDirection.NEGATIVE,
                attributions = listOf(attribution, attribution.copy(ordinal = 1)),
                calibrationSnapshot = calibration,
            ),
        )
        assertNull(
            LocalMasteryPolicy.evaluateOpenResponseWeakEvidence(
                candidate = candidate,
                sourceFact = independentFact.copy(answerRevealed = true),
                direction = MasteryEventDirection.POSITIVE,
                attributions = listOf(attribution),
                calibrationSnapshot = calibration,
            ),
        )
    }

    @Test
    fun positiveOpenResponseMassIsNotMultipliedByKnowledgeScopeCardinality() {
        val calibration = LocalMasteryCalibrationRegistry.current(SUBJECT)
        val candidate = candidate("open-response-cardinality").copy(
            candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL.name,
        )
        val sourceFact = fact(authority = MasteryEvidenceAuthority.SELF_REPORTED).copy(
            responseForm = TrustedLearningResponseForm.FREE_RESPONSE.name,
            verificationKind = TrustedLearningVerification.SELF_REPORTED.name,
            hintCount = 0,
            retryState = ObservedRetryState.FIRST_ATTEMPT.name,
            assistance = ObservedAssistance.INDEPENDENT.name,
            independentlyAnswered = true,
        )
        val first = candidateAttribution(candidate.candidateId)
        val second = first.copy(
            ordinal = 1,
            knowledgeNodeId = "math.function.general",
            knowledgeNodeRefFingerprint = "2".repeat(64),
            problemBindingRefFingerprint = CONTEXT_BINDING_FINGERPRINT,
            bindingProblemRevisionRefFingerprint = "7".repeat(64),
            proposalFingerprint = "3".repeat(64),
        )

        val single = checkNotNull(
            LocalMasteryPolicy.evaluateOpenResponseWeakEvidence(
                candidate = candidate,
                sourceFact = sourceFact,
                direction = MasteryEventDirection.POSITIVE,
                attributions = listOf(first),
                calibrationSnapshot = calibration,
            ),
        )
        val multiple = checkNotNull(
            LocalMasteryPolicy.evaluateOpenResponseWeakEvidence(
                candidate = candidate,
                sourceFact = sourceFact,
                direction = MasteryEventDirection.POSITIVE,
                attributions = listOf(first, second),
                calibrationSnapshot = calibration,
            ),
        )

        val singleMass = single.attributedMasses.sumOf { it.evidenceMassMicros }
        val multipleMass = multiple.attributedMasses.sumOf { it.evidenceMassMicros }
        assertEquals(singleMass, multipleMass)
        assertTrue(multipleMass <= calibration.modelAttributionCapMicros)
        assertTrue(multipleMass <= calibration.openResponseCapMicros)
    }

    @Test
    fun modelConfidenceCannotChooseAnEvidenceWeight() {
        val high =
            admittedMass(
                fact(),
                candidate("model-high").copy(
                    confidence = MasteryCandidateConfidence.HIGH.name,
                    candidateOrigin = MasteryCandidateOrigin.MODEL_SCOPED.name,
                ),
            )
        val medium =
            admittedMass(
                fact(),
                candidate("model-medium").copy(
                    confidence = MasteryCandidateConfidence.MEDIUM.name,
                    candidateOrigin = MasteryCandidateOrigin.MODEL_SCOPED.name,
                ),
            )

        assertEquals(500_000L, high)
        assertEquals(high, medium)
    }

    @Test
    fun presentationNodeEvidenceMassNeverExceedsOneFullObservation() {
        repeat(256) { seed ->
            val random = Random(seed)
            var consumed = 0L
            repeat(64) {
                val requested =
                    random.nextLong(0L, LocalMasteryPolicy.FULL_MASS_MICROS + 1L)
                val admitted =
                    LocalPresentationEvidenceBudget.admittedMass(consumed, requested)
                assertTrue(admitted in 0L..requested)
                consumed += admitted
                assertTrue(consumed <= LocalMasteryPolicy.FULL_MASS_MICROS)
            }
            if (consumed == LocalMasteryPolicy.FULL_MASS_MICROS) {
                assertEquals(
                    0L,
                    LocalPresentationEvidenceBudget.admittedMass(consumed, 1L),
                )
            }
        }
    }

    @Test
    fun repeatedProblemFamilyEvidenceHasDiminishingReturnsAndACap() {
        var consumed = 0L
        val admitted =
            (0L..12L).map { priorCount ->
                LocalProblemFamilyEvidenceBudget.admittedMass(
                    priorObservationCount = priorCount,
                    alreadyConsumedMicros = consumed,
                    requestedMassMicros = LocalMasteryPolicy.FULL_MASS_MICROS,
                ).also { consumed += it }
            }

        assertEquals(1_000_000L, admitted[0])
        assertEquals(500_000L, admitted[1])
        assertEquals(250_000L, admitted[2])
        assertTrue(admitted.drop(2).zipWithNext().all { (left, right) -> left >= right })
        assertEquals(2_000_000L, consumed)
        assertEquals(0L, admitted.last())
    }

    @Test
    fun weakNegativeEvidenceCannotOverwriteStableMastery() {
        val stable =
            projection(8_000_000L, 500_000L).copy(
                masteryState = KnowledgeMasteryState.STEADY.name,
                memoryStabilityMillis = 8L * 24L * 60L * 60L * 1_000L,
            )
        val weak =
            fact(
                authority = MasteryEvidenceAuthority.MODEL_REVIEWED,
                outcome = ObservedLearningOutcome.INCORRECT,
            ).copy(
                independentlyAnswered = false,
                verificationKind = TrustedLearningVerification.MODEL_REVIEWED.name,
            )
        val directError =
            fact(
                authority = MasteryEvidenceAuthority.LOCAL_VERIFIED,
                outcome = ObservedLearningOutcome.INCORRECT,
            ).copy(
                independentlyAnswered = true,
                hintCount = 0,
                answerRevealed = false,
                verificationKind = TrustedLearningVerification.DEVICE_OBSERVED.name,
            )
        val independentlyReportedStuck =
            directError.copy(outcome = ObservedLearningOutcome.STUCK.name)
        val importedNegative =
            directError.copy(responseForm = TrustedLearningResponseForm.IMPORTED_RECORD.name)
        val candidate = candidate("weak-negative-conflict")

        assertTrue(
            LocalMasteryPolicy.weakNegativeConflictsWithStableMastery(
                candidate = candidate,
                sourceFact = weak,
                direction = MasteryEventDirection.NEGATIVE,
                currentProjections = listOf(stable),
            ),
        )
        assertTrue(
            !LocalMasteryPolicy.weakNegativeConflictsWithStableMastery(
                candidate = candidate,
                sourceFact = directError,
                direction = MasteryEventDirection.NEGATIVE,
                currentProjections = listOf(stable),
            ),
        )
        assertTrue(
            LocalMasteryPolicy.weakNegativeConflictsWithStableMastery(
                candidate = candidate,
                sourceFact = independentlyReportedStuck,
                direction = MasteryEventDirection.NEGATIVE,
                currentProjections = listOf(stable),
            ),
        )
        assertTrue(
            LocalMasteryPolicy.weakNegativeConflictsWithStableMastery(
                candidate = candidate,
                sourceFact = importedNegative,
                direction = MasteryEventDirection.NEGATIVE,
                currentProjections = listOf(stable),
            ),
        )
    }

    @Test
    fun attributionFromAnotherSubjectIsAlwaysInert() {
        val fact = fact()
        val candidate = candidate("cross-subject").copy(sourceFactId = fact.sourceFactId)
        val proof =
            MasterySourceProofEntity(
                sourceFactId = fact.sourceFactId,
                sourceFactCanonicalFingerprint = fact.canonicalFingerprint,
                sourcePolicyVersion = fact.sourcePolicyVersion,
                policySupported = true,
                proofFingerprint = LearnerMasteryFingerprint.sourceProof(fact),
                createdAtEpochMillis = fact.attestedAtEpochMillis,
            )
        val decision =
            LocalMasteryPolicy.evaluate(
                candidate = candidate,
                attributions =
                    listOf(
                        candidateAttribution(candidate.candidateId).copy(subject = "PHYSICS"),
                    ),
                sourceFact = fact,
                sourceProof = proof,
                authorizedBindingFingerprints = setOf(BINDING_FINGERPRINT),
            )

        assertEquals(
            LearningObservationInertReason.SUBJECT_MISMATCH,
            (decision as LocalMasteryAdmissionDecision.KeepInert).reason,
        )
    }

    @Test
    fun negativeEvidenceOnlyTargetsPrimaryDirectAttributions() {
        val fact =
            fact(outcome = ObservedLearningOutcome.INCORRECT).copy(
                authorizedProblemBindingsFingerprint =
                    fingerprintAuthorizedProblemBindings(
                        listOf(BINDING_FINGERPRINT, CONTEXT_BINDING_FINGERPRINT),
                    ),
            )
        val candidate = candidate("negative-localized").copy(sourceFactId = fact.sourceFactId)
        val proof =
            MasterySourceProofEntity(
                sourceFactId = fact.sourceFactId,
                sourceFactCanonicalFingerprint = fact.canonicalFingerprint,
                sourcePolicyVersion = fact.sourcePolicyVersion,
                policySupported = true,
                proofFingerprint = LearnerMasteryFingerprint.sourceProof(fact),
                createdAtEpochMillis = fact.attestedAtEpochMillis,
            )
        val primary = candidateAttribution(candidate.candidateId)
        val context =
            primary.copy(
                ordinal = 1,
                knowledgeNodeId = "math.function.general",
                knowledgeNodeRefFingerprint = "2".repeat(64),
                problemBindingRefFingerprint = CONTEXT_BINDING_FINGERPRINT,
                role = MasteryAttributionRole.CONTEXT.name,
                certainty = MasteryAttributionCertainty.INFERRED.name,
                proposalFingerprint = "3".repeat(64),
            )

        val decision =
            LocalMasteryPolicy.evaluate(
                candidate = candidate,
                attributions = listOf(primary, context),
                sourceFact = fact,
                sourceProof = proof,
                authorizedBindingFingerprints =
                    setOf(BINDING_FINGERPRINT, CONTEXT_BINDING_FINGERPRINT),
            ) as LocalMasteryAdmissionDecision.Admit

        assertEquals(
            listOf(primary.knowledgeNodeId),
            decision.attributedMasses.map { it.attribution.knowledgeNodeId },
        )
        assertEquals(
            LocalMasteryPolicy.FULL_MASS_MICROS,
            decision.attributedMasses.single().evidenceMassMicros,
        )
    }

    @Test
    fun supportingAndContextKnowledgeRemainAuditOnlyForPositiveEvidence() {
        val fact =
            fact().copy(
                authorizedProblemBindingsFingerprint =
                    fingerprintAuthorizedProblemBindings(
                        listOf(BINDING_FINGERPRINT, CONTEXT_BINDING_FINGERPRINT),
                    ),
            )
        val candidate = candidate("positive-audit-only").copy(sourceFactId = fact.sourceFactId)
        val proof =
            MasterySourceProofEntity(
                sourceFactId = fact.sourceFactId,
                sourceFactCanonicalFingerprint = fact.canonicalFingerprint,
                sourcePolicyVersion = fact.sourcePolicyVersion,
                policySupported = true,
                proofFingerprint = LearnerMasteryFingerprint.sourceProof(fact),
                createdAtEpochMillis = fact.attestedAtEpochMillis,
            )
        val primary = candidateAttribution(candidate.candidateId)
        val supporting =
            primary.copy(
                ordinal = 1,
                knowledgeNodeId = "math.function.general",
                knowledgeNodeRefFingerprint = "2".repeat(64),
                problemBindingRefFingerprint = CONTEXT_BINDING_FINGERPRINT,
                role = MasteryAttributionRole.SUPPORTING.name,
                certainty = MasteryAttributionCertainty.DIRECT.name,
                proposalFingerprint = "3".repeat(64),
            )

        val decision =
            LocalMasteryPolicy.evaluate(
                candidate = candidate,
                attributions = listOf(primary, supporting),
                sourceFact = fact,
                sourceProof = proof,
                authorizedBindingFingerprints =
                    setOf(BINDING_FINGERPRINT, CONTEXT_BINDING_FINGERPRINT),
            ) as LocalMasteryAdmissionDecision.Admit

        assertEquals(
            listOf(primary.knowledgeNodeId),
            decision.attributedMasses.map { it.attribution.knowledgeNodeId },
        )
    }

    @Test
    fun negativeEvidenceAcrossMultiplePrimaryKnowledgePointsIsInert() {
        val fact =
            fact(outcome = ObservedLearningOutcome.INCORRECT).copy(
                authorizedProblemBindingsFingerprint =
                    fingerprintAuthorizedProblemBindings(
                        listOf(BINDING_FINGERPRINT, CONTEXT_BINDING_FINGERPRINT),
                    ),
            )
        val candidate = candidate("unlocalized-negative").copy(sourceFactId = fact.sourceFactId)
        val proof =
            MasterySourceProofEntity(
                sourceFactId = fact.sourceFactId,
                sourceFactCanonicalFingerprint = fact.canonicalFingerprint,
                sourcePolicyVersion = fact.sourcePolicyVersion,
                policySupported = true,
                proofFingerprint = LearnerMasteryFingerprint.sourceProof(fact),
                createdAtEpochMillis = fact.attestedAtEpochMillis,
            )
        val first = candidateAttribution(candidate.candidateId)
        val second =
            first.copy(
                ordinal = 1,
                knowledgeNodeId = "math.function.general",
                knowledgeNodeRefFingerprint = "2".repeat(64),
                problemBindingRefFingerprint = CONTEXT_BINDING_FINGERPRINT,
                proposalFingerprint = "3".repeat(64),
            )

        val decision =
            LocalMasteryPolicy.evaluate(
                candidate = candidate,
                attributions = listOf(first, second),
                sourceFact = fact,
                sourceProof = proof,
                authorizedBindingFingerprints =
                    setOf(BINDING_FINGERPRINT, CONTEXT_BINDING_FINGERPRINT),
            ) as LocalMasteryAdmissionDecision.KeepInert

        assertEquals(
            LearningObservationInertReason.UNLOCALIZED_MULTI_KNOWLEDGE_NEGATIVE,
            decision.reason,
        )
    }

    @Test
    fun helpAndRetriesNeverQualifyAsIndependentMastery() {
        assertTrue(LocalMasteryPolicy.isIndependentMasteryEvidence(fact()))
        assertTrue(
            !LocalMasteryPolicy.isIndependentMasteryEvidence(
                fact(assistance = ObservedAssistance.ONE_HINT).copy(hintCount = 1),
            ),
        )
        assertTrue(
            !LocalMasteryPolicy.isIndependentMasteryEvidence(
                fact(retryState = ObservedRetryState.ONE_RETRY),
            ),
        )
        assertTrue(
            !LocalMasteryPolicy.isIndependentMasteryEvidence(
                fact().copy(answerRevealed = true),
            ),
        )
    }

    @Test
    fun verifiedEphemeralTutorEvidenceIsAdmittedWithoutAStudentProblemBinding() {
        val candidate = candidate("ephemeral-candidate")
        val attribution =
            candidateAttribution(candidate.candidateId).copy(
                problemBindingRefFingerprint = null,
                bindingProblemRevisionRefFingerprint = null,
            )
        val fact =
            fact().copy(
                sourceKind = MasteryEvidenceSourceKind.TUTOR_FREE_RESPONSE.name,
                problemRevisionRefFingerprint = null,
                problemRevisionId = null,
                problemId = null,
                practiceUnitId = null,
                problemRevisionNumber = null,
                problemDocumentFingerprint = null,
                evidenceContextKind = MasteryEvidenceContextKind.EPHEMERAL_TUTOR_PROBLEM.name,
                ephemeralProblemFingerprint = "7".repeat(64),
                tutorTurnReferenceId = "turn-ephemeral",
                submissionEvidenceFingerprint = "8".repeat(64),
                attributionModelVersion = "model-v1",
                authorizedProblemBindingsFingerprint = null,
                authorizedKnowledgeRefsFingerprint =
                    fingerprintAuthorizedKnowledgeRefs(
                        listOf(attribution.knowledgeNodeRefFingerprint),
                    ),
                knowledgeManifestFingerprint = "9".repeat(64),
                knowledgeActivationGeneration = 1L,
            )
        val proof =
            MasterySourceProofEntity(
                sourceFactId = fact.sourceFactId,
                sourceFactCanonicalFingerprint = fact.canonicalFingerprint,
                sourcePolicyVersion = fact.sourcePolicyVersion,
                policySupported = true,
                proofFingerprint = LearnerMasteryFingerprint.sourceProof(fact),
                createdAtEpochMillis = fact.attestedAtEpochMillis,
            )

        assertTrue(
            LocalMasteryPolicy.evaluate(
                candidate = candidate.copy(sourceFactId = fact.sourceFactId),
                attributions = listOf(attribution),
                sourceFact = fact,
                sourceProof = proof,
                authorizedBindingFingerprints = emptySet(),
            ) is LocalMasteryAdmissionDecision.Admit,
        )
        val chatOnly =
            LocalMasteryPolicy.evaluate(
                candidate = candidate.copy(
                    candidateId = "ephemeral-chat",
                    sourceFactId = fact.sourceFactId,
                ),
                attributions = emptyList(),
                sourceFact = fact.copy(
                    authorizedKnowledgeRefsFingerprint =
                        fingerprintAuthorizedKnowledgeRefs(emptyList()),
                ),
                sourceProof = proof,
                authorizedBindingFingerprints = emptySet(),
            ) as LocalMasteryAdmissionDecision.KeepInert
        assertEquals(LearningObservationInertReason.NO_ATTRIBUTION, chatOnly.reason)
    }

    private fun admittedMass(
        assistance: ObservedAssistance,
        retryState: ObservedRetryState,
    ): Long {
        val decision =
            evaluate(
                fact(
                    assistance = assistance,
                    retryState = retryState,
                ),
                candidate("candidate-$assistance-$retryState"),
            )
        return (decision as LocalMasteryAdmissionDecision.Admit)
            .attributedMasses
            .single()
            .evidenceMassMicros
    }

    private fun admittedMass(
        fact: MasterySourceFactEntity,
        candidate: MasteryObservationCandidateEntity,
    ): Long =
        (evaluate(fact, candidate) as LocalMasteryAdmissionDecision.Admit)
            .attributedMasses
            .single()
            .evidenceMassMicros

    private fun evaluate(
        fact: MasterySourceFactEntity,
        candidate: MasteryObservationCandidateEntity,
    ): LocalMasteryAdmissionDecision {
        val proof =
            MasterySourceProofEntity(
                sourceFactId = fact.sourceFactId,
                sourceFactCanonicalFingerprint = fact.canonicalFingerprint,
                sourcePolicyVersion = fact.sourcePolicyVersion,
                policySupported = true,
                proofFingerprint = LearnerMasteryFingerprint.sourceProof(fact),
                createdAtEpochMillis = fact.attestedAtEpochMillis,
            )
        return LocalMasteryPolicy.evaluate(
            candidate = candidate.copy(sourceFactId = fact.sourceFactId),
            attributions = listOf(candidateAttribution(candidate.candidateId)),
            sourceFact = fact,
            sourceProof = proof,
            authorizedBindingFingerprints = setOf(BINDING_FINGERPRINT),
        )
    }

    private fun fact(
        assistance: ObservedAssistance = ObservedAssistance.INDEPENDENT,
        authority: MasteryEvidenceAuthority = MasteryEvidenceAuthority.LOCAL_VERIFIED,
        retryState: ObservedRetryState = ObservedRetryState.FIRST_ATTEMPT,
        outcome: ObservedLearningOutcome = ObservedLearningOutcome.CORRECT,
    ): MasterySourceFactEntity =
        MasterySourceFactEntity(
            sourceFactId = "source-$assistance-$authority-$retryState",
            learnerId = LEARNER_ID,
            subject = SUBJECT,
            sourceKind = MasteryEvidenceSourceKind.TUTOR_CHOICE.name,
            sourceReferenceId = "reference-$assistance-$authority-$retryState",
            presentationId = "presentation-$assistance-$authority-$retryState",
            presentationFingerprint = "9".repeat(64),
            problemRevisionRefFingerprint = PROBLEM_REVISION_FINGERPRINT,
            problemRevisionId = "revision-property",
            problemId = "problem-property",
            practiceUnitId = "practice-property",
            problemRevisionNumber = 1,
            problemDocumentFingerprint = "e".repeat(64),
            reviewSessionId = null,
            reviewQueueItemId = null,
            reviewSubmissionId = null,
            outcome = outcome.name,
            assistance = assistance.name,
            retryState = retryState.name,
            authority = authority.name,
            sourcePayloadFingerprint = "a".repeat(64),
            occurredAtEpochMillis = 1L,
            attestedAtEpochMillis = 2L,
            receivedAtEpochMillis = 3L,
            sourcePolicyVersion = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
            idempotencyKey = "idempotency-$assistance-$authority-$retryState",
            canonicalFingerprint = "b".repeat(64),
            responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE.name,
            independentlyAnswered = true,
            verificationKind =
                when (authority) {
                    MasteryEvidenceAuthority.LOCAL_VERIFIED ->
                        TrustedLearningVerification.DEVICE_OBSERVED.name
                    MasteryEvidenceAuthority.DETERMINISTIC_RUBRIC ->
                        TrustedLearningVerification.DETERMINISTIC_RUBRIC.name
                    MasteryEvidenceAuthority.MODEL_REVIEWED ->
                        TrustedLearningVerification.MODEL_REVIEWED.name
                    MasteryEvidenceAuthority.SELF_REPORTED ->
                        TrustedLearningVerification.SELF_REPORTED.name
                },
            authorizedProblemBindingsFingerprint =
                fingerprintAuthorizedProblemBindings(listOf(BINDING_FINGERPRINT)),
        )

    private fun candidate(id: String): MasteryObservationCandidateEntity =
        MasteryObservationCandidateEntity(
            candidateId = id,
            learnerId = LEARNER_ID,
            subject = SUBJECT,
            sourceFactId = "source-id",
            confidence = MasteryCandidateConfidence.HIGH.name,
            modelVersion = "model-v1",
            requestedPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            proposedAtEpochMillis = 2L,
            receivedAtEpochMillis = 3L,
            idempotencyKey = "idempotency-$id",
            canonicalFingerprint = "c".repeat(64),
        )

    private fun candidateAttribution(candidateId: String):
        MasteryCandidateAttributionEntity =
        MasteryCandidateAttributionEntity(
            candidateId = candidateId,
            ordinal = 0,
            subject = SUBJECT,
            knowledgeNodeId = NODE_ID,
            taxonomyVersion = TAXONOMY_VERSION,
            knowledgePackVersion = "pack-v1",
            knowledgeNodeRefFingerprint = "d".repeat(64),
            problemBindingRefFingerprint = BINDING_FINGERPRINT,
            bindingProblemRevisionRefFingerprint = PROBLEM_REVISION_FINGERPRINT,
            role = MasteryAttributionRole.PRIMARY.name,
            certainty = MasteryAttributionCertainty.DIRECT.name,
            proposalFingerprint = "e".repeat(64),
        )

    private fun projection(
        positive: Long,
        negative: Long,
    ): MasteryKnowledgeProjectionEntity {
        val score =
            (
                (positive + 1_000_000L).toDouble() /
                    (positive + negative + 2_000_000L).toDouble() *
                    1_000_000.0
                ).roundToLong()
        return MasteryKnowledgeProjectionEntity(
            learnerId = LEARNER_ID,
            subject = SUBJECT,
            knowledgeNodeId = NODE_ID,
            taxonomyVersion = TAXONOMY_VERSION,
            latestEvidenceKnowledgePackVersion = "pack-v1",
            stableNodeIdentityFingerprint =
                MasteryProjectionIdentity.fingerprint(SUBJECT, NODE_ID, TAXONOMY_VERSION),
            positiveEvidenceMicros = positive,
            negativeEvidenceMicros = negative,
            masteryScoreMicros = score,
            masteryState = KnowledgeMasteryState.FAMILIARIZING.name,
            trend = KnowledgeMasteryTrend.STABLE.name,
            observationCount = 1L,
            memoryStabilityMillis = 86_400_000L,
            recallDueAtEpochMillis = 86_400_001L,
            lastPositiveAtEpochMillis = 1L,
            lastNegativeAtEpochMillis = 1L,
            lastEvidenceAtEpochMillis = 1L,
            lastEventSequence = 1L,
            lastOrderedEventId = "baseline",
            projectionPolicyVersion = LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION,
        )
    }

    private fun event(
        id: String,
        direction: MasteryEventDirection,
    ): MasteryLearningEventEntity =
        LocalMasteryCalibrationRegistry.current(SUBJECT).let { calibration ->
            MasteryLearningEventEntity(
            eventId = id,
            candidateId = "candidate-$id",
            sourceFactId = "source-$id",
            sourceProofFingerprint = "f".repeat(64),
            learnerId = LEARNER_ID,
            subject = SUBJECT,
            direction = direction.name,
            eventSequence = 2L,
            occurredAtEpochMillis = 2L,
            admittedAtEpochMillis = 3L,
            projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            calibrationVersion = calibration.calibrationVersion,
            calibrationSnapshotFingerprint = calibration.snapshotFingerprint,
            calibrationProfileId = calibration.profileId,
            canonicalFingerprint = "0".repeat(64),
        )
        }

    private fun eventAttribution(
        eventId: String,
        mass: Long,
    ): MasteryLearningEventAttributionEntity =
        MasteryLearningEventAttributionEntity(
            eventId = eventId,
            ordinal = 0,
            subject = SUBJECT,
            knowledgeNodeId = NODE_ID,
            taxonomyVersion = TAXONOMY_VERSION,
            knowledgePackVersion = "pack-v1",
            knowledgeNodeRefFingerprint = "1".repeat(64),
            evidenceMassMicros = mass,
        )

    private companion object {
        const val LEARNER_ID = "learner-property"
        const val SUBJECT = "MATH"
        const val NODE_ID = "math.function.quadratic"
        const val TAXONOMY_VERSION = "taxonomy-v1"
        val PROBLEM_REVISION_FINGERPRINT = "4".repeat(64)
        val BINDING_FINGERPRINT = "5".repeat(64)
        val CONTEXT_BINDING_FINGERPRINT = "6".repeat(64)
    }
}
