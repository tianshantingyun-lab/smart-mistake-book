package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerBoundStudentMistakeLibraryPortContractTest {
    @Test
    fun portIsLearnerBoundAndDoesNotAcceptAnIdentity() {
        assertEquals(
            setOf(
                "observeChangeVersion",
                "prepareSearchIndex",
                "readDetail",
                "readFacets",
                "readPage",
            ),
            LearnerBoundStudentMistakeLibraryPort::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf()) { it.name },
        )
        LearnerBoundStudentMistakeLibraryPort::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .forEach { method ->
                assertTrue(
                    "${method.name} must not accept a learner identity",
                    method.parameterTypes.none { it == String::class.java },
                )
                assertFalse(
                    "${method.name} must not expose learner identity",
                    method.toGenericString().contains("learnerId", ignoreCase = true),
                )
            }
    }

    @Test
    fun cursorCannotBeFabricatedOrExposeItsSnapshotFields() {
        assertTrue(
            StudentMistakeLibraryCursor::class.java.declaredConstructors.all {
                Modifier.isPrivate(it.modifiers)
            },
        )
        assertTrue(
            StudentMistakeLibraryCursor::class.java.fields.isEmpty(),
        )
        assertEquals(
            "StudentMistakeLibraryCursor",
            StudentMistakeLibraryCursor.create(
                1,
                "a".repeat(64),
                "b".repeat(64),
                2,
                "problem-1",
            ).toString(),
        )
    }

    @Test
    fun publicReadDtosContainNoMasteryKnowledgeBodyOrModelInternals() {
        val safeTypes =
            listOf(
                StudentMistakeLibraryItem::class.java,
                StudentMistakeLibraryDetail::class.java,
                StudentMistakeLibraryFacets::class.java,
                StudentMistakeLibrarySection::class.java,
                StudentMistakeLibraryImage::class.java,
            )
        val forbidden =
            setOf(
                "learnerId",
                "mastery",
                "confidence",
                "weight",
                "evidence",
                "modelProvider",
                "modelId",
                "classifierVersion",
                "manifestFingerprint",
                "activationGeneration",
                "knowledgeBody",
                "room",
                "dao",
                "sqlite",
                "sql",
            )
        safeTypes.forEach { type ->
            val signature =
                buildString {
                    type.declaredFields
                        .filterNot { it.isSynthetic }
                        .forEach { field ->
                            append(field.name)
                            append(field.genericType.typeName)
                        }
                    type.declaredMethods
                        .filterNot { it.isSynthetic }
                        .forEach { method ->
                            append(method.name)
                            append(method.genericReturnType.typeName)
                            method.genericParameterTypes.forEach { append(it.typeName) }
                        }
                }
            forbidden.forEach { fragment ->
                assertFalse(
                    "${type.simpleName} leaks $fragment",
                    signature.contains(fragment, ignoreCase = true),
                )
            }
        }
        assertEquals(
            KnowledgeNodeRef::class.java,
            StudentMistakeKnowledgeFacet::class.java
                .declaredMethods
                .single { it.name == "getKnowledgeNode" }
                .returnType,
        )
        assertTrue(
            StudentMistakeLibrarySection::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .none { it.name.contains("display", ignoreCase = true) },
        )
    }

    @Test
    fun filterRequiresOneSubjectWhenAknowledgeReferenceIsUsed() {
        val mathNode =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = "math.function.quadratic",
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v1",
            )
        assertTrue(
            runCatching {
                StudentMistakeLibraryFilter(
                    subject = SubjectKind.PHYSICS,
                    knowledgeNode = mathNode,
                )
            }.isFailure,
        )
        assertEquals(
            mathNode,
            StudentMistakeLibraryFilter(
                subject = SubjectKind.MATH,
                knowledgeNode = mathNode,
            ).knowledgeNode,
        )
    }

}
