package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorAuthorizedCapability
import com.tingyun.smartmistakebook.core.domain.TutorIntentAuthorityPolicy
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput

internal fun List<ModelTaskSnapshot>.blocksTutorLongTermWrites(): Boolean = any { task ->
    val input = task.request.input as? TutorRespondInput
    val output = task.output as? TutorRespondOutput
    task.status == ModelTaskStatus.SUCCEEDED &&
        input != null &&
        output != null &&
        TutorAuthorizedCapability.BLOCK_LONG_TERM_WRITES_FOR_SESSION in
        TutorIntentAuthorityPolicy.authorize(
            decision = output.intentDecision,
            studentMessage = input.studentMessage,
        ).capabilities
}
