package com.tingyun.smartmistakebook.core.model.storage

import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeReferenceProofAuthorityTest {
    @Test
    fun proofIsAcceptedOnlyByTheAuthorityThatIssuedIt() {
        val owner = KnowledgeReferenceProofAuthority.create()
        val other = KnowledgeReferenceProofAuthority.create()
        val proof =
            owner.issuer.issue(
                knowledgeRef(),
                "a".repeat(64),
                7L,
            )

        assertTrue(owner.verifier.verifies(proof))
        assertFalse(other.verifier.verifies(proof))
    }

    @Test
    fun proofHasNoPublicConstructorCopyOrKnowledgeContentSurface() {
        assertTrue(
            VerifiedKnowledgeReferenceProof::class.java.constructors.none { constructor ->
                Modifier.isPublic(constructor.modifiers)
            },
        )
        assertEquals(
            setOf("getActivationGeneration", "getManifestFingerprint", "getRef"),
            VerifiedKnowledgeReferenceProof::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
                .filterNot { method -> method.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertTrue(
            VerifiedKnowledgeReferenceProof::class.java.declaredMethods.none { method ->
                method.name == "copy" ||
                    method.name.contains("body", ignoreCase = true) ||
                    method.name.contains("dao", ignoreCase = true) ||
                    method.name.contains("database", ignoreCase = true)
            },
        )
    }

    private fun knowledgeRef(): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = "math.function.quadratic",
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
        )
}
