package com.tingyun.smartmistakebook.core.database

/** Stable strings stored in SQLite. Values are append-only once released. */
object StudyDbValue {
    object KnowledgeRelationType {
        const val PREREQUISITE_OF = "PREREQUISITE_OF"
    }

    object KnowledgeGroundingStatus {
        const val PENDING = "PENDING"
        const val RESOLVED = "RESOLVED"
        const val DISMISSED = "DISMISSED"
    }

    object KnowledgeResearchReviewStatus {
        const val PENDING_REVIEW = "PENDING_REVIEW"
        const val APPROVED = "APPROVED"
        const val REJECTED = "REJECTED"
        const val APPLIED = "APPLIED"
    }

    object ProblemDraftStatus {
        const val EDITING = "EDITING"
        const val COMMITTED = "COMMITTED"
        const val ABANDONED = "ABANDONED"
    }

    object ProblemDraftAuthor {
        const val CAPTURE_IMPORT = "CAPTURE_IMPORT"
        const val LOCAL_OCR = "LOCAL_OCR"
        const val OPTIONAL_REMOTE_OCR = "OPTIONAL_REMOTE_OCR"
        const val USER = "USER"
    }

    object CaptureOrigin {
        const val LIBRARY = "LIBRARY"
        const val TUTOR = "TUTOR"
    }

    object SourceAssetType {
        const val CAMERA = "CAMERA"
        const val PHOTO_PICKER = "PHOTO_PICKER"
        const val TUTOR_ATTACHED = "TUTOR_ATTACHED"
    }

    object BatchImportStatus {
        const val PROCESSING = "PROCESSING"
        const val PAUSED = "PAUSED"
        const val COMPLETED = "COMPLETED"
    }

    object BatchImportPageStatus {
        const val QUEUED = "QUEUED"
        const val IMPORTING = "IMPORTING"
        const val READY = "READY"
        const val FAILED = "FAILED"
        const val SKIPPED = "SKIPPED"
    }

    object BatchImportBoundaryStatus {
        const val PENDING = "PENDING"
        const val CHECKING = "CHECKING"
        const val SAME_QUESTION = "SAME_QUESTION"
        const val NEXT_QUESTION = "NEXT_QUESTION"
        const val KEPT_SEPARATE = "KEPT_SEPARATE"
        const val FAILED = "FAILED"
    }

    /**
     * The page's optional split-recognition stage, tracked apart from [BatchImportPageStatus]
     * because it deliberately runs *after* the page is READY (intake must never be blocked by
     * the model). A crash between the page becoming READY and the attempt settling leaves the
     * page PENDING, which is the only durable signal that the attempt never finished — without
     * it the page silently degrades to a whole-page draft and nothing ever retries.
     */
    object BatchImportSplitStatus {
        const val PENDING = "PENDING"
        const val SETTLED = "SETTLED"
    }

    object SplitImportSourceKind {
        const val SINGLE_PAGE = "SINGLE_PAGE"
        const val BATCH = "BATCH"
        const val PDF = "PDF"
    }

    object SplitImportStatus {
        const val PREPARING = "PREPARING"
        const val READY = "READY"
        const val COMPLETED = "COMPLETED"
        const val ABANDONED = "ABANDONED"
    }

    object SplitImportConfirmState {
        const val PENDING = "PENDING"
        const val SAVED = "SAVED"
        const val TUTOR_SESSION = "TUTOR_SESSION"
        const val REJECTED = "REJECTED"
    }

    object ErrorBookStatus {
        const val ACTIVE = "ACTIVE"
        const val ARCHIVED = "ARCHIVED"
        const val TRASHED = "TRASHED"
    }

    object RelationType {
        const val SAME_KNOWLEDGE = "SAME_KNOWLEDGE"
        const val SAME_ERROR_PATTERN = "SAME_ERROR_PATTERN"
        const val VARIANT_OF = "VARIANT_OF"
        const val PREREQUISITE_OF = "PREREQUISITE_OF"
        const val SAME_SOURCE_BUNDLE = "SAME_SOURCE_BUNDLE"
        const val SHARES_STIMULUS = "SHARES_STIMULUS"
        const val CONTINUATION_OF = "CONTINUATION_OF"
        const val ANSWER_FOR = "ANSWER_FOR"
        const val SAME_FIGURE_PATTERN = "SAME_FIGURE_PATTERN"
        const val POSSIBLE_DUPLICATE = "POSSIBLE_DUPLICATE"
        const val DERIVED_FROM = "DERIVED_FROM"
    }

    object RelationStatus {
        const val ACTIVE = "ACTIVE"
        const val STALE = "STALE"
        const val REJECTED = "REJECTED"
    }

    object AssessmentEventType {
        const val PRESENTED = "PRESENTED"
        const val HINT_REVEALED = "HINT_REVEALED"
        const val ANSWER_REVEALED = "ANSWER_REVEALED"
        const val RESPONSE_SUBMITTED = "RESPONSE_SUBMITTED"
        const val CANCELLED = "CANCELLED"
    }

    object AssessmentEligibility {
        const val SESSION_ONLY = "SESSION_ONLY"
        const val ATTEMPT_ELIGIBLE = "ATTEMPT_ELIGIBLE"
        const val BLOCKED = "BLOCKED"
    }

    object ScoringMode {
        const val AUTO_VERIFIED = "AUTO_VERIFIED"
        const val USER_SELF_REPORT = "USER_SELF_REPORT"
        const val RUBRIC_ASSISTED = "RUBRIC_ASSISTED"
    }

    object VerificationStatus {
        const val VERIFIED = "VERIFIED"
        const val USER_ASSERTED = "USER_ASSERTED"
        const val UNKNOWN = "UNKNOWN"
    }

    object OutboxStatus {
        const val PENDING = "PENDING"
    }

    object ReviewStatus {
        const val PLANNED = "PLANNED"
        const val IN_PROGRESS = "IN_PROGRESS"
        const val COMPLETED = "COMPLETED"
        const val SKIPPED = "SKIPPED"
        const val CANCELLED = "CANCELLED"
    }
}
