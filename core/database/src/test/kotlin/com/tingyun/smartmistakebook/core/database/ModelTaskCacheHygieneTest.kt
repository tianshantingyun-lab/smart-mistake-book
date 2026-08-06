package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTaskCacheHygieneTest {
    @Test
    fun directLegacyTutorKnowledgeNumbersAreDetectedStructurally() {
        assertTrue(
            detects(
                ModelTaskKind.TUTOR_PLAN,
                """
                {
                  "schemaVersion":7,
                  "input":{
                    "type":"tutor_plan",
                    "questionDocument":{"blocks":[]},
                    "relevantLearningEvidence":[{
                      "displayName":"导数",
                      "independentCorrectLowerBound":0.73,
                      "evidenceMass":4.5,
                      "independentCorrectObservationCount":3
                    }]
                  }
                }
                """,
            ),
        )
    }

    @Test
    fun directLegacyTutorQuestionNumbersAreDetectedStructurally() {
        assertTrue(
            detects(
                ModelTaskKind.TUTOR_RESPOND,
                """
                {
                  "schemaVersion":7,
                  "input":{
                    "type":"tutor_respond",
                    "questionLearningEvidence":{
                      "independentRecallCount":2,
                      "assistedRecallCount":1,
                      "retrievalFailureCount":3,
                      "answerRevealCount":1,
                      "retentionEstimate":0.42
                    }
                  }
                }
                """,
            ),
        )
    }

    @Test
    fun currentTutorRequestWithoutLegacyFieldsIsRetained() {
        assertFalse(
            detects(
                ModelTaskKind.TUTOR_PLAN,
                """
                {
                  "schemaVersion":8,
                  "input":{
                    "type":"tutor_plan",
                    "teachingConstraints":[{
                      "ref":"current-question-point-1",
                      "label":"函数单调性",
                      "constraint":"MAY_GUIDE"
                    }]
                  }
                }
                """,
            ),
        )
    }

    @Test
    fun nonTutorTaskIsRetainedEvenWhenItsPayloadUsesLegacyFieldNames() {
        assertFalse(
            detects(
                ModelTaskKind.CAPTURE_ASSESS,
                """
                {
                  "schemaVersion":8,
                  "input":{
                    "type":"capture_assess",
                    "relevantLearningEvidence":[{
                      "evidenceMass":99.0
                    }]
                  }
                }
                """,
            ),
        )
    }

    @Test
    fun nestedAttackerFieldsAndQuestionTextDoNotMatch() {
        assertFalse(
            detects(
                ModelTaskKind.TUTOR_PLAN,
                """
                {
                  "schemaVersion":8,
                  "input":{
                    "type":"tutor_plan",
                    "questionDocument":{
                      "metadata":{
                        "relevantLearningEvidence":[{
                          "evidenceMass":99.0
                        }]
                      },
                      "blocks":[{
                        "text":"JSON示例是 evidenceMass 和 independentCorrectLowerBound: 0.75"
                      }]
                    }
                  }
                }
                """,
            ),
        )
    }

    @Test
    fun fieldLookingStringsAndWrongTutorDiscriminatorDoNotMatch() {
        assertFalse(
            detects(
                ModelTaskKind.TUTOR_PLAN,
                """
                {
                  "input":{
                    "type":"tutor_respond",
                    "relevantLearningEvidence":[{
                      "evidenceMass":"0.99",
                      "independentCorrectObservationCount":"7"
                    }]
                  }
                }
                """,
            ),
        )
    }

    @Test
    fun malformedAndOversizedSnapshotsFailClosedWithoutUnboundedParsing() {
        assertFalse(detects(ModelTaskKind.TUTOR_PLAN, """{"input":"""))
        assertFalse(
            detects(
                ModelTaskKind.TUTOR_PLAN,
                " ".repeat(ModelTaskCodec.MAX_ENCODED_CHARS + 1),
            ),
        )
        val deeplyNested = "[".repeat(65) + "0" + "]".repeat(65)
        assertFalse(detects(ModelTaskKind.TUTOR_PLAN, deeplyNested))
    }

    private fun detects(kind: ModelTaskKind, snapshot: String): Boolean =
        ModelTaskCacheHygiene.containsDeprecatedTutorMasteryNumbers(
            taskKind = kind.name,
            requestSnapshot = snapshot.trimIndent(),
        )
}
