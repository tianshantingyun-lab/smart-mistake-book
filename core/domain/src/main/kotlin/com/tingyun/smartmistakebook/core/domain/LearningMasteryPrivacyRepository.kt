package com.tingyun.smartmistakebook.core.domain

data class LearningMasteryEraseOutcome(
    val erasedAtEpochMillis: Long,
) {
    init {
        require(erasedAtEpochMillis >= 0L) {
            "Learning mastery erase time must not be negative"
        }
    }
}

fun interface LearningMasteryPrivacyRepository {
    suspend fun eraseAllLearningData(): LearningMasteryEraseOutcome
}
