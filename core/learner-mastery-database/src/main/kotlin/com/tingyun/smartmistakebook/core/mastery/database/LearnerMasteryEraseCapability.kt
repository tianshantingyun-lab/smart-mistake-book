package com.tingyun.smartmistakebook.core.mastery.database

fun interface LearnerMasteryEraseCapability {
    suspend fun eraseAll(): LearnerMasteryEraseResult
}
