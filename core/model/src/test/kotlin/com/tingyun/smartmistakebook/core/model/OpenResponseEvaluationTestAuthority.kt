package com.tingyun.smartmistakebook.core.model

internal fun issueTestOpenResponseEvaluationRequest(
    binding: OpenResponseEvaluationBinding,
    subject: SubjectKind,
    knowledgeScope: List<OpenResponseKnowledgeScopeRef>,
    evaluator: OpenResponseEvaluatorKind,
    policyFingerprint: String,
    evidenceFingerprint: String,
    authorization: OpenResponseEvaluationAuthorization =
        OpenResponseEvaluationAuthorization { Unit },
): HostIssuedOpenResponseEvaluationRequest =
    OpenResponseEvaluationHostAuthority
        .create(OpenResponseEvaluationOwnerKey.INSTANCE)
        .issue(
            binding = binding,
            subject = subject,
            knowledgeScope = knowledgeScope,
            evaluator = evaluator,
            policyFingerprint = policyFingerprint,
            evidenceFingerprint = evidenceFingerprint,
            authorization = authorization,
        )
