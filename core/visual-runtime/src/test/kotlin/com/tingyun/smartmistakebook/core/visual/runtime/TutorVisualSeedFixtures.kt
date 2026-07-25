package com.tingyun.smartmistakebook.core.visual.runtime

import com.tingyun.smartmistakebook.core.model.*

/**
 * Semantic acceptance seeds derived from the five supplied figure families.
 *
 * They deliberately contain no source-image pixels, question-number switches, or renderer hooks.
 * Every fixture reaches the same compiler and renderer entry point.
 */
internal object TutorVisualSeedFixtures {
    val all: List<TutorVisualDocumentScene> by lazy {
        listOf(
            crystalLattice(),
            membraneFlowBattery(),
            rotatingCoil(),
            uTubeGasColumns(),
            dualAxisChemistryChart(),
        )
    }

    fun crystalLattice() = TutorVisualDocumentScene(
        sceneId = "crystal_scene",
        title = "晶胞空间关系",
        panels = listOf(
            TutorVisualPanel(
                panelId = "crystal_panel",
                kind = TutorVisualPanelKind.SCENE_3D,
                camera = TutorVisualCamera(
                    projection = TutorVisualProjection.ORTHOGRAPHIC,
                    distance = 9.0,
                ),
            ),
        ),
        elements = listOf(
            TutorVisualLatticeElement(
                elementId = "crystal_lattice",
                panelId = "crystal_panel",
                latticeVectors = listOf(
                    TutorVisualVector3(1.0, 0.0, 0.0),
                    TutorVisualVector3(0.0, 1.0, 0.0),
                    TutorVisualVector3(0.0, 0.0, 1.0),
                ),
                basis = listOf(
                    TutorVisualLatticeBasisSite(
                        fractionalCoordinate = TutorVisualVector3(0.0, 0.0, 0.0),
                        label = "硼",
                    ),
                    TutorVisualLatticeBasisSite(
                        fractionalCoordinate = TutorVisualVector3(0.25, 0.25, 0.25),
                        label = "氮",
                        radiusScale = 0.82,
                    ),
                ),
                repeat = TutorVisualRepeat3D(2, 2, 2),
                connectionCutoff = 0.5,
                label = "立方晶胞",
            ),
            TutorVisualGeometry3DElement(
                elementId = "crystal_axes",
                panelId = "crystal_panel",
                kind = TutorVisualGeometry3DKind.AXES,
                label = "坐标方向",
                layer = TutorVisualLayer.ANNOTATION,
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "crystal_step_basis",
                label = "先看一个晶胞内的位置",
                focusElementIds = listOf("crystal_lattice"),
                dimmedElementIds = listOf("crystal_axes"),
                primaryRelationElementId = "crystal_lattice",
            ),
            TutorVisualStep(
                stepId = "crystal_step_axes",
                label = "再按坐标方向核对位置",
                focusElementIds = listOf("crystal_axes"),
                dimmedElementIds = listOf("crystal_lattice"),
            ),
        ),
        fallbackMarkdown = "先依据分数坐标确定晶胞内的位置，再检查周期重复后最近的相邻位置。",
        accessibilitySummary = "可旋转的立方晶胞，硼和氮用不同大小的球表示，并标有三个坐标方向。",
    )

    fun membraneFlowBattery(): TutorVisualDocumentScene {
        val panelId = "battery_panel"
        val nodes = listOf(
            node("battery_feed_a", panelId, TutorVisual2DNodeKind.RESERVOIR, "多硫电解质", 0.08, 0.58),
            node("battery_pump_a", panelId, TutorVisual2DNodeKind.PUMP, "泵", 0.20, 0.82, TutorVisualSizeClass.SMALL),
            node("battery_region_one", panelId, TutorVisual2DNodeKind.REGION, "区域一", 0.34, 0.54, TutorVisualSizeClass.TALL),
            node("battery_membrane_a", panelId, TutorVisual2DNodeKind.MEMBRANE, "膜 a", 0.46, 0.54, TutorVisualSizeClass.TALL),
            node("battery_region_two", panelId, TutorVisual2DNodeKind.REGION, "区域二", 0.56, 0.54, TutorVisualSizeClass.TALL),
            node("battery_membrane_b", panelId, TutorVisual2DNodeKind.MEMBRANE, "膜 b", 0.66, 0.54, TutorVisualSizeClass.TALL),
            node("battery_region_three", panelId, TutorVisual2DNodeKind.REGION, "区域三", 0.76, 0.54, TutorVisualSizeClass.TALL),
            node("battery_feed_b", panelId, TutorVisual2DNodeKind.RESERVOIR, "氢氧化钠溶液", 0.92, 0.58),
            node("battery_pump_b", panelId, TutorVisual2DNodeKind.PUMP, "泵", 0.84, 0.82, TutorVisualSizeClass.SMALL),
            node("battery_electrode_a", panelId, TutorVisual2DNodeKind.ELECTRODE, "电极 A", 0.28, 0.28),
            node("battery_electrode_b", panelId, TutorVisual2DNodeKind.ELECTRODE, "电极 B", 0.82, 0.28),
            node("battery_load", panelId, TutorVisual2DNodeKind.BATTERY, "电源或负载", 0.55, 0.12, TutorVisualSizeClass.WIDE),
        )
        val connectors = listOf(
            connector("battery_pipe_a", panelId, TutorVisualConnectorKind.PIPE, "battery_feed_a", "battery_region_one"),
            connector("battery_return_a", panelId, TutorVisualConnectorKind.FLOW, "battery_region_one", "battery_pump_a"),
            connector("battery_flow_a", panelId, TutorVisualConnectorKind.FLOW, "battery_pump_a", "battery_feed_a"),
            connector("battery_cross_a", panelId, TutorVisualConnectorKind.FLOW, "battery_region_one", "battery_region_two"),
            connector("battery_cross_b", panelId, TutorVisualConnectorKind.FLOW, "battery_region_two", "battery_region_three"),
            connector("battery_pipe_b", panelId, TutorVisualConnectorKind.PIPE, "battery_feed_b", "battery_region_three"),
            connector("battery_return_b", panelId, TutorVisualConnectorKind.FLOW, "battery_region_three", "battery_pump_b"),
            connector("battery_flow_b", panelId, TutorVisualConnectorKind.FLOW, "battery_pump_b", "battery_feed_b"),
            connector("battery_wire_a", panelId, TutorVisualConnectorKind.WIRE, "battery_electrode_a", "battery_load"),
            connector("battery_wire_b", panelId, TutorVisualConnectorKind.WIRE, "battery_load", "battery_electrode_b"),
        )
        val elements = buildList<TutorVisualDocumentElement> {
            addAll(nodes)
            addAll(connectors)
            add(
                TutorVisualParticleGroupElement(
                    elementId = "battery_sodium",
                    panelId = panelId,
                    regionElementId = "battery_region_one",
                    label = "钠离子",
                    instanceCount = 24,
                    motion = TutorVisualParticleMotion.FOLLOW_PATH,
                    pathElementId = "battery_cross_a",
                    deterministicSeed = 17,
                ),
            )
            add(
                TutorVisualParticleGroupElement(
                    elementId = "battery_hydroxide",
                    panelId = panelId,
                    regionElementId = "battery_region_three",
                    label = "氢氧根离子",
                    instanceCount = 18,
                    motion = TutorVisualParticleMotion.FOLLOW_PATH,
                    pathElementId = "battery_cross_b",
                    deterministicSeed = 23,
                ),
            )
        }
        return TutorVisualDocumentScene(
            sceneId = "battery_scene",
            title = "双膜液流电池",
            panels = listOf(TutorVisualPanel(panelId, TutorVisualPanelKind.DIAGRAM_2D)),
            elements = elements,
            bindings = listOf(
                TutorVisualBinding(
                    bindingId = "battery_particle_motion",
                    target = TutorVisualBindingTarget.ELEMENT,
                    targetId = "battery_sodium",
                    property = TutorVisualBindingProperty.PARTICLE_PROGRESS,
                    expression = TutorVisualDocumentExpression.timeProgress(),
                ),
            ),
            steps = listOf(
                TutorVisualStep(
                    stepId = "battery_step_regions",
                    label = "先分清三个区域",
                    focusElementIds = listOf("battery_region_one", "battery_region_two", "battery_region_three"),
                    dimmedElementIds = nodes.map { it.elementId } -
                        setOf("battery_region_one", "battery_region_two", "battery_region_three"),
                ),
                TutorVisualStep(
                    stepId = "battery_step_membranes",
                    label = "再看两层膜允许的迁移方向",
                    focusElementIds = listOf("battery_membrane_a", "battery_membrane_b", "battery_cross_a", "battery_cross_b"),
                    primaryRelationElementId = "battery_cross_a",
                    animationStartSeconds = 0.0,
                    animationEndSeconds = 6.0,
                ),
                TutorVisualStep(
                    stepId = "battery_step_circuit",
                    label = "最后核对电极和外电路",
                    focusElementIds = listOf("battery_electrode_a", "battery_electrode_b", "battery_load"),
                    primaryRelationElementId = "battery_wire_a",
                ),
            ),
            durationSeconds = 6.0,
            fallbackMarkdown = "先按三个液体区域、两层膜和外电路分层，再依据反应方向判断离子与电子的移动。",
            accessibilitySummary = "横向分成三个液体区域，中间有膜 a 和膜 b；两侧储液器经泵循环，并通过两个电极连接外电路。",
        )
    }

    fun rotatingCoil(): TutorVisualDocumentScene {
        val threeD = "coil_space_panel"
        val projection = "coil_projection_panel"
        val elements = buildList<TutorVisualDocumentElement> {
            add(
                TutorVisualGeometry3DElement(
                    elementId = "coil_frame",
                    panelId = threeD,
                    kind = TutorVisualGeometry3DKind.POLYLINE,
                    label = "正方形线框",
                    points = listOf(
                        TutorVisualVector3(-1.0, -1.0, 0.0),
                        TutorVisualVector3(1.0, -1.0, 0.0),
                        TutorVisualVector3(1.0, 1.0, 0.0),
                        TutorVisualVector3(-1.0, 1.0, 0.0),
                        TutorVisualVector3(-1.0, -1.0, 0.0),
                    ),
                ),
            )
            add(
                TutorVisualGeometry3DElement(
                    elementId = "coil_axis",
                    panelId = threeD,
                    kind = TutorVisualGeometry3DKind.LINE_SEGMENT,
                    label = "转轴",
                    points = listOf(
                        TutorVisualVector3(-1.6, 0.0, 0.0),
                        TutorVisualVector3(1.6, 0.0, 0.0),
                    ),
                ),
            )
            repeat(5) { index ->
                add(
                    TutorVisualGeometry3DElement(
                        elementId = "coil_field_$index",
                        panelId = threeD,
                        kind = TutorVisualGeometry3DKind.LINE_SEGMENT,
                        label = if (index == 0) "匀强磁场" else null,
                        points = listOf(
                            TutorVisualVector3(-2.0, index * 0.55 - 1.1, -1.0),
                            TutorVisualVector3(2.0, index * 0.55 - 1.1, -1.0),
                        ),
                        layer = TutorVisualLayer.BACKGROUND,
                    ),
                )
            }
            add(node("coil_projection", projection, TutorVisual2DNodeKind.RECTANGLE, "线框投影", 0.45, 0.52, TutorVisualSizeClass.LARGE))
            add(node("coil_projection_axis", projection, TutorVisual2DNodeKind.AXES, "中性面", 0.55, 0.52, TutorVisualSizeClass.LARGE))
            add(
                connector(
                    id = "coil_projection_angle",
                    panelId = projection,
                    kind = TutorVisualConnectorKind.ANGLE,
                    from = "coil_projection",
                    to = "coil_projection",
                    route = TutorVisualRouteKind.DIRECT,
                    label = "转角",
                ),
            )
        }
        val turnAngle = TutorVisualVariable(
            variableId = "coil_animation_angle",
            label = "播放转角",
            value = 360.0,
            dimension = TutorVisualDimension.ANGLE,
            source = TutorVisualValueSource.ILLUSTRATIVE,
            display = false,
        )
        return TutorVisualDocumentScene(
            sceneId = "coil_scene",
            title = "磁场中的转动线框",
            panels = listOf(
                TutorVisualPanel(
                    panelId = threeD,
                    kind = TutorVisualPanelKind.SCENE_3D,
                    camera = TutorVisualCamera(distance = 7.0),
                ),
                TutorVisualPanel(
                    panelId = projection,
                    kind = TutorVisualPanelKind.DIAGRAM_2D,
                    weight = 0.75,
                ),
            ),
            variables = listOf(turnAngle),
            elements = elements,
            bindings = listOf(
                TutorVisualBinding(
                    bindingId = "coil_turn_binding",
                    target = TutorVisualBindingTarget.ELEMENT,
                    targetId = "coil_frame",
                    property = TutorVisualBindingProperty.ROTATION_X_DEGREES,
                    expression = TutorVisualDocumentExpression(
                        operation = TutorVisualDocumentExpressionOperation.MULTIPLY,
                        arguments = listOf(
                            TutorVisualDocumentExpression.variable(turnAngle.variableId),
                            TutorVisualDocumentExpression.timeProgress(),
                        ),
                    ),
                ),
            ),
            steps = listOf(
                TutorVisualStep(
                    stepId = "coil_step_direction",
                    label = "先确定转轴、磁场和转动方向",
                    focusElementIds = listOf("coil_frame", "coil_axis", "coil_field_0"),
                    animationStartSeconds = 0.0,
                    animationEndSeconds = 4.0,
                    primaryRelationElementId = "coil_axis",
                ),
                TutorVisualStep(
                    stepId = "coil_step_projection",
                    label = "再用投影判断有效切割长度",
                    focusElementIds = listOf("coil_projection", "coil_projection_angle"),
                    dimmedElementIds = listOf("coil_projection_axis"),
                    animationStartSeconds = 4.0,
                    animationEndSeconds = 8.0,
                ),
            ),
            durationSeconds = 8.0,
            fallbackMarkdown = "先固定转轴和磁场方向，再从线框的二维投影判断面积与磁通量怎样变化。",
            accessibilitySummary = "主视图是可旋转的正方形线框与平行磁场线，辅助视图同步显示线框投影和转角。",
        )
    }

    fun uTubeGasColumns(): TutorVisualDocumentScene {
        val panelId = "utube_panel"
        val variables = listOf(
            givenLength("utube_c_length", "C 管长度", 30.0),
            givenLength("utube_height_difference", "初始液面高度差", 15.0),
            givenLength("utube_c_height", "C 管液面离底部", 5.0),
            givenLength("utube_a_length", "气体 A 初始长度", 12.5),
            givenLength("utube_b_length", "气体 B 初始长度", 25.0),
        )
        val nodes = listOf(
            node("utube_gas_a", panelId, TutorVisual2DNodeKind.REGION, "气体 A", 0.18, 0.78, TutorVisualSizeClass.WIDE),
            node("utube_piston", panelId, TutorVisual2DNodeKind.PISTON, "活塞", 0.08, 0.78, TutorVisualSizeClass.SMALL),
            node("utube_left_pipe", panelId, TutorVisual2DNodeKind.CONTAINER, "左管", 0.46, 0.48, TutorVisualSizeClass.TALL),
            node("utube_gas_b", panelId, TutorVisual2DNodeKind.REGION, "气体 B", 0.46, 0.22, TutorVisualSizeClass.TALL),
            node("utube_right_pipe", panelId, TutorVisual2DNodeKind.CONTAINER, "右管", 0.74, 0.55, TutorVisualSizeClass.TALL),
            node("utube_pipe_c", panelId, TutorVisual2DNodeKind.CONTAINER, "C 管", 0.90, 0.54, TutorVisualSizeClass.TALL),
            node("utube_left_level", panelId, TutorVisual2DNodeKind.LIQUID_LEVEL, "左侧液面", 0.46, 0.57, TutorVisualSizeClass.SMALL),
            node("utube_right_level", panelId, TutorVisual2DNodeKind.LIQUID_LEVEL, "右侧液面", 0.74, 0.67, TutorVisualSizeClass.SMALL),
        )
        val elements = buildList<TutorVisualDocumentElement> {
            addAll(nodes)
            add(connector("utube_link_a", panelId, TutorVisualConnectorKind.PIPE, "utube_gas_a", "utube_left_pipe"))
            add(connector("utube_link_bottom", panelId, TutorVisualConnectorKind.PIPE, "utube_left_pipe", "utube_right_pipe"))
            add(connector("utube_link_c", panelId, TutorVisualConnectorKind.PIPE, "utube_right_pipe", "utube_pipe_c"))
            add(
                connector(
                    id = "utube_delta_h",
                    panelId = panelId,
                    kind = TutorVisualConnectorKind.DIMENSION,
                    from = "utube_left_level",
                    to = "utube_right_level",
                    route = TutorVisualRouteKind.DIRECT,
                    label = "液面高度差",
                    valueVariableId = "utube_height_difference",
                ),
            )
        }
        return TutorVisualDocumentScene(
            sceneId = "utube_scene",
            title = "活塞与 U 形管",
            panels = listOf(TutorVisualPanel(panelId, TutorVisualPanelKind.DIAGRAM_2D)),
            variables = variables,
            elements = elements,
            bindings = listOf(
                TutorVisualBinding(
                    bindingId = "utube_left_level_binding",
                    target = TutorVisualBindingTarget.ELEMENT,
                    targetId = "utube_left_level",
                    property = TutorVisualBindingProperty.LIQUID_LEVEL,
                    expression = TutorVisualDocumentExpression(
                        operation = TutorVisualDocumentExpressionOperation.LERP,
                        arguments = listOf(
                            TutorVisualDocumentExpression.constant(0.35),
                            TutorVisualDocumentExpression.constant(0.60),
                            TutorVisualDocumentExpression.timeProgress(),
                        ),
                    ),
                ),
                TutorVisualBinding(
                    bindingId = "utube_right_level_binding",
                    target = TutorVisualBindingTarget.ELEMENT,
                    targetId = "utube_right_level",
                    property = TutorVisualBindingProperty.LIQUID_LEVEL,
                    expression = TutorVisualDocumentExpression(
                        operation = TutorVisualDocumentExpressionOperation.LERP,
                        arguments = listOf(
                            TutorVisualDocumentExpression.constant(0.70),
                            TutorVisualDocumentExpression.constant(0.60),
                            TutorVisualDocumentExpression.timeProgress(),
                        ),
                    ),
                ),
            ),
            steps = listOf(
                TutorVisualStep(
                    stepId = "utube_step_initial",
                    label = "先核对初始气柱和液面",
                    focusElementIds = listOf("utube_gas_a", "utube_gas_b", "utube_delta_h"),
                    displayVariableIds = listOf(
                        "utube_a_length",
                        "utube_b_length",
                        "utube_height_difference",
                    ),
                ),
                TutorVisualStep(
                    stepId = "utube_step_balance",
                    label = "推动活塞直到两侧液面相平",
                    focusElementIds = listOf("utube_piston", "utube_left_level", "utube_right_level"),
                    displayVariableIds = listOf("utube_c_length", "utube_c_height"),
                    animationStartSeconds = 0.0,
                    animationEndSeconds = 6.0,
                    primaryRelationElementId = "utube_delta_h",
                ),
            ),
            durationSeconds = 6.0,
            fallbackMarkdown = "分别写出气体 A、气体 B 的初态和末态，再用液面相平这一条件连接两边压强。",
            accessibilitySummary = "水平管内有活塞和气体 A，左侧竖管封闭气体 B，右侧竖管与 C 管连通；两侧水银液面随活塞移动。",
        )
    }

    fun dualAxisChemistryChart(): TutorVisualDocumentScene {
        val panelId = "chemistry_chart_panel"
        val temperature = TutorVisualVariable(
            variableId = "chemistry_temperature",
            label = "温度",
            value = 573.0,
            unit = "K",
            dimension = TutorVisualDimension.TEMPERATURE,
            source = TutorVisualValueSource.GIVEN,
        )
        val selectivity = TutorVisualVariable(
            variableId = "chemistry_selectivity",
            label = "一氧化碳选择性",
            value = 60.0,
            unit = "%",
            source = TutorVisualValueSource.GIVEN,
        )
        val conversion = TutorVisualVariable(
            variableId = "chemistry_conversion",
            label = "乙醇转化率",
            value = 85.0,
            unit = "%",
            source = TutorVisualValueSource.GIVEN,
        )
        return TutorVisualDocumentScene(
            sceneId = "chemistry_chart_scene",
            title = "温度对选择性和转化率的影响",
            panels = listOf(
                TutorVisualPanel(
                    panelId = panelId,
                    kind = TutorVisualPanelKind.SCIENTIFIC_CHART,
                    chart = TutorVisualChartConfiguration(
                        xAxisLabel = "温度 / K",
                        leftAxisLabel = "选择性 / %",
                        rightAxisLabel = "转化率 / %",
                    ),
                ),
            ),
            variables = listOf(temperature, selectivity, conversion),
            elements = listOf(
                TutorVisualChartSeriesElement(
                    elementId = "chemistry_selectivity_series",
                    panelId = panelId,
                    label = "一氧化碳选择性",
                    kind = TutorVisualChartSeriesKind.LINE,
                    points = listOf(TutorVisualChartPoint(573.0, 60.0)),
                    source = TutorVisualValueSource.GIVEN,
                ),
                TutorVisualChartSeriesElement(
                    elementId = "chemistry_conversion_series",
                    panelId = panelId,
                    label = "乙醇转化率",
                    kind = TutorVisualChartSeriesKind.LINE,
                    axis = TutorVisualChartAxis.RIGHT,
                    points = listOf(TutorVisualChartPoint(573.0, 85.0)),
                    source = TutorVisualValueSource.GIVEN,
                ),
                TutorVisualChartAnnotationElement(
                    elementId = "chemistry_temperature_guide",
                    panelId = panelId,
                    kind = TutorVisualChartAnnotationKind.VERTICAL_GUIDE,
                    label = "573 K",
                    xVariableId = temperature.variableId,
                ),
                TutorVisualChartAnnotationElement(
                    elementId = "chemistry_selectivity_marker",
                    panelId = panelId,
                    kind = TutorVisualChartAnnotationKind.MARKER,
                    label = "选择性 60%",
                    xVariableId = temperature.variableId,
                    yVariableId = selectivity.variableId,
                ),
                TutorVisualChartAnnotationElement(
                    elementId = "chemistry_conversion_marker",
                    panelId = panelId,
                    kind = TutorVisualChartAnnotationKind.MARKER,
                    label = "转化率 85%",
                    xVariableId = temperature.variableId,
                    yVariableId = conversion.variableId,
                ),
            ),
            steps = listOf(
                TutorVisualStep(
                    stepId = "chemistry_step_selectivity",
                    label = "先沿温度辅助线读取选择性",
                    focusElementIds = listOf("chemistry_temperature_guide", "chemistry_selectivity_marker"),
                    displayVariableIds = listOf(temperature.variableId, selectivity.variableId),
                    highlightedSeriesIds = listOf("chemistry_selectivity_series"),
                    primaryRelationElementId = "chemistry_selectivity_marker",
                ),
                TutorVisualStep(
                    stepId = "chemistry_step_conversion",
                    label = "再换到右轴读取转化率",
                    focusElementIds = listOf("chemistry_temperature_guide", "chemistry_conversion_marker"),
                    displayVariableIds = listOf(temperature.variableId, conversion.variableId),
                    highlightedSeriesIds = listOf("chemistry_conversion_series"),
                    primaryRelationElementId = "chemistry_conversion_marker",
                ),
            ),
            fallbackMarkdown = "先确认曲线对应哪一侧纵轴，再沿 573 K 的竖线读取交点，不要混用两侧刻度。",
            accessibilitySummary = "双纵轴图表在 573 K 处标出两条曲线的读数：左轴选择性 60%，右轴转化率 85%。",
        )
    }

    private fun node(
        id: String,
        panelId: String,
        kind: TutorVisual2DNodeKind,
        label: String,
        x: Double,
        y: Double,
        size: TutorVisualSizeClass = TutorVisualSizeClass.MEDIUM,
    ) = TutorVisual2DNodeElement(
        elementId = id,
        panelId = panelId,
        kind = kind,
        label = label,
        layout = TutorVisualLayoutHint(preferredX = x, preferredY = y),
        sizeClass = size,
    )

    private fun connector(
        id: String,
        panelId: String,
        kind: TutorVisualConnectorKind,
        from: String,
        to: String,
        route: TutorVisualRouteKind = TutorVisualRouteKind.AUTO_ORTHOGONAL,
        label: String? = null,
        valueVariableId: String? = null,
    ) = TutorVisual2DConnectorElement(
        elementId = id,
        panelId = panelId,
        kind = kind,
        from = TutorVisualConnectionAnchor(from),
        to = TutorVisualConnectionAnchor(to),
        route = route,
        label = label,
        valueVariableId = valueVariableId,
    )

    private fun givenLength(id: String, label: String, value: Double) = TutorVisualVariable(
        variableId = id,
        label = label,
        value = value,
        unit = "cm",
        dimension = TutorVisualDimension.LENGTH,
        source = TutorVisualValueSource.GIVEN,
    )
}
