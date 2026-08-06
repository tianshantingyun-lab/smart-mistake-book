package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.InvalidTutorStudentMessageException
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorStreamEvent
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TutorActiveStreamOwnerTest {
    @Test
    fun optimisticUserMessageAndActivityAreImmediateButPlaceholderWaitsThreeHundredMillis() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val owner = owner(dispatcher)

            owner.submit(studentMessage = "  我卡在这一步  ") {
                prepared("request-1") { identity ->
                    flow {
                        emit(TutorStreamEvent.Started(identity))
                        awaitCancellation()
                    }
                }
            }

            assertEquals("  我卡在这一步  ", owner.state.value.active?.studentMessage)
            assertTrue(owner.state.value.active?.activityVisible == true)
            assertFalse(owner.state.value.active?.showPlaceholder == true)
            runCurrent()
            advanceTimeBy(299)
            runCurrent()
            assertFalse(owner.state.value.active?.showPlaceholder == true)

            advanceTimeBy(1)
            runCurrent()

            assertTrue(owner.state.value.active?.showPlaceholder == true)
            owner.close()
        }

    @Test
    fun preparationRunsOnTheInjectedWorkerDispatcher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)
        var preparationInterceptor: ContinuationInterceptor? = null

        owner.submit(studentMessage = "为什么？") {
            preparationInterceptor = currentCoroutineContext()[ContinuationInterceptor]
            prepared("request-1") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    awaitCancellation()
                }
            }
        }
        runCurrent()

        assertSame(dispatcher, preparationInterceptor)
        owner.close()
    }

    @Test
    fun previewsAreCoalescedForSixtyFourMillisAndOnlyTheLatestSnapshotIsPublished() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = MutableSharedFlow<TutorStreamEvent>(extraBufferCapacity = 8)
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "继续") {
            prepared("request-1") { events }
        }
        runCurrent()
        val identity = requireNotNull(owner.state.value.active?.identity)
        events.tryEmit(TutorStreamEvent.Started(identity))
        events.tryEmit(
            TutorStreamEvent.Preview(
                identity,
                TutorMarkdownSnapshot("第一段", "临"),
            ),
        )
        events.tryEmit(
            TutorStreamEvent.Preview(
                identity,
                TutorMarkdownSnapshot("第一段", "临时"),
            ),
        )
        runCurrent()

        assertNull(owner.state.value.active?.snapshot)
        advanceTimeBy(63)
        runCurrent()
        assertNull(owner.state.value.active?.snapshot)

        advanceTimeBy(1)
        runCurrent()

        assertEquals("第一段临时", owner.state.value.active?.snapshot?.visibleMarkdown)
        assertFalse(owner.state.value.active?.showPlaceholder == true)
        owner.close()
    }

    @Test
    fun placeholderRemainsVisibleUntilTheFirstCoalescedPreviewIsPublished() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = MutableSharedFlow<TutorStreamEvent>(extraBufferCapacity = 8)
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "继续") {
            prepared("request-1") { events }
        }
        runCurrent()
        val identity = requireNotNull(owner.state.value.active?.identity)
        events.tryEmit(TutorStreamEvent.Started(identity))
        advanceTimeBy(300)
        runCurrent()
        assertTrue(owner.state.value.active?.showPlaceholder == true)

        events.tryEmit(
            TutorStreamEvent.Preview(
                identity,
                TutorMarkdownSnapshot("第一段", ""),
            ),
        )
        runCurrent()

        assertTrue(owner.state.value.active?.showPlaceholder == true)
        assertNull(owner.state.value.active?.snapshot)
        advanceTimeBy(64)
        runCurrent()

        assertFalse(owner.state.value.active?.showPlaceholder == true)
        assertEquals("第一段", owner.state.value.active?.snapshot?.visibleMarkdown)
        owner.close()
    }

    @Test
    fun everyIdentityFieldMustMatchBeforeAPreviewCanMutateTheActiveMessage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = MutableSharedFlow<TutorStreamEvent>(extraBufferCapacity = 8)
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "继续") {
            prepared("request-1") { events }
        }
        runCurrent()
        val identity = requireNotNull(owner.state.value.active?.identity)
        val staleIdentities = listOf(
            identity.copy(requestId = "request-stale"),
            identity.copy(ownerVersion = identity.ownerVersion + 1),
            identity.copy(turnVersion = identity.turnVersion + 1),
            identity.copy(modeVersion = identity.modeVersion + 1),
        )

        staleIdentities.forEach { stale ->
            events.tryEmit(
                TutorStreamEvent.Preview(
                    stale,
                    TutorMarkdownSnapshot("不应显示", ""),
                ),
            )
        }
        advanceTimeBy(64)
        runCurrent()

        assertNull(owner.state.value.active?.snapshot)
        owner.close()
    }

    @Test
    fun aNewTurnCancelsAndSupersedesThePreviousActiveRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var firstCancelled = false
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "第一问") {
            prepared("request-1") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled = true
                    }
                }
            }
        }
        runCurrent()
        val firstTurnVersion = requireNotNull(owner.state.value.active).turnVersion

        owner.submit(studentMessage = "第二问") {
            prepared("request-2") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    awaitCancellation()
                }
            }
        }
        runCurrent()

        assertTrue(firstCancelled)
        assertTrue("request-1" in owner.state.value.supersededRequestIds)
        assertEquals("第二问", owner.state.value.active?.studentMessage)
        assertEquals(firstTurnVersion + 1, owner.state.value.active?.turnVersion)
        owner.close()
    }

    @Test
    fun aNewTurnCancelsOnlyTheSupersededDurableRequestOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cancelledRequestIds = mutableListOf<String>()
        val owner = owner(dispatcher) { requestId ->
            cancelledRequestIds += requestId
        }
        owner.submit(studentMessage = "第一问") {
            prepared("request-1") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    awaitCancellation()
                }
            }
        }
        runCurrent()

        owner.submit(studentMessage = "第二问") {
            prepared("request-2") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    awaitCancellation()
                }
            }
        }
        runCurrent()

        assertEquals(listOf("request-1"), cancelledRequestIds)
        assertEquals("request-2", owner.state.value.active?.identity?.requestId)
        owner.close()
    }

    @Test
    fun supersededDurableCancellationSurvivesImmediateOwnerScopeCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ownerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val cancelledRequestIds = mutableListOf<String>()
        val owner = owner(
            dispatcher = dispatcher,
            ownerScope = ownerScope,
        ) { requestId ->
            cancelledRequestIds += requestId
        }
        owner.submit(studentMessage = "第一问") {
            prepared("request-1") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    awaitCancellation()
                }
            }
        }
        runCurrent()

        owner.submit(studentMessage = "第二问") {
            prepared("request-2") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    awaitCancellation()
                }
            }
        }
        ownerScope.cancel()
        runCurrent()

        assertEquals(listOf("request-1"), cancelledRequestIds)
    }

    @Test
    fun durableCancellationFailureDoesNotEscapeAndTheNewTurnRemainsUsable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(
            dispatcher = dispatcher,
            cancelDurableRequest = {
                throw IllegalStateException("database is unavailable")
            },
        )
        owner.submit(studentMessage = "第一问") {
            prepared("request-1") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    awaitCancellation()
                }
            }
        }
        runCurrent()

        owner.submit(studentMessage = "第二问") {
            prepared("request-2") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    awaitCancellation()
                }
            }
        }
        runCurrent()

        assertEquals("request-2", owner.state.value.active?.identity?.requestId)
        owner.close()
    }

    @Test
    fun modeChangeCancelsTheClearedDurableRequestOnlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cancelledRequestIds = mutableListOf<String>()
        val owner = owner(dispatcher) { requestId ->
            cancelledRequestIds += requestId
        }
        owner.submit(studentMessage = "按引导讲") {
            prepared("request-guided") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    awaitCancellation()
                }
            }
        }
        runCurrent()

        owner.updateMode(TutorExplanationMode.GUIDED)
        owner.updateMode(TutorExplanationMode.DIRECT)
        runCurrent()

        assertEquals(listOf("request-guided"), cancelledRequestIds)
        assertNull(owner.state.value.active)
        owner.close()
    }

    @Test
    fun closeLeavesTheDurableRequestRecoverable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cancelledRequestIds = mutableListOf<String>()
        val owner = owner(dispatcher) { requestId ->
            cancelledRequestIds += requestId
        }
        owner.submit(studentMessage = "同题导航后继续") {
            prepared("request-navigation") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    awaitCancellation()
                }
            }
        }
        runCurrent()

        owner.close()
        runCurrent()

        assertTrue(cancelledRequestIds.isEmpty())
    }

    @Test
    fun modeChangeAndNavigationCancelTheActiveOwner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var modeCancellationObserved = false
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "按引导讲") {
            prepared("request-guided") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    try {
                        awaitCancellation()
                    } finally {
                        modeCancellationObserved = true
                    }
                }
            }
        }
        runCurrent()

        owner.updateMode(TutorExplanationMode.GUIDED)
        runCurrent()

        assertTrue(modeCancellationObserved)
        assertNull(owner.state.value.active)
        assertTrue("request-guided" in owner.state.value.supersededRequestIds)

        var navigationCancellationObserved = false
        owner.submit(studentMessage = "新问题") {
            prepared("request-navigation") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    try {
                        awaitCancellation()
                    } finally {
                        navigationCancellationObserved = true
                    }
                }
            }
        }
        runCurrent()
        owner.close()
        runCurrent()

        assertTrue(navigationCancellationObserved)
        assertNull(owner.state.value.active)
    }

    @Test
    fun failureKeepsTheLastSafePreviewAndExposesOneRetryAction() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "继续") {
            prepared("request-1") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    emit(
                        TutorStreamEvent.Preview(
                            identity,
                            TutorMarkdownSnapshot("保留的讲解", ""),
                        ),
                    )
                    emit(
                        TutorStreamEvent.Failed(
                            identity,
                            TutorMarkdownSnapshot("保留的讲解", ""),
                            retryable = true,
                        ),
                    )
                }
            }
        }
        runCurrent()

        val failed = requireNotNull(owner.state.value.active)
        assertEquals(TutorActiveStreamPhase.FAILED, failed.phase)
        assertEquals("保留的讲解", failed.snapshot?.visibleMarkdown)
        assertEquals(listOf(TutorActiveStreamRecovery.RETRY), failed.recoveryActions)
        assertFalse(failed.activityVisible)
        assertFalse(failed.showPlaceholder)
        owner.close()
    }

    @Test
    fun retryKeepsTheKnownRequestIdentityUntilTheWorkerReattaches() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "继续") {
            prepared("request-1") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    emit(
                        TutorStreamEvent.Failed(
                            identity = identity,
                            snapshot = TutorMarkdownSnapshot("保留的讲解", ""),
                            retryable = true,
                        ),
                    )
                }
            }
        }
        runCurrent()
        val originalIdentity = requireNotNull(owner.state.value.active?.identity)

        assertTrue(owner.retry())

        val retrying = requireNotNull(owner.state.value.active)
        assertEquals(TutorActiveStreamPhase.PREPARING, retrying.phase)
        assertEquals(originalIdentity, retrying.identity)
        owner.close()
    }

    @Test
    fun aStreamThatEndsWithoutATerminalEventFailsAndKeepsItsLatestSafePreview() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "继续") {
            prepared("request-1") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    emit(
                        TutorStreamEvent.Preview(
                            identity,
                            TutorMarkdownSnapshot("断开前的讲解", ""),
                        ),
                    )
                }
            }
        }
        runCurrent()

        val failed = requireNotNull(owner.state.value.active)
        assertEquals(TutorActiveStreamPhase.FAILED, failed.phase)
        assertEquals("断开前的讲解", failed.snapshot?.visibleMarkdown)
        assertEquals(listOf(TutorActiveStreamRecovery.RETRY), failed.recoveryActions)
        owner.close()
    }

    @Test
    fun previewAfterCompletedIsIgnoredEvenIfTheBrokenFlowStaysOpen() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "继续") {
            prepared("request-1") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    emit(
                        TutorStreamEvent.Completed(
                            identity,
                            TutorMarkdownSnapshot("最终可信讲解", ""),
                        ),
                    )
                    emit(
                        TutorStreamEvent.Preview(
                            identity,
                            TutorMarkdownSnapshot("过期晚到内容", ""),
                        ),
                    )
                    delay(65)
                }
            }
        }
        advanceTimeBy(65)
        runCurrent()

        val completed = requireNotNull(owner.state.value.active)
        assertEquals(TutorActiveStreamPhase.COMPLETED, completed.phase)
        assertEquals("最终可信讲解", completed.snapshot?.visibleMarkdown)
        owner.close()
    }

    @Test
    fun completedTerminalStateWinsOverADetachedCoalescedPreview() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val previewDetached = CompletableDeferred<Unit>()
        val releasePreviewApply = CompletableDeferred<Unit>()
        val events = MutableSharedFlow<TutorStreamEvent>(extraBufferCapacity = 8)
        val owner = owner(
            dispatcher = dispatcher,
            beforeCoalescedPreviewApply = {
                previewDetached.complete(Unit)
                releasePreviewApply.await()
            },
        )
        owner.submit(studentMessage = "继续") {
            prepared("request-1") { events }
        }
        runCurrent()
        val identity = requireNotNull(owner.state.value.active?.identity)
        events.tryEmit(TutorStreamEvent.Started(identity))
        events.tryEmit(
            TutorStreamEvent.Preview(
                identity,
                TutorMarkdownSnapshot("较早的预览", ""),
            ),
        )
        runCurrent()
        advanceTimeBy(64)
        runCurrent()
        assertTrue(previewDetached.isCompleted)

        events.tryEmit(
            TutorStreamEvent.Completed(
                identity,
                TutorMarkdownSnapshot("最终可信讲解", ""),
            ),
        )
        runCurrent()
        releasePreviewApply.complete(Unit)
        runCurrent()

        val completed = requireNotNull(owner.state.value.active)
        assertEquals(TutorActiveStreamPhase.COMPLETED, completed.phase)
        assertEquals("最终可信讲解", completed.snapshot?.visibleMarkdown)
        owner.close()
    }

    @Test
    fun modeChangeCancelsRetryableFailedRequestAndRemovesItsRecovery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cancelled = mutableListOf<String>()
        val owner = owner(
            dispatcher = dispatcher,
            cancelDurableRequest = cancelled::add,
        )
        owner.submit(studentMessage = "直接讲") {
            prepared("request-direct-failed") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    emit(
                        TutorStreamEvent.Failed(
                            identity = identity,
                            snapshot = TutorMarkdownSnapshot("已显示的安全内容", ""),
                            retryable = true,
                        ),
                    )
                }
            }
        }
        runCurrent()

        owner.updateMode(TutorExplanationMode.GUIDED)
        runCurrent()

        assertEquals(listOf("request-direct-failed"), cancelled)
        assertTrue("request-direct-failed" in owner.state.value.supersededRequestIds)
        assertNull(owner.state.value.active)
        assertFalse(owner.retry())
        owner.close()
    }

    @Test
    fun completedTerminalSurvivesLateUpstreamCleanupFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "继续") {
            prepared("request-completed") { identity ->
                flow {
                    emit(
                        TutorStreamEvent.Completed(
                            identity,
                            TutorMarkdownSnapshot("最终可信讲解", ""),
                        ),
                    )
                    throw IllegalStateException("late upstream cleanup failure")
                }
            }
        }
        runCurrent()

        val completed = requireNotNull(owner.state.value.active)
        assertEquals(TutorActiveStreamPhase.COMPLETED, completed.phase)
        assertEquals("最终可信讲解", completed.snapshot?.visibleMarkdown)
        assertTrue(completed.recoveryActions.isEmpty())
        owner.close()
    }

    @Test
    fun validationFailureKeepsTheMessageEditableAndDoesNotOfferRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)

        owner.submit(studentMessage = "含有\u202E字符") {
            throw InvalidTutorStudentMessageException("unsafe bidi control")
        }
        runCurrent()

        val failed = requireNotNull(owner.state.value.active)
        assertEquals("含有\u202E字符", failed.studentMessage)
        assertEquals(TutorActiveStreamPhase.FAILED, failed.phase)
        assertFalse(failed.durablyStarted)
        assertFalse(failed.retryable)
        assertTrue(failed.failureDetail?.contains("修改") == true)
        assertTrue(failed.recoveryActions.isEmpty())
        owner.close()
    }

    @Test
    fun outputContractIllegalArgumentFailureUsesTheNormalRecoveryPath() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)

        owner.submit(studentMessage = "继续") {
            prepared("request-output-contract") { identity ->
                flow {
                    emit(TutorStreamEvent.Started(identity))
                    throw IllegalArgumentException(
                        "Invalid model task completion: TUTOR_INTENT_BOUNDARY_VIOLATION",
                    )
                }
            }
        }
        runCurrent()

        val failed = requireNotNull(owner.state.value.active)
        assertEquals(TutorActiveStreamPhase.FAILED, failed.phase)
        assertTrue(failed.durablyStarted)
        assertTrue(failed.retryable)
        assertEquals(null, failed.failureDetail)
        assertEquals(
            listOf(TutorActiveStreamRecovery.RETRY),
            failed.recoveryActions,
        )
        owner.close()
    }

    @Test
    fun storageFailureBeforeDurableStartKeepsOneRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)

        owner.submit(studentMessage = "继续") {
            prepared("request-storage-failure") {
                flow {
                    throw IllegalStateException("database unavailable")
                }
            }
        }
        runCurrent()

        val failed = requireNotNull(owner.state.value.active)
        assertEquals(TutorActiveStreamPhase.FAILED, failed.phase)
        assertFalse(failed.durablyStarted)
        assertTrue(failed.retryable)
        assertEquals(
            listOf(TutorActiveStreamRecovery.RETRY),
            failed.recoveryActions,
        )
        owner.close()
    }

    @Test
    fun storageFailureRetryIsConsumedOnlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "继续") {
            prepared("request-storage-failure") {
                flow {
                    throw IllegalStateException("database unavailable")
                }
            }
        }
        runCurrent()

        assertTrue(owner.retry())
        runCurrent()

        val failedAgain = requireNotNull(owner.state.value.active)
        assertEquals(TutorActiveStreamPhase.FAILED, failedAgain.phase)
        assertTrue(failedAgain.recoveryActions.isEmpty())
        assertFalse(owner.retry())
        owner.close()
    }

    @Test
    fun durableStartIsPublishedOnlyAfterTheStartedEvent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = MutableSharedFlow<TutorStreamEvent>(extraBufferCapacity = 2)
        val owner = owner(dispatcher)
        owner.submit(studentMessage = "继续") {
            prepared("request-1") { events }
        }
        runCurrent()

        val identity = requireNotNull(owner.state.value.active?.identity)
        assertFalse(owner.state.value.active?.durablyStarted == true)

        events.tryEmit(TutorStreamEvent.Started(identity))
        runCurrent()

        assertTrue(owner.state.value.active?.durablyStarted == true)
        owner.close()
    }

    @Test
    fun revokedRecoveryIsRemovedBeforePreparationWithoutCallingTheProvider() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)
        var prepared = false

        owner.submit(
            studentMessage = "继续",
            startsNewTurn = false,
            executionAuthorized = { false },
        ) {
            prepared = true
            prepared("request-revoked") { flow { error("provider must not run") } }
        }
        runCurrent()

        assertFalse(prepared)
        assertNull(owner.state.value.active)
        owner.close()
    }

    @Test
    fun authorityIsCheckedAgainImmediatelyBeforeOpeningTheEventFlow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)
        var checks = 0
        var eventFlowOpened = false

        owner.submit(
            studentMessage = "继续",
            startsNewTurn = false,
            executionAuthorized = {
                checks += 1
                checks < 3
            },
        ) {
            prepared("request-revoked-after-prepare") {
                eventFlowOpened = true
                flow { error("provider must not run") }
            }
        }
        runCurrent()

        assertEquals(3, checks)
        assertFalse(eventFlowOpened)
        assertNull(owner.state.value.active)
        owner.close()
    }

    @Test
    fun revokedAuthorityDropsLateEventsAndCancelsTheDurableRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = MutableSharedFlow<TutorStreamEvent>(extraBufferCapacity = 2)
        val cancelled = CompletableDeferred<String>()
        val acceptedCompletions = mutableListOf<String>()
        var authorized = true
        val owner = owner(dispatcher) { requestId ->
            cancelled.complete(requestId)
        }

        owner.submit(
            studentMessage = "继续",
            startsNewTurn = false,
            executionAuthorized = { authorized },
            onAuthorizedCompletion = { requestId ->
                acceptedCompletions += requestId
                true
            },
        ) {
            prepared("request-in-flight") { events }
        }
        runCurrent()
        val identity = requireNotNull(owner.state.value.active?.identity)
        events.tryEmit(TutorStreamEvent.Started(identity))
        runCurrent()
        assertTrue(owner.state.value.active?.durablyStarted == true)

        authorized = false
        events.tryEmit(
            TutorStreamEvent.Completed(
                identity,
                TutorMarkdownSnapshot("迟到内容", ""),
            ),
        )
        runCurrent()

        assertNull(owner.state.value.active)
        assertEquals("request-in-flight", cancelled.await())
        assertTrue(acceptedCompletions.isEmpty())
        owner.close()
    }

    @Test
    fun authorizedCompletionIsPublishedOnlyAfterTheFinalAuthorityCheck() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val acceptedCompletions = mutableListOf<String>()
        val owner = owner(dispatcher)

        owner.submit(
            studentMessage = "继续",
            startsNewTurn = false,
            executionAuthorized = { true },
            onAuthorizedCompletion = { requestId ->
                acceptedCompletions += requestId
                true
            },
        ) {
            prepared("request-authorized") { identity ->
                flowOf(
                    TutorStreamEvent.Completed(
                        identity,
                        TutorMarkdownSnapshot("最终可信讲解", ""),
                    ),
                )
            }
        }
        runCurrent()

        assertEquals(listOf("request-authorized"), acceptedCompletions)
        assertEquals(TutorActiveStreamPhase.COMPLETED, owner.state.value.active?.phase)
        owner.close()
    }

    @Test
    fun completionRejectedAfterItsEventArrivesNeverPublishesTerminalContent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val completionEntered = CompletableDeferred<Unit>()
        val allowCompletionDecision = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<String>()
        var authorized = true
        val owner = owner(dispatcher) { requestId -> cancelled.complete(requestId) }

        owner.submit(
            studentMessage = "继续",
            startsNewTurn = false,
            resultAuthorized = { authorized },
            onAuthorizedCompletion = {
                completionEntered.complete(Unit)
                allowCompletionDecision.await()
                authorized
            },
        ) {
            prepared("request-revoked-in-callback") { identity ->
                flowOf(
                    TutorStreamEvent.Completed(
                        identity,
                        TutorMarkdownSnapshot("不得出现的迟到讲解", ""),
                    ),
                )
            }
        }
        runCurrent()
        completionEntered.await()

        authorized = false
        allowCompletionDecision.complete(Unit)
        runCurrent()

        assertNull(owner.state.value.active)
        assertEquals("request-revoked-in-callback", cancelled.await())
        owner.close()
    }

    @Test
    fun onlyAStudentTurnAdvancesTheConversationGeneration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = owner(dispatcher)
        assertEquals(7L, owner.currentOwnerEpoch())
        assertTrue(
            owner.recoveryAuthorityIsOpen(
                expectedConversationGeneration = 0,
                expectedMode = TutorExplanationMode.DIRECT,
            ),
        )

        owner.submit(studentMessage = "恢复", startsNewTurn = false) {
            prepared("recovery") { flow { awaitCancellation() } }
        }
        assertEquals(0, owner.currentConversationGeneration())

        owner.submit(studentMessage = "新的回答", startsNewTurn = true) {
            prepared("new-turn") { flow { awaitCancellation() } }
        }
        assertEquals(1, owner.currentConversationGeneration())
        assertFalse(
            owner.recoveryAuthorityIsOpen(
                expectedConversationGeneration = 0,
                expectedMode = TutorExplanationMode.DIRECT,
            ),
        )
        owner.close()
        assertFalse(
            owner.recoveryAuthorityIsOpen(
                expectedConversationGeneration = 1,
                expectedMode = TutorExplanationMode.DIRECT,
            ),
        )
    }

    private fun TestScope.owner(
        dispatcher: CoroutineDispatcher,
        ownerScope: CoroutineScope = this,
        beforeCoalescedPreviewApply: suspend () -> Unit = {},
        cancelDurableRequest: suspend (String) -> Unit = {},
    ) = TutorActiveStreamOwner(
        scope = ownerScope,
        workerDispatcher = dispatcher,
        initialMode = TutorExplanationMode.DIRECT,
        ownerVersion = 7,
        coalesceMillis = 64,
        placeholderDelayMillis = 300,
        cancelDurableRequest = cancelDurableRequest,
        beforeCoalescedPreviewApply = beforeCoalescedPreviewApply,
    )

    private fun prepared(
        requestId: String,
        events: (TutorStreamIdentity) -> kotlinx.coroutines.flow.Flow<TutorStreamEvent>,
    ) = TutorPreparedStream(requestId, events)
}
