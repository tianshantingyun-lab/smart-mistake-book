package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable

/** What the student's latest free-text message is primarily trying to do. */
@Serializable
enum class TutorMessageIntent {
    CURRENT_QUESTION_HELP,
    MISTAKE_NOTEBOOK_LOOKUP,
    LEARNING_PROGRESS_LOOKUP,
    APP_HELP_OR_SETTINGS,
    CASUAL_CONVERSATION,
    END_OR_PAUSE,
    AMBIGUOUS,
}

/**
 * A model may only request a narrower local persistence policy. It cannot grant itself write
 * permission or turn free text into learning evidence.
 */
@Serializable
enum class TutorMemoryPreference {
    UNCHANGED,
    BLOCK_LONG_TERM_WRITES_FOR_SESSION,
}

/** A bounded request that still has to pass the local authority policy before it can run. */
@Serializable
enum class TutorRequestedLocalCapability {
    NONE,
    READ_MISTAKE_NOTEBOOK,
    READ_LEARNING_PROGRESS,
    OFFER_SAVE_CURRENT_QUESTION,
    OFFER_END_WITHOUT_SAVE,
}

@Serializable
data class TutorIntentDecision(
    val intent: TutorMessageIntent,
    val confidence: Double,
    val explicitActionRequest: Boolean,
    val memoryPreference: TutorMemoryPreference,
    val requestedLocalCapability: TutorRequestedLocalCapability,
    val lookupTerms: List<String> = emptyList(),
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Tutor intent confidence must be between zero and one"
        }
        require(
            requestedLocalCapability == TutorRequestedLocalCapability.NONE ||
                explicitActionRequest,
        ) {
            "A tutor local capability requires an explicit student request"
        }
        require(requestedLocalCapability in intent.allowedCapabilities()) {
            "Tutor intent requested a capability outside its local boundary"
        }
        require(lookupTerms.size <= MAX_LOOKUP_TERMS) {
            "Tutor intent contains too many lookup terms"
        }
        require(
            lookupTerms.all { term ->
                term == term.trim() &&
                    term.length in 1..MAX_LOOKUP_TERM_CHARS &&
                    term.none(Char::isISOControl)
            } &&
                lookupTerms.distinctBy(String::lowercase).size == lookupTerms.size,
        ) {
            "Tutor intent lookup terms are invalid"
        }
        require(
            lookupTerms.isEmpty() ||
                requestedLocalCapability in lookupCapabilities,
        ) {
            "Tutor intent lookup terms require a bounded read"
        }
        if (intent == TutorMessageIntent.AMBIGUOUS) {
            require(requestedLocalCapability == TutorRequestedLocalCapability.NONE) {
                "An ambiguous tutor message cannot request a local action"
            }
        }
        if (memoryPreference == TutorMemoryPreference.BLOCK_LONG_TERM_WRITES_FOR_SESSION) {
            require(explicitActionRequest) {
                "Blocking long-term writes requires an explicit student request"
            }
        }
    }

    companion object {
        fun currentQuestionDefault() = TutorIntentDecision(
            intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
            confidence = 1.0,
            explicitActionRequest = false,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.NONE,
        )

        fun ambiguousDefault() = TutorIntentDecision(
            intent = TutorMessageIntent.AMBIGUOUS,
            confidence = 0.0,
            explicitActionRequest = false,
            memoryPreference = TutorMemoryPreference.UNCHANGED,
            requestedLocalCapability = TutorRequestedLocalCapability.NONE,
        )

        const val MAX_LOOKUP_TERMS = 6
        const val MAX_LOOKUP_TERM_CHARS = 24
    }
}

private val lookupCapabilities = setOf(
    TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
    TutorRequestedLocalCapability.READ_LEARNING_PROGRESS,
)

private fun TutorMessageIntent.allowedCapabilities(): Set<TutorRequestedLocalCapability> =
    when (this) {
        TutorMessageIntent.CURRENT_QUESTION_HELP -> setOf(
            TutorRequestedLocalCapability.NONE,
            TutorRequestedLocalCapability.OFFER_SAVE_CURRENT_QUESTION,
        )
        TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP -> setOf(
            TutorRequestedLocalCapability.NONE,
            TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
            TutorRequestedLocalCapability.OFFER_SAVE_CURRENT_QUESTION,
        )
        TutorMessageIntent.LEARNING_PROGRESS_LOOKUP -> setOf(
            TutorRequestedLocalCapability.NONE,
            TutorRequestedLocalCapability.READ_LEARNING_PROGRESS,
        )
        TutorMessageIntent.END_OR_PAUSE -> setOf(
            TutorRequestedLocalCapability.NONE,
            TutorRequestedLocalCapability.OFFER_END_WITHOUT_SAVE,
        )
        TutorMessageIntent.APP_HELP_OR_SETTINGS,
        TutorMessageIntent.CASUAL_CONVERSATION,
        TutorMessageIntent.AMBIGUOUS,
        -> setOf(TutorRequestedLocalCapability.NONE)
    }
