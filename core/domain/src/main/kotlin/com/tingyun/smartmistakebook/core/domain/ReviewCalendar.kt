package com.tingyun.smartmistakebook.core.domain

import java.time.Instant
import java.time.ZoneId

/**
 * 「对**这个学习者**来说，一天从哪里到哪里」的唯一定义（审计 F-02）。
 *
 * 存在的理由是一个已经发生过的失败：同一个工程里并存过**三种**「已经过天数」口径——
 * 写入侧用 learner 本地日历日差（`LearningProjector`）、读取侧一处用墙钟整日地板
 * （`ForgettingCurve.estimateAt` 的 FSRS-6 分支）、另一处用未取整的墙钟分数天
 * （`KnowledgeReviewQueue`）。于是**同一张卡、同一时刻，两个界面对 R 的估计不同**：
 * 一张卡 23:00 复习过，次日 01:00 看，"墙钟地板"说过了 0 天（R = 1），
 * "日历日差"说过了 1 天。口径散落在各处的代价，就是把不一致留在两个界面上。
 *
 * **为什么统一到「本地日历日差」，而不是分数天**（这条被外部一手资料定过，不是口味）：
 *
 * - 本项目实现的是 **FSRS-6**，而 FSRS-6 是**按天**的模型。fsrs-rs `main` 的
 *   `model_v6.rs:243` 有 `round_elapsed_days`（`clamp_min(0) + 0.5` 后取整），
 *   注释原文 "FSRS-6 stays day-based: keep f32 transport, but round elapsed days to nearest day"；
 *   同仓 `inference.rs:521-532` 的文档注释写着 "FSRS-6 rounds elapsed days to nearest whole day
 *   internally; **FSRS-7 keeps fractional elapsed days**"。**分数天是 FSRS-7 的能力**，
 *   不是 FSRS-6 的——把分数天喂进 FSRS-6 的曲线，等于在 FSRS-6 的模型上算 FSRS-7 的输入。
 * - 已发布的 fsrs-rs 更是靠**整数入参**保证这一点（`inference.rs:357` 的
 *   `days_elapsed: u32`）；py-fsrs 用 `max(0, (now - last).days)`（UTC 24 小时截断）。
 *   两者都显式允许 `delta_t = 0`（同日复习走短程分支），**都没有 `max(1, ...)` 下限**。
 * - Anki 的 `elapsed_days_since` 以 rollover 为锚做日界计数，rollover = 0 时与**本地日历日差
 *   数学恒等**——本工程的写入侧用的正是这一支。
 *
 * 引用出处与逐版本核对见 `docs/research/deltat-convention-research.md`（一手源码已复核）。
 *
 * **边界（本节只统一"读侧估计"）**：写入侧（`LearningProjector`）本来就是本地日历日差，
 * 一个字都不用改——这是 F-02 不需要投影版本升级、不需要重放的原因。`LEGACY_EXPONENTIAL`
 * 那条曲线**故意不动**：它是 spec §2.20 的 kill switch，语义由用例逐位冻结，它用的是精确毫秒。
 */
object ReviewCalendar {

    const val DAY_MILLIS = 86_400_000L

    /**
     * 事件**自带**的 UTC 偏移换算出它所属的本地日。写入与重放走这一支：
     * 偏移来自事件自身（事件溯源的确定性输入），所以重放同一事件结果逐位相同，
     * 不依赖任何写入方是否记得给派生字段盖章（审计 AUDIT-ALGORITHM §3.7）。
     */
    fun localEpochDayOf(epochMillis: Long, utcOffsetMinutes: Int): Long =
        Math.floorDiv(epochMillis + utcOffsetMinutes.toLong() * 60_000L, DAY_MILLIS)

    /**
     * 用学习者时区换算出某个时刻所属的本地日。**读侧**走这一支：读侧手上只有一个时刻与
     * 学习者的时区，没有"当时那个事件自带的偏移"。
     *
     * 两者对固定偏移的时区（如 Asia/Shanghai，+08:00 无夏令时）**逐位相同**；
     * 只有在一个恰好跨越夏令时切换的时刻上才可能差一小时，而那一小时只影响
     * 「01:00 算不算新的一天」这种边界——这个差异比"两个界面用两套口径"小得多。
     */
    fun localEpochDayOf(epochMillis: Long, zoneId: ZoneId): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().toEpochDay()

    /**
     * 距上一次复习经过了几个**本地日历日**（以小数形式返回，便于直接喂进 R(t,S)）。
     *
     * 返回的是整数值的小数（例如 `3.0`）：FSRS-6 的 t 本来就是整天数，见本对象的类注释。
     * 时间倒流（`at` 早于 `previous`）clamp 到 0，与写入侧的 `coerceAtLeast(0.0)` 一致。
     */
    fun elapsedCalendarDays(
        previousReviewedAtEpochMillis: Long,
        atEpochMillis: Long,
        zoneId: ZoneId,
    ): Double = (
        localEpochDayOf(atEpochMillis, zoneId) -
            localEpochDayOf(previousReviewedAtEpochMillis, zoneId)
        ).toDouble().coerceAtLeast(0.0)
}
