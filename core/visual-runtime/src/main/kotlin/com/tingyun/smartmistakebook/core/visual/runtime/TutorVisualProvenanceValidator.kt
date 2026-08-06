package com.tingyun.smartmistakebook.core.visual.runtime

import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorVisual2DConnectorElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingTarget
import com.tingyun.smartmistakebook.core.model.TutorVisualChartAnnotationElement
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesElement
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesProof
import com.tingyun.smartmistakebook.core.model.TutorVisualDerivation
import com.tingyun.smartmistakebook.core.model.TutorVisualDerivationNode
import com.tingyun.smartmistakebook.core.model.TutorVisualDerivationOperation
import com.tingyun.smartmistakebook.core.model.TutorVisualDimension
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentElement
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentExpression
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DElement
import com.tingyun.smartmistakebook.core.model.TutorVisualIllustrativePurpose
import com.tingyun.smartmistakebook.core.model.TutorVisualLatticeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualParticleGroupElement
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceFact
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceFactCatalog
import com.tingyun.smartmistakebook.core.model.TutorVisualUnitCatalog
import com.tingyun.smartmistakebook.core.model.TutorVisualValueProof
import com.tingyun.smartmistakebook.core.model.TutorVisualValueSource
import com.tingyun.smartmistakebook.core.model.TutorVisualVariable
import com.tingyun.smartmistakebook.core.model.tutorVisualNumbersEqual
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class TutorVisualProvenanceContext(
    val questionDocument: QuestionDocument,
    val sourceAssets: List<CaptureSourceAssetRef>,
    val sourceFacts: List<TutorVisualSourceFact>,
)

data class TutorVisualProvenancePolicy(
    /** True only after the chart renderer can hide all synthetic axis values and touch readouts. */
    val supportsSafeIllustrativeTrends: Boolean = false,
)

enum class TutorVisualProvenanceIssueCode {
    MISSING_PROVENANCE_ENVELOPE,
    INVALID_SOURCE_FACT_CATALOG,
    MISSING_VALUE_PROOF,
    SOURCE_FACT_MISMATCH,
    INVALID_DERIVATION,
    DERIVATION_CYCLE,
    DERIVATION_VALUE_MISMATCH,
    DERIVATION_DIMENSION_MISMATCH,
    UNSAFE_CHART_DATA,
    UNSAFE_VISIBLE_NUMBER,
}

data class TutorVisualProvenanceIssue(
    val code: TutorVisualProvenanceIssueCode,
    val targetId: String,
    val detail: String,
)

data class TutorVisualProvenanceReport(
    val issues: List<TutorVisualProvenanceIssue>,
    val verifiedVariableIds: Set<String>,
    /** Only these ids may be used by a later visual-target evidence boundary. */
    val evidenceEligibleElementIds: Set<String>,
) {
    val canPresent: Boolean = issues.isEmpty()
}

/**
 * Validates semantic numeric authority. Structural rendering remains in [TutorVisualDocumentCompiler];
 * production presentation must pass both gates.
 */
object TutorVisualProvenanceValidator {
    fun validate(
        scene: TutorVisualDocumentScene,
        context: TutorVisualProvenanceContext,
        policy: TutorVisualProvenancePolicy = TutorVisualProvenancePolicy(),
    ): TutorVisualProvenanceReport {
        val issues = mutableListOf<TutorVisualProvenanceIssue>()
        if (
            scene.provenanceSchemaVersion !=
            TutorVisualDocumentScene.CURRENT_PROVENANCE_SCHEMA_VERSION
        ) {
            issues += issue(
                TutorVisualProvenanceIssueCode.MISSING_PROVENANCE_ENVELOPE,
                scene.sceneId,
                "The scene predates locally verifiable numeric provenance.",
            )
        }
        if (
            runCatching {
                TutorVisualSourceFactCatalog.requireValid(
                    context.questionDocument,
                    context.sourceAssets,
                    context.sourceFacts,
                )
            }.isFailure
        ) {
            issues += issue(
                TutorVisualProvenanceIssueCode.INVALID_SOURCE_FACT_CATALOG,
                scene.sceneId,
                "The local source-fact catalog no longer matches the committed question.",
            )
        }

        val resolver = ProvenVariableResolver(
            scene = scene,
            sourceFacts = context.sourceFacts.associateBy(TutorVisualSourceFact::factId),
            issues = issues,
        )
        scene.variables
            .filter { it.source != TutorVisualValueSource.ILLUSTRATIVE }
            .forEach { resolver.resolve(it.variableId) }
        scene.variables
            .filter { it.source == TutorVisualValueSource.ILLUSTRATIVE }
            .forEach { variable ->
                val proof = variable.proof as? TutorVisualValueProof.Illustrative
                if (proof?.purpose != TutorVisualIllustrativePurpose.ANIMATION_ONLY) {
                    issues += issue(
                        TutorVisualProvenanceIssueCode.MISSING_VALUE_PROOF,
                        variable.variableId,
                        "An illustrative variable must be explicitly limited to animation control.",
                    )
                }
            }

        scene.elements.filterIsInstance<TutorVisualChartSeriesElement>().forEach { series ->
            validateChartSeries(
                scene = scene,
                series = series,
                resolver = resolver,
                policy = policy,
                issues = issues,
            )
        }
        validateVisibleNumbers(scene, issues)

        val illustrativeVariableIds = scene.variables
            .filterTo(hashSetOf()) { it.source == TutorVisualValueSource.ILLUSTRATIVE }
            .mapTo(hashSetOf(), TutorVisualVariable::variableId)
        val illustrativePanelIds = scene.bindings
            .asSequence()
            .filter { it.target == TutorVisualBindingTarget.PANEL }
            .filter { binding ->
                binding.expression.provenanceVariableIds().any(illustrativeVariableIds::contains)
            }
            .mapTo(hashSetOf()) { it.targetId }
        val evidenceEligible = if (issues.isEmpty()) {
            scene.elements
                .asSequence()
                .filterNot { it.panelId in illustrativePanelIds }
                .filterNot { element ->
                    element is TutorVisualChartSeriesElement &&
                        element.proof is TutorVisualChartSeriesProof.IllustrativeTrend
                }
                .filter { element -> element.directVariableIds().none(illustrativeVariableIds::contains) }
                .filter { element ->
                    scene.bindings
                        .asSequence()
                        .filter {
                            it.target == TutorVisualBindingTarget.ELEMENT &&
                                it.targetId == element.elementId
                        }
                        .flatMap { it.expression.provenanceVariableIds().asSequence() }
                        .none(illustrativeVariableIds::contains)
                }
                .mapTo(hashSetOf(), TutorVisualDocumentElement::elementId)
        } else {
            emptySet()
        }

        return TutorVisualProvenanceReport(
            issues = issues.distinct(),
            verifiedVariableIds = resolver.verifiedScalars.keys,
            evidenceEligibleElementIds = evidenceEligible,
        )
    }
}

private class ProvenVariableResolver(
    scene: TutorVisualDocumentScene,
    private val sourceFacts: Map<String, TutorVisualSourceFact>,
    private val issues: MutableList<TutorVisualProvenanceIssue>,
) {
    private val variables = scene.variables.associateBy(TutorVisualVariable::variableId)
    private val states = mutableMapOf<String, ResolutionState>()
    val verifiedScalars = linkedMapOf<String, ProvenScalar>()

    fun resolve(variableId: String): ProvenScalar? {
        verifiedScalars[variableId]?.let { return it }
        val variable = variables[variableId] ?: return null
        if (states[variableId] == ResolutionState.VISITING) {
            issues += issue(
                TutorVisualProvenanceIssueCode.DERIVATION_CYCLE,
                variableId,
                "The derived value depends on itself.",
            )
            states[variableId] = ResolutionState.FAILED
            return null
        }
        if (states[variableId] == ResolutionState.FAILED) return null
        states[variableId] = ResolutionState.VISITING
        val resolved = when (variable.source) {
            TutorVisualValueSource.GIVEN -> resolveGiven(variable)
            TutorVisualValueSource.DERIVED -> resolveDerived(variable)
            TutorVisualValueSource.ILLUSTRATIVE -> null
        }
        states[variableId] = if (resolved == null) ResolutionState.FAILED else ResolutionState.RESOLVED
        if (resolved != null) verifiedScalars[variableId] = resolved
        return resolved
    }

    fun evaluateCurve(
        derivation: TutorVisualDerivation,
        parameter: ProvenScalar,
        targetId: String,
    ): ProvenScalar? = DerivationEvaluator(
        derivation = derivation,
        variableResolver = ::resolve,
        parameter = parameter,
        targetId = targetId,
        issues = issues,
    ).evaluate()

    private fun resolveGiven(variable: TutorVisualVariable): ProvenScalar? {
        val proof = variable.proof as? TutorVisualValueProof.Given
        if (proof == null) {
            issues += issue(
                TutorVisualProvenanceIssueCode.MISSING_VALUE_PROOF,
                variable.variableId,
                "A visible given value has no local source-fact reference.",
            )
            return null
        }
        val fact = sourceFacts[proof.sourceFactId]
        if (
            fact == null ||
            !tutorVisualNumbersEqual(variable.value, fact.value) ||
            variable.unit != fact.unit ||
            variable.dimension != fact.dimension
        ) {
            issues += issue(
                TutorVisualProvenanceIssueCode.SOURCE_FACT_MISMATCH,
                variable.variableId,
                "The given value does not exactly match its local source fact.",
            )
            return null
        }
        return ProvenScalar.fromDisplay(
            displayValue = variable.value,
            dimension = variable.dimension,
            unit = variable.unit,
        )
    }

    private fun resolveDerived(variable: TutorVisualVariable): ProvenScalar? {
        val proof = variable.proof as? TutorVisualValueProof.Derived
        if (proof == null) {
            issues += issue(
                TutorVisualProvenanceIssueCode.MISSING_VALUE_PROOF,
                variable.variableId,
                "A visible derived value has no executable derivation.",
            )
            return null
        }
        val evaluated = DerivationEvaluator(
            derivation = proof.derivation,
            variableResolver = ::resolve,
            parameter = null,
            targetId = variable.variableId,
            issues = issues,
        ).evaluate() ?: return null
        if (evaluated.dimension != variable.dimension) {
            issues += issue(
                TutorVisualProvenanceIssueCode.DERIVATION_DIMENSION_MISMATCH,
                variable.variableId,
                "The derivation result has a different physical dimension.",
            )
            return null
        }
        val expectedCanonicalValue = canonicalValue(
            displayValue = variable.value,
            unit = variable.unit,
            dimension = variable.dimension,
        )
        if (
            expectedCanonicalValue == null ||
            !tutorVisualNumbersEqual(evaluated.requireCanonicalValue(), expectedCanonicalValue)
        ) {
            issues += issue(
                TutorVisualProvenanceIssueCode.DERIVATION_VALUE_MISMATCH,
                variable.variableId,
                "The displayed value and unit cannot be reproduced from its derivation.",
            )
            return null
        }
        return ProvenScalar.fromDisplay(
            displayValue = variable.value,
            dimension = variable.dimension,
            unit = variable.unit,
        )
    }
}

private class DerivationEvaluator(
    derivation: TutorVisualDerivation,
    private val variableResolver: (String) -> ProvenScalar?,
    private val parameter: ProvenScalar?,
    private val targetId: String,
    private val issues: MutableList<TutorVisualProvenanceIssue>,
) {
    private val nodes = derivation.nodes.associateBy(TutorVisualDerivationNode::nodeId)
    private val rootNodeId = derivation.rootNodeId
    private val values = mutableMapOf<String, ProvenScalar>()
    private val visiting = hashSetOf<String>()

    fun evaluate(): ProvenScalar? = evaluate(rootNodeId)

    private fun evaluate(nodeId: String): ProvenScalar? {
        values[nodeId]?.let { return it }
        val node = nodes[nodeId]
        if (node == null) {
            invalid("The derivation references an unknown node.")
            return null
        }
        if (!visiting.add(nodeId)) {
            issues += issue(
                TutorVisualProvenanceIssueCode.DERIVATION_CYCLE,
                targetId,
                "The derivation graph contains a cycle.",
            )
            return null
        }
        val inputs = node.inputNodeIds.map { inputId ->
            evaluate(inputId) ?: return finish(nodeId, null)
        }
        if (inputs.any { it.canonicalValue == null }) {
            return finish(
                nodeId,
                invalid("The derivation uses a value whose unit cannot be normalized locally."),
            )
        }
        val result = when (node.operation) {
            TutorVisualDerivationOperation.VARIABLE -> variableResolver(requireNotNull(node.variableId))
            TutorVisualDerivationOperation.PARAMETER -> parameter ?: invalid(
                "A scalar derivation cannot use a chart parameter.",
            )
            TutorVisualDerivationOperation.ZERO -> ProvenScalar.calculated(0.0, TutorVisualDimension.DIMENSIONLESS)
            TutorVisualDerivationOperation.ONE -> ProvenScalar.calculated(1.0, TutorVisualDimension.DIMENSIONLESS)
            TutorVisualDerivationOperation.TWO -> ProvenScalar.calculated(2.0, TutorVisualDimension.DIMENSIONLESS)
            TutorVisualDerivationOperation.PI -> ProvenScalar.calculated(PI, TutorVisualDimension.DIMENSIONLESS)
            TutorVisualDerivationOperation.ADD,
            TutorVisualDerivationOperation.SUBTRACT,
            TutorVisualDerivationOperation.MIN,
            TutorVisualDerivationOperation.MAX,
            -> sameDimensionBinary(node.operation, inputs)
            TutorVisualDerivationOperation.MULTIPLY -> multiply(inputs[0], inputs[1])
            TutorVisualDerivationOperation.DIVIDE -> divide(inputs[0], inputs[1])
            TutorVisualDerivationOperation.NEGATE -> inputs[0].mapCanonical { -it }
            TutorVisualDerivationOperation.ABS -> inputs[0].mapCanonical(::abs)
            TutorVisualDerivationOperation.SIN,
            TutorVisualDerivationOperation.COS,
            -> trigonometric(node.operation, inputs[0])
            TutorVisualDerivationOperation.SQRT -> squareRoot(inputs[0])
            TutorVisualDerivationOperation.CLAMP -> clamp(inputs)
            TutorVisualDerivationOperation.LERP -> lerp(inputs)
        }?.takeIf {
            val value = it.canonicalValue
            value != null && value.isFinite() && abs(value) <= MAX_ABS_DERIVED_VALUE
        }
            ?: invalid("The derivation produced an invalid or unbounded value.")
        return finish(nodeId, result)
    }

    private fun finish(nodeId: String, value: ProvenScalar?): ProvenScalar? {
        visiting -= nodeId
        if (value != null) values[nodeId] = value
        return value
    }

    private fun sameDimensionBinary(
        operation: TutorVisualDerivationOperation,
        inputs: List<ProvenScalar>,
    ): ProvenScalar? {
        if (inputs[0].dimension != inputs[1].dimension) {
            return invalid("Addition, subtraction, minimum, and maximum require matching dimensions.")
        }
        val left = inputs[0].requireCanonicalValue()
        val right = inputs[1].requireCanonicalValue()
        val value = when (operation) {
            TutorVisualDerivationOperation.ADD -> left + right
            TutorVisualDerivationOperation.SUBTRACT -> left - right
            TutorVisualDerivationOperation.MIN -> min(left, right)
            TutorVisualDerivationOperation.MAX -> max(left, right)
            else -> error("Unsupported binary derivation operation")
        }
        return ProvenScalar.calculated(value, inputs[0].dimension)
    }

    private fun multiply(left: ProvenScalar, right: ProvenScalar): ProvenScalar? =
        dimensionOf(left.dimension).plus(dimensionOf(right.dimension)).toScalar(
            left.requireCanonicalValue() * right.requireCanonicalValue(),
        )
            ?: invalid("The multiplied dimensions cannot be represented safely.")

    private fun divide(left: ProvenScalar, right: ProvenScalar): ProvenScalar? {
        val divisor = right.requireCanonicalValue()
        if (abs(divisor) < MIN_DIVISOR) return invalid("The derivation divides by zero.")
        return dimensionOf(left.dimension).minus(dimensionOf(right.dimension))
            .toScalar(left.requireCanonicalValue() / divisor)
            ?: invalid("The divided dimensions cannot be represented safely.")
    }

    private fun trigonometric(
        operation: TutorVisualDerivationOperation,
        input: ProvenScalar,
    ): ProvenScalar? {
        if (input.dimension !in setOf(TutorVisualDimension.DIMENSIONLESS, TutorVisualDimension.ANGLE)) {
            return invalid("Trigonometric derivations require an angle or dimensionless value.")
        }
        val canonicalInput = input.requireCanonicalValue()
        val value = if (operation == TutorVisualDerivationOperation.SIN) sin(canonicalInput) else cos(canonicalInput)
        return ProvenScalar.calculated(value, TutorVisualDimension.DIMENSIONLESS)
    }

    private fun squareRoot(input: ProvenScalar): ProvenScalar? {
        val canonicalInput = input.requireCanonicalValue()
        if (canonicalInput < 0.0) return invalid("The derivation takes a square root of a negative value.")
        val dimension = dimensionOf(input.dimension).squareRoot()
            ?: return invalid("The square-root dimension is not exact.")
        return dimension.toScalar(sqrt(canonicalInput))
            ?: invalid("The square-root dimension cannot be represented safely.")
    }

    private fun clamp(inputs: List<ProvenScalar>): ProvenScalar? {
        if (inputs.map(ProvenScalar::dimension).distinct().size != 1) {
            return invalid("Clamp bounds require matching dimensions.")
        }
        val value = inputs[0].requireCanonicalValue()
        val firstBound = inputs[1].requireCanonicalValue()
        val secondBound = inputs[2].requireCanonicalValue()
        return ProvenScalar.calculated(
            value.coerceIn(min(firstBound, secondBound), max(firstBound, secondBound)),
            inputs[0].dimension,
        )
    }

    private fun lerp(inputs: List<ProvenScalar>): ProvenScalar? {
        if (
            inputs[0].dimension != inputs[1].dimension ||
            inputs[2].dimension != TutorVisualDimension.DIMENSIONLESS
        ) {
            return invalid("Interpolation requires matching endpoints and dimensionless progress.")
        }
        val start = inputs[0].requireCanonicalValue()
        val end = inputs[1].requireCanonicalValue()
        val progress = inputs[2].requireCanonicalValue()
        return ProvenScalar.calculated(
            start + (end - start) * progress,
            inputs[0].dimension,
        )
    }

    private fun invalid(detail: String): ProvenScalar? {
        issues += issue(TutorVisualProvenanceIssueCode.INVALID_DERIVATION, targetId, detail)
        return null
    }

    companion object {
        private const val MIN_DIVISOR = 1e-12
        private const val MAX_ABS_DERIVED_VALUE = 1e15
    }
}

private fun validateChartSeries(
    scene: TutorVisualDocumentScene,
    series: TutorVisualChartSeriesElement,
    resolver: ProvenVariableResolver,
    policy: TutorVisualProvenancePolicy,
    issues: MutableList<TutorVisualProvenanceIssue>,
) {
    when (val proof = series.proof) {
        null -> issues += issue(
            TutorVisualProvenanceIssueCode.MISSING_VALUE_PROOF,
            series.elementId,
            "A chart series has no locally verifiable data proof.",
        )
        is TutorVisualChartSeriesProof.ProvenPoints -> {
            if (proof.points.size != series.points.size) {
                issues += unsafeChart(series, "The chart proof does not cover every plotted point.")
                return
            }
            proof.points.zip(series.points).forEach { (pointProof, point) ->
                val x = resolver.resolve(pointProof.xVariableId)
                val y = resolver.resolve(pointProof.yVariableId)
                if (
                    x == null || y == null ||
                    !tutorVisualNumbersEqual(x.displayValue, point.x) ||
                    !tutorVisualNumbersEqual(y.displayValue, point.y)
                ) {
                    issues += unsafeChart(series, "A plotted point cannot be reproduced from verified variables.")
                }
            }
        }
        is TutorVisualChartSeriesProof.DerivedCurve -> {
            val start = resolver.resolve(proof.xStartVariableId)
            val end = resolver.resolve(proof.xEndVariableId)
            if (
                start == null || end == null ||
                start.dimension != end.dimension || start.unit != end.unit ||
                start.canonicalValue == null || end.canonicalValue == null
            ) {
                issues += unsafeChart(series, "The derived curve range is not proven.")
                return
            }
            val yUnitScale = canonicalScale(proof.yUnit, proof.yDimension)
            if (yUnitScale == null) {
                issues += unsafeChart(series, "The derived curve display unit is not locally supported.")
                return
            }
            if (proof.sampleCount != series.points.size) {
                issues += unsafeChart(series, "The derived curve sample count does not match its points.")
                return
            }
            series.points.forEachIndexed { index, point ->
                val fraction = index.toDouble() / (proof.sampleCount - 1).toDouble()
                val expectedX = start.displayValue + (end.displayValue - start.displayValue) * fraction
                val expectedY = resolver.evaluateCurve(
                    derivation = proof.yDerivation,
                    parameter = ProvenScalar.fromDisplay(expectedX, start.dimension, start.unit),
                    targetId = series.elementId,
                )
                val expectedYDisplay = expectedY?.canonicalValue?.div(yUnitScale)
                if (
                    expectedY == null || expectedY.dimension != proof.yDimension ||
                    expectedYDisplay == null ||
                    !tutorVisualNumbersEqual(point.x, expectedX) ||
                    !tutorVisualNumbersEqual(point.y, expectedYDisplay)
                ) {
                    issues += unsafeChart(series, "A derived curve point does not match local evaluation.")
                }
            }
        }
        is TutorVisualChartSeriesProof.IllustrativeTrend -> {
            val chart = scene.panels.single { it.panelId == series.panelId }.chart
            if (chart?.allowTouchReadout != false || !policy.supportsSafeIllustrativeTrends) {
                issues += unsafeChart(
                    series,
                    "An illustrative trend requires a renderer that hides synthetic numeric scales and readouts.",
                )
            }
        }
    }
}

private fun validateVisibleNumbers(
    scene: TutorVisualDocumentScene,
    issues: MutableList<TutorVisualProvenanceIssue>,
) {
    scene.visibleModelText().forEach { (targetId, text) ->
        if (semanticNumericValues(text).any()) {
            issues += issue(
                TutorVisualProvenanceIssueCode.UNSAFE_VISIBLE_NUMBER,
                targetId,
                "Visible free text contains a semantic number; render it through a proven variable instead.",
            )
        }
    }
}

private fun TutorVisualDocumentScene.visibleModelText(): List<Pair<String, String>> = buildList {
    add(sceneId to title)
    add(sceneId to fallbackMarkdown)
    add(sceneId to accessibilitySummary)
    panels.forEach { panel ->
        panel.title?.let { add(panel.panelId to it) }
        panel.chart?.let { chart ->
            add(panel.panelId to chart.xAxisLabel)
            add(panel.panelId to chart.leftAxisLabel)
            chart.rightAxisLabel?.let { add(panel.panelId to it) }
        }
    }
    variables.forEach { variable ->
        add(variable.variableId to variable.label)
        variable.derivationMarkdown?.let { add(variable.variableId to it) }
    }
    elements.forEach { element ->
        element.visibleTextParts().forEach { add(element.elementId to it) }
    }
    steps.forEach { add(it.stepId to it.label) }
}

private fun TutorVisualDocumentElement.visibleTextParts(): List<String> = when (this) {
    is TutorVisual2DNodeElement -> listOfNotNull(label, accessibilityLabel)
    is TutorVisual2DConnectorElement -> listOfNotNull(label, accessibilityLabel)
    is TutorVisualParticleGroupElement -> listOfNotNull(label, accessibilityLabel)
    is TutorVisualGeometry3DElement -> listOfNotNull(label, accessibilityLabel)
    is TutorVisualLatticeElement -> listOfNotNull(label, accessibilityLabel) + basis.map { it.label }
    is TutorVisualChartSeriesElement -> listOfNotNull(label, accessibilityLabel)
    is TutorVisualChartAnnotationElement -> listOfNotNull(label, accessibilityLabel)
}

private fun TutorVisualDocumentElement.directVariableIds(): List<String> = when (this) {
    is TutorVisual2DNodeElement -> listOfNotNull(valueVariableId)
    is TutorVisual2DConnectorElement -> listOfNotNull(valueVariableId)
    is TutorVisualChartAnnotationElement -> listOfNotNull(xVariableId, yVariableId, endXVariableId)
    else -> emptyList()
}

private fun TutorVisualDocumentExpression.provenanceVariableIds(): Set<String> =
    listOfNotNull(variableId).toSet() + arguments.flatMap(TutorVisualDocumentExpression::provenanceVariableIds)

private fun semanticNumericValues(text: String): Sequence<Double> =
    VISIBLE_NUMERIC_LITERAL.findAll(text).mapNotNull { match ->
        val compact = match.value.replace(" ", "").replace('−', '-').replace('–', '-')
        when {
            compact == "π" || compact == "+π" -> PI
            compact == "-π" -> -PI
            '/' in compact -> {
                val parts = compact.split('/', limit = 2)
                val numerator = parts[0].toDoubleOrNull()
                val denominator = parts[1].toDoubleOrNull()
                if (numerator == null || denominator == null || abs(denominator) < 1e-12) null
                else numerator / denominator
            }
            else -> compact.toDoubleOrNull()
        }
    }

private fun unsafeChart(
    series: TutorVisualChartSeriesElement,
    detail: String,
): TutorVisualProvenanceIssue = issue(
    TutorVisualProvenanceIssueCode.UNSAFE_CHART_DATA,
    series.elementId,
    detail,
)

private fun issue(
    code: TutorVisualProvenanceIssueCode,
    targetId: String,
    detail: String,
) = TutorVisualProvenanceIssue(code, targetId, detail)

private data class ProvenScalar(
    val displayValue: Double,
    val dimension: TutorVisualDimension,
    val unit: String?,
    val canonicalValue: Double?,
) {
    fun requireCanonicalValue(): Double = checkNotNull(canonicalValue)

    fun mapCanonical(transform: (Double) -> Double): ProvenScalar? = canonicalValue?.let { value ->
        calculated(transform(value), dimension)
    }

    companion object {
        fun fromDisplay(
            displayValue: Double,
            dimension: TutorVisualDimension,
            unit: String?,
        ) = ProvenScalar(
            displayValue = displayValue,
            dimension = dimension,
            unit = unit,
            canonicalValue = canonicalValue(displayValue, unit, dimension),
        )

        fun calculated(
            canonicalValue: Double,
            dimension: TutorVisualDimension,
        ) = ProvenScalar(
            displayValue = canonicalValue,
            dimension = dimension,
            unit = canonicalUnit(dimension),
            canonicalValue = canonicalValue,
        )
    }
}

private enum class ResolutionState {
    VISITING,
    RESOLVED,
    FAILED,
}

private data class DimensionVector(
    val length: Int = 0,
    val time: Int = 0,
    val mass: Int = 0,
    val current: Int = 0,
    val temperature: Int = 0,
    val amount: Int = 0,
) {
    fun plus(other: DimensionVector) = DimensionVector(
        length + other.length,
        time + other.time,
        mass + other.mass,
        current + other.current,
        temperature + other.temperature,
        amount + other.amount,
    )

    fun minus(other: DimensionVector) = DimensionVector(
        length - other.length,
        time - other.time,
        mass - other.mass,
        current - other.current,
        temperature - other.temperature,
        amount - other.amount,
    )

    fun squareRoot(): DimensionVector? {
        val values = listOf(length, time, mass, current, temperature, amount)
        if (values.any { it % 2 != 0 }) return null
        return DimensionVector(length / 2, time / 2, mass / 2, current / 2, temperature / 2, amount / 2)
    }

    fun toScalar(value: Double): ProvenScalar? = vectorDimensions[this]?.let { dimension ->
        ProvenScalar.calculated(value, dimension)
    }
}

private fun dimensionOf(dimension: TutorVisualDimension): DimensionVector =
    dimensionVectors[dimension] ?: UNKNOWN_DIMENSION

private val dimensionVectors = mapOf(
    TutorVisualDimension.DIMENSIONLESS to DimensionVector(),
    TutorVisualDimension.ANGLE to DimensionVector(),
    TutorVisualDimension.LENGTH to DimensionVector(length = 1),
    TutorVisualDimension.AREA to DimensionVector(length = 2),
    TutorVisualDimension.VOLUME to DimensionVector(length = 3),
    TutorVisualDimension.TIME to DimensionVector(time = 1),
    TutorVisualDimension.FREQUENCY to DimensionVector(time = -1),
    TutorVisualDimension.MASS to DimensionVector(mass = 1),
    TutorVisualDimension.ELECTRIC_CURRENT to DimensionVector(current = 1),
    TutorVisualDimension.TEMPERATURE to DimensionVector(temperature = 1),
    TutorVisualDimension.AMOUNT_OF_SUBSTANCE to DimensionVector(amount = 1),
    TutorVisualDimension.SPEED to DimensionVector(length = 1, time = -1),
    TutorVisualDimension.ACCELERATION to DimensionVector(length = 1, time = -2),
    TutorVisualDimension.FORCE to DimensionVector(length = 1, time = -2, mass = 1),
    TutorVisualDimension.ENERGY to DimensionVector(length = 2, time = -2, mass = 1),
    TutorVisualDimension.POWER to DimensionVector(length = 2, time = -3, mass = 1),
    TutorVisualDimension.PRESSURE to DimensionVector(length = -1, time = -2, mass = 1),
    TutorVisualDimension.VOLTAGE to DimensionVector(length = 2, time = -3, mass = 1, current = -1),
    TutorVisualDimension.RESISTANCE to DimensionVector(length = 2, time = -3, mass = 1, current = -2),
    TutorVisualDimension.CHARGE to DimensionVector(time = 1, current = 1),
    TutorVisualDimension.CONCENTRATION to DimensionVector(length = -3, amount = 1),
)

private val vectorDimensions = dimensionVectors.entries
    .filterNot { it.key == TutorVisualDimension.ANGLE }
    .associate { (dimension, vector) -> vector to dimension }
private val UNKNOWN_DIMENSION = DimensionVector(99, 99, 99, 99, 99, 99)

private fun canonicalValue(
    displayValue: Double,
    unit: String?,
    dimension: TutorVisualDimension,
): Double? = canonicalScale(unit, dimension)?.let { displayValue * it }

private fun canonicalScale(
    unit: String?,
    dimension: TutorVisualDimension,
): Double? = TutorVisualUnitCatalog.resolve(unit, dimension)?.canonicalScale

private fun canonicalUnit(dimension: TutorVisualDimension): String? = when (dimension) {
    TutorVisualDimension.DIMENSIONLESS -> null
    TutorVisualDimension.LENGTH -> "m"
    TutorVisualDimension.TIME -> "s"
    TutorVisualDimension.MASS -> "kg"
    TutorVisualDimension.ELECTRIC_CURRENT -> "A"
    TutorVisualDimension.TEMPERATURE -> "K"
    TutorVisualDimension.AMOUNT_OF_SUBSTANCE -> "mol"
    TutorVisualDimension.ANGLE -> "rad"
    TutorVisualDimension.AREA -> "m²"
    TutorVisualDimension.VOLUME -> "m³"
    TutorVisualDimension.SPEED -> "m/s"
    TutorVisualDimension.ACCELERATION -> "m/s²"
    TutorVisualDimension.FORCE -> "N"
    TutorVisualDimension.ENERGY -> "J"
    TutorVisualDimension.POWER -> "W"
    TutorVisualDimension.PRESSURE -> "Pa"
    TutorVisualDimension.VOLTAGE -> "V"
    TutorVisualDimension.RESISTANCE -> "Ω"
    TutorVisualDimension.CHARGE -> "C"
    TutorVisualDimension.CONCENTRATION -> "mol/m³"
    TutorVisualDimension.FREQUENCY -> "Hz"
    TutorVisualDimension.OTHER -> null
}

private val VISIBLE_NUMERIC_LITERAL = Regex(
    pattern = "(?<![A-Za-z_])[-+−–]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?(?:\\s*/\\s*[-+−–]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))?|[-+−–]?π",
)
