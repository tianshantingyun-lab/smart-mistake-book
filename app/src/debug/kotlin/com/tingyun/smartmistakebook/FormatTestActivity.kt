package com.tingyun.smartmistakebook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme

/**
 * 调试活动 - 用于测试 Markdown 和数学公式显示效果
 *
 * 使用方法:
 * adb shell am start -n com.tingyun.smartmistakebook.localfirst/com.tingyun.smartmistakebook.FormatTestActivity
 */
class FormatTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartMistakeBookTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FormatTestScreen()
                }
            }
        }
    }
}

@Composable
private fun FormatTestScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "文字格式测试页面",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        TestSection(
            title = "1. 希腊字母测试",
            content = "角度 \$\\theta\$ 范围是 \$[0, 2\\pi]\$，速度 \$v\$ 与加速度 \$\\alpha\$ 成正比。" +
                    "设 \$\\epsilon > 0\$，\$\\phi\$ 和 \$\\psi\$ 为相位角，\$\\xi\$ 为阻尼比。",
        )

        TestSection(
            title = "2. 集合符号测试",
            content = "设 \$A \\subseteq B\$，且 \$x \\in A\$，则 \$x \\in B\$。" +
                    "集合运算：\$A \\cup B\$、\$A \\cap B\$、\$A \\subset B\$。",
        )

        TestSection(
            title = "3. 箭头符号测试",
            content = "函数映射 \$f: A \\to B\$，蕴含关系 \$P \\Rightarrow Q\$，" +
                    "等价关系 \$A \\Leftrightarrow B\$，可逆反应 \$A \\rightleftharpoons B\$。",
        )

        TestSection(
            title = "4. 微积分符号测试",
            content = "对于 \$\\forall x \\in \\mathbb{R}, \\exists \\delta > 0\$，" +
                    "使得梯度 \$\\nabla f\$，偏导数 \$\\frac{\\partial f}{\\partial x}\$，" +
                    "积分 \$\\int f(x) dx\$，求和 \$\\sum_{i=1}^{n} a_i\$。",
        )

        TestSection(
            title = "5. 几何符号测试",
            content = "在 \$\\triangle ABC\$ 中，\$\\angle BAC = \\frac{\\pi}{3}\$，" +
                    "直线 \$AB \\parallel CD\$，\$AB \\perp CD\$，\$\\triangle ABC \\cong \\triangle DEF\$。",
        )

        TestSection(
            title = "6. 粗体文字测试",
            content = "这道题的**关键点**是理解**二次函数的对称性**。" +
                    "首先要明确**顶点坐标公式**，然后运用**配方法**求解。" +
                    "**重点**：掌握函数图像的**平移变换**。",
        )

        TestSection(
            title = "7. 综合公式测试",
            content = "**导数定义**：\$f'(x) = \\lim_{\\Delta x \\to 0} \\frac{f(x+\\Delta x)-f(x)}{\\Delta x}\$\n\n" +
                    "**求解步骤**：\n" +
                    "1. 设 \$f(x) = x^2 + 2x + 1\$\n" +
                    "2. 求导得 \$f'(x) = 2x + 2\$\n" +
                    "3. 令 \$f'(x) = 0\$，得 \$x = -1\$\n\n" +
                    "**结论**：函数在 \$x = -1\$ 处取得**最小值**。",
        )

        TestSection(
            title = "8. 复杂符号组合",
            content = "设向量 \$\\vec{a} = (1, 2)\$，\$\\vec{b} = (3, 4)\$，则：\n" +
                    "- 模长：\$|\\vec{a}| = \\sqrt{1^2 + 2^2} = \\sqrt{5}\$\n" +
                    "- 点积：\$\\vec{a} \\cdot \\vec{b} = 1 \\times 3 + 2 \\times 4 = 11\$\n" +
                    "- 夹角：\$\\cos\\theta = \\frac{\\vec{a} \\cdot \\vec{b}}{|\\vec{a}||\\vec{b}|}\$",
        )
    }
}

@Composable
private fun TestSection(title: String, content: String) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SafeMarkdownText(
            markdown = content,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
