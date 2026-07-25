package com.tingyun.smartmistakebook.core.visual.runtime

import com.tingyun.smartmistakebook.core.model.*

internal enum class TutorVisualBenchmarkFamily {
    CIRCUIT,
    OPTICS,
    WAVEFORM,
    FORCE_AND_MECHANISM,
    SOLID_GEOMETRY,
    FUNCTION_AND_STATISTICS,
    MOLECULAR_STRUCTURE,
    EXPERIMENTAL_APPARATUS,
    BIOLOGICAL_STRUCTURE_AND_REGULATION,
    GEOGRAPHIC_SECTION_AND_PROCESS,
    MATERIAL_RELATIONSHIP,
    SYNCHRONIZED_MULTI_VIEW,
}

internal data class TutorVisualFamilyFixture(
    val family: TutorVisualBenchmarkFamily,
    val subjects: Set<String>,
    val scene: TutorVisualDocumentScene,
)

/**
 * Coverage fixtures exercise reusable protocol combinations only. They are not question-specific
 * pages and contain no question numbers, source filenames, or renderer switches.
 */
internal object TutorVisualFamilyFixtures {
    val all: List<TutorVisualFamilyFixture> = listOf(
        fixture(
            TutorVisualBenchmarkFamily.CIRCUIT,
            setOf("PHYSICS", "TECHNOLOGY"),
            diagram(
                id = "circuit",
                title = "电路连接",
                firstKind = TutorVisual2DNodeKind.BATTERY,
                secondKind = TutorVisual2DNodeKind.RESISTOR,
                connectorKind = TutorVisualConnectorKind.WIRE,
            ),
        ),
        fixture(
            TutorVisualBenchmarkFamily.OPTICS,
            setOf("PHYSICS"),
            diagram(
                id = "optics",
                title = "光路关系",
                firstKind = TutorVisual2DNodeKind.LENS,
                secondKind = TutorVisual2DNodeKind.MIRROR,
                connectorKind = TutorVisualConnectorKind.RAY,
            ),
        ),
        fixture(
            TutorVisualBenchmarkFamily.WAVEFORM,
            setOf("PHYSICS", "MATH"),
            diagram(
                id = "wave",
                title = "波形变化",
                firstKind = TutorVisual2DNodeKind.AXES,
                secondKind = TutorVisual2DNodeKind.WAVE,
                connectorKind = TutorVisualConnectorKind.LINE,
            ),
        ),
        fixture(
            TutorVisualBenchmarkFamily.FORCE_AND_MECHANISM,
            setOf("PHYSICS", "TECHNOLOGY"),
            diagram(
                id = "force",
                title = "受力关系",
                firstKind = TutorVisual2DNodeKind.RECTANGLE,
                secondKind = TutorVisual2DNodeKind.POINT,
                connectorKind = TutorVisualConnectorKind.FORCE,
            ),
        ),
        fixture(
            TutorVisualBenchmarkFamily.SOLID_GEOMETRY,
            setOf("MATH"),
            geometry3d("solid", "立体几何关系", TutorVisualGeometry3DKind.CUBE),
        ),
        fixture(
            TutorVisualBenchmarkFamily.FUNCTION_AND_STATISTICS,
            setOf("MATH", "GEOGRAPHY"),
            chart("function", "函数与统计变化"),
        ),
        fixture(
            TutorVisualBenchmarkFamily.MOLECULAR_STRUCTURE,
            setOf("CHEMISTRY", "BIOLOGY"),
            geometry3d("molecule", "分子空间关系", TutorVisualGeometry3DKind.SPHERE),
        ),
        fixture(
            TutorVisualBenchmarkFamily.EXPERIMENTAL_APPARATUS,
            setOf("CHEMISTRY", "PHYSICS", "BIOLOGY"),
            diagram(
                id = "experiment",
                title = "实验装置连接",
                firstKind = TutorVisual2DNodeKind.CONTAINER,
                secondKind = TutorVisual2DNodeKind.RESERVOIR,
                connectorKind = TutorVisualConnectorKind.PIPE,
            ),
        ),
        fixture(
            TutorVisualBenchmarkFamily.BIOLOGICAL_STRUCTURE_AND_REGULATION,
            setOf("BIOLOGY", "POLITICS"),
            diagram(
                id = "biology",
                title = "结构与调节过程",
                firstKind = TutorVisual2DNodeKind.BIOLOGICAL_STRUCTURE,
                secondKind = TutorVisual2DNodeKind.BIOLOGICAL_STRUCTURE,
                connectorKind = TutorVisualConnectorKind.FLOW,
            ),
        ),
        fixture(
            TutorVisualBenchmarkFamily.GEOGRAPHIC_SECTION_AND_PROCESS,
            setOf("GEOGRAPHY", "HISTORY"),
            diagram(
                id = "geography",
                title = "剖面与过程",
                firstKind = TutorVisual2DNodeKind.GEOGRAPHIC_LAYER,
                secondKind = TutorVisual2DNodeKind.GEOGRAPHIC_LAYER,
                connectorKind = TutorVisualConnectorKind.FLOW,
            ),
        ),
        fixture(
            TutorVisualBenchmarkFamily.MATERIAL_RELATIONSHIP,
            setOf("CHINESE", "ENGLISH", "HISTORY", "POLITICS"),
            diagram(
                id = "material",
                title = "材料关系",
                firstKind = TutorVisual2DNodeKind.MATERIAL_NODE,
                secondKind = TutorVisual2DNodeKind.MATERIAL_NODE,
                connectorKind = TutorVisualConnectorKind.LINE,
            ),
        ),
        fixture(
            TutorVisualBenchmarkFamily.SYNCHRONIZED_MULTI_VIEW,
            setOf("PHYSICS", "CHEMISTRY", "MATH"),
            synchronizedViews(),
        ),
    )

    private fun fixture(
        family: TutorVisualBenchmarkFamily,
        subjects: Set<String>,
        scene: TutorVisualDocumentScene,
    ) = TutorVisualFamilyFixture(family, subjects, scene)

    private fun diagram(
        id: String,
        title: String,
        firstKind: TutorVisual2DNodeKind,
        secondKind: TutorVisual2DNodeKind,
        connectorKind: TutorVisualConnectorKind,
    ): TutorVisualDocumentScene {
        val panelId = "${id}_panel"
        val firstId = "${id}_first"
        val secondId = "${id}_second"
        val relationId = "${id}_relation"
        return document(
            id = id,
            title = title,
            panels = listOf(TutorVisualPanel(panelId, TutorVisualPanelKind.DIAGRAM_2D)),
            elements = listOf(
                TutorVisual2DNodeElement(
                    elementId = firstId,
                    panelId = panelId,
                    kind = firstKind,
                    label = "起点",
                    layout = TutorVisualLayoutHint(anchor = TutorVisualAnchor.START),
                ),
                TutorVisual2DNodeElement(
                    elementId = secondId,
                    panelId = panelId,
                    kind = secondKind,
                    label = "终点",
                    layout = TutorVisualLayoutHint(anchor = TutorVisualAnchor.END),
                ),
                TutorVisual2DConnectorElement(
                    elementId = relationId,
                    panelId = panelId,
                    kind = connectorKind,
                    from = TutorVisualConnectionAnchor(firstId),
                    to = TutorVisualConnectionAnchor(secondId),
                    label = "关键关系",
                ),
            ),
            primaryElementId = relationId,
        )
    }

    private fun geometry3d(
        id: String,
        title: String,
        kind: TutorVisualGeometry3DKind,
    ): TutorVisualDocumentScene {
        val panelId = "${id}_panel"
        val objectId = "${id}_object"
        return document(
            id = id,
            title = title,
            panels = listOf(
                TutorVisualPanel(
                    panelId = panelId,
                    kind = TutorVisualPanelKind.SCENE_3D,
                    camera = TutorVisualCamera(),
                ),
            ),
            elements = listOf(
                TutorVisualGeometry3DElement(
                    elementId = objectId,
                    panelId = panelId,
                    kind = kind,
                    label = "空间对象",
                ),
            ),
            primaryElementId = objectId,
        )
    }

    private fun chart(id: String, title: String): TutorVisualDocumentScene {
        val panelId = "${id}_panel"
        val seriesId = "${id}_series"
        return document(
            id = id,
            title = title,
            panels = listOf(
                TutorVisualPanel(
                    panelId = panelId,
                    kind = TutorVisualPanelKind.SCIENTIFIC_CHART,
                    chart = TutorVisualChartConfiguration(
                        xAxisLabel = "横轴",
                        leftAxisLabel = "纵轴",
                    ),
                ),
            ),
            elements = listOf(
                TutorVisualChartSeriesElement(
                    elementId = seriesId,
                    panelId = panelId,
                    label = "变化",
                    kind = TutorVisualChartSeriesKind.LINE,
                    points = listOf(
                        TutorVisualChartPoint(0.0, 0.0),
                        TutorVisualChartPoint(1.0, 1.0),
                        TutorVisualChartPoint(2.0, 0.5),
                    ),
                    source = TutorVisualValueSource.GIVEN,
                ),
            ),
            primaryElementId = seriesId,
        )
    }

    private fun synchronizedViews(): TutorVisualDocumentScene {
        val diagramPanel = "multi_diagram"
        val scenePanel = "multi_space"
        val chartPanel = "multi_chart"
        return document(
            id = "multi",
            title = "同步多视图",
            panels = listOf(
                TutorVisualPanel(diagramPanel, TutorVisualPanelKind.DIAGRAM_2D),
                TutorVisualPanel(
                    panelId = scenePanel,
                    kind = TutorVisualPanelKind.SCENE_3D,
                    camera = TutorVisualCamera(),
                ),
                TutorVisualPanel(
                    panelId = chartPanel,
                    kind = TutorVisualPanelKind.SCIENTIFIC_CHART,
                    chart = TutorVisualChartConfiguration("时间", "变化"),
                ),
            ),
            elements = listOf(
                TutorVisual2DNodeElement(
                    "multi_node",
                    diagramPanel,
                    TutorVisual2DNodeKind.RECTANGLE,
                    label = "二维位置",
                ),
                TutorVisualGeometry3DElement(
                    "multi_object",
                    scenePanel,
                    TutorVisualGeometry3DKind.CUBE,
                    label = "空间位置",
                ),
                TutorVisualChartSeriesElement(
                    "multi_series",
                    chartPanel,
                    "随时间变化",
                    TutorVisualChartSeriesKind.LINE,
                    points = listOf(
                        TutorVisualChartPoint(0.0, 0.0),
                        TutorVisualChartPoint(1.0, 1.0),
                    ),
                    source = TutorVisualValueSource.DERIVED,
                ),
            ),
            primaryElementId = "multi_node",
        )
    }

    private fun document(
        id: String,
        title: String,
        panels: List<TutorVisualPanel>,
        elements: List<TutorVisualDocumentElement>,
        primaryElementId: String,
    ) = TutorVisualDocumentScene(
        sceneId = "${id}_scene",
        title = title,
        panels = panels,
        elements = elements,
        steps = listOf(
            TutorVisualStep(
                stepId = "${id}_step",
                label = "先看关键关系",
                focusElementIds = listOf(primaryElementId),
                primaryRelationElementId = primaryElementId,
            ),
        ),
        fallbackMarkdown = "先核对图中当前突出显示的关系。",
        accessibilitySummary = "$title，当前突出显示一项关键关系。",
    )
}
