package com.tingyun.smartmistakebook.knowledge.production

/**
 * High school math core content template.
 * This file defines the structure for high school math knowledge points.
 *
 * Coverage targets:
 * - 高中数学必修主干 (Core required math for senior high)
 * - Each topic must have stable ID, curriculum version, stage/subject/module,
 *   definition, boundaries, prerequisites, follow-ups, common misconceptions,
 *   diagnostic questions, representative examples, counterexamples,
 *   formulas/units, source attribution, copyright status, author/reviewer,
 *   review status, version/deprecatedBy.
 */
object SeniorHighMathContent {

    /**
     * Core topics for senior high math.
     */
    val coreTopics = listOf(
        // 集合与常用逻辑用语
        TopicDefinition(
            topicId = "math-sh-set-logic",
            name = "集合与常用逻辑用语",
            module = "集合与逻辑",
            description = "集合的概念、表示、运算；充分条件与必要条件；全称量词与存在量词",
            knowledgePoints = listOf(
                KnowledgePointTemplate(
                    id = "kp-set-concept",
                    name = "集合的概念",
                    definition = "集合是由一些确定的、不同的对象组成的整体",
                    boundaries = "适用于所有数学对象的集合，包括数集、点集、图形集等",
                    prerequisites = emptyList(),
                    followUps = listOf("kp-set-representation", "kp-set-operations"),
                    commonMisconceptions = listOf(
                        "混淆元素与集合的关系（a∈A vs {a}⊆A）",
                        "忽略集合元素的确定性、互异性、无序性",
                    ),
                    diagnosticQuestions = listOf(
                        "1, 1, 2 这三个数能否组成集合？为什么？",
                        "集合{1,2,3}与集合{3,2,1}是否相同？",
                    ),
                    examples = listOf(
                        "用列举法表示小于5的正整数组成的集合",
                        "用描述法表示所有奇数组成的集合",
                    ),
                    counterexamples = listOf(
                        "非常接近的数组成的集合（不满足确定性）",
                    ),
                    formulas = listOf(
                        "N: 自然数集, Z: 整数集, Q: 有理数集, R: 实数集",
                    ),
                ),
                KnowledgePointTemplate(
                    id = "kp-set-operations",
                    name = "集合的基本运算",
                    definition = "交集、并集、补集的概念与运算",
                    boundaries = "有限集和无限集都适用",
                    prerequisites = listOf("kp-set-concept"),
                    followUps = listOf("kp-set-relations"),
                    commonMisconceptions = listOf(
                        "混淆交集与并集的符号",
                        "补集运算时忽略全集的概念",
                    ),
                    diagnosticQuestions = listOf(
                        "A={1,2,3}, B={2,3,4}, 求A∩B和A∪B",
                    ),
                    examples = listOf(
                        "已知A={x|x²-3x+2=0}, B={1,2,3}, 求A∩B",
                    ),
                    counterexamples = listOf(
                        "A={x|x>0}, B={x|x<0}, 则A∩B=∅",
                    ),
                    formulas = listOf(
                        "A∩B = {x|x∈A且x∈B}",
                        "A∪B = {x|x∈A或x∈B}",
                        "∁UA = {x|x∈U且x∉A}",
                    ),
                ),
            ),
        ),
        // 函数概念与基本初等函数
        TopicDefinition(
            topicId = "math-sh-function",
            name = "函数概念与基本初等函数",
            module = "函数",
            description = "函数的概念、定义域、值域、单调性、奇偶性；指数函数、对数函数、幂函数",
            knowledgePoints = listOf(
                KnowledgePointTemplate(
                    id = "kp-function-concept",
                    name = "函数的概念",
                    definition = "设A、B是非空的数集，如果按照某个确定的对应关系f，使对于集合A中的任意一个数x，在集合B中都有唯一确定的数f(x)和它对应，那么就称f：A→B为从集合A到集合B的一个函数",
                    boundaries = "适用于所有实数函数",
                    prerequisites = listOf("kp-set-concept"),
                    followUps = listOf("kp-function-domain", "kp-function-monotonicity"),
                    commonMisconceptions = listOf(
                        "认为函数必须用解析式表示",
                        "忽略定义域的重要性",
                    ),
                    diagnosticQuestions = listOf(
                        "y=1/x是否是函数？定义域是什么？",
                        "y=√x是否是函数？定义域是什么？",
                    ),
                    examples = listOf(
                        "求函数f(x)=√(x-1)的定义域",
                    ),
                    counterexamples = listOf(
                        "x²+y²=1不是函数（一个x对应两个y）",
                    ),
                    formulas = listOf(
                        "定义域: 使函数有意义的自变量的取值范围",
                        "值域: 函数值的集合",
                    ),
                ),
                KnowledgePointTemplate(
                    id = "kp-quadratic-formula",
                    name = "一元二次方程求根公式",
                    definition = "对于ax²+bx+c=0(a≠0)，x=(-b±√(b²-4ac))/(2a)",
                    boundaries = "适用于所有一元二次方程",
                    prerequisites = listOf("kp-function-concept"),
                    followUps = listOf("kp-quadratic-function"),
                    commonMisconceptions = listOf(
                        "忘记讨论a=0的情况",
                        "判别式Δ=b²-4ac的符号判断错误",
                    ),
                    diagnosticQuestions = listOf(
                        "解方程x²-5x+6=0",
                        "判断方程x²+2x+1=0的根的情况",
                    ),
                    examples = listOf(
                        "解方程2x²-3x-2=0",
                    ),
                    counterexamples = listOf(
                        "x²+1=0在实数范围内无解",
                    ),
                    formulas = listOf(
                        "Δ=b²-4ac",
                        "Δ>0: 两个不等实根",
                        "Δ=0: 两个相等实根",
                        "Δ<0: 无实根",
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
