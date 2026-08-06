package com.tingyun.smartmistakebook.feature.review

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterManifest
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionPortFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionResult
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceResult
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommand
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommandFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingResult
import com.tingyun.smartmistakebook.core.data.review.DailyReviewProductionCapability
import com.tingyun.smartmistakebook.core.data.review.DailyReviewRepository
import com.tingyun.smartmistakebook.core.data.review.DailyReviewSessionActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewRawAnswerSubmission
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeRequest
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeState
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeUnavailableReason
import com.tingyun.smartmistakebook.core.data.review.StartDailyReviewCommand
import com.tingyun.smartmistakebook.core.data.review.StartDailyReviewResult
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewProductionCapabilityProviderTest {
    @Test
    fun missingReviewAdapterBlocksBeforePublishedCapabilitiesAreRead() {
        var sourceReads = 0
        val provider =
            provider(
                available = ProductionAdapter.entries.toSet() - ProductionAdapter.REVIEW_PLANNING,
                source = {
                    sourceReads += 1
                    error("must not inspect unpublished capabilities")
                },
            )

        assertBlocked(
            provider,
            ReviewProductionCapabilityBlockReason.REVIEW_ADAPTER_UNAVAILABLE,
        )
        assertEquals(0, sourceReads)
    }

    @Test
    fun incompleteManifestBlocksBeforePublishedCapabilitiesAreRead() {
        var sourceReads = 0
        val provider =
            provider(
                available = setOf(ProductionAdapter.REVIEW_PLANNING),
                source = {
                    sourceReads += 1
                    error("must not inspect capabilities before global publication")
                },
            )

        assertBlocked(
            provider,
            ReviewProductionCapabilityBlockReason.PRODUCTION_MANIFEST_INCOMPLETE,
        )
        assertEquals(0, sourceReads)
    }

    @Test
    fun manifestAndPublicationFailuresStayBlocked() {
        val manifestFailure =
            ReviewProductionCapabilityProvider(
                adapterAvailability =
                    ProductionAdapterAvailabilityPort {
                        error("manifest unavailable")
                    },
                capabilitySource = ReviewProductionCapabilitySource { error("not reached") },
            )
        assertBlocked(
            manifestFailure,
            ReviewProductionCapabilityBlockReason.MANIFEST_UNAVAILABLE,
        )

        val sourceFailure =
            provider(
                available = ProductionAdapter.entries.toSet(),
                source = { error("publication failed") },
            )
        assertBlocked(
            sourceFailure,
            ReviewProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
        )
    }

    @Test
    fun completeManifestPublishesOnlyTheOpaqueNarrowReviewCapability() {
        val ports = FakeReviewPorts()
        val capability = ports.capability()
        val provider =
            provider(
                available = ProductionAdapter.entries.toSet(),
                source = { capability },
            )

        val available = provider.resolve() as ReviewProductionCapabilityDecision.Available

        assertSame(capability, available.capability)
        assertSame(ports.repository, available.capability.repository)
        assertSame(ports.sessions, available.capability.sessionActions)
        assertSame(ports.pacing, available.capability.pacingActions)
        assertSame(ports.answerSubmissionPorts, available.capability.answerSubmissionPorts)
        assertSame(ports.assistanceActions, available.capability.assistanceActions)
        assertSame(ports.commandFactory, available.capability.pacingCommandFactory)
        assertSame(ports.requestProvider, available.capability.requestProvider)
        assertEquals(0, ports.portCalls)
    }

    @Test
    fun publicCapabilityConstructorAcceptsOnlyProductionPlanningAndNarrowPorts() {
        val parameterTypes =
            ReviewProductionCapability::class.java.constructors
                .single()
                .parameterTypes
                .toSet()

        assertEquals(
            setOf(
                DailyReviewProductionCapability::class.java,
                DailyReviewSessionActionPort::class.java,
                DailyReviewPacingActionPort::class.java,
                DailyReviewAnswerSubmissionPortFactory::class.java,
                DailyReviewAssistanceActionPort::class.java,
                DailyReviewPacingCommandFactory::class.java,
                ReviewHomeRequestProvider::class.java,
            ),
            parameterTypes,
        )
        assertTrue(
            parameterTypes.none { type ->
                type.name.contains("StudyDatabase") ||
                    type.name.contains("StudyExperienceRepository")
            },
        )
    }

    @Test
    fun featureSubmissionPayloadContainsOnlyTheRawLearnerResponse() {
        assertEquals(
            setOf("response"),
            DailyReviewRawAnswerSubmission::class.java.declaredFields
                .filterNot { field ->
                    field.isSynthetic || Modifier.isStatic(field.modifiers)
                }
                .mapTo(linkedSetOf()) { field -> field.name },
        )
    }

    private fun provider(
        available: Set<ProductionAdapter>,
        source: () -> ReviewProductionCapability?,
    ) = ReviewProductionCapabilityProvider(
        adapterAvailability =
            ProductionAdapterAvailabilityPort {
                ProductionAdapterManifest.fromAvailable(available)
            },
        capabilitySource = ReviewProductionCapabilitySource(source),
    )

    private fun assertBlocked(
        provider: ReviewProductionCapabilityProvider,
        reason: ReviewProductionCapabilityBlockReason,
    ) {
        assertEquals(
            ReviewProductionCapabilityDecision.Blocked(reason),
            provider.resolve(),
        )
    }
}

private class FakeReviewPorts {
    var portCalls = 0

    val repository =
        object : DailyReviewRepository {
            override suspend fun readHome(
                request: ReviewHomeRequest,
            ): ReviewHomeState {
                portCalls += 1
                return ReviewHomeState.Unavailable(
                    ReviewHomeUnavailableReason.PLAN_UNAVAILABLE,
                )
            }
        }
    val sessions =
        object : DailyReviewSessionActionPort {
            override suspend fun startOrResume(
                command: StartDailyReviewCommand,
            ): StartDailyReviewResult {
                portCalls += 1
                return StartDailyReviewResult.ReloadRequired
            }
        }
    val pacing =
        object : DailyReviewPacingActionPort {
            override suspend fun record(
                command: DailyReviewPacingCommand,
            ): DailyReviewPacingResult {
                portCalls += 1
                return DailyReviewPacingResult.ReloadRequired
            }
        }
    val answerSubmissions =
        object : DailyReviewAnswerSubmissionPort {
            override suspend fun submit(
                submission: DailyReviewRawAnswerSubmission,
            ): DailyReviewAnswerSubmissionResult {
                portCalls += 1
                return DailyReviewAnswerSubmissionResult.Rejected
            }
        }
    val answerSubmissionPorts =
        DailyReviewAnswerSubmissionPortFactory {
            portCalls += 1
            answerSubmissions
        }
    val assistanceActions =
        DailyReviewAssistanceActionPort { _, _, _ ->
            DailyReviewAssistanceResult.Recorded(duplicate = false)
        }
    val commandFactory =
        DailyReviewPacingCommandFactory { _, _ ->
            portCalls += 1
            error("not called while resolving capability")
        }
    val requestProvider =
        ReviewHomeRequestProvider {
            portCalls += 1
            ReviewHomeRequest(
                localDayEpochDay = 1,
                timeZoneId = "Asia/Shanghai",
                requestedAtEpochMillis = 1,
            )
        }

    fun capability() =
        ReviewProductionCapability(
            planning = productionPlanningToken(repository),
            sessionActions = sessions,
            pacingActions = pacing,
            answerSubmissionPorts = answerSubmissionPorts,
            assistanceActions = assistanceActions,
            pacingCommandFactory = commandFactory,
            requestProvider = requestProvider,
        )
}

/**
 * The production token constructor is intentionally inaccessible outside core:data. This
 * reflection is confined to the feature contract test so the happy path can be exercised without
 * weakening the production API.
 */
private fun productionPlanningToken(
    repository: DailyReviewRepository,
): DailyReviewProductionCapability {
    val constructor =
        DailyReviewProductionCapability::class.java
            .getDeclaredConstructor(DailyReviewRepository::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(repository)
}
