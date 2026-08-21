package com.tingyun.smartmistakebook.knowledge.production

/**
 * Loads knowledge content templates into the database.
 * This is the bridge between the knowledge production templates and the
 * actual knowledge database used by the application.
 */
class KnowledgeBaseLoader(
    private val knowledgeRepository: KnowledgeRepository,
) {
    /**
     * Load all core knowledge content into the database.
     */
    suspend fun loadAllContent() {
        loadMathContent()
        loadPhysicsContent()
    }

    /**
     * Load high school math content.
     */
    suspend fun loadMathContent() {
        val topics = SeniorHighMathContent.coreTopics
        for (topic in topics) {
            loadTopic(topic)
        }
    }

    /**
     * Load high school physics content.
     */
    suspend fun loadPhysicsContent() {
        val topics = SeniorHighPhysicsContent.coreTopics
        for (topic in topics) {
            loadTopic(topic)
        }
    }

    /**
     * Load a single topic and its knowledge points.
     */
    private suspend fun loadTopic(topic: Any) {
        when (topic) {
            is SeniorHighMathContent.TopicDefinition -> {
                for (kp in topic.knowledgePoints) {
                    knowledgeRepository.upsertKnowledgePoint(
                        KnowledgePointEntity(
                            id = kp.id,
                            name = kp.name,
                            definition = kp.definition,
                            boundaries = kp.boundaries,
                            prerequisites = kp.prerequisites,
                            followUps = kp.followUps,
                            commonMisconceptions = kp.commonMisconceptions,
                            diagnosticQuestions = kp.diagnosticQuestions,
                            examples = kp.examples,
                            counterexamples = kp.counterexamples,
                            formulas = kp.formulas,
                            subjectId = "math",
                            topicId = topic.topicId,
                            moduleId = topic.module,
                            educationLevel = "SENIOR_HIGH",
                        ),
                    )
                }
            }
            is SeniorHighPhysicsContent.TopicDefinition -> {
                for (kp in topic.knowledgePoints) {
                    knowledgeRepository.upsertKnowledgePoint(
                        KnowledgePointEntity(
                            id = kp.id,
                            name = kp.name,
                            definition = kp.definition,
                            boundaries = kp.boundaries,
                            prerequisites = kp.prerequisites,
                            followUps = kp.followUps,
                            commonMisconceptions = kp.commonMisconceptions,
                            diagnosticQuestions = kp.diagnosticQuestions,
                            examples = kp.examples,
                            counterexamples = kp.counterexamples,
                            formulas = kp.formulas,
                            subjectId = "physics",
                            topicId = topic.topicId,
                            moduleId = topic.module,
                            educationLevel = "SENIOR_HIGH",
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Interface for knowledge repository operations.
 */
interface KnowledgeRepository {
    suspend fun upsertKnowledgePoint(entity: KnowledgePointEntity)
    suspend fun getKnowledgePoint(id: String): KnowledgePointEntity?
    suspend fun getAllKnowledgePoints(): List<KnowledgePointEntity>
    suspend fun getKnowledgePointsBySubject(subjectId: String): List<KnowledgePointEntity>
    suspend fun getKnowledgePointsByTopic(topicId: String): List<KnowledgePointEntity>
}

/**
 * Entity for a knowledge point in the database.
 */
data class KnowledgePointEntity(
    val id: String,
    val name: String,
    val definition: String,
    val boundaries: String,
    val prerequisites: List<String>,
    val followUps: List<String>,
    val commonMisconceptions: List<String>,
    val diagnosticQuestions: List<String>,
    val examples: List<String>,
    val counterexamples: List<String>,
    val formulas: List<String>,
    val subjectId: String,
    val topicId: String,
    val moduleId: String,
    val educationLevel: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)
