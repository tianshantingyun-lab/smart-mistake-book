package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.CalibrationInput
import com.tingyun.smartmistakebook.core.domain.CalibrationReportBuilder
import com.tingyun.smartmistakebook.core.domain.ChatEvidenceGateCalibration
import com.tingyun.smartmistakebook.core.domain.FsrsParameterOptimizer
import com.tingyun.smartmistakebook.core.domain.FsrsScheduleMath
import com.tingyun.smartmistakebook.core.domain.HLRPredictionAuditService
import com.tingyun.smartmistakebook.core.domain.OptimalRetention
import com.tingyun.smartmistakebook.core.domain.PlannedReasonCalibration
import com.tingyun.smartmistakebook.core.domain.ReviewSample
import com.tingyun.smartmistakebook.core.domain.SchedulingEvaluationHarness
import com.tingyun.smartmistakebook.core.domain.fittableReviewSamples
import com.tingyun.smartmistakebook.core.domain.SchedulingEvaluationReport
import com.tingyun.smartmistakebook.core.domain.SchedulingSettingsStore
import com.tingyun.smartmistakebook.core.domain.SourceCalibration
import com.tingyun.smartmistakebook.core.model.CalibrationReport
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningModelVersion
import java.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Calibration and scheduling-parameter surfaces of the study repository:
 * evaluation reports, per-source and per-reason calibration, reminder timing,
 * FSRS parameter optimisation and the desired-retention recommendation. Kept
 * apart from the repository's write orchestration; the learner projection is
 * injected as a provider so this service never owns projection state.
 */
internal class StudySchedulingCalibration(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val reviewLogSink: ReviewLogSink,
    private val predictionAuditService: HLRPredictionAuditService,
    private val schedulingSettingsStore: SchedulingSettingsStore?,
    /**
     * 排期与曲线**正在用**的那一组（审计 N-30）。与 `RoomBackedStudyExperienceRepository`
     * 的 `fsrsParameters` 是同一个实例：保持率建议必须与排期说同一件事，否则同一进程里
     * 会出现两个数字。这里收一个值而不是去读设置存储，正是为了让它**不可能**再分叉。
     */
    private val fsrsParameters: DoubleArray,
    private val clock: Clock,
    private val learnerSnapshot: suspend () -> LearnerSnapshot,
) {

    suspend fun evaluateSchedulingModels(): SchedulingEvaluationReport? {
        // 与 harness 同一口径：讲题判定行不计入可评估样本，因此只做过讲题判定复习的
        // 学习者走"没有数据"的返回，而不是撞进 evaluate 的空集要求。
        val samples = fittableReviewSamples(reviewLogSink.reviewSamples())
        if (samples.isEmpty()) return null
        val eligible = samples.groupBy(ReviewSample::practiceUnitId).values.any { it.size >= 2 }
        if (!eligible) return null
        return SchedulingEvaluationHarness.evaluate(samples)
    }


    suspend fun sourceCalibrations(): List<SourceCalibration> =
        reviewLogSink.sourceCalibrations()


    suspend fun plannedReasonCalibrations(): List<PlannedReasonCalibration> =
        SchedulingEvaluationHarness.calibratePlannedReasons(reviewLogSink.reviewSamples())


    suspend fun chatEvidenceGateCalibration(): ChatEvidenceGateCalibration.GateCalibrationReport? {
        val acceptedTotal = database.countAcceptedChatEvidenceSince(
            learnerId = learnerId,
            sinceEpochMillis = 0,
        )
        val rejected = database.countRejectedChatEvidenceByReason(learnerId)
        // 30 天观察窗的每小时分布（校准看近期行为，不看全部历史）。
        val hourWindowStart = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val perHour = database.countAcceptedChatEvidencePerHour(learnerId, hourWindowStart)
        val observation = ChatEvidenceGateCalibration.GateObservation(
            acceptedCount = acceptedTotal,
            rejectedByReason = rejected.associate { it.reason to it.count },
            acceptedPerHour = perHour.associate { it.hourBucket to it.count },
        )
        if (observation.totalObservations == 0) return null
        return ChatEvidenceGateCalibration.calibrate(observation)
    }


    suspend fun suggestedReminderMinute(): Int? = reviewLogSink.suggestedReminderMinute()


    suspend fun optimizeSchedulingParameters(): FsrsParameterOptimizer.Result? {
        val store = requireNotNull(schedulingSettingsStore) {
            "Parameter optimization requires a scheduling settings store"
        }
        // Phone-safe bound: numeric-gradient fitting replays the history many
        // times, so optimization runs on the most recent window only.
        val samples = reviewLogSink.reviewSamples().takeLast(MAX_OPTIMIZE_SAMPLES)
        // **现行参数就是这次拟合要挑战的那一组**：写回只在候选于同一段留出尾段上严格优于它时发生
        // （审计 S-10）。原先这里只判 `mode != INSUFFICIENT_DATA` 就无条件写回，而拟合的择优基准
        // 是出厂默认值——于是"比出厂默认好"就够写回，一次新拟合可以把已经更好的参数换掉。
        val incumbent = store.optimizedParameters.first() ?: FsrsScheduleMath.DEFAULT_PARAMETERS
        val result = FsrsParameterOptimizer.optimize(samples = samples, incumbent = incumbent)
        if (result.mode == FsrsParameterOptimizer.Mode.INSUFFICIENT_DATA) return null
        // 只在**被采纳**时写：被拒的那次返回的就是 incumbent 本身，
        // 照写一遍会白白产生一次 DataStore 写入与一次流发射。
        if (result.adopted) {
            store.setOptimizedParameters(result.parameters)
        }
        return result
    }


    /**
     * Experimental CMRR-style desired-retention recommendation over the learner's current
     * memory states (研究 2026-09-09 §5). Null when too few cards carry memory to simulate
     * anything meaningful.
     *
     * **用哪一组参数：与排期/曲线同一组（审计 N-30）。** 原先这里读
     * `schedulingSettingsStore.optimizedParameters.first()`——那是这族参数的**第三个来源**：
     * repository 构造得更早，把当时的值解析进 `fsrsParameters`，而启动期那次
     * `optimizeSchedulingParameters()` 会往设置存储里写回新的一组。于是从"拟合写回"到
     * "下次启动"这段窗口里，**排期用旧那组、这条建议用新那组**，同一进程内两个数字。
     *
     * 收成"读排期实际在用的那一组"，而不是"读最新的那一组"：后者要让排期也跟着换，
     * 那与 spec §2.20「读在构造时、一次会话内模型稳定」直接冲突——而这里给出的只是**建议**
     * （用户据此设目标保持率），滞后一次拟合的代价远小于会话中途换模型。两组参数在下次启动
     * 自动对齐。`recommendedDesiredRetentionFollowsTheParametersSchedulingUses` 钉住这条。
     */
    suspend fun recommendedDesiredRetention(): OptimalRetention.Recommendation? {
        val snapshot = learnerSnapshot()
        val cards = snapshot.problemMemoryStates.values.map { memory ->
            OptimalRetention.Card(
                stabilityDays = memory.stabilityDays,
                difficulty = memory.difficulty,
            )
        }
        return OptimalRetention.recommend(cards, fsrsParameters)
    }


    /**
     * Calibration report for one model generation, computed over resolved
     * prediction/outcome pairs persisted by the audit loop (audit §6.3).
     */
    suspend fun calibrationReport(modelVersion: LearningModelVersion): CalibrationReport {
        val resolved = database.readResolvedStudentModelPredictions(
            modelId = modelVersion.modelId,
            modelVersion = modelVersion.version,
        )
        return CalibrationReportBuilder.build(
            modelVersion = modelVersion,
            resolved = resolved.map { row ->
                CalibrationInput(
                    predictedScore = row.predictedScore,
                    conservativeScore = row.conservativeScore,
                    wasIndependentCorrect = row.wasIndependentCorrect,
                )
            },
            totalPredictions = resolved.size,
            generatedAtEpochMillis = clock.millis(),
        )
    }


    suspend fun calibrationReport(): CalibrationReport =
        calibrationReport(predictionAuditService.modelVersion)


    private companion object {
        const val MAX_OPTIMIZE_SAMPLES = 20_000
    }
}
