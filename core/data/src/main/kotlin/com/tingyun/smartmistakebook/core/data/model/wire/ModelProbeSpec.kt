package com.tingyun.smartmistakebook.core.data.model.wire

/**
 * 能力探测的种类（spec 2026-09-08-multi-protocol §3.4）：探测请求体按协议构造，
 * 判定断言共用同一份令牌——两者必须取自本文件的同一常量，否则探测会自相矛盾。
 */
internal enum class ModelProbeKind {
    /** 结构化输出：要求模型只回一个 JSON 对象（无 json_object 信封的协议靠 prompt 约束）。 */
    STRUCTURED,

    /** 图片输入：要求模型读出合成图里的四字符码。 */
    IMAGE,

    /** 原生 tools 往返：仅对支持原生工具的协议（OpenAI 兼容）探测。 */
    TOOLS,
}

/** 探测共享的令牌、指令与合成图（请求体与断言同源）。 */
internal object ModelProbeSpec {
    const val STRUCTURED_TOKEN_FIELD = "capability_check"
    const val STRUCTURED_TOKEN = "SMART_MISTAKE_BOOK_STRUCTURED_V1"
    const val TOOLS_TOKEN = "SMART_MISTAKE_BOOK_TOOLS_V1"
    const val IMAGE_RESPONSE_TOKEN = "Q7M2"

    /**
     * 探测请求的 token 上限：推理型模型会先花 token 想思维链，32 的上限会把回复截断成空，
     * 探测就拿不到令牌。留足预算让检查可达。
     */
    const val PROBE_MAX_TOKENS = 512

    const val STRUCTURED_INSTRUCTION = "Return only one JSON object and no surrounding text."

    const val STRUCTURED_USER =
        "Synthetic compatibility check. Return exactly " +
            "{\"$STRUCTURED_TOKEN_FIELD\":\"$STRUCTURED_TOKEN\"}."

    const val IMAGE_USER =
        "This is a synthetic image check, not a real question. " +
            "Read the four-character code printed inside the border. Reply with that code only."

    const val TOOLS_USER =
        "Synthetic tools check. Call synthetic_compat_check with token = \"$TOOLS_TOKEN\"."

    /** 一张合法的小 PNG，上面画着 [IMAGE_RESPONSE_TOKEN] 四字符码。 */
    const val SYNTHETIC_IMAGE_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAKAAAABACAIAAAAS6ev4AAADIUlEQVR4nO3dP0vrUBjH8dPqUhAjxTr4BiwWpFoFNTUEghQRl4J/BgWnvglxFXwJUqouOohbOxhxcVBxySBKHIqLIlgsUSi6aJ47HAiS63S5pseH32fpOUkKR76cNkOKMSISwFe83QuAn9UpX2KxWHvXAf+d/GzGDmYOgZnrDM1xz/Xbhb5tsYOZQ2DmEJg5BGYOgZlDYOYQmDkEZg6BmUNg5hCYOQRmDoGZQ2DmEJg5BGZOocA7Ozu5XG5iYmJ0dHRvb08IsbS0ZJqmaZqTk5O9vb3yskQisbCwELxreXk5kUjI8fb29tTUVDabPT4+jn79iiKir09xUJscHR3puu55HhF5nqfr+unpaXC2XC6vr6/LsaZpQ0NDHx8fROT7/vj4uKZpRNRoNAzD+Pz8dF03nU5H/ycoIlz226PRsyzr/Pw8mJ6dnc3MzMix7/vZbPbp6UlONU1bXV29uLggIsdxSqWSDOy67sHBARG1Wq1UKhXx+tURSqnKR7TrusPDw8F0ZGTk5uZGjqvV6tjYWF9fX3C2UCjYti2EsG27UCjIg+l0en5+XghxeHg4NzcX3dIV92326PX397+/vwfTt7e3ZDIpx4Zh3N7eBqc0TWs2m/l8noimp6dfX1/lDpbq9Xomk2k0GhGtWz2hlKrs4Ewm4zhOMHUcZ3BwUAhxeXnZ09MzMDDw9eJkMhmPx+/v74UQ3d3dwfFWq7W4uFipVFKpVFQLV9632aN3cnKi6/rLywsReZ6Xz+drtRoRFYvFr3dbRCT368bGxsrKyubmZnDE9/1isbi/vx/52tUSShl+LrpdLMt6eHiwLKujo8N1XSHE3d1dvV5/fHw0DOPv62dnZ9fW1q6uroIju7u7tm03m82tra2urq5arRbd6hUWk82Dp6VJjQffn5+fr6+vTdNs90J+n1BKRQPDPwulVOUmC34IAjOHwMwhMHMIzBwCM4fAzCEwcwjMHAIzh8DMITBzCMwcAjOHwMwhMHMIzBwCM4fAzCEwcwjMHAIzh8DMITBzCMwcAjOHwMyFf12I/5DFDHYwcwjMXAy/F+UNO5i5P+gYTZ49ANt4AAAAAElFTkSuQmCC"
}
