package com.tingyun.smartmistakebook.core.model

/**
 * Stable owner of the purely local data set.
 *
 * Keeping one identifier across chat, saved mistakes, and mastery prevents separate UI entry
 * points from accidentally creating different local learners.
 */
const val LOCAL_LEARNER_ID = "learner:local"
