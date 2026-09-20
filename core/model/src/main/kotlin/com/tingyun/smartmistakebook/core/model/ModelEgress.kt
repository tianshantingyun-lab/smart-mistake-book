package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable

/** Where a provider executes. External providers always require an exact disclosure manifest. */
@Serializable
enum class ModelExecutionLocation {
    LOCAL_NO_EGRESS,
    EXTERNAL_PROVIDER,
    UNAVAILABLE,
}

@Serializable
enum class ModelEgressPurpose {
    CAPTURE_TO_DOCUMENT,
    TUTORING,
    CLASSIFICATION,
    REVIEW_PLANNING,
}

@Serializable
enum class ModelEgressDataClass {
    SANITIZED_IMAGE_BYTES,
    IMAGE_DIMENSIONS,
    SELECTED_IMAGE_REGION,
    CONFIRMED_QUESTION_DOCUMENT,
    RELEVANT_LEARNING_EVIDENCE,
    QUESTION_LEARNING_EVIDENCE,
    RELATED_QUESTION_CANDIDATES,
    SUBJECT_KNOWLEDGE_BASE,
    OTHER_CAPTURE_ASSETS,
    FULL_LEARNING_HISTORY,
    API_CREDENTIALS,
    CAPTURE_METADATA,
    STUDENT_TUTOR_MESSAGE,
    TUTOR_CONVERSATION_CONTEXT,
    MODEL_AUTHORED_VISUAL_CANDIDATE,
}

/** One source of truth for the prompt whose exact scope the student approved. */
object ModelPromptPolicyVersions {
    const val CAPTURE_DOCUMENT = "capture-document-policy-v1"
    const val TUTOR_PLAN = "tutor-plan-v11-reteach-material-priority"
    const val TUTOR_RESPOND = "tutor-respond-v17-round-bound-tool-gate"
    const val TUTOR_VISUAL_GENERATE = "tutor-visual-generate-v1-bounded-semantic-document"
    const val TUTOR_VISUAL_REVIEW = "tutor-visual-review-v1-one-repair"
    const val TUTOR_LOBBY = "tutor-lobby-v6-same-page-tool-declarations"
    const val LEARNING_SUMMARIZE = "learning-summarize-v1-tutor-debrief"
    const val PROBLEM_ORGANIZATION = "problem-organization-v4-atomic"
    const val KNOWLEDGE_QUIZ = "knowledge-quiz-v1-boundary-anchored"

    fun currentFor(kind: ModelTaskKind): String? = when (kind) {
        ModelTaskKind.CAPTURE_ASSESS,
        ModelTaskKind.CAPTURE_PARSE,
        ModelTaskKind.IMAGE_PIPELINE_CLASSIFY,
        -> CAPTURE_DOCUMENT
        ModelTaskKind.TUTOR_PLAN -> TUTOR_PLAN
        ModelTaskKind.TUTOR_RESPOND -> TUTOR_RESPOND
        ModelTaskKind.TUTOR_VISUAL_GENERATE -> TUTOR_VISUAL_GENERATE
        ModelTaskKind.TUTOR_VISUAL_REVIEW -> TUTOR_VISUAL_REVIEW
        ModelTaskKind.TUTOR_LOBBY -> TUTOR_LOBBY
        ModelTaskKind.LEARNING_SUMMARIZE -> LEARNING_SUMMARIZE
        ModelTaskKind.PROBLEM_CLASSIFY -> PROBLEM_ORGANIZATION
        ModelTaskKind.KNOWLEDGE_QUIZ -> KNOWLEDGE_QUIZ
        ModelTaskKind.PROBLEM_RELATE,
        ModelTaskKind.TUTOR_EVALUATE,
        ModelTaskKind.REVIEW_RERANK,
        -> null
    }
}

/**
 * 讲题轮次出网披露的**唯一**计算口径：按运行时状态选择，不按 kind 分叉猜。
 *
 * 状态维度（`docs/tutor-surface-unification.md` §5.5 与 §6 障碍 3）：
 * - **无题**（`carriesQuestion = false`）：只有学生消息与会话上下文，**不含题面**。大厅是无题轮。
 * - **有题**（`carriesQuestion = true`，无图）：题面、学习证据、会话上下文、学科知识库。
 * - **有题带图**：`carriesQuestion = true, includesImage = true` 追加图片类目。
 *
 * **三个校验调用点实际只走到两态**（`ModelEgressManifest` 的 init、`requireAuthorizes` 的
 * Respond 与 Lobby 分支）：Respond 传 `includesImage = false` 且 `assets` 必须为空，Lobby 传
 * `carriesQuestion = false`。第三态**不是**不可达的死分支，而是**尚未接线**：Respond 的附图
 * （`studentImageAssetRefs`）今天只走全局同意通道（`agentConsentGranted`，`egressManifest == null`），
 * 逐次清单那条路上"Tutor response cannot disclose image assets"是一条**既有断言**，本轮没有放宽
 * 它——所以这一态留给"Respond 附图也走清单"的那天，函数已经能表达它。`ModelEgressTest` 里那条
 * 逐态用例断言的是函数取值，不是生产路径可达性；把不可达**钉住**的用例是
 * `a question round manifest still refuses image assets`。
 *
 * 另有两个正交的可选加成，各自只在"本轮真的带了它"时出现：
 * - `includesImage`：本轮真的出网图片字节（大厅的附图消息，这一态在生产里是活的）。
 * - `includesQuestionCandidates`：本轮真的带了候选菜单（错题本里别的题面）。
 *
 * 存在的理由：此前披露集合是按 kind 在四处分别挑常量拼出来的，"哪种轮次披露什么"没有单一
 * 口径；加一维（候选菜单）时无处可加，只能靠封堵。写成函数之后，每一态都有逐态的
 * `disclosedData == expected` 且 `prohibited == 全集 − expected` 用例。
 */
object TutorRoundDisclosure {
    fun expected(
        carriesQuestion: Boolean,
        includesImage: Boolean,
        includesQuestionCandidates: Boolean,
        schemaVersion: Int = ModelEgressManifest.CURRENT_SCHEMA_VERSION,
    ): Set<ModelEgressDataClass> {
        // 分档仍按 schema 走（schema<4 的 Respond 行当年没有 SUBJECT_KNOWLEDGE_BASE；schema<6 的
        // 大厅行不能带图）：旧行 decode 时会重跑这条校验，漏掉 legacy 分档就会让升级后的旧行
        // 直接抛异常（bf8be888 的同一类事故）。
        val base = if (carriesQuestion) {
            ModelEgressManifest.tutorRespondDisclosureForSchema(schemaVersion)
        } else {
            ModelEgressManifest.tutorLobbyDisclosureForSchema(
                schemaVersion = schemaVersion,
                includesImage = includesImage,
            )
        }
        return buildSet {
            addAll(base)
            if (includesQuestionCandidates) {
                add(ModelEgressDataClass.RELATED_QUESTION_CANDIDATES)
            }
            if (includesImage && carriesQuestion) {
                addAll(ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE)
            }
        }
    }
}

/**
 * 本轮派发的披露集合是否覆盖题面 / 学习证据 / 学科知识库——只有**题轮**（
 * [TutorRespondInput] 派遣）如此，无题轮（[TutorLobbyInput]）的集合里没有这三类。
 *
 * 它回答的是"这一轮的披露面装不装得下某个工具的产出"，而不是"这一轮的学生在说哪一道题"。
 * 两者必须分开：`TUTOR_LOBBY_DISCLOSURE` 覆盖不到掌握度明细与学科知识库，所以那两个读工具
 * 在无题轮一律不放行（见 core:domain 的 tutorRoundToolAvailable）——这条判据取自披露集合
 * 本身，不取自解析路由。
 */
fun ModelTaskInput.disclosesQuestionEvidence(): Boolean = this is TutorRespondInput

@Serializable
data class ModelEgressAssetGrant(
    val assetId: String,
    val sha256: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    /** Null means the student approved the whole canonical image. */
    val selectedRegion: NormalizedSourceRegion? = null,
) {
    init {
        assetId.requireSafeModelText("Egress asset id", ModelTaskRequest.MAX_ID_CHARS, false)
        require(sha256.length == SHA_256_HEX_CHARS && sha256.all(Char::isLowerHexDigit)) {
            "Egress asset hash must be a lowercase SHA-256 value"
        }
        require(byteSize in 1L..MODEL_EGRESS_MAX_ASSET_BYTES) {
            "Egress asset byte size is outside the supported range"
        }
        require(width in 1..MAX_CAPTURE_SOURCE_DIMENSION) {
            "Egress asset width is outside the supported range"
        }
        require(height in 1..MAX_CAPTURE_SOURCE_DIMENSION) {
            "Egress asset height is outside the supported range"
        }
        require(width.toLong() * height <= MAX_CAPTURE_SOURCE_PIXELS) {
            "Egress asset exceeds the pixel budget"
        }
        require(selectedRegion == null || selectedRegion.isValidModelRegion()) {
            "Egress asset selected region is invalid"
        }
    }
}

/**
 * Immutable proof of what the student approved for one exact capture and provider configuration.
 * It intentionally contains no URI, local path, API key, question history, or free-form prompt.
 */
@Serializable
data class ModelEgressManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val authorizationId: String,
    val subjectId: String,
    val purpose: ModelEgressPurpose,
    val authorizedTaskKinds: Set<ModelTaskKind>,
    val providerId: String,
    val modelId: String,
    val providerConfigurationVersion: String,
    val promptPolicyVersion: String,
    val approvedAtEpochMillis: Long,
    val assets: List<ModelEgressAssetGrant>,
    val disclosedData: Set<ModelEgressDataClass>,
    val prohibitedData: Set<ModelEgressDataClass>,
    /**
     * 本次授权是否覆盖**本轮候选菜单**（错题本里别的题面）。它是请求侧的事实，不是策略选择：
     * `TutorRespondInput.boundQuestionCandidates` 非空就必须为 true，否则披露集合会少报一个
     * 真实出网的类目（`requireAuthorizes` 会逐次核对这张清单与请求是否一致）。
     */
    val includesQuestionCandidates: Boolean = false,
) {
    init {
        require(schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            "Unsupported egress manifest schema"
        }
        require(schemaVersion >= QUESTION_CANDIDATE_SCHEMA_VERSION || !includesQuestionCandidates) {
            "Legacy egress manifests cannot cover a bound-question candidate menu"
        }
        authorizationId.requireSafeModelText(
            "Egress authorization id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        subjectId.requireSafeModelText("Egress subject id", ModelTaskRequest.MAX_ID_CHARS, false)
        providerId.requireSafeModelText("Egress provider id", MAX_PROVIDER_ID_CHARS, false)
        modelId.requireSafeModelText("Egress model id", MAX_MODEL_VERSION_CHARS, false)
        providerConfigurationVersion.requireSafeModelText(
            "Egress provider configuration version",
            MAX_MODEL_VERSION_CHARS,
            false,
        )
        promptPolicyVersion.requireSafeModelText(
            "Egress prompt policy version",
            MAX_MODEL_VERSION_CHARS,
            false,
        )
        require(approvedAtEpochMillis >= 0) { "Egress approval time must not be negative" }
        require(authorizedTaskKinds.isNotEmpty()) { "Egress task scope must not be empty" }
        require(assets.size <= MAX_CAPTURE_SOURCE_ASSETS) { "Egress asset scope exceeds budget" }
        require(assets.map(ModelEgressAssetGrant::assetId).distinct().size == assets.size) {
            "Egress asset ids must be unique"
        }
        require(disclosedData.isNotEmpty()) { "Egress disclosure set must not be empty" }
        require(disclosedData.intersect(prohibitedData).isEmpty()) {
            "Egress data cannot be both disclosed and prohibited"
        }
        val dataClassUniverse = dataClassUniverseForSchema(schemaVersion)
        require((disclosedData + prohibitedData).all { it in dataClassUniverse }) {
            "Egress manifest references a data class outside its schema"
        }
        require(
            purpose == ModelEgressPurpose.CAPTURE_TO_DOCUMENT ||
                purpose == ModelEgressPurpose.TUTORING ||
                purpose == ModelEgressPurpose.CLASSIFICATION,
        ) { "This egress purpose has no implemented least-disclosure policy" }
        if (purpose == ModelEgressPurpose.CAPTURE_TO_DOCUMENT) {
            require(assets.isNotEmpty()) { "Capture egress asset scope must not be empty" }
            val expectedDisclosure = CAPTURE_IMAGE_DISCLOSURE + if (
                assets.any { asset -> asset.selectedRegion != null }
            ) {
                setOf(ModelEgressDataClass.SELECTED_IMAGE_REGION)
            } else {
                emptySet()
            }
            require(
                authorizedTaskKinds == setOf(
                    ModelTaskKind.CAPTURE_ASSESS,
                    ModelTaskKind.CAPTURE_PARSE,
                ),
            ) { "Capture egress must be limited to assessment and document parsing" }
            require(disclosedData == expectedDisclosure) {
                "Capture egress disclosure must match the exact approved image scope"
            }
            require(prohibitedData == dataClassUniverse - expectedDisclosure) {
                "Capture egress must prohibit every data class outside the approved image scope"
            }
        }
        if (purpose == ModelEgressPurpose.TUTORING) {
            val tutoringKind = authorizedTaskKinds.singleOrNull()
            require(
                tutoringKind == ModelTaskKind.TUTOR_PLAN ||
                    schemaVersion >= 2 && tutoringKind == ModelTaskKind.TUTOR_RESPOND ||
                    schemaVersion >= 4 && tutoringKind == ModelTaskKind.TUTOR_LOBBY ||
                    schemaVersion >= 5 && tutoringKind == ModelTaskKind.TUTOR_VISUAL_GENERATE ||
                    schemaVersion >= 5 && tutoringKind == ModelTaskKind.TUTOR_VISUAL_REVIEW ||
                    schemaVersion >= 5 && tutoringKind == ModelTaskKind.KNOWLEDGE_QUIZ,
            ) {
                "Tutor egress must authorize exactly one supported tutoring task"
            }
            // 讲题轮次的披露**只**走这一条显式函数：按"本轮有没有题 / 有没有图 / 有没有候选菜单"
            // 运行时选择，而不是按 kind 分叉各猜一遍。大厅＝无题轮，Respond＝有题轮；有图时追加
            // 图片类目，带候选菜单时追加该菜单的类目。旧 schema 行（无候选菜单这一维）读回时
            // includesQuestionCandidates 为 false，取值与改前逐字一致。
            val expectedDisclosure = when (tutoringKind) {
                ModelTaskKind.TUTOR_RESPOND,
                ModelTaskKind.TUTOR_LOBBY,
                -> TutorRoundDisclosure.expected(
                    carriesQuestion = tutoringKind == ModelTaskKind.TUTOR_RESPOND,
                    includesImage = tutoringKind == ModelTaskKind.TUTOR_LOBBY &&
                        assets.isNotEmpty(),
                    includesQuestionCandidates = includesQuestionCandidates,
                    schemaVersion = schemaVersion,
                )
                ModelTaskKind.TUTOR_PLAN -> tutorPlanDisclosureForSchema(schemaVersion)
                ModelTaskKind.TUTOR_VISUAL_GENERATE ->
                    tutorVisualGenerateDisclosure(assets.any { it.selectedRegion != null })
                ModelTaskKind.TUTOR_VISUAL_REVIEW ->
                    tutorVisualReviewDisclosure(assets.any { it.selectedRegion != null })
                ModelTaskKind.KNOWLEDGE_QUIZ -> KNOWLEDGE_QUIZ_DISCLOSURE
            }
            if (tutoringKind == ModelTaskKind.TUTOR_LOBBY && assets.isNotEmpty()) {
                require(schemaVersion >= LOBBY_IMAGE_SCHEMA_VERSION) {
                    "Tutor lobby image egress requires egress manifest schema six"
                }
                require(assets.size <= MAX_LOBBY_IMAGE_ASSETS) {
                    "Tutor lobby image scope exceeds the message budget"
                }
            }
            // Lobby：无图时纯文本最小披露；有图时按 schema 六的图片披露集合。
            require(disclosedData == expectedDisclosure) {
                "Tutor egress disclosure must exactly match the authorized tutoring task"
            }
            require(prohibitedData == dataClassUniverseForSchema(schemaVersion) - expectedDisclosure) {
                "Tutor egress must prohibit every data class outside its bounded context"
            }
        }
        if (purpose == ModelEgressPurpose.CLASSIFICATION) {
            require(authorizedTaskKinds == setOf(ModelTaskKind.PROBLEM_CLASSIFY)) {
                "Classification egress must be limited to organizing one confirmed revision"
            }
            require(assets.isEmpty()) {
                "Problem organization must use confirmed documents, not image bytes"
            }
            require(disclosedData == PROBLEM_ORGANIZATION_DISCLOSURE) {
                "Classification egress disclosure must match the bounded organization context"
            }
            require(
                prohibitedData ==
                    dataClassUniverse - PROBLEM_ORGANIZATION_DISCLOSURE,
            ) { "Classification egress must prohibit every undisclosed data class" }
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 7
        private const val MIN_SUPPORTED_SCHEMA_VERSION = 1

        /** schema 6 起：学生一次性说明后，Lobby 可携带学生选择的消息配图。 */
        internal const val LOBBY_IMAGE_SCHEMA_VERSION = 6

        /** schema 7 起：授权清单可以覆盖本轮候选菜单（`includesQuestionCandidates`）。 */
        internal const val QUESTION_CANDIDATE_SCHEMA_VERSION = 7

        /** 一条消息最多附带的图片数（学生裁定）。 */
        const val MAX_LOBBY_IMAGE_ASSETS = 9

        val SCHEMA_V1_DATA_CLASSES = setOf(
            ModelEgressDataClass.SANITIZED_IMAGE_BYTES,
            ModelEgressDataClass.IMAGE_DIMENSIONS,
            ModelEgressDataClass.SELECTED_IMAGE_REGION,
            ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
            ModelEgressDataClass.RELEVANT_LEARNING_EVIDENCE,
            ModelEgressDataClass.QUESTION_LEARNING_EVIDENCE,
            ModelEgressDataClass.RELATED_QUESTION_CANDIDATES,
            ModelEgressDataClass.OTHER_CAPTURE_ASSETS,
            ModelEgressDataClass.FULL_LEARNING_HISTORY,
            ModelEgressDataClass.API_CREDENTIALS,
            ModelEgressDataClass.CAPTURE_METADATA,
        )

        val CAPTURE_IMAGE_DISCLOSURE = setOf(
            ModelEgressDataClass.SANITIZED_IMAGE_BYTES,
            ModelEgressDataClass.IMAGE_DIMENSIONS,
        )

        val CAPTURE_PROHIBITED_DATA =
            ModelEgressDataClass.entries.toSet() - CAPTURE_IMAGE_DISCLOSURE

        val LEGACY_TUTOR_PLAN_DISCLOSURE = setOf(
            ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
            ModelEgressDataClass.RELEVANT_LEARNING_EVIDENCE,
            ModelEgressDataClass.QUESTION_LEARNING_EVIDENCE,
        )

        private val SCHEMA_THREE_TUTOR_PLAN_DISCLOSURE = LEGACY_TUTOR_PLAN_DISCLOSURE + setOf(
            ModelEgressDataClass.STUDENT_TUTOR_MESSAGE,
            ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
        )

        val TUTOR_PLAN_DISCLOSURE = SCHEMA_THREE_TUTOR_PLAN_DISCLOSURE +
            ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE

        val TUTOR_PLAN_PROHIBITED_DATA =
            ModelEgressDataClass.entries.toSet() - TUTOR_PLAN_DISCLOSURE

        internal fun tutorPlanDisclosureForSchema(
            schemaVersion: Int,
        ): Set<ModelEgressDataClass> = when {
            schemaVersion >= 4 -> TUTOR_PLAN_DISCLOSURE
            schemaVersion >= 3 -> SCHEMA_THREE_TUTOR_PLAN_DISCLOSURE
            else -> LEGACY_TUTOR_PLAN_DISCLOSURE
        }

        private val LEGACY_TUTOR_RESPOND_DISCLOSURE = setOf(
            ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
            ModelEgressDataClass.RELEVANT_LEARNING_EVIDENCE,
            ModelEgressDataClass.QUESTION_LEARNING_EVIDENCE,
            ModelEgressDataClass.STUDENT_TUTOR_MESSAGE,
            ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
        )

        val TUTOR_RESPOND_DISCLOSURE = LEGACY_TUTOR_RESPOND_DISCLOSURE +
            ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE

        val TUTOR_RESPOND_PROHIBITED_DATA =
            ModelEgressDataClass.entries.toSet() - TUTOR_RESPOND_DISCLOSURE

        /** Knowledge review quiz discloses only the bounded node material + prior mastery. */
        val KNOWLEDGE_QUIZ_DISCLOSURE = setOf(
            ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE,
            ModelEgressDataClass.RELEVANT_LEARNING_EVIDENCE,
        )

        val KNOWLEDGE_QUIZ_PROHIBITED_DATA =
            ModelEgressDataClass.entries.toSet() - KNOWLEDGE_QUIZ_DISCLOSURE

        internal fun tutorRespondDisclosureForSchema(
            schemaVersion: Int,
        ): Set<ModelEgressDataClass> = if (schemaVersion >= 4) {
            TUTOR_RESPOND_DISCLOSURE
        } else {
            LEGACY_TUTOR_RESPOND_DISCLOSURE
        }

        val TUTOR_LOBBY_DISCLOSURE = setOf(
            ModelEgressDataClass.STUDENT_TUTOR_MESSAGE,
            ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
        )

        val TUTOR_LOBBY_PROHIBITED_DATA =
            ModelEgressDataClass.entries.toSet() - TUTOR_LOBBY_DISCLOSURE

        /** 附图消息的披露：在纯文本范围上追加图片类目（首次一次性说明后长期有效）。 */
        val TUTOR_LOBBY_IMAGE_DISCLOSURE = TUTOR_LOBBY_DISCLOSURE + CAPTURE_IMAGE_DISCLOSURE

        val TUTOR_LOBBY_IMAGE_PROHIBITED_DATA =
            ModelEgressDataClass.entries.toSet() - TUTOR_LOBBY_IMAGE_DISCLOSURE

        /**
         * Lobby 披露按请求是否附图与 schema 版本选择：旧 schema 行读回时仍按纯文本
         * 集合校验（assets 必须为空），新 schema 才允许图片类目。
         */
        internal fun tutorLobbyDisclosureForSchema(
            schemaVersion: Int,
            includesImage: Boolean,
        ): Set<ModelEgressDataClass> =
            if (includesImage && schemaVersion >= LOBBY_IMAGE_SCHEMA_VERSION) {
                TUTOR_LOBBY_IMAGE_DISCLOSURE
            } else {
                TUTOR_LOBBY_DISCLOSURE
            }

        private val TUTOR_VISUAL_GENERATE_BASE_DISCLOSURE = setOf(
            ModelEgressDataClass.SANITIZED_IMAGE_BYTES,
            ModelEgressDataClass.IMAGE_DIMENSIONS,
            ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
            ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
        )

        fun tutorVisualGenerateDisclosure(
            includesSelectedRegion: Boolean,
        ): Set<ModelEgressDataClass> = TUTOR_VISUAL_GENERATE_BASE_DISCLOSURE + if (
            includesSelectedRegion
        ) {
            setOf(ModelEgressDataClass.SELECTED_IMAGE_REGION)
        } else {
            emptySet()
        }

        fun tutorVisualReviewDisclosure(
            includesSelectedRegion: Boolean,
        ): Set<ModelEgressDataClass> =
            tutorVisualGenerateDisclosure(includesSelectedRegion) +
                ModelEgressDataClass.MODEL_AUTHORED_VISUAL_CANDIDATE

        internal fun dataClassUniverseForSchema(
            schemaVersion: Int,
        ): Set<ModelEgressDataClass> = when {
            schemaVersion == 1 -> SCHEMA_V1_DATA_CLASSES
            schemaVersion < 5 ->
                ModelEgressDataClass.entries.toSet() -
                    ModelEgressDataClass.MODEL_AUTHORED_VISUAL_CANDIDATE
            else -> ModelEgressDataClass.entries.toSet()
        }

        val PROBLEM_ORGANIZATION_DISCLOSURE = setOf(
            ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
            ModelEgressDataClass.RELATED_QUESTION_CANDIDATES,
            ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE,
        )

        val PROBLEM_ORGANIZATION_PROHIBITED_DATA =
            ModelEgressDataClass.entries.toSet() - PROBLEM_ORGANIZATION_DISCLOSURE
    }
}

sealed interface ModelExecutionPermit {
    data object LocalOnly : ModelExecutionPermit

    data class External(val manifest: ModelEgressManifest) : ModelExecutionPermit

    /**
     * Granted for agent-eligible kinds (capture assess/parse/classify and tutor
     * plan/respond/visual) when the user has enabled global model-agent consent in
     * Settings ("configuring the model = consent"). Carries no per-asset grant: the
     * request's own asset refs plus the consent flag authorize the read. Lobby and
     * the organization/summarize routes always require a manifest.
     */
    data object ProviderConsented : ModelExecutionPermit
}

class ModelGatewayExecution internal constructor(
    val request: ModelTaskRequest,
    val permit: ModelExecutionPermit,
)

class ModelEgressAuthorizationException(
    val failureCode: ModelFailureCode,
    override val message: String,
) : IllegalArgumentException(message)

class ModelRequestBudgetExceededException :
    IllegalArgumentException("Model request exceeds the upload budget")

/**
 * Computes the complete Base64 contribution without allocating it. [nonImageJsonUtf8Bytes] must
 * include the request envelope and every image data-URL prefix, but not the Base64 characters.
 */
object ModelRequestPayloadBudget {
    const val MAX_PREPARED_REQUEST_BYTES: Long =
        MODEL_EXTERNAL_TRANSPORT_REQUEST_LIMIT_BYTES - 1L

    fun requirePreparedRequestFits(
        nonImageJsonUtf8Bytes: Long,
        assetByteSizes: Iterable<Long>,
    ): Long {
        if (nonImageJsonUtf8Bytes !in 0L..MAX_PREPARED_REQUEST_BYTES) {
            throw ModelRequestBudgetExceededException()
        }
        var estimatedBytes = nonImageJsonUtf8Bytes
        assetByteSizes.forEach { byteSize ->
            val encodedBytes = base64EncodedBytes(byteSize)
            if (
                encodedBytes > MAX_PREPARED_REQUEST_BYTES ||
                estimatedBytes > MAX_PREPARED_REQUEST_BYTES - encodedBytes
            ) {
                throw ModelRequestBudgetExceededException()
            }
            estimatedBytes += encodedBytes
        }
        return estimatedBytes
    }

    private fun base64EncodedBytes(byteSize: Long): Long {
        if (byteSize <= 0L) throw ModelRequestBudgetExceededException()
        val groups = byteSize / BASE64_INPUT_GROUP_BYTES +
            if (byteSize % BASE64_INPUT_GROUP_BYTES == 0L) 0L else 1L
        if (groups > Long.MAX_VALUE / BASE64_OUTPUT_GROUP_BYTES) {
            throw ModelRequestBudgetExceededException()
        }
        return groups * BASE64_OUTPUT_GROUP_BYTES
    }

    private const val BASE64_INPUT_GROUP_BYTES = 3L
    private const val BASE64_OUTPUT_GROUP_BYTES = 4L
}

/**
 * True when this agent-eligible request may egress to the configured provider
 * under global consent, WITHOUT an image-capability constraint (a structured-only
 * provider still runs text-only PLAN/RESPOND under consent). Image capability is
 * enforced separately via [ModelTaskInput.requiresImageInput] at authorize time.
 * Single source of truth for authorize() and the gateway's pre-flight.
 */
fun ModelTaskRequest.agentConsentMatches(provider: ProviderCapabilitySnapshot): Boolean =
    agentConsentGranted &&
        input.isAgentConsentEligible &&
        provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
        provider.supports(input.kind)

/** True when the input discloses image bytes that require an image-capable provider. */
fun ModelTaskInput.requiresImageInput(): Boolean =
    isAgentConsentEligible && requestsImageBytes

object ModelEgressPolicy {
    fun authorize(
        request: ModelTaskRequest,
        provider: ProviderCapabilitySnapshot,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): ModelGatewayExecution = when (provider.executionLocation) {
        ModelExecutionLocation.LOCAL_NO_EGRESS,
        ModelExecutionLocation.UNAVAILABLE,
        -> ModelGatewayExecution(request, ModelExecutionPermit.LocalOnly)

        ModelExecutionLocation.EXTERNAL_PROVIDER -> {
            // Global-consent path: when the user enabled model-agent consent and this
            // is an agent-eligible round, the request may egress to the configured
            // provider without a per-item manifest. Image-bearing kinds additionally
            // require the provider to accept images. Lobby / organization routes still
            // require a manifest.
            if (
                request.agentConsentMatches(provider) &&
                (!request.input.requiresImageInput() || provider.supportsImageInput)
            ) {
                return ModelGatewayExecution(request, ModelExecutionPermit.ProviderConsented)
            }
            val manifest = request.egressManifest ?: throw ModelEgressAuthorizationException(
                ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED,
                "需要你确认本次发送范围后，才能交给模型处理",
            )
            runCatching { manifest.requireAuthorizes(request, provider, nowEpochMillis) }
                .getOrElse { cause ->
                    throw ModelEgressAuthorizationException(
                        ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
                        "本次发送范围与当前模型或题图不一致，请重新确认",
                    ).apply { initCause(cause) }
                }
            ModelGatewayExecution(request, ModelExecutionPermit.External(manifest))
        }
    }

    /**
     * Revalidates the exact permit immediately before an external transport is invoked.
     * Callers must not replace a local or stale permit by authorizing the request again.
     */
    fun requireCurrentExternalAuthorization(
        execution: ModelGatewayExecution,
        provider: ProviderCapabilitySnapshot,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        when (val permit = execution.permit) {
            is ModelExecutionPermit.External -> {
                val permittedManifest = permit.manifest
                if (execution.request.egressManifest != permittedManifest) {
                    throw invalidCurrentAuthorization()
                }
                val currentExecution = authorize(execution.request, provider, nowEpochMillis)
                val currentManifest = (currentExecution.permit as? ModelExecutionPermit.External)?.manifest
                    ?: throw invalidCurrentAuthorization()
                if (currentManifest != permittedManifest) {
                    throw invalidCurrentAuthorization()
                }
            }
            ModelExecutionPermit.ProviderConsented -> {
                // Re-validate the global-consent condition holds right now (toggle still on,
                // provider still the configured one, image-capable for image kinds). The
                // per-byte asset gate is enforced separately in the restricted asset source.
                if (
                    execution.request.agentConsentMatches(provider) &&
                    (!execution.request.input.requiresImageInput() || provider.supportsImageInput)
                ) {
                    return
                }
                throw invalidCurrentAuthorization()
            }
            ModelExecutionPermit.LocalOnly -> throw invalidCurrentAuthorization()
        }
    }

    private fun invalidCurrentAuthorization() = ModelEgressAuthorizationException(
        ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
        "本次发送范围与当前模型或题图不一致，请重新确认",
    )
}

private fun ModelEgressManifest.requireAuthorizes(
    request: ModelTaskRequest,
    provider: ProviderCapabilitySnapshot,
    nowEpochMillis: Long,
) {
    require(nowEpochMillis >= 0) { "Current time must not be negative" }
    require(subjectId == request.input.subjectId) { "Egress subject does not match request" }
    require(request.input.kind in authorizedTaskKinds) { "Task kind is outside egress scope" }
    require(providerId == provider.providerId) { "Egress provider does not match" }
    require(modelId == provider.modelId) { "Egress model does not match" }
    require(providerConfigurationVersion == provider.providerConfigurationVersion) {
        "Egress provider configuration changed"
    }
    val currentPromptPolicy = ModelPromptPolicyVersions.currentFor(request.input.kind)
    require(currentPromptPolicy != null && promptPolicyVersion == currentPromptPolicy) {
        "Egress prompt policy changed"
    }
    require(isModelEgressApprovalFresh(nowEpochMillis)) {
        "Egress approval expired or has an invalid timestamp"
    }
    // Tutor consent is a short-lived, question-bound conversation lease. The UI must hold a
    // current in-memory lease; the manifest still binds every exact plan/response payload here.
    if (request.input !is TutorPlanInput && request.input !is TutorRespondInput) {
        require(approvedAtEpochMillis >= request.occurredAtEpochMillis) {
            "Egress approval predates the request"
        }
    }
    when (val input = request.input) {
        is TutorDebriefInput -> {
            require(purpose == ModelEgressPurpose.TUTORING)
        }
        is KnowledgeQuizInput -> {
            require(purpose == ModelEgressPurpose.TUTORING)
            require(assets.isEmpty()) { "Knowledge quiz is text-only and never ships image assets" }
        }
        is CaptureAssessmentInput -> {
            require(purpose == ModelEgressPurpose.CAPTURE_TO_DOCUMENT)
            val expectedAssetIds = buildSet {
                add(input.sourceAssetId)
                input.followingSourceAssets.forEach { add(it.assetId) }
            }
            require(
                input.followingSourceAssets.isEmpty() ||
                    assets.mapTo(mutableSetOf()) { it.assetId } == expectedAssetIds,
            ) {
                "Page-comparison asset scope changed"
            }
            val grant = assets.singleOrNull { it.assetId == input.sourceAssetId }
                ?: error("Assessment asset is outside egress scope")
            require(grant.width == input.imageWidth && grant.height == input.imageHeight) {
                "Assessment dimensions changed after approval"
            }
            input.followingSourceAssets.forEach { source ->
                val followingGrant = assets.singleOrNull { it.assetId == source.assetId }
                    ?: error("Following assessment asset is outside egress scope")
                require(
                    followingGrant.sha256 == source.sha256 &&
                        followingGrant.width == source.width &&
                        followingGrant.height == source.height &&
                        followingGrant.selectedRegion == source.selectedRegion,
                ) { "Following assessment asset changed after approval" }
            }
        }

        is ImagePipelineClassifyInput -> {
            require(purpose == ModelEgressPurpose.CAPTURE_TO_DOCUMENT)
            val grant = assets.singleOrNull { it.assetId == input.sourceAssetId }
                ?: error("Image pipeline classify asset is outside egress scope")
            require(grant.width == input.imageWidth && grant.height == input.imageHeight) {
                "Image pipeline classify dimensions changed after approval"
            }
        }

        is CaptureParseInput -> {
            require(purpose == ModelEgressPurpose.CAPTURE_TO_DOCUMENT)
            require(input.sourceAssets.size == assets.size) { "Parse asset scope changed" }
            input.sourceAssets.forEach { source ->
                val grant = assets.singleOrNull { it.assetId == source.assetId }
                    ?: error("Parse asset is outside egress scope")
                require(
                    grant.sha256 == source.sha256 &&
                        grant.width == source.width &&
                        grant.height == source.height &&
                        grant.selectedRegion == source.selectedRegion,
                ) { "Parse asset changed after approval" }
            }
        }

        is TutorPlanInput -> {
            require(purpose == ModelEgressPurpose.TUTORING)
            require(assets.isEmpty()) { "Tutor plan cannot disclose image assets" }
            val expectedDisclosure = ModelEgressManifest.tutorPlanDisclosureForSchema(schemaVersion)
            require(disclosedData == expectedDisclosure)
            val dataClassUniverse = ModelEgressManifest.dataClassUniverseForSchema(schemaVersion)
            require(prohibitedData == dataClassUniverse - expectedDisclosure)
        }

        is TutorRespondInput -> {
            require(schemaVersion >= 2) { "Tutor response requires egress manifest schema two" }
            require(purpose == ModelEgressPurpose.TUTORING)
            require(assets.isEmpty()) { "Tutor response cannot disclose image assets" }
            // 候选菜单是错题本里**别人的**题面：它比本题多一个 RELATED_QUESTION_CANDIDATES 类目。
            // 两条都要成立才算授权：
            // ① 清单自己声明覆盖了候选菜单（否则清单就是在少报——请求里带着菜单而清单说没有）；
            // ② 清单的披露集合与"本轮真的带了什么"逐字相等。
            val carriesQuestionCandidates = input.boundQuestionCandidates.isNotEmpty()
            require(includesQuestionCandidates == carriesQuestionCandidates) {
                "Egress manifest candidate-menu scope disagrees with the request"
            }
            val expectedDisclosure = TutorRoundDisclosure.expected(
                carriesQuestion = true,
                includesImage = false,
                includesQuestionCandidates = carriesQuestionCandidates,
                schemaVersion = schemaVersion,
            )
            require(disclosedData == expectedDisclosure)
            require(
                prohibitedData ==
                    ModelEgressManifest.dataClassUniverseForSchema(schemaVersion) - expectedDisclosure,
            )
        }

        is TutorVisualGenerateInput -> {
            require(schemaVersion >= 5) { "Tutor visual generation requires egress schema five" }
            require(purpose == ModelEgressPurpose.TUTORING)
            requireVisualAssetScope(input.sourceAssets)
            val expectedDisclosure = ModelEgressManifest.tutorVisualGenerateDisclosure(
                includesSelectedRegion = input.sourceAssets.any { it.selectedRegion != null },
            )
            require(disclosedData == expectedDisclosure)
            require(
                prohibitedData ==
                    ModelEgressManifest.dataClassUniverseForSchema(schemaVersion) - expectedDisclosure,
            )
        }

        is TutorVisualReviewInput -> {
            require(schemaVersion >= 5) { "Tutor visual review requires egress schema five" }
            require(purpose == ModelEgressPurpose.TUTORING)
            requireVisualAssetScope(input.sourceAssets)
            val expectedDisclosure = ModelEgressManifest.tutorVisualReviewDisclosure(
                includesSelectedRegion = input.sourceAssets.any { it.selectedRegion != null },
            )
            require(disclosedData == expectedDisclosure)
            require(
                prohibitedData ==
                    ModelEgressManifest.dataClassUniverseForSchema(schemaVersion) - expectedDisclosure,
            )
        }

        is TutorLobbyInput -> {
            require(schemaVersion >= 4) { "Tutor lobby requires egress manifest schema four" }
            require(purpose == ModelEgressPurpose.TUTORING)
            // 本条消息的图 + 上文图片（最近一次带图消息的那几张）：两者都是本次真正出网的字节，
            // 所以授权范围必须逐张覆盖它们，缺一张就拒绝——图片不因"来自历史消息"而少一分披露。
            val disclosedImages = input.sourceImageAssetRefs + input.contextImageAssetRefs
            val includesImage = disclosedImages.isNotEmpty()
            // 大厅输入没有候选菜单字段：无题轮不存在"菜单里的别的题"。
            val carriesQuestionCandidates = false
            if (includesImage) {
                require(schemaVersion >= ModelEgressManifest.LOBBY_IMAGE_SCHEMA_VERSION) {
                    "Tutor lobby image egress requires egress manifest schema six"
                }
                require(
                    input.sourceImageAssetRefs.size <= ModelEgressManifest.MAX_LOBBY_IMAGE_ASSETS &&
                        input.contextImageAssetRefs.size <= ModelEgressManifest.MAX_LOBBY_IMAGE_ASSETS,
                ) {
                    "Tutor lobby image count exceeds the message budget"
                }
                require(assets.size == disclosedImages.size) {
                    "Lobby image scope changed"
                }
                disclosedImages.forEach { source ->
                    val grant = assets.singleOrNull { it.assetId == source.assetId }
                        ?: error("Lobby image is outside egress scope")
                    require(
                        grant.sha256 == source.sha256 &&
                            grant.width == source.width &&
                            grant.height == source.height &&
                            grant.selectedRegion == null &&
                            source.selectedRegion == null,
                    ) { "Lobby image changed after approval" }
                }
            } else {
                require(assets.isEmpty()) { "Tutor lobby cannot disclose image assets" }
            }
            require(includesQuestionCandidates == carriesQuestionCandidates) {
                "Egress manifest candidate-menu scope disagrees with the request"
            }
            val expectedDisclosure = TutorRoundDisclosure.expected(
                carriesQuestion = false,
                includesImage = includesImage,
                includesQuestionCandidates = carriesQuestionCandidates,
                schemaVersion = schemaVersion,
            )
            require(disclosedData == expectedDisclosure)
            require(
                prohibitedData ==
                    ModelEgressManifest.dataClassUniverseForSchema(schemaVersion) - expectedDisclosure,
            )
        }

        is ProblemOrganizationInput -> {
            require(purpose == ModelEgressPurpose.CLASSIFICATION)
            require(assets.isEmpty()) { "Problem organization cannot disclose image assets" }
            require(disclosedData == ModelEgressManifest.PROBLEM_ORGANIZATION_DISCLOSURE)
            val dataClassUniverse = ModelEgressManifest.dataClassUniverseForSchema(schemaVersion)
            require(
                prohibitedData == dataClassUniverse - ModelEgressManifest.PROBLEM_ORGANIZATION_DISCLOSURE,
            )
        }

    }
}

private fun ModelEgressManifest.requireVisualAssetScope(
    sourceAssets: List<CaptureSourceAssetRef>,
) {
    require(sourceAssets.size == assets.size) { "Tutor visual asset scope changed" }
    sourceAssets.forEach { source ->
        val grant = assets.singleOrNull { it.assetId == source.assetId }
            ?: error("Tutor visual image is outside egress scope")
        require(
            grant.sha256 == source.sha256 &&
                grant.width == source.width &&
                grant.height == source.height &&
                grant.selectedRegion == source.selectedRegion,
        ) { "Tutor visual image changed after approval" }
    }
}

/** Short-lived consent prevents a persisted request from silently sending long after approval. */
fun ModelEgressManifest.isModelEgressApprovalFresh(nowEpochMillis: Long): Boolean {
    return isModelEgressApprovalFresh(approvedAtEpochMillis, nowEpochMillis)
}

fun isModelEgressApprovalFresh(
    approvedAtEpochMillis: Long,
    nowEpochMillis: Long,
): Boolean {
    if (nowEpochMillis < 0) return false
    return if (approvedAtEpochMillis > nowEpochMillis) {
        approvedAtEpochMillis - nowEpochMillis <= MODEL_EGRESS_MAX_CLOCK_SKEW_MILLIS
    } else {
        nowEpochMillis - approvedAtEpochMillis <= MODEL_EGRESS_APPROVAL_TTL_MILLIS
    }
}

const val MODEL_EGRESS_APPROVAL_TTL_MILLIS: Long = 15L * 60L * 1_000L
const val MODEL_EGRESS_MAX_CLOCK_SKEW_MILLIS: Long = 2L * 60L * 1_000L

const val MODEL_EGRESS_MAX_ASSET_BYTES = 24L * 1_024L * 1_024L
