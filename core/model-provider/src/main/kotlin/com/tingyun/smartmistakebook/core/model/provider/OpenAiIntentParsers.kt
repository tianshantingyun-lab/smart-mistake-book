package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorInteractionChoice
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal fun JsonObject.toTutorIntentDecision(): TutorIntentDecision {
    requireOnlyKeys(TUTOR_INTENT_WIRE_KEYS)
    return TutorIntentDecision(
        intent = enumValue(requiredString("intent")),
        confidence = optionalDouble("confidence") ?: throw InvalidModelResponseException(),
        explicitActionRequest = requiredBoolean("explicitActionRequest"),
        memoryPreference = enumValue(requiredString("memoryPreference")),
        requestedLocalCapability = enumValue(requiredString("requestedLocalCapability")),
        lookupTerms = optionalArray("lookupTerms").map(JsonElement::requiredPrimitiveString),
    )
}

internal fun JsonObject.toTutorInteractionDirective(): TutorInteractionDirective =
    when (requiredString("kind")) {
        "CONTINUE" -> {
            requireOnlyKeys(setOf("kind"))
            TutorInteractionDirective.Continue
        }
        "FREE_RESPONSE" -> {
            requireOnlyKeys(setOf("kind", "promptMarkdown"))
            TutorInteractionDirective.FreeResponse(requiredString("promptMarkdown"))
        }
        "CHOICES" -> {
            requireOnlyKeys(setOf("kind", "promptMarkdown", "choices"))
            TutorInteractionDirective.Choices(
                promptMarkdown = requiredString("promptMarkdown"),
                choices = array("choices").map { element ->
                    element.objectValue().let { choice ->
                        choice.requireOnlyKeys(TUTOR_INTERACTION_CHOICE_WIRE_KEYS)
                        TutorInteractionChoice(
                            id = choice.requiredString("id"),
                            labelMarkdown = choice.requiredString("labelMarkdown"),
                        )
                    }
                },
            )
        }
        "VISUAL_TARGET" -> {
            requireOnlyKeys(setOf("kind", "promptMarkdown", "targetId"))
            TutorInteractionDirective.VisualTarget(
                promptMarkdown = requiredString("promptMarkdown"),
                targetId = requiredString("targetId"),
            )
        }
        else -> throw InvalidModelResponseException()
    }
