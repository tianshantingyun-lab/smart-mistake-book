package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlan
import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlanningRequest
import com.tingyun.smartmistakebook.core.domain.ReviewExamTarget
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerBoundDailyReviewPlanPortTest {
    @Test
    fun defaultBudgetIsFifteenMinutesAndLearnerIsRuntimeBound() = runBlocking {
        var captured: ThreeAuthorityDailyReviewRequest? = null
        val port: LearnerBoundDailyReviewPlanPort =
            BoundLearnerDailyReviewPlanPort(LEARNER_ID) { request ->
                captured = request
                request.toPlanningResult()
            }

        val result =
            port.planDailyReview(
                localDayEpochDay = LOCAL_DAY,
                timeZoneId = TIME_ZONE,
                planningAtEpochMillis = NOW,
            )

        assertEquals(LEARNER_ID, captured?.learnerId)
        assertEquals(15 * 60, captured?.timeBudgetSeconds)
        assertEquals(
            ThreeAuthorityReviewPlanningRequest.DEFAULT_MAX_ITEM_COUNT,
            captured?.maxItemCount,
        )
        assertEquals(15 * 60, result.plan.timeBudgetSeconds)
        assertEquals(
            ThreeAuthorityReviewPlanningRequest.DEFAULT_MAX_ITEM_COUNT,
            result.plan.maxItemCount,
        )
        assertEquals(0, result.candidateProfile.pendingKnowledgeAttributionCount)
    }

    @Test
    fun customItemCountIsBoundedAndPlanCarriesIt() = runBlocking {
        var captured: ThreeAuthorityDailyReviewRequest? = null
        val port: LearnerBoundDailyReviewPlanPort =
            BoundLearnerDailyReviewPlanPort(LEARNER_ID) { request ->
                captured = request
                request.toPlanningResult()
            }

        val result =
            port.planDailyReview(
                localDayEpochDay = LOCAL_DAY,
                timeZoneId = TIME_ZONE,
                planningAtEpochMillis = NOW,
                maxItemCount = 8,
            )

        assertEquals(8, captured?.maxItemCount)
        assertEquals(8, result.plan.maxItemCount)
        listOf(0, ThreeAuthorityReviewPlanningRequest.MAX_ITEM_COUNT + 1).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    port.planDailyReview(
                        localDayEpochDay = LOCAL_DAY,
                        timeZoneId = TIME_ZONE,
                        planningAtEpochMillis = NOW,
                        maxItemCount = invalid,
                    )
                }
            }
        }
    }

    @Test
    fun examTargetPassesThroughToCoordinatorRequest() = runBlocking {
        var captured: ThreeAuthorityDailyReviewRequest? = null
        val port: LearnerBoundDailyReviewPlanPort =
            BoundLearnerDailyReviewPlanPort(LEARNER_ID) { request ->
                captured = request
                request.toPlanningResult()
            }
        val target = ReviewExamTarget(SubjectKind.MATH, NOW + 3L * DAY_MILLIS)

        port.planDailyReview(
            localDayEpochDay = LOCAL_DAY,
            timeZoneId = TIME_ZONE,
            planningAtEpochMillis = NOW,
            examTarget = target,
        )

        assertEquals(target, captured?.examTarget)
    }

    @Test
    fun customBudgetIsBoundedAndPlanCannotExceedIt() = runBlocking {
        var calls = 0
        val port: LearnerBoundDailyReviewPlanPort =
            BoundLearnerDailyReviewPlanPort(LEARNER_ID) { request ->
                calls += 1
                request.toPlanningResult()
            }

        val result =
            port.planDailyReview(
                localDayEpochDay = LOCAL_DAY,
                timeZoneId = TIME_ZONE,
                planningAtEpochMillis = NOW,
                timeBudgetSeconds = 10 * 60,
            )

        assertEquals(10 * 60, result.plan.timeBudgetSeconds)
        assertTrue(result.plan.estimatedDurationSeconds <= 10 * 60)
        listOf(0, 60 * 60 + 1).forEach { invalidBudget ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    port.planDailyReview(
                        localDayEpochDay = LOCAL_DAY,
                        timeZoneId = TIME_ZONE,
                        planningAtEpochMillis = NOW,
                        timeBudgetSeconds = invalidBudget,
                    )
                }
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun coordinatorCannotReturnAnotherLearnersPlan() {
        val port: LearnerBoundDailyReviewPlanPort =
            BoundLearnerDailyReviewPlanPort(LEARNER_ID) { request ->
                request.toPlanningResult(learnerId = "learner-other")
            }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                port.planDailyReview(
                    localDayEpochDay = LOCAL_DAY,
                    timeZoneId = TIME_ZONE,
                    planningAtEpochMillis = NOW,
                )
            }
        }
    }

    @Test
    fun publicPortContainsNoLearnerOrDatabaseHandleParameter() {
        val method =
            LearnerBoundDailyReviewPlanPort::class.java.declaredMethods
                .single { it.name == "planDailyReview" }
        val signature =
            buildString {
                append(method.genericReturnType.typeName)
                method.genericParameterTypes.forEach { append(it.typeName) }
            }

        assertEquals(
            listOf(
                java.lang.Long.TYPE,
                String::class.java,
                java.lang.Long.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                ReviewExamTarget::class.java,
                kotlin.coroutines.Continuation::class.java,
            ),
            method.parameterTypes.toList(),
        )
        assertTrue("learnerId" !in signature)
        assertTrue(".database." !in signature)
        assertTrue("StudentMistakeStore" !in signature)
        assertTrue("LearnerMastery" !in signature)
    }

    private fun ThreeAuthorityDailyReviewRequest.toPlanningResult(
        learnerId: String = this.learnerId,
    ): ThreeAuthorityDailyReviewPlanningResult =
        ThreeAuthorityDailyReviewPlanningResult(
            plan =
                ThreeAuthorityReviewPlan(
                    planId = "plan-$localDayEpochDay",
                    canonicalFingerprint = "a".repeat(64),
                    learnerId = learnerId,
                    localDayEpochDay = localDayEpochDay,
                    timeZoneId = timeZoneId,
                    timeBudgetSeconds = timeBudgetSeconds,
                    maxItemCount = maxItemCount,
                    generatedAtEpochMillis = planningAtEpochMillis,
                    plannerVersion = "planner-v1",
                    masteryProjectionVersion = masteryProjectionVersion,
                    items = emptyList(),
                ),
            candidateProfile =
                ReviewCandidateProfileSummary(
                    readyCount = 0,
                    pendingKnowledgeAttributionCount = 0,
                    exactProblemIdentityOnlyCount = 0,
                ),
        )

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val LOCAL_DAY = 20_000L
        const val TIME_ZONE = "Asia/Shanghai"
        const val NOW = 1_800_000_000_000L
        const val DAY_MILLIS = 86_400_000L
    }
}
