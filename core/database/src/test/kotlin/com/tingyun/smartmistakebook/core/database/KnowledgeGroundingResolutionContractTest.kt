package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeGroundingResolutionContractTest {
    @Test
    fun `valid command derives its immutable resolution id`() {
        val command = command()

        assertEquals(
            KnowledgeGroundingFingerprint.resolutionId(
                groundingKey = command.groundingKey,
                knowledgeNodeId = command.knowledgeNodeId,
            ),
            KnowledgeGroundingResolutionContract.validate(command),
        )
    }

    @Test
    fun `invalid subject node and time fail before Room`() {
        listOf(
            command().copy(subject = "UNKNOWN"),
            command().copy(knowledgeNodeId = " node-with-padding "),
            command().copy(resolvedAtEpochMillis = 0),
        ).forEach { invalid ->
            assertTrue(
                runCatching { KnowledgeGroundingResolutionContract.validate(invalid) }
                    .exceptionOrNull() is DatabaseContractViolationException,
            )
        }
    }

    private fun command(): ResolveKnowledgeGroundingCommand = ResolveKnowledgeGroundingCommand(
        groundingKey = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.MATH,
            expectedParentKnowledgeDisplayName = "函数性质",
            query = "导数符号与单调性",
        ),
        subject = SubjectKind.MATH.name,
        knowledgeNodeId = "kb:research-v1:math:atomic:monotonicity",
        resolvedAtEpochMillis = 2_000,
    )
}
