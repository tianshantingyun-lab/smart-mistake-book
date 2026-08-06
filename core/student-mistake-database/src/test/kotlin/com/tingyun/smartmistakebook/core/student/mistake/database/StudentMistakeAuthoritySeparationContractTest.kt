package com.tingyun.smartmistakebook.core.student.mistake.database

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeAuthoritySeparationContractTest {
    @Test
    fun ownedAttributionPersistsFactsButNoModelConfidenceOrMasteryState() {
        assertEquals(
            setOf(
                "attributionId",
                "basisRevisionId",
                "organizationReceiptId",
                "solutionAnalysisId",
                "resolutionStatus",
                "rationaleMarkdown",
                "stepOrdinal",
                "atomicReferenceId",
                "modelProviderId",
                "modelId",
                "analyzerVersion",
                "resultCanonicalFingerprint",
                "recordedAtEpochMillis",
            ),
            StudentProblemErrorAttributionEntity::class.java.persistedFieldNames(),
        )
        listOf(
            StudentProblemErrorAttribution::class.java,
            StudentProblemResolvedErrorKnowledgeAttribution::class.java,
        ).forEach { type ->
            val exposed = type.persistedFieldNames().joinToString(" ").lowercase()
            FORBIDDEN_STUDENT_STATE_NAMES.forEach { forbidden ->
                assertFalse("${type.simpleName} exposes $forbidden", forbidden in exposed)
            }
        }
    }

    @Test
    fun knowledgeBindingsKeepOnlyOpaqueReferencesAndCatalogProofIdentity() {
        val storedFields =
            (
                StudentProblemClassificationResultEntity::class.java.persistedFieldNames() +
                    StudentProblemStepKnowledgeBindingEntity::class.java.persistedFieldNames()
            ).map(String::lowercase)
        setOf(
            "knowledgeSubject",
            "knowledgeNodeId",
            "knowledgeTaxonomyVersion",
            "knowledgePackVersion",
            "knowledgeManifestFingerprint",
            "knowledgeContentCanonicalFingerprint",
            "knowledgeActivationGeneration",
        ).forEach { expected ->
            assertTrue("missing opaque knowledge identity $expected", expected.lowercase() in storedFields)
        }
        FORBIDDEN_KNOWLEDGE_BODY_NAMES.forEach { forbidden ->
            assertTrue(
                "formal knowledge body leaked through $forbidden",
                storedFields.none { field -> forbidden in field },
            )
        }
    }

    @Test
    fun moduleCannotOpenKnowledgeMasteryOrCoreDataAuthorities() {
        val root = projectRoot()
        val build =
            File(root, "core/student-mistake-database/build.gradle.kts").readText()
        assertEquals(
            listOf(":core:model"),
            Regex("""project\(\"([^\"]+)\"\)""")
                .findAll(build)
                .map { match -> match.groupValues[1] }
                .toList(),
        )

        val productionSources =
            File(root, "core/student-mistake-database/src/main")
                .walkTopDown()
                .filter { file -> file.isFile && file.extension in setOf("kt", "java") }
                .joinToString("\n") { file -> file.readText() }
        setOf(
            "com.tingyun.smartmistakebook.core.data",
            "com.tingyun.smartmistakebook.core.knowledge.database",
            "com.tingyun.smartmistakebook.core.mastery",
        ).forEach { forbiddenImport ->
            assertFalse("student mistake authority imports $forbiddenImport", forbiddenImport in productionSources)
        }
    }

    @Test
    fun configuredMigrationChainRemainsContiguousThroughV20() {
        val root = projectRoot()
        val configuration =
            File(
                root,
                "core/student-mistake-database/src/main/java/com/tingyun/smartmistakebook/core/student/mistake/database/StudentMistakeOwnedDatabase.java",
            ).readText()
        val configuredPairs =
            Regex("""getSTUDENT_MISTAKE_MIGRATION_(\d+)_(\d+)""")
                .findAll(configuration)
                .map { match -> match.groupValues[1].toInt() to match.groupValues[2].toInt() }
                .toList()
        assertEquals((1 until 20).map { version -> version to version + 1 }, configuredPairs)

        val migration =
            File(
                root,
                "core/student-mistake-database/src/main/kotlin/com/tingyun/smartmistakebook/core/student/mistake/database/StudentMistakeMigration15To16.kt",
            ).readText()
        val copyProjection =
            migration.substringAfter("INSERT INTO `\$V15_ATTRIBUTION_TABLE`")
                .substringBefore("FROM `\${V15_ATTRIBUTION_TABLE}_v15`")
        assertFalse("v15 model confidence must not be copied", "`confidence`" in copyProjection)
        assertTrue("migration must check every foreign key", "PRAGMA foreign_key_check" in migration)
    }
}

private fun Class<*>.persistedFieldNames(): Set<String> =
    declaredFields
        .filterNot { field -> field.isSynthetic || field.name.startsWith("$") }
        .mapTo(linkedSetOf()) { field -> field.name }

private fun projectRoot(): File =
    generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        File::getParentFile,
    ).first { directory -> File(directory, "settings.gradle.kts").isFile }

private val FORBIDDEN_STUDENT_STATE_NAMES =
    setOf("confidence", "weight", "mastery", "proficiency", "ability", "probability", "score")

private val FORBIDDEN_KNOWLEDGE_BODY_NAMES =
    setOf("contentmarkdown", "body", "explanation", "definition", "example", "teachingmaterial")
