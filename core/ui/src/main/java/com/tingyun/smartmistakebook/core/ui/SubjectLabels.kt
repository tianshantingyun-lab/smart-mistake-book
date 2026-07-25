package com.tingyun.smartmistakebook.core.ui

import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.util.Locale

fun SubjectKind.studentLabel(): String = when (this) {
    SubjectKind.CHINESE -> "语文"
    SubjectKind.MATH -> "数学"
    SubjectKind.ENGLISH -> "英语"
    SubjectKind.PHYSICS -> "物理"
    SubjectKind.CHEMISTRY -> "化学"
    SubjectKind.BIOLOGY -> "生物"
    SubjectKind.POLITICS -> "思想政治"
    SubjectKind.HISTORY -> "历史"
    SubjectKind.GEOGRAPHY -> "地理"
    SubjectKind.GENERAL -> "综合"
}

fun String.studentSubjectLabel(): String = runCatching {
    SubjectKind.valueOf(trim().uppercase(Locale.ROOT))
}.getOrNull()?.studentLabel() ?: this
