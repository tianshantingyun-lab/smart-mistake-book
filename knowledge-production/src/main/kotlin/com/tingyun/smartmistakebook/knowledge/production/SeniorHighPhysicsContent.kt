package com.tingyun.smartmistakebook.knowledge.production

/**
 * High school physics core content template.
 * This file defines the structure for high school physics knowledge points.
 *
 * Coverage targets:
 * - 高中物理必修主干 (Core required physics for senior high)
 */
object SeniorHighPhysicsContent {

    /**
     * Core topics for senior high physics.
     */
    val coreTopics = listOf(
        // 运动的描述
        TopicDefinition(
            topicId = "physics-sh-motion",
            name = "运动的描述",
            module = "运动学",
            description = "质点、参考系、坐标系；时间与位移；速度与加速度",
            knowledgePoints = listOf(
                KnowledgePointTemplate(
                    id = "kp-particle-model",
                    name = "质点模型",
                    definition = "当物体的形状和大小对所研究的问题影响可以忽略不计时，可以把物体看作一个有质量的点",
                    boundaries = "适用于宏观低速运动，物体尺寸远小于运动尺度时",
                    prerequisites = emptyList(),
                    followUps = listOf("kp-reference-frame", "kp-displacement"),
                    commonMisconceptions = listOf(
                        "认为质点就是体积很小的物体",
                        "忽略质点模型的适用条件",
                    ),
                    diagnosticQuestions = listOf(
                        "研究地球公转时，能否把地球看作质点？",
                        "研究地球自转时，能否把地球看作质点？",
                    ),
                    examples = listOf(
                        "火车过桥问题中，火车能否看作质点？",
                    ),
                    counterexamples = listOf(
                        "研究乒乓球旋转时，不能把球看作质点",
                    ),
                    formulas = emptyList(),
                ),
                KnowledgePointTemplate(
                    id = "kp-velocity-acceleration",
                    name = "速度与加速度",
                    definition = "速度是位移与时间的比值；加速度是速度变化量与时间的比值",
                    boundaries = "适用于匀变速直线运动和非匀变速运动",
                    prerequisites = listOf("kp-displacement"),
                    followUps = listOf("kp-uniform-motion", "kp-free-fall"),
                    commonMisconceptions = listOf(
                        "速度大加速度就大",
                        "速度为零加速度就为零",
                    ),
                    diagnosticQuestions = listOf(
                        "匀速圆周运动的速度和加速度有什么特点？",
                        "汽车启动时，速度为零但加速度不为零，为什么？",
                    ),
                    examples = listOf(
                        "物体从静止开始做匀加速直线运动，2s末速度为4m/s，求加速度",
                    ),
                    counterexamples = listOf(
                        "匀速运动：速度不变，加速度为零",
                    ),
                    formulas = listOf(
                        "v = Δx/Δt",
                        "a = Δv/Δt",
                        "v² = v₀² + 2ax",
                    ),
                ),
            ),
        ),
        // 自由落体运动
        TopicDefinition(
            topicId = "physics-sh-free-fall",
            name = "自由落体运动",
            module = "运动学",
            description = "自由落体运动的规律；重力加速度",
            knowledgePoints = listOf(
                KnowledgePointTemplate(
                    id = "kp-free-fall",
                    name = "自由落体运动",
                    definition = "物体只在重力作用下从静止开始下落的运动",
                    boundaries = "忽略空气阻力，适用于地球表面附近",
                    prerequisites = listOf("kp-velocity-acceleration"),
                    followUps = listOf("kp-newton-second-law"),
                    commonMisconceptions = listOf(
                        "重的物体下落快",
                        "忽略空气阻力的影响",
                    ),
                    diagnosticQuestions = listOf(
                        "在真空中，羽毛和铁球哪个下落快？",
                        "为什么跳伞运动员要打开降落伞？",
                    ),
                    examples = listOf(
                        "物体从10m高处自由下落，求落地速度和时间",
                    ),
                    counterexamples = listOf(
                        "有空气阻力时，下落不是自由落体运动",
                    ),
                    formulas = listOf(
                        "v = gt",
                        "h = ½gt²",
                        "v² = 2gh",
                        "g ≈ 9.8 m/s²",
                    ),
                ),
            ),
        ),
        // 牛顿运动定律
        TopicDefinition(
            topicId = "physics-sh-newton",
            name = "牛顿运动定律",
            module = "动力学",
            description = "牛顿第一定律、牛顿第二定律、牛顿第三定律",
            knowledgePoints = listOf(
                KnowledgePointTemplate(
                    id = "kp-newton-second-law",
                    name = "牛顿第二定律",
                    definition = "物体的加速度与所受合力成正比，与物体质量成反比",
                    boundaries = "适用于惯性参考系中的宏观低速运动",
                    prerequisites = listOf("kp-velocity-acceleration"),
                    followUps = listOf("kp-force-analysis"),
                    commonMisconceptions = listOf(
                        "认为力是维持运动的原因",
                        "忽略力的矢量性",
                    ),
                    diagnosticQuestions = listOf(
                        "物体做匀速直线运动时，合力是否为零？",
                        "加速度方向与合力方向有什么关系？",
                    ),
                    examples = listOf(
                        "质量为2kg的物体受到4N的合力，求加速度",
                    ),
                    counterexamples = listOf(
                        "力消失后，物体将保持匀速直线运动或静止",
                    ),
                    formulas = listOf(
                        "F = ma",
                        "F合 = m × a",
                    ),
                ),
            ),
        ),
    )

    /**
     * Template for a knowledge point.
     */
    data class KnowledgePointTemplate(
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
    )

    /**
     * Template for a topic.
     */
    data class TopicDefinition(
        val topicId: String,
        val name: String,
        val module: String,
        val description: String,
        val knowledgePoints: List<KnowledgePointTemplate>,
    )
}
