package com.tingyun.smartmistakebook.core.data.mistake;

import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog;
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentProblemKnowledgeAttributionPort;

/** Composes the read-only knowledge-attribution view without exposing its owner store. */
public final class VerifiedStudentKnowledgeAttributionOwnerAssembly {
    private VerifiedStudentKnowledgeAttributionOwnerAssembly() {}

    public static LearnerBoundVerifiedStudentProblemKnowledgeAttributionPort create(
            LearnerBoundStudentProblemKnowledgeAttributionPort persistedAttributions,
            HighSchoolKnowledgeCatalog knowledgeCatalog) {
        return new VerifiedStudentProblemKnowledgeAttributionReader(
                persistedAttributions,
                new CatalogCurrentKnowledgeReferenceVerifier(knowledgeCatalog));
    }
}
