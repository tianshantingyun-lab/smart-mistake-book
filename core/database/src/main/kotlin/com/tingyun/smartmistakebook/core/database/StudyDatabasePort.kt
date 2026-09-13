package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.port.AttemptWritePort
import com.tingyun.smartmistakebook.core.database.port.BackupPort
import com.tingyun.smartmistakebook.core.database.port.BatchImportReadPort
import com.tingyun.smartmistakebook.core.database.port.BatchImportWritePort
import com.tingyun.smartmistakebook.core.database.port.CaptureReadPort
import com.tingyun.smartmistakebook.core.database.port.CaptureWritePort
import com.tingyun.smartmistakebook.core.database.port.DraftReadPort
import com.tingyun.smartmistakebook.core.database.port.DraftWritePort
import com.tingyun.smartmistakebook.core.database.port.KnowledgeQuestionLatticePort
import com.tingyun.smartmistakebook.core.database.port.KnowledgeReadPort
import com.tingyun.smartmistakebook.core.database.port.KnowledgeWritePort
import com.tingyun.smartmistakebook.core.database.port.LearningLedgerPort
import com.tingyun.smartmistakebook.core.database.port.LearningProjectionPort
import com.tingyun.smartmistakebook.core.database.port.LibraryReadPort
import com.tingyun.smartmistakebook.core.database.port.MasteryAdvisoryPort
import com.tingyun.smartmistakebook.core.database.port.MistakeReadPort
import com.tingyun.smartmistakebook.core.database.port.OrganizationReadPort
import com.tingyun.smartmistakebook.core.database.port.OrganizationWritePort
import com.tingyun.smartmistakebook.core.database.port.PredictionAuditPort
import com.tingyun.smartmistakebook.core.database.port.ReviewReadPort
import com.tingyun.smartmistakebook.core.database.port.ReviewWritePort
import com.tingyun.smartmistakebook.core.database.port.SeedAssessmentPort
import com.tingyun.smartmistakebook.core.database.port.SplitImportPort
import com.tingyun.smartmistakebook.core.database.port.TutorAnswerExposurePort
import com.tingyun.smartmistakebook.core.database.port.TutorReadPort
import com.tingyun.smartmistakebook.core.database.port.TutorSessionPort
import com.tingyun.smartmistakebook.core.database.port.TutorWritePort
import com.tingyun.smartmistakebook.core.database.port.VisualInteractionPort

const val MAX_REVIEW_COMPLETION_HISTORY_DAYS = 1_500
const val MAX_KNOWLEDGE_RECALL_CANDIDATES = 512

class AttemptIdempotencyConflictException(submissionId: String) :
    IllegalStateException("submissionId $submissionId was already used for a different payload")

class AssessmentSequenceConflictException(message: String) : IllegalStateException(message)

class ImmutablePayloadConflictException(entityType: String, entityId: String) :
    IllegalStateException("$entityType $entityId already exists with a different payload")

class ProblemOrganizationAuthorityConflictException(problemRevisionId: String) :
    IllegalStateException("A user correction already owns organization for $problemRevisionId")

class ProjectionCasConflictException(message: String) : IllegalStateException(message)

class LearningLedgerIntegrityException(message: String) : IllegalStateException(message)

/**
 * 账本大到无法一次全量重放。
 *
 * 与 [ProjectionCasConflictException] **不是一族**，也**不可重试**：CAS 冲突的语义是
 * 「检查点被别的写者改过，重读一次就能继续」，而这条的语义是「账本本身超出设计能重放的规模」，
 * 重试同一份账本只会得到同一个结果。两者合并会让调用方无法只在其中一个上做重试。
 *
 * 抛出时**旧检查点必须原样保留**（不提交部分结果）——恢复路径靠它成立
 * （见 `docs/adr/0003-replay-horizon.md`：出路是重放地平线，不是调大上界）。
 */
class ProjectionReplayLimitExceededException(message: String) : IllegalStateException(message)

/**
 * 一次排空的步数预算用完了，但**积压还在**。
 *
 * 与 [ProjectionCasConflictException] 同样**不是一族**——CAS 冲突的语义是「检查点被别的写者
 * 改过，重读一次就能继续」，而这条的语义是「这一次排空走得不够远」。把两者合并，调用方就
 * 无法只在其中一个上做重试，也无法在日志里分辨"有人在别的线程写"与"积压太多"这两种
 * 完全不同的现场（审计 N-06 的原始错法：64 次提交已经落库、检查点在本次调用里根本没变过，
 * 报出来的却是一句"检查点在排空途中被别的写者改过"）。
 *
 * **与 [ProjectionReplayLimitExceededException] 的关键区别是它可重试**：上界那条的输入是
 * 账本总条数，重试同一份账本只会得到同一个结果；这条的输入是"这一次走了多少步"，
 * 而每次提交都是独立事务——**已经完成的工作已经落库**，下一次调用从那个检查点接着走。
 * 抛出时因此**不**保留旧检查点（已经推进的部分就是要保留的），这与上界那条的承诺正相反。
 */
class ProjectionDrainBudgetExhaustedException(message: String) : IllegalStateException(message)

class ProblemDraftEditWorkspaceConflictException(message: String) : IllegalStateException(message)

class ProblemDraftEditWorkspaceIntegrityException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class DatabaseContractViolationException(message: String) : IllegalArgumentException(message)

/**
 * The whole persistence contract. Method groups live in the per-aggregate
 * interfaces under core.database.port; command/record types live in
 * [StudyDatabaseRecords]; stable SQLite strings live in [StudyDbValue].
 */
interface StudyDatabasePort : AutoCloseable, ModelTaskDatabasePort,
    LibraryReadPort, TutorReadPort, TutorWritePort,
    CaptureReadPort, CaptureWritePort, KnowledgeReadPort, KnowledgeWritePort,
    ReviewReadPort, MistakeReadPort, BackupPort, BatchImportReadPort, BatchImportWritePort,
    DraftReadPort, OrganizationReadPort,
    LearningLedgerPort, PredictionAuditPort, VisualInteractionPort, MasteryAdvisoryPort,
    KnowledgeQuestionLatticePort,
    DraftWritePort, TutorSessionPort, TutorAnswerExposurePort,
    SeedAssessmentPort, AttemptWritePort, OrganizationWritePort,
    LearningProjectionPort, ReviewWritePort, SplitImportPort {
}
