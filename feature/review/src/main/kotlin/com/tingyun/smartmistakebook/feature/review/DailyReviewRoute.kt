package com.tingyun.smartmistakebook.feature.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingyun.smartmistakebook.core.data.review.DailyReviewRepository
import com.tingyun.smartmistakebook.core.data.review.DailyReviewSessionActionPort
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeRequest
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeSessionStatus
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeState
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeUnavailableReason
import com.tingyun.smartmistakebook.core.data.review.StartDailyReviewCommand
import com.tingyun.smartmistakebook.core.data.review.StartDailyReviewResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun interface ReviewHomeRequestProvider {
    fun currentRequest(): ReviewHomeRequest
}

fun interface DailyReviewSessionIdFactory {
    fun createSessionId(): String
}

data class DailyReviewSessionLaunch internal constructor(
    val id: Long,
    val home: ReviewHomeState.Ready,
)

internal data class DailyReviewViewState(
    val landing: ReviewLandingState = ReviewLandingState.Loading,
    val readyHome: ReviewHomeState.Ready? = null,
    val pendingLaunch: DailyReviewSessionLaunch? = null,
)

internal class DailyReviewViewModel(
    private val repository: DailyReviewRepository,
    private val sessionActions: DailyReviewSessionActionPort,
    private val requestProvider: ReviewHomeRequestProvider,
    private val sessionIdFactory: DailyReviewSessionIdFactory,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DailyReviewViewState())
    val state: StateFlow<DailyReviewViewState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var startJob: Job? = null
    private var launchSequence: Long = 0L
    private var loadedRequest: ReviewHomeRequest? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        startJob?.cancel()
        loadedRequest = null
        mutableState.value = DailyReviewViewState()
        loadJob =
            viewModelScope.launch {
                val request =
                    try {
                        requestProvider.currentRequest()
                    } catch (_: Exception) {
                        showUnavailable()
                        return@launch
                    }
                loadedRequest = request
                val home =
                    try {
                        repository.readHome(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ReviewHomeState.Unavailable(
                            ReviewHomeUnavailableReason.PLAN_UNAVAILABLE,
                        )
                    }
                mutableState.value =
                    when (home) {
                        is ReviewHomeState.Ready ->
                            DailyReviewViewState(
                                landing = reviewLandingState(home),
                                readyHome = home,
                            )
                        is ReviewHomeState.Unavailable ->
                            DailyReviewViewState(
                                landing = ReviewLandingState.Unavailable,
                            )
                    }
            }
    }

    fun startOrContinue() {
        if (startJob?.isActive == true) return
        val home = mutableState.value.readyHome ?: return
        val request = loadedRequest ?: return
        if (home.plan.isComplete || home.plan.remainingItemCount == 0) return
        val nextProblem = home.nextProblem
        if (nextProblem == null) {
            showUnavailable()
            return
        }
        val activeSession =
            home.session?.takeIf { session ->
                session.status == ReviewHomeSessionStatus.ACTIVE
            }
        if (activeSession != null) {
            if (activeSession.currentQueueItemId == nextProblem.queueItemId) {
                publishLaunch(home)
            } else {
                showUnavailable()
            }
            return
        }

        mutableState.value =
            mutableState.value.copy(
                landing = reviewLandingState(home, actionInProgress = true),
            )
        startJob =
            viewModelScope.launch {
                val result =
                    try {
                        sessionActions.startOrResume(
                            StartDailyReviewCommand(
                                sessionId = sessionIdFactory.createSessionId(),
                                planId = home.plan.planId,
                                expectedPlanCanonicalFingerprint =
                                    home.plan.canonicalFingerprint,
                                startedAtEpochMillis = request.requestedAtEpochMillis,
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        mutableState.value =
                            mutableState.value.copy(
                                landing = reviewLandingState(home),
                            )
                        throw cancelled
                    } catch (_: Exception) {
                        StartDailyReviewResult.ReloadRequired
                    }
                when (result) {
                    is StartDailyReviewResult.Ready -> {
                        val ready = home.withActiveSession(result.session)
                        if (ready == null) showUnavailable() else publishLaunch(ready)
                    }
                    is StartDailyReviewResult.ActiveSessionConflict -> {
                        val ready = home.withActiveSession(result.activeSession)
                        if (ready == null) showUnavailable() else publishLaunch(ready)
                    }
                    StartDailyReviewResult.LegacyActivityConflict,
                    StartDailyReviewResult.ReloadRequired,
                    ->
                        showUnavailable()
                }
            }
    }

    fun acknowledgeLaunch(
        id: Long,
    ) {
        if (mutableState.value.pendingLaunch?.id == id) {
            mutableState.value = mutableState.value.copy(pendingLaunch = null)
        }
    }

    private fun publishLaunch(
        home: ReviewHomeState.Ready,
    ) {
        launchSequence += 1L
        mutableState.value =
            DailyReviewViewState(
                landing = reviewLandingState(home),
                readyHome = home,
                pendingLaunch =
                    DailyReviewSessionLaunch(
                        id = launchSequence,
                        home = home,
                    ),
            )
    }

    private fun showUnavailable() {
        mutableState.value =
            DailyReviewViewState(
                landing = ReviewLandingState.Unavailable,
            )
    }
}

private fun ReviewHomeState.Ready.withActiveSession(
    session: com.tingyun.smartmistakebook.core.data.review.ReviewHomeSession,
): ReviewHomeState.Ready? {
    val problem = nextProblem ?: return null
    if (
        session.status != ReviewHomeSessionStatus.ACTIVE ||
        session.planId != plan.planId ||
        session.currentQueueItemId != problem.queueItemId
    ) {
        return null
    }
    return copy(session = session)
}

internal class DailyReviewViewModelFactory(
    private val repository: DailyReviewRepository,
    private val sessionActions: DailyReviewSessionActionPort,
    private val requestProvider: ReviewHomeRequestProvider,
    private val sessionIdFactory: DailyReviewSessionIdFactory,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
    ): T {
        require(modelClass.isAssignableFrom(DailyReviewViewModel::class.java)) {
            "Unsupported daily-review ViewModel"
        }
        return DailyReviewViewModel(
            repository = repository,
            sessionActions = sessionActions,
            requestProvider = requestProvider,
            sessionIdFactory = sessionIdFactory,
        ) as T
    }
}

/**
 * Production review landing route. All dependencies are narrow, learner-bound capabilities.
 *
 * The route neither sees the broad legacy repository nor receives capture callbacks.
 */
@Composable
fun DailyReviewRoute(
    repository: DailyReviewRepository,
    sessionActions: DailyReviewSessionActionPort,
    requestProvider: ReviewHomeRequestProvider,
    onOpenSession: (ReviewHomeState.Ready) -> Unit,
    modifier: Modifier = Modifier,
    sessionIdFactory: DailyReviewSessionIdFactory =
        DailyReviewSessionIdFactory { UUID.randomUUID().toString() },
) {
    val factory =
        remember(
            repository,
            sessionActions,
            requestProvider,
            sessionIdFactory,
        ) {
            DailyReviewViewModelFactory(
                repository = repository,
                sessionActions = sessionActions,
                requestProvider = requestProvider,
                sessionIdFactory = sessionIdFactory,
            )
        }
    val stateHolder: DailyReviewViewModel = viewModel(factory = factory)
    val state by stateHolder.state.collectAsState()
    val pendingLaunch = state.pendingLaunch
    LaunchedEffect(pendingLaunch?.id) {
        if (pendingLaunch != null) {
            onOpenSession(pendingLaunch.home)
            stateHolder.acknowledgeLaunch(pendingLaunch.id)
        }
    }
    ReviewHomeScreen(
        state = state.landing,
        onPrimaryAction = stateHolder::startOrContinue,
        onRetry = stateHolder::load,
        modifier = modifier,
    )
}
