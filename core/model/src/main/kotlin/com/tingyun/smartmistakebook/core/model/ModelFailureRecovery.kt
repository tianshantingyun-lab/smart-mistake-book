package com.tingyun.smartmistakebook.core.model

fun ModelFailureCode.requiresModelSettings(): Boolean = this in MODEL_SETTINGS_FAILURE_CODES

fun ModelFailureCode.requiresEgressAuthorizationRenewal(): Boolean =
    this == ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED ||
        this == ModelFailureCode.EGRESS_AUTHORIZATION_INVALID

/**
 * 这次失败能否靠"把同一条消息原样再发一次"解决。
 *
 * 这条判断存在的理由是一条具体的失败：学生发出消息后收到失败提示，而界面上**没有任何出口**
 * ——失败气泡只有一句通用文案，既不说原因也不给按钮，唯一的办法是重新把话打一遍（实测记录
 * 里学生就是这么做的：失败后手动补发了"3"和"第三题"两条新消息）。对这里的这些码，重新发送
 * 会带上原消息与原附图、以新的 attempt 重新签发授权，所以是真的可恢复。
 *
 * 不在集合里的码，重发必然逐字重放同一个失败：认证失败、模型未配置、能力不匹配要去设置里改；
 * 请求被上游拒绝（含"题图总量超过单次发送上限"）要先减小负载。
 */
fun ModelFailureCode.recoverableByResending(): Boolean = when (this) {
    ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED,
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
    ModelFailureCode.NETWORK_UNAVAILABLE,
    ModelFailureCode.SERVICE_UNAVAILABLE,
    ModelFailureCode.TIMEOUT,
    ModelFailureCode.RATE_LIMITED,
    ModelFailureCode.INVALID_RESPONSE,
    ModelFailureCode.UNKNOWN,
    -> true

    ModelFailureCode.MODEL_NOT_CONFIGURED,
    ModelFailureCode.AUTHENTICATION_FAILED,
    ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
    ModelFailureCode.PROVIDER_REJECTED_INPUT,
    -> false
}

private val MODEL_SETTINGS_FAILURE_CODES = setOf(
    ModelFailureCode.MODEL_NOT_CONFIGURED,
    ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
    ModelFailureCode.AUTHENTICATION_FAILED,
)
