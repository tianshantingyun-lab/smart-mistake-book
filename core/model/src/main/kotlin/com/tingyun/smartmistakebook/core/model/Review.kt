package com.tingyun.smartmistakebook.core.model

enum class ReviewReason {
    DUE_RECALL_RISK,
    WEAK_KNOWLEDGE,
    RECENT_LAPSE,
    REPEATED_MISTAKE,
    EXAM_PRIORITY,
    NEWLY_ADDED,
    CALIBRATION_CHECK,
    MISSING_KNOWLEDGE_EVIDENCE,
    CONFLICTED_KNOWLEDGE,
    STALE_KNOWLEDGE,
    CLOCK_ANOMALY,
    LONG_WAITING,
    PREREQ_GAP,
    CONFUSABLE_PAIR,
    GRADUATED_MAINTENANCE,
    AVOIDANCE_SIGNAL,
}

enum class ReviewDifficultyBand {
    EASY,
    MEDIUM,
    HARD,
}

data class ReviewQueueItem(
    val queueItemId: String,
    val practiceUnitId: String,
    val knowledgeNodeIds: Set<String>,
    val itemFamilyId: String,
    val sourceBundleId: String?,
    val reasons: Set<ReviewReason>,
    val priorityScore: Double,
    val difficultyBand: ReviewDifficultyBand,
    val estimatedDurationSeconds: Int,
    val scheduledOrder: Int,
    val dueAtEpochMillis: Long?,
) {
    init {
        require(queueItemId.isNotBlank()) { "Queue-item id must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(knowledgeNodeIds.none(String::isBlank)) { "Knowledge-node ids must not be blank" }
        require(itemFamilyId.isNotBlank()) { "Item family id must not be blank" }
        require(sourceBundleId == null || sourceBundleId.isNotBlank()) {
            "Source bundle id must not be blank when provided"
        }
        require(reasons.isNotEmpty()) { "A review item must explain why it was scheduled" }
        require(priorityScore.isFinite() && priorityScore >= 0.0) {
            "Priority score must not be negative"
        }
        require(estimatedDurationSeconds > 0) { "Estimated duration must be positive" }
        require(scheduledOrder >= 0) { "Scheduled order must not be negative" }
        require(dueAtEpochMillis == null || dueAtEpochMillis >= 0) {
            "Due time must not be negative"
        }
    }
}

data class ReviewPlan(
    val planId: String,
    val planFingerprint: String,
    val learnerId: String,
    val localDayEpochDay: Long,
    val timeZoneId: String,
    val generatedAtEpochMillis: Long,
    val plannerVersion: String,
    val projectionCheckpoint: ProjectionCheckpoint,
    val timeBudgetSeconds: Int,
    val queueItems: List<ReviewQueueItem>,
) {
    init {
        require(planId.isNotBlank()) { "Review-plan id must not be blank" }
        require(planFingerprint.isNotBlank()) { "Review-plan fingerprint must not be blank" }
        require(planId == "plan-$planFingerprint") {
            "Review-plan id must be derived from its canonical fingerprint"
        }
        require(learnerId.isNotBlank()) { "Learner id must not be blank" }
        require(timeZoneId.isNotBlank()) { "Review-plan time-zone id must not be blank" }
        require(generatedAtEpochMillis >= 0) { "Plan time must not be negative" }
        require(plannerVersion.isNotBlank()) { "Planner version must not be blank" }
        require(timeBudgetSeconds >= 0) { "Time budget must not be negative" }
        require(queueItems.map(ReviewQueueItem::queueItemId).distinct().size == queueItems.size) {
            "Review queue-item ids must be unique"
        }
        require(queueItems.map(ReviewQueueItem::scheduledOrder) == queueItems.indices.toList()) {
            "Review queue must use contiguous scheduled order"
        }
        require(totalEstimatedDurationSeconds <= timeBudgetSeconds) {
            "Review queue must fit its time budget"
        }
    }

    val totalEstimatedDurationSeconds: Int
        get() = queueItems.sumOf(ReviewQueueItem::estimatedDurationSeconds)
}

enum class ReviewSessionStatus {
    NOT_STARTED,
    ACTIVE,
    COMPLETED,
    ABANDONED,
}

data class ReviewSession(
    val sessionId: String,
    val planId: String,
    val queueItemIds: List<String>,
    val completedAttemptIds: List<String>,
    val status: ReviewSessionStatus,
    val createdAtEpochMillis: Long,
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "Review-session id must not be blank" }
        require(planId.isNotBlank()) { "Review-plan id must not be blank" }
        require(queueItemIds.none(String::isBlank)) { "Queue-item ids must not be blank" }
        require(queueItemIds.distinct().size == queueItemIds.size) {
            "Queue-item ids must be unique"
        }
        require(completedAttemptIds.none(String::isBlank)) { "Attempt ids must not be blank" }
        require(completedAttemptIds.distinct().size == completedAttemptIds.size) {
            "Completed attempt ids must be unique"
        }
        require(createdAtEpochMillis >= 0) { "Session creation time must not be negative" }
        require(startedAtEpochMillis == null || startedAtEpochMillis >= createdAtEpochMillis) {
            "Session start must not precede creation"
        }
        require(endedAtEpochMillis == null || startedAtEpochMillis != null) {
            "A session cannot end before it starts"
        }
        require(
            endedAtEpochMillis == null ||
                endedAtEpochMillis >= requireNotNull(startedAtEpochMillis),
        ) { "Session end must not precede start" }
        when (status) {
            ReviewSessionStatus.NOT_STARTED -> require(
                startedAtEpochMillis == null && endedAtEpochMillis == null,
            ) { "A not-started session cannot have start or end times" }

            ReviewSessionStatus.ACTIVE -> require(
                startedAtEpochMillis != null && endedAtEpochMillis == null,
            ) { "An active session needs a start time and no end time" }

            ReviewSessionStatus.COMPLETED,
            ReviewSessionStatus.ABANDONED,
            -> require(startedAtEpochMillis != null && endedAtEpochMillis != null) {
                "A finished session needs start and end times"
            }
        }
    }
}
