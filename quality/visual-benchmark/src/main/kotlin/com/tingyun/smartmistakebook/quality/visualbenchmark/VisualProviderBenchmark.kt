package com.tingyun.smartmistakebook.quality.visualbenchmark

import com.tingyun.smartmistakebook.core.visual.runtime.CompiledTutorVisualDocument
import com.tingyun.smartmistakebook.core.visual.runtime.containsVisibleIllustrativeValue

enum class VisualBenchmarkCaseOutcome {
    PASS,
    SAFE_FALLBACK,
    INCORRECT,
    RUNTIME_REJECTED,
    PROVIDER_FAILURE,
}

data class VisualBenchmarkCaseScore(
    val caseId: String,
    val outcome: VisualBenchmarkCaseOutcome,
    val repaired: Boolean,
    val reasons: List<String>,
)

data class VisualBenchmarkProviderReport(
    val providerId: String,
    val modelVersion: String,
    val totalCases: Int,
    val firstPassCases: Int,
    val passAfterAtMostOneRepairCases: Int,
    val safeFallbackCases: Int,
    val caseScores: List<VisualBenchmarkCaseScore>,
) {
    val firstPassRate: Double =
        if (totalCases == 0) 0.0 else firstPassCases.toDouble() / totalCases
    val passAfterAtMostOneRepairRate: Double =
        if (totalCases == 0) 0.0 else passAfterAtMostOneRepairCases.toDouble() / totalCases
    val safeHandlingRate: Double =
        if (totalCases == 0) 0.0 else
            (passAfterAtMostOneRepairCases + safeFallbackCases).toDouble() / totalCases
}

data class VisualBenchmarkGateReport(
    val providerReports: List<VisualBenchmarkProviderReport>,
    val issues: List<String>,
) {
    val passes: Boolean = issues.isEmpty()

    fun requirePassed() {
        require(passes) {
            issues.joinToString(prefix = "Visual provider benchmark failed:\n", separator = "\n") { "- $it" }
        }
    }
}

object VisualBenchmarkAttemptFactory {
    fun generated(
        stage: VisualBenchmarkAttemptStage,
        compiled: CompiledTutorVisualDocument,
        observation: VisualBenchmarkObservation,
    ): VisualBenchmarkAttempt {
        val provenanceVerified = compiled.provenance?.canPresent == true
        return VisualBenchmarkAttempt(
            stage = stage,
            status = VisualBenchmarkAttemptStatus.GENERATED,
            runtimeCompiled = compiled.integrity.canRender,
            provenanceVerified = provenanceVerified,
            visibleIllustrativeValue = compiled.scene.containsVisibleIllustrativeValue(),
            observation = observation,
            failureReason = buildList {
                compiled.integrity.issues.mapTo(this) { it.code.name }
                compiled.provenance?.issues.orEmpty().mapTo(this) { it.code.name }
            }.distinct().takeIf { it.isNotEmpty() }?.joinToString(),
        )
    }

    fun declined(stage: VisualBenchmarkAttemptStage): VisualBenchmarkAttempt =
        VisualBenchmarkAttempt(
            stage = stage,
            status = VisualBenchmarkAttemptStatus.DECLINED_UNCERTAIN,
            runtimeCompiled = false,
            provenanceVerified = false,
            visibleIllustrativeValue = false,
        )

    fun failed(
        stage: VisualBenchmarkAttemptStage,
        reason: String,
    ): VisualBenchmarkAttempt = VisualBenchmarkAttempt(
        stage = stage,
        status = VisualBenchmarkAttemptStatus.FAILED,
        runtimeCompiled = false,
        provenanceVerified = false,
        visibleIllustrativeValue = false,
        failureReason = reason,
    )
}

object VisualProviderBenchmarkScorer {
    fun evaluate(
        manifest: VisualBenchmarkManifest,
        runs: List<VisualBenchmarkProviderRun>,
        requiredProviders: Int = 2,
        requiredComplianceRate: Double = 0.95,
    ): VisualBenchmarkGateReport {
        val manifestCases = manifest.cases.associateBy { it.caseId }
        val issues = mutableListOf<String>()
        if (runs.map { it.providerId }.distinct().size < requiredProviders) {
            issues += "Requires $requiredProviders distinct providers."
        }
        if (runs.size != runs.map { it.providerId }.distinct().size) {
            issues += "Provider ids must be unique."
        }
        val reports = runs.map { run ->
            scoreProvider(manifestCases, run, issues)
        }
        reports.forEach { report ->
            if (report.passAfterAtMostOneRepairRate < requiredComplianceRate) {
                issues +=
                    "${report.providerId} compliance after at most one repair is " +
                    "${"%.2f".format(report.passAfterAtMostOneRepairRate * 100)}%, " +
                    "below ${"%.2f".format(requiredComplianceRate * 100)}%."
            }
        }
        return VisualBenchmarkGateReport(reports, issues)
    }

    private fun scoreProvider(
        manifestCases: Map<String, VisualBenchmarkCase>,
        run: VisualBenchmarkProviderRun,
        gateIssues: MutableList<String>,
    ): VisualBenchmarkProviderReport {
        val duplicateIds = run.cases.groupingBy { it.caseId }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            gateIssues += "${run.providerId} contains duplicate case results: ${duplicateIds.sorted()}."
        }
        val runCases = run.cases.associateBy { it.caseId }
        val unknownIds = runCases.keys - manifestCases.keys
        if (unknownIds.isNotEmpty()) {
            gateIssues += "${run.providerId} contains unknown cases: ${unknownIds.sorted()}."
        }
        run.cases.forEach { caseRun ->
            val sequenceIssues = validateAttemptSequence(caseRun.attempts)
            if (sequenceIssues.isNotEmpty()) {
                gateIssues +=
                    "${run.providerId}/${caseRun.caseId} violates the one-repair policy: " +
                    sequenceIssues.joinToString()
            }
        }
        val scores = manifestCases.values.map { benchmarkCase ->
            scoreCase(benchmarkCase, runCases[benchmarkCase.caseId])
        }
        return VisualBenchmarkProviderReport(
            providerId = run.providerId,
            modelVersion = run.modelVersion,
            totalCases = scores.size,
            firstPassCases = scores.count { !it.repaired && it.outcome == VisualBenchmarkCaseOutcome.PASS },
            passAfterAtMostOneRepairCases = scores.count { it.outcome == VisualBenchmarkCaseOutcome.PASS },
            safeFallbackCases = scores.count { it.outcome == VisualBenchmarkCaseOutcome.SAFE_FALLBACK },
            caseScores = scores,
        )
    }

    private fun scoreCase(
        benchmarkCase: VisualBenchmarkCase,
        run: VisualBenchmarkCaseRun?,
    ): VisualBenchmarkCaseScore {
        if (run == null) {
            return VisualBenchmarkCaseScore(
                benchmarkCase.caseId,
                VisualBenchmarkCaseOutcome.PROVIDER_FAILURE,
                repaired = false,
                reasons = listOf("Missing provider result."),
            )
        }
        val sequenceIssues = validateAttemptSequence(run.attempts)
        if (sequenceIssues.isNotEmpty()) {
            return VisualBenchmarkCaseScore(
                benchmarkCase.caseId,
                VisualBenchmarkCaseOutcome.PROVIDER_FAILURE,
                repaired = run.attempts.any { it.stage == VisualBenchmarkAttemptStage.REPAIR },
                reasons = sequenceIssues,
            )
        }
        val firstScore = scoreAttempt(benchmarkCase, run.attempts.first())
        if (firstScore.first == VisualBenchmarkCaseOutcome.PASS) {
            return VisualBenchmarkCaseScore(benchmarkCase.caseId, firstScore.first, false, firstScore.second)
        }
        if (run.attempts.size == 1) {
            return VisualBenchmarkCaseScore(benchmarkCase.caseId, firstScore.first, false, firstScore.second)
        }
        val repairedScore = scoreAttempt(benchmarkCase, run.attempts.last())
        return VisualBenchmarkCaseScore(
            benchmarkCase.caseId,
            repairedScore.first,
            repaired = true,
            reasons = repairedScore.second,
        )
    }

    private fun validateAttemptSequence(attempts: List<VisualBenchmarkAttempt>): List<String> {
        if (attempts.isEmpty()) return listOf("No generation attempt.")
        if (attempts.size > 2) return listOf("More than one repair attempt.")
        if (attempts.first().stage != VisualBenchmarkAttemptStage.INITIAL) {
            return listOf("First attempt must be INITIAL.")
        }
        if (attempts.size == 2 && attempts.last().stage != VisualBenchmarkAttemptStage.REPAIR) {
            return listOf("Second attempt must be the only REPAIR.")
        }
        return emptyList()
    }

    private fun scoreAttempt(
        benchmarkCase: VisualBenchmarkCase,
        attempt: VisualBenchmarkAttempt,
    ): Pair<VisualBenchmarkCaseOutcome, List<String>> {
        when (attempt.status) {
            VisualBenchmarkAttemptStatus.DECLINED_UNCERTAIN ->
                return VisualBenchmarkCaseOutcome.SAFE_FALLBACK to listOf("Provider safely declined.")
            VisualBenchmarkAttemptStatus.FAILED ->
                return VisualBenchmarkCaseOutcome.PROVIDER_FAILURE to
                    listOf(attempt.failureReason ?: "Provider task failed.")
            VisualBenchmarkAttemptStatus.GENERATED -> Unit
        }
        if (!attempt.runtimeCompiled) {
            return VisualBenchmarkCaseOutcome.RUNTIME_REJECTED to listOf("Local runtime rejected the scene.")
        }
        if (!attempt.provenanceVerified) {
            return VisualBenchmarkCaseOutcome.RUNTIME_REJECTED to
                listOf("Local numeric provenance verification did not pass.")
        }
        if (attempt.visibleIllustrativeValue) {
            return VisualBenchmarkCaseOutcome.RUNTIME_REJECTED to
                listOf("An illustrative value would be visible.")
        }
        val observation = attempt.observation
            ?: return VisualBenchmarkCaseOutcome.INCORRECT to listOf("Missing semantic adjudication.")
        val reasons = semanticDifferences(benchmarkCase.annotations, observation)
        return if (reasons.isEmpty()) {
            VisualBenchmarkCaseOutcome.PASS to emptyList()
        } else {
            VisualBenchmarkCaseOutcome.INCORRECT to reasons
        }
    }

    private fun semanticDifferences(
        expected: VisualBenchmarkAnnotations,
        actual: VisualBenchmarkObservation,
    ): List<String> = buildList {
        val criticalObjects = expected.objects.filter { it.critical }.mapTo(hashSetOf()) { it.semanticId }
        val missingObjects = criticalObjects - actual.objectSemanticIds.toSet()
        if (missingObjects.isNotEmpty()) add("Missing critical objects: ${missingObjects.sorted()}.")
        addMissing("connections", expected.connections.toSet(), actual.connections.toSet())
        addMissing("directions", expected.directions.toSet(), actual.directions.toSet())
        addMissing(
            "required numeric values",
            expected.numericValues.filter { it.displayRequired }.toSet(),
            actual.numericValues.toSet(),
        )
        addMissing("spatial relations", expected.spatialRelations.toSet(), actual.spatialRelations.toSet())
        addMissing("temporal relations", expected.temporalOrder.toSet(), actual.temporalOrder.toSet())
        if (actual.contradictions.isNotEmpty()) {
            add("Adjudicated contradictions: ${actual.contradictions}.")
        }
    }

    private fun <T> MutableList<String>.addMissing(
        label: String,
        expected: Set<T>,
        actual: Set<T>,
    ) {
        val missing = expected - actual
        if (missing.isNotEmpty()) add("Missing $label: $missing.")
    }
}
