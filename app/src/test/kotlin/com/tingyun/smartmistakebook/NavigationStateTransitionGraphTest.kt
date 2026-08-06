package com.tingyun.smartmistakebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStateTransitionGraphTest {
    @Test
    fun everySecondaryWorkflowDeclaresACompleteStateTransitionGraph() {
        assertEquals(secondaryWorkflows.size, stateGraphs.size)
        assertEquals(
            secondaryWorkflows.mapTo(linkedSetOf()) { it },
            stateGraphs.mapTo(linkedSetOf()) { it.workflow },
        )
    }

    @Test
    fun everyGraphNodeIsReachableFromItsEntryState() {
        stateGraphs.forEach { graph ->
            val reachable = reachableFrom(graph, graph.entry)
            assertTrue(
                "Unreachable states in ${graph.workflow}: ${graph.states - reachable}",
                graph.states == reachable,
            )
        }
    }

    @Test
    fun noGraphHasSelfLoopsOrUnreachableDeadEnds() {
        stateGraphs.forEach { graph ->
            graph.transitions.forEach { (from, to) ->
                assertTrue("Self-loop in ${graph.workflow}: $from", from != to)
            }
            graph.states
                .filterNot { it == graph.recovery }
                .forEach { state ->
                    assertTrue(
                        "No exit from $state in ${graph.workflow}",
                        graph.transitions.any { (from, _) -> from == state },
                    )
                }
        }
    }

    @Test
    fun everyStateCanReachTheBottomBarRecoveryDestination() {
        stateGraphs.forEach { graph ->
            graph.states.forEach { state ->
                assertTrue(
                    "${graph.workflow} state $state cannot reach ${graph.recovery}",
                    canReach(graph, state, graph.recovery),
                )
            }
            assertTrue(
                "${graph.workflow} recovery must be a root destination",
                graph.recovery in rootDestinationRoutes,
            )
        }
    }

    @Test
    fun loadingAndErrorStatesAlwaysHaveExplicitExits() {
        stateGraphs.forEach { graph ->
            val exits = graph.transitions.groupBy({ it.first }, { it.second })
            assertTrue("${graph.workflow} missing loading state", "loading" in graph.states)
            assertTrue("${graph.workflow} missing error state", "error" in graph.states)
            assertTrue("${graph.workflow} loading has no exit", exits.getValue("loading").isNotEmpty())
            assertTrue("${graph.workflow} error has no exit", exits.getValue("error").isNotEmpty())
        }
    }

    private fun reachableFrom(
        graph: WorkflowStateGraph,
        start: String,
    ): Set<String> {
        val bySource = graph.transitions.groupBy({ it.first }, { it.second })
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            bySource[current].orEmpty().forEach { next -> queue.addLast(next) }
        }
        return visited
    }

    private fun canReach(
        graph: WorkflowStateGraph,
        start: String,
        target: String,
    ): Boolean = target in reachableFrom(graph, start)

    private data class WorkflowStateGraph(
        val workflow: String,
        val recovery: String,
        val entry: String = "loading",
        val states: Set<String>,
        val transitions: Set<Pair<String, String>>,
    )

    private val secondaryWorkflows: List<String> =
        listOf(
            Routes.ReviewSession,
            Routes.CaptureTutor,
            Routes.CaptureLibrary,
            Routes.CaptureInbox,
            Routes.BatchImport,
            Routes.LibraryBatchExport,
            Routes.CaptureResume,
            Routes.MistakeDetail,
            Routes.MistakeExport,
            Routes.Capability,
            Routes.LearningMastery,
            Routes.Privacy,
            Routes.Reminder,
            Routes.Storage,
        )

    private val stateGraphs: List<WorkflowStateGraph> =
        secondaryWorkflows.map { workflow ->
            val recovery =
                when (workflow) {
                    Routes.ReviewSession -> Routes.Review
                    Routes.CaptureTutor, Routes.CaptureResume -> Routes.Tutor
                    Routes.CaptureLibrary, Routes.CaptureInbox, Routes.BatchImport,
                    Routes.LibraryBatchExport, Routes.MistakeDetail, Routes.MistakeExport,
                    -> Routes.Library
                    Routes.Capability, Routes.LearningMastery, Routes.Privacy,
                    Routes.Reminder, Routes.Storage,
                    -> Routes.Profile
                    else -> error("Missing recovery edge for $workflow")
                }
            val hasWorking =
                workflow in
                    setOf(
                        Routes.ReviewSession,
                        Routes.CaptureTutor,
                        Routes.CaptureLibrary,
                        Routes.CaptureInbox,
                        Routes.BatchImport,
                        Routes.LibraryBatchExport,
                        Routes.MistakeExport,
                    )
            val states =
                buildSet {
                    add("loading")
                    add("content")
                    add("error")
                    add(recovery)
                    if (hasWorking) add("working")
                }
            val transitions =
                buildSet {
                    add("loading" to "content")
                    add("loading" to "error")
                    add("content" to recovery)
                    add("error" to "content")
                    add("error" to recovery)
                    if (hasWorking) {
                        add("content" to "working")
                        add("working" to "content")
                        add("working" to "error")
                        add("working" to recovery)
                    }
                }
            WorkflowStateGraph(
                workflow = workflow,
                recovery = recovery,
                states = states,
                transitions = transitions,
            )
        }
}
