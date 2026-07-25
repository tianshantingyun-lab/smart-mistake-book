package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Nine fixed places keep model output semantic while layout and coordinates remain local. */
@Serializable
enum class TutorDiagramAnchor {
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    LEFT,
    CENTER,
    RIGHT,
    BOTTOM_LEFT,
    BOTTOM,
    BOTTOM_RIGHT,
}

@Serializable
enum class TutorDiagramNodeShape {
    POINT,
    CIRCLE,
    BLOCK,
    BATTERY,
    RESISTOR,
    LAMP,
    SWITCH_OPEN,
    SWITCH_CLOSED,
    AMMETER,
    VOLTMETER,
    JUNCTION,
}

val TutorDiagramNodeShape.isCircuitComponent: Boolean
    get() = this in CIRCUIT_COMPONENT_SHAPES

val TutorDiagramNodeShape.isCircuitNode: Boolean
    get() = isCircuitComponent || this == TutorDiagramNodeShape.JUNCTION

@Serializable
enum class TutorDiagramEdgeStyle {
    LINE,
    DASHED,
    ARROW,
}

@Serializable
data class TutorDiagramNode(
    val nodeId: String,
    val label: String,
    val anchor: TutorDiagramAnchor,
    val shape: TutorDiagramNodeShape,
) {
    init {
        nodeId.requireTutorSceneId("Tutor spatial node id")
        label.requireTutorSceneText(
            "Tutor spatial node label",
            TutorVisualScene.MAX_SPATIAL_NODE_LABEL_CHARS,
            false,
        )
        if (shape == TutorDiagramNodeShape.CIRCLE) {
            require(label.length <= TutorVisualScene.MAX_SPATIAL_COMPACT_LABEL_CHARS) {
                "Tutor circular node label is too long"
            }
        }
        if (shape.isCircuitComponent) {
            require(label.length <= TutorVisualScene.MAX_SPATIAL_COMPACT_LABEL_CHARS) {
                "Tutor circuit component label is too long"
            }
        }
    }
}

@Serializable
data class TutorDiagramEdge(
    val edgeId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val label: String? = null,
    val style: TutorDiagramEdgeStyle,
) {
    init {
        edgeId.requireTutorSceneId("Tutor spatial edge id")
        fromNodeId.requireTutorSceneId("Tutor spatial edge start id")
        toNodeId.requireTutorSceneId("Tutor spatial edge end id")
        require(fromNodeId != toNodeId) { "Tutor spatial edge cannot connect a node to itself" }
        label?.requireTutorSceneText(
            "Tutor spatial edge label",
            TutorVisualScene.MAX_SPATIAL_EDGE_LABEL_CHARS,
            false,
        )
    }
}

/**
 * One bounded spatial explanation of the confirmed question. The model chooses named places and
 * relations only; the app owns all coordinates, strokes, colors, sizing, and accessibility text.
 */
@Serializable
@SerialName("spatial_diagram")
data class TutorSpatialDiagramScene(
    override val sceneId: String,
    override val title: String,
    val nodes: List<TutorDiagramNode>,
    val edges: List<TutorDiagramEdge>,
    val captionMarkdown: String? = null,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorVisualScene {
    init {
        requireTutorSceneHeader(sceneId, title, schemaVersion)
        val isCircuitDiagram = nodes.any { it.shape.isCircuitNode }
        val maximumNodeCount = if (isCircuitDiagram) {
            TutorVisualScene.MAX_CIRCUIT_NODE_COUNT
        } else {
            TutorVisualScene.MAX_SPATIAL_NODE_COUNT
        }
        require(nodes.size in TutorVisualScene.MIN_SPATIAL_NODE_COUNT..maximumNodeCount) {
            "Tutor spatial diagram contains an unsupported number of nodes"
        }
        require(edges.size in TutorVisualScene.MIN_SPATIAL_EDGE_COUNT..
            TutorVisualScene.MAX_SPATIAL_EDGE_COUNT) {
            "Tutor spatial diagram must contain one to ten edges"
        }
        requireUniqueTutorSceneIds(
            sceneId,
            nodes.map(TutorDiagramNode::nodeId) + edges.map(TutorDiagramEdge::edgeId),
        )
        require(nodes.map(TutorDiagramNode::anchor).distinct().size == nodes.size) {
            "Tutor spatial diagram nodes must use distinct places"
        }
        val nodeIds = nodes.mapTo(hashSetOf(), TutorDiagramNode::nodeId)
        require(edges.all { it.fromNodeId in nodeIds && it.toNodeId in nodeIds }) {
            "Tutor spatial diagram edges must reference visible nodes"
        }
        require(edges.map(::unorderedEndpointKey).distinct().size == edges.size) {
            "Tutor spatial diagram cannot draw overlapping relations"
        }
        if (isCircuitDiagram) {
            require(nodes.any { it.shape.isCircuitComponent }) {
                "Tutor circuit diagram must contain a circuit component"
            }
            require(nodes.all { it.shape.isCircuitNode }) {
                "Tutor circuit diagram cannot mix circuit and general diagram nodes"
            }
            require(edges.all { it.style == TutorDiagramEdgeStyle.LINE }) {
                "Tutor circuit diagram wires must use solid lines"
            }
            val nodesById = nodes.associateBy(TutorDiagramNode::nodeId)
            val adjacentNodeIds = nodeIds.associateWith { mutableSetOf<String>() }
            edges.forEach { edge ->
                adjacentNodeIds.getValue(edge.fromNodeId).add(edge.toNodeId)
                adjacentNodeIds.getValue(edge.toNodeId).add(edge.fromNodeId)
            }
            val visitedNodeIds = mutableSetOf<String>()
            val pendingNodeIds = ArrayDeque(listOf(nodes.first().nodeId))
            while (pendingNodeIds.isNotEmpty()) {
                val nodeId = pendingNodeIds.removeFirst()
                if (visitedNodeIds.add(nodeId)) {
                    pendingNodeIds.addAll(adjacentNodeIds.getValue(nodeId) - visitedNodeIds)
                }
            }
            require(visitedNodeIds == nodeIds) {
                "Tutor circuit diagram must be connected"
            }
            nodes.filter { it.shape.isCircuitComponent }.forEach { component ->
                val neighborAnchors = adjacentNodeIds.getValue(component.nodeId)
                    .map { nodesById.getValue(it).anchor.gridPoint() }
                require(
                    neighborAnchors.size == 2 &&
                        component.anchor.gridPoint().isStrictlyOnSegment(
                            neighborAnchors.first(),
                            neighborAnchors.last(),
                        ),
                ) {
                    "Tutor circuit component must sit between two aligned wires"
                }
            }
        }
        val anchorsByNodeId = nodes.associate { it.nodeId to it.anchor.gridPoint() }
        require(edges.none { edge ->
            val start = anchorsByNodeId.getValue(edge.fromNodeId)
            val end = anchorsByNodeId.getValue(edge.toNodeId)
            anchorsByNodeId.any { (nodeId, point) ->
                nodeId != edge.fromNodeId &&
                    nodeId != edge.toNodeId &&
                    point.isStrictlyOnSegment(start, end)
            }
        }) {
            "Tutor spatial diagram relation cannot pass through another node"
        }
        require(edges.indices.none { firstIndex ->
            ((firstIndex + 1)..edges.lastIndex).any { secondIndex ->
                val first = edges[firstIndex]
                val second = edges[secondIndex]
                val sharedNode = first.fromNodeId == second.fromNodeId ||
                    first.fromNodeId == second.toNodeId ||
                    first.toNodeId == second.fromNodeId ||
                    first.toNodeId == second.toNodeId
                !sharedNode && segmentsIntersect(
                    anchorsByNodeId.getValue(first.fromNodeId),
                    anchorsByNodeId.getValue(first.toNodeId),
                    anchorsByNodeId.getValue(second.fromNodeId),
                    anchorsByNodeId.getValue(second.toNodeId),
                )
            }
        }) {
            "Tutor spatial diagram relations cannot cross"
        }
        captionMarkdown?.requireTutorSceneText(
            "Tutor spatial diagram caption",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
        requireTutorSceneTextBudget(
            listOf(title) +
                nodes.map(TutorDiagramNode::label) +
                edges.mapNotNull(TutorDiagramEdge::label) +
                listOfNotNull(captionMarkdown),
        )
    }
}

val TutorSpatialDiagramScene.isCircuitDiagram: Boolean
    get() = nodes.any { it.shape.isCircuitNode }

private val CIRCUIT_COMPONENT_SHAPES = setOf(
    TutorDiagramNodeShape.BATTERY,
    TutorDiagramNodeShape.RESISTOR,
    TutorDiagramNodeShape.LAMP,
    TutorDiagramNodeShape.SWITCH_OPEN,
    TutorDiagramNodeShape.SWITCH_CLOSED,
    TutorDiagramNodeShape.AMMETER,
    TutorDiagramNodeShape.VOLTMETER,
)

private fun unorderedEndpointKey(edge: TutorDiagramEdge): Pair<String, String> =
    if (edge.fromNodeId < edge.toNodeId) {
        edge.fromNodeId to edge.toNodeId
    } else {
        edge.toNodeId to edge.fromNodeId
    }

private data class DiagramGridPoint(
    val x: Int,
    val y: Int,
)

private fun TutorDiagramAnchor.gridPoint(): DiagramGridPoint = when (this) {
    TutorDiagramAnchor.TOP_LEFT -> DiagramGridPoint(0, 0)
    TutorDiagramAnchor.TOP -> DiagramGridPoint(1, 0)
    TutorDiagramAnchor.TOP_RIGHT -> DiagramGridPoint(2, 0)
    TutorDiagramAnchor.LEFT -> DiagramGridPoint(0, 1)
    TutorDiagramAnchor.CENTER -> DiagramGridPoint(1, 1)
    TutorDiagramAnchor.RIGHT -> DiagramGridPoint(2, 1)
    TutorDiagramAnchor.BOTTOM_LEFT -> DiagramGridPoint(0, 2)
    TutorDiagramAnchor.BOTTOM -> DiagramGridPoint(1, 2)
    TutorDiagramAnchor.BOTTOM_RIGHT -> DiagramGridPoint(2, 2)
}

private fun DiagramGridPoint.isStrictlyOnSegment(
    start: DiagramGridPoint,
    end: DiagramGridPoint,
): Boolean =
    orientation(start, end, this) == 0 &&
        x in minOf(start.x, end.x)..maxOf(start.x, end.x) &&
        y in minOf(start.y, end.y)..maxOf(start.y, end.y)

private fun segmentsIntersect(
    firstStart: DiagramGridPoint,
    firstEnd: DiagramGridPoint,
    secondStart: DiagramGridPoint,
    secondEnd: DiagramGridPoint,
): Boolean {
    val firstToSecondStart = orientation(firstStart, firstEnd, secondStart)
    val firstToSecondEnd = orientation(firstStart, firstEnd, secondEnd)
    val secondToFirstStart = orientation(secondStart, secondEnd, firstStart)
    val secondToFirstEnd = orientation(secondStart, secondEnd, firstEnd)
    if (firstToSecondStart.hasOppositeSign(firstToSecondEnd) &&
        secondToFirstStart.hasOppositeSign(secondToFirstEnd)
    ) {
        return true
    }
    return (firstToSecondStart == 0 && secondStart.isStrictlyOnSegment(firstStart, firstEnd)) ||
        (firstToSecondEnd == 0 && secondEnd.isStrictlyOnSegment(firstStart, firstEnd)) ||
        (secondToFirstStart == 0 && firstStart.isStrictlyOnSegment(secondStart, secondEnd)) ||
        (secondToFirstEnd == 0 && firstEnd.isStrictlyOnSegment(secondStart, secondEnd))
}

private fun Int.hasOppositeSign(other: Int): Boolean =
    (this < 0 && other > 0) || (this > 0 && other < 0)

private fun orientation(
    start: DiagramGridPoint,
    end: DiagramGridPoint,
    point: DiagramGridPoint,
): Int = (end.x - start.x) * (point.y - start.y) -
    (end.y - start.y) * (point.x - start.x)
