package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof

private val organizationKnowledgeReferenceIssuer =
    KnowledgeReferenceProofAuthority.create().issuer

internal fun verifiedOrganizationKnowledgeReference(
    knowledgeNodeId: String,
    subject: SubjectKind = SubjectKind.MATH,
): VerifiedKnowledgeReferenceProof =
    organizationKnowledgeReferenceIssuer.issue(
        KnowledgeNodeRef(
            subject = subject,
            knowledgeNodeId = knowledgeNodeId,
            taxonomyVersion = "catalog-taxonomy-v1",
            knowledgePackVersion = "catalog-pack-v1",
        ),
        "d".repeat(64),
        1L,
    )
