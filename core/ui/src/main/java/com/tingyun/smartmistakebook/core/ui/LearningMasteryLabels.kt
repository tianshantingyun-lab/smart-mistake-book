package com.tingyun.smartmistakebook.core.ui

import com.tingyun.smartmistakebook.core.domain.LearningMasteryStatus
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject

fun LearningMasteryStatus.studentLabel(): String = when (this) {
    LearningMasteryStatus.NOT_YET_LEARNED -> "还没学到"
    LearningMasteryStatus.GETTING_FAMILIAR -> "正在熟悉"
    LearningMasteryStatus.FAIRLY_STEADY -> "比较稳"
    LearningMasteryStatus.NEEDS_REINFORCEMENT -> "需要再巩固"
}

fun LearningMasterySubject.studentLabel(): String = when (this) {
    LearningMasterySubject.CHINESE -> "语文"
    LearningMasterySubject.MATHEMATICS -> "数学"
    LearningMasterySubject.ENGLISH -> "英语"
    LearningMasterySubject.PHYSICS -> "物理"
    LearningMasterySubject.CHEMISTRY -> "化学"
    LearningMasterySubject.BIOLOGY -> "生物"
    LearningMasterySubject.HISTORY -> "历史"
    LearningMasterySubject.GEOGRAPHY -> "地理"
    LearningMasterySubject.IDEOLOGY_AND_POLITICS -> "思想政治"
}
