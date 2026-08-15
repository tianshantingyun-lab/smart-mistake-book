package com.tingyun.smartmistakebook.core.model

/**
 * 手动测试脚本 - 验证新增的数学符号转换
 */
fun main() {
    println("=== 数学符号转换测试 ===\n")

    val testCases = listOf(
        // 新增的希腊字母
        "\\epsilon" to "ε",
        "\\phi" to "φ",
        "\\psi" to "ψ",
        "\\xi" to "ξ",
        "\\theta" to "θ",

        // 箭头
        "\\to" to "→",
        "\\rightarrow" to "→",
        "\\Rightarrow" to "⇒",

        // 集合
        "\\in" to "∈",
        "\\subseteq" to "⊆",
        "\\cup" to "∪",
        "\\cap" to "∩",

        // 逻辑/微积分
        "\\forall" to "∀",
        "\\exists" to "∃",
        "\\nabla" to "∇",
        "\\partial" to "∂",

        // 几何
        "\\angle" to "∠",
        "\\triangle" to "△",
        "\\parallel" to "∥",

        // 复杂公式
        "f(x) = x^2" to "f(x) = x²",
        "\\frac{a}{b}" to "(a)/(b)",
        "\\sqrt{x}" to "√(x)",
    )

    var passed = 0
    var failed = 0

    testCases.forEach { (input, expected) ->
        val result = ReadableMathText.formula(input)
        val success = result.contains(expected)

        if (success) {
            println("✅ $input → $result")
            passed++
        } else {
            println("❌ $input → $result (期望包含: $expected)")
            failed++
        }
    }

    println("\n=== 测试结果 ===")
    println("通过: $passed")
    println("失败: $failed")
    println("总计: ${testCases.size}")

    // 综合测试
    println("\n=== 综合公式测试 ===")
    val complexFormulas = listOf(
        "\\forall \\epsilon > 0, \\exists \\delta > 0",
        "A \\subseteq B \\Rightarrow A \\cap B = A",
        "\\theta \\in [0, \\pi]",
        "f'(x) = \\lim_{\\Delta x \\to 0} \\frac{f(x+\\Delta x)-f(x)}{\\Delta x}",
        "\\nabla \\cdot \\vec{F}",
        "\\angle ABC = \\frac{\\pi}{3}",
    )

    complexFormulas.forEach { formula ->
        val result = ReadableMathText.formula(formula)
        println("$formula")
        println("  → $result")
        println()
    }
}
