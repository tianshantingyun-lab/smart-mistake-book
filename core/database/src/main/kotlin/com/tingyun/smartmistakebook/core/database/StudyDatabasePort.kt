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
