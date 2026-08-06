package com.tingyun.smartmistakebook.core.domain

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMasteryDisplayRepositoryTest {
    @Test
    fun `page request accepts only a bounded keyset cursor from the same subject and revision`() {
        val revision = revision("revision-a")
        val cursor = cursor(
            value = "cursor-a",
            subject = LearningMasterySubject.MATHEMATICS,
            revision = revision,
        )

        assertEquals(
            64,
            LearningMasteryPageRequest(
                subject = LearningMasterySubject.MATHEMATICS,
                revision = revision,
                cursor = cursor,
                limit = 64,
            ).limit,
        )
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryPageRequest(
                subject = LearningMasterySubject.MATHEMATICS,
                revision = revision,
                limit = 65,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryPageRequest(
                subject = LearningMasterySubject.PHYSICS,
                revision = revision,
                cursor = cursor,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryPageRequest(
                subject = LearningMasterySubject.MATHEMATICS,
                revision = revision("revision-b"),
                cursor = cursor,
            )
        }
    }

    @Test
    fun `projection changes invalidate content without exposing a technical error`() {
        val changed = LearningMasteryLoadState.Error(
            reason = LearningMasteryDisplayError.PROJECTION_CHANGED,
            currentRevision = revision("revision-b"),
        )

        assertEquals(LearningMasteryDisplayError.PROJECTION_CHANGED, changed.reason)
        assertFalse(changed.javaClass.declaredFields.any { field -> field.name == "message" })
        assertFalse(changed.javaClass.declaredFields.any { field -> field.name == "cause" })
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryLoadState.Error(
                reason = LearningMasteryDisplayError.PROJECTION_CHANGED,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryLoadState.Error(
                reason = LearningMasteryDisplayError.TEMPORARILY_UNAVAILABLE,
                currentRevision = revision("revision-b"),
            )
        }
    }

    @Test
    fun `content constructors reject empty duplicate or over-budget projections`() {
        val revision = revision("revision-a")
        val item = item("knowledge-a")

        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryOverview(
                revision = revision,
                subjects = emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryOverview(
                revision = revision,
                subjects = List(2) {
                    LearningMasterySubjectOverview(
                        subject = LearningMasterySubject.MATHEMATICS,
                        status = LearningMasteryStatus.GETTING_FAMILIAR,
                        trend = LearningMasteryTrend.IMPROVING,
                    )
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryKnowledgePage(
                subject = LearningMasterySubject.MATHEMATICS,
                revision = revision,
                items = emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryKnowledgePage(
                subject = LearningMasterySubject.MATHEMATICS,
                revision = revision,
                items = listOf(item, item),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryKnowledgePage(
                subject = LearningMasterySubject.MATHEMATICS,
                revision = revision,
                items = List(65) { index -> item("knowledge-$index") },
            )
        }
    }

    @Test
    fun `timeline request and entries enforce bounded ordered day ranges`() {
        val revision = revision("revision-a")
        val request =
            LearningMasteryTimelineRequest(
                subject = LearningMasterySubject.MATHEMATICS,
                revision = revision,
                range = LearningMasteryTimelineRange.LAST_7_DAYS,
            )

        assertEquals(7, request.range.days)
        assertEquals(30, LearningMasteryTimelineRange.LAST_30_DAYS.days)
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryTimelineEntry(
                day = 1L,
                signal = LearningMasteryTimelineSignal.PROGRESS,
                activity = LearningMasteryTimelineActivity.REGULAR,
                attempts = -1,
                knowledgePoints = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryTimeline(
                revision = revision,
                subject = LearningMasterySubject.MATHEMATICS,
                entries = listOf(entry(2L), entry(1L)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryTimeline(
                revision = revision,
                subject = LearningMasterySubject.MATHEMATICS,
                entries = List(31) { index -> entry(index.toLong()) },
            )
        }
    }

    @Test
    fun `public mastery display contract contains no learner or authority internals`() {
        val contractTypes = listOf(
            LearningMasteryDisplayRepository::class.java,
            LearningMasteryLoadState::class.java,
            LearningMasteryLoadState.Loading::class.java,
            LearningMasteryLoadState.Content::class.java,
            LearningMasteryLoadState.Empty::class.java,
            LearningMasteryLoadState.Error::class.java,
            LearningMasteryDisplayError::class.java,
            LearningMasteryOverview::class.java,
            LearningMasterySubjectOverview::class.java,
            LearningMasterySubject::class.java,
            LearningMasteryStatus::class.java,
            LearningMasteryTrend::class.java,
            LearningMasteryTimelineRange::class.java,
            LearningMasteryTimelineRequest::class.java,
            LearningMasteryTimeline::class.java,
            LearningMasteryTimelineEntry::class.java,
            LearningMasteryTimelineSignal::class.java,
            LearningMasteryTimelineActivity::class.java,
            LearningMasteryPageRequest::class.java,
            LearningMasteryKnowledgePage::class.java,
            LearningMasteryKnowledgeItem::class.java,
            LearningMasteryKnowledgeKey::class.java,
            LearningMasteryProjectionRevision::class.java,
            LearningMasteryPageCursor::class.java,
        )
        val publicSurface = contractTypes.flatMap { type ->
            buildList {
                add(type.name)
                type.declaredFields
                    .filter { field -> Modifier.isPublic(field.modifiers) }
                    .forEach { field ->
                        add(field.name)
                        add(field.genericType.typeName)
                    }
                type.declaredMethods
                    .filter { method -> Modifier.isPublic(method.modifiers) }
                    .forEach { method ->
                        add(method.name)
                        add(method.genericReturnType.typeName)
                        method.genericParameterTypes.forEach { parameter -> add(parameter.typeName) }
                    }
                type.constructors.forEach { constructor ->
                    constructor.genericParameterTypes.forEach { parameter -> add(parameter.typeName) }
                }
            }
        }.joinToString(separator = "\n").lowercase()
        val forbiddenTokens = setOf(
            "learnerid",
            "knowledgenoderef",
            "taxonomy",
            "knowledgepack",
            "weight",
            "score",
            "confidence",
            "evidence",
            "count",
            "quality",
            "family",
            "presentation",
            "stability",
            "nextreview",
            "lastreview",
            "reviewtime",
            "epoch",
            "database",
            "room",
            "dao",
            "sql",
            "attribution",
        )

        forbiddenTokens.forEach { token ->
            assertFalse("Public contract leaked forbidden token: $token", publicSurface.contains(token))
        }
        assertFalse(publicSurface.contains("offset"))
        assertFalse(publicSurface.contains("pagenumber"))
        assertFalse(publicSurface.contains("totalcount"))
        assertTrue(publicSurface.contains("opaquevalue"))
        assertTrue(publicSurface.contains("revision"))
    }

    @Test
    fun `opaque values and display text reject blank oversized or control characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryKnowledgeKey.fromOpaque(" ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryProjectionRevision.fromOpaque(" revision ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            cursor(
                value = "cursor\u0000",
                subject = LearningMasterySubject.MATHEMATICS,
                revision = revision("revision-a"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearningMasteryKnowledgeItem(
                key = LearningMasteryKnowledgeKey.fromOpaque("knowledge-a"),
                displayName = " ",
                displayPath = emptyList(),
                status = LearningMasteryStatus.GETTING_FAMILIAR,
                trend = LearningMasteryTrend.NO_CLEAR_CHANGE,
            )
        }
    }

    private fun revision(value: String) =
        LearningMasteryProjectionRevision.fromOpaque(value)

    private fun cursor(
        value: String,
        subject: LearningMasterySubject,
        revision: LearningMasteryProjectionRevision,
    ) = LearningMasteryPageCursor.fromOpaque(
        opaqueValue = value,
        subject = subject,
        revision = revision,
    )

    private fun item(key: String) = LearningMasteryKnowledgeItem(
        key = LearningMasteryKnowledgeKey.fromOpaque(key),
        displayName = "二次函数",
        displayPath = listOf("函数"),
        status = LearningMasteryStatus.GETTING_FAMILIAR,
        trend = LearningMasteryTrend.IMPROVING,
    )

    private fun entry(day: Long) = LearningMasteryTimelineEntry(
        day = day,
        signal = LearningMasteryTimelineSignal.MIXED,
        activity = LearningMasteryTimelineActivity.LIGHT,
        attempts = 1,
        knowledgePoints = 1,
    )
}
