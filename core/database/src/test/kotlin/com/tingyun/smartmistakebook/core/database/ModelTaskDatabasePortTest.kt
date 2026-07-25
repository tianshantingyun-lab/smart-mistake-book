package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import org.junit.Test

class ModelTaskDatabasePortTest {
    @Test(expected = IllegalArgumentException::class)
    fun createCommandRejectsFingerprintThatDoesNotBindItsRequest() {
        CreateModelTaskCommand(
            taskId = "task-1",
            request = ModelTaskRequest(
                requestId = "capture-assess:request-1",
                input = CaptureAssessmentInput(
                    draftId = "draft-1",
                    sourceAssetId = "asset-1",
                    origin = CaptureAssessmentOrigin.LIBRARY,
                    imageWidth = 1080,
                    imageHeight = 1440,
                ),
                occurredAtEpochMillis = 100,
            ),
            requestFingerprint = "a".repeat(64),
            occurredAtEpochMillis = 100,
        )
    }
}
