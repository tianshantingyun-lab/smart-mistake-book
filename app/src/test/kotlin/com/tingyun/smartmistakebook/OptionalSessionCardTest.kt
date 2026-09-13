package com.tingyun.smartmistakebook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 审计 N-03：会话里那两张**可选**卡片（§2.9 前置补救、§2.16 重讲开场）在
 * `produceState` 的协程里加载，异常一旦逃出去就会带走整个会话。
 *
 * 三个断言必须同时成立才算修好：
 *  1. 失败 → 降级成「这题没有这张卡」，而不是把异常交给调用方；
 *  2. 失败**有记录**——静默吞掉只是把模式 E 抬高一层；
 *  3. 取消**照旧向上传播**——吞掉取消会让协程在页面消失后继续跑。
 */
class OptionalSessionCardTest {

    @Test
    fun aFailingCardDegradesToNoCardAndIsReported() = runBlocking {
        val reported = mutableListOf<Throwable>()

        val card = loadOptionalSessionCard(onFailure = { reported += it }) {
            throw IllegalStateException("projection is not current")
        }

        assertNull("失败的卡片必须降级为 null，而不是把异常抛给调用方", card)
        assertEquals(1, reported.size)
        assertEquals("projection is not current", reported.single().message)
    }

    @Test
    fun aLoadedCardPassesThrough() = runBlocking {
        var reported = false

        val card = loadOptionalSessionCard(onFailure = { reported = true }) {
            "material:misconception"
        }

        assertEquals("material:misconception", card)
        assertFalse("成功路径不该报告失败", reported)
    }

    @Test
    fun aMissingCardIsNotAFailure() = runBlocking {
        var reported = false

        val card = loadOptionalSessionCard(onFailure = { reported = true }) { null }

        assertNull(card)
        assertFalse("「本题没有这张卡」是正常结果，不是失败", reported)
    }

    @Test
    fun cancellationStillPropagates() = runBlocking {
        var reported = false
        val thrown = try {
            loadOptionalSessionCard(onFailure = { reported = true }) {
                throw CancellationException("screen left")
            }
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertNotNull("取消必须继续向上传播，不能被当成失败吞掉", thrown)
        assertFalse("取消不是失败，不该走 onFailure", reported)
    }
}
