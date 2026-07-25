package com.tingyun.smartmistakebook.quality.visualbenchmark

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class VisualBenchmarkRequirements(
    val minimumCases: Int,
    val minimumDistinctImages: Int,
    val minimumSubjects: Int,
    val minimumFamilies: Int,
) {
    companion object {
        val RELEASE = VisualBenchmarkRequirements(
            minimumCases = 120,
            minimumDistinctImages = 120,
            minimumSubjects = 9,
            minimumFamilies = 12,
        )

        val STRUCTURAL_SMOKE = VisualBenchmarkRequirements(
            minimumCases = 1,
            minimumDistinctImages = 1,
            minimumSubjects = 1,
            minimumFamilies = 1,
        )
    }
}

data class VisualBenchmarkValidationIssue(
    val code: String,
    val detail: String,
    val caseId: String? = null,
)

data class VisualBenchmarkValidationReport(
    val issues: List<VisualBenchmarkValidationIssue>,
) {
    val isValid: Boolean = issues.isEmpty()

    fun requireValid() {
        require(isValid) {
            issues.joinToString(
                prefix = "Visual benchmark dataset is invalid:\n",
                separator = "\n",
            ) { issue ->
                val location = issue.caseId?.let { " [$it]" }.orEmpty()
                "- ${issue.code}$location: ${issue.detail}"
            }
        }
    }
}

object VisualBenchmarkDatasetValidator {
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val safeIdPattern = Regex("[a-z0-9][a-z0-9._-]{0,95}")

    fun validate(
        manifest: VisualBenchmarkManifest,
        requirements: VisualBenchmarkRequirements = VisualBenchmarkRequirements.RELEASE,
        datasetRoot: Path? = null,
    ): VisualBenchmarkValidationReport {
        val issues = buildList {
            if (manifest.schemaVersion != VISUAL_BENCHMARK_SCHEMA_VERSION) {
                add(
                    VisualBenchmarkValidationIssue(
                        code = "SCHEMA_VERSION",
                        detail = "Expected schema $VISUAL_BENCHMARK_SCHEMA_VERSION.",
                    ),
                )
            }
            if (!safeIdPattern.matches(manifest.datasetId)) {
                add(VisualBenchmarkValidationIssue("DATASET_ID", "Dataset id is not stable or safe."))
            }
            if (manifest.datasetVersion.isBlank()) {
                add(VisualBenchmarkValidationIssue("DATASET_VERSION", "Dataset version is blank."))
            }
            requireCoverage(manifest, requirements, this)
            requireUniqueCaseIds(manifest, this)
            manifest.cases.forEach { benchmarkCase ->
                validateCase(benchmarkCase, datasetRoot, this)
            }
        }
        return VisualBenchmarkValidationReport(issues)
    }

    private fun requireCoverage(
        manifest: VisualBenchmarkManifest,
        requirements: VisualBenchmarkRequirements,
        issues: MutableList<VisualBenchmarkValidationIssue>,
    ) {
        if (manifest.cases.size < requirements.minimumCases) {
            issues += VisualBenchmarkValidationIssue(
                "CASE_COVERAGE",
                "Requires at least ${requirements.minimumCases} cases; found ${manifest.cases.size}.",
            )
        }
        val distinctImages = manifest.cases.map { it.source.sha256 }.distinct().size
        if (distinctImages < requirements.minimumDistinctImages) {
            issues += VisualBenchmarkValidationIssue(
                "IMAGE_COVERAGE",
                "Requires at least ${requirements.minimumDistinctImages} distinct image hashes; found $distinctImages.",
            )
        }
        val subjects = manifest.cases.map { it.subject }.distinct().size
        if (subjects < requirements.minimumSubjects) {
            issues += VisualBenchmarkValidationIssue(
                "SUBJECT_COVERAGE",
                "Requires at least ${requirements.minimumSubjects} subjects; found $subjects.",
            )
        }
        val families = manifest.cases.map { it.family }.distinct().size
        if (families < requirements.minimumFamilies) {
            issues += VisualBenchmarkValidationIssue(
                "FAMILY_COVERAGE",
                "Requires at least ${requirements.minimumFamilies} figure families; found $families.",
            )
        }
    }

    private fun requireUniqueCaseIds(
        manifest: VisualBenchmarkManifest,
        issues: MutableList<VisualBenchmarkValidationIssue>,
    ) {
        manifest.cases
            .groupingBy { it.caseId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { duplicateId ->
                issues += VisualBenchmarkValidationIssue(
                    "DUPLICATE_CASE_ID",
                    "Case id must be unique.",
                    duplicateId,
                )
            }
    }

    private fun validateCase(
        benchmarkCase: VisualBenchmarkCase,
        datasetRoot: Path?,
        issues: MutableList<VisualBenchmarkValidationIssue>,
    ) {
        val caseIssues = mutableListOf<VisualBenchmarkValidationIssue>()
        fun issue(code: String, detail: String) {
            caseIssues += VisualBenchmarkValidationIssue(code, detail, benchmarkCase.caseId)
        }

        if (!safeIdPattern.matches(benchmarkCase.caseId)) issue("CASE_ID", "Case id is not stable or safe.")
        if (benchmarkCase.currentQuestionPart.isBlank()) issue("QUESTION_PART", "Current question part is blank.")
        if (!sha256Pattern.matches(benchmarkCase.source.sha256)) {
            issue("IMAGE_SHA256", "Image hash must be lowercase SHA-256.")
        }
        if (benchmarkCase.source.widthPixels <= 0 || benchmarkCase.source.heightPixels <= 0) {
            issue("IMAGE_DIMENSIONS", "Image dimensions must be positive.")
        }
        val relativePath = benchmarkCase.source.relativePath
            .takeIf(String::isNotBlank)
            ?.let { path -> runCatching { Path.of(path) }.getOrNull() }
        if (
            relativePath == null ||
            relativePath.isAbsolute ||
            relativePath.normalize().startsWith("..")
        ) {
            issue("IMAGE_PATH", "Image path must stay relative to the private dataset root.")
        } else if (datasetRoot != null) {
            validateImageFile(benchmarkCase, datasetRoot, relativePath, ::issue)
        }
        validateAnnotations(benchmarkCase.annotations, ::issue)
        issues += caseIssues
    }

    private fun validateImageFile(
        benchmarkCase: VisualBenchmarkCase,
        datasetRoot: Path,
        relativePath: Path,
        issue: (String, String) -> Unit,
    ) {
        val normalizedRoot = datasetRoot.toAbsolutePath().normalize()
        val imagePath = normalizedRoot.resolve(relativePath).normalize()
        if (!imagePath.startsWith(normalizedRoot)) {
            issue("IMAGE_PATH", "Resolved image path escapes the private dataset root.")
            return
        }
        if (!Files.isRegularFile(imagePath)) {
            issue("IMAGE_MISSING", "Annotated source image does not exist.")
            return
        }
        val actualHash = sha256(imagePath)
        if (actualHash != benchmarkCase.source.sha256) {
            issue("IMAGE_HASH_MISMATCH", "Annotated source image does not match its SHA-256.")
        }
    }

    private fun validateAnnotations(
        annotations: VisualBenchmarkAnnotations,
        issue: (String, String) -> Unit,
    ) {
        if (annotations.objects.isEmpty()) issue("OBJECTS_EMPTY", "At least one object must be annotated.")
        val objectIds = annotations.objects.map { it.semanticId }
        requireUnique("OBJECT_ID", objectIds, issue)
        if (annotations.objects.none { it.critical }) {
            issue("CRITICAL_OBJECT", "At least one object must be marked critical.")
        }
        annotations.objects.forEach {
            if (!safeIdPattern.matches(it.semanticId)) issue("OBJECT_ID", "Object semantic id is invalid.")
            if (it.label.isBlank()) issue("OBJECT_LABEL", "Object label is blank.")
        }
        val knownObjects = objectIds.toSet()
        annotations.connections.forEach {
            requireObjectReference(it.fromSemanticId, knownObjects, "CONNECTION_FROM", issue)
            requireObjectReference(it.toSemanticId, knownObjects, "CONNECTION_TO", issue)
            if (it.relation.isBlank()) issue("CONNECTION_RELATION", "Connection relation is blank.")
        }
        requireUnique("DIRECTION_ID", annotations.directions.map { it.semanticId }, issue)
        annotations.directions.forEach {
            requireSemanticId(it.semanticId, "DIRECTION_ID", issue)
            requireObjectReference(it.fromSemanticId, knownObjects, "DIRECTION_FROM", issue)
            requireObjectReference(it.toSemanticId, knownObjects, "DIRECTION_TO", issue)
            if (it.meaning.isBlank()) issue("DIRECTION_MEANING", "Direction meaning is blank.")
        }
        requireUnique("NUMERIC_ID", annotations.numericValues.map { it.semanticId }, issue)
        annotations.numericValues.forEach {
            requireSemanticId(it.semanticId, "NUMERIC_ID", issue)
            if (it.canonicalValue.isBlank()) issue("NUMERIC_VALUE", "Canonical numeric value is blank.")
        }
        requireUnique("SPATIAL_ID", annotations.spatialRelations.map { it.semanticId }, issue)
        annotations.spatialRelations.forEach {
            requireSemanticId(it.semanticId, "SPATIAL_ID", issue)
            requireObjectReference(it.subjectSemanticId, knownObjects, "SPATIAL_SUBJECT", issue)
            requireObjectReference(it.referenceSemanticId, knownObjects, "SPATIAL_REFERENCE", issue)
            if (it.relation.isBlank()) issue("SPATIAL_RELATION", "Spatial relation is blank.")
        }
        requireUnique("TEMPORAL_ID", annotations.temporalOrder.map { it.semanticId }, issue)
        annotations.temporalOrder.forEach {
            requireSemanticId(it.semanticId, "TEMPORAL_ID", issue)
            if (it.beforeState.isBlank() || it.trigger.isBlank() || it.afterState.isBlank()) {
                issue("TEMPORAL_RELATION", "Temporal annotation must include before, trigger, and after.")
            }
        }
    }

    private fun requireObjectReference(
        semanticId: String,
        knownObjects: Set<String>,
        code: String,
        issue: (String, String) -> Unit,
    ) {
        if (semanticId !in knownObjects) issue(code, "Unknown object semantic id: $semanticId.")
    }

    private fun requireSemanticId(
        semanticId: String,
        code: String,
        issue: (String, String) -> Unit,
    ) {
        if (!safeIdPattern.matches(semanticId)) issue(code, "Semantic id is invalid: $semanticId.")
    }

    private fun requireUnique(
        code: String,
        values: List<String>,
        issue: (String, String) -> Unit,
    ) {
        if (values.size != values.distinct().size) issue(code, "Semantic ids must be unique.")
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
