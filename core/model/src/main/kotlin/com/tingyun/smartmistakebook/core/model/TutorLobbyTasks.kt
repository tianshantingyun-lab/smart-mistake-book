package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

@Serializable
enum class TutorLobbyVisualKind {
    DIAGRAM,
    ANIMATION,
    THREE_DIMENSIONAL,
    VISUALIZATION,
}

/** Persisted local intent; it starts visual handling even when the text model omits a field. */
@Serializable
data class TutorLobbyVisualRequest(
    val kind: TutorLobbyVisualKind,
    val focusMarkdown: String,
) {
    init {
        focusMarkdown.requireTutorMarkdown(
            "Tutor lobby visual focus",
            CurrentTutorInteractionPolicy.MAX_VISUAL_FOCUS_CHARS,
        )
    }
}

/**
 * Minimal host policy that may leave the device for one Tutor Lobby turn.
 *
 * It intentionally contains no conversation id, learner id, answer, evidence, database key, or
 * proof. The egress manifest must authorize this data class before a provider may serialize it.
 */
@Serializable
data class CurrentTutorInteractionPolicy(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val choiceInteractionAuthorized: Boolean,
    @Serializable(with = SortedTutorVisualTargetIdSetSerializer::class)
    val allowedVisualTargetIds: Set<String>,
    val explicitVisualRequest: TutorLobbyVisualRequest?,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported current tutor interaction policy schema"
        }
        require(modeVersion in 0..MAX_MODE_VERSION) {
            "Tutor interaction policy mode version exceeds budget"
        }
        require(allowedVisualTargetIds.size <= MAX_VISUAL_TARGETS) {
            "Tutor interaction policy contains too many visual targets"
        }
        require(
            allowedVisualTargetIds.sumOf { targetId -> targetId.length } <=
                MAX_VISUAL_TARGET_ID_CHARS_TOTAL,
        ) {
            "Tutor interaction policy visual target ids exceed their total budget"
        }
        allowedVisualTargetIds.forEach { targetId -> targetId.requireTutorPolicyTargetId() }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_MODE_VERSION = 2_147_483_647L
        const val MAX_VISUAL_TARGETS = 32
        const val MAX_VISUAL_TARGET_ID_CHARS = 64
        const val MAX_VISUAL_TARGET_ID_CHARS_TOTAL = 1_024
        const val MAX_VISUAL_FOCUS_CHARS = 512
    }
}

/** The sole wire encoder for the manifest-authorized Tutor Lobby policy block. */
object CurrentTutorInteractionPolicyWire {
    private val codec = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(policy: CurrentTutorInteractionPolicy): String =
        codec.encodeToString(CurrentTutorInteractionPolicy.serializer(), policy)
}

/**
 * A bounded free-text entry on the Tutor home screen.
 *
 * It deliberately carries no question document, learning ledger, or write authority. The model
 * may understand the message and request one of two local reads, but local policy remains the
 * authority and free text never becomes learning evidence.
 */
@Serializable
@SerialName("tutor_lobby")
data class TutorLobbyInput(
    val conversationId: String,
    val messageOrdinal: Int,
    val studentMessage: String,
    val priorMessages: List<TutorChatHistoryEntry> = emptyList(),
    val explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
    val modeVersion: Long = 0,
    val explicitVisualRequest: TutorLobbyVisualRequest? = null,
    /** Authorizes only the choice shape; model-authored prompt and labels remain uncertified. */
    val choiceInteractionAuthorized: Boolean = false,
    /** Authorizes only local target identity, never model-authored prompt or visual semantics. */
    @Serializable(with = SortedTutorVisualTargetIdSetSerializer::class)
    val allowedVisualTargetIds: Set<String> = emptySet(),
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.TUTOR_LOBBY

    override val subjectId: String
        get() = conversationId

    init {
        conversationId.requireSafeModelText(
            "Tutor lobby conversation id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(messageOrdinal > 0) { "Tutor lobby message ordinal must be positive" }
        toCurrentTutorInteractionPolicy()
        studentMessage.requireSafeTutorStudentMessage(
            "Tutor lobby student message",
            TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS,
        )
        require(priorMessages.size <= TutorRespondInput.MAX_PRIOR_MESSAGES) {
            "Tutor lobby contains too many prior messages"
        }
        require(
            priorMessages.sumOf { message ->
                message.studentMessage.length + message.assistantMarkdown.length
            } <= TutorRespondInput.MAX_PRIOR_MESSAGE_CHARS,
        ) { "Tutor lobby prior messages exceed their text budget" }
    }

    companion object {
        const val MAX_STUDENT_MESSAGE_CHARS = TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS
        const val MAX_PRIOR_MESSAGES = TutorRespondInput.MAX_PRIOR_MESSAGES
        const val MAX_PRIOR_MESSAGE_CHARS = TutorRespondInput.MAX_PRIOR_MESSAGE_CHARS
        const val MAX_VISUAL_TARGETS = CurrentTutorInteractionPolicy.MAX_VISUAL_TARGETS
    }
}

/** Returns the only policy-shaped Tutor Lobby payload that an egress manifest may disclose. */
fun TutorLobbyInput.toCurrentTutorInteractionPolicy() = CurrentTutorInteractionPolicy(
    explanationMode = explanationMode,
    modeVersion = modeVersion,
    choiceInteractionAuthorized = choiceInteractionAuthorized,
    allowedVisualTargetIds = allowedVisualTargetIds,
    explicitVisualRequest = explicitVisualRequest,
)

internal fun TutorLobbyInput.hasLegacyDirectInteractionPolicy(): Boolean =
    explanationMode == TutorExplanationMode.DIRECT &&
        modeVersion == 0L &&
        !choiceInteractionAuthorized &&
        allowedVisualTargetIds.isEmpty() &&
        explicitVisualRequest == null

/** A persistable response that cannot claim or request a local write. */
@Serializable
@SerialName("tutor_lobby_output")
data class TutorLobbyOutput(
    val conversationId: String,
    val messageOrdinal: Int,
    val messageMarkdown: String,
    val intentDecision: TutorIntentDecision = TutorIntentDecision.ambiguousDefault(),
    val explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
    val modeVersion: Long = 0,
    val responseIntent: TutorResponseIntent = TutorResponseIntent.EXPLAIN,
    val interactionDirective: TutorInteractionDirective? = null,
    val modelVersion: String,
) : ModelTaskOutput {
    init {
        conversationId.requireSafeModelText(
            "Tutor lobby output conversation id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        require(messageOrdinal > 0) { "Tutor lobby output message ordinal must be positive" }
        require(modeVersion >= 0) { "Tutor lobby output mode version must not be negative" }
        messageMarkdown.requireTutorMarkdown(
            "Tutor lobby response",
            TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS,
        )
        require(intentDecision.requestedLocalCapability in ALLOWED_LOCAL_CAPABILITIES) {
            "Tutor lobby cannot request a local write or current-question action"
        }
        require(
            (responseIntent == TutorResponseIntent.ASK) == (interactionDirective != null),
        ) { "Tutor lobby interaction intent and directive must agree" }
        require(
            explanationMode != TutorExplanationMode.DIRECT ||
                responseIntent == TutorResponseIntent.EXPLAIN && interactionDirective == null,
        ) { "Direct tutor lobby output cannot contain an interaction" }
        require(interactionDirective != TutorInteractionDirective.Continue) {
            "Tutor lobby cannot add a continue gate"
        }
        modelVersion.requireSafeModelText(
            "Tutor lobby model version",
            MAX_MODEL_VERSION_CHARS,
            false,
        )
    }

    companion object {
        val ALLOWED_LOCAL_CAPABILITIES = setOf(
            TutorRequestedLocalCapability.NONE,
            TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
            TutorRequestedLocalCapability.READ_LEARNING_PROGRESS,
        )
    }
}

/** Deterministic egress boundary applied again by completion validation and the provider parser. */
fun TutorLobbyOutput.locallyConstrainedFor(input: TutorLobbyInput): TutorLobbyOutput? {
    if (
        conversationId != input.conversationId ||
        messageOrdinal != input.messageOrdinal ||
        explanationMode != input.explanationMode ||
        modeVersion != input.modeVersion
    ) {
        return null
    }
    if (intentDecision.requestedLocalCapability !in TutorLobbyOutput.ALLOWED_LOCAL_CAPABILITIES) {
        return null
    }
    if (input.explanationMode == TutorExplanationMode.DIRECT) {
        return takeIf {
            responseIntent == TutorResponseIntent.EXPLAIN &&
                interactionDirective == null &&
                !messageMarkdown.containsTutorQuestionMark() &&
                !messageMarkdown.containsDirectedTutorLobbyInteraction()
        }
    }
    val directive = interactionDirective
        ?: return takeIf {
            responseIntent == TutorResponseIntent.EXPLAIN &&
                !messageMarkdown.containsTutorQuestionMark() &&
                !messageMarkdown.containsDirectedTutorLobbyInteraction()
        }
    if (
        responseIntent != TutorResponseIntent.ASK ||
        intentDecision.intent != TutorMessageIntent.CURRENT_QUESTION_HELP ||
        intentDecision.confidence < MIN_GUIDED_CURRENT_QUESTION_CONFIDENCE ||
        intentDecision.requestedLocalCapability != TutorRequestedLocalCapability.NONE
    ) {
        return null
    }
    val localInteraction = directive.locallyAuthoredGuidedFreeResponse() ?: return null
    return copy(
        messageMarkdown = GUIDED_INTERACTION_MESSAGE,
        interactionDirective = localInteraction,
    )
}

private const val MIN_GUIDED_CURRENT_QUESTION_CONFIDENCE = 0.75

/**
 * Rejects student-facing interaction requests that can be disguised as an explanation simply by
 * replacing the question mark with a full stop. This is deliberately a small sentence-shape
 * classifier: it looks for an addressed student plus an interrogative or response action, and for
 * unambiguously imperative response actions at the start of a clause. It does not reject ordinary
 * teaching narration such as “先求导，再判断符号” or “判断依据是……”.
 */
private fun String.containsDirectedTutorLobbyInteraction(): Boolean =
    lineSequence()
        .flatMap { line ->
            line.split('。', '！', '？', '?', '!', '；', ';').asSequence()
        }
        .map { clause ->
            clause
                .replace(TUTOR_LOBBY_LEADING_PRESENTATION_MARKER, "")
                .replace(TUTOR_LOBBY_INLINE_WHITESPACE, "")
        }
        .filter(String::isNotEmpty)
        .any { clause ->
            TUTOR_LOBBY_ADDRESSED_QUESTION.containsMatchIn(clause) ||
                TUTOR_LOBBY_ADDRESSED_RESPONSE_REQUEST.containsMatchIn(clause) ||
                (
                    TUTOR_LOBBY_BARE_RESPONSE_REQUEST.containsMatchIn(clause) &&
                        !TUTOR_LOBBY_NARRATIVE_RELATION_OPENING.containsMatchIn(clause)
                ) ||
                TUTOR_LOBBY_ACTION_SHAPED_QUESTION.containsMatchIn(clause)
        }

private const val TUTOR_LOBBY_INTERROGATIVE =
    "(?:哪(?:个|一|些|里|项|步|种)?|什么|为什么|为何|怎么|如何|是否|能否|会不会|有没有|对不对)"

private const val TUTOR_LOBBY_RESPONSE_ACTION =
    "(?:回答|作答|选择|选出|判断|说出|写出|填写|填入|点击|指出|告诉我)"

private const val TUTOR_LOBBY_UNAMBIGUOUS_RESPONSE_ACTION =
    "(?:回答|作答|选择|选出|说出|写出|填写|填入|点击|指出|告诉我)"

private val TUTOR_LOBBY_LEADING_PRESENTATION_MARKER =
    Regex("""^(?:(?:\d{1,2}[.、)])|[（(]\d{1,2}[）)]|[#>*_\-•])+""")
private val TUTOR_LOBBY_INLINE_WHITESPACE = Regex("""\s+""")

private val TUTOR_LOBBY_NARRATIVE_RELATION_OPENING = Regex(
    """^(?:判断|选择)(?:依据|结果|标准|方法|条件|过程|步骤)(?:是|为|如下|来自)""",
)

private val TUTOR_LOBBY_ADDRESSED_QUESTION = Regex(
    """(?:^|[，,:：])(?:请你|你)[^，,:：]{0,48}$TUTOR_LOBBY_INTERROGATIVE""",
)

private val TUTOR_LOBBY_ADDRESSED_RESPONSE_REQUEST = Regex(
    """(?:^|[，,:：])(?:请(?:你)?|你)(?:先|再|现在|来|试着|尝试|需要|应该|可以|能|会|直接|认真|仔细){0,4}$TUTOR_LOBBY_RESPONSE_ACTION""",
)

private val TUTOR_LOBBY_BARE_RESPONSE_REQUEST = Regex(
    """^(?:请(?:你)?|先|再|现在|直接|认真|仔细){0,3}$TUTOR_LOBBY_UNAMBIGUOUS_RESPONSE_ACTION""",
)

private val TUTOR_LOBBY_ACTION_SHAPED_QUESTION = Regex(
    """^(?:请(?:你)?|先|再|现在|直接){0,3}$TUTOR_LOBBY_RESPONSE_ACTION[^，,:：]{0,48}$TUTOR_LOBBY_INTERROGATIVE""",
)

private fun String.requireTutorPolicyTargetId() {
    require(length in 1..CurrentTutorInteractionPolicy.MAX_VISUAL_TARGET_ID_CHARS) {
        "Tutor interaction policy visual target id exceeds budget"
    }
    require(all { character ->
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_' ||
            character == '.' ||
            character == ':'
    }) { "Tutor interaction policy visual target id has an invalid format" }
    require(first().isAsciiLetterOrDigit()) {
        "Tutor interaction policy visual target id must start with a letter or digit"
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

internal object SortedTutorVisualTargetIdSetSerializer : KSerializer<Set<String>> {
    private val delegate = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Set<String>) {
        encoder.encodeSerializableValue(delegate, value.sorted())
    }

    override fun deserialize(decoder: Decoder): Set<String> {
        val decoded = decoder.decodeSerializableValue(delegate)
        require(decoded.distinct().size == decoded.size) {
            "Tutor interaction policy visual target ids must be unique"
        }
        return decoded.toCollection(linkedSetOf<String>())
    }
}
