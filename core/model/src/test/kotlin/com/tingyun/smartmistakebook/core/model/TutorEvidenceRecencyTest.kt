package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 时间档位的边界值本身就是契约：模型看到的是档位而不是时间戳，档位算错会让
 * "最近错过"读成"很久没碰"，而两处渲染（基线 evidence 与 MASTERY_READ 结果）
 * 共用这一个实现，所以这里的边界就是两者共同的口径。
 */
class TutorEvidenceRecencyTest {

    private val dayMillis = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    @Test
    fun bucketsSitOnInclusiveBoundaries() {
        assertEquals(TutorEvidenceRecency.WITHIN_7_DAYS, at(7 * dayMillis))
        assertEquals(TutorEvidenceRecency.WITHIN_30_DAYS, at(7 * dayMillis + 1))
        assertEquals(TutorEvidenceRecency.WITHIN_30_DAYS, at(30 * dayMillis))
        assertEquals(TutorEvidenceRecency.WITHIN_90_DAYS, at(30 * dayMillis + 1))
        assertEquals(TutorEvidenceRecency.WITHIN_90_DAYS, at(90 * dayMillis))
        assertEquals(TutorEvidenceRecency.OLDER, at(90 * dayMillis + 1))
    }

    @Test
    fun aMissingTimestampIsUnknownRatherThanFresh() {
        // 没有时间戳不等于"刚刚发生过"。把缺失当新鲜会让模型以为证据是新的。
        assertEquals(TutorEvidenceRecency.UNKNOWN, TutorEvidenceRecency.of(null, now))
    }

    @Test
    fun aFutureTimestampIsUnknownRatherThanNegativeAge() {
        // 时钟回拨：事件时间晚于现在时不能算出负数年龄再落进"7天内"。
        assertEquals(
            TutorEvidenceRecency.UNKNOWN,
            TutorEvidenceRecency.of(now + dayMillis, now),
        )
    }

    private fun at(ageMillis: Long): TutorEvidenceRecency =
        TutorEvidenceRecency.of(now - ageMillis, now)
}
